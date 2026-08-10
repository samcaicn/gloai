"""
SDK 模块 — 跨端协议 + Python Client + 加密。

⚠️ **目录定位**：本目录 `server/sdk/` 是**服务端自用基础库**，
被服务端代码直接 import，是运行时依赖，禁止删除/合并。
客户端示例代码在仓库根 `sdk/`（JS/TS + Python 示例），两者严禁互相 import。

P1 协议层 (纯数据,无业务依赖):
  protocol        LlmRequest / SseEvent / RegisterResponse
  signature       HMAC-SHA256 签名 (build_canonical / sign / verify)
  sse             SSE 协议解析/编码 (parse_sse_stream / encode_sse_event)
  exceptions      ClawError 体系 + ErrorCode 字符串

P7 Client:
  ClawClient      v2 SDK (register_fingerprint / bind / call_mcp), 走 /api/v2/mcp

OpenAI 兼容: 服务端 POST /v1/chat/completions + GET /v1/models (device_token 即
API Key), 客户端可用标准 OpenAI SDK 直连。

向后兼容 (旧名 AICoopClient / encrypt_message / decrypt_message 仍可用)
"""

from .client import ClawClient
# 向后兼容: 旧 SDK 客户端引用过 ClawClientV2
ClawClientV2 = ClawClient
# 向后兼容: 更老的 SDK 客户端引用过 AICoopClient
AICoopClient = ClawClient
from .device_fingerprint import generate_device_fingerprint
from .encryption import encrypt_message, decrypt_message

__all__ = [
    "ClawClient",
    "ClawClientV2",  # 旧名别名
    "AICoopClient",  # 旧名别名
    "generate_device_fingerprint",
    "encrypt_message",
    "decrypt_message",
]