#!/usr/bin/env bash
# Launcher that runs the tenant-memory (tms) service inside the main container,
# so the memory system is consolidated into one container alongside oih/edict.
#
# The process is fully detached (setsid + nohup, stdin from /dev/null) so it
# survives the shell command that started it. Logs go to tms.log next to this
# script. Override settings via env vars: TMS_PORT / TMS_STORE / TMS_DATA_DIR
# / TMS_LLM_API_KEY / ACC_PRODUCT_CONFIG_V2.
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$DIR/tms"
LOGFILE="$DIR/tms.log"

PORT="${TMS_PORT:-8090}"
STORE="${TMS_STORE:-file}"
DATA_DIR="${TMS_DATA_DIR:-/workspace/edict-shared}"
# Do NOT default LLM_API_KEY to "mock": when unset, config.Load() falls back to
# the platform's unified system LLM interface (ACC_PRODUCT_CONFIG_V2). Only set
# it when TMS_LLM_API_KEY is provided (including an explicit =mock for offline).
LLM_API_KEY="${TMS_LLM_API_KEY:-}"

start() {
  mkdir -p "$DATA_DIR"
  cd "$DIR"
  # 二进制缺失则先编译（build.sh 内含 go 定位与排错提示）。
  if [ ! -x "$BIN" ]; then
    echo "tms 二进制缺失，先执行编译..."
    if ! bash "$DIR/build.sh"; then
      echo "编译失败，未启动 tms。请先修复编译问题。" >&2
      exit 1
    fi
  fi
  echo "starting tms on :$PORT (store=$STORE data=$DATA_DIR)..."
  local tms_env=( PORT="$PORT" STORE="$STORE" DATA_DIR="$DATA_DIR" )
  if [ -n "$LLM_API_KEY" ]; then
    tms_env+=( LLM_API_KEY="$LLM_API_KEY" )
  fi
  if [ -n "${ACC_PRODUCT_CONFIG_V2:-}" ]; then
    tms_env+=( ACC_PRODUCT_CONFIG_V2="$ACC_PRODUCT_CONFIG_V2" )
  fi
  env "${tms_env[@]}" \
    setsid nohup ./"$(basename "$BIN")" >"$LOGFILE" 2>&1 < /dev/null &
  # give it a moment, then probe
  sleep 1.5
  if curl -s --max-time 3 "http://localhost:$PORT/healthz" >/dev/null 2>&1; then
    echo "tms up on :$PORT"
  else
    echo "tms did not respond; check $LOGFILE"
  fi
}

stop() {
  # kill whatever holds the port in this container's network namespace
  fuser -k "${PORT}/tcp" 2>/dev/null
  sleep 1
  pkill -f 'tenant-memory/tms' 2>/dev/null
  pkill -f './tms' 2>/dev/null
  echo "stop signal sent"
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 1; start ;;
  status)
    if curl -s --max-time 3 "http://localhost:$PORT/healthz" >/dev/null 2>&1; then
      echo "tms running on :$PORT"
    else
      echo "tms not responding on :$PORT"
    fi ;;
  *) echo "usage: $0 {start|stop|restart|status}"; exit 1 ;;
esac
