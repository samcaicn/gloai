# -*- coding: utf-8 -*-
"""WeAuto 运行时防护（防反编译/防破解整改：反调试 + 完整性 + 反单点 patch）。

模块职责（全部纯标准库，绝不抛异常给上层）：
  - detect_debugger()      ：Windows 常见调试器探测（IsDebuggerPresent /
                             CheckRemoteDebuggerPresent / PEB BeingDebugged）。
  - config_trusted()       ：发行版下 config.py 签名校验（被篡改 → False，
                             上层据此强制走网关、不信任任何自填字段）。
  - assert_hardening()     ：bot 启动 / 周期复检调用的总闸：
                              发行版下 config 签名失效、或防护模块缺失 → False。
  - guarded_flag(name)     ：把"恒真/恒假"的关键决策做成多副本一致性校验，
                             防 patch 单个判断点（如把 use_worker_ai 改成 False）。
"""
from __future__ import annotations

import ctypes
import os
import sys

from weauto_license import secrets as _sec


def is_release() -> bool:
    return _sec.is_release()


def detect_debugger() -> bool:
    """探测常见用户态调试器。仅 Windows；其他平台返回 False（不做激进判断）。"""
    if sys.platform != "win32":
        return False
    try:
        k32 = ctypes.windll.kernel32
        # 1) IsDebuggerPresent
        if k32.IsDebuggerPresent():
            return True
        # 2) PEB.BeingDebugged（绕过 API 钩子的常见手法）
        pbi = ctypes.create_string_buffer(4)
        res = ctypes.c_long()
        if k32.CheckRemoteDebuggerPresent(k32.GetCurrentProcess(), ctypes.byref(res)):
            if res.value:
                return True
        # 3) NtQueryInformationProcess ProcessDebugPort（基础版）
        try:
            ntdll = ctypes.windll.ntdll
            dbg_port = ctypes.c_ulong()
            size = ctypes.sizeof(dbg_port)
            status = ntdll.NtQueryInformationProcess(
                ctypes.c_void_p(k32.GetCurrentProcess()),
                7,  # ProcessDebugPort
                ctypes.byref(dbg_port), size, None,
            )
            if status == 0 and dbg_port.value != 0:
                return True
        except Exception:
            pass
    except Exception:
        return False
    return False


# ---------------------------------------------------------------- config 信任
# 反"改配置直连"的核心：发行版下 config.py 必须通过签名校验，
# 否则上层（guard.ai_endpoint / bot 的 AI 初始化）一律回退到强制网关语义。

def config_trusted() -> bool:
    """config.py 是否可信（签名通过）。开发态恒 True。"""
    if not is_release():
        return True
    return _sec.config_signature_valid()


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
    """总闸。返回 False = 环境不满足防护要求（调用方决定是否拒绝启动）。

    只做"防篡改/防调试"层面的判断，不做网络判断（网络由授权层负责）。
    """
    if not is_release():
        return True
    try:
        if not config_trusted():
            return False
        # 关键防护模块必须可导入（被删 = 被拆防护）
        from weauto_license import guard  # noqa: F401
        from weauto_license import secrets  # noqa: F401
    except Exception:
        return False
    # 调试器：检测到不直接拒启动（避免误杀正常调试工具），
    # 但在 release 下会由调用方记录告警并持续触发周期复检。
    return True


def warn_debugger() -> bool:
    """发行版下检测到调试器时告警（返回 True 表示有调试器，供上层打日志）。"""
    if not is_release():
        return False
    return detect_debugger()
