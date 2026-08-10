"""
HMAC-SHA256 请求签名协议。

对应 TypeScript: sdk/shared/signature.ts + backend/utils/signatureGuard.ts。

canonical 字符串:
  METHOD\nPATH\n<sorted_query>\nTIMESTAMP\nNONCE\n<sha256(body)_hex>

签名:
  sig = hex(HMAC-SHA256(key=client_secret, msg=canonical))

headers (客户端发):
  x-claw-timestamp: unix seconds
  x-claw-nonce:     16+ char
  x-claw-signature: hex

服务端 verify() 内部校验:
  - ts 与 now 差 ≤ SIGNATURE_WINDOW_SECONDS (默认 300s)
  - 签名 constant-time 比较

nonce 防重放由服务端 nonce_store 单独维护(签名层不负责)。
"""

from __future__ import annotations
import hashlib
import hmac
import secrets
import time
from typing import Dict, Optional
from urllib.parse import urlencode

from .exceptions import SignatureError, TimestampError

# 签名时间窗: 5 分钟 (与 TypeScript SDK 一致)
SIGNATURE_WINDOW_SECONDS: int = 300

# nonce 推荐长度 (16 bytes = 32 hex chars)
NONCE_BYTES: int = 16


def _sorted_query(query: Optional[Dict[str, str]]) -> str:
    """规范化 query 串: 按 key 字典序排序,空 query 返空串。"""
    if not query:
        return ""
    return urlencode(sorted(((k, str(v)) for k, v in query.items() if v is not None)))


def _body_hash(body: bytes) -> str:
    """body 的 SHA-256 十六进制,空 body 也算 (e != b"" 时也走)。"""
    if not body:
        # 与 TypeScript SDK 一致:空 body 用 sha256("")
        return hashlib.sha256(b"").hexdigest()
    return hashlib.sha256(body).hexdigest()


def build_canonical(
    method: str,
    path: str,
    query: Optional[Dict[str, str]],
    ts: int,
    nonce: str,
    body: bytes,
) -> bytes:
    """拼接 canonical 串(返回 bytes 便于直接喂 HMAC)。"""
    method = method.upper().strip()
    path = path if path.startswith("/") else f"/{path}"
    parts = [
        method,
        path,
        _sorted_query(query),
        str(int(ts)),
        nonce,
        _body_hash(body),
    ]
    return "\n".join(parts).encode("utf-8")


def sign(secret: bytes, canonical: bytes) -> str:
    """计算 HMAC-SHA256 签名的 hex 字符串。"""
    return hmac.new(secret, canonical, hashlib.sha256).hexdigest()


def generate_nonce(nbytes: int = NONCE_BYTES) -> str:
    """生成推荐长度 nonce(hex)。"""
    return secrets.token_hex(nbytes)


def verify(
    secret: bytes,
    method: str,
    path: str,
    query: Optional[Dict[str, str]],
    ts: int,
    nonce: str,
    body: bytes,
    provided_signature: str,
    *,
    now: Optional[int] = None,
    window_seconds: int = SIGNATURE_WINDOW_SECONDS,
) -> bool:
    """服务端校验签名。

    校验失败抛 SignatureError / TimestampError;
    校验成功返 True(签名一致 + ts 在窗口内)。

    Args:
        secret: 客户端 secret bytes
        method/path/query/body: 实际收到的请求
        ts: 请求头里的 x-claw-timestamp
        nonce: 请求头里的 x-claw-nonce
        provided_signature: 请求头里的 x-claw-signature (hex)
        now: 可注入"当前时间",便于测试;默认 time.time()
        window_seconds: 时间窗宽度
    """
    if now is None:
        now = int(time.time())

    if not isinstance(ts, int) or abs(now - ts) > window_seconds:
        raise TimestampError(
            f"timestamp {ts} out of window (now={now}, window={window_seconds}s)"
        )

    canonical = build_canonical(method, path, query, ts, nonce, body)
    expected = sign(secret, canonical)

    # constant-time 比较
    if not hmac.compare_digest(expected, provided_signature or ""):
        raise SignatureError("signature mismatch")

    return True