#!/usr/bin/env bash
# Single-container deployment for the CEOadmin Hub (oih) + tenant-memory (tms).
#
# oih auto-launches tms as a sidecar at startup (see internal wiring in main.go),
# so running this script brings up the whole memory system inside ONE container.
# The edict Go backend is already compiled into oih as a builtin app, so no
# separate edict container is needed.
#
# Usage: ./deploy.sh {build|start|stop|restart|status}
set -euo pipefail

cd "$(cd "$(dirname "$0")" && pwd)"
# make `go` available (installed at /usr/local/go/bin)
export PATH="$PATH:/usr/local/go/bin"
[ -f /etc/profile.d/go.sh ] && . /etc/profile.d/go.sh

OIH_LISTEN="${OIH_LISTEN:-:9800}"
OIH_DB="${OIH_DB:-/workspace/ceoadmin.db}"
TMS_PORT="${TMS_PORT:-8090}"
TMS_DATA_DIR="${TMS_DATA_DIR:-/workspace/edict-shared}"
# IMPORTANT: do NOT default TMS_LLM_API_KEY to "mock". When unset, the tms
# sidecar falls back to the platform's unified system LLM interface
# (ACC_PRODUCT_CONFIG_V2), so the application uses the same system LLM as the
# Hub's tenants. Set TMS_LLM_API_KEY explicitly (or =mock) to override / opt
# into mock offline mode.

build() {
  echo "==> building oih"
  go build -o oih .
  echo "==> building tms"
  (cd tenant-memory && go build -o tms .)
  # place tms next to oih so the default TMS_BIN resolution (exe dir) finds it
  cp tenant-memory/tms ./tms
  echo "==> build done"
}

start() {
  mkdir -p "$TMS_DATA_DIR"
  echo "==> starting oih on $OIH_LISTEN (tms sidecar on $TMS_PORT)"
  # Only forward TMS_LLM_API_KEY when explicitly set; leaving it unset lets the
  # tms sidecar use the platform's unified system LLM interface.
  local tms_env=( TMS_PORT="$TMS_PORT" TMS_DATA_DIR="$TMS_DATA_DIR" )
  if [ -n "${TMS_LLM_API_KEY:-}" ]; then
    tms_env+=( TMS_LLM_API_KEY="$TMS_LLM_API_KEY" )
  fi
  env "${tms_env[@]}" \
    setsid ./oih -db "$OIH_DB" -listen "$OIH_LISTEN" >oih.log 2>&1 < /dev/null &
  sleep 3
  local oihp="${OIH_LISTEN#:}"
  curl -s --max-time 3 "http://localhost:${oihp}/healthz" >/dev/null 2>&1 && echo "oih up on $OIH_LISTEN" || echo "oih not responding (see oih.log)"
  curl -s --max-time 3 "http://localhost:$TMS_PORT/healthz" >/dev/null 2>&1 && echo "tms sidecar up on :$TMS_PORT" || echo "tms not responding (see tms-from-oih.log)"
}

stop() {
  local oihp="${OIH_LISTEN#:}"
  fuser -k "${oihp}/tcp" 2>/dev/null
  fuser -k "${TMS_PORT}/tcp" 2>/dev/null
  pkill -f './oih' 2>/dev/null
  pkill -f './tms' 2>/dev/null
  echo "stop signal sent"
}

case "${1:-start}" in
  build)    build ;;
  start)    build; start ;;
  stop)     stop ;;
  restart)  stop; sleep 1; start ;;
  status)
    local oihp="${OIH_LISTEN#:}"
    curl -s --max-time 3 "http://localhost:${oihp}/healthz" >/dev/null 2>&1 && echo "oih running on $OIH_LISTEN" || echo "oih down"
    curl -s --max-time 3 "http://localhost:$TMS_PORT/healthz" >/dev/null 2>&1 && echo "tms running on :$TMS_PORT" || echo "tms down"
    ;;
  *) echo "usage: $0 {build|start|stop|restart|status}"; exit 1 ;;
esac
