# WebSocket RFC 6455 协议详解

## 目录
1. [协议概述](#协议概述)
2. [握手过程](#握手过程)
3. [数据帧格式](#数据帧格式)
4. [帧类型详解](#帧类型详解)
5. [掩码处理](#掩码处理)
6. [分片机制](#分片机制)
7. [连接关闭](#连接关闭)
8. [心跳机制](#心跳机制)
9. [实现要点](#实现要点)

## 协议概述

WebSocket是一种在单个TCP连接上进行全双工通信的协议，基于HTTP协议升级而来。

**特点：**
- 全双工通信
- 低延迟
- 减少HTTP头部开销
- 支持文本和二进制数据

## 握手过程

### 客户端请求
```
GET /chat HTTP/1.1
Host: server.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Origin: http://example.com
Sec-WebSocket-Protocol: chat, superchat
Sec-WebSocket-Version: 13
```

### 服务端响应
```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
Sec-WebSocket-Protocol: chat
```

**关键字段说明：**
- `Sec-WebSocket-Key`: 客户端生成的随机Base64编码值
- `Sec-WebSocket-Accept`: 服务端计算的响应值，算法：`base64(sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`

## 数据帧格式

### 基本帧结构（RFC 6455）

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - - +
:                     Payload Data continued ...                :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

### 字段详解

**第1字节：**
- `FIN` (1 bit): 1表示这是消息的最后一帧，0表示还有后续帧
- `RSV1, RSV2, RSV3` (各1 bit): 保留位，必须为0（除非扩展协议）
- `Opcode` (4 bits): 操作码，定义帧类型

**第2字节：**
- `MASK` (1 bit): 1表示数据被掩码处理，0表示未掩码
- `Payload length` (7 bits): 负载长度标识

**负载长度编码规则：**
- 0-125: 直接表示长度
- 126: 后续2字节表示16位长度
- 127: 后续8字节表示64位长度

**掩码键（如果MASK=1）：**
- 4字节的掩码键，用于数据解密

## 帧类型详解

### 操作码（Opcode）定义

| Opcode | 名称 | 说明 |
|--------|------|------|
| 0x0 | Continuation Frame | 连续帧 |
| 0x1 | Text Frame | 文本帧 |
| 0x2 | Binary Frame | 二进制帧 |
| 0x8 | Connection Close | 连接关闭 |
| 0x9 | Ping | 心跳请求 |
| 0xA | Pong | 心跳响应 |

### 控制帧 vs 数据帧

**控制帧（0x8, 0x9, 0xA）：**
- 长度必须小于125字节
- 不能被分片
- 可以插入到分片消息之间

**数据帧（0x0, 0x1, 0x2）：**
- 可以是任意长度
- 支持分片传输

## 掩码处理

### 掩码算法
当MASK位为1时，数据需要进行异或解密：

```
解密公式：decoded-octet = encoded-octet XOR masking-key-octet
其中：masking-key-octet = masking-key[octet-index % 4]
```

### 示例
假设掩码键为 `[0x37, 0xfa, 0x21, 0x3d]`，原始数据为 `"Hello"`：

```
原始: H    e    l    l    o
HEX:  48   65   6C   6C   6F
索引: 0    1    2    3    4

掩码: 37   fa   21   3d   37  (循环使用)
结果: 7F   9F   4D   51   58
```

## 分片机制

### 分片传输示例

发送长消息 "Hello World!" 分为3个片段：

**片段1（开始帧）：**
```
FIN=0, opcode=0x1, payload="Hello "
```

**片段2（连续帧）：**
```
FIN=0, opcode=0x0, payload="World"
```

**片段3（结束帧）：**
```
FIN=1, opcode=0x0, payload="!"
```

### 分片规则
- 第一帧设置opcode，后续帧opcode为0x0
- 只有最后一帧FIN=1
- 控制帧不能分片

## 连接关闭

### 关闭帧格式
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       | Status Code (optional)        |
+-------------------------------- - - - - - - - - - - - - - - - +
| Status Code (continued)       |    Reason Phrase (optional)   |
+-------------------------------- - - - - - - - - - - - - - - - +
:                     Reason Phrase continued ...               :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

### 状态码定义

| 状态码 | 含义 | 说明 |
|--------|------|------|
| 1000 | Normal Closure | 正常关闭 |
| 1001 | Going Away | 端点离开 |
| 1002 | Protocol Error | 协议错误 |
| 1003 | Unsupported Data | 不支持的数据类型 |
| 1007 | Invalid Frame Payload Data | 消息格式无效 |
| 1008 | Policy Violation | 违反政策 |
| 1009 | Message Too Big | 消息过大 |
| 1011 | Internal Server Error | 服务器内部错误 |

## 心跳机制

### Ping/Pong帧
- 用于连接保活
- Pong帧必须回应对应的Ping帧
- 控制帧，长度<125字节

### 交互流程
```
客户端 -> 服务器: Ping帧 [0x89][0x05]["Hello"]
服务器 -> 客户端: Pong帧 [0x8A][0x05]["Hello"]
```

## 实现要点

### 1. 帧解析状态机

```java
private int framePos = 0;        // 解析位置
private byte[] frameBuffer;      // 帧数据缓冲区
private int payloadLen = 0;      // 负载长度
private boolean mask = false;    // 是否掩码
private byte[] maskingKey;       // 掩码键
private int opcode = 0;          // 操作码
private boolean fin = false;     // 是否结束帧
```

### 2. 状态解析流程

```
状态0: 解析FIN/RSV/Opcode
状态1: 解析MASK/Payload Length
状态2-9: 解析扩展长度
状态10-13: 解析掩码键
状态>=14: 解析负载数据
```

### 3. 接收报文示例

**接收文本消息 "Hello"（客户端->服务端）：**
```
十六进制: 81 85 37 fa 21 3d 7f 9f 4d 51 58
二进制:   
10000001 10000101 00110111 11111010 00100001 00111101 01111111 10011111 01001101 01010001 01011000

解析:
- 10000001: FIN=1, RSV=000, Opcode=0001(TEXT)
- 10000101: MASK=1, Length=5
- 00110111 11111010 00100001 00111101: 掩码键
- 01111111 10011111 01001101 01010001 01011000: 掩码后数据
```

**解掩码后得到:** "Hello"

### 4. 发送报文示例

**发送文本消息 "Hello"（服务端->客户端）：**
```
十六进制: 81 05 48 65 6c 6c 6f
二进制:
10000001 00000101 01001000 01100101 01101100 01101100 01101111

解析:
- 10000001: FIN=1, RSV=000, Opcode=0001(TEXT)
- 00000101: MASK=0, Length=5
- 01001000 01100101 01101100 01101100 01101111: 明文数据 "Hello"
```

### 5. 分片报文示例

**发送 "Hello World!" 分三片：**

**第一片：**
```
十六进制: 01 06 48 65 6c 6c 6f 20
解析: FIN=0, Opcode=0001(TEXT), Length=6, Data="Hello "
```

**第二片：**
```
十六进制: 00 05 57 6f 72 6c 64
解析: FIN=0, Opcode=0000(CONT), Length=5, Data="World"
```

**第三片：**
```
十六进制: 80 01 21
解析: FIN=1, Opcode=0000(CONT), Length=1, Data="!"
```

### 6. 控制帧报文示例

**Ping帧：**
```
十六进制: 89 05 48 65 6c 6c 6f
解析: FIN=1, Opcode=1001(PING), Length=5, Data="Hello"
```

**Pong帧响应：**
```
十六进制: 8a 05 48 65 6c 6c 6f
解析: FIN=1, Opcode=1010(PONG), Length=5, Data="Hello"
```

**关闭帧：**
```
十六进制: 88 02 03 e8
解析: FIN=1, Opcode=1000(CLOSE), Length=2, StatusCode=1000
```

## 调试技巧

### 1. 抓包分析
使用Wireshark捕获WebSocket流量，过滤条件：
```
tcp.port == 8080 && websocket
```

### 2. 十六进制查看
```
echo -n "Hello" | xxd -p
# 输出: 48656c6c6f
```

### 3. 掩码验证
```bash
# Python验证掩码
python3 -c "
data = bytearray([0x48, 0x65, 0x6c, 0x6c, 0x6f])
mask = [0x37, 0xfa, 0x21, 0x3d]
result = bytearray(len(data))
for i in range(len(data)):
    result[i] = data[i] ^ mask[i % 4]
print(' '.join(f'{b:02x}' for b in result))
"
```

## 注意事项

1. **字节序**：网络传输使用大端序（Big Endian）
2. **掩码要求**：客户端发送给服务端的数据必须掩码，服务端发送给客户端的数据不能掩码
3. **错误处理**：遇到协议错误应发送关闭帧并断开连接
4. **缓冲区管理**：合理管理接收缓冲区，避免内存溢出
5. **线程安全**：多线程环境下注意帧解析的状态同步

## 参考资料

- [RFC 6455 - The WebSocket Protocol](https://tools.ietf.org/html/rfc6455)
- [WebSocket API - MDN](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API)
- [WebSocket浏览器兼容性](https://caniuse.com/websockets)