# HTTP Response API 文档

## 概述

Wastnet HTTP Response API 提供了一套完整、高性能的HTTP响应处理机制，支持同步和异步响应处理、chunked传输编码、自动头部管理等功能。

## 核心接口

### HttpResponse 接口

`HttpResponse` 是HTTP响应的核心抽象接口，定义了所有响应操作的标准API。

#### 基础信息设置

```java
// 设置HTTP状态码
void setStatus(HttpStatus status);

// 获取HTTP版本
HttpVersion getHttpVersion();

// 获取当前状态码
HttpStatus getStatus();
```

#### 响应头管理

```java
// 添加响应头（支持重复键）
void addHeader(String key, Serializable value);

// 链式添加响应头
HttpResponse header(String key, Serializable value);

// 移除整个响应头
void removeHeader(String key);

// 移除指定键值对
void removeHeader(String key, Serializable value);
```

#### 内容相关设置

```java
// 设置Content-Length
void setContentLength(long contentLength);
HttpResponse contentLength(long contentLength);

// 获取Content-Length
long getContentLength();

// 设置Content-Type
void setContentType(String contentType);
HttpResponse contentType(String contentType);

// 获取Content-Type
String getContentType();

// 设置字符编码
void setCharacterEncoding(String characterEncoding);
String getCharacterEncoding();

// 设置Last-Modified时间戳
void setLastModified(long lastModifiedMillis);
```

#### 响应体写入

```java
// 写入响应体数据（追加到已有缓冲区）
void write(byte[] buf) throws IOException;
void write(byte[] buf, int offset, int count) throws IOException;

// 链式替换响应体（无拷贝，直接替换内部缓冲区引用）
HttpResponse body(byte[] body);

// 获取输出流
OutputStream outputStream();

// 写入chunked数据
void writeChunked(byte[] data) throws IOException;
```

**`body()` vs `write()` 的区别：**

| 方法 | 行为 | 适用场景 |
|------|------|---------|
| `body(byte[])` | **无拷贝替换**：直接替换内部 bodyBuf 的引用，不会复制字节数组 | 已持有完整 body 数据，一次性写入 |
| `write(byte[])` | **持续追加**：将数据拷贝到 bodyBuf 中，可多次调用持续写入；数据量超过 `BODY_MEMORY_THRESHOLD` 时自动刷到 channel 防 OOM | 流式写入或分块组装响应体 |

```java
// body() — 无拷贝替换
byte[] json = "{\"message\":\"ok\"}".getBytes();
response.body(json);   // 直接引用 json 数组，不拷贝

// write() — 持续追加
response.write(part1);  // 拷贝到 bodyBuf
response.write(part2);  // 继续追加
response.commit();
```

#### 传输控制

```java
// 刷新响应数据
void flush() throws IOException;

// 提交响应并完成传输
void commit() throws IOException;
```

**重要：应用层通常无需手动调用 `flush()` 和 `commit()`。**

框架在 handler 返回后自动完成响应提交。以下为自动提交的行为说明：

| 场景 | 自动行为 |
|------|---------|
| 同步 handler + `autoCommit=true`（默认） | handler 返回时框架自动 `commit()` |
| 异步 handler（`autoCommit=false`） | 需手动调用 `commit()` 结束响应 |
| 仅调用了 `body()` / `write()` | 无需额外操作，handler 返回后自动提交 |
| 使用了 `writeChunked()` | 最后需调用 `commit()` 发送结束标记 |

**`commit()` 不可过早调用。** 一旦 `commit()` 执行完成，响应即视为结束，后续的 `write()` / `body()` 等操作不会再有实际效果（框架不做防御检查，既不报错也不发送）。如果需要在主体写入完成后提前结束响应，正确做法是：

```java
// 正确：全部写入完成后调用 commit 结束
response.body(data);
response.commit();

// 错误：commit 之后再写入无效
response.commit();
response.write(moreData);  // 已提交，数据不会再发送，也不报错
```

> **`commit()` 能不用就不用。** `HttpResponse` 是轻量设计，没有做应用层调用防御 —— `commit()` 后继续 `write()`/`body()` 不会发送数据，也不会有任何异常提醒，不容易排查。**没有异步等特殊需求时直接忽略 commit 即可，让框架在 handler 返回后自动处理。**

#### Chunked传输编码

```java
// 获取chunked状态
boolean isChunked();

// 设置chunked编码
void setChunked(boolean chunked);
void setChunkedEncoding();
void removeChunkedEncoding();

// 链式设置chunked
HttpResponse chunked();
HttpResponse chunked(boolean chunked);
```

#### 连接管理

```java
// 获取keep-alive状态
boolean isKeepAlive();

// 设置keep-alive
void setKeepAlive(boolean keepAlive);
```

#### 自动提交控制

```java
// 设置自动提交模式
void setAutoCommit(boolean autoCommit);

// 获取自动提交状态
boolean isAutoCommit();
```

## 实现类：HttpDefaultResponse

`HttpDefaultResponse` 是 `HttpResponse` 接口的默认实现，提供了完整的HTTP响应处理功能。

### 构造方法

```java
protected HttpDefaultResponse(HttpRequest request, ChannelContext ctx)
```

参数说明：
- `request`: HTTP请求对象，用于获取HTTP版本和连接信息
- `ctx`: 通道上下文，用于底层网络I/O操作

### 核心特性

#### 1. 自动头部管理

响应会自动添加以下标准HTTP头部（前提是对应头部尚未由应用显式设置）：
- `Date`: 当前时间戳（始终写入）
- `Connection`: 连接状态（mirror 请求或基于 HTTP 版本，始终写入）
- `Server`: 服务器标识（wastnet/version）——**仅在 `server-header.expose=true` 时写入，默认隐藏**

**可通过 HttpConf 开关控制：**

| 配置键 | 默认值 | 说明 |
|-------|--------|------|
| `wastnet.http.header.default.enabled` | `true` | 是否自动写入 Date、Connection 等默认头 |
| `wastnet.http.server-header.expose` | `false` | 是否暴露 Server 头（默认隐藏，安全最佳实践） |

关闭自动头部（适合安全加固或减少带宽）：

```properties
# wastnet-http.properties
wastnet.http.header.default.enabled=false
wastnet.http.server-header.expose=false
```

> 详细配置说明参见 [HttpConf 配置参考](../03-reference/http-conf-reference.md)。
>
> SSE（Server-Sent Events）推送 API 参见 [SSE 指南](sse-guide.md)。

#### 2. Content-Length自动计算

```java
// 当未显式设置Content-Length时，会根据响应体大小自动计算
response.body("Hello World".getBytes());
// 自动设置Content-Length: 11
```

#### 3. Chunked传输编码支持

```java
// 启用chunked编码
response.chunked();
response.writeChunked(data1);
response.writeChunked(data2);
response.commit();
```

#### 4. 异步处理控制

当需要进行异步处理时，需要关闭自动提交并手动调用commit()：

```java
response.setAutoCommit(false);
CompletableFuture.runAsync(() -> {
    // 异步处理逻辑
    response.body("Async result".getBytes());
    response.commit(); // 异步场景下必须显式调用
});
```

## 使用示例

### 基础同步响应

```java
public class SimpleHandler implements HttpRequestHandler {
    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        response
            .status(HttpStatus.OK)
            .contentType("text/plain;charset=utf-8")
            .body("Hello World!".getBytes());
    }
}
```

### JSON API响应

```java
public class JsonHandler implements HttpRequestHandler {
    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        String jsonStr = "{\"message\":\"success\",\"data\":{}}";
        byte[] jsonBytes = jsonStr.getBytes();
        response
            .status(HttpStatus.OK)
            .contentType("application/json;charset=utf-8")
            .contentLength(jsonBytes.length)
            .body(jsonBytes);
    }
}
```

### 文件下载响应

```java
public class FileDownloadHandler implements HttpRequestHandler {
    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        File file = new File("example.pdf");
        response
            .header("Content-Disposition", "attachment; filename=\"example.pdf\"")
            .sendFile(file); // 零拷贝发送，无需手动设置 status/content-length，sendFile 内部自动处理
    }
}
```

### Chunked流式响应

```java
public class StreamHandler implements HttpRequestHandler {
    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        response
            .status(HttpStatus.OK)
            .contentType("text/event-stream")
            .chunked(); // 启用chunked编码
        
        // 发送多个数据块
        for (int i = 0; i < 10; ++i) {
            String data = "data: Message " + i + "\n\n";
            response.writeChunked(data.getBytes());
            Thread.sleep(1000); // 模拟延迟
        }
        response.commit();
    }
}
```

### 异步处理响应

```java
public class AsyncHandler implements HttpRequestHandler {
    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        // 关闭自动提交
        response.setAutoCommit(false);
        
        // 异步处理
        CompletableFuture.supplyAsync(() -> {
            // 耗时操作
            return processData();
        }).thenAccept(result -> {
            try {
                response
                    .status(HttpStatus.OK)
                    .contentType("application/json")
                    .body(result.getBytes())
                    .commit();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
    
    private String processData() {
        // 模拟耗时处理
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "{\"result\":\"processed\"}";
    }
}
```

## 性能优化特性

### 1. 缓冲区优化

- 使用 `HttpBuf` 进行高效的字节操作
- 减少不必要的字节数组转换
- 优化内存使用

### 2. 头部处理优化

- 预计算常用头部字节序列
- 避免重复的字符串操作
- 统一的头部标准化处理

### 3. 网络I/O优化

- 批量写入操作减少系统调用
- 零拷贝缓冲区管理
- 高效的chunked编码实现

### 4. Body 内存阈值防 OOM

框架内部使用 `HttpConf.BODY_MEMORY_THRESHOLD`（默认 512KB）控制响应 body 缓冲区大小：

```java
// 当缓冲区数据超过阈值时，自动 flush 到网络 channel，防止 OOM
// 实际 bodyBuf 大小不会超过 BODY_MEMORY_THRESHOLD × 2
response.write(largeData);  // 内部自动分片写入
```

### 5. 文件发送策略

`HttpDefaultResponse` 根据文件大小和连接类型自动选择最优发送策略：

| 条件 | 策略 | 说明 |
|------|------|------|
| SSL 连接 + 任意大小文件 | `sendFileBuffered()` | 需加密，走缓冲读路径 |
| 非 SSL + 文件大小 < writeBufferSize | `sendFileBuffered()` | 头部和文件数据一起 flush |
| 非 SSL + 文件大小 ≥ writeBufferSize | `sendFileZeroCopy()` | `FileChannel.transferTo()` 零拷贝；大文件自动分片（每片 ~1GB） |

> **注**：零拷贝触发条件为非 SSL 且 `estimatedTotalSize ≥ writeBufferSize`（默认仅 1024B）；`~1GB` 是 `transferTo` 内部的分片上限，不是触发阈值。`writeBufferSize` 仅对明文连接生效，SSL 连接默认使用内置协议缓冲（约 16KB）。


---

## 高级响应 API

### handover() — 移交响应控制权

当需要由框架内部逻辑（如代理转发、资源下载）完全接管响应发送时调用。`handover()` 在基类 `HttpInternalResponse` 中声明为抽象方法（由 `HttpDefaultResponse` 实现），需将 `HttpResponse` 强转为实现类后调用：

```java
public void handle(HttpRequest request, HttpResponse response) throws Throwable {
    // 由 HttpProxyRoute 或其他内部 handler 接管响应
    ((HttpDefaultResponse) response).handover();
}

// 调用后：
// - 框架跳过自动提交和 flush
// - 响应内容必须由内部逻辑通过原始 ChannelContext 写入
// - 用于代理、sendFile 等场景
```

### isCorrupted() — 检查响应流状态

当 `sendFile()` 发送失败时（IO 异常），`HttpDefaultResponse` 会将响应标记为损坏：

```java
try {
    response.sendFile(largeFile);
} catch (IOException e) {
    if (response.isCorrupted()) {
        // 文件发送失败，数据可能不完整
        // HttpResponse 无 close() 方法：重新抛出，交由框架异常处理流程统一处理
        throw e;
    }
}
```

> **注意**：`isCorrupted()` 定义于基类 `HttpInternalResponse`，由 `HttpDefaultResponse` 在文件压缩或发送过程中抛出 IOException 时置位，返回 `true`。

### write() 的内存保护机制

`write()` 方法在写入大响应体时自动进行内存保护：

```java
// 当数据大小超过 BODY_MEMORY_THRESHOLD 时：
// 1. 先 flush 现有缓冲区
// 2. 新数据直接写入 channel（不经过 bodyBuf）
// 3. Chunked 模式下直接作为一个独立 chunk 发送
response.write(largeBytes, offset, count);
```

## 103 Early Hints

`HttpResponse.earlyHints(String linkHeader)` 用于发送 103 Early Hints 响应（RFC 8297），在最终响应之前通知浏览器预加载资源。

### 原型

```java
void earlyHints(String linkHeader) throws IOException;
```

### 使用示例

```java
// 手动调用
response.earlyHints("</style.css>; rel=preload; as=style");
response.earlyHints("</app.js>; rel=preload; as=script");
```

### 路由级配置

配合 `HttpResourceRoute` 可以自动为静态资源索引页发送 103：

```java
new HttpResourceRoute("/", docBase)
    .earlyHints(
        "<$base_path/style.css>; rel=preload; as=style"
    );
```

### 注意事项

- **调用时机**：必须在响应 HEADERS 发送之前调用，否则静默忽略
- **协议兼容**：H1 发送原始 `HTTP/1.1 103 Early Hints` 文本，H2 发送 HEADERS 帧
- **多条提示**：每条 `earlyHints()` 调用发送一条独立的 103 响应

---

## 最佳实践

### 1. 异步处理

当需要进行异步处理时：

```java
response.setAutoCommit(false);
// ... 异步逻辑
response.commit();
```

### 2. 正确设置Content-Length

```java
// 已知内容长度时明确设置
response.contentLength(data.length).body(data);

// 未知长度时使用chunked编码
response.chunked().writeChunked(chunk1).writeChunked(chunk2);
```

### 3. 流式处理大文件

推荐直接使用 `sendFile()`，框架内部自动根据文件大小和连接类型选择零拷贝或缓冲策略，无需手写流式读写：

```java
response.header("Content-Disposition", "attachment; filename=\"large-file.bin\"")
        .sendFile(largeFile); // 零拷贝发送，自动设置 Content-Type/Content-Length 并 commit
```

如果需要在发送前对数据做额外处理（如加密、转换），也可手动流式读写：

```java
response.setContentType("application/octet-stream");
response.setHeader("Content-Disposition", "attachment; filename=\"large-file.bin\"");
response.setContentLength(largeFile.length());

try (InputStream is = new FileInputStream(largeFile)) {
    byte[] buffer = new byte[8192];
    int len;
    while ((len = is.read(buffer)) != -1) {
        // 可在此处对 buffer 做处理
        response.write(buffer, 0, len);
    }
}
response.commit();
```

### 4. 错误处理

```java
@Override
public void handle(HttpRequest request, HttpResponse response) throws Throwable {
    try {
        // 业务逻辑
        processBusinessLogic(request, response);
    } catch (Exception e) {
        response
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType("application/json")
            .body("{\"error\":\"Internal Server Error\"}".getBytes());
    }
}
```

## 注意事项

1. **线程安全**: `HttpResponse` 实例不是线程安全的，应在单个请求处理线程中使用

2. **资源清理**: 框架会自动管理资源清理，应用层通常不需要手动关闭

3. **异步处理**: 当进行异步处理时，需要调用 `setAutoCommit(false)` 并手动调用 `commit()`
   - 避免重复提交同一个响应

4. **头部设置**: 响应头必须在首次写入响应体之前设置

5. **Chunked编码**: 启用chunked后不能再设置Content-Length

## 版本历史

- **v1.0**: 基础HTTP响应功能
- **v1.1**: 添加chunked传输编码支持
- **v1.2**: 引入自动提交控制机制
- **v1.3**: 优化性能和内存使用

---
*本文档基于 Wastnet HTTP Framework v1.3*