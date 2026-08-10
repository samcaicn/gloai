"""
SDK 异常体系（错误码 + 异常类）。

对应 TypeScript: sdk/shared/protocol.ts (error codes) + 后端各服务错误类。

设计原则:
- 错误码用字符串常量而非 int,便于跨语言一致
- 每个异常带 code + message + 可选 details(dict)
- 异常可被 FastAPI handler 捕获后映射到 HTTP status
"""

from __future__ import annotations
from typing import Any, Dict, Optional


# ===== 错误码常量（跨 SDK 端共用字符串） =====
class ErrorCode:
    # 签名 / 鉴权
    SIGNATURE_INVALID = "signature_invalid"
    SIGNATURE_EXPIRED = "signature_expired"
    NONCE_REUSED = "nonce_reused"
    MISSING_AUTH_HEADER = "missing_auth_header"
    DEVICE_TOKEN_INVALID = "device_token_invalid"

    # 时间戳
    TIMESTAMP_OUT_OF_WINDOW = "timestamp_out_of_window"

    # 设备 / 风险
    DEVICE_NOT_REGISTERED = "device_not_registered"
    DEVICE_FINGERPRINT_INVALID = "device_fingerprint_invalid"
    CONSENT_REQUIRED = "consent_required"
    CONSENT_REJECTED = "consent_rejected"
    RISK_BLOCKED = "risk_blocked"
    CAPTCHA_REQUIRED = "captcha_required"
    CAPTCHA_INVALID = "captcha_invalid"

    # LLM / SSE
    LLM_UPSTREAM_ERROR = "llm_upstream_error"
    LLM_RATE_LIMITED = "llm_rate_limited"
    LLM_CONTEXT_TOO_LONG = "llm_context_too_long"
    LLM_STREAM_INTERRUPTED = "llm_stream_interrupted"

    # 通用
    BAD_REQUEST = "bad_request"
    UNAUTHORIZED = "unauthorized"
    FORBIDDEN = "forbidden"
    NOT_FOUND = "not_found"
    INTERNAL_ERROR = "internal_error"


class ClawError(Exception):
    """SDK 端基类异常。

    服务端通过 HTTP 返 JSON {code, message, details?} 给 SDK 解析;
    SDK 端可反序列化成本类。
    """

    code: str = ErrorCode.INTERNAL_ERROR
    http_status: int = 500

    def __init__(
        self,
        message: str = "",
        *,
        code: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message or self.code)
        self.message = message or self.code
        if code is not None:
            self.code = code
        self.details = details or {}

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r}, message={self.message!r})"


class SignatureError(ClawError):
    code = ErrorCode.SIGNATURE_INVALID
    http_status = 401


class SignatureExpiredError(ClawError):
    code = ErrorCode.SIGNATURE_EXPIRED
    http_status = 401


class NonceReusedError(ClawError):
    code = ErrorCode.NONCE_REUSED
    http_status = 401


class TimestampError(ClawError):
    code = ErrorCode.TIMESTAMP_OUT_OF_WINDOW
    http_status = 401


class DeviceNotRegisteredError(ClawError):
    code = ErrorCode.DEVICE_NOT_REGISTERED
    http_status = 403


class DeviceTokenInvalidError(ClawError):
    code = ErrorCode.DEVICE_TOKEN_INVALID
    http_status = 401


class ConsentRequiredError(ClawError):
    code = ErrorCode.CONSENT_REQUIRED
    http_status = 400


class ConsentRejectedError(ClawError):
    """用户拒绝隐私采集 — 走匿名降级模式"""

    code = ErrorCode.CONSENT_REJECTED
    http_status = 200  # 不是错误,但需要特殊处理


class RiskBlockedError(ClawError):
    code = ErrorCode.RISK_BLOCKED
    http_status = 403


class CaptchaRequiredError(ClawError):
    code = ErrorCode.CAPTCHA_REQUIRED
    http_status = 403


class CaptchaInvalidError(ClawError):
    code = ErrorCode.CAPTCHA_INVALID
    http_status = 403


class LlmUpstreamError(ClawError):
    code = ErrorCode.LLM_UPSTREAM_ERROR
    http_status = 502


class LlmRateLimitedError(ClawError):
    code = ErrorCode.LLM_RATE_LIMITED
    http_status = 429


class LlmStreamInterruptedError(ClawError):
    code = ErrorCode.LLM_STREAM_INTERRUPTED
    http_status = 500