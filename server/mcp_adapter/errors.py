"""
MCP 适配器错误定义。
"""

from __future__ import annotations
from typing import Any, Dict, Optional


class McpError(Exception):
    """MCP 错误基类，可被 handler 捕获后映射到标准错误响应。"""

    code: str = "internal_error"
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


class ValidationError(McpError):
    code = "validation_error"
    http_status = 400


class MissingParam(ValidationError):
    def __init__(self, param: str):
        super().__init__(f"missing required parameter: {param}", code="missing_param", details={"param": param})


class InvalidParam(ValidationError):
    def __init__(self, param: str, reason: str = ""):
        msg = f"invalid parameter: {param}"
        if reason:
            msg += f" ({reason})"
        super().__init__(msg, code="invalid_param", details={"param": param, "reason": reason})


class NotFound(McpError):
    code = "not_found"
    http_status = 404

    def __init__(self, resource: str, identifier: str):
        super().__init__(f"{resource} not found: {identifier}", code="not_found", details={"resource": resource, "id": identifier})


class Forbidden(McpError):
    code = "forbidden"
    http_status = 403


class Unauthorized(McpError):
    code = "unauthorized"
    http_status = 401


class TokenRequired(Unauthorized):
    code = "token_required"

    def __init__(self, msg: str = "missing Authorization header"):
        super().__init__(msg)


class TokenInvalid(Unauthorized):
    code = "token_invalid"

    def __init__(self, msg: str = "invalid token"):
        super().__init__(msg)


class TokenExpired(Unauthorized):
    code = "token_expired"

    def __init__(self, msg: str = "device token expired"):
        super().__init__(msg)


class TokenRevoked(Unauthorized):
    code = "token_revoked"

    def __init__(self, msg: str = "device token has been revoked"):
        super().__init__(msg)


class RateLimited(McpError):
    code = "rate_limited"
    http_status = 429


class LlmError(McpError):
    code = "llm_error"
    http_status = 502