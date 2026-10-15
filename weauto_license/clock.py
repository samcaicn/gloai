# -*- coding: utf-8 -*-
"""可信时钟（防系统时间回拨篡改）。

攻击面：本地授权到期 / 每日免费额度都依赖系统时钟。
用户把系统时间改回过去 → 授权"不过期"、每日额度可反复刷。

防线：
  - 首次运行起持久化 max_seen_ts（HMAC 保护，篡改即视为异常）；
  - trusted_now() = max(真实当前时间, max_seen_ts)：
      * 时钟正常前进 → 返回真实时间并推进水位；
      * 时钟回拨 → 返回水位时间（不倒退），并累计回拨次数/首次时间；
  - 回拨事件可被上层读取（供 UI 提示 / 日志），不默认惩罚业务。
  持久化文件与 ledger 同目录（APPDATA/WeAuto 或项目根），盐复用
  QUOTA_LEDGER_SALT（构建期注入，客户端可读但已混淆保护）。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys
import time

from weauto_license import secrets as _sec


def _clock_path() -> str:
    if getattr(sys, "frozen", False):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, "WeAuto")
    else:
        d = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        d = os.path.expanduser("~")
    return os.path.join(d, ".weauto_clock.json")


def _salt() -> bytes:
    v = _sec.get_bytes("QUOTA_LEDGER_SALT")
    return v if v else b"weauto-clock-v1-dev"


def _sign(blob: dict) -> str:
    raw = json.dumps(blob, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hmac.new(_salt(), raw, hashlib.sha256).hexdigest()


def _read() -> dict:
    """读水位。

    返回结构：
      - 正常：{max_seen, rollback_count, first_rollback_ts}
      - 签名失效/格式错：{"__corrupt__": True, "raw_max_seen": <文件里的 max_seen 或 0>}
        （不因篡改而重置水位：上层取 raw 与当前时间较大者，避免删文件/改文件降水位）
      - 文件不存在：{}
    """
    try:
        with open(_clock_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if not isinstance(d, dict):
            return {"__corrupt__": True, "raw_max_seen": 0}
        raw = int(d.get("max_seen", 0) or 0)
        sig = d.pop("sig", "")
        if not hmac.compare_digest(sig, _sign(d)):
            return {"__corrupt__": True, "raw_max_seen": raw}
        return d
    except FileNotFoundError:
        return {}
    except Exception:
        return {"__corrupt__": True, "raw_max_seen": 0}


def _write(d: dict) -> None:
    try:
        body = {k: v for k, v in d.items()}
        body["sig"] = _sign(body)
        with open(_clock_path(), "w", encoding="utf-8") as f:
            json.dump(body, f)
    except Exception:
        pass


def trusted_now() -> int:
    """返回可信 Unix 秒。永远不会比历史水位更低。

    规则：
      - 水位文件正常（HMAC 有效）→ now 与水位取大，推进水位并写回；
      - 水位文件签名失效/被破坏（__corrupt__）→ 取 now 与文件中 raw_max_seen
        的较大者，但不推进/不重建水位（防"删文件/改坏文件降水位"）；
      - 首次运行（无文件）→ 建立水位 = now。
    """
    now = int(time.time())
    try:
        d = _read()
        if d.get("__corrupt__"):
            prev = max(now, int(d.get("raw_max_seen", 0) or 0))
            return prev
        prev = int(d.get("max_seen", 0) or 0)
        if now >= prev:
            if now > prev:
                d["max_seen"] = now
                _write(d)
            return now
        # 回拨：返回水位，记录事件
        d.setdefault("rollback_count", 0)
        d["rollback_count"] = int(d["rollback_count"]) + 1
        d.setdefault("first_rollback_ts", 0)
        if not d.get("first_rollback_ts"):
            d["first_rollback_ts"] = now
        _write(d)
        return prev
    except Exception:
        return now


def rollback_info() -> dict:
    """回拨事件摘要（UI/日志用）。"""
    d = _read()
    return {
        "rollback_count": int(d.get("rollback_count", 0) or 0),
        "first_rollback_ts": int(d.get("first_rollback_ts", 0) or 0),
        "max_seen": int(d.get("max_seen", 0) or 0),
    }
