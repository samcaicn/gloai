"""
MCP Server — Streamable HTTP 模式，嵌入现有 FastAPI。

使用 FastMCP 提供 Streamable HTTP 传输（SSE-based），
所有 MCP 工具/资源通过 /mcp 路径对外暴露。
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from mcp.server.fastmcp import FastMCP

logger = logging.getLogger(__name__)

_TOOLS_CACHE = None


# 工具函数延迟导入：避免 package __init__ 导入 mcp_server 时循环依赖
def _tools():
    global _TOOLS_CACHE
    if _TOOLS_CACHE is not None:
        return _TOOLS_CACHE
    # 这里需要根据实际项目结构导入对应的 tools
    # from server.mcp_adapter.tools import (...)
    _TOOLS_CACHE = ()
    return _TOOLS_CACHE


def _auth():
    from server.mcp_adapter.auth import aresolve_tenant_from_bearer
    return aresolve_tenant_from_bearer


mcp_app = FastMCP(
    "shijiback-mcp",
    instructions="AI协同生产系统 MCP 接口。提供技能搜索/管理、流程编排、执行上报等功能。",
)


# ============================================================
# Tool: list_skills（需 Bearer Token）
# ============================================================
@mcp_app.tool(
    name="list_skills",
    description="列出所有可用技能（需 Authorization: Bearer token）",
)
async def list_skills(authorization: Optional[str] = None) -> dict:
    resolve = _auth()
    await resolve(authorization)
    fn = _tools()[0] if _tools() else None
    if fn:
        return await fn()
    return {"total": 0, "skills": []}


# ============================================================
# Tool: search_skills（需 Bearer Token）
# ============================================================
@mcp_app.tool(
    name="search_skills",
    description="搜索技能（需 Authorization: Bearer token）",
)
async def search_skills(query: str, max_results: int = 10, authorization: Optional[str] = None) -> dict:
    resolve = _auth()
    await resolve(authorization)
    fn = _tools()[1] if len(_tools()) > 1 else None
    if fn:
        return await fn(query, max_results)
    return {"total": 0, "query": query, "skills": []}


# ============================================================
# Mount helper
# ============================================================
def mount_mcp_to_app(app) -> None:
    """将 MCP Streamable HTTP 端点挂载到 FastAPI 实例。

    挂载两个端点:
    - /mcp/sse  — SSE transport (Streamable HTTP)
    - /mcp/messages  — Message endpoint
    """
    try:
        sse_starlette = mcp_app.sse_app()
        app.mount("/mcp", sse_starlette)
        logger.info("[mcp] MCP server mounted at /mcp (Streamable HTTP)")
        logger.info("[mcp] SSE endpoint: /mcp/sse")
        logger.info("[mcp] Message endpoint: /mcp/messages")
        logger.info('[mcp] Client config: {"mcpServers":{"shijiback":{"url":"http://<host>:<port>/mcp/sse"}}}')
    except Exception as e:
        logger.warning(f"[mcp] mount failed: {e}")