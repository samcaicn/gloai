# -*- coding: utf-8 -*-
"""配额本地记账（免费日额度 + 离线授权次数）。

设计
----
- 云端 BILLING_KV 按机器指纹计每日条数、超限 429，是硬墙；
  本地记账是第二道：发请求前先自查当日用量（HMAC 防篡改），
  超限直接返回配额错误，不把滥用流量打给云端。
- 离线授权模式（2026-10 商业模式）：导入管理员签发的「话术包+回复次数」
  授权文件后，回复走**授权总次数**（跨天累计，用一次扣一次，用完需续授权）；
  未授权设备仍走每日免费条数。
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
    # 用可信时钟：防"改系统日期刷每日额度"
    from weauto_license import clock as _clk
    return time.strftime("%Y-%m-%d", time.localtime(_clk.trusted_now()))


# ================================================================== 离线授权
# 授权记录存记账文件 offline 段（HMAC 覆盖）：
#   {pack_id, name, total, used, expires_at, imported_at}
# total 来自授权文件 header.reply_quota（签名防篡改）；used 只增不减。
# 授权有效期间回复走授权次数；过期/用完/被篡改 → 回退每日免费（不默认放行超限）。

def _offline() -> dict:
    d = _read()
    if d.get("__tampered__"):
        return {}
    o = d.get("offline") or {}
    if not isinstance(o, dict):
        return {}
    # 防回滚：used 不得低于历史最高已用（max_used 单调递增）。
    # 攻击者备份旧 ledger（used 小）恢复 → 此处把 used 抬回高水位。
    used = int(o.get("used", 0) or 0)
    max_used = int(o.get("max_used", 0) or 0)
    if used < max_used:
        o["used"] = max_used
        d["offline"] = o
        _write(d)
    return o


def offline_license() -> dict:
    """当前生效的离线授权摘要；无授权返回 {}。"""
    from weauto_license import clock as _clk
    o = _offline()
    if not o.get("pack_id"):
        return {}
    total = int(o.get("total", 0) or 0)
    used = int(o.get("used", 0) or 0)
    expires = int(o.get("expires_at", 0) or 0)
    if total <= 0 or (expires and expires <= _clk.trusted_now()):
        return {}
    return {
        "pack_id": str(o.get("pack_id", "")),
        "name": str(o.get("name", "") or o.get("pack_id", "")),
        "total": total,
        "used": used,
        "remaining": max(0, total - used),
        "expires_at": expires,
        "imported_at": int(o.get("imported_at", 0) or 0),
    }


def set_offline_license(pack_id: str, name: str, total: int, expires_at: int = 0) -> bool:
    """导入授权后写入离线授权记录。total<=0 或 pack_id 空 → 拒绝。"""
    if not pack_id or int(total or 0) <= 0:
        return False
    d = _read()
    if d.get("__tampered__"):
        return False
    d["offline"] = {
        "pack_id": str(pack_id),
        "name": str(name or pack_id),
        "total": int(total),
        "used": 0,  # 新授权从 0 开始计
        "max_used": 0,  # 单调高水位：历史最大已用（防 ledger 回滚降次数）
        "expires_at": int(expires_at or 0),
        "imported_at": int(time.time()),
    }
    _write(d)
    return True


def clear_offline_license() -> None:
    d = _read()
    if d.get("__tampered__"):
        return
    d.pop("offline", None)
    _write(d)


def is_offline_licensed() -> bool:
    return bool(offline_license())


# ================================================================== 配额判定

def check_quota(limit: int | None = None) -> tuple[bool, int, int]:
    """是否还可用。返回 (allowed, remaining, limit)。

    release 下：
      - 有离线授权 → 按授权总次数判定（remaining = total - used），不受每日免费限制；
      - 无授权 → 每日免费条数判定；
      - 记账被篡改 → (False, 0, limit)。
    开发态：恒 (True, limit, limit)。
    """
    if not _sec.is_release():
        lim = limit or FREE_QUOTA_DEFAULT
        return True, lim, lim
    d = _read()
    if d.get("__tampered__"):
        lim = limit or FREE_QUOTA_DEFAULT
        return False, 0, lim
    lic = _offline()
    if lic.get("pack_id") and int(lic.get("total", 0) or 0) > 0:
        total = int(lic["total"])
        used = int(lic.get("used", 0) or 0)
        return (used < total), max(0, total - used), total
    lim = limit or FREE_QUOTA_DEFAULT
    if d.get("date") != _today():
        return True, lim, lim
    used = int(d.get("count", 0) or 0)
    return (used < lim), max(0, lim - used), lim


def record_usage() -> None:
    """记一次使用（仅在 release 下生效）。有离线授权扣授权次数，否则记每日免费。"""
    if not _sec.is_release():
        return
    d = _read()
    if d.get("__tampered__"):
        return
    lic = d.get("offline")
    if isinstance(lic, dict) and lic.get("pack_id") and int(lic.get("total", 0) or 0) > 0:
        lic["used"] = int(lic.get("used", 0) or 0) + 1
        lic["max_used"] = max(int(lic.get("max_used", 0) or 0), lic["used"])
        d["offline"] = lic
        _write(d)
        return
    if d.get("date") != _today():
        d = {"date": _today(), "count": 0}
    d["count"] = int(d.get("count", 0) or 0) + 1
    _write(d)


def sync_from_headers(headers) -> None:
    """用云端响应头校准本地计数（X-Quota-Remaining / X-Quota-Limit）。

    有离线授权时按云端口径（limit = 授权总次数）覆盖 used；
    否则按每日免费口径覆盖 count。
    """
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
    lic = d.get("offline")
    if isinstance(lic, dict) and lic.get("pack_id"):
        lic["used"] = max(0, lim - rem)
        lic["max_used"] = max(int(lic.get("max_used", 0) or 0), lic["used"])
        d["offline"] = lic
        _write(d)
        return
    d["date"] = _today()
    d["count"] = max(0, lim - rem)
    _write(d)
