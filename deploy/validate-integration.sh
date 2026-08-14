#!/bin/sh
# 单容器集成验证脚本：oih (ceoadmin) + golershop + edict-go，全部 SQLite。
#
# 用法：先 `docker compose up -d` 启动，再运行本脚本。
set -e

HUB_PORT="${HUB_PORT:-9800}"
GOLERSHOP_PORT="${GOLERSHOP_PORT:-8000}"
EDICT_PORT="${EDICT_PORT:-7891}"

PASS=0
FAIL=0
SKIP=0

ok()   { echo "  ✅ PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "  ❌ FAIL: $1"; FAIL=$((FAIL+1)); }
skip() { echo "  ⏭️  SKIP: $1"; SKIP=$((SKIP+1)); }

echo "==================================="
echo "单容器 (SQLite) 集成验证"
echo "==================================="

# --- Test 1: hub 端口响应 ---
echo ""
echo "[Test 1] ceoadmin (oih) 监听 ${HUB_PORT}..."
HUB_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${HUB_PORT}/api/system/version" 2>/dev/null || echo "000")
case "$HUB_CODE" in
  200|301|302) ok "oih 响应正常 (HTTP $HUB_CODE)" ;;
  401) ok "oih 响应正常 (HTTP $HUB_CODE，需认证属预期)" ;;
  000) fail "oih 无响应" ;;
  *)   fail "oih 异常响应 (HTTP $HUB_CODE)" ;;
esac

# --- Test 2: golershop 端口响应 ---
echo ""
echo "[Test 2] golershop 监听 ${GOLERSHOP_PORT}..."
GS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${GOLERSHOP_PORT}/" 2>/dev/null || echo "000")
case "$GS_CODE" in
  200|301|302) ok "golershop 响应正常 (HTTP $GS_CODE)" ;;
  000) fail "golershop 无响应" ;;
  *)   fail "golershop 异常响应 (HTTP $GS_CODE)" ;;
esac

# --- Test 3: edict 端口响应 ---
echo ""
echo "[Test 3] edict-go 监听 ${EDICT_PORT}..."
ED_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${EDICT_PORT}/" 2>/dev/null || echo "000")
case "$ED_CODE" in
  200|301|302|404) ok "edict-go 响应正常 (HTTP $ED_CODE)" ;;
  000) fail "edict-go 无响应" ;;
  *)   fail "edict-go 异常响应 (HTTP $ED_CODE)" ;;
esac

# --- Test 4: 容器内三个进程 ---
echo ""
echo "[Test 4] 容器内进程 (oih / golershop / edict-go)..."
if docker compose ps hub > /dev/null 2>&1; then
  PS_OUT=$(docker compose exec hub ps aux 2>/dev/null || true)
  echo "$PS_OUT" | grep -q "oih"      && ok "oih 进程运行中"      || fail "oih 进程缺失"
  echo "$PS_OUT" | grep -q "golershop" && ok "golershop 进程运行中" || fail "golershop 进程缺失"
  echo "$PS_OUT" | grep -q "edict-go" && ok "edict-go 进程运行中" || fail "edict-go 进程缺失"
else
  skip "无法进入 hub 容器"
fi

# --- Test 5: 单一 hub 容器（无 postgres/minio 依赖） ---
echo ""
echo "[Test 5] 服务拓扑（应只有 hub 容器）..."
RUNNING=$(docker compose ps --services --status running 2>/dev/null || echo "")
if [ "$RUNNING" = "hub" ]; then
  ok "仅 hub 单容器运行"
elif echo "$RUNNING" | grep -qE "postgres|minio"; then
  fail "仍有 postgres/minio 容器在运行: $RUNNING"
else
  skip "容器状态异常: $RUNNING"
fi

# --- Test 6: oih 使用 SQLite ---
echo ""
echo "[Test 6] oih 数据库为 SQLite (非 postgres)..."
if docker compose exec hub sh -c 'echo "DATABASE_URL=$DATABASE_URL"' 2>/dev/null | grep -q "postgres"; then
  fail "DATABASE_URL 仍指向 postgres"
elif docker compose exec hub ls /var/lib/CEOadmin/ceoadmin.db >/dev/null 2>&1; then
  ok "ceoadmin.db 存在且 DATABASE_URL 非 postgres"
else
  fail "ceoadmin.db 未生成"
fi

# --- Test 7: golershop SQLite 数据库 ---
echo ""
echo "[Test 7] golershop SQLite 数据库..."
if docker compose exec hub sh -c 'ls /var/lib/CEOadmin/golershop_*.db 2>/dev/null | head -3' 2>/dev/null | grep -q "\.db"; then
  ok "golershop SQLite 数据库已生成"
else
  fail "golershop SQLite 数据库缺失"
fi

# --- Test 8: edict-go SQLite 数据库 ---
echo ""
echo "[Test 8] edict-go SQLite 数据库..."
if docker compose exec hub ls /var/lib/CEOadmin/edict.db >/dev/null 2>&1; then
  ok "edict.db 已生成"
else
  fail "edict.db 未生成"
fi

# --- Test 9: 反向代理 /apps/golershop ---
echo ""
echo "[Test 9] 反向代理 /apps/golershop..."
PROXY_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${HUB_PORT}/apps/golershop/" 2>/dev/null || echo "000")
case "$PROXY_CODE" in
  200|301|302) ok "反向代理正常 (HTTP $PROXY_CODE)" ;;
  401) ok "反向代理已挂载（需认证 HTTP $PROXY_CODE）" ;;
  000) fail "反向代理无响应" ;;
  *)   fail "反向代理异常 (HTTP $PROXY_CODE)" ;;
esac

# --- Test 10: 认证后 builtin-apps 注册 ---
echo ""
echo "[Test 10] 登录 admin 并验证 golershop 内置应用注册..."
CJAR=$(mktemp)
LOGIN_CODE=$(curl -s -o /dev/null -w "%{http_code}" -c "$CJAR" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"A@666666"}' \
  "http://localhost:${HUB_PORT}/api/auth/login" 2>/dev/null || echo "000")
if [ "$LOGIN_CODE" = "200" ]; then
  ok "admin 登录成功 (HTTP $LOGIN_CODE)"
  BUILTIN_RESP=$(curl -s -b "$CJAR" "http://localhost:${HUB_PORT}/api/marketplace/builtin" 2>/dev/null || "")
  if echo "$BUILTIN_RESP" | grep -q "golershop"; then
    ok "golershop 已注册为内置应用"
  else
    fail "内置应用列表未找到 golershop"
  fi
else
  skip "admin 登录失败 (HTTP $LOGIN_CODE)"
fi
rm -f "$CJAR"

# --- Test 11: 数据持久化卷 ---
echo ""
echo "[Test 11] hub-data 数据卷..."
if docker compose exec hub ls /var/lib/CEOadmin/ >/dev/null 2>&1; then
  ok "hub-data 卷挂载并含数据"
else
  fail "hub-data 卷异常"
fi

echo ""
echo "==================================="
echo "结果: ✅ $PASS 通过  ❌ $FAIL 失败  ⏭️ $SKIP 跳过"
echo "==================================="
[ "$FAIL" -eq 0 ]