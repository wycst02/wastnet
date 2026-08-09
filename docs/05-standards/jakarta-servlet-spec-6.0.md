# Jakarta Servlet 规范 6.0 详解

> 本规范文档依据 Jakarta Servlet Specification 6.0（Jakarta EE 10）官方规范整理，用于指导 `wastnet-servlet` 桥接模块的实现。
> 官方地址：https://jakarta.ee/specifications/servlet/6.0/
> 主要包：`jakarta.servlet`、`jakarta.servlet.http`、`jakarta.servlet.annotation`、`jakarta.servlet.descriptor`。

---

## 1. 概述（Introduction）

Servlet 是基于 Web 的 Java 组件，由容器托管，用于生成动态内容。规范定义了一套请求/响应模型、生命周期、过滤器、会话、异步处理等标准 API。

约定：
- **SRV.x.y** 为规范条款编号（Section Reference）。
- 容器（servlet container）即 servlet 引擎，如 Tomcat、Jetty、Undertow。
- 本桥接模块将 wastnet-core 的 `HttpRequest`/`HttpResponse` 适配为 `jakarta.servlet` API，使 wastnet 可作为嵌入式 Web 服务器托管 Spring Boot 3 等标准 servlet 应用。

---

## 2. The Servlet Interface（Servlet 接口）

### 2.1 Servlet 接口方法
- `init(ServletConfig)`：容器加载 servlet 后调用一次。
- `service(ServletRequest, ServletResponse)`：每次请求调用。
- `destroy()`：容器卸载前调用一次。
- `getServletConfig()` / `getServletInfo()`。

### 2.2 Servlet 生命周期
- **加载与实例化**：容器在启动时或首次请求时加载。
- **初始化（SRV.2.2）**：`init` 在 `service` 前调用，失败抛 `ServletException` 导致不可用。
- **请求处理（SRV.2.3）**：并发请求时容器可能同时调用多个 `service`（线程不安全，需自行同步）。
- **终止（SRV.2.4）**：`destroy` 后容器不再派发请求。

### 2.3 Request Handling Methods（请求处理方法）
- 2.3.1 基于 HTTP 定义 `HttpServlet`，提供 `doGet/doPost/doPut/doDelete/doHead/doOptions/doTrace`。
- 2.3.2 条件 GET 支持：`getLastModified()`。
- 2.3.3 异步处理（见 §2.3.3 系列）、2.3.4 远程方法、2.3.5 售后（post-service）处理。

### 2.4 Number of Instances（实例数量）
容器通常为每个 servlet 声明维护一个实例；`SingleThreadModel` 已在 6.0 中废弃/移除。

### 2.5 Servlet 与 Filter 的特例
过滤器链在 `service` 之前/之后执行。

---

## 3. The Request（请求）

### 3.1 HTTP 协议参数（HTTP Protocol Parameters）
- 参数来源：URI 查询串、POST 表单（application/x-www-form-urlencoded）。
- `getParameter` / `getParameterValues` / `getParameterNames` / `getParameterMap`。
- 参数仅在首次访问时被解析并缓存。

### 3.2 文件上传（File upload）
- 当 Content-Type 为 `multipart/form-data` 时，参数不再通过 `getParameter` 暴露，需通过 `getParts()` / `getPart(name)` 访问。
- 相关：`Part` 接口、`@MultipartConfig`。

### 3.3 属性（Attributes）
- `setAttribute` / `getAttribute` / `removeAttribute` / `getAttributeNames`。
- 容器保留属性名前缀 `java.*`、`jakarta.*`。

### 3.4 请求头（Request Headers）
- `getHeader` / `getHeaders` / `getHeaderNames` / `getIntHeader` / `getDateHeader`。

### 3.5 请求路径（Request Path Elements）
- `getContextPath()`：web 应用上下文路径。
- `getServletPath()`：映射的 servlet 路径。
- `getPathInfo()`：额外路径信息。
- `getRequestURI()` / `getRequestURL()` / `getPathTranslated()`。
- 路径元素映射规则（SRV.3.5）。

### 3.6 路径转译方法（Path Translation Methods）
- `getRealPath(String path)`：映射到文件系统（可选）。

### 3.7 非阻塞 IO（Non Blocking IO）
- `ServletInputStream.setReadListener(ReadListener)`、`ServletOutputStream.setWriteListener(WriteListener)`。
- 配合 `isReady()` 实现异步非阻塞读写。

### 3.8 安全性（Security）
- `getRemoteUser` / `isUserInRole` / `getUserPrincipal`。
- `authenticate` / `login` / `logout` / `getAuthType`。
- `getScheme` / `isSecure` / `getSSLAttribute`（`getServletConnection().getSSLSupportedProtocols()` 等）。

### 3.9 异步处理（Asynchronous Processing）
- 3.9.1 概述：通过 `startAsync()` 将请求移交其他线程处理。
- 3.9.2 处理线程：原始容器线程立即返回，业务线程稍后 `complete()`。
- 3.9.3 异步上下文：见 §2.3.3。
- 3.9.4 异步监听器：`AsyncListener` 的 `onComplete`/`onTimeout`/`onError`/`onStartAsync`。
- 3.9.5 超时：`AsyncContext.setTimeout(long)`，`getTimeout()`。
- 3.9.6 立即分派（dispatch immediately）。
- 3.9.7 线程安全：请求/响应对象在 async 期间需保持引用有效。

### 3.10 分块传输（Chunked Transfer）
- `Transfer-Encoding: chunked`。

### 3.11 尾部头（Trailer Headers）
- 3.11.1 请求尾部：`getTrailerFields()`（须 `isTrailerFieldsReady()` 为 true）。
- 3.11.2 响应尾部：`setTrailerFields(Supplier<Map<String,String>>)`、`getTrailerFields()`。

### 3.12 请求分派器（Request Dispatcher）
- `RequestDispatcher.forward()`：控制权转移，响应缓冲重置（除已提交部分）。
- `RequestDispatcher.include()`：包含另一资源输出。
- `getNamedDispatcher` / `getRequestDispatcher`。

### 3.13 请求对象的生命周期（Lifetime of the Request Object）
- 请求对象在 `service` 期间有效；异步时延续至 `complete` 之前。

### 3.14 请求监听器（Request Listener）
- `ServletRequestListener`（`requestInitialized`/`requestDestroyed`）。
- `ServletRequestAttributeListener`。

### 3.15 升级处理（Upgrade Processing）
- `HttpServletRequest.upgrade(Class<T>)`：将连接交由应用协议处理器（如 WebSocket）接管。
- `HttpUpgradeHandler` 生命周期：`init`/`destroy`。

### 3.16 连接信息（Connection Information）
- `getServletConnection()` 返回 `ServletConnection`，提供 `getConnectionId()`、`getProtocol()`、`getSSLSessionId()` 等。

---

## 4. The Response（响应）

### 4.1 缓冲（Buffering）
- 写入先进入缓冲，`setBufferSize`、`getBufferSize`、`flushBuffer`、`reset`、`resetBuffer`、`isCommitted`。
- 一旦 `flushBuffer`/输出超出缓冲即 commit，之后不能再设置状态码/头。

### 4.2 头（Headers）
- `setHeader`/`addHeader`/`setIntHeader`/`setDateHeader`/`containsHeader`/`getHeader`/`getHeaders`/`getHeaderNames`。

### 4.3 状态代码（Status Codes）
- `setStatus(int)`、`getStatus()`。
- 标准码：200/201/204/301/302/304/400/401/403/404/405/408/409/410/411/413/414/415/416/500/501/503。
- `sendError(int, String)` 触发错误页机制。

### 4.4 特殊意义的状态码（Status Code Specific Semantics）
- 3xx 重定向语义、4xx/5xx 错误语义。

### 4.5 设置内容（Setting the Content）
- `setContentType`、`setContentLength`、`setContentLengthLong`、`setCharacterEncoding`、`setLocale`。
- 内容类型契约：含 charset 时响应按该编码。

### 4.6 国际化（Internationalization）
- `getLocale`/`getLocales`、`setLocale`。

### 4.7 关闭响应（Closing the Response）
- 容器在 `service` 返回后关闭，刷新并提交缓冲。

### 4.8 响应对象的生命周期（Lifetime of the Response Object）
- 与请求同生命周期；异步时延续至 complete。

### 4.9 非阻塞 IO（见 §3.7）
- `ServletOutputStream.setWriteListener`。

### 4.10 尾部头（Trailer Headers，见 §3.11.2）

---

## 5. 过滤器（Filters）

### 5.1 什么是过滤器
- 拦截请求/响应以执行日志、鉴权、压缩等横切逻辑。

### 5.2 过滤器模型（Filter Model）
- `Filter` 接口：`init(FilterConfig)`、`doFilter(ServletRequest, ServletResponse, FilterChain)`、`destroy()`。
- `FilterChain.doFilter` 推进链。

### 5.3 过滤环境（Filter Environment）
- `FilterConfig` 提供 `getInitParameter`/`getServletContext`。

### 5.4 在 Web 应用中配置过滤器
- `web.xml` 的 `<filter>`/`<filter-mapping>`，或 `@WebFilter`。
- `dispatcherTypes`：REQUEST/ FORWARD/ INCLUDE/ ERROR/ ASYNC。

### 5.5 过滤器与请求分派器（Filters and Request Dispatcher）
- 通过 `dispatcher` 限定在 forward/include 时是否触发。

---

## 6. 会话（Sessions）

### 6.1 会话（Session）
- 6.1.1 会话创建：`HttpServletRequest.getSession()` / `getSession(boolean)`。
- 6.1.2 会话识别：通过 `JSESSIONID` cookie 或 URL 重写（`encodeURL`/`encodeRedirectURL`）。
- 6.1.3 会话生命周期：`getCreationTime`/`getLastAccessedTime`/`getMaxInactiveInterval`/`invalidate`。
- 6.1.4 会话属性：`getAttribute`/`setAttribute`/`removeAttribute`/`getAttributeNames`。
- 6.1.5 会话过期：`setMaxInactiveInterval(秒)`；容器后台回收过期会话。
- 6.1.6 分派器会话：`getSession` 在 include/forward 中行为。
- 6.1.7 会话并发：多请求共享同一会话时的线程安全由容器保证。
- 6.1.8 分布式会话：跨 JVM 复制（本桥接为单实例，暂不支持）。

### 6.2 会话跟踪机制（Session Tracking）
- Cookie、SSL、URL 重写三种机制，按优先级 Cookie 优先。

### 6.3 Cookie 接口
- `Cookie` 类：name/value 及 `setPath`/`setDomain`/`setMaxAge`/`setSecure`/`setHttpOnly`/`setComment`/`setVersion` 等。
- `addCookie`/`getCookies`。

### 6.4 Session 监听器（Session Listeners）
- `HttpSessionListener`/`HttpSessionAttributeListener`/`HttpSessionActivationListener`/`HttpSessionBindingListener`/`HttpSessionIdListener`。

---

## 7. 应用生命周期事件（Application Lifecycle Events）

### 7.1 事件监听器（Event Listeners）
- `ServletContextListener`/`ServletContextAttributeListener`/`ServletRequestListener`/`ServletRequestAttributeListener`/`HttpSessionListener` 等。

### 7.2 监听器类（Listener Classes）
- 通过 `@WebListener` 或 `web.xml` 注册。

### 7.3 监听器部署声明（Declaration）
- 部署描述符中声明。

### 7.4 监听器实例与线程安全
- 监听器实例为单例，注意并发。

### 7.5 监听器传播顺序（Ordering）
- 注解声明的顺序未定义，web.xml 中 `<absolute-ordering>` 控制。

### 7.6 应用配置监听器（Application Configuration Listeners）
- `ServletContextListener.contextInitialized` 接收 `ServletContextEvent`。

---

## 8. 部署描述符（Deployment Descriptor）

### 8.1 部署描述符元素（Deployment Descriptor Elements）
- `web.xml` 的 DTD/XML Schema 定义。

### 8.2 部署描述符处理规则（Processing Rules）
- 注解与描述符合并规则、覆盖优先级。

### 8.3 部署描述符示例（Example）
- 标准 `web.xml` 示例。

### 8.4 语法（Syntax）
- XSD 语法。

### 8.5 部署描述符示例（Deployment Descriptor Examples）
- 安全约束、过滤器、servlet 映射示例。

---

## 9. 注解与可插拔性（Annotations and Pluggability）

### 9.1 注解（Annotations）
- `@WebServlet`、`@WebFilter`、`@WebListener`、`@WebInitParam`、`@MultipartConfig`、`@ServletSecurity`、`@HttpConstraint`、`@HttpMethodConstraint`。

### 9.2 可插拔性（Pluggability）
- `web-fragment.xml` 与 `@HandlesTypes`、`ServletContainerInitializer`。

### 9.3 模块化 Web 应用（Modularized Web Applications）
- 资源打包与合并。

### 9.4 处理规则（Processing Rules）
- 合并与排序规则。

---

## 10. 安全性（Security）

### 10.1 容器认证机制（Container Authentication Mechanisms）
- BASIC、FORM、DIGEST、CLIENT-CERT、自定义。

### 10.2 编程式安全（Programmatic Security）
- `login`/`logout`/`authenticate`/`isUserInRole`/`getUserPrincipal`。

### 10.3 注解安全（Annotation Security）
- `@ServletSecurity`、`@HttpConstraint`。

### 10.4 单点登录（Single Sign On）

### 10.5 登出处理（Logout Handling）

### 10.6 安全相关方法（Security Related Methods）
- `getAuthType`/`getRemoteUser`/`getUserPrincipal`。

### 10.7 部署描述符安全（Security in Deployment Descriptor）
- `<security-constraint>`/`<login-config>`/`<security-role>`。

---

## 11. 国际化问题（Internationalization Issues）

### 11.1 语言环境（Locale）
- `ServletRequest.getLocale`/`getLocales`，`ServletResponse.setLocale`。

### 11.2 字符编码（Character Sets）
- 请求/响应的字符编码处理，默认 ISO-8859-1（历史），建议显式 UTF-8。

---

## 12. 分发器类型（Dispatcher Types）

- `DispatcherType` 枚举：FORWARD、INCLUDE、REQUEST、ASYNC、ERROR。
- 用于过滤器按分派类型选择性触发。

---

## 附录 A. 标准映射（Standard Mappings）
- `/` 默认 servlet、`*.jsp` 扩展名映射等。

## 附录 B. 部署描述符定义（Deployment Descriptor Definition）
- 完整的 XSD 片段。

## 附录 C. 规范版本变更（6.0 vs 5.0）
- 移除 `SingleThreadModel`、`HttpUtils`、`PageContext` 等废弃类型。
- 升级至 Jakarta EE 10 命名空间 `jakarta.servlet.*`。
- 新增 `ServletConnection`、`getTrailerFields`/`setTrailerFields`、部分非阻塞增强。

---

## 本模块实现对照（Implementation Mapping）

| Servlet 规范要点 | wastnet-servlet 实现位置 |
| --- | --- |
| §2 Servlet 接口 / §3.13 请求生命周期 | `ServletDispatchHandler` 包装 wastnet-core，调用 `jakarta.servlet.Servlet.service` |
| §3.1 协议参数 | `ServletRequest.getParameterXXX`（桥接 core `getParameterValues`） |
| §3.4 请求头 / §3.5 路径 | `ServletRequest.getHeader*` / `getRequestURI` / `getContextPath` |
| §3.7 非阻塞 IO | `ServletInputStreamImpl` / `ServletOutputStreamImpl` |
| §3.9 异步处理 | `AsyncContextImpl` + `AsyncListener` |
| §3.11 尾部头 / §3.16 连接 | `HttpServletRequestImpl.getTrailerFields` / `getServletConnection` |
| §3.15 升级处理 | `HttpServletRequestImpl.upgrade` |
| §4 响应缓冲 / 头 / 状态码 | `ServletResponse` 缓冲写 + commit |
| §5 过滤器链 | `FilterChainImpl` |
| §6 会话 | `HttpSessionImpl` + `SessionRegistry` + `SessionData` |
| §6.3 Cookie | `CookieHeaderUtils` 解析/生成 Set-Cookie |
| §9 注解 | 由上层框架（如 Spring Boot）提供，本桥接不解析注解 |
