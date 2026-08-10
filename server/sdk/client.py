"""
SaaS Python SDK Client (v2 — 唯一真通道)。

API (v2: MCP 模式):
  ClawClient(base_url, device_token=None)
    .register_fingerprint(fingerprint, client_info=None, capability_tags=None) -> dict
       POST /api/v1/client/fingerprint → 拿到 device_token 自动存
    .bind(join_code)                      -> dict  (MCP client.bind, 需 iLink 审批)
    .bind_status(request_id)              -> dict  (MCP client.bind.status)
    .unbind(client_id, reason)            -> dict  (MCP client.unbind, 需 iLink 审批)
    .unbind_status(request_id)            -> dict  (MCP client.unbind.status)
    .call_mcp(action, params)             -> dict  (通用 MCP 调用)
    .close()

唯一 HTTP 通道:
  - POST /api/v1/client/fingerprint   首次注册 (公开, 无鉴权)
  - POST /api/v2/mcp                  所有业务 (Authorization: Bearer <device_token>)

v2 架构: 所有业务通过 MCP 入口 /api/v2/mcp。
OpenAI 兼容: 服务端另提供 POST /v1/chat/completions + GET /v1/models (device_token
即 API Key, Authorization: Bearer <device_token>), 与 OpenAI 官方 SDK 同构。
"""

from __future__ import annotations
import hashlib
import logging
from typing import Any, Dict, Optional

import httpx

from .exceptions import ClawError, ErrorCode

logger = logging.getLogger(__name__)

# 浏览器/Node 端 UA
DEFAULT_USER_AGENT = "SaaS-SDK/2.0 (python)"


class ClawClient:
    """v2 SDK 客户端: fingerprint + bind + MCP 完整客户端 (唯一真通道)。

    典型使用流程:
        # 1. 注册设备 (获取 device_token)
        client = ClawClient("https://api.example.com")
        resp = await client.register_fingerprint(fingerprint)
        # resp["device_token"] → 自动存入 client._device_token

        # 2. 绑定到租户 (通过分享码)
        bind_resp = await client.bind(join_code="12345678")
        # bind_resp["status"] == "pending_approval"
        # bind_resp["request_id"] → 用于轮询

        # 3. 轮询审批状态 (管理员在 iLink 确认后)
        status_resp = await client.bind_status(request_id)
        # status_resp["status"] == "approved" → token 不变, 直接可用

        # 4. 审批通过后直接用 MCP (无需更新 token)
        result = await client.call_mcp("skill.list")

    注意: 审批通过后**不换 token**, 客户端无需任何额外操作。
    """

    def __init__(
        self,
        base_url: str,
        *,
        device_token: Optional[str] = None,
        timeout_seconds: float = 30.0,
        user_agent: str = DEFAULT_USER_AGENT,
    ):
        self._base_url = base_url.rstrip("/")
        self._device_token = device_token
        self._timeout = timeout_seconds
        self._ua = user_agent
        self._http: Optional[httpx.AsyncClient] = None

    async def __aenter__(self) -> "ClawClient":
        await self._ensure_http()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.close()

    async def _ensure_http(self) -> httpx.AsyncClient:
        if self._http is None:
            self._http = httpx.AsyncClient(timeout=self._timeout)
        return self._http

    async def close(self) -> None:
        if self._http is not None:
            await self._http.aclose()
            self._http = None

    # ===== 设备指纹生成(本地,不依赖服务端) =====

    @staticmethod
    def generate_device_fingerprint(
        platform: str,
        arch: str,
        language: str,
        timezone: str,
        hardware_serial: str = "",
        *,
        salt: Optional[str] = None,
    ) -> str:
        """生成 64-char SHA-256 hex fingerprint。

        只采集非隐私特征(platform/arch/language/timezone), 不采集 lat/lng/fonts。
        salt 用于"匿名降级模式"(consent_rejected 时派生不同指纹)。
        """
        raw = f"{platform}|{arch}|{language}|{timezone}|{hardware_serial}|{salt or ''}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    # ===== v2 唯一注册入口: 设备指纹 =====

    async def register_fingerprint(
        self,
        fingerprint: str,
        *,
        client_info: Optional[Dict[str, Any]] = None,
        capability_tags: Optional[list] = None,
    ) -> dict:
        """v2: 注册指纹获取 device_token (POST /api/v1/client/fingerprint)。

        成功 (success=true + device_token) → 自动存入 client._device_token。
        """
        body = {
            "fingerprint": fingerprint,
            "client_info": client_info or {},
            "capability_tags": capability_tags or [],
        }
        http = await self._ensure_http()
        resp = await http.post(
            f"{self._base_url}/api/v1/client/fingerprint",
            json=body,
            headers={"User-Agent": self._ua},
        )
        if resp.status_code != 200:
            raise ClawError(
                f"fingerprint failed: {resp.status_code} {resp.text[:200]}",
                code=ErrorCode.BAD_REQUEST,
            )
        data = resp.json()
        if data.get("success") and data.get("device_token"):
            self._device_token = data["device_token"]
        return data

    # ===== 设备绑定/解绑 =====

    async def bind(self, join_code: str) -> dict:
        """通过分享码绑定设备到租户 (需管理员 iLink 确认)。

        Args:
            join_code: 8 位数字分享码

        Returns:
            {"success": True, "status": "pending_approval", "request_id": str, ...}
            客户端用 request_id 轮询 bind_status()。

        注意: 审批通过后 token 不变, 客户端无需更新本地存储。
        """
        if not self._device_token:
            raise ClawError(
                "no device_token: call register_fingerprint() first",
                code=ErrorCode.DEVICE_NOT_REGISTERED,
            )
        return await self.call_mcp("client.bind", {
            "join_code": join_code,
            "device_token": self._device_token,
        })

    async def bind_status(self, request_id: str) -> dict:
        """轮询绑定审批状态。

        Returns:
            {"status": "pending" | "approved" | "unknown" | "unauthorized", ...}
            - approved: 绑定已通过, **token 不变**, 直接用原 token 调 MCP
            - pending: 等待管理员确认
            - unknown: request_id 无效
        """
        if not self._device_token:
            raise ClawError(
                "no device_token: call register_fingerprint() first",
                code=ErrorCode.DEVICE_NOT_REGISTERED,
            )
        return await self.call_mcp("client.bind.status", {
            "request_id": request_id,
            "device_token": self._device_token,
        })

    async def unbind(self, client_id: str, reason: str = "") -> dict:
        """设备解绑 (需管理员 iLink 审批)。

        Args:
            client_id: 要解绑的客户端 ID
            reason: 解绑原因 (可选)

        Returns:
            {"request_id": str, "status": "pending" | "notify_failed"}
            客户端用 request_id 轮询 unbind_status()。
        """
        return await self.call_mcp("client.unbind", {
            "client_id": client_id,
            "reason": reason,
        })

    async def unbind_status(self, request_id: str) -> dict:
        """轮询解绑审批状态。

        Returns:
            {"status": "pending" | "approved" | "not_found", "request_id": str}
        """
        return await self.call_mcp("client.unbind.status", {
            "request_id": request_id,
        })

    async def call_mcp(
        self,
        action: str,
        params: Optional[dict] = None,
    ) -> dict:
        """v2: 调用 MCP action (POST /api/v2/mcp, Bearer token)。"""
        if not self._device_token:
            raise ClawError(
                "no device_token: call register_fingerprint() first",
                code=ErrorCode.DEVICE_NOT_REGISTERED,
            )

        body = {
            "action": action,
            "params": params or {},
        }
        http = await self._ensure_http()
        resp = await http.post(
            f"{self._base_url}/api/v2/mcp",
            json=body,
            headers={
                "Authorization": f"Bearer {self._device_token}",
                "User-Agent": self._ua,
            },
        )
        if resp.status_code != 200:
            raise ClawError(
                f"mcp failed: {resp.status_code} {resp.text[:200]}",
                code=ErrorCode.BAD_REQUEST,
            )
        return resp.json()


# 向后兼容别名: 旧代码引用过 ClawClientV2
ClawClientV2 = ClawClient