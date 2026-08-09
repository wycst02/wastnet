# HTTP Router 使用指南

`HttpRouterHandler` 是 wastnet 内置的路由分发器，支持精确匹配、前缀匹配、正则匹配、静态资源服务和反向代理。配合 `HTTPServer` 可以快速搭建功能完整的 HTTP 服务。

---

## 目录

- [快速启动](#快速启动)
- [路由匹配规则](#路由匹配规则)
- [Context Path](#context-path)
- [内置 Handler](#内置-handler)
- [自定义 HttpRoute](#自定义-httproute)
- [静态资源服务](#静态资源服务)
- [反向代理](#反向代理)
  - [基本代理](#基本代理)
  - [路径重写](#路径重写)
  - [协议升级（WebSocket / h2c）](#协议升级websocket--h2c)
  - [请求头管理](#请求头管理)
  - [超时配置](#超时配置)
  - [代理内置变量](#代理内置变量)
- [404 处理](#404-处理)
- [异常处理](#异常处理)
- [完整示例](#完整示例)

---

## 快速启动

### 最简 HTTP 服务

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;

HTTPServer.of(8080)
    .requestHandler(new HttpRequestHandler() {
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            response.status(200).write("Hello World".getBytes());
        }
    })
    .start();
```

### 使用 Router 的 HTTP 服务（推荐）

对于大部分业务场景，推荐使用 `HttpRouterHandler` 而非原始的 `requestHandler()`，它提供了路由分发、Context Path、WebSocket、反向代理等内置能力。

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

HttpRouterHandler router = new HttpRouterHandler();

router.route("/api", new HttpRoute() {
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.status(200).write("API response".getBytes());
    }
});

HTTPServer.of(8080).requestHandler(router).start();
```


---

## 路由匹配规则

`HttpRouterHandler` 提供两种路由注册方式，匹配优先级：**精确匹配 > 路由列表（按注册顺序）**。

### exactRoute — 精确匹配

路径必须完全相等才匹配，不支持正则。

```java
router.exactRoute("/user", new HttpRoute() {
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.status(200).write("User page".getBytes());
    }
});
```

| 请求路径 | 是否匹配 |
|---------|---------|
| `/user` | ✓ |
| `/user/` | ✗ |
| `/user/1` | ✗ |

### route — 前缀匹配 / 正则匹配

**不以 `^` 开头的路径**：作为前缀匹配。

```java
router.route("/api", new HttpRoute() { ... });
```

| 请求路径 | 是否匹配 |
|---------|---------|
| `/api` | ✓ |
| `/api/users` | ✓ |
| `/api/v1/data` | ✓ |
| `/apiv2` | ✗ |

**以 `^` 开头的路径**：作为正则匹配。

```java
router.route("^/v\\d+/resource$", new HttpRoute() { ... });
```

| 请求路径 | 是否匹配 |
|---------|---------|
| `/v1/resource` | ✓ |
| `/v2/resource` | ✓ |
| `/v1/resource/extra` | ✗ |

> 正则路由如果未以 `$` 结尾，框架会自动追加 `.*` 以匹配后续路径。

---

## Context Path

`HttpRouterHandler` 支持配置 Context Path，所有路由在 Context Path 之下匹配：

```java
// 所有路由以 /myapp 为前缀
HttpRouterHandler router = new HttpRouterHandler("/myapp");

router.route("/api", apiHandler);   // 匹配 /myapp/api, /myapp/api/xxx
router.exactRoute("/user", userHandler); // 匹配 /myapp/user
```

**规则：**
- 默认为 `"/"`
- 必须以 `/` 开头
- 尾部 `/` 会被自动移除

---

## 内置 Handler

### HttpWelcomeHandler

默认欢迎页，输出框架介绍和快速开始信息。当未设置 `requestHandler` 时自动生效。

### HttpServerChannelHandler

内置管道处理器，负责：
1. 检测协议升级（WebSocket / h2c）
2. 分发请求到 `requestHandler`
3. 异常处理和错误响应
4. 响应完成（`complete()`）和请求清理

---

## 自定义 HttpRoute

`HttpRoute` 是抽象类，只需实现 `handle(String path, HttpRequest request, HttpResponse response)`：

```java
HttpRoute userRoute = new HttpRoute() {
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        // path 是匹配后的子路径（已去除 contextPath）
        String userId = path.substring("/user/".length());
        response.status(200)
                .contentType("application/json;charset=utf-8")
                .write(("{\"id\":\"" + userId + "\"}").getBytes());
    }
};

router.route("/user", userRoute);
```

---

## 静态资源服务

`HttpResourceRoute` 提供本地文件映射服务：

```java
// 根路径映射
router.resource(new HttpResourceRoute("/path/to/static/files"));

// 指定路由前缀
router.resource(new HttpResourceRoute("/assets", "/path/to/static/files"));

// 指定默认文件
File defaultFile = new File("/path/to/static/files/index.html");
router.resource(new HttpResourceRoute("/assets", "/path/to/static/files", defaultFile));
```

**功能特性：**
- 自动识别 `index.html` / `index.htm` 作为默认文件
- 路径遍历防护（拒绝 `..` 路径）
- 默认仅允许 GET 方法，其他返回 405

**103 Early Hints（RFC 8297）：**

支持在返回默认索引页时发送 103 预加载提示：

```java
router.resource(new HttpResourceRoute("/", docBase)
        .earlyHints(
                "<$base_path/style.css>; rel=preload; as=style",
                "<$base_path/app.js>; rel=preload; as=script"
        ));
```

- `$base_path` 由路由器自动替换为 `contextPath`，无需手动拼接
- 仅在请求默认索引页（`/` 或 `/index.html`）时触发
- H1 下发送原始 HTTP 文本，H2 下发送 HEADERS 帧
- 详细文档见 [103 Early Hints](response-api.md#103-early-hints)
- 通过 `response.sendFile(file)` 高效发送文件

**缓存协商（304 Not Modified）：**

默认开启 HTTP 条件请求缓存协商。当客户端发送 `If-None-Match` 或 `If-Modified-Since` 请求头时，服务器返回 304 状态码和空 Body，客户端使用本地缓存。

可通过 `cacheEnabled(false)` 关闭，此时服务器始终返回完整文件：

```java
// 关闭缓存协商（适合频繁变化的动态资源）
router.resource(new HttpResourceRoute("/", docBase).cacheEnabled(false));
```

---

## 反向代理

`HttpProxyRoute` 是 `HttpRoute` 的子类，提供 HTTP 反向代理能力。通过 `HttpProxyConfig` 进行配置。

### 支持的协议组合

| 客户端 → 代理 | 代理 → 后端 | 状态 |
|---------------|-------------|------|
| HTTP/1.1 | HTTP/1.1 | ✅ 支持（PASSTHROUGH 透传） |
| HTTP/2 | HTTP/1.1 | ✅ 支持（H2H1ProxyAdapter 协议转换） |
| HTTP/2 | HTTP/2 | ✅ 支持（H2H2ProxyAdapter） |
| HTTP/1.1 | HTTP/2 | ✅ 支持（H1H2ProxyAdapter，继承 H2H2ProxyAdapter） |

> HTTP/2 → HTTP/1.1 代理需要将 H2 帧解码后转为 H1 请求发送到后端，并将 H1 响应转回 H2 帧格式返回给客户端。此功能通过 `H2H1ProxyAdapter` 实现；H2→H2 与 H1→H2 分别由 `H2H2ProxyAdapter`、`H1H2ProxyAdapter` 实现。

### 基本代理

```java
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000");
router.route("/api", new HttpProxyRoute(config));
```

所有以 `/api` 开头的请求会被代理到 `http://localhost:8000`，路径保持不变。

> 目标地址支持 HTTP 和 HTTPS。端口缺省时默认使用 80（HTTP）或 443（HTTPS）。

### 路径重写

#### replacePrefix — 前缀替换

```java
// /api/users → /users （去掉 /api 前缀）
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .replacePrefix("/api", "");

// /api/users → /v2/users （替换 /api 为 /v2）
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .replacePrefix("/api", "/v2");
```

#### replaceRegex — 正则替换

```java
// /api/users → /users
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .replaceRegex("^/api/(.*)$", "/$1");
```

#### rewrite — 自定义函数

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .rewrite(new HttpProxyConfig.RewriteFunction() {
        public String rewrite(String path) {
            return path.replaceFirst("^/rest", "");
        }
    });
```

#### 目标地址自带路径

如果目标 URL 本身包含路径，会自动作为前缀拼接：

```java
// /users → /backend/users
HttpProxyConfig.target("http://localhost:8000/backend")
```

> 当同时配置了 rewrite 规则时，rewrite 优先，目标路径前缀不生效。

### 协议升级（WebSocket / h2c）

当代理需要支持 WebSocket 或 HTTP/2 明文升级（h2c）时，启用 `upgrade`：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .upgrade(true);

router.route("/ws", new HttpProxyRoute(config));
```

**工作流程：**
1. 客户端发送携带 `Upgrade: websocket` 或 `Upgrade: h2c` 的请求
2. 代理将请求转发给目标服务器
3. 代理等待目标服务器返回 `HTTP/1.1 101 Switching Protocols`
4. 确认 101 后，代理注册客户端读事件，进入双向透传模式
5. 后续数据在客户端与目标服务器之间实时中继

> 协议升级判断基于每次请求（非连接级别），连接复用时仍能正确处理普通请求与升级请求。

### 请求头管理

#### 添加静态请求头

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .addHeader("X-Proxy", "wastnet")
    .addHeader("X-Custom-Id", "12345");
```

#### 添加动态请求头（内置变量）

值中包含 `$` 变量时自动识别为动态头：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .addHeader("X-Real-IP", "$remote_addr")
    .addHeader("X-Forwarded-Proto", "$scheme")
    .addHeader("X-Forwarded-Host", "$scheme://$host");
```

#### 添加动态请求头（自定义 Resolver）

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .addHeader("X-Request-Id", new HttpProxyConfig.HeaderValueResolver() {
        public String resolve(HttpRequest request) {
            return UUID.randomUUID().toString();
        }
    });
```

#### 删除请求头

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .removeHeader("X-Internal-Header", "Authorization");
```

#### 修改 Origin（changeOrigin）

默认 `changeOrigin = true`，代理会将请求的 `Host` 头改为目标服务器地址：

```java
// 保留原始 Host
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .changeOrigin(false);
```

#### 环路检测（loopDetection）

防止代理请求循环（例如 A → B → A），使用 `X-Forwarded-Proxy-{UUID}` 唯一标记检测请求循环：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .loopDetection(true);
```

启用后，代理会在转发请求时添加一个唯一标记头。如果后续再收到带有相同标记的请求，则说明发生了环路，代理会拒绝转发。

#### 后端 HTTP/2 支持

当当前代理服务器本身在服务 HTTP/2 连接时，可以尝试使用 HTTP/2 协议与后端通信：

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .http2(true);
```

> 注意：仅当客户端通过 HTTP/2 连接到代理服务器时此配置才生效。如果客户端通过 HTTP/1.1 连接，无论此配置如何，代理始终使用 HTTP/1.1 与后端通信。

### 超时配置

```java
HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8000")
    .connectionTimeout(3000)   // 连接目标服务器超时（毫秒），默认 5000
    .readTimeout(10000);       // 等待目标响应超时（毫秒），默认 60000
```

- **connectionTimeout**：与目标服务器建立 TCP 连接的超时时间
- **readTimeout**：代理等待目标服务器首次响应数据的超时时间，超时后返回 `504 Gateway Timeout`

### 代理内置变量

类似 Nginx 变量，可在动态请求头值中使用：

| 变量 | 说明 |
|------|------|
| `$remote_addr` | 客户端 IP 地址 |
| `$remote_port` | 客户端端口 |
| `$host` | 客户端原始 Host 请求头 |
| `$scheme` | 请求协议（http / https） |
| `$request_uri` | 解码后的请求 URI（不含查询参数） |
| `$uri` | 原始请求 URI（含查询参数） |
| `$query_string` | 查询字符串（`?` 之后的部分） |
| `$server_addr` | 服务器 IP 地址 |
| `$server_port` | 服务器端口 |

变量支持组合使用：`"$scheme://$host"` → `"http://example.com"`

---

## 404 处理

默认返回 `404 Not Found` 纯文本响应。可自定义：

```java
router.notFoundHandler(new HttpRequestHandler() {
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        response.status(404)
                .contentType("application/json;charset=utf-8")
                .write("{\"error\":\"Not Found\"}".getBytes());
    }
});
```

---

## 异常处理

通过 `HTTPServer` 设置全局异常处理器，捕获 Handler 抛出的异常：

```java
HTTPServer.of(8080)
    .requestHandler(router)
    .exceptionHandler(new HttpExceptionHandler() {
        public void handleException(HttpRequest request, HttpResponse response, Throwable throwable) {
            response.status(500)
                    .contentType("application/json;charset=utf-8")
                    .write(("{\"error\":\"" + throwable.getMessage() + "\"}").getBytes());
        }
    })
    .start();
```

未设置异常处理器时，框架默认返回 `500 Internal Server Error`。

---

## 完整示例

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.*;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

public class FullRouterServer {

  public static void main(String[] args) throws Exception {
    int port = 8080;
    String contextPath = "/";
    String docBase = "/path/to/static/files";

    HttpRouterHandler router = new HttpRouterHandler(contextPath);

    // 1. 精确匹配
    router.exactRoute("/health", new HttpRoute() {
      public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.status(200).write("OK".getBytes());
      }
    });

    // 2. 前缀匹配 — 业务处理
    router.route("/api/users", new HttpRoute() {
      public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.status(200)
                .contentType("application/json;charset=utf-8")
                .write("{\"users\":[]}".getBytes());
      }
    });

    // 3. 正则匹配
    router.route("^/v\\d+/resource$", new HttpRoute() {
      public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.status(200).write(("Matched: " + path).getBytes());
      }
    });

    // 4. 反向代理 — WebSocket + 路径重写 + 动态请求头
    HttpProxyConfig proxyConfig = HttpProxyConfig.target("http://localhost:8000")
            .upgrade(true)
            .replacePrefix("/ws", "")
            .addHeader("X-Real-IP", "$remote_addr")
            .addHeader("X-Forwarded-Proto", "$scheme")
            .removeHeader("X-Internal-Header")
            .connectionTimeout(3000)
            .readTimeout(10000);
    router.route("/ws", new HttpProxyRoute(proxyConfig));

    // 5. 反向代理 — 普通 API 代理
    HttpProxyConfig apiProxyConfig = HttpProxyConfig.target("http://backend:3000")
            .replacePrefix("/rest", "/api")
            .changeOrigin(true)
            .addHeader("X-Proxy-By", "wastnet");
    router.route("/rest", new HttpProxyRoute(apiProxyConfig));

    // 6. 静态资源
    router.resource(new HttpResourceRoute("/", docBase));

    // 7. 自定义 404
    router.notFoundHandler(new HttpRequestHandler() {
      public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        response.status(404)
                .contentType("application/json;charset=utf-8")
                .write("{\"error\":\"Not Found\"}".getBytes());
      }
    });

    // 启动服务
    HTTPServer server = HTTPServer.of(port)
            .requestHandler(router)
            .exceptionHandler(new HttpExceptionHandler() {
              public void handleException(HttpRequest request, HttpResponse response, Throwable throwable) {
                response.status(500)
                        .contentType("text/plain;charset=utf-8")
                        .write(("Error: " + throwable.getMessage()).getBytes());
              }
            })
            .start();

    System.out.println("Server started on http://localhost:" + port + contextPath);
  }
}
```

---

## API 速查

### HTTPServer

| 方法 | 说明 |
|------|------|
| `HTTPServer.of(int port)` | 创建 HTTP 服务器 |
| `requestHandler(HttpRequestHandler)` | 设置请求处理器 |
| `upgradeHandler(UpgradeHandler)` | 设置协议升级处理器 |
| `exceptionHandler(HttpExceptionHandler)` | 设置异常处理器 |
| `ssl(boolean)` | 启用 SSL |
| `sslContext(SSLContext)` | 设置 SSL 上下文 |
| `bufferSize(int)` | 设置缓冲区大小 |
| `workerNum(int)` | 设置 Worker 线程数 |
| `idleStateHandler(IdleStateHandler)` | 设置空闲连接处理器 |
| `start()` | 启动服务器 |

### HttpRouterHandler

| 方法 | 说明 |
|------|------|
| `new HttpRouterHandler()` | 默认 contextPath `/` |
| `new HttpRouterHandler(String)` | 指定 contextPath |
| `exactRoute(String, HttpRoute)` | 注册精确匹配路由 |
| `route(String, HttpRoute)` | 注册前缀/正则匹配路由 |
| `route(String, HttpRoute, HttpMethod...)` | 注册带方法过滤的前缀路由 |
| `get(String, HttpRoute)` | 注册 GET 方法精确匹配路由 |
| `post(String, HttpRoute)` | 注册 POST 方法精确匹配路由 |
| `put(String, HttpRoute)` | 注册 PUT 方法精确匹配路由 |
| `delete(String, HttpRoute)` | 注册 DELETE 方法精确匹配路由 |
| `patch(String, HttpRoute)` | 注册 PATCH 方法精确匹配路由 |
| `proxy(String, String)` | 注册反向代理路由（快速） |
| `proxy(String, HttpProxyConfig)` | 注册反向代理路由（完整配置） |
| `resource(HttpResourceRoute)` | 注册静态资源路由 |
| `healthRoute(String)` | 设置健康检查路径，null 禁用；默认 `/health` |
| `ws(String, WebSocketResource)` | 注册 WebSocket（自动追加 contextPath） |
| `h2c(String)` | 注册 h2c 升级（自动追加 contextPath） |
| `notFoundHandler(HttpRequestHandler)` | 自定义 404 处理 |
| `autoRedirect(boolean)` | 根路径 `/` 是否重定向到 contextPath（默认 true） |
| `clear()` | 清空所有路由 |

### HttpProxyConfig

#### 常用配置

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `target(String)` | 目标地址（静态工厂） | — |
| `changeOrigin(boolean)` | 修改 Host 头 | `true` |
| `readTimeout(int)` | 响应超时（ms） | `60000` |
| `connectionTimeout(int)` | 连接超时（ms） | `5000` |
| `upgrade(boolean)` | 允许协议升级（WebSocket / h2c） | `false` |
| `addHeader(String, String)` | 添加请求头（支持 `$` 变量） | — |
| `addHeader(String, HeaderValueResolver)` | 添加动态请求头 | — |
| `removeHeader(String...)` | 移除请求头（可变参数） | — |
| `replacePrefix(String, String)` | 前缀路径重写 | — |
| `replaceRegex(String, String)` | 正则路径重写 | — |
| `rewrite(RewriteFunction)` | 自定义路径重写函数 | — |
| `rewrite(boolean)` | 布尔重写开关（`true` 应用重写，`false` 透传原路径） | `false` |

#### 增强配置

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `loopDetection(boolean)` | 环路检测（代理转发时防止回环） | `false` |
| `http2(boolean)` | 目标后端支持 HTTP/2（H2H1 / H2H2） | `false` |
| `h2c(boolean)` | h2c prior knowledge：强制明文 H2，不降级 | `false` |
| `enabledProtocols(String...)` | 出站代理连接允许的 TLS 协议 | — |
| `trustManagers(TrustManager[])` | 自定义 TrustManager（出站 SSL 校验） | — |
| `verifyHostname(boolean)` | HTTPS 代理连接校验主机名 | `true` |
