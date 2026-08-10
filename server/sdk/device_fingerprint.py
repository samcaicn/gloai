"""
设备指纹生成工具（本地生成，不依赖服务端）。
"""

import hashlib
from typing import Optional


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