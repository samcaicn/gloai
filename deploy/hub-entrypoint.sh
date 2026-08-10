#!/bin/sh
# 同容器启动 Hub(oih) 与 edict-go（方案 C：edict 不作为独立容器，直连端口）。
#
# - edict-go 以后台进程运行在 ${EDICT_ADDR}（默认 :7891），直接对外提供服务；
#   应用市场「应用主页」指向该端口即可，无需 Hub 反代 / APP_PROXY / -base 基路径。
# - oih 以前台进程运行在 ${HUB_LISTEN}（默认 0.0.0.0:9800），作为容器主进程。
#
# 环境变量（均可覆盖）：
#   EDICT_DB      edict sqlite 数据库路径，默认 /data/edict.db
#   EDICT_ADDR    edict 监听地址，默认 :7891
#   EDICT_WEB     edict 托管的 React 前端 dist 目录，默认 /app/edict/edict/frontend/dist
#   HUB_LISTEN    oih 监听地址，默认 0.0.0.0:9800
set -e

EDICT_DB="${EDICT_DB:-/var/lib/CEOadmin/edict.db}"
EDICT_ADDR="${EDICT_ADDR:-:7891}"
EDICT_WEB="${EDICT_WEB:-/app/edict/edict/frontend/dist}"

# edict-go 后台运行；即便它启动失败也不影响 oih 前台主进程。
# 若 EDICT_WEB 目录不存在，edict-go 退化为仅提供 API（前端需另行托管）。
# 先确保 DB 所在目录存在（oih 可能尚未创建 /data），否则 edict-go 会因子目录缺失而打开库失败。
mkdir -p "$(dirname "$EDICT_DB")"
edict-go -db "$EDICT_DB" serve -addr "$EDICT_ADDR" -web "$EDICT_WEB" &

# 未显式传入参数时，让 oih 默认监听 0.0.0.0:9800；否则直接使用传入参数，避免 -listen 重复。
if [ $# -eq 0 ]; then
  set -- -listen "${HUB_LISTEN:-0.0.0.0:9800}"
fi
exec oih "$@"
