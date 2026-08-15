# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the SafeOPC desktop client.

Builds a native desktop app that wraps the existing aiohttp office-ui server
in a pywebview (system WebView) window. Run from the repo root:

    pyinstaller packaging/safeopc.spec --noconfirm

Output: dist/SafeOPC/SafeOPC.exe  (onedir — fast to build & easy to debug)
"""

import os
from PyInstaller.utils.hooks import collect_all

# PyInstaller injects SPECPATH (dir of this .spec). Fall back to cwd if run
# outside PyInstaller.
try:
    REPO_ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
    DESKTOP_APP = os.path.join(SPECPATH, "desktop_app.py")
except NameError:
    REPO_ROOT = os.getcwd()
    DESKTOP_APP = os.path.join(REPO_ROOT, "packaging", "desktop_app.py")

# ── Data files to bundle ────────────────────────────────────────────────────
# Keep frontend_dist at its source-relative path so server.py's
# Path(__file__).parent / "frontend_dist" still resolves inside the bundle.
datas = [
    (os.path.join(REPO_ROOT, "opc", "plugins", "office_ui", "frontend_dist"),
     os.path.join("opc", "plugins", "office_ui", "frontend_dist")),
    # Config templates (repo root `config/`) -> config_templates/ in _MEIPASS.
    (os.path.join(REPO_ROOT, "config"), "config_templates"),
    # Skill assets, matching the wheel force-include mapping.
    (os.path.join(REPO_ROOT, "skills", "core"),
     os.path.join("opc", "skills_assets", "core")),
]

# ── Pull heavy packages in full (submodules + native binaries + data) ───────
binaries = []
hiddenimports = []
_collected_pkgs = ["opc", "aiohttp", "chromadb", "litellm", "mcp", "anyio"]
for pkg in _collected_pkgs:
    try:
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h
    except Exception as exc:  # pragma: no cover
        print(f"[spec] collect_all({pkg}) skipped: {exc}")

# collect_all("opc") grabs everything under opc/, including the heavy frontend
# source tree (frontend_src/node_modules, 200MB+ of JS). Drop it — only the
# built frontend_dist (added explicitly above) belongs in the bundle.
datas = [
    (src, dst)
    for (src, dst) in datas
    if "frontend_src" not in src.replace("\\", "/")
]

# Explicit safety net for commonly-missed optional imports.
hiddenimports += [
    "aiohttp.web",
    "aiohttp.web_middlewares",
    "aiosqlite",
    "onnxruntime",
    "hnswlib",
    "tokenizers",
    "numpy",
    "pydantic",
    "pydantic_settings",
    "opc.plugins.office_ui.server",
    "opc.plugins.office_ui.ws_handler",
    "opc.engine",
    "opc.core.config",
    "webview",
    "webview._native.winforms",
    "webview._native.edgechromium",
    "webview._native.cocoa",
    "webview._native.gtk",
]

# ── Exclude optional/headless-only weight to slim the bundle ────────────────
# playwright is handled gracefully by opc (browser tools degrade) and its node
# driver is a known PyInstaller pain — drop it. Channel SDKs / test tooling are
# not needed by the desktop core.
excludes = [
    "playwright",
    "pytest",
    "textual",
    "python-telegram-bot",
    "discord.py",
    "lark-oapi",
    "python-socketio",
    "dingtalk-stream",
    "slack-sdk",
    "qq-botpy",
    "matrix-nio",
    # NOTE: do NOT exclude `websockets` — mcp's WS transport may import it at
    # runtime, and the size cost is negligible.
]

a = Analysis(
    [DESKTOP_APP],
    pathex=[REPO_ROOT],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    excludes=excludes,
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="SafeOPC",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=None,
    console=False,  # native GUI: no console window
    icon=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="SafeOPC",
)
