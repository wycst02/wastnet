# HTTPServer API 参考

`HTTPServer` 是 wastnet 的 HTTP 服务器入口类，继承自 `TCPServer`，提供链式 API 进行配置和启动。

---

## 构造与静态工厂

```java
// 指定端口，使用默认 NioConfig
HTTPServer server = new HTTPServer(8080);

// 指定端口和自定义 NioConfig
HTTPServer server = new HTTPServer(8080, nioConfig);

// 静态工厂（推荐）
HTTPServer server = HTTPServer.of(8080);

// 静态工厂 + 自定义 NioConfig
HTTPServer server = HTTPServer.of(8080, nioConfig);
```

---

## 配置方法完整列表

### 请求处理器

```java
// 默认请求处理器（路径 "/**"），处理所有到达的 HTTP 请求
//
// 建议：对于大多数场景，推荐使用 HttpRouterHandler 替代原始 HttpRequestHandler，
// 以获得路由分发、Context Path、静态资源、反向代理等内置能力。
// 参见 docs/http-router-guide.md
HTTPServer requestHandler(HttpRequestHandler requestHandler)

// 设置协议升级处理器（WebSocket / h2c）
HTTPServer upgradeHandler(UpgradeHandler upgradeHandler)

// 自定义异常处理器，当 requestHandler 抛出异常时被调用
HTTPServer exceptionHandler(HttpExceptionHandler exceptionHandler)
```

**示例：**

```java
HTTPServer.of(8080)
    // 推荐使用 HttpRouterHandler（支持路由分发、Context Path、
    // WebSocket、反向代理等），详见 http-router-guide.md
    .requestHandler(router)
    .upgradeHandler(upgradeHandler)
    .exceptionHandler(new HttpExceptionHandler() {
        public void handleException(HttpRequest request, HttpResponse response, Throwable throwable) {
            response.status(500).write(("Error: " + throwable.getMessage()).getBytes());
        }
    })
    .start();
```

### SSL/TLS 配置

```java
// 启用/禁用 SSL
HTTPServer ssl(boolean sslFlag)

// 直接设置 SSLContext（JKS 方式）
HTTPServer sslContext(SSLContext sslCtx)

// 设置自定义 SSLContextFactory
HTTPServer sslContextFactory(SSLContextFactory sslContextFactory)

// 通过 classpath 加载 OpenSSL PEM 格式证书和私钥（无需 keytool 转换）
HTTPServer pemSSL(String certResource, String keyResource)

// 设置 SSL 加密套件
HTTPServer sslCipherSuites(String... sslCipherSuites)

// 设置 ALPN 应用层协议协商
HTTPServer applicationProtocols(String... applicationProtocols)
```

**PEM 证书示例：**

```java
HTTPServer.of(8443)
    .pemSSL("cert/cert.pem", "cert/server.pem")  // classpath 路径
    .requestHandler(router)
    .start();
```

> 测试用 PEM 证书位于 `wastnet-test/src/main/resources/cert/` 目录下，可直接在测试项目中引用。生产环境请替换为真实证书。

**ALPN / HTTP/2 示例：**

```java
// 告知客户端优先使用 h2，客户端不支持 h2 则自动回退 h1
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .applicationProtocols("h2", "http/1.1")
    .requestHandler(router)
    .start();

// 快捷方式：等价于 .applicationProtocols("h2")
// 服务端始终同时支持 H1 和 H2，仅通过 ALPN 告知客户端优先协商 h2
HTTPServer.of(8443)
    .pemSSL("cert.pem", "key.pem")
    .h2()
    .requestHandler(router)
    .start();
```

### 网络配置

```java
// 设置 Worker 线程数（处理 NIO 事件循环的线程数）
// 默认值由 NioConfig 决定：highestOneBit(availableProcessors)
// 范围：2 <= workerNum <= defaultWorkerNum << 2
HTTPServer workerNum(int workerNum)

// 设置读写缓冲区大小（字节），仅明文（非 SSL）连接生效
// SSL 连接默认使用内置协议缓冲（约 16KB），该设置不生效
HTTPServer bufferSize(int buffSize)

// 仅监听本地 127.0.0.1（不监听外部地址）
HTTPServer localOnly(boolean localOnly)
```

### 日志控制

```java
// 控制是否打印 SSL 错误日志（默认 false）
HTTPServer printSSLErrorLog(boolean bl)

// 控制是否打印读取错误日志（默认 false）
HTTPServer printReadErrorLog(boolean bl)

// 控制是否打印应用层消息日志（默认 false）
HTTPServer printApplicationMessage(boolean bl)

// 控制是否打印异常堆栈（默认 false)
HTTPServer printStackTraceError(boolean bl)
```

### 高级配置

```java
// 设置空闲状态处理器（心跳检测），见 IdleStateHandler
HTTPServer idleStateHandler(IdleStateHandler idleStateHandler)

// 设置连接过滤器（accept 级别），在资源分配前进行连接过滤
HTTPServer connectionFilter(ConnectionFilter connectionFilter)

// 设置子处理器，用于非 HTTP 协议的场景
// 不能替换内置的 serverChannelHandler，而是设置为它的子处理器
HTTPServer channelHandler(ChannelHandler<?> channelHandler)

// 设置替换 HTTP 协议读取器（协议解码器）
HTTPServer channelReader(ChannelReader channelReader)

// 自定义 NioConfig
HTTPServer config(NioConfig nioConfig)
```

**连接过滤器示例：**

```java
// import java.nio.channels.SocketChannel;

HTTPServer.of(8080)
    .connectionFilter(new ConnectionFilter() {
        public boolean onAccept(SocketChannel channel) {
            // 仅允许内网 IP
            InetSocketAddress remoteAddress = (InetSocketAddress) channel.socket().getRemoteSocketAddress();
            return remoteAddress.getAddress().isSiteLocalAddress();
        }
    })
    .requestHandler(router)
    .start();
```

**空闲连接检测示例：**

```java
HTTPServer.of(8080)
    .idleStateHandler(new IdleStateHandler(60, 0, TimeUnit.SECONDS) {  // 60 秒读空闲检测
        public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType type,
                long totalCount, long consecutiveCount) throws Throwable {
            // type: READER_IDLE / WRITER_IDLE / ALL_IDLE
            // totalCount: 该连接总触发次数
            // consecutiveCount: 连续触发次数
            ctx.close();  // 关闭空闲连接
        }
    })
    .requestHandler(router)
    .start();
```

### 生命周期

```java
// 启动服务器
HTTPServer start()

// 获取已注册的 UpgradeHandler（若存在）
UpgradeHandler upgradeHandler()
```

---

## 完整配置示例

```java
public class FullConfigServer {
    public static void main(String[] args) throws Exception {
        HTTPServer.of(8080)
            // === 请求处理 ===
            .requestHandler(router)
            .exceptionHandler(new HttpExceptionHandler() {
                public void handleException(HttpRequest request, HttpResponse response, Throwable t) {
                    response.status(500).write(("Error: " + t.getMessage()).getBytes());
                }
            })

            // === SSL ===
            .pemSSL("cert/cert.pem", "cert/key.pem")
            .h2()  // 告知客户端优先协商 h2，不支持的客户端自动使用 h1

            // === 网络 ===
            .workerNum(4)
            .bufferSize(65536)
            .localOnly(false)

            // === 日志 ===
            .printSSLErrorLog(true)
            .printReadErrorLog(true)
            .printStackTraceError(true)

            // === 高级 ===
            .connectionFilter(ipFilter)
            .idleStateHandler(idleHandler)

            .start();
    }
}
```

---

## 版本信息

```java
String ver = HTTPServer.VERSION;  // "1.0.2"
String server = HTTPServer.SERVER; // "wastnet"
```
