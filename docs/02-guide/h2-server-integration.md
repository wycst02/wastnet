# HTTP/2 服务端集成指南

本文档介绍 wastnet 中 HTTP/2 的服务端集成与使用方法。协议层面的帧结构、HPACK 编码等详见 [HTTP/2 协议详解](../03-reference/HTTP2_PROTOCOL.md)。

---

## 启用 HTTP/2

### ALPN 协商（TLS）

HTTP/2 over TLS (h2) 通过 ALPN (Application-Layer Protocol Negotiation) 告知客户端优先协商 h2。客户端不支持 h2 时自动回退到 h1：

> **JDK 版本要求**：ALPN 是 JDK 9 引入的 API（`SSLParameters.setApplicationProtocols()`）。JDK 8 下 h2 over TLS 不可用，请改用 h2c（详见下方 [h2c 升级](#h2c-升级明文)）。

```java
// 完整写法：仅声明首选协议，服务端始终自动支持 HTTP/1.1 回退
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .applicationProtocols("h2")
    .requestHandler(router)
    .start();

// 快捷方式：等价于 .applicationProtocols("h2", "http/1.1")
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

> 测试用 PEM 证书位于 `wastnet-test/src/main/resources/cert/` 目录下（`cert.pem` + `server.pem`），可直接在测试项目中引用。生产环境请替换为真实证书。

### h2c 升级（明文）

> h2c 基于明文传输，不涉及 ALPN 协商，**无 JDK 版本限制**，JDK 8 及以上均可使用。
> **注意**：主流浏览器（Chrome、Firefox、Safari 等）**不支持 h2c**，仅支持 h2 (HTTP/2 over TLS)。h2c 适用于服务端间通信、微服务调用、内网工具等场景，**无法通过浏览器测试**，需使用支持 h2c 的客户端（如 OkHttp）验证。

h2c (HTTP/2 Cleartext) 支持两种模式：

#### 方式一：HTTP Upgrade

通过 `router.h2c()` 注册升级端点，客户端从 HTTP/1.1 升级到 HTTP/2：

```java
router.h2c("/upgrade");
```

适合需要 **H1 / H2 共存兼容** 的场景：客户端可能只发 HTTP/1.1，由 `Upgrade` 头协商切换到 HTTP/2；代价是需多一次升级握手往返。

客户端发起升级请求：

```
GET /upgrade HTTP/1.1
Host: localhost:8080
Upgrade: h2c
HTTP2-Settings: <base64url encoded SETTINGS>
```

服务端返回：

```
HTTP/1.1 101 Switching Protocols
Upgrade: h2c
Connection: Upgrade
```

客户端收到 101 后，发送 HTTP/2 连接前言（connection preface）：

```
PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n
```

服务端校验前言通过后回 SETTINGS 帧，连接随即切换为 HTTP/2 帧格式。

#### 方式二：H2_PRIOR_KNOWLEDGE（直接 H2）

适合**客户端和服务端都明确知道使用 HTTP/2** 的场景（如服务端间通信、微服务调用、内网工具）：客户端直接发 H2 连接前言（Connection Preface），无需 H1 升级握手，省一次往返。服务端通过 `applicationProtocols("h2c")` 启用直接 H2 识别，建连后自动检测前言：

```java
HTTPServer.of(8080)
    .applicationProtocols("h2c")  // 声明 h2c，客户端明确使用 HTTP/2
    .requestHandler(router)
    .start();
```

> `applicationProtocols` 同样适用于非 SSL 端口。配置 h2c 后，服务端依据连接**首包自动识别协议**：客户端发送 HTTP/2 连接前言（PRIOR_KNOWLEDGE）走明文 H2；客户端发送 HTTP/1.1 请求则自动按 HTTP/1.1 处理（**支持降级**）。即 h2c 优先尝试明文 H2，不匹配前言时回退到 HTTP/1.1。

**测试示例（OkHttp）：**

h2c 是明文协议，**浏览器不支持**，无法通过浏览器访问；只能使用支持 h2c 的客户端测试，如 OkHttp 的 `H2_PRIOR_KNOWLEDGE` 模式：

```java
import okhttp3.*;

String url = "http://localhost:8080/app/api/test";

OkHttpClient client = new OkHttpClient.Builder()
        .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
        .build();

Request request = new Request.Builder()
        .url(url)
        .get()
        .build();

try (Response response = client.newCall(request).execute()) {
    System.out.println("Protocol: " + response.protocol());   // h2
    System.out.println("Status: " + response.code());
    Headers headers = response.headers();
    for (String name : headers.names()) {
        System.out.println(name + ": " + headers.get(name));
    }
    ResponseBody body = response.body();
    if (body != null) {
        System.out.println("Body: " + body.string());
    }
}
```

**两种方式对比：**

| 方式 | 优点 | 适用场景 |
|:-----|:-----|:---------|
| HTTP Upgrade（`router.h2c()`） | 兼容 H1 客户端，可同时服务 H1 和 H2 | 需要同时支持两种协议的通用场景 |
| H2_PRIOR_KNOWLEDGE（`applicationProtocols("h2c")`） | 减少一次 RTT，性能更好 | 已知两端都支持 H2 的内网通信、微服务间调用 |

---

## Http2Request API

当客户端通过 HTTP/2 连接时，框架会将请求解析为 `Http2Request` 对象。它在业务 handler 中的用法与普通 `HttpRequest` 完全相同：

```java
public void handle(HttpRequest request, HttpResponse response) throws Throwable {
    if (request.getHttpVersion() == HttpVersion.HTTP_2) {
        // HTTP/2 特定逻辑
        Http2Request h2Request = (Http2Request) request;
        int streamId = h2Request.streamId();
        // ...
    }
    response.status(200).body("OK");
}
```

### 特有方法

```java
// 获取 HTTP/2 流 ID（用于调试和流追踪）
int streamId();

// 获取流（用于流控、代理转发等底层操作）
Http2ServerStream stream();
```

### 标准请求方法（与 HTTP/1.1 通用）

`Http2Request` 继承 `HttpInternalRequest`，因此以下方法均可用：

```java
// 请求信息
HttpMethod getMethod();         // GET / POST / PUT 等
String getRequestUri();         // 解码后的请求路径（不含查询参数）
String getUri();                // 原始 URI（含查询参数）
String getScheme();             // 请求协议（http / https）
String getQueryString();        // 查询字符串
String getHeader(String name);  // 获取请求头
String getParameter(String);    // 查询参数
String getContentType();        // Content-Type
long getContentLength();        // Content-Length
InputStream bodyStream();       // 请求体输入流
byte[] getBodyData();           // 请求体数据

// 连接信息
InetSocketAddress getRemoteAddress(); // 客户端地址
InetSocketAddress getServerAddress(); // 服务端地址
```

---

## Http2ServerStream

`Http2ServerStream` 代表服务端 HTTP/2 连接内的一个双向数据流，管理流的状态、窗口和元数据。

### 流状态字段

```java
int streamId;                  // 流 ID
boolean endStream;             // 是否收到 END_STREAM 标记
boolean endHeaders;            // 头部是否接收完成
long sendWindow;               // 当前发送窗口大小
long receiveWindow;            // 当前接收窗口大小
String method;                 // 请求方法
String scheme;                 // 协议
String path;                   // 路径
String authority;              // 权威标识（host:port）
Map<String, Object> headers;   // 头部数据
```

### 代理场景：URI 重写

```java
Http2Request h2Request = (Http2Request) request;
h2Request.setRewriteUri("/rewritten/path");
// 用于反向代理等需要修改请求路径的场景
```

---

## Http2Frame 调试

`Http2Frame` 是框架内部协议层的帧解析对象，**应用层业务处理器无法直接获取**。HTTP/2 帧的十六进制调试由框架内置的**调试开关**控制：启动时加 JVM 参数 `-Dwastnet.http2.debug=true` 开启后，`Http2Stream` / `Http2MessageReader` 在收发帧时会以 debug 日志输出帧内容（`toHexDump()`）。同时将日志级别调整为 debug 即可看到类似输出：

```java
// 开启方式（JVM 启动参数）：
//   -Dwastnet.http2.debug=true
// 开启后框架在收发帧时输出的 debug 日志，格式例如：
// [Server HEADERS] streamId=1 length=42
// +-----+ ... +---------+
// | [HEADERS] streamId=1 length=42 flags=0x04 |
// +-----+ ... +---------+
// | Frame:   0000002a010400000001...        |
// | Head:    0000002a010400000001           |
// | Payload: ...                             |
// +-----+ ... +---------+
```

### 帧类型对照

| 类型代码 | 帧类型 | 说明 |
|---------|--------|------|
| `0x00` | DATA | 数据帧 |
| `0x01` | HEADERS | 头部帧 |
| `0x02` | PRIORITY | 优先级帧 |
| `0x03` | RST_STREAM | 重置流 |
| `0x04` | SETTINGS | 设置帧 |
| `0x05` | PUSH_PROMISE | 推送承诺 |
| `0x06` | PING | 心跳帧 |
| `0x07` | GOAWAY | 关闭连接 |
| `0x08` | WINDOW_UPDATE | 窗口更新 |
| `0x09` | CONTINUATION | 延续帧 |

---

## HPACK 编解码

HPACK 头部压缩和解压缩由框架内部自动处理。以下是相关组件：

- `Http2HpackCodec` — HPACK 编解码器
- `Http2HpackTable` — 静态表 + 动态表管理
- `HuffmanByteCodec` — Huffman 编解码（多级查表实现）
- `Http2HpackException` — HPACK 编解码异常

关于 Huffman 编码表和多级查表设计的详细说明，见 [Huffman 查表设计文档](../03-reference/huffman-table-design.md)。

---

## 流量控制

HTTP/2 流量控制分为连接级和流级两个层面：

| 级别 | 说明 | 默认初始窗口 |
|------|------|-------------|
| 连接级 (Stream ID=0) | 影响该连接上所有流的发送 | 约 1MB（流级默认 ×16） |
| 流级 (Stream ID>0) | 影响单个流的发送 | 65535 字节 |

框架在以下时机自动发送 WINDOW_UPDATE：

1. 读取 DATA 帧载荷并写入流缓冲区时
2. 接收窗口低于阈值时

---

## 完整示例：支持 H2 + H1 的服务器

> **JDK 版本要求**：本示例使用 h2 over TLS（`.h2()` + `pemSSL`），依赖 ALPN，需 **JDK 9+**；JDK 8 请改用 h2c（见上方 [h2c 升级](#h2c-升级明文)）。
>
> **证书来源**：示例证书位于测试模块 `wastnet-test/src/main/resources/cert/` 下（`cert.pem` + `server.pem`），运行前请复制到运行目录或调整路径。完整可运行的 h2 over TLS 示例见 [MvcDemo.java](../../../wastnet-test/src/main/java/io/github/wycst/wastnet/examples/http/mvc/MvcDemo.java)。

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.handler.HttpRoute;

public class H2ServerExample {

    public static void main(String[] args) throws Exception {
        HttpRouterHandler router = new HttpRouterHandler();

        // 注册 HTTP 路由（H2 和 H1 下路由完全一致）
        router.exactRoute("/api/hello", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(200)
                    .contentType("application/json;charset=utf-8")
                    .body("{\"version\":\"" + request.getHttpVersion() + "\"}");
            }
        });

        // h2c 升级端点
        router.h2c("/h2c");

        // 启用 h2 ALPN 协商（H1 客户端同样支持）
        HTTPServer server = HTTPServer.of(8443)
            .pemSSL("cert/server.pem", "cert/server.key")
            .h2()
            .requestHandler(router)
            .start();

        System.out.println("Server running on https://localhost:8443 (h2 + h1)");
    }
}
```
