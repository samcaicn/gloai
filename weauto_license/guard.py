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
import hashlib
import uuid
import platform
import urllib.request
import urllib.error

try:
    import config as _cfg_mod  # 项目根目录的 config.py
except Exception:
    _cfg_mod = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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


def _read_cache() -> dict:
    try:
        with open(CACHE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _write_cache(d: dict) -> None:
    try:
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f)
    except Exception:
        pass


def _post(path: str, payload: dict) -> dict:
    url = worker_url() + path
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


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
            resp = _post("/validate", {"key": key, "instance_id": cache["instance_id"]})
        else:
            resp = _post("/activate", {"key": key, "instance_name": machine})
        if resp.get("ok"):
            _write_cache({
                "key": key,
                "instance_id": resp.get("instance_id"),
                "expire_at": resp.get("expires_at") or (now + 30 * 86400),
            })
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
        resp = _post("/activate", {"key": license_key, "instance_name": get_machine_id()})
        if resp.get("ok"):
            _write_cache({
                "key": license_key,
                "instance_id": resp.get("instance_id"),
                "expire_at": resp.get("expires_at") or (time.time() + 30 * 86400),
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
        resp = _post("/deactivate", {"key": key, "instance_id": inst})
        if resp.get("ok"):
            try:
                os.remove(CACHE_FILE)
            except Exception:
                pass
            return True, "已释放本机激活"
        return False, "释放失败: " + str(resp.get("reason", ""))
    except Exception as e:
        return False, "释放异常: %s" % e
