# wastnet 性能压测指南 (Benchmark)

本指南帮助你在 **克隆本项目后**，直接跑通 wastnet 与 Undertow 的 HTTP/1.1、HTTP/2 吞吐对比压测。

所有 server 暴露同一个最小端点：返回 `hello world` 的 `text/plain` 响应，排除业务干扰。

> **JDK 版本与场景的关系**：**推荐直接用 JDK 9+ 运行全部 6 个场景**。若运行环境只有 JDK 8，则只能跑场景 1/2/3/6（明文 HTTP/1.1 与 h2c 明文 HTTP/2）——因为 **HTTP/2 over TLS（场景 4/5）依赖 ALPN，JDK 8 原生不支持**。
> Undertow 对比使用 **2.2.39.Final**（最后一个支持 Java 8 的分支），其 h1 场景同样可在 JDK 8 下对比；h2 场景也需 JDK 9+。

---

## 1. 环境准备

> **压测机必须使用 Linux 或 macOS。**
> `wrk` 与 `h2load`（nghttp2）在 Windows 上不支持运行，因此以下所有压测步骤均在 Linux/macOS 终端执行。
> 源码本身跨平台，Windows 仅用于开发/构建，不用于跑压测。

| 工具 | 说明 | 检查命令 |
|------|------|----------|
| JDK 9+（推荐） | 可跑全部 6 个场景；若只能用 JDK 8，则仅能跑场景 1/2/3/6（h2 over TLS 需 ALPN，场景 4/5 不可用，但 h2c 明文无此限制） | `java -version` |
| Maven 3.x | 构建工具 | `mvn -v` |
| wrk | HTTP/1.1 压测客户端（Linux/macOS） | `wrk --version` |
| h2load | nghttp2 的 HTTP/2 压测客户端（Linux/macOS） | `h2load --version` |

`wrk` / `h2load` 不在本仓库内，需自行安装并加入 `PATH`：
- Debian/Ubuntu: `apt install wrk nghttp2`（h2load 随 nghttp2 包提供）
- CentOS/RHEL: `yum install -y nghttp2`（h2load 随 nghttp2 包提供；`wrk` 默认源无此包，需从源码编译，见下方说明）
- macOS: `brew install wrk nghttp2`

wrk 源码编译（CentOS/RHEL 等无包可用时通用）：`git clone https://github.com/wg/wrk.git && cd wrk && make && sudo cp wrk /usr/local/bin/`

---

## 2. 克隆与构建

```bash
git clone <本仓库地址> wastnet
cd wastnet
mvn -pl wastnet-test -am package -DskipTests
```

构建产物：`wastnet-test/target/wastnet-test-<version>.jar`

---

## 3. 一键压测（推荐，Linux/macOS）

仓库根目录提供 `bench.sh`，自动完成：
定位 fat-jar（缺失则自动打包）→ 生成 POST 数据 `payload.bin`（默认 512KB，可配置）→ 按场景 **预热 → 启动 server → 跑压测 → 关停 server**。

```bash
# 跑全部 6 个场景（默认 wastnet）
./bench.sh

# 指定实现（对比 undertow）
./bench.sh undertow

# 一次性跑 wastnet + undertow 各 6 个场景（结果分目录落盘，便于对比）
./bench.sh all

# 只跑单个场景（1 wrk-get | 2 h1-get | 3 h1-post | 4 h2-get | 5 h2-post | 6 h2c-post）
./bench.sh wastnet 2

# 指定 POST body 大小（字节），场景 3/5 使用；impl / scenario / size 三个位置参数
./bench.sh all all 1024          # 全部场景，POST body 1024 字节
./bench.sh wastnet 5 1048576     # 场景5，POST body 1MB

# 或用环境变量指定大小（覆盖默认 512KB）
POST_SIZE=1048576 ./bench.sh all

# 通过 VMARGS 透传 JVM 参数给压测 server
VMARGS="-Xms256m -Xmx256m" ./bench.sh all

# 只生成 payload.bin
./bench.sh gen

# 自定义压测目标 IP（第 4 个位置参数，或 HOST 环境变量）
# 一旦指定非 localhost 的地址，脚本不会在本机启动 server（假定目标已独立运行）
./bench.sh undertow all 524288 192.168.1.10
HOST=192.168.1.10 ./bench.sh wastnet 2

# 不传 HOST 时默认 localhost，照旧在本机启动 server
./bench.sh wastnet all
```

脚本依赖 `java`、`wrk`、`h2load`、`mvn`（首次自动打包）。场景 4/5 为 h2 over TLS，直接使用证书（无需 `-k`），**但运行脚本的 `java` 必须是 JDK 9+**（JDK 8 原生不支持 ALPN，跑 4/5 会失败）。

每个场景执行时，完整压测命令会以**高亮**打印在终端，同时完整输出**按实现与压测目标 IP 分别落盘到按天命名的日志文件**：`bench-results/wastnet-<host>-YYYY-MM-DD.txt` 与 `bench-results/undertow-<host>-YYYY-MM-DD.txt`（`<host>` 为 `HOST` 参数值，IPv6 的 `:` 会被替换为 `_`；默认 `localhost`）。`./bench.sh all` 时两者各自生成；每次运行会覆盖当天该实现+该 IP 的文件，文件内以 `scenario` 分隔行区分，便于事后查看、上传与对比。

> **预热**：每个场景在正式压测前会先跑一轮不打分的预热（h1 用 `wrk -d2s`；h1p/h2 用 `h2load` 小批量 `-n`，不加 `-D`），用于 JIT 预热与 HPACK 动态表建表，预热输出不计入日志。正式压测时长固定 15 秒（wrk `-d15s` / h2load `-D 15`），保证各协议可比。
> **远程模式**：指定非 `localhost` 的 `HOST` 时，脚本仅作为压测客户端，不启动/不预热本机 server，也不会做端口探测；此时需确保目标主机已在对应端口（wastnet 8080/8443，undertow 8081/8444）独立运行服务并放行防火墙。

### Windows 批量启动 server（bench-start.bat）

Windows 上 `wrk`/`h2load` 无法运行压测，但可以用 `bench-start.bat` 在本机一键拉起 4 个 server（便于从 Linux/macOS 压测机用 `HOST=<windows-ip>` 远程打过来）：

```bat
REM 在项目根目录双击运行，4 个 server 各开一个窗口
bench-start.bat
```

脚本会自动定位 fat-jar（缺失则 `mvn package` 构建），并用 4 个独立窗口启动 wastnet(8080/8443)、undertow(8081/8444)，关闭窗口即停止对应服务。远程压测时从 Linux/macOS 执行：`./bench.sh all all 524288 <windows-ip>`。

---

## 4. 手动压测（对照命令）

如需手动分步起 server + 跑客户端，步骤如下。

### 4.1 启动 server（各开一个终端）

> 必须用 `-cp <jar> <主类>` 显式指定主类，**不要** `java -jar`（jar 内 mainClass 是 demo，非压测程序）。
> 端口约定：**wastnet h1=8080 / h2=8443**；**undertow h1=8081 / h2=8444**。

```bash
JAR=wastnet-test/target/wastnet-test-*.jar
MAIN=io.github.wycst.wastnet.benchmarks.http.BenchmarkLauncher

# 场景1/2/3 明文 HTTP/1.1（wastnet 端口 8080）
# pipeline 统一开启（入口加 -D，wrk 也无害；h2load --h1 必须，否则协议死锁卡住）
java -Dwastnet.http.pipeline.enabled=true -Dimpl=wastnet -Dproto=h1  -Dport=8080 -cp $JAR $MAIN   # 场景1 (wrk)
java -Dwastnet.http.pipeline.enabled=true -Dimpl=wastnet -Dproto=h1p -Dport=8080 -cp $JAR $MAIN   # 场景2/3 (h2load --h1)

# 场景4/5 HTTP/2 over TLS（wastnet 端口 8443）
java -Dwastnet.http.pipeline.enabled=true -Dimpl=wastnet -Dproto=h2 -Dport=8443 -cp $JAR $MAIN    # 场景4/5

# 场景6 明文 HTTP/2（h2c，wastnet 端口 8443）
java -Dwastnet.http.pipeline.enabled=true -Dimpl=wastnet -Dproto=h2c -Dport=8443 -cp $JAR $MAIN   # 场景6

# 对照 undertow（端口 8081 / 8444）
java -Dimpl=undertow -Dproto=h1  -Dport=8081 -cp $JAR $MAIN
java -Dimpl=undertow -Dproto=h1p -Dport=8081 -cp $JAR $MAIN
java -Dimpl=undertow -Dproto=h2  -Dport=8444 -cp $JAR $MAIN
java -Dimpl=undertow -Dproto=h2c -Dport=8444 -cp $JAR $MAIN
```

### 4.2 生成 POST 数据（场景 3 / 5）

默认 body 大小为 **512KB（`524288` 字节）**，可由 `bench.sh` 的 `<post_size>` 参数或 `POST_SIZE` 环境变量覆盖（见第 3 节）。手动生成时按所需大小替换 `head -c` 的字节数即可：

```bash
# 默认 512KB
head -c 524288 /dev/zero | tr '\0' 'a' > payload.bin

# 指定 1MB
head -c 1048576 /dev/zero | tr '\0' 'a' > payload.bin
```

### 4.3 压测命令

```bash
# 场景1：wrk 基础 GET（h1）
wrk -t7 -c200 -d15s --latency "http://localhost:8080/hello"

# 场景2：h2load --h1 GET（须 server 以 proto=h1p 启动，URL 用 http://）
h2load --h1 -n 10000 -c 10 -m 100 -D 15 http://localhost:8080/hello

# 场景3：h2load --h1 POST 512KB（proto=h1p，http://）
h2load --h1 -n 1000 -c 10 -t 4 -d payload.bin -H content-type:text/plain -D 15 http://localhost:8080/hello

# 场景4：h2load GET over TLS（server 以 proto=h2 启动，端口 8443）
h2load -n 10000 -c 10 -m 100 -D 15 https://localhost:8443/hello

# 场景5：h2load POST 512KB over TLS（proto=h2，端口 8443）
h2load -n 1000 -c 10 -t 4 -d payload.bin -H content-type:text/plain -D 15 https://localhost:8443/hello

# 场景6：h2load POST 512KB 明文 h2c（server 以 proto=h2c 启动，URL 用 http://，端口 8443）
h2load -n 1000 -c 10 -t 4 -d payload.bin -H content-type:text/plain -D 15 http://localhost:8443/hello
```

> 上述命令中的 `localhost` 可替换为任意目标 IP（如远程 server 地址）。`bench.sh` 已内置该能力（见第 3 节 `HOST` 参数），无需手动改命令。

---

## 5. 协议模式说明

通过 `-Dproto=<mode>` 切换，对应 6 个场景：

| proto | 含义 | 场景 | TLS |
|-------|------|------|-----|
| `h1`  | 明文 HTTP/1.1 | 1 (wrk) | 否 |
| `h1p` | 明文 HTTP/1.1（h2load 走 pipeline） | 2 / 3 (h2load `--h1`) | 否 |
| `h2`  | HTTP/2 over TLS（浏览器支持的 h2） | 4 / 5 | 是 |
| `h2c` | 明文 HTTP/2（prior-knowledge，无 ALPN/Upgrade） | 6 | 否 |

- **pipeline**：wastnet 默认关闭 HTTP/1.1 pipelining，`NioConfig.testMode()` 开启 pipelining。
- **证书**：`h2` 模式证书已打包在 jar 内 `cert/cert.pem`、`cert/server.pem`，启动自动从 classpath 加载，无需额外文件。
- **h2c**：明文 HTTP/2，服务端在明文连接上检测 `h2c` 的 prior-knowledge preface 自动升级，无需证书，**任何 JDK 版本均可运行**（场景 6 不要求 JDK 9+）。

### JDK 版本与场景对应

| 运行 JDK | 可测场景 | 说明 |
|----------|----------|------|
| **JDK 9+（推荐）** | 1 / 2 / 3 / 4（h2 GET）/ 5（h2 POST）/ 6（h2c POST） | 全部场景均可；h2 over TLS 依赖 ALPN，仅 JDK 9+ 支持 |
| **仅能用 JDK 8 时** | 1（wrk GET）/ 2（h1 GET）/ 3（h1 POST）/ 6（h2c POST） | 明文 HTTP/1.1 与 h2c 明文无 ALPN 限制，场景 4/5（h2 over TLS）不可用 |

> 用 JDK 8 跑场景 4/5 会失败（ALPN 不可用）。`bench.sh` 在启动场景 4/5 前会检测运行 JDK，
> 若低于 9 会直接报错退出并提示改用 JDK 9+。

---

## 6. 记录字段（贴回时请提供）

1. 环境：CPU/核数、内存、OS、JDK 版本、wrk/h2load 版本
2. 参数：线程 `-t`、连接 `-c`、时长/请求 `-d/-n`、H2 的 `-m`
3. 结果：Requests/sec、平均延迟、Transfer/sec、错误数
4. 六个场景结果（wrk-h1 / h1p-GET / h1p-POST / h2-GET / h2-POST / h2c-POST）

> 提示：先用场景 1 的 wrk 自测连通性，再跑 h2load。
> 远程压测注意放行端口，建议本机或同网段以减少网络抖动。
