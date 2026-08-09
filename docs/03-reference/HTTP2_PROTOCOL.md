# HTTP/2 协议详解

## 1. 连接建立流程

```
客户端                              服务端
   |                                    |
   |------- TCP 连接 --------------------|
   |                                    |
   |------- PRI * HTTP/2.0\r\n\r\nSM --→|  PREFACE (24字节)
   |        \r\n\r\n                       |
   |←------ SETTINGS (ACK) ------------|  空 SETTINGS + ACK
   |                                    |
   |------- SETTINGS (ACK) ------------→|  可选：客户端 SETTINGS
   |------- HEADERS (请求) ------------→|
   |------- DATA (请求体) ------------→|
   |------- WINDOW_UPDATE ------------→|  流量控制
   |                                    |
   |←------- HEADERS (响应) ------------|  200 OK
   |←------- DATA (响应体) -------------|  可选
   |------- WINDOW_UPDATE ------------→|
   |------- GOAWAY --------------------|  可选：关闭连接
```

### 1.1 PREFACE 常量

HTTP/2 连接 preface 用于识别协议：

```java
static final byte[] PREFACE_MAGIC = {
    0x50, 0x52, 0x49, 0x20,     // "PRI"
    0x2A, 0x20, 0x48, 0x54,     // "* HT"
    0x54, 0x50, 0x2F, 0x32,     // "TP/2"
    0x2E, 0x30, 0x0D, 0x0A,     // ".0\r\n"
    0x0D, 0x0A, 0x53, 0x4D,     // "\r\nSM"
    0x0D, 0x0A                      // "\r\n"
};
// 共 24 字节
// 字符串形式: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
```

### 1.2 SETTINGS 帧 (握手)

连接建立时，双方需要交换 SETTINGS：

```java
// 服务端发送空 SETTINGS + ACK
byte[] serverPreface = {
    // 帧头 (9字节)
    0x00, 0x00, 0x00,  // Length = 0
    0x04,               // Type = SETTINGS
    0x01,               // Flags = ACK
    0x00, 0x00, 0x00, 0x00  // Stream ID = 0 (连接级)
};
```

---

## 2. 帧结构

### 2.1 帧格式

```
+-----------+------------+----------+-----------+
|  Length (24 bits)  | Type (8) | Flags (8) |
+-----------+------------+---------------------+
|         Stream Identifier (31 bits)         |
+---------------------------------------------+

+-----------------+-----------------------------+
|              Payload (Length bytes)            |
+---------------------------------------------+

字节偏移:  0-2        3        4           5-8
```

### 2.2 帧头详解

| 字段 | 长度 | 说明 |
|------|------|------|
| Length | 3字节 | Payload 长度 (0-16384) |
| Type | 1字节 | 帧类型 |
| Flags | 1字节 | 帧标志 |
| Stream | 4字节 | 流标识符 (31位有效) |

### 2.3 帧头字节序

```java
// Length 解析 (大端序)
int length = ((buf[0] & 0xFF) << 16)
           | ((buf[1] & 0xFF) << 8)
           | (buf[2] & 0xFF);

// Type 解析
int type = buf[3] & 0xFF;

// Flags 解析
int flags = buf[4] & 0xFF;

// Stream ID 解析 (高位置0，保留位忽略)
int streamId = ((buf[5] & 0x7F) << 24)
             | ((buf[6] & 0xFF) << 16)
             | ((buf[7] & 0xFF) << 8)
             | (buf[8] & 0xFF);
```

---

## 3. 帧类型

### 3.1 帧类型一览

| 类型 | 值 | 方向 | 说明 |
|------|-----|------|------|
| DATA | 0x00 | 双向 | 数据帧 |
| HEADERS | 0x01 | 双向 | 头部帧 |
| PRIORITY | 0x02 | 双向 | 优先级帧 |
| RST_STREAM | 0x03 | 双向 | 流重置帧 |
| SETTINGS | 0x04 | 双向 | 设置帧 |
| PUSH_PROMISE | 0x05 | 服务端 | 推送承诺帧 |
| PING | 0x06 | 双向 | PING帧 |
| GOAWAY | 0x07 | 服务端 | 关闭帧 |
| WINDOW_UPDATE | 0x08 | 双向 | 窗口更新帧 |
| CONTINUATION | 0x09 | 双向 | 续传帧 |

### 3.2 帧类型枚举

```java
public enum Http2FrameType {
    DATA(0x00),
    HEADERS(0x01),
    PRIORITY(0x02),
    RST_STREAM(0x03),
    SETTINGS(0x04),
    PUSH_PROMISE(0x05),
    PING(0x06),
    GOAWAY(0x07),
    WINDOW_UPDATE(0x08),
    CONTINUATION(0x09);
}
```

---

## 4. Flags 标志

### 4.1 Flags 一览

| 标志 | 值 | 适用帧 | 说明 |
|------|-----|--------|------|
| END_STREAM | 0x01 | DATA, HEADERS | 流结束 |
| END_HEADERS | 0x04 | HEADERS, PUSH_PROMISE, CONTINUATION | 头部结束 |
| ACK | 0x01 | SETTINGS, PING | 确认 |
| END_STREAM | 0x01 | DATA | 请求/响应结束 |
| PADDED | 0x08 | DATA, HEADERS | 包含填充 |

### 4.2 Flags 判断

```java
public boolean hasFlag(int flag) {
    return (flags & flag) != 0;
}

// 使用示例
if (frame.hasFlag(0x04)) {  // END_HEADERS
    // 头部完整
}
```

---

## 5. Stream 流

### 5.1 Stream ID 规则

| 发起方 | ID 规则 | 示例 |
|--------|----------|------|
| 客户端发起 | 奇数 | 1, 3, 5, 7... |
| 服务端推送 | 偶数 | 2, 4, 6, 8... |

### 5.2 Stream 状态机

```
                    +----------------+
                    | idle (空闲)    |
                    +----------------+
                          |
                          | 发送 HEADERS
                          ↓
                    +----------------+
                    | open (打开)    |
                    +----------------+
                          |
           +---------------+---------------+
           |               |               |
           | 发送/接收      | 发送 END_STREAM | 接收 END_STREAM
           | DATA          |               |
           ↓               ↓               ↓
    +----------------+ +----------------+ +----------------+
    | open (打开)    | | half_closed    | | half_closed    |
    | (本地半关闭)   | | (远端半关闭)   | +----------------+
    +----------------+ +----------------+
           |               |
           | 发送 END_STREAM | 接收 END_STREAM
           ↓               ↓
    +----------------+ +----------------+
    | closed (关闭)   | | closed (关闭)  |
    +----------------+ +----------------+
```

### 5.3 Stream 生命周期示例

```
客户端                              服务端
   |                                    |
   |------- HEADERS (stream=1) --------→|  idle → open
   |------- DATA (stream=1) ----------→|  open (继续)
   |------- DATA (stream=1, END) ----→|  open → half_closed
   |                                    |
   |←------- HEADERS (stream=1) --------|  open → half_closed
   |←------- DATA (stream=1, END) ----|  half_closed → closed
```

---

## 6. DATA 帧

### 6.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length (24)   | 0 |flags| Stream (31)             |
+---------------+---+---+-----------------------------+
|                   Payload                          |
+-----------------------------------------------+
```

### 6.2 帧示例

```
// 10字节数据，无 END_STREAM
字节: 00 00 0A 00 00 00 00 00 01 48 65 6C 6C 6F 57 6F 72 6C 64
     |---|type|flags|---stream---|
     |---|length---|
     |------------payload: "HelloWorld"
```

### 6.3 代码解析

```java
void handleDataFrame(Http2Frame frame) {
    int streamId = frame.getStreamId();
    int length = frame.getLength();
    boolean endStream = frame.hasFlag(0x01);
    boolean padded = frame.hasFlag(0x08);

    // 读取数据
    byte[] data = frame.getPayload().buf;

    // 更新流量控制窗口
    remoteWindowSize -= length;
    if (remoteWindowSize < threshold) {
        sendWindowUpdate(remoteWindowIncrement);
    }

    if (endStream) {
        // 流结束
        closeStream(streamId);
    }
}
```

---

## 7. HEADERS 帧

### 7.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length (24)   | 01 |flags| Stream (31)             |
+---------------+---+---+-----------------------------+
|                   HPACK Encoded Headers             |
+-----------------------------------------------+
```

### 7.2 Flags

| 标志 | 值 | 说明 |
|------|-----|------|
| END_STREAM | 0x01 | 请求/响应结束 |
| END_HEADERS | 0x04 | 头部完整，后面无 CONTINUATION |
| PADDED | 0x08 | 包含填充 |
| PRIORITY | 0x20 | 包含流优先级信息 |

### 7.3 帧示例

```
// 简单 GET /index.html 请求
字节: 00 00 05 01 05 00 00 00 01
     |---|type|flags|---stream=1---|
     |---|length=5---|

Payload (HPACK):
40 84 2F 69 6E 64 65 78           // :path = /index
```

### 7.4 CONTINUATION 帧

当 HEADERS 帧的 END_HEADERS 未设置时，后续跟 CONTINUATION 帧：

```
HEADERS (flags=0, length=100)                    // 无 END_HEADERS
    |
    +-- CONTINUATION (length=80)                   // 续传
    |
    +-- CONTINUATION (flags=4, length=20)          // 有 END_HEADERS，结束
```

**注意**: CONTINUATION 必须与前面的 HEADERS/PUSH_PROMISE 有相同的 Stream ID

---

## 8. SETTINGS 帧

### 8.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length (24)   | 04 |flags| Stream (31)=0          |
+---------------+---+---+-----------------------------+
|                   Settings Payload                    |
+-----------------------------------------------+
```

**注意**: SETTINGS 帧的 Stream ID 必须为 0（连接级）

### 8.2 Settings 参数

| 参数 | ID | 默认值 | 说明 |
|------|-----|--------|------|
| SETTINGS_HEADER_TABLE_SIZE | 0x01 | 4096 | HPACK 表大小 |
| SETTINGS_ENABLE_PUSH | 0x02 | 1 | 是否启用推送 |
| SETTINGS_MAX_CONCURRENT_STREAMS | 0x03 | 512（wastnet 默认；RFC 未设置前为无限制） | 最大并发流数 |
| SETTINGS_INITIAL_WINDOW_SIZE | 0x04 | 65535 | 流窗口大小 |
| SETTINGS_MAX_FRAME_SIZE | 0x05 | 16384 | 最大帧大小 |
| SETTINGS_MAX_HEADER_LIST_SIZE | 0x06 | 无限制 | 最大头列表大小 |

> wastnet 中该参数默认通告 **512**，并且配置值低于 **100** 时会自动钳制为 100，用于防止资源耗尽（Rapid Reset 攻击）。可通过 `wastnet.http2.max-concurrent-streams` 调整。

### 8.3 帧示例

```
// 发送 SETTINGS ACK
字节: 00 00 00 04 01 00 00 00 00
     |---|---|type|flags|stream=0|

// 发送 MAX_CONCURRENT_STREAMS = 100
字节: 00 00 06 04 00 00 00 00 00
     |---|---|type|flags|stream=0|
Payload:
     00 03        // 参数ID = SETTINGS_MAX_CONCURRENT_STREAMS
     00 00 00 64  // 值 = 100
```

### 8.4 代码处理

```java
void handleSettingsFrame(Http2Frame frame) {
    // ACK 帧直接返回
    if (frame.hasFlag(0x01)) {
        return;  // ACK
    }

    // 解析 settings 参数
    byte[] payload = frame.getPayload().buf;
    for (int i = 0; i < payload.length; i += 6) {
        int id = payload[i] << 8 | payload[i + 1];
        int value = (payload[i + 2] << 24)
                  | (payload[i + 3] << 16)
                  | (payload[i + 4] << 8)
                  | payload[i + 5];

        applySetting(id, value);
    }

    // 发送 ACK
    sendSettingsAck();
}
```

---

## 9. WINDOW_UPDATE 帧

### 9.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length = 4    | 08 |flags=0| Stream (31)          |
+---------------+---+---+-----------------------------+
|      Window Size Increment (31 bits)                  |
+--------------------------------------------------+
```

### 9.2 流量控制规则

- 连接级窗口: 所有流共享，默认 65535
- 流级窗口: 每个流独立，默认 65535
- 发送 DATA 时必须确保窗口足够

### 9.3 帧示例

```
// 窗口增加 1024
字节: 00 00 04 08 00 00 00 00 01
     |---|---|type|flags|stream=1|
Payload:
     00 00 04 00  // 增量 = 1024
```

---

## 10. RST_STREAM 帧

### 10.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length = 4    | 03 |flags=0| Stream (31)          |
+---------------+---+---+-----------------------------+
|            Error Code (32 bits)                       |
+---------------------------------------------------+
```

### 10.2 错误码

| 错误码 | 值 | 说明 |
|--------|-----|------|
| NO_ERROR | 0x00 | 无错误 |
| PROTOCOL_ERROR | 0x01 | 协议错误 |
| INTERNAL_ERROR | 0x02 | 内部错误 |
| FLOW_CONTROL_ERROR | 0x03 | 流量控制错误 |
| SETTINGS_TIMEOUT | 0x04 | 设置超时 |
| STREAM_CLOSED | 0x05 | 流已关闭 |
| FRAME_SIZE_ERROR | 0x06 | 帧大小错误 |
| REFUSED_STREAM | 0x07 | 流被拒绝 |
| CANCEL | 0x08 | 取消 |
| COMPRESSION_ERROR | 0x09 | 压缩错误 |
| CONNECT_ERROR | 0x0A | 连接错误 |
| ENHANCE_YOUR_CALM | 0x0B | 禁止使用 |
| INADEQUATE_SECURITY | 0x0C | 安全不足 |
| HTTP_1_1_REQUIRED | 0x0D | 需要 HTTP/1.1 |

### 10.3 Rapid Reset 防御（CVE-2023-44487）

攻击者利用 HTTP/2 单连接多路复用，在开流（`HEADERS + END_STREAM`）后立即发送 `RST_STREAM` 取消该流，使连接不关闭却能无限 churn，形成"1 连接 = ∞ 请求"的放大杠杆，且 `max-concurrent-streams` 的并发上限拦不住（槽位瞬时腾空）。

wastnet 在**单连接**维度对客户端发来的 `RST_STREAM` 做固定窗口限速：

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `wastnet.http2.client-rst.window-seconds` | `1`（最小 `1`） | 统计窗口（秒），仅统计客户端发来的 RST，服务端主动发出的 RST 不计入 |
| `wastnet.http2.client-rst.max-count` | `100`（最小 `1`） | 窗口内允许的客户端 RST 上限，超过即关闭连接 |

- 计数仅在流被客户端 RST 时执行，非 RST 帧零额外开销；每连接仅 `long + int` 状态，O(1) 无锁无分配。
- 受固定窗口边界影响，极端情况下实际放行速率最高约 `2 × max-count`；如需更严格可调小 `max-count`。
- 超限时若配置的 `ConnectionFilter` 实现了 `AbuseHandler`，会收到 `"RST_FLOOD"` 上报（可用于封 IP 等），未实现则忽略。

**完整示例（同一个过滤器同时实现 `ConnectionFilter` 与 `AbuseHandler`）：**

```java
// import java.nio.channels.SocketChannel;
// import java.net.InetSocketAddress;

// 让同一个过滤器同时承担连接过滤与滥用上报
public class AbuseConnectionFilter implements ConnectionFilter, AbuseHandler {

    // ① 连接级过滤（IP 黑/白名单等），返回 false 即拒绝，零资源浪费
    @Override
    public boolean onAccept(SocketChannel channel) throws Exception {
        // 例：仅允许内网地址
        return ((InetSocketAddress) channel.getRemoteSocketAddress())
                .getAddress().isSiteLocalAddress();
    }

    // ② 滥用事件回调：运行在 I/O 工作线程，务必轻量、非阻塞
    @Override
    public void onAbuse(ChannelContext ctx, String abuseType) {
        if ("RST_FLOOD".equals(abuseType)) {
            InetSocketAddress remote = ctx.getRemoteAddress();   // 可能为 null，需判空
            String ip = remote != null ? remote.getAddress().getHostAddress() : "unknown";
            blocklist.add(ip);   // 例：将来源 IP 加入封禁名单
            // 防御本身已在超限时关闭连接，此处一般只做记录/封禁，无需再 ctx.close()
        }
    }
}

// ③ 挂入服务器（HTTPServer / TCPServer 用法一致）
HTTPServer.of(8080)
    .connectionFilter(new AbuseConnectionFilter())
    .requestHandler(router)
    .start();
```

> 注：`onAbuse` 在 I/O 工作线程上同步调用，实现应保持轻量（如把 IP 投入并发集合），不要做阻塞或耗时操作；未实现 `AbuseHandler` 的连接过滤器会静默忽略上报。

> **默认配置无需修改**
>
> 该限速**只针对客户端主动取消流**（`RST_STREAM`）这一种行为。在正常的业务流量中，客户端极少频繁取消已发起的请求——即使偶尔取消，单次会话内通常也远低于 100 次/秒。因此**即便把 `max-count` 调到 1 或 10，正常用户也几乎不可能触碰阈值、更不会被误伤**；会突破上限的，只有刻意制造"开流即取消"的 Rapid Reset 攻击流量。
>
> 缺省值（`window-seconds=1`、`max-count=100`）已足以拦截此类攻击，且对正常请求零影响。**应用通常无需修改这两项配置**；仅在确认遭受针对性攻击、且需要更严格限制时，才考虑下调 `max-count`。

---

## 11. PING 帧

### 11.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length = 8    | 06 |flags| Stream = 0            |
+---------------+---+---+-----------------------------+
|                    8字节 Opaque Data               |
+-------------------------------------------------+
```

### 11.2 帧示例

```
// PING 请求
字节: 00 00 08 06 00 00 00 00 00
     |---|---|type|flags|stream=0|
     |---length=8---|
Payload:
     01 02 03 04 05 06 07 08  // 任意数据

// PING 响应 (ACK flag = 1)
字节: 00 00 08 06 01 00 00 00 00
                       ↑
                    flags = 0x01 (ACK)
```

---

## 12. GOAWAY 帧

### 12.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length (24)   | 07 |flags| Stream = 0            |
+---------------+---+---+-----------------------------+
|      Last-Stream-ID (31 bits)                         |
+-------------------------------------------------+
|          Error Code (32 bits)                       |
+-------------------------------------------------+
|          Additional Debug Data (可选)                |
+-------------------------------------------------+
```

### 12.2 帧示例

```
字节: 00 00 08 07 00 00 00 00 00
     |---|---|type|flags|stream=0|
     |---|length=8---|
Payload:
     00 00 00 01  // Last-Stream-ID = 1
     00 00 00 00  // Error-Code = NO_ERROR
```

---

## 13. PRIORITY 帧

### 13.1 帧格式

```
+---------------+---+---+-----------------------------+
| Length = 5    | 02 |flags| Stream (31)          |
+---------------+---+---+-----------------------------+
|   Stream Dependency (31 bits)   | Weight (8 bits)     |
+-------------------------------------------------+
```

### 13.2 Exclusive 和 Weight

```
Exclusive (1 bit) | Stream Dependency (31 bits)
        ↓
    0: 与父流同级
    1: 独占父流
Weight: 1-256 (优先级权重)
```

---

## 14. HPACK 头部压缩

### 14.1 编码结构

```
+---+---+---+----------------------------+
| 1 |Index (7 bits)                    |  索引引用
+---+---+-----------------------------+
| 0 | 1 |Name Index (6 bits)          |  索引名 + 字面值
+---+---+-----------------------------+
| 0 | 0 |H| Name Length (7 bits)       |  字面值名称 + 字面值
+---+---+---+-------------------------+
| H |  String Data (Length bytes)        |
+---+--------------------------------+
```

### 14.2 静态表索引 (部分)

| 索引 | 名称 | 值 |
|------|------|-----|
| 1 | :authority | |
| 2 | :method | GET |
| 3 | :method | POST |
| 4 | :path | / |
| 5 | :scheme | http |
| 6 | :scheme | https |
| 7 | :status | 200 |
| 8 | :status | 204 |
| 9 | :status | 206 |
| ... | ... | ... |
| 65 | content-type | |
| 66 | cache-control | |

### 14.3 编码示例

```
// :status = 200
0x88 = 1000 1000
  └─── 1 | 索引 = 8 (引用 :status: 200)

// :status = 200, content-type = text/html
0x88 0x3F 0x5C
  88 = 1000 1000  → 索引8 (:status=200)
  3F = 0011 1111  → 索引 63 (literal header)
  5C = 0101 1100  → 索引 31 (text/html)
```

### 14.4 Huffman 编码

HPACK 支持 Huffman 压缩，标识位 H=1：

```
+---------------+
| H | Length (7) |
+---------------+
|   Encoded Data  |
+----------------+

// 示例: "hello" 编码后
H=1, Length=24 bits
01110 00101 01100 00110 01100 01100 00011 00010
      h     e     l     l     l     o
```

---

## 15. 完整请求响应示例

### 15.1 HTTP/2 请求响应

```
客户端                              服务端
   |                                    |
   |------- PRI * HTTP/2.0\r\n\r\nSM --→|
   |------- SETTINGS (ACK) ------------→|
   |                                    |
   |←------ SETTINGS (ACK) ------------|
   |                                    |
   |------- HEADERS #1 ----------------→|
   |        :method = GET              |
   |        :path = /index.html       |
   |        :scheme = https            |
   |        :authority = example.com   |
   |        (END_HEADERS)             |
   |                                    |
   |←------- HEADERS #1 ---------------|
   |        :status = 200              |
   |        content-type = text/html    |
   |        (END_HEADERS)             |
   |                                    |
   |←------- DATA #1 ------------------|
   |        <html>...</html>          |
   |        (END_STREAM)               |
   |                                    |
   |------- GOAWAY ------------------→|  (可选，关闭连接)
```

### 15.2 字节流

```
# PREFACE
50 52 49 20 2A 20 48 54 54 50 2F 32 2E 30 0D 0A 0D 0A 53 4D 0D 0A

# SETTINGS ACK (服务端)
00 00 00 04 01 00 00 00 00

# HEADERS 请求
00 00 1A 01 04 00 00 00 01
  83 86 44 6F 6D 61 69 6E 2E 63 6F 6D  # :authority
  84 2F                                   # :path = /
  87                                     # :method = GET
  41 91 93 50 8B 96 4C                   # :scheme = https

# HEADERS 响应
00 00 08 01 04 00 00 00 01
  88                                     # :status = 200
  5F 04 74 65 78 74 2F 68 74 6D 6C   # content-type

# DATA (HTML)
00 00 14 00 01 00 00 00 01
  3C 21 44 4F 43 54 59 50 45 20 68 74 6D 6C 3E 0A
  # "<!DOCTYPE html>\n"
```

---

## 16. 错误处理

### 16.1 错误码

| 错误码 | 值 | 使用场景 |
|--------|-----|---------|
| NO_ERROR | 0x0 | 正常关闭 |
| PROTOCOL_ERROR | 0x1 | 协议违规 |
| INTERNAL_ERROR | 0x2 | 内部错误 |
| FLOW_CONTROL_ERROR | 0x3 | 流量控制违规 |
| SETTINGS_TIMEOUT | 0x4 | SETTINGS 超时 |
| STREAM_CLOSED | 0x5 | 流已关闭 |
| FRAME_SIZE_ERROR | 0x6 | 帧大小错误 |
| REFUSED_STREAM | 0x7 | 流被拒绝 |
| CANCEL | 0x8 | 流取消 |
| COMPRESSION_ERROR | 0x9 | HPACK 错误 |
| CONNECT_ERROR | 0xA | CONNECT 错误 |
| ENHANCE_YOUR_CALM | 0xB | 禁止行为 |
| INADEQUATE_SECURITY | 0xC | 安全不足 |
| HTTP_1_1_REQUIRED | 0xD | 需要降级 |

### 16.2 连接错误 vs 流错误

| 类型 | 影响 | 处理方式 |
|------|------|----------|
| 连接错误 | 整个连接 | GOAWAY |
| 流错误 | 单个流 | RST_STREAM |

---

## 17. 流量控制

### 17.1 窗口更新

```java
// 发送端
if (sendWindow < limit) {
    // 暂停发送
}

// 接收端
void onDataReceived(int bytes) {
    receiveWindow -= bytes;
    if (receiveWindow < limit / 2) {
        sendWindowUpdate(DEFAULT_WINDOW - receiveWindow);
        receiveWindow = DEFAULT_WINDOW;
    }
}
```

### 17.2 窗口示意

```
发送窗口: 65535
    ↓ 发送 1000 字节
窗口: 64535
    ↓ 发送 2000 字节
窗口: 62535
    ↓ 达到阈值
    发送 WINDOW_UPDATE (+10000)
窗口: 72535
```

---

## 18. 参考

- [RFC 7540 - HTTP/2](https://tools.ietf.org/html/rfc7540)
- [RFC 7541 - HPACK](https://tools.ietf.org/html/rfc7541)
