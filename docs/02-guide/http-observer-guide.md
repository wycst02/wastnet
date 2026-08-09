# HTTP 服务器观察者（HttpServerObserver）使用指南

`HttpServerObserver` 是 wastnet 提供的**观察者 SPI**，用于在请求 / 连接生命周期的关键节点注入监控逻辑。框架本身**不内置任何实现**，由应用层或可选模块提供。本文档是 README「服务器观察者」章节的深入补充。

适用场景：指标采集（Prometheus / Micrometer）、分布式追踪（TraceId 注入）、审计日志、慢请求采样、连接数监控等。

---

## 1. 设计定位

- **SPI 而非内置组件**：`HttpServerObserver` 只是一个接口，框架只负责在正确的时机回调它，不关心实现内容，也不依赖任何具体监控库。
- **零侵入、默认不启用**：不设置观察者即完全不触发任何回调，热路径（请求处理）上没有任何额外开销。
- **只读观察**：观察者**不得**修改请求 / 响应，也**不得**中断处理流程。需要改写请求 / 响应请使用拦截器（见 [http-interceptor-guide.md](http-interceptor-guide.md)）或处理器链。

---

## 2. 注册方式

```java
// HTTPServer 链式配置（推荐）
HTTPServer.of(8080)
        .observer(myObserver)
        .requestHandler(router)
        .start();
```

> 默认不设置观察者即完全不触发任何回调；若之前已设置，可传入 `null` 停用。

---

## 3. 回调方法

| 方法 | 触发时机 | 入参要点 |
|:-----|:---------|:---------|
| `onRequestStart(HttpRequest)` | 请求开始被处理（解析完成、进入 handler 之前） | 可在此累加「请求总数」「活跃请求数」 |
| `onRequestComplete(HttpRequest, HttpResponse, durationNanos, error)` | 响应提交之后 | `durationNanos` 为本次处理耗时（纳秒）；`error` 非 null 表示业务处理器**抛出异常**（由异常处理器处理，通常返回 5xx）；业务主动写 5xx 状态码但未抛异常时 `error` 为 null；`response.getStatus()` 已确定为最终状态 |
| `onConnectionOpen(ChannelContext)` | 连接建立（accept / TLS handshake 之后） | 累加「连接总数」「活跃连接数」 |
| `onConnectionClose(ChannelContext)` | 连接关闭 | 递减「活跃连接数」 |

### 入参可用性

- **`onRequestStart`**：`HttpRequest` 已解析完成，可读取 URI、Header、Method 等，但此时响应尚未生成。
- **`onRequestComplete`**：`HttpResponse` 的状态码已最终确定，可安全读取 `response.getStatus().code` 做状态码分桶；`durationNanos` 为框架内部计时的处理耗时（不含网络传输）。
- **`onConnectionOpen` / `onConnectionClose`**：`ChannelContext` 提供远端地址等信息（`ctx.getRemoteAddress()`），可用于按客户端 IP 聚合连接数。

---

## 4. 一个最小实现

```java
public class MyObserver implements HttpServerObserver {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder activeRequests = new LongAdder();

    @Override
    public void onRequestStart(HttpRequest request) {
        totalRequests.increment();
        activeRequests.increment();
    }

    @Override
    public void onRequestComplete(HttpRequest request, HttpResponse response,
                                  long durationNanos, Throwable error) {
        activeRequests.decrement();
        // error != null 时通常代表 5xx
    }

    @Override
    public void onConnectionOpen(ChannelContext ctx) { /* ... */ }

    @Override
    public void onConnectionClose(ChannelContext ctx) { /* ... */ }
}
```

> **务必使用原子类**（`LongAdder` / `AtomicLong`）。所有回调在 I/O Worker 线程上执行，可能被多个线程并发调用，普通 `long` / `int` 字段会产生数据竞争。

---

## 5. 并发安全

`HttpServerChannelHandler` 在 `start()` 时通过 `prepare()` 将观察者 / 拦截器（通过 `setObserver` / `setInterceptor` 设置，若已设置）一次性组装进一个不可变的 `HttpRequestLifecycleDelegate`；请求路径只读取 `delegate` 引用（先捕获到**局部变量**）再使用，因此：

- **不会出现「判空通过、使用时变 null」的 NPE**：`delegate` 引用在请求内被捕获为局部变量，运行时即使替换 SPI 也不会让正在进行的请求崩溃。
- **一次请求内回调对称**：同一个请求上的 `onRequestStart` 与 `onRequestComplete` 一定落在**同一个 delegate 内的 observer 实例**上，计数与耗时天然配对，不会出现「start 算 A、complete 算 B」的错配。
- **热路径零额外开销**：未注册观察者 / 拦截器时 `delegate` 为 `null`，请求路径仅一次 `delegate != null` 判断。

> 配置须在 `start()` **之前**完成；`start()` 之后再调用 `setObserver` / `setInterceptor` **不会生效**（delegate 只在 `prepare()` 组装一次，框架不提供运行期热更新能力）。如需更换观察者 / 拦截器，须重启服务。

---

## 6. 停机清理（ClearableHandler）

若观察者实现了 `io.github.wycst.wastnet.socket.handler.ClearableHandler`，框架在 server `stop()` 时会调用其 `clear()`：

```java
public class MyObserver implements HttpServerObserver, ClearableHandler {
    // ... 计数器 ...
    @Override
    public void clear() {
        totalRequests.reset();
        activeRequests.reset();
        // ... 其余计数器 reset ...
    }
}
```

---

## 7. 完整可运行示例（Prometheus 风格）

`wastnet-test` 模块提供了开箱即用的示例，位于 `io.github.wycst.wastnet.examples.http.prometheus` 包：

- `SimpleCounterServerObserver` — 基于原子计数器的实现，零第三方依赖，跟踪：
  - 请求总数 / 活跃请求数
  - 状态码分桶：`2xx` / `3xx` / `4xx` / `5xx` / 异常请求数
  - 连接总数 / 活跃连接数
  - 累计耗时 / 平均耗时 / 最大耗时（纳秒）
- `PrometheusServerExample` — 带 `main` 的启动类，注册观察者并通过 `/metrics` 端点暴露快照。

### 启动

```java
// 直接运行 PrometheusServerExample.main()，然后访问：
//   http://localhost:8080/        普通请求（被 observer 计数）
//   http://localhost:8080/metrics 当前指标快照
SimpleCounterServerObserver observer = new SimpleCounterServerObserver();

HTTPServer.of(8080)
        .observer(observer)
        .requestHandler((req, resp) -> {
            if ("/metrics".equals(req.getRequestUri())) {
                resp.contentType("text/plain;charset=utf-8")
                    .body(formatMetrics(observer.getMetrics()));
                return;
            }
            resp.body("hello world".getBytes());
        })
        .start();
```

> 可用 `-Dport=9090` 覆盖默认端口。`getMetrics()` 返回 `Map<String,Object>` 快照，示例里用 `key=value` 文本格式化后由 `/metrics` 端点输出。

---

## 8. 指标语义与常见误区

### 累计值 vs 瞬时值

| 指标 | 类型 | 含义 | 压测时表现 |
|:-----|:-----|:-----|:-----------|
| `totalConnections` | counter（累计） | 从启动到现在**一共建立**的 TCP 连接数 | 只增不减，会随重连持续上涨 |
| `activeConnections` | gauge（瞬时） | **当前时刻**打开的连接数 | 约等于 `-c` 并发值 |
| `totalRequests` | counter（累计） | 累计请求数 | 只增不减 |
| `activeRequests` | gauge（瞬时） | 当前正在处理的请求数 | 约等于并发请求数 |

### 为什么 `wrk -c 200` 后 `totalConnections` 不是 200？

这是**概念差异，不是计数 bug**。`totalConnections` 是累计建连总数，而 `wrk -c 200` 是 wrk 声称要维持的**目标并发**，二者不保证相等：

1. **连接会被重建**：HTTP/1.1 keep-alive 超时后服务端关闭连接，wrk 会重新建立，每次重建都使 `totalConnections` 再 +1。
2. **两轮紧挨着跑会少几个**：第 1 轮结束时 wrk 关闭的 200 个连接在**客户端**进入 TIME_WAIT（约 60s），第 2 轮立刻再建时客户端 ephemeral 端口被占用，可能少建 1~2 个连接。
3. **浏览器访问可能复用连接**：同源 keep-alive 下浏览器会复用已有连接，不触发新 `onConnectionOpen`。

验证方法：

- 单轮 `wrk -c 200` → `totalConnections` 大概率正好是 **200**；
- 两轮之间 `sleep 60s`（等 TIME_WAIT 释放）再跑 → 总数接近 **400**；
- 想要「峰值并发连接数」，demo 当前没有，可加一个 `maxActiveConnections`（在 `onConnectionOpen` 时用 CAS 记录 `activeConnections` 的历史最大值）。

> 计数器使用 `LongAdder.increment()`，并发安全、不会丢计数。看到的数值即服务端**真实 accept 到的连接总数**。

---

## 9. 与拦截器的区别

| 维度 | HttpServerObserver | 拦截器 / 处理器链 |
|:-----|:-------------------|:--------------------|
| 目的 | 观察、采集、埋点 | 改写请求 / 响应、鉴权、熔断 |
| 能否修改数据 | 否 | 是 |
| 能否中断流程 | 否 | 是（可直接返回响应） |
| 执行线程 | I/O Worker | I/O Worker |
| 典型用途 | 指标、追踪、审计 | 认证、限流、参数改写 |

---

## 10. 扩展建议

- **分布式追踪**：在 `onRequestStart` 中从 Header 读取 / 生成 `TraceId`，存入 `ChannelContext` 的 attachment，在 `onRequestComplete` 中上报 span。
- **慢请求采样**：在 `onRequestComplete` 中判断 `durationNanos` 超过阈值时，将 `request` 关键信息异步写入慢请求日志。
