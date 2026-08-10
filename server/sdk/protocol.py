"""
协议层数据 schema (跨 SDK 端契约)。

对应 TypeScript: sdk/shared/protocol.ts。
Python dataclass 实现;JSON 序列化天然兼容前端 TS interface 形状。

约束:
- 零业务依赖,纯数据(无 import server.*)
- 仅依赖 stdlib + typing
"""

from __future__ import annotations
import time
import uuid
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any, Dict, List, Optional


# ===== LLM 请求 =====
@dataclass
class LlmRequest:
    messages: List[Dict[str, str]]
    model: str = "ark-code-latest"
    temperature: float = 0.7
    max_tokens: Optional[int] = None
    stream: bool = True
    top_p: float = 1.0
    frequency_penalty: float = 0.0
    presence_penalty: float = 0.0
    stop: Optional[List[str]] = None
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "model": self.model,
            "messages": list(self.messages),
            "temperature": self.temperature,
            "top_p": self.top_p,
            "frequency_penalty": self.frequency_penalty,
            "presence_penalty": self.presence_penalty,
            "stream": self.stream,
        }
        if self.max_tokens is not None:
            d["max_tokens"] = self.max_tokens
        if self.stop:
            d["stop"] = list(self.stop)
        d.update(self.extra)
        return d


# ===== SSE 事件协议 =====
class SseEventType(str, Enum):
    OPEN = "open"
    MESSAGE = "message"
    USAGE = "usage"
    DONE = "done"
    ERROR = "error"
    HEARTBEAT = "heartbeat"


@dataclass
class SseEvent:
    type: SseEventType
    data: Dict[str, Any] = field(default_factory=dict)
    id: Optional[str] = None
    retry_ms: Optional[int] = None

    def encode(self) -> str:
        """序列化为 SSE 协议字符串 (event/data 双行 + 空行结尾)。"""
        import json
        lines = []
        if self.id:
            lines.append(f"id: {self.id}")
        lines.append(f"event: {self.type.value}")
        lines.append(f"data: {json.dumps(self.data, ensure_ascii=False)}")
        if self.retry_ms is not None:
            lines.insert(1, f"retry: {self.retry_ms}")
        return "\n".join(lines) + "\n\n"


# ===== v2 设备注册请求/响应 (POST /api/v1/client/fingerprint) =====
@dataclass
class RegisterRequest:
    """POST /api/v1/client/fingerprint 的请求体 (v2: 注册即发 token, 无需审批)。

    对应 RegisterService.register() 的入参。consent_granted=False 走匿名降级
    (当前实现选择直接转发, 不前缀 anon-, 见 register_service 注释)。
    """

    tenant_id: str
    device_fingerprint: str
    capability_tags: List[str] = field(default_factory=list)
    hardware_config: Dict[str, Any] = field(default_factory=dict)
    consent_granted: bool = True
    consent_id: str = ""
    join_code: str = ""            # 8 位租户分享码, mock tenant_service 不验
    rsa_public_key: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class RegisterResponse:
    """POST /api/v1/client/fingerprint 的响应 (v2: 绑定无需审批)。

    行为约定:
    - success=true → 客户端拿到 device_token, 可直接 stream_chat
    - success=false → 业务错误 (invalid_fingerprint / no_tenant_available / ...)

    设备状态流转:
    - 新设备: activation.required=True, current_state=unbound → 需走 client.bind
    - 已审批设备重新注册: activation.required=False, current_state=active → 直接可用
    - 审批通过后 token 不变, 客户端无需更新本地存储

    设备解绑另走 MCP action: client.unbind (需 admin iLink 审批), 详见 client_unbind.
    """

    success: bool
    client_id: str = ""
    device_token: str = ""
    rsa_public_key: Optional[str] = None
    device_secret_b64: Optional[str] = None  # 把 HMAC secret 一并返给 client, 免去二次获取
    risk_level: str = "trust"
    risk_score: int = 0
    next_step: str = "ok"  # ok | error
    error_detail: Optional[str] = None
    # v1 兼容字段 (RegisterService.verify_captcha 流程, 保留):
    # 高风险指纹注册时 success=False, 返回 verification_token + captcha_answer
    # 客户端用二者调 verify_captcha 完成注册。
    verification_token: str = ""
    captcha_answer: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


# ===== LLM 错误结构(嵌入 SSE error event) =====
@dataclass
class LlmErrorPayload:
    code: str
    message: str
    retry_after: Optional[int] = None  # 秒

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


# ===== 通用辅助 =====
def make_event_id() -> str:
    return f"evt-{uuid.uuid4().hex[:16]}"


def now_ts() -> int:
    return int(time.time())


# ===== v2 MCP 协议 =====
@dataclass
class McpRequest:
    """POST /api/v2/mcp 的请求体。"""
    action: str
    params: Dict[str, Any] = field(default_factory=dict)
    id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id or str(uuid.uuid4()),
            "action": self.action,
            "params": self.params,
        }


@dataclass
class McpResponse:
    """POST /api/v2/mcp 的响应。"""
    id: str
    ok: bool
    data: Optional[Any] = None
    error: Optional[Dict[str, str]] = None

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {"id": self.id, "ok": self.ok}
        if self.data is not None:
            d["data"] = self.data
        if self.error is not None:
            d["error"] = self.error
        return d