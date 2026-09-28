# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [1.0.2] - 2026-09-29

### Added

- Strongly-typed Option configuration mechanism (`HttpOptions` / `SocketOptions` / `Option`), config scoped per server instance, with isolation across multiple servers.
- Standalone `wastnet-mvc` module: annotation-driven MVC / IoC split out of core.
- Global configuration for health-check response content type and body.
- `request()` accessor on SSE `SseEmitter`.

### Changed

- HTTP request-body decoding rework (`HttpBodyDecoder` family, `HttpDecodedRequest` / `HttpDecodedResponse` / `HttpDefaultResponse`, etc.).
- HTTP header and message handling rework (`HttpHeaderUtils`, `HttpMessage`, `HttpRequestDecoder`, etc.).
- Routing configuration optimization (`HttpRouterHandler`, `HttpServerChannelHandler`).
- Annotation MVC result handling enhancement: ContentType-based converter registration, built-in TEXT converter, view resolver and default result handling.
- HTTP/2 flow control, timeout and Huffman decoding rework; H2 message reading and frame processing rework.
- TCP layer rework (`ChannelContext`, `ChannelRunner`, `NioConfig` / `NioEngine`, `SocketConf` / `SocketOptions`, etc.).
- JDK8 compatibility simplification: drop the JDK9 runtime branch (`RuntimeEnvJDK9Plus` removed).
- Reverse-proxy layer adaptation (`HttpProxyConfig` / `HttpProxyVariables` / adapters).
- File upload Multipart handling adjustments.

### Fixed

- Fix URI percent-decoding truncating values containing `=`.
- Harden static resource symlink protection.
- Improve HTTP/2 stream cleanup / connection close.
- Correct the HTTP/2 `PING_ACK` flag value to comply with RFC 7540.

---

## [1.0.1] - 2026-08-09

First stable release.

### Added

- **Reactor multi-threaded networking framework** — single Acceptor thread plus multiple Worker threads (independent Selectors), lock-free design.
- **Zero third-party dependencies** — JDK only, no external libraries (JDK 8 compilation target).
- **TCP server / client** — NIO implementation with auto-reconnect, custom codecs, and SSL/TLS support.
- **SSL/TLS** — PEM / JKS certificate loading, first-byte sniffing to auto-detect plaintext / encrypted connections.
- **HTTP/1.1** — full HTTP method support (standard methods plus WebDAV / extension methods), Pipeline, Keep-Alive, SSE.
- **HTTP/2 (h2 / h2c)** — HPACK header compression, Huffman coding, flow control, multiplexing, ALPN negotiation, H2C cleartext upgrade, H2→H1 proxy translation.
- **103 Early Hints** — static resource preload hints (RFC 8297), supported over both H1 and H2.
- **Routing** — exact match, prefix match, regex match, HTTP method filtering.
- **Annotation routing (MVC)** — `@Controller` / `@Endpoint` annotation routing plus lightweight dependency injection and message conversion SPI.
- **Reverse proxy** — URL rewrite, header variables (`$remote_addr`, etc.), frontend/backend protocol translation across h1/h2 and plaintext/HTTPS.
- **WebSocket** — frame codec, text / binary / Ping-Pong / fragmented frames.
- **Static resources** — zero-copy transfer, negotiation caching, streaming GZIP compression.
- **File upload** — Multipart/Form-Data parsing, streaming body reads, large files spilled to temp files.
- **Chunked encoding** — bidirectional Chunked Transfer Encoding for requests and responses.
- **GZIP compression** — automatic compression (configurable threshold and MIME-type filtering).
- **Server-Sent Events (SSE)** — `SseEmitter`-based push, thread-safe, auto-close on timeout.
- **Protocol codecs** — `LengthFrameCodec` (length-prefixed frames), `ObjectCodec` (object message protocol) and codec extension abstractions.
- **Request interceptor (HttpServerInterceptor)** — intercepts before the business handler for auth, CORS preflight, rate limiting.
- **Router-level interceptors** — bind `PRE_ROUTE` / `ENDPOINT` interceptors to specific controllers / endpoints via `@WithInterceptor`.
- **Health check endpoint** — `router.healthRoute(path)` built-in endpoint exposing uptime, route stats and other runtime info.
- **Server observer (HttpServerObserver)** — request / connection lifecycle monitoring SPI, zero overhead when unregistered.
- **Connection filter (ConnectionFilter)** — TCP accept-level IP black / white list and per-IP connection limiting.
- **Idle connection detection (IdleStateHandler)** — read / write idle callbacks with EXCLUSIVE / SHARED modes.
- **Graceful shutdown** — `shutdownGraceful()` stops accepting, drains in-flight requests, then forces close.
- **H2 connection monitor** — `-Dwastnet.h2.monitor=true` enables `H2Monitor.global()` snapshots of live connections.
- **HTTP/2 trailer blocks** — `getTrailers()` / `setTrailersListener(TrailersListener)` (RFC 7540 §8.1).
- **Security features** — path traversal protection, HTTP method whitelist, request size limits, idle connection timeout, WebSocket Origin validation; baseline protection against common mainstream web vulnerabilities.
