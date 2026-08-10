"""
MCP Bearer Token 鉴权 — 精简三段式。

设计原则:
    主函数 `resolve_tenant_from_bearer` 只做编排, 三层语义拆为独立辅助函数:
      1. `_extract_bearer_token`  — Bearer 头存在 + 解析 token 字符串
      2. `_load_valid_token_record` — token 有效 (存在性 + 过期 + 续期 + 回滚开关)
      3. `_resolve_tenant_id_from_record` — token 已绑定 client (反查 tenant_id)

    每层职责单一、可独立测试, 错误码细分保持不变:
      - TokenRequired:  无 Authorization header
      - TokenInvalid:   token 找不到 / 格式错 / client_id 缺失
      - TokenExpired:   token 已过期 (服务端管理, 12h TTL + 2h 滑动续期)
      - TokenRevoked:   client_id 存在但 client 实体不在 (admin 撤销)

注意: 此模块依赖 server.core.tenant_service 和 server.security 模块，
在 Go 项目中这些是内部包，Python 环境下需要提供存根或替代实现。
"""

from __future__ import annotations

import logging
import time
from typing import Optional

from server.mcp_adapter.errors import (
    TokenInvalid,
    TokenRequired,
    TokenExpired,
    TokenRevoked,
)

logger = logging.getLogger(__name__)

# 滑动过期: 每次 MCP 调用检查 expires_at, 到期前 2h 内自动续 12h
_RENEW_WINDOW_HOURS = 2
_TOKEN_TTL_HOURS = 12

# 可选依赖
try:
    from server.security import token_persistence
except ImportError:
    token_persistence = None

try:
    from server.security.signature_guard import get_token_store
except ImportError:
    get_token_store = None

try:
    from server.config import config
except ImportError:
    config = None

try:
    from server.core.tenant_service.tenant_manager import tenant_service
except ImportError:
    tenant_service = None


# ============================================================
# 第 1 层: Bearer 头存在 + 解析 token
# ============================================================
def _extract_bearer_token(authorization: Optional[str]) -> str:
    """从 Authorization header 解析 Bearer token。

    - None / 空串 → TokenRequired (无 header)
    - "Bearer " (strip 后空) → TokenInvalid (有 header 但 token 空)
    - "Bearer xxx" → "xxx"
    - 非 Bearer 前缀 → 取末段 (兼容 "token" 直传场景)
    """
    if not authorization:
        raise TokenRequired("missing Authorization header")
    token = authorization.split(" ", 1)[-1].strip()
    if not token:
        raise TokenInvalid("empty Bearer token")
    return token


# ============================================================
# 第 2 层: token 有效 (存在性 + 过期 + 续期 + 回滚开关)
# ============================================================
def _try_renew_token(token: str, record: dict) -> None:
    """如果 token 在续期窗口内, 非阻塞续期。"""
    expires_at = record.get("expires_at")
    if not isinstance(expires_at, (int, float)) or expires_at <= 0:
        return
    now = int(time.time())
    remaining_hours = (int(expires_at) - now) / 3600
    if remaining_hours <= _RENEW_WINDOW_HOURS:
        try:
            if token_persistence is not None:
                token_persistence.renew_token_expiry(token)
        except Exception as e:
            logger.debug(f"auth: renew_token_expiry failed (non-fatal): {e}")


def _try_repair_from_memory_store(token: str) -> Optional[dict]:
    """兜底: token 在 DeviceTokenStore 内存 map 但不在 COS 缓存 → 补写到 COS。

    返回修复后的 record, 或 None (无法修复)。
    """
    try:
        if get_token_store is not None:
            cid = get_token_store().get_client_id(token)
            if cid:
                logger.info(
                    f"auth: token in DeviceTokenStore but missing in CosCachedStorage, "
                    f"repairing client={cid[:8]}..."
                )
                if token_persistence is not None:
                    token_persistence.persist_register(token, cid)
                    return token_persistence.get_token_record(token)
    except Exception as e:
        logger.debug(f"auth: repair_from_memory_store failed (non-fatal): {e}")
    return None


def _check_expiry_and_renew(token: str, record: dict, _t0: float) -> None:
    """过期检查 + 滑动续期 + 老 token 补写。

    - TOKEN_EXPIRY_ENABLED=False → 跳过过期检查 (回滚开关)
    - expires_at 缺失 (老 token) → 补写 now+12h, 不阻塞
    - expires_at < now → 抛 TokenExpired
    - 到期前 2h 内 → 非阻塞续期
    """
    # 回滚开关: 关闭时跳过整个过期检查
    if config is not None and not getattr(config, "TOKEN_EXPIRY_ENABLED", True):
        return

    expires_at = record.get("expires_at")
    now_ts = int(time.time())

    if isinstance(expires_at, (int, float)) and expires_at > 0:
        if int(expires_at) < now_ts:
            elapsed_ms = (time.monotonic() - _t0) * 1000
            cid = record.get("client_id", "?")[:8]
            logger.warning(f"auth: token expired client={cid} in {elapsed_ms:.1f}ms")
            raise TokenExpired("device token expired")
        # 到期前 2h 内自动续期 (非阻塞)
        _try_renew_token(token, record)
    else:
        # 存量老 token 缺 expires_at → 补写 now+12h (不阻塞业务, 失败也放行)
        try:
            if token_persistence is not None:
                token_persistence.lazy_patch_expires_at(token)
        except Exception as e:
            logger.debug(f"auth: lazy_patch_expires_at failed (non-fatal): {e}")


def _load_valid_token_record(token: str, _t0: float) -> dict:
    """加载并校验 token record (存在性 + 过期 + 续期)。

    返回 record dict (至少含 client_id)。失败抛 TokenInvalid / TokenExpired。
    """
    if token_persistence is None:
        raise TokenInvalid("token_persistence not available")

    record = token_persistence.get_token_record(token)
    if record is None:
        # 兜底: 内存 DeviceTokenStore 有但 COS 缓存缺 → 补写
        record = _try_repair_from_memory_store(token)
    if record is None:
        elapsed_ms = (time.monotonic() - _t0) * 1000
        logger.warning(f"auth: token lookup failed in {elapsed_ms:.1f}ms")
        raise TokenInvalid("invalid token")

    # 过期检查 + 续期 (含 TOKEN_EXPIRY_ENABLED 回滚开关)
    _check_expiry_and_renew(token, record, _t0)

    client_id = record.get("client_id")
    if not client_id:
        # record 在但 client_id 字段缺失 — 视为无效
        elapsed_ms = (time.monotonic() - _t0) * 1000
        logger.warning(f"auth: record missing client_id in {elapsed_ms:.1f}ms")
        raise TokenInvalid("invalid token record")

    return record


# ============================================================
# 第 3 层: token 已绑定 client (反查 tenant_id)
# ============================================================
def _resolve_tenant_id_from_record(record: dict, _t0: float) -> str:
    """从 record 反查 client.tenant_id。

    - client 实体被删 → TokenRevoked
    - client.tenant_id 为空 → 返 "" (未激活, 交 bind gate 决定)
    - 正常 → 返 tenant_id
    """
    if tenant_service is None:
        raise TokenInvalid("tenant_service not available")

    # 用 get_client_for_auth 而非 get_client: auth 阶段没有 tenant_id 上下文,
    # 不能传 request_tenant_id (get_client 在 None 时会返 None, 误判为 TokenRevoked)
    client_id = record["client_id"]
    client = tenant_service.get_client_for_auth(client_id)
    if not client:
        # client_id 存在但 client 实体被删 (admin 走 unbind + cleanup)
        elapsed_ms = (time.monotonic() - _t0) * 1000
        logger.warning(f"auth: client={client_id[:8]}... revoked/missing in {elapsed_ms:.1f}ms")
        raise TokenRevoked("device token has been revoked")

    if not client.tenant_id:
        # token 有效, client 注册了, 但还没绑到 tenant
        # (走 fingerprint 但没走 ilink-login / join_code)
        # 返回 None 放行, 交给 enforce_bind_gate 决定白名单/拒绝
        elapsed_ms = (time.monotonic() - _t0) * 1000
        logger.info(f"auth: client={client_id[:8]}... not activated in {elapsed_ms:.1f}ms")
        return ""

    elapsed_ms = (time.monotonic() - _t0) * 1000
    logger.debug(
        f"auth: resolved tenant={client.tenant_id} "
        f"client={client_id[:8]}... in {elapsed_ms:.1f}ms"
    )
    return client.tenant_id


# ============================================================
# 主函数: 编排三层校验
# ============================================================
def resolve_tenant_from_bearer(authorization: Optional[str]) -> str:
    """验证 Bearer Token 并返回 tenant_id。

    精简三段式:
      1. _extract_bearer_token        — Bearer 头存在 + 解析
      2. _load_valid_token_record     — token 有效 (存在 + 过期 + 续期 + 回滚开关)
      3. _resolve_tenant_id_from_record — token 已绑定 client

    返回:
      - tenant_id 字符串: token 有效且 client 已绑租户
      - "": token 有效但 client 未绑租户 (交 bind gate 决定)

    抛出:
      - TokenRequired: 无 Authorization header
      - TokenInvalid: token 找不到 / 格式错 / client_id 缺失
      - TokenExpired: token 已过期 (TOKEN_EXPIRY_ENABLED=true 时)
      - TokenRevoked: client_id 存在但 client 实体不在 (admin 撤销)
    """
    _t0 = time.monotonic()

    # 三层校验, 每层职责单一
    token = _extract_bearer_token(authorization)
    record = _load_valid_token_record(token, _t0)
    return _resolve_tenant_id_from_record(record, _t0)


async def aresolve_tenant_from_bearer(authorization: Optional[str]) -> str:
    """异步版鉴权: 把同步 I/O (token/COS 存储读取) 移到线程池, 避免阻塞事件循环。"""
    import asyncio
    return await asyncio.to_thread(resolve_tenant_from_bearer, authorization)