"""双模式浏览器自测：CDP 连 SafeOPC 自身 WebView2 + 独立 Chromium 跑外部任务。

前置条件：
  1. 已下载浏览器二进制：  python -m playwright install chromium
  2. SafeOPC 桌面 app 以 debug_cdp 启动（config.system.browser.debug_cdp=true），
     使 WebView2 暴露 --remote-debugging-port=9222。

运行：
  cd C:\code\openopc
  SafeOPC.exe            # 另一个终端/窗口，已开 debug_cdp
  python scripts/browser_self_test.py
"""
from __future__ import annotations

import asyncio
import sys

from opc.layer4_tools.browser import BrowserLaunchConfig, BrowserRuntime

CDP_PORT = 9222


async def _mode_a_self_window() -> bool:
    """模式 A：connect_over_cdp 连 SafeOPC 自己的 WebView2，验证渲染。"""
    rt = BrowserRuntime(config_loader=lambda: BrowserLaunchConfig(mode="cdp", cdp_port=CDP_PORT))
    try:
        shot = await rt.take_screenshot(filename="self_render.png")
        print(f"[A] SafeOPC 自身窗口截图 -> {shot['saved_to']}  (title={shot['title']!r})")
        snap = await rt.snapshot(max_chars=600)
        print(f"[A] 自身窗口 URL={snap['url']}  可见交互元素数={len(snap['interactive_elements'])}")
        await rt.close()  # cdp 模式仅 detach，不会关闭 SafeOPC 窗口
        return True
    except Exception as exc:  # noqa: BLE001
        print(f"[A] CDP 连自身窗口失败：{exc}")
        print("    -> 确认 SafeOPC 以 debug_cdp=true 启动，且 9222 端口开放")
        return False


async def _mode_b_external_task() -> bool:
    """模式 B：launch 独立 Chromium 跑外部网页任务。"""
    rt = BrowserRuntime(config_loader=lambda: BrowserLaunchConfig(mode="embedded", headless=True))
    try:
        nav = await rt.navigate("https://example.com")
        print(f"[B] 外部页面标题={nav['title']!r}  URL={nav['url']}")
        shot = await rt.take_screenshot(filename="external.png")
        print(f"[B] 外部截图 -> {shot['saved_to']}")
        await rt.close()
        return True
    except Exception as exc:  # noqa: BLE001
        print(f"[B] 外部 Chromium 任务失败：{exc}")
        return False


async def main() -> int:
    a = await _mode_a_self_window()
    b = await _mode_b_external_task()
    if a and b:
        print("\nOK: 双模式均可用 —— 自渲染验证(CDP) + 独立 Chromium 外部任务。")
        return 0
    print("\nFAIL: 至少有一项未通过（见上方 [A]/[B] 错误信息）。")
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
