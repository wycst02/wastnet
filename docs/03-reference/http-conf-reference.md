# HTTP 配置参考手册

`HttpConf` 是 wastnet 的 HTTP 全局配置控制类，所有配置项通过 `wastnet-http.properties` 文件、系统属性或 Docker 环境变量进行设置。

**默认场景下无需任何配置即可运行。** 所有配置项均有缺省值，默认值基于常见场景的最佳实践设置（如 512KB 内存阈值、8KB 单头限制等）。除非有特定的生产环境安全加固或性能调优需求，通常不需要修改默认值。

---

## 配置文件加载

### 配置文件位置

`wastnet-http.properties` 的加载按以下优先级从低到高搜索（高优先级覆盖低优先级）：

| 优先级 | 位置 | 说明 |
|--------|------|------|
| 1 (最低) | `{classpath}/wastnet-http.properties` | 源代码根目录 |
| 2 | `{classpath}/config/wastnet-http.properties` | 源代码根目录 config 目录 |
| 3 | `{JAR 同级}/wastnet-http.properties` | JAR 文件同目录 |
| 4 | `{JAR 同级}/config/wastnet-http.properties` | JAR 文件同目录 config 子目录 |
| 5 (最高) | `{JAR 父级}/config/wastnet-http.properties` | JAR 文件所在目录的父级下的 config 目录 |

### 属性值优先级

配置项最终值的获取顺序：

```
系统属性 (System.getProperty)  > 环境变量 > 配置文件 (wastnet-http.properties)
```

---

> **大小类配置单位说明**：所有以「字节」为单位的配置项（如 `max-ws-frame-size`、`body-max-size`、`gzip-min-size`、`max-single-header-size` 等）均支持 `KB` / `MB` / `GB` 单位（大小写不敏感，如 `16MB`、`512kb`）；不带单位时按字节处理。**必须为整数，不支持小数**。例如 `wastnet.http.max-ws-frame-size=16MB`、`wastnet.http.body-max-size=512MB`。

## 配置项完整列表

### 头部安全与大小限制

| 配置键 | 说明 | 默认值 | 最小值 |
|-------|------|--------|-------|
| `wastnet.http.max-single-header-size` | 单个请求头的总大小限制（键 + 值，字节） | `8192` (8KB) | `1` |
| `wastnet.http.max-http-header-size` | 所有 HTTP 头部的总大小限制（字节） | `16384` (16KB) | `1` |
| `wastnet.http.max-uri-length` | 请求 URI (request-target) 的最大长度 | `16384` | `1` |

### Body 大小与内存控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.body-memory-threshold` | 响应 Body 内存阈值（字节）。缓冲区大小超过此值时立即刷新到 channel 以防止 OOM | `524288` (512KB) |
| `wastnet.http.max-body-in-memory` | 请求 Body 在内存中保留的最大大小（字节）。超过此值转为流式处理 | `2097152` (2MB) |
| `wastnet.http.body-max-size` | Body 允许的最大总大小（字节）。≤ 0 表示无限制。正值时最小为 `body-memory-threshold` | `536870912` (512MB) |
| `wastnet.http.enable-temp-file` | Body 超过内存阈值时是否生成临时文件。关闭时将忽略原需写入临时文件的上传域 | `true` |
| `wastnet.http.temp-file-dir` | 临时文件存储目录 | `{java.io.tmpdir}/wastnet-http` |
| `wastnet.http.temp-file-prefix` | 临时文件前缀 | `wastnet_tmp_` |

### 字符集与编码

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.default-charset` | 默认字符集 | `UTF-8` |

### GZIP 压缩

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.gzip` | 是否启用 GZIP 压缩 | `false` |
| `wastnet.http.gzip-min-size` | GZIP 压缩最小阈值（字节）。小于该值不压缩，`0` 表示无下限 | `2048` (2KB) |

### 响应头控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.header.default.enabled` | 是否自动写入默认响应头（Date、Server、Connection） | `true` |
| `wastnet.http.server-header.expose` | 是否在 HTTP 响应中暴露 Server 头（安全最佳实践建议隐藏） | `false` |
| `wastnet.http.header.order.preserve` | 是否保留 HTTP 头部插入顺序。`false` 使用 HashMap (性能优先)，`true` 使用 LinkedHashMap | `false` |

### 连接与请求控制

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.http.pipeline.enabled` | 是否启用 HTTP/1.1 Pipeline。启用后单个 TCP 包中的多个 HTTP 请求会被处理，禁用后多余的请求被丢弃 | `false` |
| `wastnet.http.request-timeout` | 从第一个字节到达到请求完整接收的最大允许时间（毫秒）。≤ 0 视为禁用。每次 NIO 数据到达时非阻塞检查，超时返回 408 Request Timeout | `60000` (60 秒) |
| `wastnet.http.implemented-methods` | 服务器实际实现的 HTTP 方法白名单（逗号分隔，如 `GET,POST,PUT,DELETE`）。未配置时所有已知方法均可使用；配置后将只识别列表中的方法，其他返回 501 Not Implemented | 全部放行 |

### WebSocket

| 配置键 | 说明 | 默认值 | 最小值 |
|--------|------|--------|--------|
| `wastnet.http.max-ws-frame-size` | WebSocket 单个帧的最大有效载荷大小（字节） | `16777216` (16MB payload) | `1` |
| `wastnet.http.max-ws-continuations` | 单个分片消息允许的最大 continuation 帧数量。用于防御碎片化攻击（攻击者发送大量微小 continuation 帧触发重复 merge-copy，O(n²) CPU 消耗） | `256` | `1` |
| `wastnet.http.max-ws-fragment-merge-timeout-ms` | 分片消息合并的最大超时时间（毫秒）。仅对 MERGE/BATCH 策略生效，STREAM 策略忽略。用于防御慢速碎片化攻击（攻击者以极低速率发送 continuation 帧以长时间占用服务端资源），超时后连接以 1009 状态码关闭 | `30000` (30s) | `1000` (1s) |

### SSE (Server-Sent Events)

| 配置键 | 说明 | 默认值 |
|--------|------|--------|
| `wastnet.http.sse-timeout-ms` | SSE 连接超时时间（毫秒）。超过该时间后 SSE 连接将自动关闭。`≤ 0` 表示禁用超时 | `1800000` (30 分钟) |

---

## 配置文件示例

```properties
# ==================== 头部安全 ====================
wastnet.http.max-single-header-size=8KB
wastnet.http.max-http-header-size=16KB
wastnet.http.max-uri-length=16384

# ==================== Body 控制 ====================
wastnet.http.body-memory-threshold=512KB
wastnet.http.max-body-in-memory=2MB
wastnet.http.body-max-size=512MB
wastnet.http.enable-temp-file=true
wastnet.http.temp-file-dir=${java.io.tmpdir}/wastnet-http
wastnet.http.temp-file-prefix=wastnet_tmp_

# ==================== 字符集 ====================
wastnet.http.default-charset=UTF-8

# ==================== GZIP 压缩 ====================
wastnet.http.gzip=false
wastnet.http.gzip-min-size=2KB

# ==================== 响应头 ====================
wastnet.http.header.default.enabled=true
wastnet.http.server-header.expose=false
wastnet.http.header.order.preserve=false

# ==================== WebSocket ====================
# wastnet.http.max-ws-frame-size=16MB
# wastnet.http.max-ws-continuations=256
# wastnet.http.max-ws-fragment-merge-timeout-ms=30000

# ==================== 请求控制 ====================
wastnet.http.pipeline.enabled=false
# wastnet.http.request-timeout=60000

# ==================== 方法实现白名单 ====================
# wastnet.http.implemented-methods=GET,POST,PUT,DELETE
```

---

## HTTP/2 配置

HTTP/2 相关配置已统一纳入 `HttpConf` 管理，可通过 `wastnet-http.properties`、环境变量或 JVM 系统属性（优先级同其他 HTTP 配置）设置：

| 配置键 | 默认值 | 说明 |
|-------|--------|------|
| `wastnet.http2.initial.send-window-size` | `65535` (64KB) | 初始发送/接收窗口大小（字节），钳制范围 64KB–16MB。增大可提升吞吐量，但会占用更多内存 |
| `wastnet.http2.max-concurrent-streams` | `512`（最小值 `100`） | 服务端最大并发流数量。默认已设为有界值防止资源耗尽（Rapid Reset 攻击）；配置值低于 `100` 时会自动钳制为 `100` |
| `wastnet.http2.client-rst.window-seconds` | `1`（最小值 `1`） | Rapid Reset（CVE-2023-44487）防御：统计单连接客户端发来的 RST_STREAM 帧的固定时间窗口（秒）。仅统计客户端发来的 RST，服务端主动发出的 RST 不计入（默认即可，正常流量不会触发） |
| `wastnet.http2.client-rst.max-count` | `100`（最小值 `1`） | 上述窗口内允许的客户端 RST_STREAM 上限，超过即判定为攻击并关闭连接。受固定窗口边界影响，极端情况下实际放行速率最高可达约 2 倍；如需更严格上限可调小此值（默认即可，正常流量不会触发；仅恶意攻击才会突破） |
| `wastnet.http2.hpack.huffman.enabled` | `true` | HPACK Huffman 编码开关。开启后对 ASCII-only 且长度 > 5 字节的 header 字符串启用 Huffman 压缩（典型节省 30-50% 带宽）。设为 `false` 可在 CPU 受限环境或调试时关闭。可通过 `HttpConf.HTTP2_HPACK_HUFFMAN_ENABLED` 程序化访问 |
| `wastnet.http2.debug` | `false` | 调试开关，开启后打印 H2 帧的详细信息（仍通过 JVM 系统属性 `Boolean.getBoolean` 直接读取，不纳入 `HttpConf`） |
| `wastnet.http2.flow-control-wait-timeout-ms` | `30000` (30秒，最小值 `1000`) | 等待对端通过 WINDOW_UPDATE 授予更多发送窗口的最大时间（毫秒）；超时则判定为 FLOW_CONTROL_ERROR 并关闭连接（RFC 7540 §6.9.2） |
| `wastnet.http2.stream-early` | `true` | 首次接收窗口耗尽即进入流式模式（而非缓冲到 `MAX_STREAM_CAPACITY_SIZE`）。开启后流在初始接收窗口耗尽时立即切换为流式（环形缓冲 = `INITIAL_RECEIVE_WINDOW_SIZE`），大幅降低大请求体的单流内存占用。可通过 `HttpConf.HTTP2_STREAM_EARLY` 程序化访问 |

### 通过系统属性设置

```bash
# 默认已为 512（有界，下限 100）；以下为高并发网关场景上调示例
java -Dwastnet.http2.max-concurrent-streams=1024 \
     -Dwastnet.http2.initial.send-window-size=1MB \
     -Dwastnet.http2.client-rst.window-seconds=1 \
     -Dwastnet.http2.client-rst.max-count=100 \
     -jar your-app.jar
# 可选：让 ConnectionFilter 实现 AbuseHandler 即可在超限时收到 "RST_FLOOD" 上报（如用于封 IP）
```

### 生产环境建议

```properties
# 默认已是 512 有界值（下限 100，低于 100 自动钳制），已可防资源耗尽（Rapid Reset）
# 高并发网关/反向代理场景可上调至 1024 等；值越低越安全，越高单连接并发能力越强
-Dwastnet.http2.max-concurrent-streams=1024
# 增大窗口以提升吞吐（1MB）
-Dwastnet.http2.initial.send-window-size=1MB
```

> 详细协议实现说明参见 [HTTP/2 协议详解](HTTP2_PROTOCOL.md)，服务端集成示例参见 [H2 服务端集成指南](../02-guide/h2-server-integration.md)。

---

## 运行时 API

`HttpConf` 提供一个运行时方法，可在应用代码中调用以查看当前生效的配置：

### dumpAsProperties()

以 Properties 格式输出所有 HTTP 配置（键名与配置文件一致，可直接复用）：

```java
String props = HttpConf.dumpAsProperties();
System.out.println(props);
// 输出：
// # HTTP Configuration
// wastnet.http.max-single-header-size=8192
// wastnet.http.max-http-header-size=16384
// ...
```

### getProperty(String key)

动态获取配置属性值（优先系统属性 > 环境变量 > 配置文件）：

```java
String value = HttpConf.getProperty("wastnet.http.gzip");
```

---

## 性能调优建议

### 大文件上传场景

```properties
wastnet.http.max-body-in-memory=1MB      # 1MB 内存阈值，超限转为流式
wastnet.http.body-max-size=1GB        # 限制最大 Body 为 1GB
wastnet.http.enable-temp-file=true           # 启用临时文件（默认）
```

### 高并发 API 场景

```properties
wastnet.http.body-memory-threshold=128KB    # 小 Body 内存阈值，快速刷出
wastnet.http.max-uri-length=2048             # 缩短 URI 限制，防止滥用
wastnet.http.header.default.enabled=false    # 关闭默认响应头，节省带宽
```

### 安全加固场景

```properties
wastnet.http.max-single-header-size=4KB     # 收紧单头限制
wastnet.http.max-http-header-size=8KB       # 收紧总头限制
wastnet.http.server-header.expose=false      # 隐藏 Server 信息（默认）
wastnet.http.request-timeout=10000           # 请求超时 10 秒
# WebSocket 碎片化攻击防御
wastnet.http.max-ws-continuations=128        # 收紧 continuation 帧数量
wastnet.http.max-ws-fragment-merge-timeout-ms=15000  # 收紧分片合并超时
```
