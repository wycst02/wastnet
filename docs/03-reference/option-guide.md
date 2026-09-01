# 配置 Option 使用指南

> **为什么需要 Option？**
>
> wastnet 早期版本的所有配置都集中在 `HttpConf` / `SocketConf` 两个**全局静态持有者**里，
> 由启动期的系统属性（如 `wastnet.http.*`、`wastnet.socket.*`）一次性初始化。
> 这在一台进程只跑一份服务、所有连接共用一套参数时没有问题。
>
> 但随着框架演进，我们需要支持**一个进程内运行多个服务实例、且每个实例拥有独立配置**，
> 不同的虚拟主机、不同的超时与限流策略，互不干扰。
>
> 为此，wastnet 1.0.2 引入了 **Option 机制**——把"配置项"抽象成强类型、带默认值、带范围
> 校验的 `Option<T>` 键，每个 `NioConfig`（服务实例持有的配置对象）可以独立覆盖，而不再污染全局静态
> 持有者。本文档描述该能力的使用方式、可隔离的配置清单，以及它与系统属性的关系。

---

## 1. Option 是什么

`Option<T>` 是一个强类型、全局唯一（按 `index` 序号）的配置键，声明在注册表类
`HttpOptions`（HTTP 域）与 `SocketOptions`（Socket 域）中，形式为 `public static final` 常量。

每个 Option 携带四个要素：

| 字段 | 含义 |
|------|------|
| `index` | 全局唯一序号，作为 Map 的 key（不同注册表不会冲突） |
| `value` | **默认值**。当某个实例未覆盖该 Option 时，`option(Option)` 返回它 |
| `type` | 运行时类型（用于安全转型） |
| `normalizer` | 范围 / 枚举校验函数，每次 `option(option, value)` 写入时都会应用（无约束的用恒等函数） |

默认值本身取自 `HttpConf` / `SocketConf` 的对应字段，因此 Option 体系与系统属性定义的全局默认
**始终一致**；Option 只是在其上叠加了"实例级覆盖"这一层。

---

## 2. 可隔离配置清单

以下 Option 均支持 `option(opt, val)` 实例级覆盖。默认值取自 `HttpConf` / `SocketConf`，
取值范围遵循各自 normalizer。

### 2.1 HTTP / 1.1（`HttpOptions`）

| Option | 类型 | 默认值 | 范围 / 归一化 |
|--------|------|--------|--------------|
| `MAX_SINGLE_HEADER_SIZE` | Integer | 来自 `HttpConf` | `1 .. Integer.MAX_VALUE` |
| `MAX_HTTP_HEADER_SIZE` | Integer | 来自 `HttpConf` | `1 .. Integer.MAX_VALUE` |
| `MAX_URI_LENGTH` | Integer | 来自 `HttpConf` | `1 .. Integer.MAX_VALUE` |
| `BODY_MEMORY_THRESHOLD` | Integer | 来自 `HttpConf` | `1 .. Integer.MAX_VALUE` |
| `MAX_BODY_IN_MEMORY` | Integer | 来自 `HttpConf` | `1 .. Integer.MAX_VALUE` |
| `BODY_MAX_SIZE` | Long | 来自 `HttpConf` | `<=0 => 无限制`；否则 `>= BODY_MEMORY_THRESHOLD` |
| `ENABLE_TEMP_FILE` | Boolean | 来自 `HttpConf` | — |
| `TEMP_FILE_DIR` | String | 来自 `HttpConf` | — |
| `TEMP_FILE_PREFIX` | String | 来自 `HttpConf` | — |
| `GZIP` | Boolean | 来自 `HttpConf` | — |
| `GZIP_MIN_SIZE` | Integer | 来自 `HttpConf` | `0 .. Integer.MAX_VALUE` |
| `WRITE_DEFAULT_HEADERS` | Boolean | 来自 `HttpConf` | — |
| `EXPOSE_SERVER_HEADER` | Boolean | 来自 `HttpConf` | — |
| `PIPELINE_ENABLED` | Boolean | 来自 `HttpConf` | — |
| `REQUEST_TIMEOUT_MS` | Long | 来自 `HttpConf` | `<=0 => 无限制` |
| `PRESERVE_HEADER_ORDER` | Boolean | 来自 `HttpConf` | — |
| `SSE_TIMEOUT_MS` | Long | 来自 `HttpConf` | `<=0 => 无限制` |

### 2.2 HTTP / 2（`HttpOptions`）

| Option | 类型 | 默认值 | 范围 / 归一化 |
|--------|------|--------|--------------|
| `HTTP2_INITIAL_SEND_WINDOW_SIZE` | Integer | 来自 `HttpConf` | `0xFFFF .. 0xFFFFFF` |
| `HTTP2_MAX_CONCURRENT_STREAMS` | Integer | 来自 `HttpConf` | `>= 100` |
| `HTTP2_CLIENT_RST_WINDOW_SECONDS` | Integer | 来自 `HttpConf` | `>= 1` |
| `HTTP2_CLIENT_RST_MAX_COUNT` | Integer | 来自 `HttpConf` | `>= 1` |
| `HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS` | Integer | 来自 `HttpConf` | `>= 1000` |
| `HTTP2_BODY_READ_TIMEOUT_MS` | Integer | 来自 `HttpConf` | `>= 1000` |
| `HTTP2_STREAM_EARLY` | Boolean | 来自 `HttpConf` | — |

### 2.3 Socket（`SocketOptions`）

| Option | 类型 | 默认值 | 范围 / 归一化 |
|--------|------|--------|--------------|
| `ENABLE_VIRTUAL_THREAD` | Boolean | 来自 `SocketConf` | — |
| `SELECT_TIMEOUT_MS` | Long | 来自 `SocketConf` | `min(v, 100)` |
| `SELECT_EMPTY_COUNT` | Integer | 来自 `SocketConf` | — |
| `MAX_CONCURRENT` | Integer | 来自 `SocketConf` | `-1 => 无界`；`<=0 => CPU*100`；否则 `>= CPU` |
| `LOAD_BALANCE_TYPE` | String | 来自 `SocketConf` | `LEAST_CONN` 或 `ROUND_ROBIN` |
| `SSL_HANDSHAKE_TIMEOUT_MS` | Long | 来自 `SocketConf` | — |
| `READ_TIMEOUT_MS` | Long | 来自 `SocketConf` | `>= 0` |
| `WRITE_TIMEOUT_MS` | Long | 来自 `SocketConf` | `>= 0` |
| `GRACEFUL_SHUTDOWN_TIMEOUT_MS` | Long | 来自 `SocketConf` | `>= 0` |

---

## 3. 基本用法

### 3.1 读取（运行时，已隔离）

```java
NioConfig config = ...;
ChannelContext ctx = ...;

// 两种入口等价：NioConfig 与 ChannelContext 都实现了 option(Option<T>)
long sseTimeout = config.option(HttpOptions.SSE_TIMEOUT_MS);   // 无覆盖则返回默认值
int readTimeout = ctx.option(SocketOptions.READ_TIMEOUT_MS);
```

### 3.2 写入（实例级覆盖，流式）

```java
NioConfig apiServer = new NioConfig();
apiServer
    .option(HttpOptions.MAX_BODY_IN_MEMORY, 32 * 1024 * 1024)   // 上传服务放宽内存阈值
    .option(HttpOptions.BODY_MAX_SIZE, 2L * 1024 * 1024 * 1024) // 单请求 body 上限 2GB
    .option(HttpOptions.GZIP, true)
    .option(SocketOptions.READ_TIMEOUT_MS, 30_000L);            // 该实例读超时 30s
```

写入经 `normalizer` 归一化后存入实例的 `Map<Option<?>, Object>`，**仅对该实例生效**，
不影响 `HttpConf` 全局默认，也不影响其它 `NioConfig` 实例。

### 3.3 范围校验

所有 Option 在写入时都会执行 `normalizer`，与全局默认值的校验口径完全一致。例如：

```java
apiServer.option(HttpOptions.MAX_SINGLE_HEADER_SIZE, 0);     // normalizer 拉到 Math.max(1, v) => 1
apiServer.option(HttpOptions.SSE_TIMEOUT_MS, 0L);            // <=0 视为无限制 => Long.MAX_VALUE
apiServer.option(SocketOptions.LOAD_BALANCE_TYPE, "FOO");    // 非法枚举 => "ROUND_ROBIN"
apiServer.option(SocketOptions.MAX_CONCURRENT, -1);          // -1 = 无界缓存线程池（保留语义）
```

> 非法值不会抛异常，而是被 normalizer 收敛到合法边界，确保运行安全。

---

## 4. 优先级关系

**实例级 Option 优先于全局默认值。**

- **未调用 `option(opt, val)`**：读到 `opt.value`，即全局默认（含 JVM 系统属性在启动期注入的值）。
- **调用后**：读到该实例的覆盖值，仅对当前 `NioConfig` 生效，不影响其它实例或全局默认。

> 全局默认值来自 `HttpOptions` / `SocketOptions` 的 `Option.value`（可由系统属性在启动期注入）。

---

## 5. 多服务实例示例

一个进程内跑两份服务、各自独立配置，正是 Option 机制的目标场景：

```java
// 实例 A 的配置：面向公网的上传服务，放宽 body 限制、启用临时文件
NioConfig uploadConfig = new NioConfig();
uploadConfig
    .option(HttpOptions.BODY_MAX_SIZE, 5L * 1024 * 1024 * 1024)
    .option(HttpOptions.MAX_BODY_IN_MEMORY, 64 * 1024 * 1024)
    .option(HttpOptions.ENABLE_TEMP_FILE, true)
    .option(SocketOptions.READ_TIMEOUT_MS, 120_000L);

// 实例 B 的配置：内网 API 服务，紧凑超时、禁用临时文件
NioConfig apiConfig = new NioConfig();
apiConfig
    .option(HttpOptions.BODY_MAX_SIZE, 16L * 1024 * 1024)
    .option(HttpOptions.ENABLE_TEMP_FILE, false)
    .option(HttpOptions.GZIP, true)
    .option(SocketOptions.READ_TIMEOUT_MS, 10_000L)
    .option(SocketOptions.MAX_CONCURRENT, 256);

// 两份 server 各自绑定不同端口，配置互不干扰
new HTTPServer(8080, uploadConfig).start();
new HTTPServer(9090, apiConfig).start();
```

未来框架会在更高层（server / virtual-host 抽象）复用这套 Option 体系，使每个
`listen` / `server block` 都能拥有独立的 `Map<Option<?>, Object>` 覆盖层。

---

## 6. 兼容性说明

- Option 是 **运行期覆盖层**，不替代系统属性：系统属性仍负责定义全局默认，Option 负责实例微调。
- 删除 / 新增 Option 不会破坏系统属性文档：`http-conf-reference.md` / `socket-conf-reference.md`
  描述的 `wastnet.*` 系统属性入口保持不变。
- `@since 1.0.2`
