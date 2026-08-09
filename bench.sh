#!/usr/bin/env bash
# wastnet vs undertow benchmark helper (Linux/macOS)
#
# Usage:
#   ./bench.sh                    # run all 5 scenarios for wastnet (default impl)
#   ./bench.sh <impl>             # run all 5 scenarios for given impl
#   ./bench.sh all                # run all 5 scenarios for wastnet AND undertow
#   ./bench.sh <impl> <scenario>  # run single scenario (1..5)
#   ./bench.sh <impl> <scenario> <post_size>  # override POST body size (bytes)
#   ./bench.sh gen                # only generate the payload.bin
#
# impl: wastnet (default) | undertow | all
# scenario: 1 wrk-get | 2 h1-get | 3 h1-post | 4 h2-get | 5 h2-post
# post_size: POST body size in bytes for scenarios 3/5 (default 524288 = 512KB),
#            also overridable via the POST_SIZE environment variable.
#
# Requires: java (JDK 9+ for scenarios 4/5), wrk, h2load (nghttp2), mvn (auto-builds the jar if missing)
set -u

CYAN='\033[1;36m'
NC='\033[0m'

DIR="$(cd "$(dirname "$0")" && pwd)"
RESULT_DIR="$DIR/bench-results"
mkdir -p "$RESULT_DIR"
if [ "${REBUILD:-0}" = "1" ]; then
  echo "[bench] REBUILD=1: rebuilding fat-jar..."
  (cd "$DIR" && mvn -pl wastnet-test -am package -DskipTests -q) || { echo "[bench] build failed" >&2; exit 1; }
fi
JAR=$(ls -t "$DIR"/wastnet-test/target/wastnet-test-*.jar 2>/dev/null | grep -v '/original-' | head -n1 || true)
if [ -z "$JAR" ]; then
  echo "[bench] fat-jar not found, building..."
  (cd "$DIR" && mvn -pl wastnet-test -am package -DskipTests -q) || { echo "[bench] build failed" >&2; exit 1; }
  JAR=$(ls -t "$DIR"/wastnet-test/target/wastnet-test-*.jar | grep -v '/original-' | head -n1)
fi
echo "[bench] using jar: $JAR"
ls -l "$JAR"
MAIN=io.github.wycst.wastnet.benchmarks.http.BenchmarkLauncher
SRV_PID=

IMPL=${1:-wastnet}
SCENARIO=${2:-all}
# POST body size for scenarios 3/5, default 512KB, overridable by arg or env.
POST_SIZE=${3:-${POST_SIZE:-524288}}
# Target host for the load client; arg #4 or HOST env overrides. When set to a
# non-local address, the local server is NOT started (assumed already running).
if [ -n "${4:-}" ]; then
  HOST=$4
elif [ -n "${HOST:-}" ]; then
  HOST=$HOST
else
  HOST=localhost
fi
case "$HOST" in
  localhost|127.0.0.1) NEED_START=1 ;;
  *) NEED_START=0 ;;
esac

LOG_EXT=txt

# Per-impl result log path; the target host is embedded so different HOSTs
# don't overwrite each other. IPv6 ':' is sanitized to '_' for filename safety.
log_path() {
  local safe=${HOST//:/_}
  echo "$RESULT_DIR/$IMPL-$safe-$(date +%Y-%m-%d).$LOG_EXT"
}

# Extra JVM args for the benchmark server (e.g. VMARGS="-Xms256m -Xmx256m").
VMARGS=${VMARGS:-}

echo "[bench] impl=$IMPL scenario=$SCENARIO post_size=$POST_SIZE host=$HOST"
if [ "$NEED_START" = "1" ]; then
  echo "[bench] starting local server: yes"
else
  echo "[bench] starting local server: no (remote host, assume already running)"
fi
echo "[bench] jar=$JAR"

gen_error_log() {
  local cur
  if [ -f "$DIR/payload.bin" ]; then
    cur=$(stat -c%s "$DIR/payload.bin" 2>/dev/null || echo 0)
    if [ "$cur" = "$POST_SIZE" ]; then
      return 0
    fi
    echo "[bench] payload.bin size $cur != $POST_SIZE, regenerating ..."
  fi
  echo "[bench] generating ${POST_SIZE} bytes payload.bin (filled with 'a') ..."
  head -c "$POST_SIZE" /dev/zero | tr '\0' 'a' > "$DIR/payload.bin"
}

start_server() {
  local proto=$1 port=$2
  echo "[bench] starting $IMPL proto=$proto port=$port ..."
  # Enable HTTP/1.1 pipelining via JVM flag (must be set before HttpConf class loads).
  # Harmless for wrk (h1) and required for h2load --h1 (h1p) to avoid protocol deadlock.
  java -Xms256m -Xmx256m $VMARGS -Dwastnet.http.pipeline.enabled=true -Dimpl="$IMPL" -Dproto="$proto" -Dport="$port" -cp "$JAR" "$MAIN" >/dev/null 2>&1 &
  SRV_PID=$!
  # wait until port listens (max 15s)
  for i in $(seq 1 15); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      exec 3>&- 3<&-
      echo "[bench] server ready (pid=$SRV_PID)"
      return 0
    fi
    sleep 1
  done
  echo "[bench] server did not start in time" >&2
  kill -9 "$SRV_PID" 2>/dev/null || true
  exit 1
}

stop_server() {
  if [ -n "$SRV_PID" ]; then
    echo "[bench] stopping server (pid=$SRV_PID) ..."
    kill -9 "$SRV_PID" 2>/dev/null || true
    wait "$SRV_PID" 2>/dev/null || true
    SRV_PID=
  fi
}

# h2 over TLS needs ALPN, which is only available on JDK 9+. Refuse early on JDK 8.
require_jdk9() {
  local ver
  ver=$(java -version 2>&1 | head -n1)
  case "$ver" in
    *"1.8"*|*"version \"8"*) echo "[bench] ERROR: scenarios 4/5 (h2 over TLS) need JDK 9+ (ALPN); current: $ver" >&2; exit 1 ;;
  esac
}

# Kill any leftover benchmark server from a previous run by its command-line signature.
kill_stale_servers() {
  pkill -9 -f 'BenchmarkLauncher' 2>/dev/null || true
  sleep 1
}

# Kill any process holding the given port (lsof preferred, fuser fallback).
free_port() {
  local port="$1"
  local pid=""
  pid=$(lsof -ti tcp:"$port" 2>/dev/null)
  if [ -z "$pid" ] && command -v fuser >/dev/null 2>&1; then
    pid=$(fuser "$port"/tcp 2>/dev/null | tr -d ' ')
  fi
  if [ -n "$pid" ]; then
    echo "[bench] freeing port $port (killing pid(s): $pid) ..."
    kill -9 $pid 2>/dev/null || true
    sleep 1
  fi
}

# On exit: stop our server AND free all 4 benchmark ports so a next run is clean.
cleanup() {
  stop_server
  kill_stale_servers
  for p in 8080 8081 8443 8444 8445 8446; do
    free_port "$p"
  done
}
trap cleanup EXIT

if [ "$IMPL" = "gen" ]; then
  gen_error_log
  exit 0
fi

gen_error_log

case "$IMPL" in
  wastnet) H1_PORT=8080; H2_PORT=8443; H2C_PORT=8445 ;;
  undertow) H1_PORT=8081; H2_PORT=8444; H2C_PORT=8446 ;;
  all) ;;
  *) echo "unknown impl: $IMPL" >&2; exit 1 ;;
esac

# Kill any leftover benchmark server from a previous run so a stale process
# cannot block the new one (and cause a hang). Then free ports as a fallback.
kill_stale_servers
for p in 8080 8081 8443 8444 8445 8446; do
  free_port "$p"
done

# Print the command in highlight, run it, and tee output to the per-impl daily log.
run_cmd() {
  local n=$1
  local cmd=$2
  local log="$(log_path)"
  # highlight to terminal only; keep log file free of escape codes
  echo -e "${CYAN}\$ $cmd${NC}"
  echo "\$ $cmd" >> "$log"
  eval "$cmd" 2>&1 | tee -a "$log"
}

# Warm up the server before the measured run so JIT / class loading / HPACK
# dynamic-table seeding don't distort the first seconds of the timed run.
# Output is discarded and never written to the result log.
warmup() {
  local proto=$1 port=$2
  case $proto in
    h1)
      wrk -t4 -c100 -d2s http://$HOST:$port/hello >/dev/null 2>&1 || true
      ;;
    h1p)
      h2load --h1 -n 3000 -c 10 -m 100 http://$HOST:$port/hello >/dev/null 2>&1 || true
      ;;
    h2)
      h2load -n 3000 -c 10 -m 100 https://$HOST:$port/hello >/dev/null 2>&1 || true
      ;;
    h2c)
      h2load -n 3000 -c 10 -m 100 http://$HOST:$port/hello >/dev/null 2>&1 || true
      ;;
  esac
}

run_scenario() {
  local n=$1
  case $n in
    1)
      if [ "$NEED_START" = "1" ]; then start_server h1 "$H1_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h1 "$H1_PORT"; fi
      run_cmd "$n" "wrk -t7 -c200 -d15s --latency http://$HOST:$H1_PORT/hello"
      ;;
    2)
      if [ "$NEED_START" = "1" ]; then start_server h1p "$H1_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h1p "$H1_PORT"; fi
      run_cmd "$n" "h2load --h1 -n 10000 -c 10 -m 100 -D 15 http://$HOST:$H1_PORT/hello"
      ;;
    3)
      if [ "$NEED_START" = "1" ]; then start_server h1p "$H1_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h1p "$H1_PORT"; fi
      run_cmd "$n" "h2load --h1 -n 1000 -c 10 -t 4 -d $DIR/payload.bin -H content-type:text/plain -D 15 http://$HOST:$H1_PORT/hello"
      ;;
    4)
      require_jdk9
      if [ "$NEED_START" = "1" ]; then start_server h2 "$H2_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h2 "$H2_PORT"; fi
      run_cmd "$n" "h2load -n 10000 -c 10 -m 100 -D 15 https://$HOST:$H2_PORT/hello"
      ;;
    5)
      require_jdk9
      if [ "$NEED_START" = "1" ]; then start_server h2 "$H2_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h2 "$H2_PORT"; fi
      run_cmd "$n" "h2load -n 1000 -c 10 -t 4 -d $DIR/payload.bin -H content-type:text/plain -D 15 https://$HOST:$H2_PORT/hello"
      ;;
    6)
      # cleartext HTTP/2 (h2c), prior-knowledge; no ALPN/TLS, works on any JDK.
      # Same get-benchmark params as scenario 4 (h2), only https->http and its own port.
      if [ "$NEED_START" = "1" ]; then start_server h2c "$H2C_PORT"; fi
      if [ "$NEED_START" = "1" ]; then warmup h2c "$H2C_PORT"; fi
      run_cmd "$n" "h2load -n 10000 -c 10 -m 100 -D 15 http://$HOST:$H2C_PORT/hello"
      ;;
    *) echo "unknown scenario: $n" >&2; exit 1 ;;
  esac
}

# Run all 5 scenarios for the given impl (sets IMPL/ports locally).
run_all() {
  IMPL=$1
  case "$IMPL" in
    wastnet) H1_PORT=8080; H2_PORT=8443; H2C_PORT=8445 ;;
    undertow) H1_PORT=8081; H2_PORT=8444; H2C_PORT=8446 ;;
    *) echo "unknown impl: $IMPL" >&2; exit 1 ;;
  esac
  : > "$(log_path)"
  echo "[bench] ===== run at $(date '+%Y-%m-%d %H:%M:%S') (impl=$IMPL) =====" | tee -a "$(log_path)"
  echo "########## IMPL: $IMPL ##########"
  for n in 1 2 3 4 5 6; do
    echo "================ scenario $n ================"
    run_scenario "$n"
  done
}

# Single scenario: clear this impl's daily log before running.
if [ "$SCENARIO" != "all" ]; then
  : > "$(log_path)"
  echo "[bench] ===== run at $(date '+%Y-%m-%d %H:%M:%S') (impl=$IMPL) =====" | tee -a "$(log_path)"
fi

if [ "$SCENARIO" = "all" ]; then
  if [ "$IMPL" = "all" ]; then
    run_all wastnet
    run_all undertow
  else
    run_all "$IMPL"
  fi
else
  run_scenario "$SCENARIO"
fi

echo "[bench] outputs saved to: $RESULT_DIR"
