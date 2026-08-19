#!/bin/sh
# 统一入口脚本：启动 golershop (商城)、edict-go、multica 与 oih (ceoadmin)
#
# 环境变量：
#   GOLERSHOP_DB_DIR  golershop 数据库目录，默认 /var/lib/CEOadmin/golershop
#   EDICT_DB          edict sqlite 数据库路径，默认 /var/lib/CEOadmin/edict.db
#   EDICT_ADDR        edict 监听地址，默认 :7891
#   EDICT_WEB         edict 前端 dist 目录，默认 /app/edict/edict/frontend/dist
#   HUB_LISTEN        oih 监听地址，默认 0.0.0.0:9800
#   GOLERSHOP_PORT    golershop 端口，默认 8000
#   MULTICA_DISABLED   设为 1 则跳过 multica；默认启用（内置 PostgreSQL）
#   MULTICA_DATABASE_URL  multica postgres(pgvector) 连接串；
#                         留空或指向本机(127.0.0.1/localhost)则用容器内置 PostgreSQL，
#                         指向外部地址则用外部库
#   MULTICA_PORT      multica 后端端口，默认 8080
#   MULTICA_WEB_PORT  multica web 端口，默认 3001
#   MULTICA_JWT_SECRET multica JWT 密钥，默认 change-me-in-production
set -e

# --- multica (agents 管理平台；PostgreSQL 17 + pgvector 已捆绑进镜像，单容器部署) ---
if [ "${MULTICA_DISABLED:-0}" != "1" ]; then
  MULTICA_PORT="${MULTICA_PORT:-8080}"
  MULTICA_WEB_PORT="${MULTICA_WEB_PORT:-3001}"
  MULTICA_JWT_SECRET="${MULTICA_JWT_SECRET:-change-me-in-production}"
  MULTICA_WEB_DIR="${MULTICA_WEB_DIR:-/app/multica/web}"

  # 显式给了非本机数据库连接串时使用外部库；否则用容器内捆绑的 PostgreSQL
  if [ -n "${MULTICA_DATABASE_URL:-}" ] && ! echo "$MULTICA_DATABASE_URL" | grep -qE "@(localhost|127\.0\.0\.1)[:/]"; then
    echo "Using external multica database: ${MULTICA_DATABASE_URL}"
    MULTICA_DB_URL="$MULTICA_DATABASE_URL"
  else
    PGDATA="${PGDATA:-/var/lib/CEOadmin/pgdata}"
    PGLOG="${PGDATA}/postgres.log"
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    if [ ! -s "$PGDATA/PG_VERSION" ]; then
      echo "Initializing bundled PostgreSQL (${PGDATA})..."
      su postgres -c "initdb -D '$PGDATA' -E UTF8 --locale=C.UTF-8" >/dev/null 2>&1 \
        || su postgres -c "initdb -D '$PGDATA' -E UTF8" >/dev/null
    fi
    su postgres -c "pg_ctl -D '$PGDATA' -o '-p 5432 -c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp' -l '$PGLOG' -w start" >/dev/null
    for i in $(seq 1 30); do
      if su postgres -c "pg_isready -h 127.0.0.1 -p 5432 -U postgres" >/dev/null 2>&1; then
        break
      fi
      sleep 1
    done
    if ! su postgres -c "psql -h 127.0.0.1 -p 5432 -tAc \"SELECT 1 FROM pg_roles WHERE rolname='multica'\"" | grep -q 1; then
      su postgres -c "psql -h 127.0.0.1 -p 5432 -c \"CREATE ROLE multica LOGIN PASSWORD 'multica'\"" >/dev/null
    fi
    if ! su postgres -c "psql -h 127.0.0.1 -p 5432 -tAc \"SELECT 1 FROM pg_database WHERE datname='multica'\"" | grep -q 1; then
      su postgres -c "psql -h 127.0.0.1 -p 5432 -c 'CREATE DATABASE multica OWNER multica'" >/dev/null
    fi
    su postgres -c "psql -h 127.0.0.1 -p 5432 -d multica -c 'CREATE EXTENSION IF NOT EXISTS vector'" >/dev/null
    MULTICA_DB_URL="postgres://multica:multica@127.0.0.1:5432/multica?sslmode=disable"
    echo "Bundled PostgreSQL ready: ${MULTICA_DB_URL}"
  fi

  echo "Running multica migrations (retrying until database is ready)..."
  cd /app/multica
  MIGRATED=false
  for i in $(seq 1 60); do
    if DATABASE_URL="$MULTICA_DB_URL" multica-migrate up; then
      MIGRATED=true
      break
    fi
    echo "multica database not ready, retry ${i}/60..."
    sleep 1
  done
  cd /
  if [ "$MIGRATED" != "true" ]; then
    echo "ERROR: multica database at ${MULTICA_DB_URL} not reachable after 60s"
    exit 1
  fi

  echo "Starting multica backend on port ${MULTICA_PORT}..."
  PORT="$MULTICA_PORT" \
    DATABASE_URL="$MULTICA_DATABASE_URL" \
    JWT_SECRET="$MULTICA_JWT_SECRET" \
    multica-server &

  echo "Starting multica web on port ${MULTICA_WEB_PORT}..."
  cd "$MULTICA_WEB_DIR"
  PORT="$MULTICA_WEB_PORT" \
    HOSTNAME=0.0.0.0 \
    REMOTE_API_URL="http://localhost:${MULTICA_PORT}" \
    node apps/web/server.js &
  cd /
fi

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
