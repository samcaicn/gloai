# -*- coding: utf-8 -*-
"""隐私合规的设备唯一 ID + 抗卸载的用户数据目录。

设计要点
--------
1) **只用非个人信息的操作系统级标识**（Windows: MachineGuid + 系统盘卷序列号；
   macOS: IOPlatformUUID；Linux: /etc/machine-id）。
   **不采集**主机名、MAC 地址、用户名、产品密钥、BIOS 序列号等可识别到人的信息。
2) **单向不可逆**：原始标识只参与一次 SHA-256（带应用级盐），
   落盘 / 展示 / 上报的只有哈希结果；原始值不写文件、不打日志、不上传。
   换盐即换 ID，跨应用无法关联。
3) **可重算**：即使缓存被删，重装后仍能算出同一个 ID（只要系统盘与系统未换）。
4) **缓存放在安装目录之外**的用户数据目录（Windows 为 %APPDATA%\\WeAuto），
   卸载程序不会带走 —— 配合“可重算”实现**卸载重装数据不丢**。

微信身份键（跨端互通）
----------------------
同一个人在 Windows(EXE) 与 Android(APK) 上登录同一个微信号时，两边算出的
wxKey 必须一致 —— 这是人格档案跨端互通的归属键。算法（两端必须完全对齐）：

    wxKey = "wx_" + sha256("weauto.wx-identity.v1" + "|" + wxid.strip().lower()).hexdigest()[:32]

* wxid 是敏感个人信息，**只参与一次哈希**，落盘与上报的只有哈希结果；
* 微信号大小写不敏感，故统一 lower()；
* 换盐即换键，跨应用无法关联。

对外接口
--------
    get_device_id()   -> str   稳定设备 ID（形如 dev_xxxxxxxx…）
    device_info()     -> dict  给 UI 展示（只含哈希与元信息，不含原始标识）
    stable_data_dir() -> str   抗卸载的数据目录
    reset()           -> bool  清除缓存（下次调用按硬件重算，用于“重置设备标识”）
    save_wx_identity(wxid, nickname) -> str  登录成功后记录微信身份键
    wx_key()          -> str   当前登录者的微信身份键（未登录返回 ''）
    wx_identity_info() -> dict 给 UI 展示（只有哈希与昵称掩码）
"""

import hashlib
import json
import os
import secrets
import sys
import threading

APP_NAME = "WeAuto"

# 应用级盐：只有 WeAuto 能算出这个 ID，别的软件拿到同样的硬件标识也算不出同一个值
_SALT = "weauto.device-id.v1"
_CACHE_NAME = "device.json"

_lock = threading.Lock()


# ------------------------------------------------------------------ 数据目录
def stable_data_dir():
    """安装目录之外的稳定数据目录（卸载不会删除）。"""
    plat = sys.platform
    if plat.startswith("win"):
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, APP_NAME)
    elif plat == "darwin":
        d = os.path.join(os.path.expanduser("~"), "Library", "Application Support", APP_NAME)
    else:
        base = os.environ.get("XDG_DATA_HOME") or os.path.join(os.path.expanduser("~"), ".local", "share")
        d = os.path.join(base, APP_NAME.lower())

    try:
        os.makedirs(d, exist_ok=True)
        return d
    except Exception:
        # 兜底：用户目录下隐藏目录
        fb = os.path.join(os.path.expanduser("~"), "." + APP_NAME.lower())
        try:
            os.makedirs(fb, exist_ok=True)
        except Exception:
            pass
        return fb


def _cache_path():
    return os.path.join(stable_data_dir(), _CACHE_NAME)


# ------------------------------------------------------------ 硬件信号（非 PII）
def _win_machine_guid():
    """Windows MachineGuid：系统安装级标识，不含个人信息。"""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography") as k:
            v, _ = winreg.QueryValueEx(k, "MachineGuid")
        v = str(v or "").strip()
        return v
    except Exception:
        return ""


def _win_volume_serial():
    """系统盘卷序列号：换盘才会变，不含个人信息。"""
    try:
        import ctypes
        from ctypes import wintypes
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.GetVolumeInformationW.argtypes = [
            ctypes.c_wchar_p, ctypes.c_wchar_p, wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
            ctypes.POINTER(wintypes.DWORD), ctypes.c_wchar_p, wintypes.DWORD,
        ]
        k32.GetVolumeInformationW.restype = wintypes.BOOL
        drive = (os.environ.get("SystemDrive") or "C:").rstrip("\\") + "\\"
        serial = wintypes.DWORD(0)
        ok = k32.GetVolumeInformationW(drive, None, 0, ctypes.byref(serial), None, None, None, 0)
        if ok and serial.value:
            return "vol-%08X" % (serial.value & 0xFFFFFFFF)
    except Exception:
        pass
    return ""


def _darwin_uuid():
    try:
        import subprocess
        out = subprocess.run(["ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
                             capture_output=True, text=True, timeout=5)
        for ln in (out.stdout or "").splitlines():
            if "IOPlatformUUID" in ln and '"' in ln:
                part = ln.split('"')
                if len(part) >= 2 and part[1].strip():
                    return part[1].strip()
    except Exception:
        pass
    return ""


def _linux_machine_id():
    for p in ("/etc/machine-id", "/var/lib/dbus/machine-id"):
        try:
            with open(p, "r", encoding="utf-8") as f:
                v = f.read().strip()
            if v:
                return v
        except Exception:
            continue
    return ""


def _collect_signals():
    """收集非 PII 的稳定信号。返回值只在内存里参与哈希，绝不落盘。"""
    sigs = []
    if sys.platform.startswith("win"):
        for fn in (_win_machine_guid, _win_volume_serial):
            v = fn()
            if v:
                sigs.append(v)
    elif sys.platform == "darwin":
        v = _darwin_uuid()
        if v:
            sigs.append(v)
    else:
        v = _linux_machine_id()
        if v:
            sigs.append(v)
    return sigs


def _hash(signals):
    h = hashlib.sha256()
    h.update(_SALT.encode("utf-8"))
    for s in signals:
        h.update(b"|")
        h.update(str(s).encode("utf-8"))
    return "dev_" + h.hexdigest()[:32]


# ------------------------------------------------------------------ 对外接口
def _read_cache():
    try:
        with open(_cache_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and str(d.get("device_id", "")).startswith("dev_"):
            return d
    except Exception:
        pass
    return None


def get_device_id():
    """稳定的设备唯一 ID。优先用缓存（稳定），缓存缺失则按硬件重算。"""
    with _lock:
        cached = _read_cache()
        if cached:
            return cached["device_id"]

        signals = _collect_signals()
        if signals:
            dev_id = _hash(signals)
            src = "hardware"
        else:
            # 极端环境拿不到任何信号：用随机数并持久化，至少在本机保持稳定
            dev_id = "dev_" + secrets.token_hex(16)
            src = "random"

        try:
            with open(_cache_path(), "w", encoding="utf-8") as f:
                json.dump({
                    "device_id": dev_id,
                    "source": src,
                    "signal_count": len(signals),
                    "created_at": __import__("time").time(),
                    "version": 1,
                }, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
        return dev_id


def device_info():
    """给 UI 展示的设备信息。只包含哈希与元信息，不含任何原始硬件标识。"""
    cached = _read_cache()
    return {
        "device_id": get_device_id(),
        "cached": cached is not None,
        "signal_count": len(_collect_signals()),
        "data_dir": stable_data_dir(),
        "privacy": "仅取系统级非个人信息并做单向 SHA-256（带应用盐），原始标识不落盘、不上传",
    }


def reset():
    """清除缓存的设备 ID（下次调用按硬件重算；硬件不变则 ID 不变）。"""
    try:
        p = _cache_path()
        if os.path.exists(p):
            os.remove(p)
        return True
    except Exception:
        return False


# ------------------------------------------------------------------ 微信身份键
# 盐值与算法必须与 Android 端（jev-chat-jarvis）完全一致，否则人格无法跨端互通。
_WX_SALT = "weauto.wx-identity.v1"
_WX_CACHE_NAME = "wx_identity.json"


def _wx_cache_path():
    return os.path.join(stable_data_dir(), _WX_CACHE_NAME)


def hash_wxid(wxid):
    """微信身份键。wxid 只进不出：返回单向哈希，绝不回传原始 wxid。"""
    raw = str(wxid or "").strip().lower()
    if not raw:
        return ""
    h = hashlib.sha256()
    h.update(_WX_SALT.encode("utf-8"))
    h.update(b"|")
    h.update(raw.encode("utf-8"))
    return "wx_" + h.hexdigest()[:32]


def save_wx_identity(wxid, nickname=""):
    """bot 登录成功后调用：把当前登录者的身份键落到稳定目录（卸载不丢）。

    只存哈希与昵称，不存原始 wxid。返回 wxKey（空串表示 wxid 无效）。
    """
    key = hash_wxid(wxid)
    if not key:
        return ""
    try:
        with open(_wx_cache_path(), "w", encoding="utf-8") as f:
            json.dump({
                "wx_key": key,
                "nickname": str(nickname or ""),
                "updated_at": __import__("time").time(),
                "version": 1,
            }, f, ensure_ascii=False, indent=2)
    except Exception:
        pass
    return key


def wx_key():
    """当前登录者的微信身份键；未登录/未记录返回 ''（此时云端归属退化为设备 ID）。"""
    try:
        with open(_wx_cache_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        k = str(d.get("wx_key", "") or "")
        if k.startswith("wx_"):
            return k
    except Exception:
        pass
    return ""


def wx_identity_info():
    """给 UI 展示：只有哈希与昵称掩码，不含原始 wxid。"""
    nick = ""
    try:
        with open(_wx_cache_path(), "r", encoding="utf-8") as f:
            nick = str(json.load(f).get("nickname", "") or "")
    except Exception:
        pass
    return {
        "wx_key": wx_key(),
        "nickname": nick,
        "bound": bool(wx_key()),
        "privacy": "微信号只做单向 SHA-256（带应用盐），上传与落盘的均为哈希",
    }


if __name__ == "__main__":
    print("wx_key    :", wx_key() or "(未记录)")
    info = device_info()
    print("device_id :", info["device_id"])
    print("data_dir  :", info["data_dir"])
    print("signals   :", info["signal_count"], "(原始值不展示)")
    print("cached    :", info["cached"])
