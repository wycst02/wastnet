# SSE (Server-Sent Events) API 文档

## 概述

Wastnet 提供完整的 Server-Sent Events 支持，允许服务端向客户端推送实时事件。支持三种使用模式：

- **原始实现**：手动设置 header、拼接 `data:` 格式、write/flush
- **loop 模式**（`response.sse()`）：简单直接，handler 线程内循环推送
- **emitter 模式**（`router.sse()` + `emitter.emit()`）：多线程安全，handler 即时返回

## 方式一：原始实现（手工拼接，仅供参考）

> **仅供参考。** 应用层推荐使用方式二（`response.sse()`）或方式三（emitter 模式）。此方式仅用于展示 SSE 底层协议的数据格式，帮助理解封装 API 的便捷性。

```java
router.get("/events/raw", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        response.contentType("text/event-stream; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .chunked();
        for (int i = 0; i < 5; ++i) {
            String data = "data: {\"count\":" + i + "}\n\n";
            response.write(data.getBytes(StandardCharsets.UTF_8));
            response.flush();
            Thread.sleep(1000);
        }
    }
});
```

## 方式二：loop 模式（response.sse()）

使用 `response.sse()` 在 handler 线程内循环推送数据。handler 执行期间连接保持打开，结束返回后框架自动提交响应。

```java
router.get("/events", new HttpRoute() {
    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        for (int i = 0; i < 10; ++i) {
            response.sse("{\"count\":" + i + "}");
            Thread.sleep(1000);
        }
    }
});
```

对比原始实现，`response.sse()` 一行代替了 header 设置、`data:` 格式拼接、getBytes、flush 等 4-5 行代码。

## 方式三：emitter 模式

通过 `router.sse()` 注册 SSE 端点，获取 `SseEmitter` 实例进行推送。

### 基本使用

```java
HttpRouterHandler router = new HttpRouterHandler();

router.sse("/news", emitter -> {
    for (int i = 1; i <= 10; ++i) {
        emitter.emit("{\"id\":" + i + "}");
        Thread.sleep(1000);
    }
    emitter.close();
});
```

### 完整参数

```java
emitter.emit("chat", "hello", "msg-001", 3000);
```

参数说明：

| 参数 | 类型 | 说明 |
|------|------|------|
| event | String | 事件类型（null 则省略） |
| data | String | 事件数据 |
| id | String | 事件 ID，断线重连时浏览器通过 Last-Event-ID 恢复（null 则省略） |
| retry | long | 重连间隔毫秒数（<= 0 则省略） |

### 自定义超时

默认超时 30 分钟。可通过第二个参数指定：

```java
router.sse("/events", 60000L, emitter -> { ... });  // 60 秒超时
```

### 线程安全

`emit()` 和 `close()` 是线程安全的，可从多个线程同时调用。

```java
router.sse("/events", 60000L, emitter -> {
    for (int i = 1; i <= 10; ++i) {
        final int seq = i;
        new Thread(() -> {
            emitter.emit("data", "msg-" + seq, "id-" + seq, 3000);
        }).start();
    }
});
```

## API 参考

### `HttpResponse.sse(String data)`

发送一个简单的 SSE 事件（`data: <data>\n\n`）。

### `HttpResponse.sse(String event, String data)`

发送带事件类型的 SSE 事件（`event: <event>\ndata: <data>\n\n`）。

### `HttpResponse.sse(String event, String data, String id, long retry)`

发送完整参数的 SSE 事件。

### `HttpRouterHandler.sse(String path, SseHandler handler)`

注册 SSE 端点，默认超时 30 分钟。

### `HttpRouterHandler.sse(String path, long timeoutMs, SseHandler handler)`

注册 SSE 端点，指定自定义超时。

### `SseEmitter.emit(String data)`

推送 data-only 事件。

### `SseEmitter.emit(String event, String data, String id, long retry)`

推送完整事件。

### `SseEmitter.close()`

关闭 SSE 连接。框架自动提交响应（发送 chunked 结束标记或 H2 END_STREAM）。

### `SseEmitter.onClose(Runnable callback)`

注册连接关闭回调（客户端断开或服务端主动关闭时触发）。

### `SseEmitter.isClosed()`

检查连接是否已关闭。

## 如何选择

| 场景 | 推荐方式 |
|------|---------|
| handler 线程内简单定时推送 | loop 模式（`response.sse()`） |
| 多线程/异步推送 | emitter 模式（`router.sse()`） |
| 需要自定义超时 | emitter 模式 + `sse(path, timeoutMs, handler)` |
| 需要断线重连 ID | emitter 模式 + `emit(event, data, id, retry)` |


## 示例代码

| 示例 | 文件 | 说明 |
|------|------|------|
| SSE 三种实现方式对比 | `io.github.wycst.wastnet.examples.http.SseExample` | 原始实现、loop 模式、emitter 模式 |
| 综合演示（含 WebSocket 聊天室 + SSE） | `io.github.wycst.wastnet.examples.http.ChatExample` | 启动后访问 `http://localhost:8080` |
