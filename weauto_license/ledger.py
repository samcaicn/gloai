# -*- coding: utf-8 -*-
"""免费配额本地记账（防破解整改：免费模式收紧）。

设计
----
- 云端 BILLING_KV 按机器指纹计每日条数、超限 429，是硬墙；
  本地记账是第二道：发请求前先自查当日用量（HMAC 防篡改），
  超限直接返回配额错误，不把滥用流量打给云端。
- 记账文件带 HMAC 签名（盐 QUOTA_LEDGER_SALT 构建期注入），
  手工改 JSON 会被识破并视为超限。
- 与云端响应头 X-Quota-Remaining / X-Quota-Limit 校准：每次 2xx/429
  都按云端口径覆盖本地计数，避免本地漂移误杀。
- 开发态不记账（恒放行），不干扰本地开发。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys
import time

from weauto_license import secrets as _sec

FREE_QUOTA_DEFAULT = 30


def _ledger_path() -> str:
    if getattr(sys, "frozen", False):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, "WeAuto")
    else:
        d = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        d = os.path.expanduser("~")
    return os.path.join(d, ".weauto_ledger.json")


def _salt() -> bytes:
    v = _sec.get_bytes("QUOTA_LEDGER_SALT")
    return v if v else b"weauto-ledger-v1-dev"


def _sign(blob: dict) -> str:
    raw = json.dumps(blob, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hmac.new(_salt(), raw, hashlib.sha256).hexdigest()


def _read() -> dict:
    """读记账；签名不符/格式错 → 视为空（并当作超限处理，不默认放行）。"""
    try:
        with open(_ledger_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if not isinstance(d, dict):
            return {}
        sig = d.pop("sig", "")
        want = _sign(d)
        if not hmac.compare_digest(sig, want):
            return {"__tampered__": True}
        return d
    except Exception:
        return {}


def _write(d: dict) -> None:
    try:
        body = {k: v for k, v in d.items() if k != "__tampered__"}
        body["sig"] = _sign(body)
        with open(_ledger_path(), "w", encoding="utf-8") as f:
            json.dump(body, f)
    except Exception:
        pass


def _today() -> str:
    return time.strftime("%Y-%m-%d")


def check_quota(limit: int | None = None) -> tuple[bool, int, int]:
    """是否还可用。返回 (allowed, remaining, limit)。

    release 下：超限或记账被篡改 → (False, 0, limit)。
    开发态：恒 (True, limit, limit)。
    """
    if not _sec.is_release():
        lim = limit or FREE_QUOTA_DEFAULT
        return True, lim, lim
    lim = limit or FREE_QUOTA_DEFAULT
    d = _read()
    if d.get("__tampered__"):
        return False, 0, lim
    if d.get("date") != _today():
        return True, lim, lim
    used = int(d.get("count", 0) or 0)
    return (used < lim), max(0, lim - used), lim


def record_usage() -> None:
    """记一次使用（仅在 release 下生效）。"""
    if not _sec.is_release():
        return
    d = _read()
    if d.get("__tampered__"):
        return
    if d.get("date") != _today():
        d = {"date": _today(), "count": 0}
    d["count"] = int(d.get("count", 0) or 0) + 1
    _write(d)


def sync_from_headers(headers) -> None:
    """用云端响应头校准本地计数（X-Quota-Remaining / X-Quota-Limit）。"""
    if not _sec.is_release():
        return
    try:
        remaining = headers.get("X-Quota-Remaining")
        limit = headers.get("X-Quota-Limit")
        if remaining is None or limit is None:
            return
        rem = int(remaining)
        lim = int(limit)
    except Exception:
        return
    if lim <= 0:
        return
    d = _read()
    if d.get("__tampered__"):
        return
    d["date"] = _today()
    d["count"] = max(0, lim - rem)
    _write(d)
