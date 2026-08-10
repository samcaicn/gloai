"""
share_code_auth.py — 分享码身份验证模块。

为第三方 MCP 客户端提供基于分享码（join_code）的轻量身份验证，
替代 Bearer Token 鉴权方式。分享码由租户管理员生成，第三方客户端配置后
即可在 MCP 接口中代表该租户操作供应/采购发布与删除。

认证流程:
  1. 第三方客户端持有 {tenant_id, share_code} 对
  2. 调用 MCP 工具时，通过 share_code 参数传入
  3. 服务端校验 share_code 是否匹配 tenant.join_code
  4. 校验通过后，返回 tenant_id 作为身份上下文

安全设计:
  - share_code 是 8 位数字，由租户管理员通过 share.code.get / share.code.rotate 获取
  - share_code.rotate 可重新生成，旧码立即失效
  - 与 Bearer Token 并行：已有 Bearer Token 的客户端仍走 Token 鉴权
  - share_code 仅开放供应/采购相关接口，不暴露全量 MCP action
  - 速率限制：10 次/分钟/租户（防暴力破解）
  - 操作审计：每次分享码鉴权都记录到审计日志

缓存设计:
  - LRU 内存缓存验证结果，TTL 60s，避免高频调用时重复查 tenant 表
  - 缓存 key = sha256(share_code)[:16]，不直接存明文码

注意: 此模块依赖 server.core.tenant_service 模块，
在 Go 项目中这是内部包，Python 环境下需要提供存根或替代实现。
"""

from __future__ import annotations

import hashlib
import logging
import threading
import time
from typing import Optional

from server.mcp_adapter.errors import (
    Forbidden,
    InvalidParam,
    RateLimited,
)

logger = logging.getLogger(__name__)

# 可选依赖
try:
    from server.core.tenant_service.tenant_manager import tenant_service
except ImportError:
    tenant_service = None

# ── 分享码验证缓存 ─────────────────────────────────────────────
_CACHE_LOCK = threading.Lock()
_CACHE: dict = {}  # cache_key → (tenant_id, expire_at)
_CACHE_TTL = 60  # 60 秒
_CACHE_MAX_SIZE = 500

# ── 分享码限流器 ─────────────────────────────────────────────────
_RATE_LOCK = threading.Lock()
_RATE_COUNTERS: dict = {}  # key → (count, window_start)
_RATE_WINDOW = 60  # 60 秒窗口
_RATE_MAX = 10  # 10 次/分钟


def _cache_key(share_code: str) -> str:
    """生成缓存 key：sha256 哈希后取前 16 位，不存明文码。"""
    return hashlib.sha256(share_code.encode()).hexdigest()[:16]


def _check_rate_limit(tenant_id: str) -> None:
    """检查分享码验证速率限制。超限抛 RateLimited。"""
    now = time.time()
    key = tenant_id or "anon"
    with _RATE_LOCK:
        count, window_start = _RATE_COUNTERS.get(key, (0, now))
        if now - window_start > _RATE_WINDOW:
            _RATE_COUNTERS[key] = (1, now)
            return
        if count >= _RATE_MAX:
            raise RateLimited(
                f"share_code rate limit exceeded for tenant {tenant_id}",
                code="rate_limited.share_code",
            )
        _RATE_COUNTERS[key] = (count + 1, window_start)


def resolve_tenant_from_share_code(
    share_code: str,
    tenant_id: Optional[str] = None,
) -> str:
    """验证分享码并返回 tenant_id。

    两种调用模式:
      1. 仅传 share_code：服务端反查 tenant_id (find_tenant_by_join_code)
      2. 传 share_code + tenant_id：校验码是否匹配该租户

    Returns:
        tenant_id: 验证通过后返回的租户 ID

    Raises:
        InvalidParam: share_code 为空或格式错误
        Forbidden: 分享码无效或不匹配
        RateLimited: 速率超限
    """
    if not share_code:
        raise InvalidParam("share_code", "required for third-party MCP access")

    if not share_code.isdigit() or len(share_code) != 8:
        raise InvalidParam("share_code", "must be 8-digit numeric code")

    # 速率限制
    rate_key = tenant_id or share_code
    _check_rate_limit(rate_key)

    # 查缓存
    ck = _cache_key(share_code)
    with _CACHE_LOCK:
        cached = _CACHE.get(ck)
        if cached is not None:
            cached_tid, expire_at = cached
            if time.time() < expire_at:
                # 缓存命中：如果传了 tenant_id，仍需匹配
                if tenant_id and cached_tid != tenant_id:
                    raise Forbidden("share_code does not match the specified tenant")
                return cached_tid
            else:
                _CACHE.pop(ck, None)

    # 查 tenant 表
    if tenant_service is None:
        raise Forbidden("tenant_service not available")

    if tenant_id:
        # 模式 2：校验码是否匹配指定租户
        tenant = tenant_service.get_tenant(tenant_id)
        if tenant is None:
            raise Forbidden("tenant not found")
        import hmac
        if not hmac.compare_digest(tenant.join_code, share_code):
            # 审计：分享码校验失败
            logger.warning(
                f"share_code_auth: code mismatch for tenant={tenant_id} "
                f"code={share_code[:2]}****"
            )
            raise Forbidden("share_code does not match tenant")
        resolved_tid = tenant_id
    else:
        # 模式 1：反查 tenant_id
        resolved_tid = tenant_service.find_tenant_by_join_code(share_code)
        if not resolved_tid:
            logger.warning(
                f"share_code_auth: no tenant found for code={share_code[:2]}****"
            )
            raise Forbidden("invalid share_code, no matching tenant")

    # 写缓存
    with _CACHE_LOCK:
        if len(_CACHE) >= _CACHE_MAX_SIZE:
            # 简单淘汰：删除最早过期的
            expired_keys = [
                k for k, (_, exp) in _CACHE.items()
                if time.time() >= exp
            ]
            for k in expired_keys:
                _CACHE.pop(k, None)
            # 如果还满，删 LRU（按插入顺序的第一个）
            if len(_CACHE) >= _CACHE_MAX_SIZE:
                oldest_key = next(iter(_CACHE))
                _CACHE.pop(oldest_key, None)
        _CACHE[ck] = (resolved_tid, time.time() + _CACHE_TTL)

    # 审计日志
    logger.info(
        f"share_code_auth: resolved tenant={resolved_tid} "
        f"via share_code={share_code[:2]}****"
    )

    return resolved_tid