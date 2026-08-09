# Socket 配置参考手册

`SocketConf` 是 wastnet 的 Socket 层全局配置控制类，所有配置项通过 `wastnet-socket.properties` 文件、系统属性或环境变量设置。适用于 TCP 服务器和 HTTP 服务器的底层行为控制。

**默认场景下无需任何配置即可运行。** 所有配置项均有缺省值，默认值基于常见场景的最佳实践设置（如轮询负载均衡、5 秒 SSL 握手超时等）。除非有特定的生产环境安全加固或性能调优需求，通常不需要修改默认值。

---

## 配置文件

### 配置文件位置

`wastnet-socket.properties` 的加载规则与 `wastnet-http.properties` 相同（5级优先级）：

| 优先级 | 位置 | 说明 |
|--------|------|------|
| 1 (最低) | `{classpath}/wastnet-socket.properties` | 源代码根目录 |
| 2 | `{classpath}/config/wastnet-socket.properties` | 源代码根目录 config 目录 |
| 3 | `{JAR 同级}/wastnet-socket.properties` | JAR 文件同目录 |
| 4 | `{JAR 同级}/config/wastnet-socket.properties` | JAR 文件同目录 config 子目录 |
| 5 (最高) | `{JAR 父级}/config/wastnet-socket.properties` | JAR 文件所在目录的父级下的 config 目录 |

### 属性值优先级

```
系统属性 (System.getProperty) > 环境变量 > 配置文件 (wastnet-socket.properties)
```

---

## 配置项完整列表

### 线程与并发

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.max-concurrent` | Runner 线程池最大线程数。`-1`=无界，小于CPU核数自动钳制到核数，其他非法值回退默认 | `CPU核数 × 100` |
| `wastnet.socket.default-sync-runner` | 是否默认使用同步 Runner（不走线程池，测试场景使用） | `false` |
| `wastnet.socket.virtual-thread.enabled` | 是否启用虚拟线程（仅 JDK 21+ 有效） | `false` |

### Selector 调度

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.select-timeout-ms` | Selector 轮询超时（毫秒）。仅钳制上限 100ms（下限不强制，配置小于 100 的值会被原样接受） | `100` |
| `wastnet.socket.select-empty-count` | Selector 空轮询次数阈值（防 NIO 空轮询 bug） | `1024` |

### 负载均衡

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.load-balance-strategy` | 新连接分配到 Worker 的策略。可选值（区分大小写）：`ROUND_ROBIN`（轮询）、`LEAST_CONN`（最少连接） | `ROUND_ROBIN` |

### SSL 配置

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.ssl.handshake-timeout-ms` | SSL 握手超时时间（毫秒）。`0` 表示无超时 | `5000` (5秒) |

### 读写超时

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.read-timeout-ms` | Channel 读操作超时（毫秒）。`0` 表示无限制 | `10000` (10秒) |
| `wastnet.socket.write-timeout-ms` | Channel 写操作超时（毫秒）。`0` 表示无限制 | `30000` (30秒) |

### 优雅停机

| 配置键 | 说明 | 默认值 |
|-------|------|--------|
| `wastnet.socket.graceful-shutdown-timeout-ms` | 优雅停机等待时间（毫秒）。`shutdownGraceful()` 在此时间内等待进行中的请求完成，超时后强制关闭剩余连接 | `10000` (10秒) |

---

## 配置文件示例

```properties
# ==================== 线程与并发 ====================
wastnet.socket.max-concurrent=3200
wastnet.socket.virtual-thread.enabled=false

# ==================== Selector 调度 ====================
wastnet.socket.select-timeout-ms=100
wastnet.socket.select-empty-count=1024

# ==================== 负载均衡 ====================
wastnet.socket.load-balance-strategy=ROUND_ROBIN

# ==================== SSL ====================
wastnet.socket.ssl.handshake-timeout-ms=5000

# ==================== 读写超时 ====================
wastnet.socket.read-timeout-ms=10000
wastnet.socket.write-timeout-ms=30000

# ==================== 优雅停机 ====================
wastnet.socket.graceful-shutdown-timeout-ms=10000
```

---

## 运行时 API

```java
// 获取 Socket 配置属性值
String value = SocketConf.getProperty("wastnet.socket.max-concurrent");

// 检测当前负载均衡策略是否为 LEAST_CONN
boolean useLeastConn = SocketConf.useLoadBalanceLeastConnections();

// Windows 平台检测（内部使用，修复 Selector 空轮询 bug）
boolean isWindows = SocketConf.WINDOWS_PLATFORM;
```

---

## 场景调优建议

### 高并发 API 服务

```properties
wastnet.socket.max-concurrent=3200         # 增大线程池容量
wastnet.socket.load-balance-strategy=LEAST_CONN  # 最少连接分配，负载更均衡
wastnet.socket.select-timeout-ms=50        # 降低轮询延迟
```

### SSL 密集场景

```properties
wastnet.socket.ssl.handshake-timeout-ms=10000    # SSL 握手超时放宽到 10s
wastnet.socket.write-timeout-ms=60000            # 写超时放宽到 60s
```

### 长连接推送场景

```properties
wastnet.socket.read-timeout-ms=0                 # 长连接推送场景下的有意放宽：服务端主要推送、不读客户端，关闭读超时避免被误断（默认 10s，普通场景无需此设置）
wastnet.socket.max-concurrent=1600               # 根据连接数调整线程池
```
