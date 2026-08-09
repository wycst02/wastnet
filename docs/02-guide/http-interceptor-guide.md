# HTTP 服务器拦截器（HttpServerInterceptor）使用指南

`HttpServerInterceptor` 是 wastnet 提供的**主动请求拦截 SPI**（与被动观察者 observer 相对），在业务请求处理器**之前**切入，可用于鉴权、CORS 预检、限流、参数改写等需要**拦截或改写请求**的场景。框架本身**不内置任何实现**，由应用层提供。本文档是 README「请求拦截器」章节的深入补充。

适用场景：登录鉴权、CORS 预检、限流熔断、请求参数改写、灰度路由等。

---

## 1. 设计定位

- **SPI 而非内置组件**：`HttpServerInterceptor` 只是一个接口，框架只负责在正确时机调用它，不关心实现内容，也不依赖任何具体安全/限流库。
- **零侵入、默认不启用**：不设置拦截器即完全不触发任何回调，热路径（请求处理）上没有任何额外开销。
- **主动改写**：与只读的 observer 不同，拦截器**可以**修改请求 / 响应、**可以**直接写出响应并**中断**处理流程（short-circuit）。

> 与 observer 的分工：observer 负责"观察、采集、埋点"（只读、不中断，见 [http-observer-guide.md](http-observer-guide.md)）；拦截器负责"改写、鉴权、熔断"（可写、可中断）。需要两者配合时，拦截器在 observer 之后、业务处理器之前执行。

---

## 2. 注册方式

```java
// HTTPServer 链式配置（推荐）
HTTPServer.of(8080)
        .interceptor(myInterceptor)
        .requestHandler(router)
        .start();
```

> 默认不设置拦截器即完全不触发任何回调；若之前已设置，可传入 `null` 停用。

---

## 3. 拦截方法

| 方法 | 触发时机 | 返回值语义 |
|:-----|:---------|:-----------|
| `beforeHandle(HttpRequest, HttpResponse, ChannelContext)` | 请求解析完成、**进入业务处理器之前** | 返回 `true` 继续进业务；返回 `false` 表示响应已写出，**跳过**业务处理器 |

### 入参可用性

- **`HttpRequest`**：已解析完成，可读取 URI、Header、Method、Query 等；可调用 `request.getHeader("...")`、`request.getRequestUri()`。Body 视请求类型而定：小 body 可 `getBodyData()` 直接取 `byte[]`，chunked / 超大 body 需用 `bodyStream()` 流式读取（直接 `getBodyData()` 会抛 `IllegalStateException`）。
- **`HttpResponse`**：可直接写入状态码 / 头 / 响应体（如 `response.status(401).body("...")`），写入即表示拦截。
- **`ChannelContext`**：提供连接级信息，如 `ctx.getRemoteAddress()`（可用于按客户端 IP 限流）、`ctx.isSSL()` 等。

---

## 4. 一个最小实现（登录鉴权）

```java
public class AuthInterceptor implements HttpServerInterceptor {

    @Override
    public boolean beforeHandle(HttpRequest request, HttpResponse response, ChannelContext ctx)
            throws Exception {
        // 校验 Authorization 头
        String auth = request.getHeader("Authorization");
        if (auth == null || !checkToken(auth)) {
            response.status(401).body("Unauthorized");
            return false;   // 响应已写出，跳过业务处理器
        }
        return true;        // 继续进入业务处理器
    }

    private boolean checkToken(String auth) {
        // 实际项目中在此校验 token / session / JWT
        return auth.startsWith("Bearer ");
    }
}
```

> **务必保持廉价**：`beforeHandle` 在 I/O Worker 线程上执行，可能被多个线程并发调用。若鉴权涉及同步 DB / RPC 等阻塞操作，应**派发到业务线程池**执行（如 `ctx.runAsync(...)`），不要在拦截器内直接阻塞。

---

## 5. 执行时机与短路语义

拦截器在 `HttpServerChannelHandler` 层统一执行，**先于**所有路由分发（`HttpRouterHandler` / `AnnotationRouterHandler` / 静态资源 `HttpResourceRoute` 等），因此对**全部请求**天然生效：

```
HttpServerChannelHandler.executeHandle()
  ├─ delegate.onRequestStart()  → observer.onRequestStart()         （只读观察）
  │                             → interceptor.beforeHandle()        （主动拦截）
  │                                  ├─ true  → 继续
  │                                  └─ false → 直接 complete()，跳过业务处理器
  └─ requestHandler.handle()  （路由分发，进入 controller / 静态资源等）
```

**短路语义**：`beforeHandle` 返回 `false` 时，框架直接提交响应并返回，**不会进入任何业务 handler**。因此鉴权拒绝、CORS 预检（`OPTIONS`）、限流放行等都可以在拦截器内完成，业务代码无需感知。

### 粒度说明

拦截器是**全局（服务器级）**的，一次注册对全部路由生效。它无法天然区分"哪些接口要鉴权、哪些公开"——公开接口需要在拦截器内按路径 / 方法判断：

```java
// 公开路径白名单：在拦截器内显式放行
String path = request.getRequestUri();
if (path.equals("/login") || path.equals("/register") || path.startsWith("/static")) {
    return true;
}
```

若需要**按接口 / 注解**的细粒度鉴权，请使用路由级拦截器 `RouterInterceptor`（见第 12 节），它支持通过 `@WithInterceptor` 精确绑定到具体的控制器类或端点方法。

---

## 6. 并发安全

与 observer 相同，`HttpServerChannelHandler` 在 `start()` 时通过 `prepare()` 将拦截器（通过 `setInterceptor` 设置，若已设置）一次性组装进一个不可变的 `HttpRequestLifecycleDelegate`；请求路径只读取 `delegate` 引用（先捕获到**局部变量**）再使用，因此：

- **不会出现「判空通过、使用时变 null」的 NPE**：`delegate` 引用在请求内被捕获为局部变量，运行时即使替换 SPI 也不会让正在进行的请求崩溃。
- **热路径零额外开销**：未注册拦截器时 `delegate` 为 `null`，请求路径仅一次 `delegate != null` 判断。
- **拦截器异常兜底**：`beforeHandle` 抛出异常会被框架捕获、记录（`log.debug`），并视为 `false` 短路处理（跳过业务处理器），不会让请求崩溃。

> 配置须在 `start()` **之前**完成；`start()` 之后再调用 `setInterceptor` **不会生效**（delegate 只在 `prepare()` 组装一次，框架不提供运行期热更新能力）。如需更换拦截器，须重启服务。

---

## 7. 停机清理（ClearableHandler）

若拦截器实现了 `io.github.wycst.wastnet.socket.handler.ClearableHandler`，框架在 server `stop()` 时会调用其 `clear()`：

```java
public class RateLimitInterceptor implements HttpServerInterceptor, ClearableHandler {
    // ... 限流计数器 / 令牌桶 ...
    @Override
    public void clear() {
        // 清理内部状态，如计数器归零、关闭连接池等
    }
}
```

---

## 8. 完整可运行示例（鉴权 + CORS + 限流）

```java
public class CombinedInterceptor implements HttpServerInterceptor {

    @Override
    public boolean beforeHandle(HttpRequest request, HttpResponse response, ChannelContext ctx)
            throws Exception {
        HttpMethod method = request.getMethod();

        // 1. CORS：预检请求直接放行（不进入业务处理器）
        if (method == HttpMethod.OPTIONS) {
            response.header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                    .status(200);
            return false;   // 预检请求不需要走业务逻辑
        }

        // 2. 公开路径放行
        String path = request.getRequestUri();
        if (path.equals("/login") || path.startsWith("/static")) {
            return true;
        }

        // 3. 鉴权：未带 token 返回 401
        if (request.getHeader("Authorization") == null) {
            response.status(401).body("Unauthorized");
            return false;
        }

        // 4. 限流：按客户端 IP 简单计数（非阻塞实现）
        String ip = String.valueOf(ctx.getRemoteAddress());
        if (!allow(ip)) {
            response.status(429).body("Too Many Requests");
            return false;
        }

        return true;
    }

    private boolean allow(String ip) {
        // 实际项目中用原子计数器 / 令牌桶实现
        return true;
    }
}
```

```java
// 注册
HTTPServer.of(8080)
        .interceptor(new CombinedInterceptor())
        .requestHandler(router)
        .start();
```

---

## 9. 常见误区

### 拦截器能拿到注解元数据吗？

**不能。** `HttpServerInterceptor` 在路由匹配（进入 `AnnotationRouterHandler` / controller）**之前**执行，此时**拿不到** `@Endpoint` / `@Controller` 上的注解，只能靠路径 / 方法判断。需要基于注解的细粒度授权时，请改用路由级的 `RouterInterceptor` + `@WithInterceptor`（见第 12 节）。

### 返回 `false` 后响应一定会写出吗？

框架只保证**跳过业务处理器**；响应内容需要拦截器自己写入。若 `beforeHandle` 返回 `false` 但**什么都没写**，客户端会收到空响应。因此短路时**务必**在拦截器内写出状态码 / 响应体。

### 可以在这个拦截器里做慢请求采样吗？

不建议。耗时统计、采样属于**只读观察**职责，应使用 `HttpServerObserver.onRequestComplete(...)`。拦截器专注于"改写 / 拦截"。

---

## 10. 与观察者（HttpServerObserver）的区别

| 维度 | HttpServerObserver | HttpServerInterceptor |
|:-----|:-------------------|:----------------------|
| 目的 | 观察、采集、埋点 | 改写请求 / 响应、鉴权、熔断 |
| 能否修改数据 | 否 | 是 |
| 能否中断流程 | 否 | 是（返回 `false` 短路） |
| 执行线程 | I/O Worker | I/O Worker |
| 典型用途 | 指标、追踪、审计 | 认证、限流、CORS、参数改写 |
| 执行顺序 | 先（只读观察） | 后（主动拦截） |

---

## 11. 扩展建议

- **登录态注入**：鉴权通过后，可将用户信息写入 `HttpRequest.setAttribute(key, value)` 或 `ChannelContext` 的 attachment，供后续业务处理器读取。

---

## 12. 路由级拦截器（RouterInterceptor）

`RouterInterceptor` 是与 `HttpServerInterceptor` **相互独立**的另一套拦截机制，注册在 `HttpRouterHandler` 上而非服务器上，因此能拿到**已剥离 contextPath 的路由路径**，并支持按注解绑定到具体端点。

```java
public interface RouterInterceptor {
    boolean beforeHandle(String path, HttpRequest request, HttpResponse response) throws Throwable;
}
```

其中 `path` 是路由匹配所使用的 `subPath`（已解码、已去除查询串、已剥离 contextPath），与 `HttpRoute.handle` 收到的路径一致。返回 `false` 即短路。

### 12.1 两种类型

由 `@Interceptor(type = ...)` 指定：

| 类型 | 执行时机 | 生效范围 | 典型用途 |
|:-----|:---------|:---------|:---------|
| `InterceptorType.PRE_ROUTE`（默认） | 路由分发**之前** | **全局无条件**，对该 router 下所有请求生效 | 日志、限流、全局鉴权 |
| `InterceptorType.ENDPOINT` | 端点匹配成功后、调用 controller 方法**之前** | **仅**被 `@WithInterceptor` 引用的类 / 方法 | 细粒度权限（如 admin 角色校验） |

> `ENDPOINT` 类型**不会**进入全局链；未被任何 `@WithInterceptor` 引用时它就不会执行。若 `@WithInterceptor` 引用的是 `PRE_ROUTE` 拦截器，则会**跳过**该引用（它已由全局前置链执行），不会重复执行。

### 12.2 前置路由拦截器（PRE_ROUTE）

两种注册方式，效果等价：

```java
// 方式一：手动注册（普通 HttpRouterHandler 也可用）
router.interceptor(new RouterInterceptor() {
    @Override
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
        if ("/login".equals(path)) return true;
        if (request.getHeader("Authorization") == null) {
            response.status(401).body("Unauthorized");
            return false;
        }
        return true;
    }
});
```

```java
// 方式二：注解注册（scanPackages 后自动加入链，按 order 升序）
@Interceptor(order = 1)
public class AuthLogInterceptor implements RouterInterceptor {
    @Override
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
        System.out.println("[" + request.getMethod() + "] " + path);
        return true;
    }
}
```

### 12.3 端点拦截器（ENDPOINT）

先定义拦截器，`value` 即引用名：

```java
@Interceptor(value = "admin", order = 1, type = InterceptorType.ENDPOINT)
public class AdminAuthInterceptor implements RouterInterceptor {
    @Override
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
        if (!"admin".equals(request.getHeader("X-Role"))) {
            response.status(403).body("Forbidden: admin role required");
            return false;
        }
        return true;
    }
}
```

再用 `@WithInterceptor` 绑定，支持**类级**（该类下所有端点）与**方法级**（追加到类级之后）：

```java
@RestController("/admin")
@WithInterceptor("admin")          // 类级：/admin 下所有端点都要校验
public class AdminController {

    @Endpoint("/users")
    public String users() { return "admin user list"; }

    @Endpoint("/audit")
    @WithInterceptor("audit")      // 方法级：在 admin 之后再追加 audit
    public String audit() { return "audit log"; }
}
```

绑定关系在**扫描期**完成解析，运行时只是遍历一个预先排好序的数组，因此**没有路径匹配开销**；未绑定拦截器的端点返回空列表，仅多一次 `isEmpty()` 判断。SSE 端点（`@Sse`）同样支持，拦截器在 emitter 发送响应头之前执行。

### 12.4 启动期校验（fail-fast）

`@WithInterceptor` 引用的名字在注册控制器时立即解析，以下情况**直接抛异常**，避免拦截器静默失效：

- 引用了不存在的 bean 名字（拼写错误）；
- 引用的 bean 未实现 `RouterInterceptor`。

> 若引用的 bean 是 `type = InterceptorType.PRE_ROUTE`，**不会报错**，而是直接跳过——因为它已经注册进全局前置链、对所有请求无条件执行，再绑到端点级只会重复执行一次。同理，类级与方法级 `@WithInterceptor` 若引用了同一个 `ENDPOINT` 拦截器，会被**自动去重**，只执行一次。

此外，标注了 `@Interceptor` 但未实现 `RouterInterceptor` 的类**不会**被识别为组件。

### 12.5 三种拦截机制对比

| 维度 | HttpServerInterceptor | RouterInterceptor (PRE_ROUTE) | RouterInterceptor (ENDPOINT) |
|:-----|:----------------------|:------------------------------|:-----------------------------|
| 注册位置 | `HTTPServer.interceptor(...)` | `router.interceptor(...)` 或 `@Interceptor` | `@Interceptor(type=ENDPOINT)` + `@WithInterceptor` |
| 生效范围 | 全服务器（含静态资源、代理） | 该 router 全部请求 | 显式绑定的类 / 方法 |
| 路径参数 | 无（需自取 `getRequestUri()`） | `subPath`（已剥离 contextPath） | 同左 |
| 能否感知端点 | 否 | 否 | 是（注解绑定） |
| 可拿 `ChannelContext` | 是 | 否 | 否 |

> 三者可叠加使用，执行顺序为：`HttpServerInterceptor` → `RouterInterceptor(PRE_ROUTE)` → 路由匹配 → `RouterInterceptor(ENDPOINT)` → controller 方法。

### 12.6 拦截器异常 vs 短路

`beforeHandle` 有两种「中止请求」的方式，语义完全不同：

| 方式 | 写法 | 框架行为 |
|:-----|:-----|:---------|
| **正常短路** | `return false` | 跳过后续拦截器 / controller；**框架不报错**，但响应需拦截器自己写出（否则客户端收到空响应） |
| **抛异常** | `throw ...` | 异常穿透到 `HttpServerChannelHandler`，与 controller 业务异常走同一条 `handleApplicationException` 链路 |

异常链路（`HttpServerInterceptor` / `PRE_ROUTE` / `ENDPOINT` 三种拦截器均适用）：

- 未配置 `HttpExceptionHandler` → 返回 **500 Internal Server Error**（`text/plain`）。
- 配置了 `HttpExceptionHandler` → 由自定义 handler 决定响应内容与状态码。
- 若自定义 handler 自身又抛异常 → 兜底：响应尚未被修改则回 500，已写入部分内容则把半成品响应发出去。
- `printStackTraceError = true` 时异常会打印到日志。

```java
@Override
public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
    if (!"admin".equals(request.getHeader("X-Role"))) {
        // 正确：鉴权失败用短路，自己写出响应
        response.status(403).body("Forbidden: admin role required");
        return false;
    }
    // 错误：不要用抛异常表达业务拒绝，否则客户端只会收到 500，且可能泄露堆栈
    // throw new RuntimeException("no permission");
    return true;
}
```

> **经验法则**：业务层面的「拒绝访问」一律用 `return false` + 自己写响应；只有真正意外的基础设施错误（如依赖不可用时）才让它抛异常，交给 500 / `HttpExceptionHandler` 兜底。

### 12.7 临时停用拦截器

提供两层 `disabled` 开关，便于调试时临时停用，二者为 **AND** 关系（总开关开 **且** 单个未禁用 → 才执行）：

**① 全局总开关（`HttpRouterHandler` 级）**

在创建 `AnnotationRouterHandler` 时调用，一键停用所有路由级拦截器（`PRE_ROUTE` + `ENDPOINT`）：

```java
AnnotationRouterHandler router = new AnnotationRouterHandler(beanContainer);
router.interceptorsDisabled(true); // 停用全部路由级拦截器（默认 false = 启用）
```

启用后，`PRE_ROUTE` 不再注册进全局链、`ENDPOINT` 不再绑定到端点，效果等同「整站跳过鉴权」。

**② 单个拦截器开关（`@Interceptor` 级）**

在拦截器定义处声明 `disabled = true`，仅停用该拦截器（无论被全局引用还是被 `@WithInterceptor` 引用都不生效）：

```java
@Interceptor(value = "admin", type = InterceptorType.ENDPOINT, disabled = true)
public class AdminAuthInterceptor implements RouterInterceptor {
    // ...
}
```

默认 `disabled = false`（启用），不加该属性时行为完全不变。

> 两种方式可叠加使用：例如全局总开关开启，但某个拦截器单独 `disabled = true`，则只有该拦截器停用，其余照常执行。

完整示例见 `wastnet-test` 模块的 `InterceptorDemo.java`、`AdminAuthInterceptor.java`、`AdminController.java`。
