# 变更日志

所有显著变更均记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

---

## [Unreleased]

---

## [1.0.2] - 2026-09-29

### Added

- 强类型 Option 配置机制（`HttpOptions` / `SocketOptions` / `Option`），配置按服务实例隔离，支持多服务间隔离。
- 独立 `wastnet-mvc` 模块，注解驱动的 MVC / IoC 从核心拆分独立。
- 健康检查响应内容类型与响应体的全局配置。
- SSE `SseEmitter` 新增 `request()` 访问器。

### Changed

- HTTP 请求体解码重构（`HttpBodyDecoder` 系列、`HttpDecodedRequest` / `HttpDecodedResponse` / `HttpDefaultResponse` 等）。
- HTTP 头部与消息处理重构（`HttpHeaderUtils`、`HttpMessage`、`HttpRequestDecoder` 等）。
- 路由配置优化（`HttpRouterHandler`、`HttpServerChannelHandler`）。
- 注解 MVC 结果处理增强：按 ContentType 注册转换器、内置 TEXT 转换器、视图解析器与默认结果处理。
- HTTP/2 流控、超时与 Huffman 解码重构；H2 消息读取与帧处理重构。
- TCP 层重构（`ChannelContext`、`ChannelRunner`、`NioConfig` / `NioEngine`、`SocketConf` / `SocketOptions` 等）。
- JDK8 兼容简化：移除 JDK9 运行时分支（`RuntimeEnvJDK9Plus` 删除）。
- 反向代理层适配调整（`HttpProxyConfig` / `HttpProxyVariables` / 各 Adapter）。
- 文件上传 Multipart 处理调整。

### Fixed

- 修复 URI 解码时值中包含等号被截断的问题。
- 增强静态资源路由符号链接防护。
- 完善 HTTP/2 流清理与连接关闭处理。
- 修正 HTTP/2 `PING_ACK` 标志位值以符合 RFC 7540。

---

## [1.0.1] - 2026-08-09

首个正式版本。

### Added

- **Reactor 多线程网络框架** — 单 Acceptor 线程 + 多 Worker 线程（独立 Selector），无锁设计。
- **零第三方依赖** — 仅依赖 JDK，无需任何外部库（JDK 8 编译目标）。
- **TCP 服务器 / 客户端** — NIO 实现，支持自动重连、自定义编解码、SSL/TLS。
- **SSL/TLS** — 支持 PEM / JKS 证书加载、首字节嗅探自动识别明文 / 加密连接。
- **HTTP/1.1** — 完整 HTTP 方法支持（标准方法及 WebDAV / 扩展方法）、Pipeline、Keep-Alive、SSE。
- **HTTP/2 (h2 / h2c)** — HPACK 头部压缩、Huffman 编码、流控、多路复用、ALPN 协商、H2C 明文升级、H2→H1 代理转换。
- **103 Early Hints** — 静态资源预加载提示（RFC 8297），H1/H2 双协议支持。
- **路由分发** — 精确匹配、前缀匹配、正则匹配、HTTP 方法过滤。
- **注解路由 (MVC)** — `@Controller` / `@Endpoint` 注解路由 + 轻量依赖注入 + 消息转换 SPI。
- **反向代理** — URL 重写、Header 变量（`$remote_addr` 等）、h1/h2 与明文/HTTPS 前后端协议转换。
- **WebSocket** — 帧编解码、文本 / 二进制 / Ping-Pong / 分片帧。
- **静态资源** — 零拷贝发送、协商缓存、GZIP 流式压缩。
- **文件上传** — Multipart/Form-Data 解析、流式 body 读取、大文件自动落盘。
- **Chunked 编码** — 请求 / 响应双向 Chunked Transfer Encoding。
- **GZIP 压缩** — 自动压缩（可配置阈值、MIME 类型过滤）。
- **Server-Sent Events (SSE)** — 基于 `SseEmitter` 的事件推送，线程安全，超时自动关闭。
- **协议编解码** — `LengthFrameCodec`（长度前缀帧）、`ObjectCodec`（对象消息协议）及编解码扩展抽象。
- **请求拦截器 (HttpServerInterceptor)** — 在业务处理器前切入，支持鉴权、CORS 预检、限流。
- **路由级拦截器** — 通过 `@WithInterceptor` 为指定控制器 / 端点绑定 `PRE_ROUTE` / `ENDPOINT` 拦截器。
- **健康检查端点** — `router.healthRoute(path)` 内置端点，返回运行时长、路由统计等运行时信息。
- **服务器观察者 (HttpServerObserver)** — 请求 / 连接生命周期监控 SPI，零开销（未注册时无额外开销）。
- **连接过滤器 (ConnectionFilter)** — TCP accept 级 IP 黑 / 白名单与连接数限流。
- **空闲连接检测 (IdleStateHandler)** — 读 / 写空闲回调，支持 EXCLUSIVE / SHARED 模式。
- **优雅停机** — `shutdownGraceful()` 停止接受新连接 → 排空在途请求 → 强制关闭。
- **H2 连接监控** — `-Dwastnet.h2.monitor=true` 启用，`H2Monitor.global()` 获取存活连接快照。
- **HTTP/2 Trailer 块** — `getTrailers()` / `setTrailersListener(TrailersListener)`（RFC 7540 §8.1）。
- **安全特性** — 路径穿越防护、HTTP 方法白名单、请求大小限制、连接超时检测、WebSocket Origin 校验；覆盖主流 Web 漏洞的基础防护。
