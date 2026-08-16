"""Device authorization for SafeOPC — CLIENT architecture.

SafeOPC 桌面端是**客户端**；本地的 aiohttp office-ui 只是客户端的本地后台，
**不是**授权服务端。真正的审批权威在远端授权服务器（auth_server_base_url）。

角色划分（务必搞清楚）：
  * 远端授权服务器  = 权威。签发 token、决定 pending/active（审核通过）。
  * 本地 office-ui  = 客户端。把请求发给远端（或开发期跑一个「本地桩」模拟远端），
                      把远端的裁决**缓存**到本地，门禁据此本地放行/拦截。
                      本地永远不自己当权威、不自己批自己。

本模块三部分：
  1. 远端服务端契约的**本地桩**（dev_stub，仅 SAFEOPC_AUTH_STUB=1）：
     office-ui 在本地模拟远端服务器的行为（签发 token / 决定审批），纯粹是
     开发期对远端服务器的模拟，**不是生产环境的权威**。
  2. 客户端缓存：把远端服务器的裁决写到 device_auth_client.json，门禁据此执行。
  3. 生产代理：office-ui 把 /api/device/* 请求转发到 auth_server_base_url；
     未配置远端服务器时返回 503，设备保持未注册、门禁拦截。

配置（SystemConfig.device_auth，env 可覆盖）：
  SAFEOPC_DEVICE_AUTH  门禁总开关（默认 "1"）
  SAFEOPC_AUTH_SERVER  远端授权服务器 base URL（如 https://auth.safeopc.example）
  SAFEOPC_AUTH_STUB    开发期本地桩开关（默认 "0"）
  SAFEOPC_APPROVE_CODES 桩的自动审批码（仅桩生效，代表"服务端策略"）
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import socket
import time
import uuid
from pathlib import Path
from typing import Any


# ── Environment ─────────────────────────────────────────────────────────────

def _env_enabled() -> bool:
    v = os.environ.get("SAFEOPC_DEVICE_AUTH", "1").strip().lower()
    return v not in ("0", "false", "no", "off")


def _env_stub() -> bool:
    v = os.environ.get("SAFEOPC_AUTH_STUB", "0").strip().lower()
    return v in ("1", "true", "yes", "on")


def _env_base_url() -> str | None:
    return os.environ.get("SAFEOPC_AUTH_SERVER") or None


def _env_auto_approve_codes() -> list[str]:
    raw = os.environ.get("SAFEOPC_APPROVE_CODES")
    if raw:
        return [c.strip().upper() for c in raw.split(",") if c.strip()]
    return ["SAFEOPC-DEMO"]


_REGISTRY_LOCK = asyncio.Lock()


# ── Fingerprint（设备指纹，客户端生成，发给远端）─────────────────────────────

def collect_fingerprint() -> str:
    """生成本机稳定匿名指纹（SHA-256），客户端用来向远端标识本设备。"""
    parts: list[str] = []
    try:  # Windows MachineGuid
        import winreg  # type: ignore

        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography") as key:
            parts.append("mg:" + str(winreg.QueryValueEx(key, "MachineGuid")[0]))
    except Exception:
        pass
    try:  # MAC（跳过 uuid.getnode 的随机哨兵）
        mac = uuid.getnode()
        if (mac >> 40) & 0xFF != 0:
            parts.append("mac:" + format(mac, "012x"))
    except Exception:
        pass
    try:  # 系统卷序列
        import ctypes  # type: ignore

        serial = ctypes.c_ulong()
        ctypes.windll.kernel32.GetVolumeInformationW(  # type: ignore[attr-defined]
            "C:\\", None, 0, ctypes.byref(serial), None, None, None, 0
        )
        parts.append("vol:" + str(serial.value))
    except Exception:
        pass
    try:
        parts.append("host:" + socket.gethostname())
    except Exception:
        pass
    if not parts:
        parts.append("fallback:" + str(os.getpid()))
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:32]


# ── Settings ────────────────────────────────────────────────────────────────

def resolve_settings(config: Any | None = None) -> dict:
    """合并 config 与 env。env 优先（未设置 env 时回落到 config）。"""
    enabled = _env_enabled()
    stub = _env_stub()
    base_url = _env_base_url()
    codes = _env_auto_approve_codes()
    dev_approve = True

    if config is not None:
        system = getattr(config, "system", None)
        da = getattr(system, "device_auth", None) if system is not None else None
        if da is not None:
            if os.environ.get("SAFEOPC_DEVICE_AUTH") is None:
                enabled = bool(getattr(da, "enabled", enabled))
            if os.environ.get("SAFEOPC_AUTH_STUB") is None:
                stub = bool(getattr(da, "dev_stub_enabled", stub))
            if os.environ.get("SAFEOPC_AUTH_SERVER") is None:
                cfg_url = getattr(da, "auth_server_base_url", None)
                if cfg_url:
                    base_url = str(cfg_url)
            if os.environ.get("SAFEOPC_APPROVE_CODES") is None:
                cfg_codes = getattr(da, "stub_auto_approve_codes", None)
                if cfg_codes:
                    codes = [str(c).strip().upper() for c in cfg_codes if str(c).strip()]
            if getattr(da, "stub_dev_approve_enabled", None) is not None:
                dev_approve = bool(getattr(da, "stub_dev_approve_enabled"))

    return {
        "enabled": enabled,
        "dev_stub_enabled": stub,
        "auth_server_base_url": base_url or "",
        "auto_approve_codes": codes,
        "dev_approve_enabled": dev_approve,
    }


# ── Client cache（本地缓存远端裁决；不是权威）────────────────────────────────

def _client_cache_path(opc_home: Any) -> Path:
    return Path(opc_home) / "device_auth_client.json"


def _client_load(opc_home: Any) -> dict:
    try:
        with open(_client_cache_path(opc_home), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            return data
    except FileNotFoundError:
        pass
    except Exception:
        pass
    return {}


def _client_save(opc_home: Any, data: dict) -> None:
    path = _client_cache_path(opc_home)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _normalize_status(raw: str | None) -> str:
    lower = (raw or "").lower()
    if lower in ("active", "approved"):
        return "active"
    if lower in ("pending_approval", "pending", "unbound", "device_not_bound", "not_bound",
                 "rejected", "reject", "declined", "denied", "disabled", "revoked"):
        return "pending_approval"
    return "unknown"


def cache_server_response(opc_home: Any, result: Any) -> None:
    """把远端服务器返回的裁决写入本地客户端缓存（供门禁读取）。"""
    if not isinstance(result, dict):
        return
    data = _client_load(opc_home)
    token = result.get("token")
    status = result.get("approvalStatus") or result.get("status")
    tenant = result.get("tenantId")
    if token:
        data["token"] = token
    if status:
        data["approval_status"] = _normalize_status(status)
    if tenant is not None:
        data["tenant_id"] = tenant
    _client_save(opc_home, data)


def get_cached_status(opc_home: Any) -> str:
    """本地缓存的审批状态（来自远端）；无记录则 unregistered。"""
    return _client_load(opc_home).get("approval_status") or "unregistered"


def is_execution_allowed(opc_home: Any, settings: dict | None = None) -> bool:
    """门禁：远端裁决为 active 才放行 LLM/MCP；关闭门禁或无记录则拦截。"""
    if settings is None:
        settings = resolve_settings()
    if not settings.get("enabled"):
        return True
    return get_cached_status(opc_home) == "active"


# ── Dev stub（模拟远端授权服务器；仅开发期，明确不是生产权威）────────────────

def _stub_path(opc_home: Any) -> Path:
    return Path(opc_home) / "device_auth_stub.json"


def _stub_load(opc_home: Any) -> dict:
    try:
        with open(_stub_path(opc_home), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            data.setdefault("devices", {})
            return data
    except FileNotFoundError:
        pass
    except Exception:
        pass
    return {"devices": {}}


def _stub_save(opc_home: Any, data: dict) -> None:
    path = _stub_path(opc_home)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _stub_find_by_token(data: dict, token: str | None) -> dict | None:
    if not token:
        return None
    for dev in data.get("devices", {}).values():
        if dev.get("device_token") == token:
            return dev
    return None


async def stub_register(opc_home: Any, join_code: str, settings: dict | None = None) -> dict:
    """桩：模拟远端服务器处理设备注册 / 验证码提交。"""
    if settings is None:
        settings = resolve_settings()
    async with _REGISTRY_LOCK:
        data = _stub_load(opc_home)
        fp = collect_fingerprint()
        dev = data["devices"].get(fp)
        is_new = False
        if dev is None:
            dev = {
                "device_id": fp,
                "device_token": secrets.token_urlsafe(32),
                "approval_status": "pending_approval",
                "tenant_id": None,
                "created_at": time.time(),
                "bind_requests": {},
            }
            data["devices"][fp] = dev
            is_new = True
        if not dev.get("device_token"):
            dev["device_token"] = secrets.token_urlsafe(32)

        status = dev["approval_status"]
        request_id: str | None = None
        if join_code:
            code = join_code.strip().upper()
            if code in settings["auto_approve_codes"]:
                dev["approval_status"] = "active"
                dev["tenant_id"] = dev.get("tenant_id") or "tenant-default"
                status = "active"
                next_step = "approved"
            else:
                request_id = uuid.uuid4().hex
                dev["bind_requests"][request_id] = {
                    "join_code_hash": hashlib.sha256(code.encode()).hexdigest(),
                    "status": "pending_approval",
                    "created_at": time.time(),
                }
                dev["approval_status"] = "pending_approval"
                status = "pending_approval"
                next_step = "await_approval"
        else:
            next_step = "approved" if status == "active" else "await_approval"
        _stub_save(opc_home, data)
        return {
            "token": dev["device_token"],
            "deviceId": dev["device_id"],
            "tenantId": dev.get("tenant_id"),
            "isNewDevice": is_new,
            "approvalStatus": status,
            "nextStep": next_step,
            "requestId": request_id,
        }


async def stub_bind_status(opc_home: Any, token: str, request_id: str | None = None) -> dict:
    data = _stub_load(opc_home)
    dev = _stub_find_by_token(data, token)
    if not dev:
        return {"status": "unknown", "valid": False}
    if dev["approval_status"] == "active":
        return {"status": "active", "valid": True}
    if request_id and request_id in dev.get("bind_requests", {}):
        br = dev["bind_requests"][request_id]
        return {"status": br["status"], "valid": br["status"] == "active"}
    return {"status": dev["approval_status"], "valid": False}


async def stub_verify(opc_home: Any, token: str) -> dict:
    data = _stub_load(opc_home)
    dev = _stub_find_by_token(data, token)
    if not dev:
        return {"valid": False, "approvalStatus": "unknown", "tenantId": None}
    return {
        "valid": True,
        "approvalStatus": dev["approval_status"],
        "tenantId": dev.get("tenant_id"),
    }


async def stub_dev_approve(
    opc_home: Any,
    settings: dict | None = None,
    *,
    request_id: str | None = None,
    device_id: str | None = None,
) -> dict:
    """桩：模拟远端服务器操作员审核通过。仅桩模式可用。"""
    if settings is None:
        settings = resolve_settings()
    if not settings.get("dev_approve_enabled"):
        return {"ok": False, "error": "dev_approve_disabled"}
    async with _REGISTRY_LOCK:
        data = _stub_load(opc_home)
        target: dict | None = None
        if device_id and device_id in data["devices"]:
            target = data["devices"][device_id]
        elif request_id:
            for d in data["devices"].values():
                if request_id in d.get("bind_requests", {}):
                    d["bind_requests"][request_id]["status"] = "active"
                    target = d
                    break
        if target is None:
            fp = collect_fingerprint()
            target = data["devices"].get(fp)
            if target is None:
                target = {
                    "device_id": fp,
                    "device_token": secrets.token_urlsafe(32),
                    "approval_status": "pending_approval",
                    "tenant_id": None,
                    "created_at": time.time(),
                    "bind_requests": {},
                }
                data["devices"][fp] = target
        target["approval_status"] = "active"
        target["tenant_id"] = target.get("tenant_id") or "tenant-default"
        _stub_save(opc_home, data)
        return {"ok": True, "deviceId": target["device_id"], "approvalStatus": "active"}


# ── Production proxy（客户端把请求转发到真正的远端授权服务器）────────────────

async def proxy_to_server(base_url: str, action: str, body: dict) -> dict:
    """把设备请求转发到远端授权服务器。客户端不裁决，只转发。"""
    import aiohttp  # 延迟导入，避免无网络环境下模块加载负担

    url = base_url.rstrip("/") + "/device/" + action
    async with aiohttp.request(
        "POST", url, json=body, timeout=aiohttp.ClientTimeout(total=10)
    ) as resp:
        text = await resp.text()
        if resp.status >= 400:
            raise RuntimeError(f"auth server {resp.status}: {text[:200]}")
        return json.loads(text)
