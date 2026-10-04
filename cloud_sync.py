# -*- coding: utf-8 -*-
"""设备档案 / 套餐 / AI 分身人格 —— 与 Cloudflare Worker 的云同步。

为什么要有这个
--------------
`device_id.py` 保证的是「本机卸载重装不丢」（数据目录在安装目录之外 + 硬件可重算）。
但它扛不住**换机**、也做不到**跨端互通**。本模块把三样东西上云：

    1. 设备 ID         —— 同一台机器重装后仍认得出来
    2. 套餐（tier）    —— 卡密/账户令牌校验通过后由服务端权威写入，重装可直接恢复
    3. AI 分身人格     —— 以「微信身份键 wxKey」为归属键，Windows 与 Android 互通

归属键（跨端互通的核心）
------------------------
    wxKey（wx_<hex>，wxid 的单向哈希）  >  deviceId（dev_<hex>）

同一个微信号在 Windows(EXE) 和 Android(APK) 上登录，两端算出同一个 wxKey，
于是读到的是同一份人格档案 —— 这就是「人格跨端互通」。
拿不到微信身份时退化为设备归属，功能照常，只是不跨端。

与 Android 端（jev-chat-jarvis）的兼容性
----------------------------------------
Android 每个请求本来就带 `X-WeAuto-Instance`（设备 ID）+ `Authorization: Bearer <账户令牌>`，
Worker 侧 `/device/*` 直接复用这两个头 —— **Android 端零改动即可接入本套接口**。
Android 若要参与人格互通，只需在 body 里多带一个 `wxKey`（算法见 device_id.hash_wxid）。

失败即降级
----------
所有网络失败都吞掉并写进本地状态，绝不影响 bot 运行；人格本体始终以本地文件为准，
云端只是同步层 —— 断网、Worker 挂了、没买套餐，软件照常能用。

对外接口
--------
    enabled()          -> bool  是否开启（config.DEVICE_SYNC_ENABLED，默认开）
    status()           -> dict  给 UI 展示（离线也返回，含上次同步时间与错误）
    sync(reason="")    -> dict  双向同步（hello -> 比 rev -> 推或拉）
    push(force=False)  -> dict  强制上传本地人格
    pull(force=False)  -> dict  强制下载云端人格（覆盖本地）
    sync_plan()        -> dict  把卡密对应的套餐刷到云端
    start_background(interval=900)  起后台线程定时同步
"""

import hashlib
import hmac
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import device_id
except Exception:  # noqa: BLE001
    device_id = None

try:
    import persona
except Exception:  # noqa: BLE001
    persona = None

# Cloudflare WAF 会把 Python-urllib 默认 UA 挡成 403（实测），必须带浏览器 UA。
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

PLATFORM = "windows-exe"
APP_VER = "3.25.1"

_TIMEOUT = 12
_STATE_NAME = "cloud_sync.json"

_lock = threading.Lock()
_thread = None


# ------------------------------------------------------------------ 配置读取
def _cfg(name, default=None):
    """延迟读 config（不能静态 import，否则改配置不生效）。"""
    env_val = os.environ.get("WEAUTO_" + name)
    if env_val is not None:
        return env_val
    try:
        import config as _c
        return getattr(_c, name, default)
    except Exception:  # noqa: BLE001
        return default


def enabled():
    v = str(_cfg("DEVICE_SYNC_ENABLED", "True")).strip().lower()
    return v in ("1", "true", "yes", "on")


def worker_url():
    """优先复用 weauto_license 的网关地址，保证与卡密体系同一个域名。"""
    try:
        from weauto_license import guard
        u = (guard.worker_url() or "").strip()
        if u:
            return u
    except Exception:  # noqa: BLE001
        pass
    return str(_cfg("CREEM_WORKER_URL", "") or "").strip().rstrip("/")


def _client_secret():
    return str(_cfg("WEAUATO_CLIENT_SECRET", "") or "").strip()


def _license_credential():
    """取本地已激活的卡密与激活实例（用于让服务端权威写入套餐）。

    注意：Creem 的 instance_id 是**激活时**用的机器指纹（guard.get_machine_id()），
    不是本模块的 device_id —— 两者混用会导致 validate 失败。
    """
    try:
        from weauto_license import guard
        cache = guard._read_cache() or {}
        key = str(cache.get("key") or "").strip()
        inst = str(cache.get("instance_id") or "").strip()
        if key and inst:
            return key, inst
        key = str(_cfg("CREEM_LICENSE_KEY", "") or "").strip()
        if key:
            return key, str(guard.get_machine_id())
    except Exception:  # noqa: BLE001
        pass
    return "", ""


# ------------------------------------------------------------------ 本地状态
def _state_path():
    base = device_id.stable_data_dir() if device_id else os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, _STATE_NAME)


def _read_state():
    try:
        with open(_state_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict):
            return d
    except Exception:  # noqa: BLE001
        pass
    return {}


def _write_state(d):
    try:
        p = _state_path()
        tmp = p + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
        os.replace(tmp, p)
    except Exception:  # noqa: BLE001
        pass


def _merge_state(**kw):
    st = _read_state()
    st.update(kw)
    _write_state(st)
    return st


# ------------------------------------------------------------------ 签名/请求
def _sign(secret, method, path, ts, nonce, body_bytes):
    """与 Worker deviceAuth ③ 完全一致的 HMAC-SHA256。

    msg = "<METHOD>\\n<path>\\n<ts>\\n<nonce>\\n" + body
    """
    msg = (method + "\n" + path + "\n" + ts + "\n" + nonce + "\n").encode("utf-8") + body_bytes
    return hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


def _request(method, path, payload=None, extra_headers=None, timeout=None):
    """打 Worker。返回 (ok: bool, data: dict|None, err: str)。"""
    base = worker_url()
    if not base:
        return False, None, "未配置云端地址（CREEM_WORKER_URL）"
    url = base + path
    body = b""
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json",
        "User-Agent": _UA,
        "X-WeAuto-Instance": (device_id.get_device_id() if device_id else ""),
    }
    # 凭证：① 卡密（服务端可据此权威写入套餐）
    lk, li = _license_credential()
    if lk:
        headers["X-WeAuto-Key"] = lk
    # 凭证：③ HMAC 签名（没卡密也能用）
    secret = _client_secret()
    if secret:
        try:
            ts = str(int(time.time()))
            nonce = secrets.token_hex(16)
            p = urllib.parse.urlparse(url).path or path
            headers["X-WeAuto-Ts"] = ts
            headers["X-WeAuto-Nonce"] = nonce
            headers["X-WeAuto-Sig"] = _sign(secret, method, p, ts, nonce, body)
        except Exception:  # noqa: BLE001
            pass
    if extra_headers:
        headers.update(extra_headers)

    req = urllib.request.Request(url, data=body if body else None, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout or _TIMEOUT) as r:
            raw = r.read().decode("utf-8", "replace")
        try:
            return True, json.loads(raw), ""
        except Exception:  # noqa: BLE001
            return True, {"raw": raw[:500]}, ""
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001
            detail = ""
        return False, None, f"HTTP {e.code} {detail}"
    except Exception as e:  # noqa: BLE001
        return False, None, f"{type(e).__name__}: {e}"


# ------------------------------------------------------------------ 人格载荷
def _persona_payload():
    if persona is None:
        return None
    d = persona.load()
    return {
        "identity": d.get("identity", {}),
        "settings": d.get("settings", {}),
        "styleText": persona._style_text(),
    }


def _local_rev():
    if persona is None:
        return 0
    return int(float(persona.load().get("updated_at", 0) or 0) * 1000)


def _apply_remote_persona(p):
    """把云端人格写回本地。返回 (ok, message)。"""
    if persona is None or not isinstance(p, dict):
        return False, "人格模块不可用或数据非法"
    d = persona.load()
    ident = p.get("identity") or {}
    for k in persona.DEFAULT["identity"]:
        d["identity"][k] = str(ident.get(k) or "").strip()
    st = p.get("settings") or {}
    d["settings"]["enabled"] = bool(st.get("enabled"))
    for k in ("personaPrompt", "chatPurpose"):
        d["settings"][k] = str(st.get(k) or "").strip()
    d["remote_style"] = str(p.get("styleText") or "")
    # 保留云端版本号，避免刚拉下来又被判定为本地更新
    rev = int(float(p.get("updatedAt") or 0))
    persona.save(d)
    if rev:
        d["updated_at"] = rev / 1000.0
        persona.save(d)
    return True, "已用云端人格覆盖本地"


# ------------------------------------------------------------------ 对外动作
def hello():
    """上报设备心跳并拉回云端档案（含套餐与人格版本号）。"""
    if not enabled():
        return {"ok": False, "skipped": True, "error": "云同步已关闭"}
    lk, li = _license_credential()
    payload = {
        "deviceId": (device_id.get_device_id() if device_id else ""),
        "wxKey": (device_id.wx_key() if device_id else "") or "",
        "platform": PLATFORM,
        "appVer": APP_VER,
    }
    if lk:
        payload["licenseKey"] = lk
        payload["licenseInstanceId"] = li
    ok, data, err = _request("POST", "/device/hello", payload)
    now = time.time()
    if not ok:
        _merge_state(last_try=now, last_error=err)
        return {"ok": False, "error": err}
    _merge_state(last_sync=now, last_error="", server=data or {})
    return {"ok": True, "state": data or {}}


def push(force=False):
    """上传本地人格。服务端 rev 更新时拒绝（409），除非 force。"""
    if not enabled():
        return {"ok": False, "skipped": True, "error": "云同步已关闭"}
    payload = {
        "deviceId": (device_id.get_device_id() if device_id else ""),
        "wxKey": (device_id.wx_key() if device_id else "") or "",
        "platform": PLATFORM,
        "appVer": APP_VER,
        "persona": _persona_payload(),
        "rev": _local_rev(),
    }
    if force:
        payload["rev"] = int(time.time() * 1000)
    ok, data, err = _request("PUT", "/device/persona", payload)
    if not ok:
        _merge_state(last_try=time.time(), last_error=err)
        return {"ok": False, "error": err}
    _merge_state(last_sync=time.time(), last_error="", last_rev=(data or {}).get("personaRev", 0))
    return {"ok": True, "rev": (data or {}).get("personaRev", 0)}


def pull(force=False):
    """下载云端人格并覆盖本地。"""
    if not enabled():
        return {"ok": False, "skipped": True, "error": "云同步已关闭"}
    wxk = (device_id.wx_key() if device_id else "") or ""
    dev = (device_id.get_device_id() if device_id else "")
    qs = "?" + urllib.parse.urlencode({"wx": wxk, "device": dev})
    ok, data, err = _request("GET", "/device/persona" + qs)
    if not ok:
        _merge_state(last_try=time.time(), last_error=err)
        return {"ok": False, "error": err}
    p = (data or {}).get("persona")
    if not p:
        _merge_state(last_sync=time.time(), last_error="")
        return {"ok": True, "applied": False, "message": "云端还没有人格档案"}
    # 云端比本地旧就不覆盖（除非 force）
    remote_rev = int(float((data or {}).get("personaRev") or 0))
    if (not force) and remote_rev and remote_rev < _local_rev():
        _merge_state(last_sync=time.time(), last_error="")
        return {"ok": True, "applied": False, "message": "本地人格更新，已保留本地"}
    ok2, msg = _apply_remote_persona(p)
    _merge_state(last_sync=time.time(), last_error="" if ok2 else msg, last_rev=remote_rev)
    return {"ok": ok2, "applied": ok2, "message": msg, "rev": remote_rev}


def sync(reason=""):
    """双向同步：先 hello 拿服务端版本号，再决定推还是拉。

    - 本地比云端新  -> 上传
    - 云端比本地新  -> 下载（换机/重装恢复就走这条）
    - 一样新        -> 只更新心跳
    """
    if not enabled():
        return {"ok": False, "skipped": True, "error": "云同步已关闭", "reason": reason}
    h = hello()
    if not h.get("ok"):
        return dict(h, reason=reason)
    state = h.get("state") or {}
    remote_rev = int(float(state.get("personaRev") or 0))
    local_rev = _local_rev()
    action = "up-to-date"
    detail = ""
    if remote_rev and remote_rev > local_rev + 1000:
        r = pull()
        action = "pull" if r.get("ok") else "pull-failed"
        detail = r.get("message") or r.get("error") or ""
    elif local_rev and (not remote_rev or local_rev > remote_rev + 1000):
        r = push()
        action = "push" if r.get("ok") else "push-failed"
        detail = r.get("error") or ""
    _merge_state(last_action=action, last_reason=reason)
    return {"ok": True, "action": action, "detail": detail,
            "local_rev": local_rev, "remote_rev": remote_rev, "state": state, "reason": reason}


def sync_plan():
    """把卡密对应的套餐刷到云端（服务端现验 Creem，客户端说了不算）。"""
    if not enabled():
        return {"ok": False, "skipped": True, "error": "云同步已关闭"}
    lk, li = _license_credential()
    if not lk:
        return {"ok": False, "error": "本机没有已激活的卡密"}
    payload = {
        "deviceId": (device_id.get_device_id() if device_id else ""),
        "wxKey": (device_id.wx_key() if device_id else "") or "",
        "platform": PLATFORM,
        "licenseKey": lk,
        "licenseInstanceId": li,
    }
    ok, data, err = _request("PUT", "/device/plan", payload)
    if not ok:
        return {"ok": False, "error": err}
    return {"ok": True, "tier": (data or {}).get("tier", ""), "plan": (data or {}).get("plan")}


# ------------------------------------------------------------------ UI 状态
def status():
    """给 /avatar 页面展示。断网也返回（带上次同步时间与最近错误）。"""
    st = _read_state()
    server = st.get("server") or {}

    def _fmt(ts):
        try:
            ts = float(ts or 0)
        except Exception:  # noqa: BLE001
            return "从未"
        if ts <= 0:
            return "从未"
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))

    dev = device_id.device_info() if device_id else {}
    wx = device_id.wx_identity_info() if device_id else {}
    return {
        "last_sync_str": _fmt(st.get("last_sync", 0)),
        "last_try_str": _fmt(st.get("last_try", 0)),
        "enabled": enabled(),
        "worker": worker_url(),
        "device_id": dev.get("device_id", ""),
        "data_dir": dev.get("data_dir", ""),
        "wx_key": wx.get("wx_key", ""),
        "wx_nickname": wx.get("nickname", ""),
        "wx_bound": bool(wx.get("bound")),
        "owner_scope": ("微信身份（跨端互通）" if wx.get("bound") else "设备 ID（仅本机）"),
        "tier": server.get("tier", ""),
        "plan": server.get("plan"),
        "persona_rev": server.get("personaRev", 0),
        "persona_source": server.get("personaSource", ""),
        "devices": server.get("devices", []),
        "last_sync": st.get("last_sync", 0),
        "last_try": st.get("last_try", 0),
        "last_action": st.get("last_action", ""),
        "last_error": st.get("last_error", ""),
        "local_rev": _local_rev(),
    }


# ------------------------------------------------------------------ 后台线程
def start_background(interval=900, logger=None):
    """定时同步（默认 15 分钟一次）。失败不影响任何业务逻辑。"""
    global _thread
    if not enabled():
        return False
    with _lock:
        if _thread is not None and _thread.is_alive():
            return True

        def _loop():
            while True:
                try:
                    time.sleep(max(60, int(interval)))
                except Exception:  # noqa: BLE001
                    return
                try:
                    r = sync(reason="auto")
                    if logger and not r.get("ok"):
                        logger.debug(f"人格云同步失败（已忽略）: {r.get('error')}")
                except Exception as e:  # noqa: BLE001
                    if logger:
                        logger.debug(f"人格云同步异常（已忽略）: {e}")

        _thread = threading.Thread(target=_loop, name="cloud-sync", daemon=True)
        _thread.start()
        return True


if __name__ == "__main__":
    print("=== 云同步自检 ===")
    s = status()
    for k in ("enabled", "worker", "device_id", "wx_key", "wx_bound", "owner_scope", "last_error"):
        print(f"{k:14}: {s.get(k)}")
    print("--- sync ---")
    print(json.dumps(sync(reason="self-test"), ensure_ascii=False)[:800])
