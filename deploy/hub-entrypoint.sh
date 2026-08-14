#!/bin/sh
# 统一入口脚本：启动 golershop (商城)、edict-go 与 oih (ceoadmin)
#
# 环境变量：
#   GOLERSHOP_DB_DIR  golershop 数据库目录，默认 /var/lib/CEOadmin/golershop
#   EDICT_DB          edict sqlite 数据库路径，默认 /var/lib/CEOadmin/edict.db
#   EDICT_ADDR        edict 监听地址，默认 :7891
#   EDICT_WEB         edict 前端 dist 目录，默认 /app/edict/edict/frontend/dist
#   HUB_LISTEN        oih 监听地址，默认 0.0.0.0:9800
#   GOLERSHOP_PORT    golershop 端口，默认 8000
set -e

# --- edict-go (后台运行) ---
EDICT_DB="${EDICT_DB:-/var/lib/CEOadmin/edict.db}"
EDICT_ADDR="${EDICT_ADDR:-:7891}"
EDICT_WEB="${EDICT_WEB:-/app/edict/edict/frontend/dist}"

mkdir -p "$(dirname "$EDICT_DB")"
edict-go -db "$EDICT_DB" serve -addr "$EDICT_ADDR" -web "$EDICT_WEB" &

# --- golershop ---
GOLERSHOP_DB_DIR="${GOLERSHOP_DB_DIR:-/var/lib/CEOadmin/golershop}"
GOLERSHOP_PORT="${GOLERSHOP_PORT:-8000}"

mkdir -p "$GOLERSHOP_DB_DIR"
cd /app/golershop

# 启动 golershop
echo "Starting golershop on port ${GOLERSHOP_PORT}..."
./golershop &
GOLERSHOP_PID=$!

# 等待 golershop 就绪（最多等 10 秒）
GOLERSHOP_READY=false
for i in $(seq 1 20); do
  if ! kill -0 "$GOLERSHOP_PID" 2>/dev/null; then
    echo "ERROR: golershop process exited unexpectedly"
    exit 1
  fi
  if curl -s -o /dev/null -w "%{http_code}" "http://localhost:${GOLERSHOP_PORT}/" 2>/dev/null | grep -q "200\|301\|404"; then
    GOLERSHOP_READY=true
    break
  fi
  sleep 0.5
done

if [ "$GOLERSHOP_READY" = "true" ]; then
  echo "golershop started successfully (PID: $GOLERSHOP_PID)"
else
  echo "WARNING: golershop health check did not pass, but continuing..."
fi

# --- oih ---
HUB_LISTEN="${HUB_LISTEN:-0.0.0.0:9800}"

if [ $# -eq 0 ]; then
  set -- -listen "${HUB_LISTEN}"
fi
exec oih "$@"
