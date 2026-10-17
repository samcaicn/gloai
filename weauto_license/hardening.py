# -*- coding: utf-8 -*-
"""WeAuto 运行时防护（防反编译/防破解整改：反调试 + 完整性 + 反单点 patch）。

模块职责（全部纯标准库，绝不抛异常给上层）：
  - detect_debugger()      ：Windows 常见调试器/反编译器探测（IsDebuggerPresent /
                             CheckRemoteDebuggerPresent / NtQueryInformationProcess /
                             父进程名扫描 / sys trace）。
  - self_integrity_ok()    ：EXE 自体检——用同目录 WeAuto.exe.sig（Ed25519）验自身，
                            防「已安装 EXE 被 patch」。sig 缺失→放行告警；不匹配→拒绝。
  - config_trusted()       ：发行版下 config.py 签名校验 + compromised 降级判定。
  - assert_hardening()     ：总闸（config 签名 / 防护模块 / 自体检），失败→False。
  - enforce_hardening()    ：冻结发行态【启动边界】调用：防护失效/调试器→拒绝启动。
  - recheck()              ：周期复检（运行时揭穿被 patch/被调试，置 compromised）。
  - guarded_flag(name)     ：把"恒真/恒假"的关键决策做成多副本一致性校验，
                            防 patch 单个判断点（如把 use_worker_ai 改成 False）。
"""
from __future__ import annotations

import ctypes
import hashlib
import logging
import os
import sys

from weauto_license import secrets as _sec

_LOGGER = logging.getLogger("weauto.license")

# 发行态防护被突破的全局标志：一旦置 True，config_trusted() 等判定会强制走
# 网关/禁用付费功能（降级），使被 patch / 被调试的客户端无法使用付费能力。
_COMPROMISED = False
# sig 过渡态自愈的进程内防抖：自愈要下载完整构件（~83MB），每次启动/复检都试
# 会把离线用户卡死在启动边界。进程内只尝试一次，失败则交给 compromised 降级。
_REPAIR_TRIED = False


def is_compromised() -> bool:
    return _COMPROMISED


def _set_compromised(reason: str) -> None:
    global _COMPROMISED
    _COMPROMISED = True
    try:
        _LOGGER.critical("HARDENING COMPROMISED: %s", reason)
    except Exception:
        pass


def _clear_compromised() -> None:
    """自愈成功后解除降级（过渡态误判的恢复路径）。"""
    global _COMPROMISED
    _COMPROMISED = False


def is_release() -> bool:
    return _sec.is_release()


# 已知调试器 / 反编译 / 内存修改工具进程名（小写）。父进程命中即视为被调试。
_DEBUGGER_NAMES = (
    "x64dbg.exe", "x32dbg.exe", "x96dbg.exe", "ida.exe", "ida64.exe",
    "ollydbg.exe", "ollydbg2.exe", "immunitydebugger.exe", "cheatengine.exe",
    "cheatengine-x86_64.exe", "windbg.exe", "windbgx.exe", "dnspy.exe",
    "ilspy.exe", "de4dot.exe", "procexp.exe", "procexp64.exe", "procmon.exe",
    "frida.exe", "frida-trace.exe", "reshacker.exe", "stud_pe.exe",
    "peid.exe", "lordpe.exe",
)


def _parent_process_name() -> str:
    """返回父进程 exe 名（小写）；任何异常返回空串。"""
    try:
        k32 = ctypes.windll.kernel32
        TH32CS_SNAPPROCESS = 0x00000002
        hsnap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if hsnap in (0, -1):
            return ""
        class PROCESSENTRY32(ctypes.Structure):
            _fields_ = [
                ("dwSize", ctypes.c_ulong),
                ("cntUsage", ctypes.c_ulong),
                ("th32ProcessID", ctypes.c_ulong),
                ("th32DefaultHeapID", ctypes.POINTER(ctypes.c_ulong)),
                ("th32ModuleID", ctypes.c_ulong),
                ("cntThreads", ctypes.c_ulong),
                ("th32ParentProcessID", ctypes.c_ulong),
                ("pcPriClassBase", ctypes.c_long),
                ("dwFlags", ctypes.c_ulong),
                ("szExeFile", ctypes.c_char * 260),
            ]
        pe = PROCESSENTRY32()
        pe.dwSize = ctypes.sizeof(pe)
        ppid = os.getppid()
        name = ""
        if k32.Process32First(hsnap, ctypes.byref(pe)):
            while True:
                if pe.th32ProcessID == ppid:
                    name = pe.szExeFile.decode("mbcs", "ignore")
                    break
                if not k32.Process32Next(hsnap, ctypes.byref(pe)):
                    break
        k32.CloseHandle(hsnap)
        return name.lower()
    except Exception:
        return ""


def _nt_query_debug() -> bool:
    """NtQueryInformationProcess 的 ProcessDebugFlags(0x1f)/ProcessDebugObjectHandle(0x1e)。"""
    try:
        k32 = ctypes.windll.kernel32
        ntdll = ctypes.windll.ntdll
        h = ctypes.c_void_p(k32.GetCurrentProcess())
        # ProcessDebugFlags：0 => 被调试
        dbg_flags = ctypes.c_ulong()
        if ntdll.NtQueryInformationProcess(h, 0x1f, ctypes.byref(dbg_flags), 4, None) == 0:
            if dbg_flags.value == 0:
                return True
        # ProcessDebugObjectHandle：非 NULL => 被调试
        hobj = ctypes.c_void_p()
        if ntdll.NtQueryInformationProcess(h, 0x1e, ctypes.byref(hobj),
                                           ctypes.sizeof(hobj), None) == 0:
            if hobj.value is not None:
                return True
    except Exception:
        pass
    return False


def detect_debugger() -> bool:
    """探测常见用户态调试器 / 反编译器。仅 Windows 生效；任何异常都返回 False
    （宁可漏报也不误杀正常环境）。"""
    if sys.platform != "win32":
        return False
    try:
        k32 = ctypes.windll.kernel32
        # 1) IsDebuggerPresent
        if k32.IsDebuggerPresent():
            return True
        # 2) CheckRemoteDebuggerPresent
        res = ctypes.c_long()
        if k32.CheckRemoteDebuggerPresent(k32.GetCurrentProcess(), ctypes.byref(res)):
            if res.value:
                return True
        # 3) PEB.BeingDebugged 经 ProcessDebugPort(7)
        try:
            ntdll = ctypes.windll.ntdll
            dbg_port = ctypes.c_ulong()
            if ntdll.NtQueryInformationProcess(
                    ctypes.c_void_p(k32.GetCurrentProcess()), 7,
                    ctypes.byref(dbg_port), ctypes.sizeof(dbg_port), None) == 0:
                if dbg_port.value != 0:
                    return True
        except Exception:
            pass
        # 4) 更深的内核态判定
        if _nt_query_debug():
            return True
        # 5) 父进程是已知调试器
        if _parent_process_name() in _DEBUGGER_NAMES:
            return True
        # 6) 反反编译：sys trace/profile 被设置（反编译器/decompyle 常用）
        if sys.gettrace() is not None or sys.getprofile() is not None:
            return True
    except Exception:
        return False
    return False


# ---------------------------------------------------------------- config 信任
# 反"改配置直连"的核心：发行版下 config.py 必须通过签名校验，
# 否则上层（guard.ai_endpoint / bot 的 AI 初始化）一律回退到强制网关语义。

def config_trusted() -> bool:
    """config.py 是否可信（签名通过）。开发态恒 True；被标记 compromised 时恒 False。"""
    if not is_release():
        return True
    if _COMPROMISED:
        return False
    return _sec.config_signature_valid()


def self_integrity_ok() -> bool:
    """EXE 自体检（防「已安装 EXE 被 patch」）。

    发行冻结态下，校验与 exe 同目录的 WeAuto.exe.sig（构建期用 UPDATER_SIGN_KEY
    对 SHA-256(exe) 做的 Ed25519 签名，公钥内嵌于 secrets blob）。任一不符 → 被篡改。
    仅冻结发行态生效；开发/测试态或无 sig 场景恒 True（避免误杀合法调试）。
    """
    if not (getattr(sys, "frozen", False) and is_release()):
        return True
    exe = sys.executable
    if not exe or not exe.lower().endswith(".exe"):
        return True
    sig_path = os.path.join(os.path.dirname(os.path.abspath(exe)), "WeAuto.exe.sig")
    if not os.path.exists(sig_path):
        # 缺失 sig = 无法自证清白，但**不能据此拒绝启动**（会误伤合法用户，
        # 例如只拷了单 exe 的场景）。放行 + 告警；真正定罪的判据是「sig 存在但不匹配」。
        try:
            _LOGGER.warning("未找到 WeAuto.exe.sig，跳过 EXE 自体检（同目录签名随分发/更新一并提供）")
        except Exception:
            pass
        return True
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric import ed25519
        pub = _sec.get_bytes("UPDATER_VERIFY_KEY")
        if not pub or len(pub) != 32:
            return False
        with open(exe, "rb") as f:
            digest = hashlib.sha256(f.read()).digest()
        with open(sig_path, "rb") as f:
            sig = f.read()
        ed25519.Ed25519PublicKey.from_public_bytes(pub).verify(sig, digest)
        return True
    except InvalidSignature:
        # sig 存在但验不过。两种可能：① 真被 patch；② **更新过渡态**——旧版
        # updater 自动更新只搬 exe 不搬 sig，磁盘是「新 exe + 旧 sig」。先尝试
        # 自愈：本地 exe 与 Worker 最新版逐字节一致时拉取对应 sig 覆盖。
        global _REPAIR_TRIED
        if not _REPAIR_TRIED:
            _REPAIR_TRIED = True  # 进程内只试一次（见 _REPAIR_TRIED 注释）
            try:
                import updater as _upd
                if _upd.Updater().repair_sig_if_transitional():
                    with open(exe, "rb") as f:
                        digest = hashlib.sha256(f.read()).digest()
                    with open(sig_path, "rb") as f:
                        sig = f.read()
                    ed25519.Ed25519PublicKey.from_public_bytes(pub).verify(sig, digest)
                    _LOGGER.warning("EXE sig 过渡态已自愈（旧版 updater 更新未同步 sig）")
                    return True
            except Exception:
                pass
        return False
    except Exception:
        return False


# ---------------------------------------------------------------- 决策一致性
# 这些标志是防破解的关键决策点（如"是否走网关"）。为防被 patch 成单个常量，
# 用"多处派生 + 两副本交叉校验"：任何一个副本被改，另一个仍能揭穿。

_FLAGS = ("weauto_hardening_flags_v1",)


def guarded_flag(name: str, expected: bool) -> bool:
    """读取一个由模块级派生、跨模块复制的决策标志，并交叉校验。

    实现：标志值同时存在于本模块 __dict__ 与 weauto_license 包级 __dict__
    （两个独立位置），读取时逐位比较；不一致按"防护被干扰"处理 → 返回 expected 的安全侧。
    """
    try:
        import weauto_license as _pkg
        a = getattr(_pkg, name, None)
        b = getattr(sys.modules.get(__name__), name, None)
        if a is not None and b is not None and bool(a) == bool(b):
            return bool(a)
        # 副本缺失/不一致 → 防护被干扰，回到安全侧
        return expected
    except Exception:
        return expected


def assert_hardening() -> bool:
    """总闸。返回 False = 环境不满足防护要求（调用方应拒绝启动 / 降级付费功能）。

    仅做"防篡改/防调试"层面的判断，不做网络判断（网络由授权层负责）。
    调试器探测不在此软判（避免误杀），由 enforce_hardening 在启动边界硬拒。
    """
    if not is_release():
        return True
    try:
        if not config_trusted():
            _set_compromised("config sig invalid")
            return False
        # 关键防护模块必须可导入（被删 = 被拆防护）
        from weauto_license import guard  # noqa: F401
        from weauto_license import secrets  # noqa: F401
        if not self_integrity_ok():
            # ⚠️ 自愈后仍不过 = 两种不可区分的情形：① 真被 patch；② 旧版 updater
            # 的更新过渡态但自愈失败（如离线）。启动边界【不因此拒绝启动】——
            # 硬拒会让正版用户变砖（已实测：旧版更新后新 exe+旧 sig 直接 os._exit）。
            # 处置：降级（compromised → 强制走网关/禁付费功能）让破解无利可图，
            # 防破解的硬拒由 config 签名 / 防护模块存在性 / 调试器探测承担。
            _set_compromised("self integrity failed (unrepaired)")
            _LOGGER.warning(
                "EXE 自体检未通过且自愈失败，已降级但不拒绝启动（避免更新过渡态误杀正版用户）")
        else:
            _clear_compromised()  # 自愈成功/本就清白：解除过渡态误判
    except Exception as _e:
        _set_compromised("hardening check error: %r" % _e)
        return False
    return True


def enforce_hardening() -> None:
    """冻结发行态【启动边界】调用：任一防护失效 / 检测到调试器 → 拒绝启动（防破解）。

    开发态 / 源码态（非 frozen 或 RELEASE=False）直接放行，不影响本地调试与 UX 测试。
    """
    if not (getattr(sys, "frozen", False) and is_release()):
        return
    if not assert_hardening():
        _LOGGER.critical("防护校验失败，拒绝启动（疑似被篡改）")
        os._exit(1)
    if detect_debugger():
        _LOGGER.critical("检测到调试器，拒绝启动")
        os._exit(1)


def recheck() -> bool:
    """周期复检（bot 后台线程定期调用）。返回 False = 当前被破坏，已置 compromised。

    目的：即便破解者 patch 了启动期检查，周期复检仍能在其运行时揭穿并触发降级。
    """
    if not is_release():
        return True
    ok = assert_hardening()
    if not ok:
        _set_compromised("periodic recheck failed")
    return ok


def warn_debugger() -> bool:
    """发行版下检测到调试器时告警（返回 True 表示有调试器，供上层打日志）。"""
    if not is_release():
        return False
    return detect_debugger()
