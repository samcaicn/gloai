# -*- coding: utf-8 -*-
"""GUI-only 产品的 stdio 兜底（无 cmd 黑框形态）。

背景
----
WeAuto 的 EXE 以 PyInstaller ``console=False``（noconsole / -w 形态）构建：双击启动
只有桌面窗口，没有 cmd 黑框。这是产品硬要求，不是可选项。

noconsole 形态下 Python 解释器启动时 **sys.stdout / sys.stderr / sys.stdin 直接
就是 None**。实测（CPython 3.10）结论要分两半，不要想当然：

* ``print(...)`` 写到 None 流 —— **不抛**，被静默吞掉；
* ``logging.StreamHandler()`` 无参构造 —— 会把当时的 ``sys.stderr``（None）绑成
  自己的 stream，首次 emit 走 ``Handler.handleError``，同样被静默吞掉。

也就是说「日志把 bot 打哑巴」并不会发生。但静默吞掉不等于没问题：

* 排障时看不到任何线索（连「日志丢了」这件事本身都看不见）；
* 任何绕过 logging/print 的写法（``sys.stdout.write(...)``、第三方 C 扩展直接
  写 fd 1/2、``os.write(1, ...)``）会直接抛 ``AttributeError``/``OSError``；
* MCP stdio 模式下 stdout 被占着，静默丢弃请求/响应比报错更难查。

所以本模块把三流**显式兜底成 devnull**：行为与「静默丢弃」一致，但变成
**可预期的、我们主动选择的**，而不是依赖解释器的隐式兜底。

本模块一次性解决这两件事
------------------------
1. 需要 stdio 的子形态（--bot / --cli / --mcp）用
   ``AttachConsole(ATTACH_PARENT_PROCESS)`` 重新附着 **调用方** 的控制台，
   再走 ``GetStdHandle -> open_osfhandle -> os.fdopen`` 手工把流接到
   sys.stdout/stderr/stdin 上，并 ``dup2`` 到 CRT 的 0/1/2（否则 C 扩展层
   的 printf / 第三方库日志照样丢）。
   · 从 cmd / PowerShell / agent 启动 -> 输出回到调用方窗口（等价旧的 console=True）
   · 双击启动（无父控制台）-> AttachConsole 失败，退到 devnull，不崩
2. 无论是否附着成功，最终保证 sys.stdin/stdout/stderr **一定不是 None**
   （拿不到真实流就给 devnull），让 print 与 logging 永远安全。

设计红线
--------
- 纯标准库、绝不抛异常、绝不改变调用方已有可用的流。
- 幂等：重复调用无副作用。
- 「附着父控制台」只在 Windows 冻结形态且带 stdio 标志时做（源码运行本来就有
  真实控制台）；但 **devnull 兜底是无条件执行的** —— 本模块可能被在流已被置
  None 的环境里调用（单测、嵌入式宿主、pywebview 二次封装），那时不兜底就会炸。
"""

from __future__ import annotations

import os
import sys

__all__ = ['ensure_stdio', 'has_console']

# 需要真实 stdio 的子形态标志
_STDIO_FLAGS = ('--bot', '--cli', '--mcp')

_done = False


def has_console() -> bool:
    """当前进程是否拿到了可用的真实控制台输出流（用于日志/诊断，不抛异常）。"""
    return _is_real(getattr(sys, 'stdout', None))


def _is_real(stream) -> bool:
    """区分「真实控制台/文件流」与「我们塞进去的 devnull 兜底」。"""
    if stream is None:
        return False
    try:
        return not getattr(stream, '_weauto_stdio_fallback', False)
    except Exception:
        return True


def _attach_parent_console() -> bool:
    """附着调用方（父进程）的控制台并把 0/1/2 接到 sys 流上。成功返回 True。"""
    try:
        import ctypes
        import msvcrt
    except Exception:
        return False
    try:
        k32 = ctypes.windll.kernel32
        # ATTACH_PARENT_PROCESS = (DWORD)-1
        if not k32.AttachConsole(ctypes.c_uint(-1).value):
            return False
        # STD_INPUT_HANDLE=-10 / STD_OUTPUT_HANDLE=-11 / STD_ERROR_HANDLE=-12
        _STD = (-10, -11, -12)
        _modes = ('r', 'w', 'w')
        _names = ('stdin', 'stdout', 'stderr')
        for slot, (hnd_id, mode, name) in enumerate(zip(_STD, _modes, _names)):
            try:
                h = int(k32.GetStdHandle(hnd_id))
            except Exception:
                h = 0
            if h <= 0:      # 0 与 INVALID_HANDLE_VALUE(-1) 都算无效
                continue
            flags = os.O_RDONLY if mode == 'r' else (os.O_WRONLY | os.O_BINARY)
            try:
                fd = msvcrt.open_osfhandle(h, flags)
            except OSError:
                continue
            # 同时 dup2 到 CRT 的 0/1/2，否则 C 扩展层的 printf / 第三方日志会丢
            try:
                os.dup2(fd, slot)
            except OSError:
                pass
            try:
                stream = os.fdopen(
                    fd, mode, buffering=1,
                    encoding='utf-8' if mode == 'r' else None,
                    errors='replace', newline=None,
                )
            except OSError:
                continue
            setattr(sys, name, stream)
        return True
    except Exception:
        return False


def _fallback_to_devnull() -> None:
    """把所有仍是 None 的流换成 devnull，并打上标记（供 has_console 识别）。"""
    for name, mode in (('stdin', 'r'), ('stdout', 'w'), ('stderr', 'w')):
        if getattr(sys, name, None) is not None:
            continue
        try:
            stream = open(os.devnull, mode, encoding='utf-8')
        except Exception:
            continue
        try:
            stream._weauto_stdio_fallback = True
        except Exception:
            pass
        setattr(sys, name, stream)


def ensure_stdio(argv=None) -> bool:
    """确保 sys.stdin/stdout/stderr 可用。返回是否拿到了真实控制台。

    必须在**任何会 print / 建 StreamHandler 的代码之前**调用，
    推荐在程序入口（``__main__`` 块）第一行。
    """
    global _done
    if _done:
        return has_console()
    _done = True

    # 源码运行（非冻结）时本来就有真实控制台，不需要附着父进程。
    # 但兜底仍要执行 —— 本模块可能被在「流已被置 None」的环境里调用
    # （单测、某些嵌入式宿主、pywebview 二次封装），那时 print 一样会炸。
    frozen_nt = getattr(sys, 'frozen', False) and os.name == 'nt'
    attached = False
    if frozen_nt:
        if argv is None:
            argv = sys.argv
        if any(f in argv for f in _STDIO_FLAGS):
            attached = _attach_parent_console()
    # 无论是否附着成功，兜底必须执行：noconsole 形态下 stdout 本来就是 None
    _fallback_to_devnull()
    return attached
