# -*- coding: utf-8 -*-
"""WeAuto License Guard（客户端侧，运行在用户机器的 EXE 内）。

设计边界（铁律，bot.py 不受 GPL-3.0 约束，按项目方指令接通 Creem）：
- 本文件**只与本项目自有的 Cloudflare Worker 通信**，绝不直接请求 api.creem.io。
- 本文件**绝不持有任何 Creem 密钥**（API key / webhook secret 只在 Worker 侧）。
- 客户端仅持有两样东西：
    1) 用户购买的 license key（来自 config.py 的 CREEM_LICENSE_KEY，或环境变量）
    2) 本机机器指纹（由 get_machine_id 生成，不含 PII，仅用于设备配额）

流程：
- 首次：调用 Worker /activate（key + machine_id）→ 缓存 instance_id + 过期时间
- 之后：调用 Worker /validate（key + instance_id）→ 刷新缓存
- 离线宽限：联网失败时，若缓存未超 OFFLINE_GRACE_SECONDS 仍放行，避免断网即停用
"""
import os
import sys
import json
import time
import hmac
import hashlib
import uuid
import platform
import threading
import urllib.request
import urllib.error

try:
    import config as _cfg_mod  # 项目根目录的 config.py
except Exception:
    _cfg_mod = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- 发行版固杀标记（反破解第一道） ----
# CI 构建正式 EXE 前会生成 weauto_license/_release.py（内容 RELEASE = True）。
# 发行版里门禁**强制开启**：即使用户把 config.py 的 LICENSE_GUARD_ENABLED 改成
# False、甚至删掉这行，也无法关掉校验。本地开发无该文件 = 开发态，不受影响。
try:
    from weauto_license import _release as _release_mod
    RELEASE_BUILD = bool(getattr(_release_mod, "RELEASE", False))
except Exception:
    RELEASE_BUILD = os.environ.get("WEAUTO_RELEASE_BUILD", "") == "1"

# 激活缓存签名盐（配合 _release 注入；防手写 APPDATA 里的 JSON 伪造激活记录）
_CACHE_SIG_SALT = "weauto-license-cache-v1"


def _cache_file() -> str:
    """激活缓存路径。

    冻结态（onefile）下 __file__ 位于 sys._MEIPASS 临时目录、进程退出即被清理，
    若把缓存写在那里会导致离线宽限跨运行失效。故冻结态改用用户级应用数据目录，
    保证激活记录持久；开发态（未冻结）写在项目根目录，便于排查。
    """
    if getattr(sys, "frozen", False):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, "WeAuto")
    else:
        d = ROOT
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        d = ROOT
    return os.path.join(d, ".weauto_license.json")


CACHE_FILE = _cache_file()
OFFLINE_GRACE_SECONDS = int(os.environ.get("WEAUTO_LICENSE_GRACE", 7 * 86400))


def _cfg(name, default=None):
    """配置优先级：环境变量 WEAUTO_<NAME> > config.py 的 <NAME> > default。"""
    env_val = os.environ.get("WEAUTO_" + name)
    if env_val is not None:
        return env_val
    return getattr(_cfg_mod, name, default) if _cfg_mod else default


def guard_enabled() -> bool:
    """门禁开关。发行版（_release.py 存在）下**恒为 True**，config 关不掉。"""
    if RELEASE_BUILD:
        return True
    return str(_cfg("LICENSE_GUARD_ENABLED", "False")).lower() in ("1", "true", "yes", "on")


def worker_url() -> str:
    return (_cfg("CREEM_WORKER_URL", "") or "").rstrip("/")


def get_machine_id() -> str:
    """稳定的机器指纹（不含 PII，仅用于设备配额）。"""
    parts = []
    try:
        parts.append(platform.node())
    except Exception:
        pass
    try:
        parts.append(str(uuid.getnode()))  # MAC 派生，重启/换网卡会变，作为辅助因子
    except Exception:
        pass
    try:
        if sys.platform.startswith("win"):
            import subprocess
            out = subprocess.run(
                ["wmic", "csproduct", "get", "uuid"],
                capture_output=True, text=True, timeout=5,
            )
            for ln in out.stdout.splitlines():
                ln = ln.strip()
                if ln and ln.lower() != "uuid":
                    parts.append(ln)
                    break
    except Exception:
        pass
    if not parts:
        parts.append("fallback-" + str(os.getpid()))
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:32]


def _cache_sig_material(key: str, machine: str, body: dict) -> bytes:
    blob = json.dumps(body, sort_keys=True, ensure_ascii=False)
    mac_key = hashlib.sha256(
        "|".join([key or "", machine or "", _CACHE_SIG_SALT]).encode("utf-8")
    ).digest()
    return hmac.new(mac_key, blob.encode("utf-8"), hashlib.sha256).hexdigest()


def _read_cache() -> dict:
    """读激活缓存。带 HMAC 校验：签名不符/缺签名（旧版或手写伪造）一律视为空。"""
    try:
        with open(CACHE_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
    except Exception:
        return {}
    if not isinstance(d, dict):
        return {}
    sig = d.pop("sig", None)
    key = d.get("key")
    if not (key and sig):
        return {}
    try:
        want = _cache_sig_material(key, d.get("machine") or get_machine_id(), d)
        if not hmac.compare_digest(sig, want):
            return {}
    except Exception:
        return {}
    return d


def _write_cache(d: dict) -> None:
    """写激活缓存并附 HMAC 签名（key + machine 绑定，换机/篡改即失效）。"""
    try:
        body = {k: v for k, v in d.items() if k != "sig"}
        body["machine"] = get_machine_id()
        body["sig"] = _cache_sig_material(body.get("key"), body["machine"], body)
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(body, f)
    except Exception:
        pass


def _json_body(r) -> dict:
    """从响应/HTTPError 里尽力解析 JSON；非 JSON 或空正文返回带 ok:False 的占位。"""
    try:
        raw = r.read().decode("utf-8", "replace")
    except Exception:
        return {"ok": False, "reason": "unreadable_response"}
    try:
        d = json.loads(raw)
    except Exception:
        return {"ok": False, "reason": "bad_response"}
    return d if isinstance(d, dict) else {"ok": False, "reason": "bad_response"}


def _post(path: str, payload: dict):
    """POST JSON 到 Worker，返回 (resp_dict, http_status)。

    - 2xx：正常解析 JSON
    - 4xx/5xx：urllib 会抛 HTTPError，这里归一化为 (dict, status) 而**不再抛出**，
      这样调用方才能区分「服务端临时故障 5xx」与「卡密真的无效 4xx/ok:false」；
      否则 5xx 混进网络异常分支，服务端抖一下就可能把合法用户判成未授权。
    - 网络层异常（DNS/超时/连接重置）照旧抛出，交给上层离线宽限处理。
    """
    url = worker_url() + path
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return _json_body(r), int(getattr(r, "status", 0) or 0)
    except urllib.error.HTTPError as e:
        return _json_body(e), int(e.code or 0)


def _buy_prompt(reason):
    # flush=True：冻结态(onefile) stdout 为 block-buffering，不 flush 用户看不到提示
    print("=" * 56, flush=True)
    print("  WeAuto 未激活 / 激活失效", flush=True)
    print("  原因: " + str(reason), flush=True)
    w = worker_url()
    print("  购买: " + (w + "/buy" if w else "(未配置 CREEM_WORKER_URL)"), flush=True)
    print("  然后: 在 config.py 填入 CREEM_LICENSE_KEY 后重启", flush=True)
    print("=" * 56, flush=True)


def ensure_license(strict: bool = True) -> bool:
    """门禁主函数。返回 True 放行，False 拒绝。

    strict=False 时：即便校验失败也放行（仅用于开发/异常兜底，避免门禁 bug 卡死主程序）。
    正式发布请把 LICENSE_GUARD_ENABLED=True 并保持 strict 默认。
    """
    if not guard_enabled():
        return True
    w = worker_url()
    if not w:
        if strict:
            _buy_prompt("未配置 CREEM_WORKER_URL")
            return False
        return True
    key = _cfg("CREEM_LICENSE_KEY", "")
    if not key:
        _buy_prompt("未配置 CREEM_LICENSE_KEY")
        return False

    cache = _read_cache()
    now = time.time()
    machine = get_machine_id()

    # 离线宽限：缓存有效且未超 grace 期 → 直接放行，不联网
    if (cache.get("key") == key and cache.get("instance_id")
            and now < cache.get("expire_at", 0) + OFFLINE_GRACE_SECONDS):
        return True

    try:
        if cache.get("key") == key and cache.get("instance_id"):
            resp, http_status = _post("/validate", {"key": key, "instance_id": cache["instance_id"]})
        else:
            resp, http_status = _post("/activate", {"key": key, "instance_name": machine})
        if resp.get("ok"):
            _write_cache({
                "key": key,
                "instance_id": resp.get("instance_id"),
                "expire_at": resp.get("expires_at") or (now + 30 * 86400),
                "tier": resp.get("tier"),
                "valid_days": resp.get("valid_days") or 30,
            })
            return True
        # 5xx：Worker / Creem 临时故障，不等于卡密失效。本地有同一 key 的历史成功
        # 记录时按「服务暂时不可用、按最近缓存放行」处理，避免服务端抖一下就踢掉合法用户。
        if 500 <= http_status < 600 and cache.get("key") == key and cache.get("instance_id"):
            print("[License] 授权服务暂时不可用（HTTP %d），按本机最近一次成功记录放行。"
                  % http_status, flush=True)
            return True
        _buy_prompt(resp.get("reason", "未知原因"))
        return False
    except Exception as e:
        # 联网失败：缓存仍在 grace 期内 → 离线放行
        if (cache.get("key") == key and cache.get("instance_id")
                and now < cache.get("expire_at", 0) + OFFLINE_GRACE_SECONDS):
            print("[License] 联网校验失败（%s），离线宽限内放行。" % e, flush=True)
            return True
        _buy_prompt("联网校验失败: %s" % e)
        return False


def activate(license_key: str):
    """手动激活（CLI 子命令用）：写缓存并返回结果，提示用户把 key 存进 config.py。"""
    try:
        resp, _status = _post("/activate", {"key": license_key, "instance_name": get_machine_id()})
        if resp.get("ok"):
            _write_cache({
                "key": license_key,
                "instance_id": resp.get("instance_id"),
                "expire_at": resp.get("expires_at") or (time.time() + 30 * 86400),
                "tier": resp.get("tier"),
                "valid_days": resp.get("valid_days") or 30,
            })
            return True, "激活成功（请把该 key 写入 config.py 的 CREEM_LICENSE_KEY 以持久化）"
        return False, "激活失败: " + str(resp.get("reason", ""))
    except Exception as e:
        return False, "激活异常: %s" % e


def deactivate(license_key: str = None):
    """释放本机激活（换机/退订前调用）。"""
    cache = _read_cache()
    key = license_key or cache.get("key")
    inst = cache.get("instance_id")
    if not (key and inst):
        return False, "本地无激活记录"
    try:
        resp, _status = _post("/deactivate", {"key": key, "instance_id": inst})
        if resp.get("ok"):
            try:
                os.remove(CACHE_FILE)
            except Exception:
                pass
            return True, "已释放本机激活"
        return False, "释放失败: " + str(resp.get("reason", ""))
    except Exception as e:
        return False, "释放异常: %s" % e


def license_tier() -> str:
    """返回当前激活档位（normal/premium/lifetime），未激活或无法解析返回空串。"""
    try:
        return (_read_cache().get("tier") or "").strip()
    except Exception:
        return ""


def ai_endpoint():
    """LLM 统一走自有 Worker 代理时的端点信息；门禁未启用返回 None（走本地 config）。

    返回 {"base_url", "api_key", "headers"}：
      - base_url: <worker>/ai/v1 —— Worker 验完卡密后透传到真正的 upstream，
                  并注入商家持有的 AI_UPSTREAM_KEY；客户端永远拿不到真实 key，
                  反编译/patch 门禁也白嫖不了（无有效卡密 Worker 直接 401）。
      - api_key:  用户的 license key（作为 Bearer 凭证传给 Worker 验证）
      - headers:  X-WeAuto-Instance（实例绑定，供 Worker 做设备级校验）
    """
    if not guard_enabled():
        return None
    w = worker_url()
    if not w:
        return None
    inst = ""
    try:
        inst = _read_cache().get("instance_id") or ""
    except Exception:
        pass
    key = _cfg("CREEM_LICENSE_KEY", "") or ""
    return {
        "base_url": w + "/ai/v1",
        "api_key": key or "license-pending",
        "headers": {"X-WeAuto-Instance": inst} if inst else {},
    }


def start_periodic_recheck(interval_seconds=None, on_invalid=None, on_near_expiry=None, near_expiry_days=5):
    """运行期周期复检线程（防『只堵启动一处』：patch 掉启动检查也逃不过运行中复检）。

    - 网络异常/服务端异常：静默跳过（走离线宽限），不打扰运行中的 bot
    - 显式失效（key 无效/实例被释放/宽限期已过）：调用 on_invalid(reason)
      bot.py 的回调会用 os._exit(1) —— 线程里必须 os._exit，sys.exit 只杀线程
    - 门禁未启用（开发态）时线程空转，零开销
    """
    def _loop():
        iv = interval_seconds or int(os.environ.get("WEAUTO_LICENSE_RECHECK_SECONDS", 6 * 3600))
        last_reminded = [0.0]
        while True:
            time.sleep(iv)
            try:
                if not guard_enabled() or not on_invalid:
                    continue
                key = _cfg("CREEM_LICENSE_KEY", "") or ""
                if not key:
                    on_invalid("卡密被清空")
                    continue
                cache = _read_cache()
                now = time.time()
                inst = cache.get("instance_id")
                # 临近到期提醒（软件自定义「每月提示续费」）：离线也能算，不依赖联网
                if on_near_expiry and inst and key:
                    try:
                        ea = cache.get("expire_at") or 0
                        if ea:
                            dleft = (ea - now) / 86400.0
                            if 0 < dleft <= near_expiry_days and now - last_reminded[0] > 86400:
                                last_reminded[0] = now
                                on_near_expiry(int(round(dleft)), cache.get("tier") or "")
                    except Exception:
                        pass
                if cache.get("key") == key and inst:
                    if now >= cache.get("expire_at", 0) + OFFLINE_GRACE_SECONDS:
                        on_invalid("离线宽限期已过且未通过校验")
                        continue
                    try:
                        resp, http_status = _post("/validate", {"key": key, "instance_id": inst})
                    except Exception:
                        continue  # 网络异常 → 离线宽限
                    if 500 <= http_status < 600:
                        continue  # 服务端临时故障 → 离线宽限，不能据此判定卡密失效
                    if resp.get("ok"):
                        _write_cache({"key": key, "instance_id": inst,
                                      "expire_at": resp.get("expires_at") or (now + 30 * 86400),
                                      "tier": resp.get("tier"),
                                      "valid_days": resp.get("valid_days") or 30})
                    else:
                        on_invalid(str(resp.get("reason", "校验未通过")))
                # 无 instance 记录 → 运行期不判定，交给下次启动的 ensure_license
            except Exception:
                pass
    t = threading.Thread(target=_loop, name="weauto-license-recheck", daemon=True)
    t.start()
    return t
