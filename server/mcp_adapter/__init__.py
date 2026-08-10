"""
MCP Adapter 包 — 将业务能力暴露为 MCP Tools / Resources / Prompts。

主要模块:
- mcp_server: FastMCP 应用实例 + 挂载函数
- dispatcher: action -> handler 路由表 + 分发入口
- auth: Bearer Token 鉴权 (resolve_tenant_from_bearer)
- share_code_auth: 分享码鉴权 (resolve_tenant_from_share_code)
- tools: 具体 MCP tool 实现
- errors: MCP 错误定义
"""

from .mcp_server import mcp_app, mount_mcp_to_app
from .dispatcher import dispatch, get_manifest_version, bump_manifest_version, get_mcp_stats
from .auth import resolve_tenant_from_bearer, aresolve_tenant_from_bearer
from .share_code_auth import resolve_tenant_from_share_code
from .errors import (
    McpError,
    ValidationError,
    MissingParam,
    InvalidParam,
    NotFound,
    Forbidden,
    Unauthorized,
    TokenRequired,
    TokenInvalid,
    TokenExpired,
    TokenRevoked,
    RateLimited,
    LlmError,
)

__all__ = [
    "mcp_app",
    "mount_mcp_to_app",
    "dispatch",
    "get_manifest_version",
    "bump_manifest_version",
    "get_mcp_stats",
    "resolve_tenant_from_bearer",
    "aresolve_tenant_from_bearer",
    "resolve_tenant_from_share_code",
    "McpError",
    "ValidationError",
    "MissingParam",
    "InvalidParam",
    "NotFound",
    "Forbidden",
    "Unauthorized",
    "TokenRequired",
    "TokenInvalid",
    "TokenExpired",
    "TokenRevoked",
    "RateLimited",
    "LlmError",
]