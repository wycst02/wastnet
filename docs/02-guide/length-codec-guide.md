# 内置编解码器 - LengthFrameCodec / ObjectCodec / ObjectProtocol

> **包路径说明**：以下代码示例使用的类均位于 `io.github.wycst.wastnet` 包下：
> - `TCPServer` → `io.github.wycst.wastnet.socket.tcp.TCPServer`
> - `TCPClient` → `io.github.wycst.wastnet.socket.tcp.TCPClient`
> - `ChannelHandler`、`ChannelContext` → `io.github.wycst.wastnet.socket.handler.*`
> - `LengthFrameCodec` → `io.github.wycst.wastnet.socket.channel.LengthFrameCodec`
> - `ObjectCodec`、`ObjectProtocol` → `io.github.wycst.wastnet.socket.protocol.ObjectCodec`、`io.github.wycst.wastnet.socket.protocol.ObjectProtocol`

## 概述

三个核心目标：

1. **解决粘包** — TCP 是流协议，通过长度前缀自动切帧，业务层只收到完整消息
2. **无状态** — Codec 实例所有字段 `final`，多连接共享安全
3. **简化代码** — 一行配置拆帧规则，覆盖几个方法即可定制协议

## 一、LengthFrameCodec — 长度前缀帧基类

### 设计

```
[Length N bytes][Payload...]
```

- **Length**：Body 长度（或包含头部的总长度），大端/小端可配
- **Payload**：长度字段对应的内容

### 构造参数

| 参数 | 说明 |
|------|------|
| `headerLength` | 头部总长度 |
| `lengthFieldOffset` | 长度字段在头部中的偏移 |
| `lengthFieldLength` | 长度字段字节数（1~4） |
| `maxFrameLength` | 最大允许的总帧长（header + body + trailer） |
| `lengthIncludesHeader` | 长度值是否包含头部 |

```java
// 4字节长度前缀，长度表示 body 大小
new LengthFrameCodec<byte[]>(4, 0, 4, 65536);

// 12字节头部，长度字段在第5~8字节，长度4字节
new LengthFrameCodec<byte[]>(12, 4, 4, 65536, false);
```

### 可覆盖的方法

| 方法 | 用途 |
|------|------|
| `encodeBody(ChannelContext ctx, T)` | 对象 → 字节数组 |
| `createHeader(int)` | 构造帧头部（默认填充零，可写自定义字段） |
| `onFrame(ctx, header, body, trailer)` | 处理完整帧（trailer 为 0~4 字节尾部，可为 null） |
| `onProtocolError(ctx, cause)` | 协议错误回调 |
| `readLength / writeLength` | 自定义长度编解码（如变长编码） |

### 大小端配置

```java
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<>(...)
    .byteOrder(ByteOrder.LITTLE_ENDIAN);
```

---

## 二、ObjectCodec — 对象编解码器

`ObjectCodec` 继承 `LengthFrameCodec`，预定义 12 字节帧头 + 可插拔序列化。

### 帧格式

```
字节:  0 ~ 3       4 ~ 7         8 ~ 11        12 ~ N      N+1 ~ N+2
     [Magic 4B]  [BodyLength 4B] [SeqID 4B]  [Payload...]  [CRC16 2B]
```

| 字段 | 说明 |
|------|------|
| Magic | 帧标识（默认 `0x57534E54` = "WSNT"） |
| BodyLength | body 长度（不含头部和尾部），4B int |
| SeqID | 自增序号，用于请求-应答关联 |
| Payload | 序列化后的业务数据 |
| CRC16 | 帧边界完整性校验尾（默认种子值，可自定义） |

### 构造参数

| 参数 | 说明 |
|------|------|
| `maxFrameLength` | 最大允许的总帧长（header + body + trailer） |
| `protocol` | ObjectProtocol 实现 |
| `magic` | 4 字节帧标识（int） |
| `validate` | 是否校验接收帧的 Magic 与 frameTrailer |
| `lengthIncludesHeader` | 长度字段是否包含头部 |
| `frameTrailer` | 帧边界完整性校验种子（short，默认 0） |

```java
// 默认 Magic + 不校验
ObjectCodec<Message> codec = new ObjectCodec<>(65536, protocol);

// 自定义 Magic + 开启校验
ObjectCodec<Message> codec = new ObjectCodec<>(65536, protocol, 0x12345678, true);
```

### 特性

- **无状态**：所有字段 `final`，一个实例可共享给多个连接
- **自动 SeqID**：每帧自动生成序号，存入 `ctx.setAttribute("_seq", id)`
- **协议错误**：Magic 校验失败时触发 `onProtocolError`，默认断连

---

## 三、ObjectProtocol — 序列化接口

```java
public interface ObjectProtocol {
    byte[] encode(Object obj) throws Exception;
    Object  decode(byte[] data) throws Exception;
}
```

只需两个方法，适配任意序列化协议（JSON / JSONB / Fury 等）。

### 集成 Fastjson2 JSON

```java
import com.alibaba.fastjson2.JSON;

ObjectProtocol protocol = new ObjectProtocol() {
    public byte[] encode(Object obj) { return JSON.toJSONBytes(obj); }
    public Object decode(byte[] data) {
        return JSON.parseObject(data, Message.class);
    }
};
```

### 集成 Jackson JSON

```java
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper mapper = new ObjectMapper();
ObjectProtocol protocol = new ObjectProtocol() {
    public byte[] encode(Object obj) throws IOException { return mapper.writeValueAsBytes(obj); }
    public Object decode(byte[] data) throws IOException {
        return mapper.readValue(data, Message.class);
    }
};
```

### 集成 Fastjson2 JSONB（推荐）

```java
import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;

ObjectProtocol protocol = new ObjectProtocol() {
    public byte[] encode(Object obj) { return JSONB.toBytes(obj); }
    public Object decode(byte[] data) {
        return JSONB.parseObject(data, Message.class, JSONReader.Feature.FieldBased);
    }
};
```

### 集成 Fury

```java
import org.apache.fury.Fury;
import org.apache.fury.config.Language;

Fury fury = Fury.builder()
    .withLanguage(Language.JAVA)
    .requireClassRegistration(false)
    .build();
fury.register(Message.class);

ObjectProtocol protocol = new ObjectProtocol() {
    public byte[] encode(Object obj) { return fury.serialize(obj); }
    public Object decode(byte[] data) { return fury.deserialize(data); }
};
```

---

## 四、完整示例

### 服务端

```java
new TCPServer(9096)
    .channelCodec(new ObjectCodec<>(65536, protocol))
    .channelHandler(new ChannelHandler<Message>() {
        public void onHandle(ChannelContext ctx, Message msg) throws IOException {
            System.out.println("received: " + msg);
            ctx.send(new Message("echo:" + msg.content));
        }
    })
    .start();
```

### 客户端

```java
TCPClient<Message> client = new TCPClient<Message>("localhost", 9096)
    .channelCodec(new ObjectCodec<>(65536, protocol))
    .channelHandler(new ChannelHandler<Message>() {
        public void onHandle(ChannelContext ctx, Message msg) {
            System.out.println("received: " + msg);
        }
    })
    .connect();
client.send(new Message("hello"));
```

---

## 五、常见问题

### ObjectCodec 能否多连接共享？

可以。所有字段 `final`，无状态，一个实例可安全共享。

### 如何关闭 Magic 校验？

```java
new ObjectCodec<>(65536, protocol);  // 默认 false
// 或显式指定
new ObjectCodec<>(65536, protocol, 0x57534E54, false);
```

### 如何获取接收帧的 SeqID？

```java
public void onHandle(ChannelContext ctx, Message msg) {
    int seqId = (Integer) ctx.getAttribute("_seq");
}
```

### 协议错误如何自定义处理？

```java
ObjectCodec<Message> codec = new ObjectCodec<Message>(65536, protocol) {
    @Override
    protected void onProtocolError(ChannelContext ctx, Throwable cause) {
        System.err.println("protocol error: " + cause.getMessage());
        super.onProtocolError(ctx, cause);  // 默认断连
    }
};
```

---

## 六、类层次

```
ChannelCodec<T>
  └── LengthFrameCodec<T>           ← 长度前缀帧协议
        ├── byteOrder(ByteOrder)     ← 大小端
        ├── readLength / writeLength ← 长度字段
        ├── readInt / writeInt       ← 4B int 工具 (final)
        ├── onFrame / onProtocolError
        └── encodeBody / createHeader
              └── ObjectCodec<T>     ← 对象协议
                    ├── 12B 头: Magic + BodyLength + SeqID
                    ├── ObjectProtocol 可插拔
                    └── 无状态 final 字段
```

## 七、数据流

```
编码： send(obj) → protocol.encode(obj) → byte[]
       → createHeader → [Magic][Len][SeqID]
       → write(header + body) → flush

解码： byte[] → readLength() → 读 body 长度
       → onFrame(header, body, trailer)
       → 校验 Magic / trailer? → 提取 SeqID → ctx.setAttribute("_seq", id)
       → protocol.decode(body) → T → ctx.invokeHandle(T)
```
