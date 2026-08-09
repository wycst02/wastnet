# WebSocket 高级 API 指南

本文档介绍 wastnet WebSocket 的高级功能，包括连接管理、用户绑定、分组消息推送、超时检测等。

---

## 快速回顾

### 基础 WebSocket 服务

```java
HttpRouterHandler router = new HttpRouterHandler();

router.ws("/chat", new WebSocketResource() {
    public void onOpen(WebSocketConnection conn) {
        System.out.println("New connection: " + conn.id());
    }

    public void onMessage(WebSocketConnection conn, String message) throws IOException {
        System.out.println("Received: " + message);
        conn.sendText("Echo: " + message);  // 原样返回
    }

    public void onBinary(WebSocketConnection conn, byte[] data) throws IOException {
        conn.sendBinary(data);  // 原样返回
    }

    public void onClose(WebSocketConnection conn, int code, String reason) {
        System.out.println("Closed: " + code + " - " + reason);
    }

    public void onError(WebSocketConnection conn, Throwable error) {
        error.printStackTrace();
    }
});

HTTPServer.of(8080).requestHandler(router).start();
```

---

## 声明式 vs 编程式升级

wastnet 提供两种 WebSocket 升级方式，按使用场景区分：

1. **声明式（路由注册）**：`router.ws(path, resource)`。资源按路径注册到路由表，框架在握手前后自动回调 `onOpen` / `onMessage` / `onClose` 等钩子，并默认支持**消息组 / 广播能力**（`enableBroadcast()`）。
2. **编程式（应用持有连接）**：`request.upgrade()` / `request.upgrade(ws)`。由应用层 handler 在处理请求时手动触发，框架返回 `WebSocketConnection` 后由调用方**端到端**驱动收发，不经过路由表匹配。

下面分章节说明每种方式的用法与示例代码，最后总结差异。

### 1. 声明式升级（路由注册）

声明式是推荐的首选方式。把 `WebSocketResource` 绑定到路径，框架自动完成握手与回调分发，连接默认加入全局 map，具备分组 / 广播能力。

```java
HttpRouterHandler router = new HttpRouterHandler();

router.ws("/chat", new WebSocketResource(true, 60, WebSocketResource.TimeoutStrategy.PING) {
    @Override
    public void onOpen(WebSocketConnection conn) {
        System.out.println("New connection: " + conn.id());
    }

    @Override
    public void onMessage(WebSocketConnection conn, String message) throws IOException {
        System.out.println("Received: " + message);
        conn.sendText("Echo: " + message);
    }

    @Override
    public void onBinary(WebSocketConnection conn, byte[] data) throws IOException {
        conn.sendBinary(data);
    }

    @Override
    public void onClose(WebSocketConnection conn, int code, String reason) {
        System.out.println("Closed: " + conn.id() + " code=" + code + " reason=" + reason);
    }

    @Override
    public void onError(WebSocketConnection conn, Throwable error) {
        error.printStackTrace();
    }
});

HTTPServer.of(8080).requestHandler(router).start();
```

要点：
- 连接由框架托管，回调按连接事件自动触发，无需手动管理生命周期。
- `WebSocketResource(true, ...)` 开启广播，可在 `onOpen` 里通过 `conn.setGroupId(room)` 给连接分组，再用 `WebSocketResource.broadcastMessage(frame, room)` 做分组推送（见"群组广播与消息推送"章节）。
- 握手前认证可重写 `beforeHandshake(request, response)`（见"认证与鉴权"章节）。

### 2. 编程式升级（应用持有连接）

编程式升级不注册路由，而是在普通 HTTP handler 里调用 `request.upgrade(ws)` 手动触发。框架完成 101 握手后把 `WebSocketConnection` 返回给调用方，由调用方端到端驱动收发；握手失败时返回 `null`（不写任何响应），由应用自行决定如何回包。

```java
HttpRouterHandler router = new HttpRouterHandler();

router.get("/ws/upgrade", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        // 编程式模式无分组/广播能力，broadcast 必须为 false
        WebSocketResource ws = new WebSocketResource(false) {

            @Override
            public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                // 握手校验：无 name 参数则拒绝升级
                return request.getParameter("name") != null;
            }

            @Override
            public void onOpen(WebSocketConnection conn) {
                System.out.println("open: " + conn.id() + ", subprotocol: " + conn.subprotocol());
                try {
                    conn.sendText("system: connected");   // 建立后主动发一条文本
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(WebSocketConnection conn, String message) throws IOException {
                conn.sendText("echo:" + message);        // 文本回声
            }

            @Override
            public void onBinary(WebSocketConnection conn, byte[] data) throws IOException {
                conn.sendBinary(data);                   // 二进制回声
            }

            @Override
            public void onClose(WebSocketConnection conn, int code, String reason) {
                System.out.println("close: " + conn.id() + ", code=" + code + ", reason=" + reason);
            }
        };

        WebSocketConnection conn = request.upgrade(ws);
        if (conn == null) {
            // 升级失败（Origin 校验不过 / beforeHandshake 返回 false），按普通 HTTP 响应
            response.setContentType("text/plain");
            response.write("upgrade rejected: missing ?name= or bad origin".getBytes());
        }
        // 升级成功后 handler 无需再写响应，后续帧事件由 ws 回调驱动
    }
});

HTTPServer.of(8080).requestHandler(router).start();
```

要点：
- `new WebSocketResource(false)` 必须禁用广播（编程式无分组能力）。
- `request.upgrade(ws)` 返回非 `null` 表示升级成功，后续收发全部由 `ws` 的回调（`onOpen` / `onMessage` / `onBinary` / `onClose`）驱动，调用方无需再写 HTTP 响应。
- 返回 `null` 表示升级被拒绝（Origin 校验 / `beforeHandshake` 返回 `false`），且框架不写响应，由调用方自行处理。

### 方式对比

| 方式 | 注册 | 连接托管 | 分组/广播 | 适用场景 |
|---|---|---|---|---|
| 声明式 `router.ws` | 路由表 | 框架 | ✅ 支持 | 多连接分组推送的消息型服务 |
| 编程式 `request.upgrade(ws)` | 无（handler 内触发） | 调用方（端到端） | ❌ 必须禁用 | 单连接数据交互、需要自行控制连接 |

简言之：声明式由框架托管连接、聚焦消息组能力；编程式由应用自行持有连接、聚焦端到端数据交互。

---

## WebSocketResource 构造选项

```java
// 默认：广播模式开启，无超时检测
new WebSocketResource();

// 指定是否开启广播模式
new WebSocketResource(true);   // 开启广播
new WebSocketResource(false);  // 关闭广播（连接不加入全局 map）

// 超时检测：60 秒无数据自动断开
new WebSocketResource(60);

// 超时检测：60 秒无数据发送 Ping 保活
new WebSocketResource(60, WebSocketResource.TimeoutStrategy.PING);

// 完整参数：关闭广播 + 30 秒超时断开
new WebSocketResource(false, 30, WebSocketResource.TimeoutStrategy.DISCONNECT);
```

### 分片消息安全限制（链式配置）

防御碎片化攻击，可对分片消息的 continuation 帧数量和合并超时做限制：

```java
WebSocketResource ws = new WebSocketResource() {
    // ...
}
.maxContinuations(128)              // 单消息最多 128 个 continuation 帧，超出返回 1009
.fragmentMergeTimeoutMs(15_000)     // 分片合并超时 15 秒，超时返回 1009
.continuationStrategy(ContinuationStrategy.MERGE);  // 分片策略
```

| 方法 | 说明 | 默认值 | 最小值 |
|------|------|--------|--------|
| `maxContinuations(int)` | 单消息最大 continuation 帧数 | `256` | `1` |
| `fragmentMergeTimeoutMs(long)` | 分片合并超时（毫秒），仅 MERGE/BATCH 策略生效 | `30000` (30s) | `1000` (1s) |
| `continuationStrategy(strategy)` | 分片处理策略 | `MERGE` | — |

### TimeoutStrategy 枚举

| 枚举值 | 说明 |
|--------|------|
| `DISCONNECT` | 超时后直接断开连接 |
| `PING` | 超时后发送 Ping 帧保活，连接保持 |

> 注意：超时仅在 `WebSocketResource` 构造时指定 `timeout` 值时生效。也可以在连接建立后通过 `conn.timeoutDetection()` 设置。

---

## Origin 校验（CSWSH 防护）

浏览器场景下，恶意网页可以利用用户的登录态（Cookie）向你的 WebSocket 端点发起跨站连接，即**跨站 WebSocket 劫持（CSWSH）**。RFC 6455 §10.2 要求服务端校验 `Origin` 头。

框架在握手前提供 Origin 白名单校验（在用户 `beforeHandshake` 之前执行，无法被绕过）：

```java
router.ws("/ws/chat", new WebSocketResource(300) { ... }
        .allowedOrigins("https://example.com", "https://app.example.com"));
```

拒绝时客户端收到 `403 Forbidden`，连接不会升级到 WebSocket。

### 匹配规则

| 场景 | 行为 |
|------|------|
| 未配置 `allowedOrigins`（默认） | 不校验，全部放行（向后兼容） |
| Origin 精确命中白名单 | 放行 |
| Origin 未命中 | 403 拒绝 |
| `allowedOrigins("*")` | 显式允许任意来源 |
| 请求无 Origin 头（curl、Java 客户端） | 默认放行，可用 `.allowMissingOrigin(false)` 开启严格模式 |

> 说明：浏览器发起的 WebSocket 握手必然携带 `Origin` 头，因此 CSWSH 攻击载荷一定带 Origin；无 Origin 的请求来自非浏览器客户端，不是该攻击的载体，默认放行以兼容现有客户端。
>
> 安全建议：**面向浏览器的生产端点务必配置 `allowedOrigins`**；纯服务端对服务端的内部端点可保持默认。

---

## WebSocketConnection 接口

### 基础通信

```java
// 发送文本消息
void sendText(String message) throws IOException;

// 发送二进制数据
void sendBinary(byte[] data) throws IOException;

// 以流式发送文件内容（不加载到内存），自动拆分为 continuation 帧
void sendFile(File file) throws IOException;

// 以流式发送 InputStream 内容，逐块编码为 WebSocket 帧发送
void sendInputStream(InputStream in) throws IOException;

// 推送编码后的 WebSocket 帧（可复用已编码的帧）
void push(WebSocketFrame frame);

// 心跳
void ping() throws IOException;
void pong() throws IOException;

// 关闭连接
void close(int code, String reason) throws IOException;
void disconnect();  // 相当于 close(1000, "Disconnected")，会发送关闭帧后关闭通道
```

### 连接信息

```java
// 连接唯一 ID
String id();

// 是否已关闭
boolean isClosed();

// 获取握手时的 HTTP 请求（可从中提取 headers、query params 等）
HttpRequest request();

// 获取子协议
String subprotocol();
```

### 用户绑定与会话管理

```java
// 绑定用户账户信息（可以是 userId、用户对象 ID 等）
void setAccount(Serializable account);

// 获取绑定的用户账户
Serializable getAccount();

// 绑定分组 ID（可以是房间号、群组 ID 等）
void setGroupId(Serializable groupId);

// 获取分组 ID
Serializable getGroupId();
```

**示例：用户认证与绑定**

```java
router.ws("/chat", new WebSocketResource() {
    public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
        // 从查询参数取 token 做认证
        String token = request.getParameter("token");
        if (!isValidToken(token)) {
            response.status(401).write("Unauthorized".getBytes());
            return false;  // 拒绝握手
        }
        return true;
    }

    public void onOpen(WebSocketConnection conn) {
        // 从请求中提取用户信息并绑定
        String userId = conn.request().getParameter("userId");
        String roomId = conn.request().getParameter("room");

        conn.setAccount(userId);       // 绑定用户
        conn.setGroupId(roomId);       // 绑定房间/分组
    }
});
```

### 超时检测

可以为单个连接单独设置超时策略（覆盖 `WebSocketResource` 的默认设置）：

```java
// 从连接内部设置
conn.timeoutDetection(30, WebSocketResource.TimeoutStrategy.PING);

// 在 onOpen 中设置
public void onOpen(WebSocketConnection conn) {
    // 每 30 秒发 Ping 保活
    conn.timeoutDetection(30, WebSocketResource.TimeoutStrategy.PING);
}
```

---

## 群组广播与消息推送

`WebSocketResource` 内置了连接管理和广播能力（需启用 `broadcast` 模式，默认开启）。

### 广播消息

```java
WebSocketResource wsResource = new WebSocketResource() {
    // ...
};

// 广播给所有连接
WebSocketFrame frame = WebSocketFrame.textOf("Hello everyone!");
wsResource.broadcastMessage(frame);

// 广播给指定分组
wsResource.broadcastMessage(frame, "room-1001");

// 推送给指定用户
wsResource.pushMessage(WebSocketFrame.textOf("Hello user!"), "user-123");

// 根据账户查找连接
WebSocketConnection conn = wsResource.getConnectionByAccount("user-123");
if (conn != null) {
    conn.sendText("Direct message");
}
```

### 断开连接管理

```java
// 断开所有连接
wsResource.disconnect();

// 断开指定分组的连接
wsResource.disconnect("room-1001");

// 断开指定用户的连接
wsResource.disconnectByAccount("user-123");
```

---

## WebSocketFrame 工厂与操作

### 创建服务端帧（自动处理掩码）

`WebSocketFrame` 提供了静态工厂方法，用于创建已编码好的服务端帧（无需手动处理掩码）：

```java
// 创建文本帧
WebSocketFrame textFrame = WebSocketFrame.textOf("Hello");

// 创建二进制帧
WebSocketFrame binaryFrame = WebSocketFrame.binaryOf(dataBytes);
```

### 帧属性

```java
WebSocketFrame frame = WebSocketFrame.textOf("Hello");

FrameType type = frame.getType();   // TEXT / BINARY / CLOSE / PING / PONG
boolean isFin = frame.isFin();       // 是否为最终帧
byte[] data = frame.getData();       // 内容随来源而异：工厂创建（textOf/binaryOf）= 含帧头的编码帧；接收帧 = 解掩码后的明文 payload
```

### 分片帧合并

接收分片消息时，可使用 `merge()` 合并多个 CONTINUATION 帧：

```java
@Override
public void onFrame(WebSocketConnection conn, WebSocketFrame frame) throws IOException {
    // 假设外部维护了分片缓存
    WebSocketFrame mergedFrame = partialFrame.merge(frame, frame.isFin());
    if (frame.isFin()) {
        // 完整消息接收完毕，处理 mergedFrame
        handleCompleteMessage(mergedFrame);
    }
}
```

### FrameType 枚举

| 枚举值 | Opcode | 说明 |
|--------|--------|------|
| `CONTINUATION` | `0x0` | 连续帧 |
| `TEXT` | `0x1` | 文本帧 |
| `BINARY` | `0x2` | 二进制帧 |
| `CLOSE` | `0x8` | 连接关闭帧 |
| `PING` | `0x9` | Ping 帧 |
| `PONG` | `0xA` | Pong 帧 |

---

## 完整示例：聊天室

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

public class ChatRoomServer {

    public static void main(String[] args) throws Exception {
        HttpRouterHandler router = new HttpRouterHandler();

        WebSocketResource chat = new WebSocketResource(true, 60, WebSocketResource.TimeoutStrategy.PING) {
            @Override
            public void onOpen(WebSocketConnection conn) {
                String room = conn.request().getParameter("room");
                String user = conn.request().getParameter("user");
                conn.setGroupId(room);
                conn.setAccount(user);
                conn.timeoutDetection(30, TimeoutStrategy.PING);

                // 广播进入消息
                broadcastMessage(
                        WebSocketFrame.textOf("System: " + user + " joined " + room),
                        room
                );
            }

            @Override
            public void onMessage(WebSocketConnection conn, String message) throws IOException {
                // 转发消息到房间内所有人
                String user = (String) conn.getAccount();
                String room = (String) conn.getGroupId();
                broadcastMessage(
                        WebSocketFrame.textOf("[" + user + "]: " + message),
                        room
                );
            }

            @Override
            public void onClose(WebSocketConnection conn, int code, String reason) {
                String user = (String) conn.getAccount();
                String room = (String) conn.getGroupId();
                broadcastMessage(
                        WebSocketFrame.textOf("System: " + user + " left"),
                        room
                );
            }
        };

        router.ws("/chat", chat);
        HTTPServer.of(8080).requestHandler(router).start();
        System.out.println("Chat server running on ws://localhost:8080/chat");
    }
}
```

---

## 生命周期回调

| 回调方法 | 触发时机 | 说明 |
|---------|---------|------|
| `beforeHandshake(request, response)` | WebSocket 握手前 | 返回 `false` 拒绝握手 |
| `onOpen(conn)` | 握手成功，连接建立 | 此时可绑定用户/分组 |
| `onMessage(conn, text)` | 收到文本帧 | 自动合并分片 |
| `onBinary(conn, data)` | 收到二进制帧 | 自动合并分片 |
| `onFrame(conn, frame)` | 收到任意帧 | 低层级回调（含控制帧） |
| `onClose(conn, code, reason)` | 正常关闭 | code 为关闭状态码 |
| `onError(conn, error)` | 业务处理抛异常 | 回调后连接会自动 `close(1011, "Internal server error")` 关闭 |
| `onErrorClose(conn)` | 异常关闭 | 非正常关闭时触发 |

## 流式文件传输

`sendFile` 和 `sendInputStream` 用于发送大文件或流式数据，全程不将完整数据加载到内存，自动拆分为 BINARY + CONTINUATION 帧序列发送。

```java
// 服务端向客户端发送文件
WebSocketResource resource = new WebSocketResource() {
    public void onOpen(WebSocketConnection conn) {
        // 发送文件（自动分片，不占内存）
        conn.sendFile(new File("/data/video.mp4"));
    }
};

// 或发送任意 InputStream
conn.sendInputStream(new FileInputStream("/data/bigfile.zip"));

// 客户端使用 OkHttp 接收，自动重组分片
client.newWebSocket(request, new WebSocketListener() {
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        byte[] fileData = bytes.toByteArray();  // 完整文件
    }
});
```

**性能说明：**
- 非最后帧复用预分配缓冲区，零额外 `new byte[]` 分配
- 最后帧 1 次分配
- 文件大小为 chunkSize 整数倍时，自动发送 0 字节 CONTINUATION FIN=true 尾帧

## 完整示例

在线聊天室综合示例（WebSocket + SSE + 静态页面）详见 `io.github.wycst.wastnet.examples.http.ChatExample`。启动后访问 `http://localhost:8080/websocket-chat.html` 即可体验。
