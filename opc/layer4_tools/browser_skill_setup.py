"""BrowserSkill dependency setup: auto-install CLI, guide extension.

This module operationalizes the project's install-strategy decision for the
Tencent/BrowserSkill integration (`opc.layer4_tools.browser_skill`):

  Component            Auto-install?  Strategy
  ───────────────────  ────────────  ────────────────────────────────────────
  bsk CLI + 守护进程    YES           ``ensure_bsk_installed`` runs the official
                                      one-click script, idempotent, drops the
                                      binary into ``~/.local/bin`` (or platform
                                      equivalent) and re-probes.
  浏览器扩展            NO            Chrome/Edge forbid desktop apps from
                                      silently injecting extensions. We only
                                      DETECT the bridge and, when missing, return
                                      a deep-link + step-by-step guidance asking
                                      the USER to click "Add to browser".

Why the split is non-negotiable:
  * The CLI is a normal executable — a desktop app can fetch and place it.
  * The extension lives inside the user's browser profile and is gated by the
    browser's security model. No out-of-process binary can install it for the
    user; attempting to do so is both impossible and a red flag. Hence we never
    try — we detect and nudge.

Verified official install endpoints (Tencent/BrowserSkill, 0.1.x):
  * macOS / Linux : ``curl -fsSL <install.sh> | sh``
  * Windows       : ``irm <install.ps1> | iex``  (PowerShell)
  Both install ``bsk`` into ``~/.local/bin`` and attempt to write PATH.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

from opc.layer4_tools.browser_skill import BrowserSkillBackend, BrowserSkillError

BSK_REPO = "https://github.com/Tencent/BrowserSkill"

# Official one-click install scripts per platform.
BSK_INSTALL_SCRIPT: dict[str, str] = {
    "win32": "https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.ps1",
    "darwin": "https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.sh",
    "linux": "https://raw.githubusercontent.com/Tencent/BrowserSkill/main/install.sh",
}

# Where the official script drops the binary — also our idempotency probe target.
BSK_INSTALL_DIR = Path.home() / ".local" / "bin"

MANUAL_FALLBACK = (
    "手动安装：从 GitHub Releases 下载 bsk-<version>-<arch>.zip，"
    "解压 bsk 到 ~/.local/bin（Windows 为 %USERPROFILE%\\.local\\bin），"
    "并将该目录加入系统 PATH，重启应用后生效。"
)


# --------------------------------------------------------------------------- #
# CLI install
# --------------------------------------------------------------------------- #
def _candidate_binary_name() -> str:
    return "bsk.exe" if sys.platform == "win32" else "bsk"


def bsk_binary_present(command: str = "bsk") -> tuple[bool, str]:
    """Idempotency probe.

    Returns (present, resolved_path). Checks PATH first, then the known
    install directory — the latter matters because the install script writes
    PATH for *future* processes, not the currently running one.
    """
    found = shutil.which(command)
    if found:
        return True, os.path.abspath(found)
    candidate = BSK_INSTALL_DIR / _candidate_binary_name()
    if candidate.exists():
        return True, str(candidate)
    return False, ""


def build_install_command(platform: Optional[str] = None,
                          url: Optional[str] = None) -> list[str]:
    """Return the argv that runs the official one-click installer.

    ``platform`` defaults to ``sys.platform``; ``url`` overrides the script
    source (useful for pinned/air-gapped mirrors).
    """
    platform = platform or sys.platform
    url = url or BSK_INSTALL_SCRIPT.get(platform, BSK_INSTALL_SCRIPT["linux"])
    if platform == "win32":
        ps = f'irm "{url}" | iex'
        return ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps]
    # darwin / linux / other unix
    return ["sh", "-c", f'curl -fsSL "{url}" | sh']


def ensure_bsk_installed(backend: Optional[BrowserSkillBackend] = None,
                         *, auto_run: bool = True,
                         timeout: float = 300.0,
                         platform: Optional[str] = None,
                         url: Optional[str] = None) -> dict:
    """Ensure the ``bsk`` CLI + daemon bits are present. Idempotent.

    Flow:
      1. Probe (PATH + ~/.local/bin). If present -> return ``already_present``.
      2. If ``auto_run`` is False, return ``needs_install`` with the exact
         command + manual fallback (caller decides / surfaces to UI).
      3. Else run the official one-click script, then re-probe.

    Returns a structured report. Never raises for install failure — failures
    are reported in the returned dict so the agent/UI can react.
    """
    present, path = bsk_binary_present()
    if present:
        return {
            "action": "already_present", "installed": True, "ok": True,
            "path": path, "note": "bsk 已安装，幂等跳过安装。",
        }

    cmd = build_install_command(platform, url)
    if not auto_run:
        return {
            "action": "needs_install", "installed": False, "ok": True,
            "install_command": cmd, "manual": MANUAL_FALLBACK,
        }

    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout, check=False
        )
    except subprocess.TimeoutExpired:
        return {
            "action": "install_timeout", "installed": False, "ok": False,
            "error": f"安装脚本超时 (> {timeout}s)，可能是网络问题。",
            "install_command": cmd, "manual": MANUAL_FALLBACK,
        }
    except Exception as exc:  # environment-specific (no powershell/sh, etc.)
        return {
            "action": "install_error", "installed": False, "ok": False,
            "error": str(exc), "install_command": cmd, "manual": MANUAL_FALLBACK,
        }

    if proc.returncode != 0:
        return {
            "action": "install_failed", "installed": False, "ok": False,
            "error": (proc.stderr or proc.stdout or "").strip() or f"exit {proc.returncode}",
            "install_command": cmd, "manual": MANUAL_FALLBACK,
        }

    present, path = bsk_binary_present()
    if present:
        return {
            "action": "installed", "installed": True, "ok": True, "path": path,
            "note": "bsk 安装成功。若当前进程 PATH 未刷新，请重启应用或手动刷新环境。",
        }
    return {
        "action": "installed_but_not_on_path", "installed": False, "ok": False,
        "path": str(BSK_INSTALL_DIR),
        "error": "安装脚本已执行，但 bsk 仍不在 PATH 或 ~/.local/bin。",
        "install_command": cmd, "manual": MANUAL_FALLBACK,
    }


# --------------------------------------------------------------------------- #
# Browser-extension detection + guidance (NEVER auto-install)
# --------------------------------------------------------------------------- #
def _extract_browser_list(data: Any, raw: str) -> Optional[list]:
    """Pull a connected-browser list from bsk output, if present."""
    if isinstance(data, dict):
        for key in ("browsers", "connected_browsers", "connectedBrowsers"):
            v = data.get(key)
            if isinstance(v, list):
                return v
    # Some builds print a JSON array directly.
    if isinstance(data, list):
        return data
    return None


def _extract_bool_flag(data: Any, raw: str,
                       keys: tuple[str, ...]) -> Optional[bool]:
    if isinstance(data, dict):
        for key in keys:
            v = data.get(key)
            if isinstance(v, bool):
                return v
    return None


def detect_extension(backend: Optional[BrowserSkillBackend] = None,
                     *, use_browsers: bool = True) -> tuple[bool, str, Any]:
    """Detect whether the BrowserSkill extension bridge is connected.

    Probes ``bsk browsers`` (the extension's exposed surface) first, falling
    back to ``bsk status``. Returns (connected, detail, raw).

    Detection is best-effort and tolerant: if the output schema is ambiguous we
    err toward "not detected" and let the caller surface guidance, rather than
    falsely claiming readiness.
    """
    backend = backend or BrowserSkillBackend()
    probe = "browsers" if use_browsers else "status"
    try:
        res = backend.run([probe])
    except BrowserSkillError as exc:
        return False, f"探测失败 ({probe}): {exc}", None

    raw = res.raw or ""
    data = res.data
    if not res.ok:
        return False, f"探测失败 ({probe}): {res.error}", raw

    browsers = _extract_browser_list(data, raw)
    if browsers is not None:
        if len(browsers) > 0:
            return True, f"扩展已连接，发现 {len(browsers)} 个浏览器", raw
        return False, "扩展已安装但未发现已连接浏览器（请确认扩展已固定并处于活动状态）", raw

    flag = _extract_bool_flag(data, raw, ("extension_connected", "bridge_connected"))
    if flag is True:
        return True, "扩展已连接", raw
    if flag is False:
        return False, "扩展未连接", raw

    low = raw.lower()
    if "extension not detected" in low or "未检测到扩展" in raw or "no extension" in low:
        return False, "扩展未检测到", raw
    if ("extension" in low) and (
        ("detected" in low) or ("connected" in low) or ("已连接" in raw)
    ):
        return True, "扩展已连接", raw

    # Uncertain -> treat as not detected, but say so explicitly.
    return False, "无法确定扩展连接状态（按未安装处理，给出引导）", raw


def extension_guidance() -> dict:
    """Guidance for the part we CANNOT auto-install: the browser extension.

    Chrome/Edge forbid desktop apps from silently injecting extensions, so this
    only provides the deep link + steps and asks the user to click.
    """
    return {
        "auto_installable": False,
        "reason": "Chrome/Edge 禁止桌面应用静默注入扩展；只能由用户在浏览器内手动添加。",
        "deep_link_command": "bsk install-extension",  # 官方：自动打开商店页
        "store": {
            "chrome_web_store": "在 Chrome 网上应用店搜索 “BrowserSkill” 并点击“添加至 Chrome”",
            "edge_addons": "在 Microsoft Edge 加载项搜索 “BrowserSkill” 并点击“获取”",
            "github_fallback": (
                f"{BSK_REPO}/releases 下载 browserskill-chrome-extension.zip，"
                "在 chrome://extensions 开启开发者模式后加载解压目录"
            ),
        },
        "steps": [
            "在【你日常使用的、已登录目标站点】的那个浏览器里打开扩展商店页"
            "（运行 `bsk install-extension` 会自动弹出商店页）。",
            "点击 “添加至 Chrome / 获取”，完成安装。",
            "把 BrowserSkill 扩展固定到工具栏，保持其处于活动/启用状态。",
            "回到本应用，重新调用 browser_skill_readiness 或 browser_skill_browsers "
            "验证扩展已连接。",
        ],
    }


# --------------------------------------------------------------------------- #
# Combined readiness
# --------------------------------------------------------------------------- #
def readiness_report(backend: Optional[BrowserSkillBackend] = None,
                     *, auto_install: bool = False) -> dict:
    """One-call readiness check for the agent/UI.

    When ``auto_install`` is True, a missing CLI is auto-installed. The
    extension is NEVER auto-installed — if missing, guidance is attached.
    """
    backend = backend or BrowserSkillBackend()
    cli = ensure_bsk_installed(backend, auto_run=auto_install)
    report: dict = {"cli": cli, "extension": None, "ready": False}

    if not cli.get("installed"):
        report["extension"] = {
            "installed": False,
            "detail": "CLI 缺失，扩展探测跳过；请先安装 CLI。",
        }
        report["guidance"] = extension_guidance()
        return report

    installed, detail, _ = detect_extension(backend)
    report["extension"] = {"installed": installed, "detail": detail}
    if installed:
        report["ready"] = True
    else:
        report["guidance"] = extension_guidance()
    return report


# --------------------------------------------------------------------------- #
# Tool-callable surface (browser_skill_* family)
# --------------------------------------------------------------------------- #
def browser_skill_ensure_installed(auto_run: bool = True) -> dict:
    """确保 bsk CLI 已安装（缺失时自动跑官方一键脚本，幂等）。"""
    return ensure_bsk_installed(auto_run=auto_run)


def browser_skill_extension_guidance() -> dict:
    """返回浏览器扩展的安装引导（无法自动装，只给深链 + 步骤）。"""
    return extension_guidance()


def browser_skill_readiness(auto_install: bool = False) -> dict:
    """一次性就绪检查：CLI 状态 +（可选自动装）+ 扩展连接 + 引导。"""
    return readiness_report(auto_install=auto_install)


BROWSER_SKILL_SETUP_TOOL_SPECS: list[dict] = [
    {
        "name": "browser_skill_ensure_installed",
        "category": "browser_skill",
        "description": "确保 bsk CLI 已安装：缺失时自动执行官方一键脚本（幂等，装到 ~/.local/bin），已装则跳过。auto_run=False 时只返回安装命令与手动步骤。",
        "parameters": [
            {"name": "auto_run", "type": "bool", "required": False,
             "description": "True 则缺失时自动执行安装脚本；False 仅返回安装命令。"},
        ],
        "session_required": False,
    },
    {
        "name": "browser_skill_extension_guidance",
        "category": "browser_skill",
        "description": "返回浏览器扩展安装引导。Chrome/Edge 禁止桌面应用静默注入扩展，故只提供商店深链（bsk install-extension）+ 手动步骤，需用户自行点“添加”。",
        "parameters": [],
        "session_required": False,
    },
    {
        "name": "browser_skill_readiness",
        "category": "browser_skill",
        "description": "一次性就绪检查：CLI 安装状态、（可选）自动安装、扩展连接探测；未就绪时附带引导。扩展永远不会被自动安装。",
        "parameters": [
            {"name": "auto_install", "type": "bool", "required": False,
             "description": "True 则 CLI 缺失时自动安装；默认 False（只报告）。"},
        ],
        "session_required": False,
    },
]


def get_browser_skill_setup_tools() -> dict:
    """Return {tool_name: callable} for the browser_skill setup capability."""
    return {
        "browser_skill_ensure_installed": browser_skill_ensure_installed,
        "browser_skill_extension_guidance": browser_skill_extension_guidance,
        "browser_skill_readiness": browser_skill_readiness,
    }
