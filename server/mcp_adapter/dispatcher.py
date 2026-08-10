"""
dispatcher.py - v2 MCP action → handler 路由表

所有业务 action 在此统一分发, 替代原 REST 路由。
action 命名规范: {domain}.{verb}, 如 skill.search / task.create。
"""

from __future__ import annotations

import logging
import statistics
import threading
import time
from dataclasses import dataclass
from threading import Lock
from typing import Any, Callable, Coroutine, Dict, Optional, Protocol

from server.mcp_adapter.errors import McpError, RateLimited, ValidationError, MissingParam, InvalidParam, NotFound
from server.mcp_adapter.auth import _extract_bearer_token

logger = logging.getLogger(__name__)

# 可选依赖：尝试导入租户服务和令牌持久化
try:
    from server.core.tenant_service.tenant_manager import tenant_service
except ImportError:
    tenant_service = None

try:
    from server.security.token_persistence import lookup_client_id
except ImportError:
    lookup_client_id = None


# P0-8 修复: 接入 TwoLevelRateLimiter 防 MCP action 滥用
# 全局 100 req/min/tenant (跨 action 聚合) + 高危 action 单独更严:
#   llm.request / llm.stream_request: 30/min
#   cos.upload_ticket: 20/min
#   supply_demand.post: 10/min
# fail-open: 限流器初始化/检查异常不阻塞业务
_global_limiter: Any = None
_action_limiters: Dict[str, Any] = {}
try:
    from server.core.security.two_level_rate_limiter import TwoLevelRateLimiter
except ImportError:
    TwoLevelRateLimiter = None

try:
    if TwoLevelRateLimiter is not None:
        _global_limiter = TwoLevelRateLimiter.build(
            name="mcp_global",
            max_requests=100,
            window_seconds=60,
            store_namespace="rl_mcp_global",
        )
        for _act, _max in (
            ("llm.request", 30),
            ("llm.stream_request", 30),
            ("cos.upload_ticket", 20),
            ("supply_demand.post", 10),
            ("supply.publish", 10),
            ("procurement.publish", 10),
        ):
            _action_limiters[_act] = TwoLevelRateLimiter.build(
                name=f"mcp_{_act.replace('.', '_')}",
                max_requests=_max,
                window_seconds=60,
                store_namespace=f"rl_mcp_{_act.replace('.', '_')}",
            )
        logger.info(
            "[dispatcher] rate limiters ready: global=100/min + %d high-risk actions",
            len(_action_limiters),
        )
except Exception as _rl_init_err:  # noqa: BLE001 — fail-open 兜底
    logger.warning(
        "[dispatcher] rate limiter init failed, fail-open: %s", _rl_init_err
    )
    _global_limiter = None
    _action_limiters = {}


# ── Handler 协议 ────────────────────────────────────────────────
class McpHandler(Protocol):
    async def __call__(self, params: dict, ctx: "McpContext") -> dict | str: ...


@dataclass(frozen=True)
class McpContext:
    """传给每个 handler 的上下文, 比裸 tuple 参数清晰。"""
    tenant_id: Optional[str]
    authorization: str


# ── 绑定状态白名单门禁 ──────────────────────────
# 未绑定租户的设备 (client.tenant_id 为 None/空) 只能调白名单 action。
# 绑定通过后 rebind_client_tenant 迁移 tenant_id 到业务租户, 自动放行。
_UNBOUND_TENANT_IDS = frozenset({None, ""})
_BIND_ALLOWED_ACTIONS = frozenset({
    "client.bind",
    "client.bind.status",
    "client.fingerprint.bind",
    "client.fingerprint.status",
    "client.heartbeat",
    "client.dev_bind",
    "mcp.manifest",
    "branding.logo.get",
    "binding",
})


def enforce_bind_gate(tenant_id: Optional[str], action: str) -> None:
    """绑定状态白名单门禁: 未绑定租户的设备只能调白名单 action。

    在 dispatch() 和 mcp.py streaming 分支前调用; 抛 ValidationError 即拒绝。
    绑定通过后 rebind_client_tenant 把 tenant_id 迁到业务租户, 自动放行。
    """
    if tenant_id in _UNBOUND_TENANT_IDS and action not in _BIND_ALLOWED_ACTIONS:
        raise ValidationError(
            "device not bound, please complete client.bind first",
            code="device_not_bound",
        )


_HANDLERS: Dict[str, Callable[..., Coroutine]] = {}

# ── manifest_version 单例 ──
# 每次 _register() / 反注册 → bump; 客户端通过 fingerprint 响应拿到此值,
# 比对本地缓存版本, 决定是否要重拉 mcp.manifest。
_MANIFEST_VERSION: int = 0
_MANIFEST_VERSION_LOCK = threading.Lock()


def bump_manifest_version() -> int:
    """注册新 action 后调用, 让客户端知道 manifest 变了。"""
    global _MANIFEST_VERSION
    with _MANIFEST_VERSION_LOCK:
        _MANIFEST_VERSION += 1
        return _MANIFEST_VERSION


def get_manifest_version() -> int:
    """返回当前 manifest 版本号, fingerprint 响应里塞这个值。"""
    return _MANIFEST_VERSION


# ── MCP 调用可观测性 metrics ────────────────────────────────────
_stats_lock = Lock()
_import_lock = Lock()
_stats: Dict[str, dict] = {}  # action -> {count, success, fail, total_ms, latencies}
_LATENCY_WINDOW = 200  # 保留最近 200 次延迟样本用于 p50/p99


def _record_call(action: str, duration_ms: float, success: bool) -> None:
    """记录单次 MCP action 调用指标。线程安全。防御性: 自身异常不掩盖原异常。"""
    try:
        with _stats_lock:
            s = _stats.setdefault(action, {
                "count": 0, "success": 0, "fail": 0,
                "total_ms": 0.0, "latencies": [],
            })
            s["count"] += 1
            if success:
                s["success"] += 1
            else:
                s["fail"] += 1
            s["total_ms"] += duration_ms
            s["latencies"].append(duration_ms)
            if len(s["latencies"]) > _LATENCY_WINDOW:
                s["latencies"] = s["latencies"][-_LATENCY_WINDOW:]
    except Exception:
        pass  # metrics 记录失败不应掩盖业务异常


def get_mcp_stats() -> Dict[str, Any]:
    """返回 MCP action 调用统计快照（admin 端点用）。

    每个 action 含: count / success / fail / fail_rate / avg_ms / p50_ms / p99_ms。
    """
    with _stats_lock:
        out: Dict[str, Any] = {}
        for action, s in _stats.items():
            lats = sorted(s["latencies"])
            n = len(lats)
            p50 = statistics.median(lats) if lats else 0.0
            p99 = lats[min(int(n * 0.99), n - 1)] if n else 0.0
            count = s["count"]
            out[action] = {
                "count": count,
                "success": s["success"],
                "fail": s["fail"],
                "fail_rate": round(s["fail"] / count, 4) if count else 0.0,
                "avg_ms": round(s["total_ms"] / count, 2) if count else 0.0,
                "p50_ms": round(p50, 2),
                "p99_ms": round(p99, 2),
            }
        return out


def reset_mcp_stats() -> None:
    """清空 metrics（调试用）。"""
    with _stats_lock:
        _stats.clear()


def _register(action: str, handler: Callable[..., Coroutine]) -> None:
    _HANDLERS[action] = handler
    bump_manifest_version()  # 每次注册新 action → manifest_version+1


def _lazy_import():
    if _HANDLERS:
        return
    with _import_lock:
        if _HANDLERS:
            return
        _lazy_import_impl()


def _lazy_import_impl():
    """延迟导入所有 action handlers，避免循环依赖。"""
    # 这里需要根据实际项目结构导入对应的 handlers
    # 示例结构，实际需要根据 shijiback 的业务逻辑实现
    pass


async def dispatch(
    action: str,
    params: dict,
    tenant_id: Optional[str],
    authorization: str,
) -> Any:
    """分发 MCP action 到对应 handler。抛出 McpError 或其他异常。"""
    _lazy_import()

    handler = _HANDLERS.get(action)
    if handler is None:
        # 未知 action 不记 metrics，避免污染 stats（拼写错误/探测请求）
        raise ValidationError(f"unknown action: {action}", code="action.unknown")

    # 绑定状态白名单门禁
    enforce_bind_gate(tenant_id, action)

    # 限流检查
    _rl_key = tenant_id or "anon"
    if _global_limiter is not None:
        try:
            if not _global_limiter.allow(_rl_key):
                raise RateLimited(
                    f"global rate limit exceeded (tenant={tenant_id})",
                    code="rate_limited.global",
                )
        except RateLimited:
            raise
        except Exception as _rl_err:  # noqa: BLE001 — fail-open
            logger.warning("[dispatcher] global limiter error (fail-open): %s", _rl_err)
    _act_limiter = _action_limiters.get(action)
    if _act_limiter is not None:
        try:
            if not _act_limiter.allow(_rl_key):
                raise RateLimited(
                    f"rate limit exceeded for action {action}",
                    code=f"rate_limited.{action}",
                )
        except RateLimited:
            raise
        except Exception as _rl_err:  # noqa: BLE001 — fail-open
            logger.warning("[dispatcher] action limiter error (fail-open): %s", _rl_err)

    t0 = time.perf_counter()
    success = True
    try:
        ctx = McpContext(tenant_id=tenant_id, authorization=authorization)
        return await handler(params, ctx)
    except Exception:
        success = False
        logger.exception(f"[dispatcher] action={action} failed")
        raise
    finally:
        duration_ms = (time.perf_counter() - t0) * 1000
        _record_call(action, duration_ms, success)