# wastnet

[![Java CI](https://github.com/wycst02/wastnet/actions/workflows/maven.yml/badge.svg)](https://github.com/wycst02/wastnet/actions/workflows/maven.yml)
[![CodeQL](https://github.com/wycst02/wastnet/actions/workflows/codeql.yml/badge.svg)](https://github.com/wycst02/wastnet/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-8-green.svg)](https://www.oracle.com/java/)
[![codecov](https://codecov.io/gh/wycst02/wastnet/branch/main/graph/badge.svg)](https://codecov.io/gh/wycst02/wastnet)

**wastnet** 是一个轻量级、高性能的 Java NIO 网络通信框架，基于 Reactor 多线程模式设计，提供 TCP 和 HTTP 服务器/客户端的完整实现。零第三方依赖，仅依赖 JDK。

---

## 特性

### 核心特性

- **Reactor 多线程架构** - 单 Acceptor 线程 + 多 Worker 线程（独立 Selector），无锁设计
- **零依赖** - 仅依赖 JDK，无第三方库
- **高性能** - 零拷贝（`FileChannel.transferTo`）、位运算批量扫描字节
- **灵活的线程模型** - 支持同步/异步双执行模式，自动适配
- **SSL/TLS** - 支持 PEM 证书加载、首字节嗅探自动识别明文/加密连接
- **TCP 客户端** - NIO 客户端，支持自动重连、自定义编解码、SSL/TLS
- **SSE (Server-Sent Events)** - 事件发射器，支持超时自动关闭、自定义事件类型

### HTTP 服务器特性

| 特性 | 说明 |
|:-----|:------|
| **HTTP/1.1** | 完整支持 GET/POST/PUT/DELETE/PATCH, Pipeline, Keep-Alive, SSE |
| **HTTP/2 (h2/h2c)** | HPACK 头部压缩、Huffman 编码、流控、多路复用、ALPN 协商 |
| **103 Early Hints** | 静态资源预加载提示（RFC 8297），H1/H2 双协议支持 |
| **路由分发** | 精确匹配、前缀匹配、正则匹配、HTTP 方法过滤 |
| **注解路由 (MVC)** | `@Controller`/`@Endpoint` 注解路由 + 轻量依赖注入 + 消息转换 SPI |
| **反向代理** | URL 重写、Header 变量（`$remote_addr` 等）、H2→H1 协议转换 |
| **WebSocket** | 帧编解码、文本/二进制/Ping-Pong/分片帧 |
| **静态资源** | 零拷贝发送、ETag/Last-Modified 缓存、GZIP 流式压缩 |
| **文件上传** | Multipart/Form-Data 解析、流式 body 读取 |
| **Chunked 编码** | 请求/响应双向 Chunked Transfer Encoding |
| **GZIP 压缩** | 自动 GZIP 压缩（可配置阈值、MIME 类型过滤） |

### 安全特性

- SSL/TLS 加密传输（JKS/PEM 证书格式）
- PEM 证书直接加载（无需 keytool 转换）
- 路径穿越防护（`..` 检测）
- HTTP 方法白名单（静态资源默认仅允许 GET）
- 请求大小限制（URI 长度、Header 大小、Body 大小）
- 连接超时检测（空闲连接自动关闭）
- ALPN 协议协商（h2, http/1.1）
- **连接过滤** — TCP accept 级黑/白名单、IP 限流（`ConnectionFilter`）
- **Origin 校验** — WebSocket 握手 Origin 白名单（CSWSH 防护，`allowedOrigins`）

---

## 快速开始

### 环境要求 - JDK 8 或更高版本

> **注**：HTTP/2 over TLS (h2) 基于 ALPN 协议协商，需要 JDK 9+。
> HTTP/2 cleartext (h2c / H2_PRIOR_KNOWLEDGE) 无此限制，JDK 8 即可使用。

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.wycst</groupId>
    <artifactId>wastnet-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 基础 HTTP 服务器

```java
import io.github.wycst.wastnet.http.HTTPServer;

HTTPServer server = HTTPServer.of(8080)
        .requestHandler((request, response) -> {
            response.contentType("application/json;charset=utf-8")
                    .body("{\"message\": \"Hello World\"}");
        })
        .start();
```

### 带路由的 HTTP 服务器

```java
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

HttpRouterHandler router = new HttpRouterHandler();

// 精确匹配
router.get("/user", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.body("User page");
    }
});

// 前缀匹配
router.route("/api", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.body("API: " + path);
    }
});

// 正则匹配
router.route("^/v\\d+/resource$", routeHandler);

HTTPServer.of(8080).requestHandler(router).start();
```

### HTTPS 服务器（PEM 证书）

```java
HTTPServer.of(8443)
        .pemSSL("cert/cert.pem", "cert/server.pem")
        .h2()
        .requestHandler(router)
        .start();
```

### WebSocket

```java
router.ws("/ws", new WebSocketResource(30 /* 空闲超时秒数 */) {
    public void onOpen(WebSocketConnection conn) {
        System.out.println("Connected: " + conn.id());
    }
    public void onMessage(WebSocketConnection conn, String msg) throws IOException {
        conn.sendText("Echo: " + msg);
    }
    public void onClose(WebSocketConnection conn, int code, String reason) {
        System.out.println("Closed: " + conn.id());
    }
});
```

### 反向代理

```java
router.proxy("/rest", "http://backend:8080");  // 快速方式

// 或使用完整配置
router.proxy("/rest", HttpProxyConfig.target("http://backend:8080")
        .upgrade(true)
        .readTimeout(5000)
        .rewrite(path -> path.replaceFirst("^/rest", "")));
```

### 静态资源服务 + 103 Early Hints

```java
router.resource(new HttpResourceRoute("/", "/var/www")
        .earlyHints(
                "<$base_path/style.css>; rel=preload; as=style",
                "<$base_path/app.js>; rel=preload; as=script"
        ));
// $base_path 由路由器自动替换为 contextPath
```

---

## 架构设计

### Reactor 多线程模型

```
┌──────────────────────────────────────────────────────────────────────┐
│                          TCPServer                                     │
├──────────────────────────────────────────────────────────────────────┤
│  AcceptDispatcher (1 线程) ← 负责 Accept                               │
│         │                                                              │
│         │ 轮询分发（连接数取模 / 最少连接）                              │
│         ↓                                                              │
│  ChannelWorker[0..N] (多线程, 独立 Selector)                            │
│  ┌──────┐ ┌──────┐ ┌──────┐           ┌──────┐                        │
│  │ W-0  │ │ W-1  │ │ W-2  │    ...    │ W-N  │                        │
│  └──────┘ └──────┘ └──────┘           └──────┘                        │
│         │                                                              │
│         ↓                                                              │
│  ChannelRunner / ChannelSSLRunner (同步/异步执行)                      │
│         ↓                                                              │
│  ChannelReader → ChannelHandler (业务处理)                             │
└──────────────────────────────────────────────────────────────────────┘
```

### 设计优势

| 特性 | 说明 |
|:-----|:------|
| **无锁设计** | 每个 Worker 独立 Selector，连接固定分配，无跨线程竞争 |
| **零拷贝** | `FileChannel.transferTo` 发送文件，内核空间直接传输 |
| **批量字节扫描** | 位掩码 + 长整型读取实现 8 字节并行分隔符检测 |
| **动态执行** | 快连接同步执行（零线程切换），慢连接异步执行（不阻塞 Worker） |
| **连接级缓冲区复用** | 每个连接持有独立读写缓冲区，减少 GC |

---

## TCP 客户端

`TCPClient` 是基于 NIO 的 TCP 客户端，支持自动重连（指数退避）、自定义编解码器、SSL/TLS。

```java
import io.github.wycst.wastnet.socket.tcp.TCPClient;

TCPClient client = new TCPClient("127.0.0.1", 8080)
        .channelHandler(new ChannelHandler<ByteBuffer>() {
            public void onHandle(ChannelContext ctx, ByteBuffer message) throws IOException {
                byte[] data = new byte[message.remaining()];
                message.get(data);
                System.out.println("Received: " + new String(data));
            }
        })
        .connect();
```

### 自动重连

```java
TCPClient client = new TCPClient("127.0.0.1", 8080)
        .autoReconnect(true)            // 启用自动重连
        .reconnectAttempts(10)          // 最大重试次数（0 表示无限）
        .reconnectDelay(1000)           // 初始延迟（毫秒，指数退避）
        .channelHandler(handler)
        .connect();
```

### 自定义协议编解码

```java
// 使用 ObjectCodec（Magic + BodyLength + SeqID + CRC16）
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception {
        return new String(data, "UTF-8");
    }
    public byte[] encode(Object msg) throws Exception {
        return ((String) msg).getBytes("UTF-8");
    }
};

TCPClient client = new TCPClient("127.0.0.1", 8080)
        .channelCodec(new ObjectCodec<String>(65536, protocol))
        .channelHandler(myHandler)
        .connect();
```

---

## HTTP 路由（HttpRouterHandler）

### 路由类型

| 方法 | 匹配方式 | 示例 |
|:-----|:---------|:-----|
| `get/post/put/delete/patch` | 精确匹配 + 方法过滤 | `router.get("/user", handler)` |
| `exactRoute` | 精确匹配（不限方法） | `router.exactRoute("/health", handler)` |
| `route` | 前缀匹配（默认） | `router.route("/api", handler)` 匹配 `/api/xxx` |
| `route("^pattern")` | 正则匹配 | `router.route("^/v\\d+/resource$", handler)` |
| `resource` | 静态资源服务 | `router.resource(new HttpResourceRoute("/", "/var/www"))` |
| `proxy` | 反向代理 | `router.proxy("/api", "http://backend:8080")` |
| `ws` | WebSocket | `router.ws("/ws", new WebSocketResource())` |
| `h2c` | H2C 升级 | `router.h2c("/h2c")` |
| `sse` | SSE 事件推送 | `router.route("/events", new SseHandler() {...})` |

方法过滤也支持 `HttpMethodRoute` builder 模式，为同一路径的不同方法指定不同 handler：

```java
router.exactRoute("/api", new HttpMethodRoute()
    .get(getHandler)    // GET 请求
    .post(postHandler)  // POST 请求
    .put(putHandler)    // PUT 请求
);
```

### Context Path

```java
HttpRouterHandler router = new HttpRouterHandler("/my-app");
// 请求 /my-app/api/users → subPath = /api/users
```

### 健康检查

```java
router.healthRoute("/health");  // 内置端点，返回运行时长、路由统计等
```

### 反向代理配置

完整代理配置能力，参考 `docs/02-guide/http-router-guide.md`：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://upstream:8080")
    .upgrade(true)                  // 支持 H2→H1 协议转换
    .rewrite(path -> path.replaceFirst("^/api", ""))  // 路径重写
    .connectionTimeout(3000)        // 连接超时
    .readTimeout(5000)              // 读取超时
    .changeOrigin(true)             // 修改 Host 头
    .addHeader("X-Real-IP", "$remote_addr");  // 动态 Header 变量

router.proxy("/api", config);       // 注册到路由器
```

支持的 Header 变量：`$remote_addr`, `$remote_port`, `$host`, `$scheme`, `$request_uri`, `$query_string`, `$server_addr`, `$server_port`

---

## 注解驱动的 MVC 框架

wastnet 内置一套**注解路由 + 轻量依赖注入（DI）+ 消息转换 SPI** 的迷你 MVC 框架（位于 `io.github.wycst.wastnet.http.annotation`），无需任何第三方框架即可用注解声明式地编写 HTTP 服务，功能上等价于一个精简版 Spring MVC。

### 核心用法

```java
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;

AnnotationRouterHandler router = new AnnotationRouterHandler()
        .messageConverter(myConverter)          // 启用 @RequestBody/@ResponseBody 自动转换
        .property("app.prefix", "Member-")      // 供 @Value 注入的配置属性
        .scanPackages("io.example.mvc");        // 扫描控制器 / 组件包
```

### 常用注解

| 注解 | 目标 | 说明 |
|:-----|:----:|:-----|
| `@Controller(value)` | 类 | 标记控制器，`value` 为类下所有端点的 base path 前缀 |
| `@RestController(value)` | 类 | 控制器 + 所有方法默认带 `@ResponseBody` |
| `@Endpoint(value, allowMethods, responseType)` | 方法 | 标记 HTTP 端点；`allowMethods` 限制 HTTP 方法，`responseType` 声明响应类型（默认 JSON） |
| `@RequestBody` | 参数 | 从请求体反序列化 POJO（需配置 messageConverter） |
| `@ResponseBody` | 方法 | 返回值自动序列化到响应体（需配置 messageConverter） |
| `@RequestParam(value, required, defaultValue)` | 参数 | 绑定 query/form 参数，支持数组/集合/文件上传 |
| `@PathParam(value)` | 参数 | 绑定路径模板变量（`${id}` 或 `{id}`，支持内联正则 `{id:pattern}`） |
| `@Sse(value)` | 方法 | 标记 SSE 端点（方法接收单个 `SseEmitter` 参数） |
| `@WebSocket(value)` | 类 | 标记继承 `WebSocketResource` 的类为 WebSocket 端点 |
| `@Component(value)` | 类 | 托管组件，自动实例化并注册进容器 |
| `@Configuration` + `@Bean` | 类/方法 | 配置源及其 `@Bean` 方法生产 bean 进容器 |
| `@Inject(value)` | 字段/参数 | 依赖注入，`value` 为空按类型查找，否则按名称 |
| `@Value(value)` | 字段/参数 | 注入配置值，支持 `${key:default}` 占位符 |
| `@PostConstruct` / `@PreDestroy` | 方法 | 生命周期回调（bean 初始化后 / 容器清理时） |

### 控制器示例

```java
@Controller("/api/user")                     // base path
public class UserController {

    @Inject
    private UserService userService;          // 字段依赖注入

    @ResponseBody
    @Endpoint("/list")                        // GET /api/user/list
    public String list() {
        return userService.listUsers();       // 返回值自动序列化
    }

    @ResponseBody
    @Endpoint("/get")                         // GET /api/user/get?id=42
    public String get(@RequestParam int id) {
        return userService.getUserName(id);
    }

    @Endpoint("/create")                      // POST /api/user/create
    public String create(@RequestBody CreateUserReq req) {
        return userService.getUserName(req.id);
    }
}
```

托管组件示例：

```java
@Component
public class UserService {

    @Value("${app.prefix:User-}")
    private String namePrefix;

    @PostConstruct
    public void init() { /* 依赖注入完成后调用 */ }

    @PreDestroy
    public void close() { /* 容器清理时调用 */ }
}
```

### 配置与依赖注入

`AnnotationRouterHandler` 支持通过链式方法配置：

- `messageConverter(HttpMessageConverter)` — 设置消息转换 SPI（启用 `@RequestBody`/`@ResponseBody` 的前提，典型用 Jackson/Gson 或 wastnet 自带的 `JSON` 实现）
- `property(key, val)` / `properties(map)` — 设置 `@Value` 可注入的配置属性
- `loadProperties("application.properties")` — 从 classpath `.properties` 文件加载配置
- `loadConfig(ConfigLoader)` — 通过自定义 `ConfigLoader` 加载配置（如 YAML）
- `requestBodyBy(...)` / `responseBodyBy(...)` / `pathParamBy(...)` / `requestParamBy(...)` — 自定义识别哪些注解作为请求体/响应体/路径变量/请求参数
- `valueBy(...)` / `injectBy(...)` / `postConstructBy(...)` / `preDestroyBy(...)` — 自定义 `@Value`/`@Inject`/生命周期注解
- `annotationResolver(AnnotationResolver)` — 桥接第三方注解体系（如 Spring Boot 的 `@RestController`/`@RequestMapping`/`@Service`/`@Autowired`/`@Value`）

### 响应类型（ContentType）

通过 `@Endpoint(responseType = ...)` 声明端点产生的响应类型，扫描时解析进 `ConverterConfig`，由 `HttpMessageConverter` 据此决定序列化方式。编码固定为 UTF-8。

```java
@Controller("/api")
public class DemoController {

    @Endpoint("/hello")                                // 默认 JSON
    public User hello() { return new User(); }

    @Endpoint(value = "/hello", responseType = ContentType.TEXT)   // 纯文本
    public String text() { return "hello world"; }
}
```

`ContentType` 枚举：`JSON`（默认）、`TEXT`、`HTML`、`XML`、`CUSTOM`（自定义，converter 自行决定）。

converter 内通过 `ConverterConfig` 判断类型：

```java
.messageConverter(new HttpMessageConverter() {
    @Override
    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
        if (config.isTextual()) {                    // XML / TEXT / HTML → 原字符串输出
            response.contentType(config.getContentType()).body(String.valueOf(value));
            return;
        }
        if (config.isJson()) {                       // JSON → 对象序列化
            response.contentType(config.getContentType());
            response.body(JSON.toJsonBytes(value));
            return;
        }
        // CUSTOM → converter 自行决定 content-type
        response.contentType(ContentType.JSON.getContentType()).body(JSON.toJsonBytes(value));
    }
})
```

`ConverterConfig` 常用判断方法：

| 方法 | 说明 |
|:-----|:-----|
| `getResponseType()` | 返回 `ContentType` 枚举 |
| `getContentType()` | 返回带 UTF-8 的完整 Content-Type 头值（`CUSTOM` 返回 `null`） |
| `isTextual()` | 是否文本类（`XML`/`TEXT`/`HTML`） |
| `isJson()` / `isXml()` / `isText()` / `isHtml()` | 是否对应具体类型 |
| `isCustom()` | 是否自定义类型 |
| `beforeResponseBody(request, response, result)` | 响应写出前的实例钩子，可覆写做内容协商等（默认空实现） |

### WebSocket / SSE 注解

```java
@WebSocket("/ws/chat")
public class ChatWebSocket extends WebSocketResource {
    public ChatWebSocket() { super(30); }   // 30 秒空闲超时

    @Override
    public void onMessage(WebSocketConnection conn, String msg) throws IOException {
        conn.sendText("echo: " + msg);
    }
}
```

完整的可运行示例见 `wastnet-test` 模块 `examples/http/mvc/`（`MvcDemo` 启动类）。

---

## 协议编解码

wastnet 提供内置的 TCP 协议编解码器，用于自定义二进制协议的消息拆分和组装。

### LengthFrameCodec — 通用长度前缀帧协议

适用于自定义二进制协议，支持 1-4 字节长度字段、可变 header、trailer 校验、大小端字节序：

```java
// 4 字节 header，长度字段在 offset=2 占用 2 字节，body 最大 64KB
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 2, 2, 65536);

// 使用小端字节序
codec.byteOrder(ByteOrder.LITTLE_ENDIAN);

// 包含 trailer（如 CRC16）
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 65536, 2, false);

// TCP 服务器中使用
TCPServer server = TCPServer.of(port)
        .channelCodec(codec)
        .channelHandler(handler)
        .start();
```

### ObjectCodec — 对象消息协议

内置的完整消息协议：12 字节 header（Magic `0x57534E54` + BodyLength + SeqID）+ Payload + CRC16 trailer。

```java
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception { ... }
    public byte[] encode(Object msg) throws Exception { ... }
};

// TCP 服务端
TCPServer.of(port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .start();

// TCP 客户端
new TCPClient(host, port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .connect();
```

详细文档参见 `docs/02-guide/length-codec-guide.md`。

### 自定义编解码扩展点

对于非标准协议，可直接实现 `socket/channel` 包下的编解码抽象来定制消息拆分与组装：

| 抽象 | 说明 |
|:-----|:-----|
| `ChannelReader<T>` | 通道数据解码接口（读方向） |
| `ChannelWriter<T>` | 编码写出接口（写方向） |
| `ChannelDecoder<T>` / `ChannelBytesDecoder<E>` | 字节读取基类（后者为数组式解码，含大小端说明） |
| `ChannelCodec<T>` | 读写双向编解码基类，`TCPServer`/`TCPClient` 的 `channelCodec(...)` 接收此类型 |

---

## HTTP/2 支持

### 特性

- HPACK 头部压缩（静态表 + 动态表）
- Huffman 编码/解码（高性能位运算实现）
- 流控（连接级 + 流级 WINDOW_UPDATE）
- 多路复用（Stream 并发处理）
- H2C 升级（非加密 H2）
- H2→H1 代理转换
- 103 Early Hints
- ALPN 协商（`.h2()`）
- Trailer 块（`getTrailers()` / `setTrailersListener(TrailersListener)`，RFC 7540 §8.1）
- 连接监控（`-Dwastnet.h2.monitor=true` 启用，`H2Monitor.global()` 获取存活连接快照，用于定位卡死/泄漏的 H2 连接）

### 启用 H2

```java
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

> **JDK 版本要求**：h2 (HTTP/2 over TLS) 依赖 ALPN 协商，需要 JDK 9+。
> h2c (HTTP/2 Cleartext) 基于明文 HTTP Upgrade，JDK 8 即可使用（参见 [h2c 升级](docs/02-guide/h2-server-integration.md#h2c-升级明文)）。
> 对于纯内网场景，也可通过 `.applicationProtocols("h2c")` 启用 H2_PRIOR_KNOWLEDGE 模式，减少一次 RTT（服务端始终自动支持 HTTP/1.1 回退，无需显式声明）。

---

## 103 Early Hints

`HttpResourceRoute` 支持发送 103 Early Hints（RFC 8297），在返回 index.html 之前通知浏览器预加载资源：

```java
new HttpResourceRoute("/", docBase)
    .earlyHints(
        "<$base_path/style.css>; rel=preload; as=style",
        "<$base_path/app.js>; rel=preload; as=script"
    );
```

- `$base_path` 自动替换为 `contextPath`，无需手动拼接
- H1 发送原始 103 文本，H2 发送 HEADERS 帧（`:status=103`）
- 仅在请求默认索引页时触发，不影响具体资源请求

---

## 静态资源服务

```java
// 基本用法
router.resource(new HttpResourceRoute("/", "/var/www"));

// 自定义默认文件
router.resource(new HttpResourceRoute("/", "/var/www", new File("index.htm")));

// 安全控制
new HttpResourceRoute("/", docBase)
    .allowAllMethods()     // 允许非 GET 方法（默认仅 GET）
    .notAllowedBody("Custom 405");  // 自定义 405 响应
```

- 自动查找 `index.html` / `index.htm` 作为默认页
- 路径穿越防护（`..` 检测）
- 默认仅允许 `GET` 方法，其余返回 405

---

## WebSocket

| 事件 | 方法 | 说明 |
|:-----|:-----|:------|
| 连接建立 | `onOpen(WebSocketConnection)` | 新连接 |
| 文本消息 | `onMessage(WebSocketConnection, String)` | UTF-8 文本 |
| 二进制消息 | `onBinary(WebSocketConnection, byte[])` | 二进制数据 |
| 连接关闭 | `onClose(WebSocketConnection, int, String)` | 关闭事件 |
| 错误关闭 | `onErrorClose(WebSocketConnection)` | 异常断连 |

`WebSocketConnection` API：

```java
connection.sendText("message");    // 发送文本
connection.sendBinary(data);       // 发送二进制
connection.ping();                 // Ping 帧
connection.close();                // 关闭连接
```

支持配置空闲超时（秒）：

```java
new WebSocketResource(60) { ... }  // 60 秒无消息自动关闭
```

---

## Server-Sent Events (SSE)

支持服务端推送事件（SSE），基于 `SseEmitter` 实现，线程安全，支持超时自动关闭。

### 基本用法

```java
router.route("/events", new SseHandler() {
    @Override
    public void handle(SseEmitter emitter) {
        // 持续推送事件
        for (int i = 0; i < 10; ++i) {
            emitter.emit("count: " + i);
            Thread.sleep(1000);
        }
        emitter.close();
    }
});
```

### SseEmitter API

| 方法 | 说明 |
|:-----|:------|
| `emit(String data)` | 发送 data-only 事件 |
| `emit(String event, String data, String id, long retry)` | 完整控制（null/0 字段自动省略） |
| `close()` | 关闭连接 |

SSE 空闲超时通过 `router.sse(path, timeoutMs, handler)` 重载配置（默认 `HttpConf.SSE_TIMEOUT_MS`），超过指定毫秒数无事件时自动关闭连接。

详细文档参见 `docs/02-guide/sse-guide.md`。

---

wastnet 支持 `multipart/form-data` 格式的文件上传解析，自动在小字段（内存）和大文件（临时文件）之间切换。

### 基本用法

```java
router.exactRoute("/upload", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        if (!request.isMultipart()) {
            response.status(400).body("Not multipart");
            return;
        }

        // 遍历上传字段
        for (String fieldName : request.getMultipartFieldNames()) {
            MultipartField field = request.getMultipartField(fieldName);

            if (field.isFile()) {
                // 文件字段：transferTo 零拷贝写入
                field.transferTo(new File("/tmp/upload.bin"));
            } else {
                // 普通字段：getDataAsString 获取文本值
                System.out.println(fieldName + " = " + field.getDataAsString());
            }
        }

        response.body("OK");
    }
});
```

### 大文件支持

超过内存阈值的文件自动落盘到临时文件，使用流式读取避免 OOM：

```java
MultipartField field = request.getMultipartField("largeFile");
InputStream in = field.getInputStream();  // 流式读取，不加载到内存
field.transferTo(new File("/dest/file.zip"));  // 或直接传输到目标文件
```

### 配置项

| 属性 | 默认值 | 说明 |
|:-----|:------:|:------|
| `wastnet.http.max-body-in-memory` | 2MB | 请求 Body 内存上限，超过转为流式处理（支持 KB/MB/GB 单位） |
| `wastnet.http.body-max-size` | 512MB | 请求 Body 最大总大小限制（字节），≤0 为无限制，支持 KB/MB/GB 单位 |
| `wastnet.http.enable-temp-file` | true | 是否启用临时文件 |
| `wastnet.http.temp-file-dir` | 系统临时目录 | 临时文件目录 |

详细使用指南参见 `docs/02-guide/file-upload-guide.md`。

---

## HTTP 请求 / 响应 API 概览

以下为 `HttpRequest` / `HttpResponse` 中除文件上传外常用的 API（更完整的说明参见 `docs/02-guide/response-api.md`）。

### 请求（HttpRequest）

| 类别 | 方法 |
|:-----|:-----|
| 请求体 | `getBodyData()`、`isStream()`、`isCompleted()`、`complete()` |
| 参数 | `getParameter(name)`、`getParameterValues(name)`、`getParameterMap()`、`getParameterNames()`、`getQueryString()`、`getUriParameter()`、`getBodyParameter()` |
| URL/URI | `getUri()`、`getRequestUri()`、`getRequestURL()`、`getDecodedRequestURL()`、`getHost()` |
| 地址 | `getServerAddress()`、`getServerHost()`、`getServerPort()`、`getRemoteHost()`、`getRemotePort()` |
| 内容判断 | `isJson()`、`isFormUrlencoded()`、`getCharset()`、`isMultipart()` |
| 标识/安全 | `getRequestId()`、`getConnectionId()`、`isSSL()` |

### 响应（HttpResponse）

```java
response.contentType("application/json")        // 设置 Content-Type
        .status(200)                             // 设置状态码
        .body(bytes);                            // 设置响应体

// 零拷贝发送文件（仅非 SSL 连接生效）
response.sendFile(new File("/path/file.zip"));

// Chunked 传输编码
response.setChunkedEncoding();

// 响应级 SSE / 103 Early Hints
response.sse("data", "hello");                   // 推送 SSE 事件
response.earlyHints("<style.css>; rel=preload"); // 发送 103

// 流式输出与手动提交
response.outputStream();
response.setAutoCommit(false);                   // 关闭自动提交，手动控制响应发送时机
```

| 类别 | 方法 |
|:-----|:-----|
| 文件/压缩 | `sendFile(File)`、`sendFile(File, boolean, int)`、`sendFile(File, boolean, String, String)`、`isGzipSupported()` |
| Chunked | `setChunkedEncoding()`、`setChunked(boolean)`、`chunked()`、`isChunked()`、`removeChunkedEncoding()` |
| 编码/缓存 | `setCharacterEncoding()`、`setLastModified()` |
| 生命周期 | `setAutoCommit()`、`isAutoCommit()`、`reset()`、`isCommitted()`、`isCorrupted()` |

---

## SSL/TLS 配置

### PEM 证书（推荐）

```java
// 从文件
server.pemSSL("cert.pem", "key.pem");

// 从 classpath
server.pemSSL("classpath:cert.pem", "classpath:key.pem");

// 从输入流
server.pemSSL(certInputStream, keyInputStream);
```

### JKS 证书

```java
server.ssl(true).sslContext(sslContext);
```

---

## 配置项

> 所有配置项均有缺省值，默认场景下无需任何配置即可运行。仅在生产部署或特定性能调优场景下才需要关注以下配置。

完整配置参考（含所有属性、加载优先级、场景调优建议）详见：

- [HTTP 配置参考](docs/03-reference/http-conf-reference.md)
- [Socket 配置参考](docs/03-reference/socket-conf-reference.md)

### NioConfig

```java
import io.github.wycst.wastnet.socket.tcp.NioConfig;

NioConfig config = new NioConfig();
config.setWorkerNum(8);                     // Worker 线程数
config.setSyncRunner(true);                 // 同步执行模式
config.setReadBufferSize(8192);             // 读缓冲区（仅明文连接生效；SSL 用内置协议缓冲约 16KB）
config.setWriteBufferSize(32768);           // 写缓冲区（仅明文连接生效；SSL 用内置协议缓冲约 16KB）
config.setSslHandshakeTimeoutMs(5000);      // SSL 握手超时
config.setAllowPlaintextWhenSslEnabled(true); // TLS 端口允许明文

HTTPServer.of(8080, config).requestHandler(router).start();
```

`NioConfig`（`io.github.wycst.wastnet.socket.tcp`）由 `TCPServer` 与 `TCPClient` 共享，可传入 `HTTPServer.of(int, NioConfig)` / `new TCPServer(int, NioConfig)` / `new TCPClient(host, port, NioConfig)` 使用。

---

## 启动 Banner

服务启动时默认输出 URL 和耗时信息。可通过 `startupBannerEnabled(false)` 关闭：

```java
HTTPServer.of(8080)
    .startupBannerEnabled(false)
    .requestHandler(...)
    .start();
```

默认输出示例：

```
  wastnet/1.0.0 started in 1363 ms
  ➜  Local:   http://localhost:8080
  ➜  Network: http://10.252.31.235:8080
```

也可通过继承 `HTTPServer` 重写 `onStarted()` 方法完全自定义：

```java
class MyServer extends HTTPServer {
    @Override
    protected void onStarted() {
        System.out.println("MyServer ready on port " + port);
    }
}
```

> **注意**：`TCPServer` 的子类同样可通过重写 `onStarted()` 自定义输出。

---

## 异常处理

```java
router.notFoundHandler((request, response) -> {
    response.status(404).body("Custom 404");
});

// 全局异常处理
server.exceptionHandler((request, response, exception) -> {
    response.status(500)
            .contentType("application/json")
            .body("{\"error\":\"Internal Error\"}");
});

// Debug 模式（打印堆栈）
server.printStackTraceError(true);
```

---

## 优雅停机（Graceful Shutdown）

`TCPServer.shutdownGraceful()` 提供优雅停机能力：

```java
server.shutdownGraceful();   // 停止接受新连接 → 等待在途请求完成 → 强制关闭
server.stop();               // 直接停止（可重启）
server.stop(false);          // 停止但保留连接
```

执行流程（best-effort）：
1. **停止 Accept**：关闭服务端 channel，不再接受新连接（Worker 继续运行）
2. **排空在途请求**：等待已有请求处理完成，直到 `gracefulShutdownTimeout` 超时
3. **强制关闭**：关闭剩余连接并释放线程池，关闭时发送 H2 GOAWAY / WebSocket CLOSE 帧

> **注意**：这是 best-effort 实现，不保证应用层在途请求 100% 完成；如需完全干净停机，仍建议配合负载均衡排空（drain）与健康检查摘除。

---

## 服务器观察者（HttpServerObserver）

`HttpServerObserver` 是框架提供的**观察者 SPI**，用于在请求/连接生命周期的关键节点（请求开始、请求完成、连接打开、连接关闭）注入监控逻辑，如指标采集、分布式追踪、审计日志、慢请求采样等。框架本身**不内置任何实现**，由应用层或可选模块提供。

### 注册方式

通过链式 API 注册。**默认不设置即不启用观察者**（无任何额外开销）；若之前已设置，可传入 `null` 停用：

```java
// HTTPServer 链式配置
HTTPServer.of(8080)
        .observer(myObserver)
        .requestHandler(router)
        .start();
```

### 回调方法

| 方法 | 触发时机 | 说明 |
|:-----|:---------|:-----|
| `onRequestStart(HttpRequest)` | 请求开始处理 | 可在此累加请求计数 |
| `onRequestComplete(HttpRequest, HttpResponse, durationNanos, error)` | 响应提交后 | 含耗时（纳秒）与异常（5xx 时非 null） |
| `onConnectionOpen(ChannelContext)` | 连接建立（accept/handshake 后） | 累加连接计数 |
| `onConnectionClose(ChannelContext)` | 连接关闭 | 递减活跃连接 |

> **注意**：所有回调在 I/O Worker 线程上执行，实现必须**非阻塞且廉价**（推荐原子计数器）。观察者**不得**修改请求/响应或中断处理流程——那应使用拦截器或处理器链。

### 并发安全

`HttpServerChannelHandler` 在 `start()` 时通过 `prepare()` 将观察者 / 拦截器（通过 `setObserver` / `setInterceptor` 设置，若已设置）一次性组装进一个不可变的 `HttpRequestLifecycleDelegate`；请求路径只读取 `delegate` 引用（先捕获到**局部变量**），因此：
- 不会出现 "判空通过、使用时变 null" 的 NPE；
- 一次请求内的 `onRequestStart` / `onRequestComplete` 始终落在**同一个 delegate 内的 observer 实例**上，计数与耗时对称；
- 热路径仅一次 `delegate != null` 判断，未注册观察者 / 拦截器时完全零开销。

配置须在 `start()` **之前**完成；`start()` 之后再调用 `setObserver` / `setInterceptor` **不会生效**（delegate 只在 `prepare()` 组装一次，框架不提供运行期热更新能力）。如需更换观察者 / 拦截器，须重启服务。

### 停机清理（ClearableHandler）

若 observer 实现了 `io.github.wycst.wastnet.socket.handler.ClearableHandler`，框架在 server stop 时会调用其 `clear()`。

### 示例

`wastnet-test` 模块提供了完整可运行示例：

- `examples/http/prometheus/SimpleCounterServerObserver` — 基于 `LongAdder` / `AtomicLong` 的计数器实现（请求计数、状态码分桶、耗时、连接数），零第三方依赖；
- `examples/http/prometheus/PrometheusServerExample` — 带 `main` 的启动类，演示注册 observer 并通过 `/metrics` 端点暴露指标。

```java
// 直接运行 PrometheusServerExample，然后访问：
//   http://localhost:8080/        普通请求（被 observer 计数）
//   http://localhost:8080/metrics 当前指标快照
SimpleCounterServerObserver observer = new SimpleCounterServerObserver();
HTTPServer.of(8080)
        .observer(observer)
        .requestHandler((req, resp) -> resp.body("hello world"))
        .start();
```

> 更详细的用法（完整示例、并发安全、指标语义与常见误区）参见 [docs/02-guide/http-observer-guide.md](docs/02-guide/http-observer-guide.md)。

---

## 请求拦截器（HttpServerInterceptor）

`HttpServerInterceptor` 是**主动的请求拦截 SPI**（与被动观察者 observer 相对），在业务请求处理器**之前**切入，可用于鉴权、CORS 预检、限流等需要**拦截或改写请求**的场景。

### 接口方法

```java
boolean beforeHandle(HttpRequest request, HttpResponse response, ChannelContext ctx) throws Exception;
```

- 返回 `true` 继续进入业务处理器；
- 返回 `false` 表示响应已写出，**跳过**业务处理器（如鉴权拒绝、CORS 预检、限流放行）。

> **注意**：拦截器在 I/O Worker 线程上执行，必须**非阻塞且廉价**；若需阻塞操作（如同步 DB 调用），应派发到业务线程池执行。

### 注册方式

```java
HTTPServer.of(8080)
        .interceptor((request, response, ctx) -> {
            // 鉴权：未携带 token 直接返回 401 并拦截
            if (request.getHeader("Authorization") == null) {
                response.status(401).body("Unauthorized");
                return false;   // 跳过业务处理器
            }
            return true;        // 继续
        })
        .requestHandler(router)
        .start();
```

与 observer 相同，拦截器须在 `start()` **之前**通过 `interceptor(...)` 注册，并由框架在 `prepare()` 时组装进不可变的 `HttpRequestLifecycleDelegate`；`start()` 之后再设置不会生效。

> 更详细的用法（执行时机与短路语义、粒度说明、完整示例、与 observer 的区别）参见 [docs/02-guide/http-interceptor-guide.md](docs/02-guide/http-interceptor-guide.md)。

---

## 空闲连接检测

`IdleStateHandler` 在连接超过指定时间无读/写活动时触发回调：

```java
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import java.util.concurrent.TimeUnit;

server.idleStateHandler(new IdleStateHandler(10, 0, TimeUnit.SECONDS) {
    @Override
    public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType idleType,
                                 long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
        System.out.println("Idle: conn=" + ctx.getId()
                + " type=" + idleType
                + " consecutive=" + triggerConsecutiveCount);
        // Close after 3 consecutive idle triggers (30s total)
        if (triggerConsecutiveCount >= 3) {
            ctx.close();
        }
    }
});
```

构造参数说明：

| 参数 | 说明 |
|:-----|:------|
| `readerIdleTime` | 读取空闲超时（≤ 0 表示不检测） |
| `writerIdleTime` | 写入空闲超时（≤ 0 表示不检测） |
| `unit` | 时间单位（`TimeUnit.SECONDS`、`MILLISECONDS` 等） |
| `mode` | 可选，空闲检测模式：`EXCLUSIVE`（默认，独立调度，纳秒级精度，常规场景已足够）或 `SHARED`（Worker 级扫描，~1s 精度，高并发场景） |

SHARED 模式示例（高并发场景，常规场景使用默认模式即可）：

```java
server.idleStateHandler(new IdleStateHandler(30, 0, TimeUnit.SECONDS, Mode.SHARED) {
    @Override
    public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType idleType,
                                 long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
        ctx.close();
    }
});
```

回调参数说明（`onIdleTriggered`）：

| 参数 | 说明 |
|:-----|:------|
| `idleType` | 空闲类型：`IdleType.Read`（读空闲）或 `IdleType.Write`（写空闲） |
| `triggerTotalCount` | 该连接自建立以来触发的空闲总次数 |
| `triggerConsecutiveCount` | 该连接自最后一次读写活动后连续触发的空闲次数。可用于分级处理：首次警告，多次后关闭 |

---

## 连接过滤器（ConnectionFilter）

在 TCP accept 阶段拦截连接，适用于 IP 黑/白名单、连接数限流等场景，**零资源浪费**（被拒绝的连接不会创建 ChannelContext 和 ByteBuffer）。

```java
// IP 黑名单
final Set<String> blacklist = new HashSet<String>(Arrays.asList("192.168.1.100", "10.0.0.5"));
HTTPServer.of(8080)
    .connectionFilter(ch -> !blacklist.contains(
        ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress()))
    .requestHandler(router)
    .start();

// IP 级连接数限流
final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<String, AtomicInteger>();
HTTPServer.of(8080)
    .connectionFilter(ch -> {
        String ip = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
        return counters.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() <= 50;
    })
    .requestHandler(router)
    .start();
```

`connectionFilter` 在 `AcceptDispatcher` 中执行，filter 抛异常或返回 `false` 时自动关闭连接并记录日志，不影响 Acceptor 主循环。

## 项目结构

```
wastnet/                               ← 父工程 (pom)
├── pom.xml                              ← 模块管理、版本管理
│
├── wastnet-core/                       ← 底层网络框架 (jar)
│   ├── pom.xml                          ← 零外部依赖
│   └── src/main/java/io/github/wycst/wastnet/
│       ├── socket/                  # TCP 核心模块
│       │   ├── tcp/                 # 服务器、连接上下文、NioConfig
│       │   ├── handler/            # 业务处理器、空闲检测
│       │   ├── channel/            # 编解码器
│       │   ├── conf/               # 静态配置项
│       │   └── protocol/           # ObjectCodec 等协议
│       ├── http/                   # HTTP 核心
│       │   ├── HTTPServer.java     # HTTP 服务器
│       │   ├── annotation/         # 注解路由 + 轻量 DI (MVC)
│       │   ├── handler/            # 路由、资源、异常处理器、拦截器/观察者
│       │   ├── h2/                 # HTTP/2 (HPACK/Huffman/帧/流)
│       │   ├── proxy/              # 反向代理
│       │   ├── upgrade/            # 协议升级 (WebSocket/H2C)
│       │   ├── extension/          # HttpServerObserver / HttpServerInterceptor
│       │   └── reader/             # HTTP 请求解码
│       ├── env/                    # JDK 版本兼容层 (ALPN 等)
│       ├── log/                    # 内置日志
│       ├── util/                   # 工具类
│       └── exception/              # 异常定义
│
├── wastnet-test/                       ← 测试和示例 (jar)
│   ├── pom.xml                          # 依赖 wastnet-core
│   ├── src/main/java/                   # 测试代码 + 可运行示例
│   ├── src/main/resources/              # 证书、密钥库、演示页面
│   └── test-files/                      # 测试数据文件
│
└── docs/
│   ├── 02-guide/                       # 使用指南
│   │   ├── http-server-api.md
│   │   ├── http-router-guide.md
│   │   ├── response-api.md
│   │   ├── file-upload-guide.md
│   │   ├── websocket-advanced-api.md
│   │   ├── h2-server-integration.md
│   │   ├── length-codec-guide.md
│   │   ├── sse-guide.md
│   │   ├── http-observer-guide.md
│   │   └── http-interceptor-guide.md
│   ├── 03-reference/                   # 配置/协议参考
│   │   ├── http-conf-reference.md
│   │   ├── socket-conf-reference.md
│   │   ├── HTTP2_PROTOCOL.md
│   │   ├── huffman-table-design.md
│   │   ├── websocket-*.md
│   ├── 05-standards/                   # RFC 标准
│   │   └── rfc*.md
│   └── 04-architecture/               # 架构原理
│       └── TCPSERVER_ARCHITECTURE.md
```

---

## 详细文档

| 文档 | 内容 |
|:-----|:-----|
| `docs/02-guide/http-router-guide.md` | 路由匹配、Context Path、反向代理配置 |
| `docs/02-guide/response-api.md` | 响应 API：状态码、Header、Chunked、GZIP、sendFile |
| `docs/02-guide/file-upload-guide.md` | 文件上传：Multipart API、大文件流式处理、配置项 |
| `docs/02-guide/sse-guide.md` | SSE 服务端推送：Emitter API、超时控制 |
| `docs/02-guide/websocket-advanced-api.md` | WebSocket 高级 API：帧编码、Ping-Pong、分片 |
| `docs/02-guide/h2-server-integration.md` | H2/H2C 服务器配置：TLS ALPN、明文升级 |
| `docs/02-guide/length-codec-guide.md` | 长度前缀帧编解码：自定义二进制协议 |
| `docs/02-guide/http-observer-guide.md` | HTTP 观察者：请求/连接生命周期回调 |
| `docs/02-guide/http-interceptor-guide.md` | HTTP 拦截器：请求前置处理 |
| `docs/03-reference/http-conf-reference.md` | HTTP 配置项参考（含调优建议） |
| `docs/03-reference/socket-conf-reference.md` | Socket 配置项参考 |
| `docs/03-reference/HTTP2_PROTOCOL.md` | H2 连接建立、帧结构、HPACK、流控 |
| `docs/03-reference/websocket-cheatsheet.md` | WebSocket 帧类型速查 |
| `docs/03-reference/websocket-implementation-guide.md` | WebSocket 实现细节 |
| `docs/04-architecture/TCPSERVER_ARCHITECTURE.md` | TCP 服务器架构原理 |

---

## 性能优化建议

| 场景 | 推荐 Worker 数 | 模式 | 缓冲区 |
|:-----|:-------------:|:----:|:------:|
| API 服务（小包） | CPU 核心数 | 同步 | 1-4 KB |
| 静态文件（大包） | CPU 核心数 × 2 | 异步 | 16-64 KB |
| 反向代理 | CPU 核心数 × 2 | 同步 | 4-8 KB |
| WebSocket 长连 | CPU 核心数 | 异步 | 2-4 KB |

---

## 与其他框架对比

| 特性 | wastnet | Netty | Vert.x |
|:-----|:--------:|:-----:|:------:|
| 依赖 | 无 | 多个 | 多个 |
| 学习曲线 | 低 | 中 | 中 |
| HTTP 路由 | 内置 | 需编解码器 | 内置 |
| HTTP/2 | 内置 | 需添加 handler | 内置 |
| WebSocket | 内置 | 需添加 handler | 内置 |
| 反向代理 | 内置 | 需自行实现 | 需扩展 |
| 零拷贝 | ✅ | ✅ | ✅ |
| 内存池 | 连接级复用 | 可配置 | 可配置 |

---

## 性能压测对比报告

以下数据基于 `bench.sh` / `bench-start.bat` 自动化压测脚本产出，对比对象为 **wastnet** 与 **Undertow**（JBoss 高性能 NIO HTTP 服务器）。两份框架各启动独立进程，分别监听 h1 / h2 / h2c 三个端口。

### 测试环境

| 项 | 配置 |
|:---|:------|
| 服务端框架 | wastnet vs Undertow |
| 测试工具 | `wrk`（H1 短连接）、`h2load`（H1/H2/H2C 长连接） |
| GET 参数 | `-n 10000 -c 10 -m 100 -D 15`（并发 10、每连接 100 流、时长 15s） |
| POST 参数 | `-n 1000 -c 10 -t 4 -d payload.bin`（小 body，参数与 GET 场景不同，不可直接横比） |
| 硬件 - Windows | Intel i9 32 核，内存由启动脚本指定；作为跨机场景的 Server 主机 |
| 硬件 - Linux | CentOS 7，16 核，内存由启动脚本指定；作为虚拟机同时承担跨机 Client 与同机 Server/Client |
| 拓扑 A（跨机） | Server 运行于 Windows 主机 `192.168.5.1`（i9 32 核），`h2load` 运行于同网段 CentOS7 虚拟机（16 核） |
| 拓扑 B（同机） | Server 与 `h2load` 均运行于 CentOS7 虚拟机 `localhost`（16 核，loopback 无网络损耗） |

> 注：跨机场景中 Server（i9 32 核）硬件明显强于 Client（16 核虚拟机），因此 Client 侧 `h2load` 更易先成为瓶颈，跨机数据主要反映 **Server 端框架在网络 IO 模型上的效率差异**，而非纯计算能力对比。同机场景 Server/Client 共用 16 核，CPU 竞争激烈，更考验框架在资源受限下的调度。

> 所有场景 0 失败、0 错误、0 超时；H2 均协商为 `h2`（ALPN，`TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384`），H2C 为 `h2c`（H2_PRIOR_KNOWLEDGE 明文）。

### 跨机压测（Server: 192.168.5.1 / Windows, Client: Linux 虚拟机）

| 协议 | wastnet (req/s) | undertow (req/s) | wastnet 优势 |
|:----|:--------------:|:----------------:|:-----------:|
| HTTP/1.1 (wrk)        | 22,962 * | 22,280 * | +3.1% |
| HTTP/1.1 (h2load)     | 161,186 | 130,780 | **+23.2%** |
| HTTP/2 over TLS (h2)  | 103,892 | 79,544  | **+30.6%** |
| HTTP/2 cleartext (h2c)| 174,081 | 97,613  | **+78.3%** |

\* `wrk` 数据为 `Requests/sec`（7 线程 200 连接短连接场景），与 h2load 长连接口径不同，仅作参考。

**结论**：跨机网络场景下，wastnet 在三种协议上**全面领先** Undertow，其中 H2C 明文优势最大（约 +78%），H2 次之（约 +31%）。H2C 免去了 TLS 握手与加解密开销，在 wastnet 的无锁 Reactor 模型下吞吐优势被进一步放大。

### 同机压测（Server & Client: localhost / Linux 虚拟机）

| 协议 | wastnet (req/s) | undertow (req/s) | 领先方 |
|:----|:--------------:|:----------------:|:------:|
| HTTP/1.1 (wrk)        | 340,063 * | 329,911 * | wastnet (+3.1%) |
| HTTP/1.1 (h2load)     | 320,845 | 327,000 | undertow (+1.9%) |
| HTTP/2 over TLS (h2)  | 87,731  | 97,866  | undertow (+11.6%) |
| HTTP/2 cleartext (h2c)| 137,307 | 207,489 | **undertow (+51.1%)** |

\* `wrk` 同上，仅作参考。

**结论**：loopback 同机场景下，两者 H1 基本持平；但在 H2 / H2C 上 **Undertow 反超**，尤其 H2C 领先约 51%。这与跨机结果相反——说明在零网络延迟、CPU 成为瓶颈的同机环境中，Undertow 的 H2 栈实现（JDK 原生 ALPN + 成熟 HPACK 实现）单连接处理效率更高，而 wastnet 的 H2 路径仍有优化空间（如 HPACK 动态表、流调度）。

### 综合解读

- **网络是 H2 的隐形瓶颈**：跨机时 TLS 握手（connect ~400ms、首字节 ~480ms）与网络 RTT 主导延迟，wastnet 的轻量连接模型更抗网络开销；同机时这些成本消失，裸计算效率决定胜负。
- **H2C 是 wastnet 的强项（跨机）**：免去 TLS 后 wastnet 领先幅度最大，适合内网服务间高性能通信。
- **H2 是同机短板**：wastnet 在 loopback 下的 H2 吞吐约为 Undertow 的 90%（h2）/ 66%（h2c），后续版本将重点优化 H2 帧调度与头部压缩。

### POST 场景（小 body，参数 `-n 1000 -c 10 -t 4`，绝对值参考）

| 协议 | wastnet (192.168.5.1) | undertow (192.168.5.1) | wastnet (localhost) | undertow (localhost) |
|:----|:---------------------:|:----------------------:|:-------------------:|:--------------------:|
| H1  | 57 req/s  | 48 req/s  | 2,817 req/s | 5,834 req/s |
| H2  | 761 req/s | 65 req/s  | 13,258 req/s| 1,969 req/s |

> 注：POST 场景请求数仅 1000、线程数 4，统计窗口小、波动大，且两框架 body 处理路径差异明显（undertow 同机 POST H1 异常偏高可能与 body 缓冲策略有关），**不建议作为横向对比依据**，此处仅记录原始数值。

完整原始日志见 `bench-results/` 目录（`wastnet|undertow` × `192.168.5.1|localhost`，后缀 `-2026-08-08.txt`）。

---

## 开发路线

- [x] HTTP/2 完整实现（HPACK + Huffman + 流控 + 多路复用）
- [x] 反向代理（H2→H1 转换、URL 重写、Header 变量）
- [x] 103 Early Hints（RFC 8297）
- [x] TCP 客户端（自动重连、自定义编解码、SSL/TLS）
- [x] SSE（Server-Sent Events）
- [x] 协议编解码（LengthFrameCodec、ObjectCodec）
- [x] 注解路由 + 轻量 DI（AnnotationRouterHandler，可桥接 Spring 注解）
- [x] 监控指标（HttpServerObserver / HttpServerInterceptor SPI + Prometheus 示例，见 `examples/http/prometheus`）
- [ ] HTTP/3 (QUIC) 支持（预留）
- [ ] 连接池
- [ ] UDP 支持

---

## 许可证

本项目基于 [Apache 2.0](LICENSE) 许可证开源。
