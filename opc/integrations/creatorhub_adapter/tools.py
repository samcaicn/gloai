"""SafeOPC tool definitions that proxy to a CreatorHub sidecar.

Each tool builds a short-lived :class:`CreatorHubClient` (closing it after
the call) and forwards to the matching API endpoint. The tools are returned
as SafeOPC ``ToolDefinition`` objects so the engine can register them via
``tool_registry.register(...)`` — no modification to ``engine.py`` required
if you instead expose them through the bundled MCP server
(``mcp_server.py`` + an ``mcp_servers`` entry in ``system_config.yaml``).
"""
from __future__ import annotations

import os
from typing import Any, List

from opc.layer4_tools.registry import ToolDefinition

from .client import DEFAULT_BASE_URL, CreatorHubClient

_BASE_URL_ENV = "CREATORHUB_BASE_URL"


def _base_url() -> str:
    return os.environ.get(_BASE_URL_ENV, DEFAULT_BASE_URL)


async def _client() -> CreatorHubClient:
    return CreatorHubClient(base_url=_base_url())


# ── individual tool implementations ─────────────────────────────
async def _health() -> Any:
    async with await _client() as c:
        return await c.health()


async def _list_accounts(platform: str | None = None) -> Any:
    async with await _client() as c:
        return await c.list_accounts(platform=platform)


async def _account_environment(account_id: int) -> Any:
    async with await _client() as c:
        return await c.account_environment(int(account_id))


async def _parse_share_links(share_text: str, limit: int = 10) -> Any:
    async with await _client() as c:
        return await c.parse_share_links(share_text, limit=limit)


async def _share_download(
    share_text: str,
    link_index: int = 0,
    all_links: bool = False,
    max_filesize_mb: int = 0,
    output_dir: str | None = None,
) -> Any:
    async with await _client() as c:
        return await c.share_download(
            share_text,
            link_index=link_index,
            all_links=all_links,
            max_filesize_mb=max_filesize_mb,
            output_dir=output_dir,
        )


async def _list_collections() -> Any:
    async with await _client() as c:
        return await c.list_collections()


async def _create_collection(spec: dict) -> Any:
    async with await _client() as c:
        return await c.create_collection(**(spec or {}))


async def _get_collection(job_id: str) -> Any:
    async with await _client() as c:
        return await c.get_collection(job_id)


async def _list_collection_contents(job_id: str) -> Any:
    async with await _client() as c:
        return await c.list_collection_contents(job_id)


async def _cancel_collection(job_id: str) -> Any:
    async with await _client() as c:
        return await c.cancel_collection(job_id)


async def _list_monitors() -> Any:
    async with await _client() as c:
        return await c.list_monitors()


async def _create_monitor(spec: dict) -> Any:
    async with await _client() as c:
        return await c.create_monitor(**(spec or {}))


async def _get_monitor(tid: str) -> Any:
    async with await _client() as c:
        return await c.get_monitor(tid)


async def _list_contents(query: dict | None = None) -> Any:
    async with await _client() as c:
        return await c.list_contents(**(query or {}))


async def _list_published(account_id: int) -> Any:
    async with await _client() as c:
        return await c.list_published(int(account_id))


# ── schema helpers ──────────────────────────────────────────────
def _obj(properties: dict, required: list | None = None) -> dict:
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
    }


_STR = {"type": "string"}
_INT = {"type": "integer"}
_BOOL = {"type": "boolean"}


def create_creatorhub_tools() -> List[ToolDefinition]:
    """Return the list of SafeOPC tools that talk to CreatorHub."""
    return [
        ToolDefinition(
            name="creatorhub_health",
            description="检查本地 CreatorHub 服务是否存活（探活，不需要登录）。",
            parameters=_obj({}),
            func=_health,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_list_accounts",
            description="列出 CreatorHub 中已登录的多平台账号（抖音/小红书/快手/视频号）及其风控画像。可选 platform 过滤。",
            parameters=_obj({"platform": _STR}, []),
            func=_list_accounts,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_account_environment",
            description="返回某账号的浏览器后端诊断：是否使用系统 Chrome·CDP，还是回退到 Patchright Chromium。用于确认浏览器策略生效。",
            parameters=_obj({"account_id": _INT}, ["account_id"]),
            func=_account_environment,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_parse_share_links",
            description="从分享文案中提取抖音/小红书/快手/B站/YouTube 等分享链接（纯本地解析，不需要浏览器或登录）。返回识别到的链接列表。",
            parameters=_obj({"share_text": _STR, "limit": _INT}, ["share_text"]),
            func=_parse_share_links,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_share_download",
            description="解析分享文案并下载媒体/封面/字幕/元数据，或仅读取作品信息。会写入本地媒体目录，属于写操作。",
            parameters=_obj({
                "share_text": _STR,
                "link_index": _INT,
                "all_links": _BOOL,
                "max_filesize_mb": _INT,
                "output_dir": _STR,
            }, ["share_text"]),
            func=_share_download,
            category="creatorhub",
            requires_confirmation=True,
        ),
        ToolDefinition(
            name="creatorhub_list_collections",
            description="列出所有采集任务（Collections）。",
            parameters=_obj({}),
            func=_list_collections,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_create_collection",
            description="创建一个新的采集任务。spec 为 CreatorHub 采集任务配置对象（按官方 API 字段填写）。",
            parameters=_obj({"spec": {"type": "object"}}, ["spec"]),
            func=_create_collection,
            category="creatorhub",
            requires_confirmation=True,
        ),
        ToolDefinition(
            name="creatorhub_get_collection",
            description="获取单个采集任务的详情。",
            parameters=_obj({"job_id": _STR}, ["job_id"]),
            func=_get_collection,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_list_collection_contents",
            description="列出某采集任务已抓取到的内容。",
            parameters=_obj({"job_id": _STR}, ["job_id"]),
            func=_list_collection_contents,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_cancel_collection",
            description="取消一个正在运行的采集任务。写操作。",
            parameters=_obj({"job_id": _STR}, ["job_id"]),
            func=_cancel_collection,
            category="creatorhub",
            requires_confirmation=True,
        ),
        ToolDefinition(
            name="creatorhub_list_monitors",
            description="列出所有监控目标（Monitors）。",
            parameters=_obj({}),
            func=_list_monitors,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_create_monitor",
            description="创建一个新的监控目标。spec 为 CreatorHub 监控配置对象（按官方 API 字段填写）。",
            parameters=_obj({"spec": {"type": "object"}}, ["spec"]),
            func=_create_monitor,
            category="creatorhub",
            requires_confirmation=True,
        ),
        ToolDefinition(
            name="creatorhub_get_monitor",
            description="获取单个监控目标的详情。",
            parameters=_obj({"tid": _STR}, ["tid"]),
            func=_get_monitor,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_list_contents",
            description="列出已采集的作品内容。可选 query 过滤（如 account_id、platform）。",
            parameters=_obj({"query": {"type": "object"}}, []),
            func=_list_contents,
            category="creatorhub",
            read_only=True,
        ),
        ToolDefinition(
            name="creatorhub_list_published",
            description="列出某账号已通过 CreatorHub 发布的作品。需要浏览器与已登录的小红书账号。",
            parameters=_obj(
                {"account_id": _INT}, ["account_id"]),
            func=_list_published,
            category="creatorhub",
            read_only=True,
        ),
    ]
