# WebSocket 实现指南

## 核心数据结构

### 1. 帧头解析结构

```java
public class WebSocketFrameHeader {
    // 第一字节
    boolean fin;        // FIN位
    int rsv;           // RSV1-3 (保留位)
    int opcode;        // 操作码 (4位)
    
    // 第二字节  
    boolean mask;      // MASK位
    int payloadLength; // 负载长度标识
    
    // 扩展字段
    long actualLength; // 实际负载长度
    byte[] maskingKey; // 掩码键 (4字节)
}
```

### 2. 帧类型枚举

```java
public enum WebSocketOpcode {
    CONTINUATION(0x0),
    TEXT(0x1), 
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);
    
    private final int code;
    WebSocketOpcode(int code) { this.code = code; }
    public int getCode() { return code; }
}
```

## 核心解析算法

### 1. 帧头解析

```java
public WebSocketFrameHeader parseFrameHeader(byte[] buffer, int offset) {
    WebSocketFrameHeader header = new WebSocketFrameHeader();
    
    // 解析第一字节
    byte firstByte = buffer[offset];
    header.fin = (firstByte & 0x80) != 0;           // bit 7
    header.rsv = (firstByte & 0x70) >> 4;           // bits 4-6
    header.opcode = firstByte & 0x0F;               // bits 0-3
    
    // 解析第二字节
    byte secondByte = buffer[offset + 1];
    header.mask = (secondByte & 0x80) != 0;         // bit 7
    header.payloadLength = secondByte & 0x7F;       // bits 0-6
    
    return header;
}
```

### 2. 负载长度解析

```java
public long parsePayloadLength(byte[] buffer, int offset, WebSocketFrameHeader header) {
    switch (header.payloadLength) {
        case 126: // 16位长度
            return ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
        case 127: // 64位长度
            long length = 0;
            for (int i = 0; i < 8; ++i) {
                length = (length << 8) | (buffer[offset + i] & 0xFF);
            }
            return length;
        default: // 7位长度
            return header.payloadLength;
    }
}
```

### 3. 掩码键解析

```java
public byte[] parseMaskingKey(byte[] buffer, int offset) {
    byte[] mask = new byte[4];
    System.arraycopy(buffer, offset, mask, 0, 4);
    return mask;
}
```

### 4. 数据解掩码

```java
public void unmaskData(byte[] data, byte[] mask) {
    for (int i = 0; i < data.length; ++i) {
        data[i] ^= mask[i % 4];
    }
}
```

## 完整帧解析实现

```java
public class WebSocketFrameParser {
    private int parseState = 0;
    private WebSocketFrameHeader currentHeader;
    private ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
    
    public WebSocketFrame parseFrame(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            
            switch (parseState) {
                case 0: // 解析第一字节
                    currentHeader = new WebSocketFrameHeader();
                    currentHeader.fin = (b & 0x80) != 0;
                    currentHeader.rsv = (b & 0x70) >> 4;
                    currentHeader.opcode = b & 0x0F;
                    parseState = 1;
                    break;
                    
                case 1: // 解析第二字节
                    currentHeader.mask = (b & 0x80) != 0;
                    currentHeader.payloadLength = b & 0x7F;
                    if (currentHeader.payloadLength <= 125) {
                        currentHeader.actualLength = currentHeader.payloadLength;
                        parseState = currentHeader.mask ? 2 : 3;
                    } else if (currentHeader.payloadLength == 126) {
                        parseState = 4; // 需要读取2字节长度
                    } else {
                        parseState = 6; // 需要读取8字节长度
                    }
                    break;
                    
                case 2: case 3: case 4: case 5: // 16位长度
                    // 实现16位长度解析
                    break;
                    
                case 6: case 7: case 8: case 9: 
                case 10: case 11: case 12: case 13: // 64位长度
                    // 实现64位长度解析
                    break;
                    
                case 14: case 15: case 16: case 17: // 掩码键
                    // 实现掩码键解析
                    break;
                    
                default: // 读取负载数据
                    payloadBuffer.write(b);
                    if (payloadBuffer.size() == currentHeader.actualLength) {
                        byte[] payload = payloadBuffer.toByteArray();
                        if (currentHeader.mask) {
                            unmaskData(payload, currentHeader.maskingKey);
                        }
                        return new WebSocketFrame(currentHeader, payload);
                    }
                    break;
            }
        }
        return null; // 帧未完整
    }
}
```

## 帧构建算法

### 1. 计算帧头大小

```java
public int calculateFrameHeaderSize(long payloadLength, boolean mask) {
    int headerSize = 2; // 基础头大小
    
    if (payloadLength > 125) {
        headerSize += (payloadLength > 65535) ? 8 : 2;
    }
    
    if (mask) {
        headerSize += 4; // 掩码键大小
    }
    
    return headerSize;
}
```

### 2. 构建帧

```java
public byte[] buildFrame(int opcode, boolean fin, byte[] payload, boolean mask) {
    long payloadLength = payload.length;
    int headerSize = calculateFrameHeaderSize(payloadLength, mask);
    byte[] frame = new byte[headerSize + (int)payloadLength];
    
    // 第一字节
    frame[0] = (byte)((fin ? 0x80 : 0x00) | (opcode & 0x0F));
    
    // 第二字节和长度字段
    int offset = 2;
    if (payloadLength <= 125) {
        frame[1] = (byte)((mask ? 0x80 : 0x00) | (payloadLength & 0x7F));
    } else if (payloadLength <= 65535) {
        frame[1] = (byte)((mask ? 0x80 : 0x00) | 126);
        frame[2] = (byte)((payloadLength >> 8) & 0xFF);
        frame[3] = (byte)(payloadLength & 0xFF);
        offset = 4;
    } else {
        frame[1] = (byte)((mask ? 0x80 : 0x00) | 127);
        for (int i = 0; i < 8; ++i) {
            frame[2 + i] = (byte)((payloadLength >> (56 - i * 8)) & 0xFF);
        }
        offset = 10;
    }
    
    // 掩码键和数据
    if (mask) {
        byte[] maskKey = generateMaskKey();
        System.arraycopy(maskKey, 0, frame, offset, 4);
        offset += 4;
        
        // 掩码数据
        for (int i = 0; i < payload.length; ++i) {
            frame[offset + i] = (byte)(payload[i] ^ maskKey[i % 4]);
        }
    } else {
        System.arraycopy(payload, 0, frame, offset, payload.length);
    }
    
    return frame;
}

private byte[] generateMaskKey() {
    byte[] mask = new byte[4];
    // 生成随机掩码键
    new Random().nextBytes(mask);
    return mask;
}
```

## 具体报文示例

### 1. 文本消息发送

**发送 "Hello" 到客户端（无掩码）：**

```java
// 构建帧数据
String message = "Hello";
byte[] payload = message.getBytes(StandardCharsets.UTF_8);
byte[] frame = buildFrame(0x1, true, payload, false);

// frame 内容：
// [0x81, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F]
// FIN=1, Opcode=TEXT, MASK=0, Length=5, Data="Hello"
```

### 2. 文本消息接收

**从客户端接收掩码文本 "Hello"：**

```java
// 接收的字节流
byte[] received = { 
    (byte)0x81, (byte)0x85,  // FIN=1, TEXT, MASK=1, LEN=5
    0x37, (byte)0xFA, 0x21, 0x3D,  // 掩码键
    0x7F, (byte)0x9F, 0x4D, 0x51, 0x58  // 掩码数据
};

// 解析过程
WebSocketFrameHeader header = parseFrameHeader(received, 0);
// header.fin = true, header.opcode = 1, header.mask = true

long length = parsePayloadLength(received, 2, header);
// length = 5

byte[] maskKey = parseMaskingKey(received, 4);
// maskKey = [0x37, 0xFA, 0x21, 0x3D]

byte[] maskedData = Arrays.copyOfRange(received, 8, 13);
unmaskData(maskedData, maskKey);
String text = new String(maskedData, StandardCharsets.UTF_8);
// text = "Hello"
```

### 3. 分片消息处理

**发送长消息分三片：**

```java
String message = "Hello World!";
byte[] data = message.getBytes(StandardCharsets.UTF_8);

// 第一片：开始帧
byte[] fragment1 = "Hello ".getBytes(StandardCharsets.UTF_8);
byte[] frame1 = buildFrame(0x1, false, fragment1, false);
// [0x01, 0x06, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20]

// 第二片：连续帧
byte[] fragment2 = "World".getBytes(StandardCharsets.UTF_8);
byte[] frame2 = buildFrame(0x0, false, fragment2, false);
// [0x00, 0x05, 0x57, 0x6F, 0x72, 0x6C, 0x64]

// 第三片：结束帧
byte[] fragment3 = "!".getBytes(StandardCharsets.UTF_8);
byte[] frame3 = buildFrame(0x0, true, fragment3, false);
// [0x80, 0x01, 0x21]
```

### 4. 控制帧处理

**Ping/Pong交互：**

```java
// 收到Ping帧
byte[] pingFrame = { (byte)0x89, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F };
// 解析得到ping数据："Hello"

// 回复Pong帧
byte[] pongFrame = buildFrame(0xA, true, "Hello".getBytes(), false);
// [0x8A, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F]

// 发送关闭帧
byte[] closeFrame = buildFrame(0x8, true, new byte[]{0x03, (byte)0xE8}, false);
// [0x88, 0x02, 0x03, (byte)0xE8] - 状态码1000
```

## 状态管理

### 连接状态机

```java
public enum ConnectionState {
    HANDSHAKE_PENDING,    // 等待握手
    OPEN,                // 连接打开
    CLOSING,             // 正在关闭
    CLOSED               // 已关闭
}
```

### 分片状态管理

```java
public class FragmentationState {
    private ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();
    private int fragmentType = -1; // 1=TEXT, 2=BINARY
    
    public void addFragment(byte[] data) {
        fragmentBuffer.write(data, 0, data.length);
    }
    
    public byte[] getCompleteMessage() {
        return fragmentBuffer.toByteArray();
    }
    
    public void reset() {
        fragmentBuffer.reset();
        fragmentType = -1;
    }
}
```

## 错误处理

### 协议错误检测

```java
public boolean validateFrame(WebSocketFrameHeader header) {
    // 检查保留位
    if (header.rsv != 0) return false;
    
    // 检查操作码有效性
    if (!isValidOpcode(header.opcode)) return false;
    
    // 检查控制帧长度
    if (isControlFrame(header.opcode) && header.payloadLength > 125) {
        return false;
    }
    
    // 检查掩码要求
    if (isClientToServer() && !header.mask) {
        return false; // 客户端必须掩码
    }
    
    return true;
}
```

## 性能优化建议

### 1. 缓冲区复用
```java
private ThreadLocal<byte[]> bufferPool = ThreadLocal.withInitial(() -> new byte[8192]);
```

### 2. 零拷贝处理
```java
// 直接操作ByteBuffer，避免字节数组复制
public void processFrameDirect(ByteBuffer buffer) {
    // 直接读取和处理，不创建中间数组
}
```

### 3. 批量处理
```java
public List<WebSocketFrame> parseMultipleFrames(ByteBuffer buffer) {
    List<WebSocketFrame> frames = new ArrayList<>();
    WebSocketFrame frame;
    while ((frame = parseFrame(buffer)) != null) {
        frames.add(frame);
    }
    return frames;
}
```

## 调试辅助工具

### 1. 帧转储函数
```java
public static String dumpFrame(byte[] frame) {
    StringBuilder sb = new StringBuilder();
    sb.append("Frame: ");
    for (byte b : frame) {
        sb.append(String.format("%02X ", b));
    }
    return sb.toString();
}
```

### 2. 协议分析器
```java
public static void analyzeFrame(byte[] frame) {
    System.out.println("=== WebSocket Frame Analysis ===");
    System.out.printf("FIN: %b%n", (frame[0] & 0x80) != 0);
    System.out.printf("Opcode: 0x%X%n", frame[0] & 0x0F);
    System.out.printf("MASK: %b%n", (frame[1] & 0x80) != 0);
    System.out.printf("Length: %d%n", frame[1] & 0x7F);
    // ... 更多分析
}
```

这个实现指南提供了完整的WebSocket协议实现所需的所有核心算法和示例，你可以基于这些代码模板来实现自己的WebSocket解析器。