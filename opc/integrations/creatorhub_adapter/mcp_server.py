"""MCP server that exposes CreatorHub as native SafeOPC tools.

Register it in ``config/system_config.yaml`` under ``mcp_servers``::

    - name: creatorhub
      type: local
      command: ["python", "<repo>/opc/integrations/creatorhub_adapter/mcp_server.py"]
      enabled: true
      env:
        CREATORHUB_BASE_URL: "http://127.0.0.1:8000"

SafeOPC's existing MCP manager (``engine._register_mcp_tools``) launches this
process over stdio and registers every ``@mcp.tool`` below — no change to
``engine.py``. The base URL is taken from ``CREATORHUB_BASE_URL``.

Uses the high-level ``mcp.server.Server`` API (mcp >= 2.0).
"""
from __future__ import annotations

import os

from mcp.server.mcpserver.server import MCPServer

from .client import DEFAULT_BASE_URL, CreatorHubClient

_BASE_URL = os.environ.get("CREATORHUB_BASE_URL", DEFAULT_BASE_URL)
mcp = MCPServer("creatorhub")


async def _client() -> CreatorHubClient:
    return CreatorHubClient(base_url=_BASE_URL)


@mcp.tool()
async def creatorhub_health() -> dict:
    """检查本地 CreatorHub 服务是否存活（探活，不需要登录）。"""
    async with await _client() as c:
        return await c.health()


@mcp.tool()
async def creatorhub_list_accounts(platform: str | None = None) -> list:
    """列出已登录的多平台账号。可选 platform 过滤 (douyin/xhs/kuaishou/shipinhao)。"""
    async with await _client() as c:
        return await c.list_accounts(platform=platform)


@mcp.tool()
async def creatorhub_account_environment(account_id: int) -> dict:
    """返回某账号的浏览器后端诊断：系统 Chrome·CDP 还是回退 Patchright Chromium。"""
    async with await _client() as c:
        return await c.account_environment(int(account_id))


@mcp.tool()
async def creatorhub_parse_share_links(share_text: str, limit: int = 10) -> dict:
    """从分享文案提取抖音/小红书/快手/B站/YouTube 等分享链接（本地解析，不需浏览器/登录）。"""
    async with await _client() as c:
        return await c.parse_share_links(share_text, limit=limit)


@mcp.tool()
async def creatorhub_share_download(
    share_text: str,
    link_index: int = 0,
    all_links: bool = False,
    max_filesize_mb: int = 0,
    output_dir: str | None = None,
) -> dict:
    """解析分享文案并下载媒体/封面/字幕/元数据（写操作）。"""
    async with await _client() as c:
        return await c.share_download(
            share_text, link_index=link_index, all_links=all_links,
            max_filesize_mb=max_filesize_mb, output_dir=output_dir,
        )


@mcp.tool()
async def creatorhub_list_collections() -> list:
    """列出所有采集任务。"""
    async with await _client() as c:
        return await c.list_collections()


@mcp.tool()
async def creatorhub_create_collection(spec: dict) -> dict:
    """创建采集任务。spec 为 CreatorHub 采集任务配置对象。"""
    async with await _client() as c:
        return await c.create_collection(**(spec or {}))


@mcp.tool()
async def creatorhub_get_collection(job_id: str) -> dict:
    """获取单个采集任务详情。"""
    async with await _client() as c:
        return await c.get_collection(job_id)


@mcp.tool()
async def creatorhub_list_collection_contents(job_id: str) -> list:
    """列出某采集任务已抓取内容。"""
    async with await _client() as c:
        return await c.list_collection_contents(job_id)


@mcp.tool()
async def creatorhub_cancel_collection(job_id: str) -> dict:
    """取消一个正在运行的采集任务。"""
    async with await _client() as c:
        return await c.cancel_collection(job_id)


@mcp.tool()
async def creatorhub_list_monitors() -> list:
    """列出所有监控目标。"""
    async with await _client() as c:
        return await c.list_monitors()


@mcp.tool()
async def creatorhub_create_monitor(spec: dict) -> dict:
    """创建监控目标。spec 为 CreatorHub 监控配置对象。"""
    async with await _client() as c:
        return await c.create_monitor(**(spec or {}))


@mcp.tool()
async def creatorhub_get_monitor(tid: str) -> dict:
    """获取单个监控目标详情。"""
    async with await _client() as c:
        return await c.get_monitor(tid)


@mcp.tool()
async def creatorhub_list_contents(query: dict | None = None) -> list:
    """列出已采集作品。可选 query 过滤。"""
    async with await _client() as c:
        return await c.list_contents(**(query or {}))


@mcp.tool()
async def creatorhub_list_published(account_id: int) -> list:
    """列出某账号已发布作品。需要浏览器与已登录的小红书账号。"""
    async with await _client() as c:
        return await c.list_published(int(account_id))


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
