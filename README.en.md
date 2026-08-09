# wastnet

[![Java CI](https://github.com/wycst02/wastnet/actions/workflows/maven.yml/badge.svg)](https://github.com/wycst02/wastnet/actions/workflows/maven.yml)
[![CodeQL](https://github.com/wycst02/wastnet/actions/workflows/codeql.yml/badge.svg)](https://github.com/wycst02/wastnet/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-8-green.svg)](https://www.oracle.com/java/)
[![codecov](https://codecov.io/gh/wycst02/wastnet/branch/main/graph/badge.svg)](https://codecov.io/gh/wycst02/wastnet)

**wastnet** is a lightweight, high-performance Java NIO network framework based on the Reactor multi-thread pattern, providing complete TCP and HTTP server/client implementations. Zero third-party dependencies, only relies on JDK.

---

## Features

### Core Features

- **Reactor Multi-thread Architecture** - Single Acceptor thread + multiple Worker threads (independent Selectors), lock-free design
- **Zero Dependencies** - Only relies on JDK, no third-party libraries
- **High Performance** - Zero-copy (`FileChannel.transferTo`), bitwise batch byte scanning
- **Flexible Thread Model** - Supports synchronous/asynchronous dual execution modes, auto-adaptation
- **SSL/TLS** - PEM certificate loading, first-byte sniffing for auto-detection of plaintext/encrypted connections
- **TCP Client** - NIO client with auto-reconnect, custom codec, SSL/TLS
- **SSE (Server-Sent Events)** - Event emitter with auto-close timeout, custom event types

### HTTP Server Features

| Feature | Description |
|:--------|:------------|
| **HTTP/1.1** | Full support for GET/POST/PUT/DELETE/PATCH, Pipeline, Keep-Alive, SSE |
| **HTTP/2 (h2/h2c)** | HPACK header compression, Huffman encoding, flow control, multiplexing, ALPN negotiation |
| **103 Early Hints** | Static resource preloading hints (RFC 8297), dual-protocol support for H1/H2 |
| **Router** | Exact match, prefix match, regex match, HTTP method filtering |
| **Annotation Router (MVC)** | `@Controller`/`@Endpoint` annotation routing + light DI + message converter SPI |
| **Reverse Proxy** | URL rewriting, header variables (`$remote_addr` etc.), H2→H1 protocol conversion |
| **WebSocket** | Frame encoding/decoding, text/binary/ping-pong/fragmented frames |
| **Static Resources** | Zero-copy file sending, ETag/Last-Modified caching, streaming GZIP compression |
| **File Upload** | Multipart/Form-Data parsing, streaming body reading |
| **Chunked Encoding** | Bidirectional Chunked Transfer Encoding for request/response |
| **GZIP Compression** | Auto-GZIP compression (configurable threshold, MIME type filtering) |

### Security Features

- SSL/TLS encrypted transport (JKS/PEM certificate formats)
- Direct PEM certificate loading (no keytool conversion needed)
- Path traversal protection (`..` detection)
- HTTP method whitelist (static resources only allow GET by default)
- Request size limits (URI length, Header size, Body size)
- Connection timeout detection (auto-close idle connections)
- ALPN protocol negotiation (h2, http/1.1)
- **Connection Filtering** - TCP accept-level blacklist/whitelist, IP rate limiting (`ConnectionFilter`)
- **Origin Validation** - WebSocket handshake Origin allowlist against CSWSH (`allowedOrigins`)

---

## Quick Start

### Requirements - JDK 8 or higher

> **Note**: HTTP/2 over TLS (h2) requires ALPN negotiation, which needs JDK 9+.
> HTTP/2 cleartext (h2c / H2_PRIOR_KNOWLEDGE) has no such restriction and works on JDK 8+.

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.wycst</groupId>
    <artifactId>wastnet-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic HTTP Server

```java
import io.github.wycst.wastnet.http.HTTPServer;

HTTPServer server = HTTPServer.of(8080)
        .requestHandler((request, response) -> {
            response.contentType("application/json;charset=utf-8")
                    .body("{\"message\": \"Hello World\"}".getBytes());
        })
        .start();
```

### HTTP Server with Router

```java
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

HttpRouterHandler router = new HttpRouterHandler();

// Exact match
router.get("/user", (path, request, response) -> {
    response.body("User page".getBytes());
});

// Prefix match
router.route("/api", (path, request, response) -> {
    response.body(("API: " + path).getBytes());
});

// Regex match
router.route("^/v\\d+/resource$", routeHandler);

HTTPServer.of(8080).requestHandler(router).start();
```

### HTTPS Server (PEM Certificate)

```java
HTTPServer.of(8443)
        .pemSSL("cert/cert.pem", "cert/server.pem")
        .h2()
        .requestHandler(router)
        .start();
```

### WebSocket

```java
router.ws("/ws", new WebSocketResource(30 /* idle timeout in seconds */) {
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

### Reverse Proxy

```java
router.proxy("/rest", "http://backend:8080");  // Quick way

// Or with full configuration
router.proxy("/rest", HttpProxyConfig.target("http://backend:8080")
        .upgrade(true)
        .readTimeout(5000)
        .rewrite(path -> path.replaceFirst("^/rest", "")));
```

### Static Resource Serving + 103 Early Hints

```java
router.resource(new HttpResourceRoute("/", "/var/www")
        .earlyHints(
                "<$base_path/style.css>; rel=preload; as=style",
                "<$base_path/app.js>; rel=preload; as=script"
        ));
// $base_path is automatically replaced by the router with the contextPath
```

---

## Architecture

### Reactor Multi-thread Model

```
┌──────────────────────────────────────────────────────────────────────┐
│                          TCPServer                                     │
├──────────────────────────────────────────────────────────────────────┤
│  AcceptDispatcher (1 thread) ← Handles Accept                          │
│         │                                                              │
│         │  Round-robin distribution (connection count / least conns)   │
│         ↓                                                              │
│  ChannelWorker[0..N] (multi-thread, independent Selectors)             │
│  ┌──────┐ ┌──────┐ ┌──────┐           ┌──────┐                        │
│  │ W-0  │ │ W-1  │ │ W-2  │    ...    │ W-N  │                        │
│  └──────┘ └──────┘ └──────┘           └──────┘                        │
│         │                                                              │
│         ↓                                                              │
│  ChannelRunner / ChannelSSLRunner (sync/async execution)               │
│         ↓                                                              │
│  ChannelReader → ChannelHandler (business processing)                  │
└──────────────────────────────────────────────────────────────────────┘
```

### Design Advantages

| Feature | Description |
|:--------|:------------|
| **Lock-free Design** | Each Worker has its own Selector, connections are fixed-allocated, no cross-thread contention |
| **Zero Copy** | `FileChannel.transferTo` for file sending, kernel-space direct transfer |
| **Batch Byte Scanning** | Long-word read + bitmask for 8-byte parallel delimiter detection |
| **Dynamic Execution** | Fast connections execute synchronously (zero thread switching), slow connections execute asynchronously (non-blocking Workers) |
| **Connection-level Buffer Reuse** | Each connection has its own read/write buffers, reducing GC |

---

## TCP Client

`TCPClient` is a NIO-based TCP client with auto-reconnect (exponential backoff), custom codec, and SSL/TLS support.

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

### Auto-Reconnect

```java
TCPClient client = new TCPClient("127.0.0.1", 8080)
        .autoReconnect(true)            // Enable auto-reconnect
        .reconnectAttempts(10)          // Max retry attempts (0 = infinite)
        .reconnectDelay(1000)           // Initial delay (ms, exponential backoff)
        .channelHandler(handler)
        .connect();
```

### Custom Protocol Codec

```java
// Using ObjectCodec (Magic + BodyLength + SeqID + CRC16)
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

## Protocol Codec

wastnet provides built-in TCP protocol codecs for message framing and assembly in custom binary protocols.

### LengthFrameCodec — Generic Length-Prefixed Frame Protocol

Supports 1-4 byte length fields, variable headers, trailer checksums, and byte order:

```java
// 4-byte header, length field at offset=2, 2 bytes, max body 64KB
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 2, 2, 65536);

// Little-endian byte order
codec.byteOrder(ByteOrder.LITTLE_ENDIAN);

// With trailer (e.g. CRC16)
LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 65536, 2, false);

// Usage in TCP server
TCPServer server = TCPServer.of(port)
        .channelCodec(codec)
        .channelHandler(handler)
        .start();
```

### ObjectCodec — Object Message Protocol

Built-in message protocol: 12-byte header (Magic `0x57534E54` + BodyLength + SeqID) + Payload + CRC16 trailer.

```java
ObjectProtocol protocol = new ObjectProtocol() {
    public Object decode(byte[] data) throws Exception { ... }
    public byte[] encode(Object msg) throws Exception { ... }
};

// TCP server
TCPServer.of(port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .start();

// TCP client
new TCPClient(host, port)
    .channelCodec(new ObjectCodec<String>(65536, protocol))
    .channelHandler(handler)
    .connect();
```

See `docs/02-guide/length-codec-guide.md` for details.

### Custom Codec Extension Points

For non-standard protocols, implement the codec abstractions under `socket/channel` to customize message framing and assembly:

| Abstraction | Description |
|:------------|:------------|
| `ChannelReader<T>` | Channel data decode interface (read direction) |
| `ChannelWriter<T>` | Encode/write interface (write direction) |
| `ChannelDecoder<T>` / `ChannelBytesDecoder<E>` | Byte-reading base classes (the latter is array-based, with byte-order notes) |
| `ChannelCodec<T>` | Bidirectional read/write codec base class, accepted by `TCPServer`/`TCPClient` `channelCodec(...)` |

---

## HTTP Routing (HttpRouterHandler)

### Route Types

| Method | Match Mode | Example |
|:-------|:-----------|:--------|
| `get/post/put/delete/patch` | Exact + Method filter | `router.get("/user", handler)` |
| `exactRoute` | Exact (any method) | `router.exactRoute("/health", handler)` |
| `route` | Prefix match (default) | `router.route("/api", handler)` matches `/api/xxx` |
| `route("^pattern")` | Regex match | `router.route("^/v\\d+/resource$", handler)` |
| `resource` | Static resource serving | `router.resource(new HttpResourceRoute("/", "/var/www"))` |
| `proxy` | Reverse proxy | `router.proxy("/api", "http://backend:8080")` |
| `ws` | WebSocket | `router.ws("/ws", new WebSocketResource())` |
| `h2c` | H2C upgrade | `router.h2c("/h2c")` |
| `sse` | SSE events | `router.route("/events", new SseHandler() {...})` |

Method filtering also supports `HttpMethodRoute` builder:

```java
router.exactRoute("/api", new HttpMethodRoute()
    .get(getHandler)    // GET requests
    .post(postHandler)  // POST requests
    .put(putHandler)    // PUT requests
);
```

### Context Path

```java
HttpRouterHandler router = new HttpRouterHandler("/my-app");
// Request /my-app/api/users → subPath = /api/users
```

### Health Check

```java
router.healthRoute("/health");  // Built-in endpoint, returns uptime, route stats, etc.
```

### Reverse Proxy Configuration

```java
HttpProxyConfig config = HttpProxyConfig.target("http://upstream:8080")
    .upgrade(true)                  // Supports H2→H1 protocol conversion
    .rewrite(path -> path.replaceFirst("^/api", ""))  // Path rewriting
    .connectionTimeout(3000)        // Connection timeout
    .readTimeout(5000)              // Read timeout
    .changeOrigin(true)             // Modify Host header
    .addHeader("X-Real-IP", "$remote_addr");  // Dynamic header variables

router.proxy("/api", config);       // Register with the router
```

Supported header variables: `$remote_addr`, `$remote_port`, `$host`, `$scheme`, `$request_uri`, `$query_string`, `$server_addr`, `$server_port`

---

## Annotation-driven MVC Framework

wastnet ships a built-in **annotation routing + lightweight dependency injection (DI) + message converter SPI** mini-MVC framework (in `io.github.wycst.wastnet.http.annotation`), letting you write HTTP services declaratively with annotations, with no third-party framework required — functionally equivalent to a slim Spring MVC.

### Basic Usage

```java
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;

AnnotationRouterHandler router = new AnnotationRouterHandler()
        .messageConverter(myConverter)          // enable @RequestBody/@ResponseBody auto-conversion
        .property("app.prefix", "Member-")      // config property for @Value injection
        .scanPackages("io.example.mvc");        // scan controller / component packages
```

### Common Annotations

| Annotation | Target | Description |
|:-----------|:------:|:------------|
| `@Controller(value)` | Class | Marks a controller; `value` is the base path prefix for all endpoints |
| `@RestController(value)` | Class | Controller whose methods default to `@ResponseBody` |
| `@Endpoint(value, allowMethods, responseType)` | Method | Marks an HTTP endpoint; `allowMethods` restricts HTTP methods, `responseType` declares the response type (default JSON) |
| `@RequestBody` | Parameter | Deserialize a POJO from the request body (requires messageConverter) |
| `@ResponseBody` | Method | Auto-serialize the return value to the response body (requires messageConverter) |
| `@RequestParam(value, required, defaultValue)` | Parameter | Bind query/form parameters; supports arrays/collections/file upload |
| `@PathParam(value)` | Parameter | Bind a path template variable (`${id}` or `{id}`, with inline regex `{id:pattern}`) |
| `@Sse(value)` | Method | Marks an SSE endpoint (method receives a single `SseEmitter` parameter) |
| `@WebSocket(value)` | Class | Marks a class extending `WebSocketResource` as a WebSocket endpoint |
| `@Component(value)` | Class | Managed component, auto-instantiated and registered in the container |
| `@Configuration` + `@Bean` | Class/Method | Configuration source whose `@Bean` methods produce beans in the container |
| `@Inject(value)` | Field/Parameter | Dependency injection; empty `value` resolves by type, otherwise by name |
| `@Value(value)` | Field/Parameter | Inject a config value, supporting `${key:default}` placeholders |
| `@PostConstruct` / `@PreDestroy` | Method | Lifecycle callbacks (after bean init / on container clear) |

### Controller Example

```java
@Controller("/api/user")                     // base path
public class UserController {

    @Inject
    private UserService userService;          // field dependency injection

    @ResponseBody
    @Endpoint("/list")                        // GET /api/user/list
    public String list() {
        return userService.listUsers();       // return value auto-serialized
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

Managed component example:

```java
@Component
public class UserService {

    @Value("${app.prefix:User-}")
    private String namePrefix;

    @PostConstruct
    public void init() { /* called after dependency injection */ }

    @PreDestroy
    public void close() { /* called on container clear */ }
}
```

### Configuration & Dependency Injection

`AnnotationRouterHandler` supports these chained configuration methods:

- `messageConverter(HttpMessageConverter)` — set the message conversion SPI (prerequisite for `@RequestBody`/`@ResponseBody`; typically implemented with Jackson/Gson or wastnet's built-in `JSON`)
- `property(key, val)` / `properties(map)` — set config properties available for `@Value` injection
- `loadProperties("application.properties")` — load config from classpath `.properties` files
- `loadConfig(ConfigLoader)` — load config via a custom `ConfigLoader` (e.g. YAML)
- `requestBodyBy(...)` / `responseBodyBy(...)` / `pathParamBy(...)` / `requestParamBy(...)` — customize which annotations are treated as request body / response body / path variable / request parameter
- `valueBy(...)` / `injectBy(...)` / `postConstructBy(...)` / `preDestroyBy(...)` — customize the `@Value`/`@Inject`/lifecycle annotations
- `annotationResolver(AnnotationResolver)` — bridge third-party annotation systems (e.g. Spring Boot's `@RestController`/`@RequestMapping`/`@Service`/`@Autowired`/`@Value`)

### Response Type (ContentType)

Declare the response type an endpoint produces via `@Endpoint(responseType = ...)`; it is resolved into a `ConverterConfig` at scan time so the `HttpMessageConverter` can decide how to serialize the return value. Encoding is always UTF-8.

```java
@Controller("/api")
public class DemoController {

    @Endpoint("/hello")                                // default JSON
    public User hello() { return new User(); }

    @Endpoint(value = "/hello", responseType = ContentType.TEXT)   // plain text
    public String text() { return "hello world"; }
}
```

`ContentType` enum: `JSON` (default), `TEXT`, `HTML`, `XML`, `CUSTOM` (custom, converter decides).

In the converter, judge the type via `ConverterConfig`:

```java
.messageConverter(new HttpMessageConverter() {
    @Override
    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
        if (config.isTextual()) {                    // XML / TEXT / HTML → output as string
            response.contentType(config.getContentType()).body(String.valueOf(value));
            return;
        }
        if (config.isJson()) {                       // JSON → object serialization
            response.contentType(config.getContentType());
            response.body(JSON.toJsonBytes(value));
            return;
        }
        // CUSTOM → the converter decides the content-type
        response.contentType(ContentType.JSON.getContentType()).body(JSON.toJsonBytes(value));
    }
})
```

Common `ConverterConfig` judgment methods:

| Method | Description |
|:-------|:------------|
| `getResponseType()` | Returns the `ContentType` enum |
| `getContentType()` | Returns the full Content-Type header value with UTF-8 (`null` for `CUSTOM`) |
| `isTextual()` | Whether a textual type (`XML`/`TEXT`/`HTML`) |
| `isJson()` / `isXml()` / `isText()` / `isHtml()` | Whether the specific type |
| `isCustom()` | Whether the custom type |
| `beforeResponseBody(request, response, result)` | Instance hook before response body write; override for content negotiation etc. (default no-op) |

### WebSocket / SSE Annotations

```java
@WebSocket("/ws/chat")
public class ChatWebSocket extends WebSocketResource {
    public ChatWebSocket() { super(30); }   // 30s idle timeout

    @Override
    public void onMessage(WebSocketConnection conn, String msg) throws IOException {
        conn.sendText("echo: " + msg);
    }
}
```

A complete runnable example is in the `wastnet-test` module at `examples/http/mvc/` (start with `MvcDemo`).

---

## HTTP/2 Support

### Features

- HPACK header compression (static table + dynamic table)
- Huffman encoding/decoding (high-performance bitwise implementation)
- Flow control (connection-level + stream-level WINDOW_UPDATE)
- Multiplexing (concurrent Stream processing)
- H2C upgrade (non-encrypted H2)
- H2→H1 proxy conversion
- 103 Early Hints
- ALPN negotiation (`.h2()`)
- Trailer blocks (`getTrailers()` / `setTrailersListener(TrailersListener)`, RFC 7540 §8.1)
- Connection monitoring (enable with `-Dwastnet.h2.monitor=true`; `H2Monitor.global()` returns a snapshot of live H2 connections to locate stuck/leaked connections)

### Enable H2

```java
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

> **JDK Version Requirement**: h2 (HTTP/2 over TLS) relies on ALPN negotiation and requires JDK 9+.
> h2c (HTTP/2 Cleartext) uses plaintext HTTP Upgrade and works on JDK 8+ (see [h2c upgrade](docs/02-guide/h2-server-integration.md#h2c-升级明文)).
> For intranet scenarios, use `.applicationProtocols("h2c")` to enable H2_PRIOR_KNOWLEDGE mode and save one RTT (HTTP/1.1 fallback is always automatically supported, no need to declare).

---

## 103 Early Hints

`HttpResourceRoute` supports sending 103 Early Hints (RFC 8297), notifying the browser to preload resources before returning index.html:

```java
new HttpResourceRoute("/", docBase)
    .earlyHints(
        "<$base_path/style.css>; rel=preload; as=style",
        "<$base_path/app.js>; rel=preload; as=script"
    );
```

- `$base_path` is automatically replaced with `contextPath`, no manual concatenation needed
- H1 sends raw 103 text, H2 sends HEADERS frame (`:status=103`)
- Only triggered when the default index page is requested, does not affect specific resource requests

---

## Static Resource Serving

```java
// Basic usage
router.resource(new HttpResourceRoute("/", "/var/www"));

// Custom default file
router.resource(new HttpResourceRoute("/", "/var/www", new File("index.htm")));

// Security control
new HttpResourceRoute("/", docBase)
    .allowAllMethods()     // Allow non-GET methods (GET only by default)
    .notAllowedBody("Custom 405");  // Custom 405 response
```

- Auto-detects `index.html` / `index.htm` as default pages
- Path traversal protection (`..` detection)
- Only `GET` method allowed by default, others return 405

---

## WebSocket

| Event | Method | Description |
|:------|:-------|:------------|
| Connection Open | `onOpen(WebSocketConnection)` | New connection |
| Text Message | `onMessage(WebSocketConnection, String)` | UTF-8 text |
| Binary Message | `onBinary(WebSocketConnection, byte[])` | Binary data |
| Connection Close | `onClose(WebSocketConnection, int, String)` | Close event |
| Error Close | `onErrorClose(WebSocketConnection)` | Abnormal disconnection |

`WebSocketConnection` API:

```java
connection.sendText("message");    // Send text
connection.sendBinary(data);       // Send binary
connection.ping();                 // Ping frame
connection.close();                // Close connection
```

Supports configurable idle timeout (seconds):

```java
new WebSocketResource(60) { ... }  // Auto-close after 60 seconds of inactivity
```

---

## Server-Sent Events (SSE)

Server-sent events (SSE) support via `SseEmitter`, thread-safe with auto-close timeout.

### Basic Usage

```java
router.route("/events", new SseHandler() {
    @Override
    public void handle(SseEmitter emitter) {
        for (int i = 0; i < 10; ++i) {
            emitter.emit("count: " + i);
            Thread.sleep(1000);
        }
        emitter.close();
    }
});
```

### SseEmitter API

| Method | Description |
|:-------|:------------|
| `emit(String data)` | Send data-only event |
| `emit(String event, String data, String id, long retry)` | Full control (null/zero fields omitted) |
| `close()` | Close connection |

The SSE idle timeout is configured via the `router.sse(path, timeoutMs, handler)` overload (default `HttpConf.SSE_TIMEOUT_MS`); the connection auto-closes after no events for the specified milliseconds.

See `docs/02-guide/sse-guide.md` for details.

---

## File Upload

### Basic Usage

```java
router.exactRoute("/upload", (path, request, response) -> {
    if (!request.isMultipart()) {
        response.status(400).body("Not multipart".getBytes());
        return;
    }

    // Iterate over upload fields
    for (String fieldName : request.getMultipartFieldNames()) {
        MultipartField field = request.getMultipartField(fieldName);

        if (field.isFile()) {
            // File field: zero-copy via transferTo
            field.transferTo(new File("/tmp/upload.bin"));
        } else {
            // Regular form field
            System.out.println(fieldName + " = " + field.getDataAsString());
        }
    }

    response.body("OK".getBytes());
});
```

### Large File Support

Files exceeding the memory threshold are automatically spilled to temporary files, using streaming reads to avoid OOM:

```java
MultipartField field = request.getMultipartField("largeFile");
InputStream in = field.getInputStream();  // Streaming, no memory load
field.transferTo(new File("/dest/file.zip"));  // Or direct transfer to target file
```

### Configuration

| Property | Default | Description |
|:---------|:-------:|:------------|
| `wastnet.http.max-body-in-memory` | 2MB | Max request body kept in memory, beyond which streaming is used (KB/MB/GB units supported) |
| `wastnet.http.body-max-size` | 512MB | Max request body size limit (bytes); ≤0 means unlimited; KB/MB/GB units supported |
| `wastnet.http.enable-temp-file` | true | Enable temporary file spill |
| `wastnet.http.temp-file-dir` | System temp dir | Temporary file directory |

See `docs/02-guide/file-upload-guide.md` for detailed usage.

---

## HTTP Request / Response API Overview

Commonly used `HttpRequest` / `HttpResponse` APIs beyond file upload (see `docs/02-guide/response-api.md` for the full reference).

### Request (HttpRequest)

| Category | Methods |
|:---------|:--------|
| Body | `getBodyData()`, `isStream()`, `isCompleted()`, `complete()` |
| Parameters | `getParameter(name)`, `getParameterValues(name)`, `getParameterMap()`, `getParameterNames()`, `getQueryString()`, `getUriParameter()`, `getBodyParameter()` |
| URL/URI | `getUri()`, `getRequestUri()`, `getRequestURL()`, `getDecodedRequestURL()`, `getHost()` |
| Address | `getServerAddress()`, `getServerHost()`, `getServerPort()`, `getRemoteHost()`, `getRemotePort()` |
| Content | `isJson()`, `isFormUrlencoded()`, `getCharset()`, `isMultipart()` |
| ID/Security | `getRequestId()`, `getConnectionId()`, `isSSL()` |

### Response (HttpResponse)

```java
response.contentType("application/json")        // set Content-Type
        .status(200)                             // set status code
        .body(bytes);                            // set response body

// Zero-copy file send (only works for non-SSL connections)
response.sendFile(new File("/path/file.zip"));

// Chunked transfer encoding
response.setChunkedEncoding();

// Response-level SSE / 103 Early Hints
response.sse("data", "hello");                   // push an SSE event
response.earlyHints("<style.css>; rel=preload"); // send 103

// Streaming output and manual commit
response.outputStream();
response.setAutoCommit(false);                   // disable auto-commit to control response timing
```

| Category | Methods |
|:---------|:--------|
| File/Compression | `sendFile(File)`, `sendFile(File, boolean, int)`, `sendFile(File, boolean, String, String)`, `isGzipSupported()` |
| Chunked | `setChunkedEncoding()`, `setChunked(boolean)`, `chunked()`, `isChunked()`, `removeChunkedEncoding()` |
| Encoding/Cache | `setCharacterEncoding()`, `setLastModified()` |
| Lifecycle | `setAutoCommit()`, `isAutoCommit()`, `reset()`, `isCommitted()`, `isCorrupted()` |

---

## SSL/TLS Configuration

### PEM Certificate (Recommended)

```java
// From filesystem path (absolute or relative to working dir)
server.pemSSL("/etc/ssl/cert.pem", "/etc/ssl/key.pem");

// From classpath (auto-detected when not a filesystem file)
server.pemSSL("cert/cert.pem", "cert/server.pem");

// From input stream
server.pemSSL(certInputStream, keyInputStream);
```

### JKS Certificate

```java
server.ssl(true).sslContext(sslContext);
```

---

## Configuration

> All configuration items have sensible defaults. The server runs out-of-the-box without any configuration. Only tune these for production deployment or specific performance requirements.

For the complete configuration reference (all properties, loading priorities, and tuning guides), see:

- [HTTP Configuration Reference](docs/03-reference/http-conf-reference.md)
- [Socket Configuration Reference](docs/03-reference/socket-conf-reference.md)

### NioConfig

```java
import io.github.wycst.wastnet.socket.tcp.NioConfig;

NioConfig config = new NioConfig();
config.setWorkerNum(8);                     // Worker thread count
config.setSyncRunner(true);                 // Sync execution mode
config.setReadBufferSize(8192);             // Read buffer (plaintext connections only; SSL uses built-in ~16KB protocol buffer)
config.setWriteBufferSize(32768);           // Write buffer (plaintext connections only; SSL uses built-in ~16KB protocol buffer)
config.setSslHandshakeTimeoutMs(5000);      // SSL handshake timeout
config.setAllowPlaintextWhenSslEnabled(true); // Allow plaintext on TLS port

HTTPServer.of(8080, config).requestHandler(router).start();
```

`NioConfig` (`io.github.wycst.wastnet.socket.tcp`) is shared by `TCPServer` and `TCPClient`; pass it to `HTTPServer.of(int, NioConfig)` / `new TCPServer(int, NioConfig)` / `new TCPClient(host, port, NioConfig)`.

---

## Startup Banner

The server prints a startup banner with URL and timing information by default. Use `startupBannerEnabled(false)` to disable it:

```java
HTTPServer.of(8080)
    .startupBannerEnabled(false)
    .requestHandler(...)
    .start();
```

Default output:

```
  wastnet/1.0.0 started in 1363 ms
  ➜  Local:   http://localhost:8080
  ➜  Network: http://10.252.31.235:8080
```

For full customization, extend `HTTPServer` and override `onStarted()`:

```java
class MyServer extends HTTPServer {
    @Override
    protected void onStarted() {
        System.out.println("MyServer ready on port " + port);
    }
}
```

> **Note**: Subclasses of `TCPServer` can also override `onStarted()` for custom output.

---

## Exception Handling

```java
router.notFoundHandler((request, response) -> {
    response.status(404).body("Custom 404".getBytes());
});

// Global exception handling
server.exceptionHandler((request, response, exception) -> {
    response.status(500)
            .contentType("application/json")
            .body("{\"error\":\"Internal Error\"}".getBytes());
});

// Debug mode (print stack traces)
server.printStackTraceError(true);
```

---

## Server Observer (HttpServerObserver) & Interceptor (HttpServerInterceptor)

`HttpServerObserver` is a **passive observer SPI** for injecting monitoring logic (metrics, distributed tracing, audit logs) at request/connection lifecycle points. The framework itself ships **no implementation**; provide one at the application layer. `HttpServerInterceptor` is the **active request interception SPI**, invoked before the business handler for auth, CORS preflight, or rate limiting.

### Observer Registration

```java
HTTPServer.of(8080)
        .observer(myObserver)
        .requestHandler(router)
        .start();
```

Observer callbacks (all run on the I/O worker thread, so implementations must be non-blocking and cheap):

| Method | Trigger Point | Description |
|:-------|:--------------|:------------|
| `onRequestStart(HttpRequest)` | Request handling starts | Accumulate request counts |
| `onRequestComplete(HttpRequest, HttpResponse, durationNanos, error)` | After response committed | Includes duration (nanos) and error (non-null on 5xx) |
| `onConnectionOpen(ChannelContext)` | Connection established | Count active connections |
| `onConnectionClose(ChannelContext)` | Connection closed | Decrement active connections |

### Interceptor Registration

`HttpServerInterceptor` has a single method:

```java
boolean beforeHandle(HttpRequest request, HttpResponse response, ChannelContext ctx) throws Exception;
```

Return `true` to proceed to the business handler, or `false` if the response has already been written and the business handler should be skipped (e.g. auth rejection, CORS preflight, rate limiting).

```java
HTTPServer.of(8080)
        .interceptor((request, response, ctx) -> {
            // Auth: reject with 401 and intercept if no token
            if (request.getHeader("Authorization") == null) {
                response.status(401).body("Unauthorized".getBytes());
                return false;   // skip business handler
            }
            return true;        // continue
        })
        .requestHandler(router)
        .start();
```

> **Concurrency safety**: `HttpServerChannelHandler` assembles the observer/interceptor (set via `observer(...)` / `interceptor(...)`) into an immutable `HttpRequestLifecycleDelegate` in `prepare()` at `start()`. Configuration must be done **before** `start()`; setting after `start()` has no effect. If the observer implements `io.github.wycst.wastnet.socket.handler.ClearableHandler`, the framework calls its `clear()` on server stop.

---

## Graceful Shutdown

`TCPServer.shutdownGraceful()` provides graceful shutdown:

```java
server.shutdownGraceful();   // stop accepting → drain in-flight requests → force close
server.stop();               // stop directly (restartable)
server.stop(false);          // stop but keep connections
```

Execution flow (best-effort):
1. **Stop accepting**: close the server channel; no new connections are accepted (workers keep running)
2. **Drain in-flight requests**: wait for in-flight requests to finish, up to `gracefulShutdownTimeout`
3. **Force close**: close remaining connections and release thread pools, sending H2 GOAWAY / WebSocket CLOSE frames on close

> **Note**: This is a best-effort implementation; it does not guarantee all application-layer in-flight requests complete. For a fully clean shutdown, still coordinate with load-balancer drain and health-check removal.

---

## Idle Connection Detection

`IdleStateHandler` triggers a callback when a connection has no read/write activity beyond the configured timeout:

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

Constructor Parameters:

| Parameter | Description |
|:----------|:------------|
| `readerIdleTime` | Read idle timeout (≤ 0 disables) |
| `writerIdleTime` | Write idle timeout (≤ 0 disables) |
| `unit` | Time unit (`TimeUnit.SECONDS`, `MILLISECONDS`, etc.) |
| `mode` | Optional idle mode: `EXCLUSIVE` (default, per-connection scheduling, nanosecond precision, sufficient for most scenarios) or `SHARED` (worker-level scan, ~1s precision, for high-concurrency scenarios) |

SHARED mode example (for high-concurrency scenarios; default mode is sufficient for regular use):

```java
server.idleStateHandler(new IdleStateHandler(30, 0, TimeUnit.SECONDS, Mode.SHARED) {
    @Override
    public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType idleType,
                                 long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
        ctx.close();
    }
});
```

Callback Parameters (`onIdleTriggered`):

| Parameter | Description |
|:----------|:------------|
| `idleType` | Idle type: `IdleType.Read` (read idle) or `IdleType.Write` (write idle) |
| `triggerTotalCount` | Total idle triggers since the connection was established |
| `triggerConsecutiveCount` | Consecutive idle triggers since the last read/write activity. Useful for graduated handling: warn on first, close after several |

---

## Connection Filter (ConnectionFilter)

Intercepts connections at the TCP accept stage, suitable for IP blacklist/whitelist, connection count rate limiting, etc. **Zero resource waste** (rejected connections do not create ChannelContext or ByteBuffer).

```java
// IP blacklist
final Set<String> blacklist = new HashSet<String>(Arrays.asList("192.168.1.100", "10.0.0.5"));
HTTPServer.of(8080)
    .connectionFilter(ch -> !blacklist.contains(
        ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress()))
    .requestHandler(router)
    .start();

// IP-level connection rate limiting
final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<String, AtomicInteger>();
HTTPServer.of(8080)
    .connectionFilter(ch -> {
        String ip = ((InetSocketAddress) ch.getRemoteAddress()).getAddress().getHostAddress();
        return counters.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() <= 50;
    })
    .requestHandler(router)
    .start();
```

The `connectionFilter` executes in `AcceptDispatcher`. If the filter throws an exception or returns `false`, the connection is automatically closed and logged, without affecting the Acceptor main loop.

---

## Project Structure

```
wastnet/                               ← Parent project (pom)
├── pom.xml                              ← Module management, version management
│
├── wastnet-core/                       ← Core network framework (jar)
│   ├── pom.xml                          ← zero external dependencies
│   └── src/main/java/io/github/wycst/wastnet/
│       ├── socket/                  # TCP core module
│       │   ├── tcp/                 # Server, connection context, NioConfig
│       │   ├── handler/            # Business handlers, idle detection
│       │   ├── channel/            # Codecs
│       │   ├── conf/               # Static configuration items
│       │   └── protocol/           # ObjectCodec and other protocols
│       ├── http/                   # HTTP core
│       │   ├── HTTPServer.java     # HTTP server
│       │   ├── annotation/         # Annotation routing + light DI (MVC)
│       │   ├── handler/            # Router, resources, exception handlers, interceptor/observer
│       │   ├── h2/                 # HTTP/2 (HPACK/Huffman/frames/streams)
│       │   ├── proxy/              # Reverse proxy
│       │   ├── upgrade/            # Protocol upgrade (WebSocket/H2C)
│       │   ├── extension/          # HttpServerObserver / HttpServerInterceptor
│       │   └── reader/             # HTTP request decoding
│       ├── env/                    # JDK version compatibility layer (ALPN, etc.)
│       ├── log/                    # Built-in logging
│       ├── util/                   # Utilities
│       └── exception/              # Exception definitions
│
├── wastnet-test/                       ← Tests and examples (jar)
│   ├── pom.xml                          # depends on wastnet-core
│   ├── src/main/java/                   # Test code + runnable examples
│   │   └── ... (HTTP decoders, HTTP/2, WebSocket, TCP tests)
│   ├── src/main/resources/              # Certificates, keystores, demo pages
│   └── test-files/                      # Test data files
│
└── docs/
│   ├── 02-guide/                       # User guides
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
│   ├── 03-reference/                   # Configuration/protocol references
│   │   ├── http-conf-reference.md
│   │   ├── socket-conf-reference.md
│   │   ├── HTTP2_PROTOCOL.md
│   │   ├── huffman-table-design.md
│   │   ├── websocket-*.md
│   ├── 05-standards/                   # RFC standards
│   │   └── rfc*.md
│   └── 04-architecture/               # Architecture principles
│       └── TCPSERVER_ARCHITECTURE.md
```

---

## Detailed Documentation

| Document | Content |
|:---------|:--------|
| `docs/02-guide/http-router-guide.md` | Route matching, Context Path, reverse proxy configuration |
| `docs/02-guide/response-api.md` | Response API: status codes, headers, chunked, GZIP, sendFile |
| `docs/02-guide/file-upload-guide.md` | File upload: Multipart API, large file streaming, configuration |
| `docs/02-guide/sse-guide.md` | SSE server push: Emitter API, timeout control |
| `docs/02-guide/websocket-advanced-api.md` | WebSocket advanced API: frame encoding, Ping-Pong, fragments |
| `docs/02-guide/h2-server-integration.md` | H2/H2C server setup: TLS ALPN, cleartext upgrade |
| `docs/02-guide/length-codec-guide.md` | Length-prefixed frame codec: custom binary protocols |
| `docs/02-guide/http-observer-guide.md` | Server observer (HttpServerObserver): metrics, tracing, audit |
| `docs/02-guide/http-interceptor-guide.md` | Server interceptor (HttpServerInterceptor): auth, CORS, rate limiting |
| `docs/03-reference/http-conf-reference.md` | HTTP configuration reference (with tuning tips) |
| `docs/03-reference/socket-conf-reference.md` | Socket configuration reference |
| `docs/03-reference/HTTP2_PROTOCOL.md` | H2 connection setup, frame structure, HPACK, flow control |
| `docs/03-reference/websocket-cheatsheet.md` | WebSocket frame type quick reference |
| `docs/03-reference/websocket-implementation-guide.md` | WebSocket implementation details |
| `docs/04-architecture/TCPSERVER_ARCHITECTURE.md` | TCP server architecture principles |

---

## Performance Tuning

| Scenario | Recommended Workers | Mode | Buffer |
|:---------|:------------------:|:----:|:------:|
| API service (small payload) | CPU cores | Sync | 1-4 KB |
| Static files (large payload) | CPU cores × 2 | Async | 16-64 KB |
| Reverse proxy | CPU cores × 2 | Sync | 4-8 KB |
| WebSocket long connections | CPU cores | Async | 2-4 KB |

---

## Comparison with Other Frameworks

| Feature | wastnet | Netty | Vert.x |
|:--------|:--------:|:-----:|:------:|
| Dependencies | None | Multiple | Multiple |
| Learning curve | Low | Medium | Medium |
| HTTP routing | Built-in | Needs codec | Built-in |
| HTTP/2 | Built-in | Needs handler | Built-in |
| WebSocket | Built-in | Needs handler | Built-in |
| Reverse proxy | Built-in | Manual | Extension needed |
| Zero copy | ✅ | ✅ | ✅ |
| Memory pool | Connection-level reuse | Configurable | Configurable |

---

## Benchmark Comparison Report

The data below is produced by the automated benchmark scripts `bench.sh` / `bench-start.bat`, comparing **wastnet** against **Undertow** (the JBoss high-performance NIO HTTP server). Each framework runs as an independent process listening on three separate ports: h1 / h2 / h2c.

### Test Environment

| Item | Configuration |
|:-----|:--------------|
| Server framework | wastnet vs Undertow |
| Benchmark tools | `wrk` (H1 short-lived connections), `h2load` (H1/H2/H2C long-lived connections) |
| GET parameters | `-n 10000 -c 10 -m 100 -D 15` (10 concurrent, 100 streams/connection, 15s duration) |
| POST parameters | `-n 1000 -c 10 -t 4 -d payload.bin` (small body; parameters differ from the GET scenario, not directly comparable) |
| Hardware - Windows | Intel i9, 32 cores, memory set by the launch script; acts as the Server host in the cross-machine scenario |
| Hardware - Linux | CentOS 7, 16 cores, memory set by the launch script; acts as the VM running both the cross-machine Client and the same-machine Server/Client |
| Topology A (cross-machine) | Server runs on the Windows host `192.168.5.1` (i9, 32 cores); `h2load` runs on the same-subnet CentOS7 VM (16 cores) |
| Topology B (same-machine) | Both Server and `h2load` run on the CentOS7 VM `localhost` (16 cores, loopback, no network overhead) |

> Note: In the cross-machine scenario the Server (i9, 32 cores) is significantly more powerful than the Client (16-core VM), so the Client-side `h2load` is more likely to become the bottleneck first. The cross-machine numbers therefore mainly reflect **the efficiency difference of each framework's network IO model on the Server side**, rather than a pure compute-power comparison. In the same-machine scenario the Server/Client share 16 cores with intense CPU contention, which better stresses each framework's scheduling under constrained resources.

> All scenarios reported 0 failures, 0 errors, 0 timeouts. H2 negotiated as `h2` (ALPN, `TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384`); H2C as `h2c` (H2_PRIOR_KNOWLEDGE, cleartext).

### Cross-Machine Benchmark (Server: 192.168.5.1 / Windows, Client: Linux VM)

| Protocol | wastnet (req/s) | undertow (req/s) | wastnet advantage |
|:--------|:---------------:|:----------------:|:-----------------:|
| HTTP/1.1 (wrk)         | 22,962 * | 22,280 * | +3.1% |
| HTTP/1.1 (h2load)      | 161,186 | 130,780 | **+23.2%** |
| HTTP/2 over TLS (h2)   | 103,892 | 79,544  | **+30.6%** |
| HTTP/2 cleartext (h2c) | 174,081 | 97,613  | **+78.3%** |

\* `wrk` reports `Requests/sec` (7 threads, 200 connections, short-lived scenario), a different scope from h2load's long-lived connections; for reference only.

**Conclusion**: Under cross-machine network conditions, wastnet **leads Undertow across all three protocols**, with the largest gap on H2C cleartext (≈ +78%) and the next on H2 (≈ +31%). Without TLS handshake and crypto overhead, wastnet's lock-free Reactor model amplifies its throughput advantage.

### Same-Machine Benchmark (Server & Client: localhost / Linux VM)

| Protocol | wastnet (req/s) | undertow (req/s) | Leader |
|:--------|:---------------:|:----------------:|:------:|
| HTTP/1.1 (wrk)         | 340,063 * | 329,911 * | wastnet (+3.1%) |
| HTTP/1.1 (h2load)      | 320,845 | 327,000 | undertow (+1.9%) |
| HTTP/2 over TLS (h2)   | 87,731  | 97,866  | undertow (+11.6%) |
| HTTP/2 cleartext (h2c) | 137,307 | 207,489 | **undertow (+51.1%)** |

\* `wrk` as above, for reference only.

**Conclusion**: On the loopback same-machine scenario, H1 is roughly tied; but on H2 / H2C **Undertow pulls ahead**, especially H2C by ≈ 51%. This reverses the cross-machine result — under zero network latency where CPU becomes the bottleneck, Undertow's H2 stack (JDK-native ALPN + mature HPACK) processes single connections more efficiently, while wastnet's H2 path still has room for optimization (e.g. HPACK dynamic table, stream scheduling).

### Overall Interpretation

- **Network is H2's hidden bottleneck**: Cross-machine, TLS handshake (connect ~400ms, first-byte ~480ms) and network RTT dominate latency, and wastnet's lightweight connection model better absorbs network overhead; same-machine, these costs vanish and raw compute efficiency decides the winner.
- **H2C is wastnet's strength (cross-machine)**: Without TLS, wastnet's lead is largest, making it well suited for high-performance inter-service communication within a LAN.
- **H2 is wastnet's same-machine weak spot**: On loopback, wastnet's H2 throughput is about 90% (h2) / 66% (h2c) of Undertow's; future versions will focus on optimizing H2 frame scheduling and header compression.

### POST Scenario (small body, parameters `-n 1000 -c 10 -t 4`, absolute values for reference)

| Protocol | wastnet (192.168.5.1) | undertow (192.168.5.1) | wastnet (localhost) | undertow (localhost) |
|:--------|:---------------------:|:----------------------:|:-------------------:|:--------------------:|
| H1  | 57 req/s  | 48 req/s  | 2,817 req/s | 5,834 req/s |
| H2  | 761 req/s | 65 req/s  | 13,258 req/s| 1,969 req/s |

> Note: The POST scenario uses only 1000 requests and 4 threads — a small statistical window with high variance, and the two frameworks' body-handling paths differ significantly (Undertow's same-machine POST H1 spike may relate to its body buffering strategy). **Not recommended as a basis for cross-framework comparison**; recorded here as raw values only.

Full raw logs are under `bench-results/` (`wastnet|undertow` × `192.168.5.1|localhost`, suffix `-2026-08-08.txt`).

---

## Roadmap

- [x] HTTP/2 full implementation (HPACK + Huffman + flow control + multiplexing)
- [x] Reverse proxy (H2→H1 conversion, URL rewriting, header variables)
- [x] 103 Early Hints (RFC 8297)
- [x] TCP client (auto-reconnect, custom codec, SSL/TLS)
- [x] SSE (Server-Sent Events)
- [x] Protocol codec (LengthFrameCodec, ObjectCodec)
- [x] Annotation routing + light DI (AnnotationRouterHandler, bridges Spring annotations)
- [x] Monitoring metrics (HttpServerObserver / HttpServerInterceptor SPI + Prometheus example)
- [ ] HTTP/3 (QUIC) support (reserved)
- [ ] Connection pool
- [ ] UDP support

---

## License

This project is licensed under the [Apache 2.0](LICENSE) License.
