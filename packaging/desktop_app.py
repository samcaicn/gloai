"""SafeOPC Desktop — pywebview shell around the office-ui aiohttp server.

Strategy
--------
The SafeOPC backend is an aiohttp service (OPCEngine + WebSocket + the built
React/Phaser frontend). We keep that server exactly as-is and wrap it in a
native desktop window via pywebview (system WebView: WebView2 / WKWebView /
WebKitGTK). Nothing about the frontend or engine changes — we only add a
thin shell that:

  1. Pins OPC_HOME to a user-writable location (frozen bundles have no stable
     cwd, and we must not write into the read-only dist/ directory).
  2. Seeds a default config into OPC_HOME on first run so the user has a
     llm_config.yaml to edit.
  3. Starts the office-ui server on 127.0.0.1 (no LAN exposure).
  4. Opens a native window pointing at the local server.

Env
---
SAFEOPC_HEADLESS=1  -> start the server only (no window). Used for CI/smoke
                       tests on headless machines where no display exists.
SAFEOPC_PORT=<int>  -> override the server port (default 8765).
"""

from __future__ import annotations

import ctypes
import logging
import os
import shutil
import socket
import sys
import threading
from ctypes import wintypes
from pathlib import Path

LOG = logging.getLogger("safeopc.desktop")

DEFAULT_PORT = 8765

# ── Native splash ───────────────────────────────────────────────────────────
# The visible splash is a dependency-free Win32 GDI window (see _NativeSplash);
# it paints in well under a second and does NOT depend on WebView2. WebView2 is
# just too slow to initialise to serve as a splash itself. ERROR_HTML below is
# only the transient placeholder shown inside the WebView before the real UI
# loads (the splash covers it anyway).

ERROR_HTML = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<style>
  html,body{margin:0;height:100%;background:#0f1115;color:#e6edf3;
    font-family:-apple-system,"Segoe UI",Roboto,"Microsoft YaHei",sans-serif;
    display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px;text-align:center;}
  .logo{font-size:32px;font-weight:800;color:#ff6b6b;margin-bottom:14px;}
  .msg{font-size:14px;color:#c9d4df;max-width:520px;line-height:1.6;word-break:break-all;}
</style></head><body>
  <div class="logo">SafeOPC</div>
  <div class="msg">%s</div>
</body></html>"""

# Transient placeholder shown inside the WebView before the real UI loads.
# The native splash covers this anyway, so it just needs to be a stable dark
# page with NO '%' formatting (ERROR_HTML uses %s and would break %-format).
PLACEHOLDER_HTML = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<style>html,body{margin:0;height:100%;background:#0f1115;}</style></head>
<body></body></html>"""


def _show_error(title: str, message: str) -> None:
    """Best-effort native message box — no extra deps, works when frozen.

    Used so startup failures are never silent (console is hidden in the
    built app, and Windows may swallow tracebacks on double-click).
    """
    try:
        import ctypes

        ctypes.windll.user32.MessageBoxW(0, str(message), str(title), 0x10)  # MB_ICONERROR
    except Exception:
        pass


def find_free_port(preferred: int, max_tries: int = 50) -> int:
    """Return `preferred` if free, otherwise the next free TCP port.

    A second launch (or a leftover dev server) would otherwise crash with
    'address already in use'. We probe and fall through instead.
    """
    for offset in range(max_tries):
        cand = preferred + offset
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.2)
            try:
                s.bind(("127.0.0.1", cand))
                return cand
            except OSError:
                continue
    LOG.warning("Could not find a free port near %d; using %d anyway", preferred, preferred)
    return preferred


# ── OPC_HOME ────────────────────────────────────────────────────────────────

def resolve_opc_home() -> Path:
    """Return a stable, writable OPC data dir, forcing $OPC_HOME if unset.

    When frozen by PyInstaller the application lives in a temp dir and cwd is
    unpredictable, so we must never let get_opc_home() fall back to
    {project_root}/.opc. We point it at the platform user-data location.
    """
    existing = os.environ.get("OPC_HOME")
    if existing:
        home = Path(existing)
    elif sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home()))
        home = base / "SafeOPC"
    elif sys.platform == "darwin":
        home = Path.home() / "Library" / "Application Support" / "SafeOPC"
    else:
        home = Path.home() / ".config" / "SafeOPC"

    home.mkdir(parents=True, exist_ok=True)
    os.environ["OPC_HOME"] = str(home)
    return home


# ── Default config seeding ──────────────────────────────────────────────────

def _config_template_dir() -> Path | None:
    """Locate the bundled config templates.

    Editable installs don't apply the wheel's force-include mapping, so the
    templates live at the repo root `config/`. Frozen builds get them via the
    PyInstaller datas entry copied to `config_templates/` under _MEIPASS.
    """
    if getattr(sys, "frozen", False):
        cand = Path(sys._MEIPASS) / "config_templates"
        if cand.is_dir():
            return cand
    # repo-root layout: packaging/desktop_app.py -> ../config
    repo_config = Path(__file__).resolve().parents[1] / "config"
    if repo_config.is_dir():
        return repo_config
    return None


def seed_default_config(opc_home: Path) -> None:
    """Copy config templates into OPC_HOME/config on first run."""
    target = opc_home / "config"
    if target.is_dir() and any(target.iterdir()):
        return
    src = _config_template_dir()
    if src is None:
        LOG.warning("No config templates found; starting with empty config dir.")
        target.mkdir(parents=True, exist_ok=True)
        return
    target.mkdir(parents=True, exist_ok=True)
    for item in src.iterdir():
        if item.is_file():
            shutil.copy2(item, target / item.name)
    LOG.info("Seeded default config into %s", target)


# ── Logging to file (console is hidden in the built app) ────────────────────

def _configure_logging(opc_home: Path) -> None:
    log_file = opc_home / "desktop.log"
    try:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(name)s: %(message)s",
            handlers=[
                logging.FileHandler(log_file, encoding="utf-8"),
                logging.StreamHandler(sys.stderr),
            ],
        )
    except Exception as exc:  # never let logging setup crash the app
        print(f"[safeopc] logging setup failed: {exc}", file=sys.stderr)


# ── Server lifecycle ────────────────────────────────────────────────────────

def _start_server_thread(port: int) -> threading.Thread:
    """Run the office-ui aiohttp server in a daemon thread."""
    from opc.plugins.office_ui.server import run_server

    def _target() -> None:
        try:
            # Belt-and-suspenders: ensure litellm telemetry is off at runtime too.
            try:
                import litellm

                litellm.telemetry = False
            except Exception:
                pass
            run_server(host="127.0.0.1", port=port)
        except Exception as exc:  # surface but don't kill the window thread
            LOG.exception("office-ui server exited: %s", exc)

    t = threading.Thread(target=_target, name="office-ui-server", daemon=True)
    t.start()
    return t


def _wait_for_server(port: int, timeout: float = 60.0) -> bool:
    """Block until the local server answers, or timeout."""
    import socket
    import time

    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1.0):
                return True
        except OSError:
            time.sleep(0.25)
    return False


# ── CreatorHub sidecar (optional in-app integration) ─────────────────────────

def _creatorhub_app_dir() -> Path | None:
    """Locate the bundled CreatorHub FastAPI app.

    Dev: repo_root/integrations/creatorhub. Frozen: _MEIPASS/integrations/creatorhub
    (added as a datas entry in safeopc.spec).
    """
    if getattr(sys, "frozen", False):
        cand = Path(sys._MEIPASS) / "integrations" / "creatorhub"
        if cand.is_dir():
            return cand
    # packaging/desktop_app.py -> repo root
    dev = Path(__file__).resolve().parents[1] / "integrations" / "creatorhub"
    if dev.is_dir():
        return dev
    return None


def _maybe_launch_creatorhub() -> None:
    """Auto-launch the CreatorHub sidecar (uvicorn on :8000) in a daemon thread.

    Gated by ``SAFEOPC_CREATORHUB_AUTOSTART`` (default "1"; set "0" to disable).
    The sidecar runs in its own isolated venv (see creatorhub_adapter.launcher)
    so its FastAPI / Patchright deps never clash with SafeOPC's environment.
    """
    if os.environ.get("SAFEOPC_CREATORHUB_AUTOSTART", "1") == "0":
        LOG.info("CreatorHub autostart disabled via SAFEOPC_CREATORHUB_AUTOSTART=0")
        return
    app_dir = _creatorhub_app_dir()
    if app_dir is None:
        LOG.warning("CreatorHub app dir not found; skipping autostart.")
        return
    try:
        from opc.integrations.creatorhub_adapter.launcher import CreatorHubLauncher
    except Exception as exc:  # pragma: no cover - import guard
        LOG.warning("CreatorHub launcher import failed; skipping autostart: %s", exc)
        return

    opc_home = resolve_opc_home()
    data_root = opc_home / "integrations" / "creatorhub"
    venv_dir = data_root / ".venv"

    def _bg() -> None:
        try:
            launcher = CreatorHubLauncher(
                creatorhub_dir=app_dir,
                data_root=data_root,
                port=8000,
                host="127.0.0.1",
            )
            # The bundled app dir is read-only (under _MEIPASS when frozen), so
            # the sidecar venv must live in the writable opc_home, not inside
            # creatorhub_dir. Override both derived paths.
            launcher.venv_dir = venv_dir
            launcher.provision_marker = venv_dir / ".creatorhub_provisioned"
            launcher.ensure_venv()
            launcher.write_config()
            if not launcher.is_running():
                launcher.start()
            LOG.info("CreatorHub sidecar launched (pid file: %s)", launcher.pid_file)
        except Exception as exc:  # never crash the desktop app on sidecar failure
            LOG.exception("CreatorHub sidecar launch failed: %s", exc)

    threading.Thread(target=_bg, name="creatorhub-sidecar", daemon=True).start()


def show_loading(window) -> None:
    """Placeholder kept for API symmetry; splash is now a native Tk window."""
    pass


def show_error_html(window, message: str) -> None:
    """Placeholder kept for API symmetry; errors surface via Tk/MessageBox."""
    pass


# ── Native splash Win32 structures (module level: nested classes cannot see
#    the enclosing class scope, so they must live at module level) ───────────
class _SplashRect(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


class _SplashPaintStruct(ctypes.Structure):
    _fields_ = [
        ("hdc", wintypes.HDC), ("fErase", ctypes.c_int),
        ("rcPaint", _SplashRect), ("fRestore", ctypes.c_int),
        ("fIncUpdate", ctypes.c_int),
        ("rgbReserved", ctypes.c_byte * 32)]


class _SplashMsg(ctypes.Structure):
    _fields_ = [
        ("hWnd", wintypes.HWND), ("message", wintypes.UINT),
        ("wParam", wintypes.WPARAM), ("lParam", wintypes.LPARAM),
        ("time", ctypes.c_ulong), ("pt", ctypes.c_long * 2)]


_SplashWNDPROC = ctypes.WINFUNCTYPE(
    ctypes.c_longlong, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)


class _SplashWndClass(ctypes.Structure):
    _fields_ = [
        ("style", ctypes.c_uint),
        ("lpfnWndProc", _SplashWNDPROC),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE),      # wintypes has NO HCURSOR
        ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]


class _NativeSplash:
    """Dependency-free native splash window drawn with Win32 GDI (ctypes).

    Why not WebView2 / Tk: a WebView2 window is just as slow to initialise as
    the real app (so it can't beat the black screen), and Tk isn't bundled in
    the frozen build. A raw Win32 popup paints in well under a second and needs
    nothing but user32/gdi32, which every Windows box has. It shows the SafeOPC
    logo, a rotating disc and a sliding progress bar during the boot.

    The window runs its own message loop on a daemon thread; `close()` posts a
    quit message so the main flow can drop it the moment the real UI is ready.

    IMPORTANT (64-bit correctness): every Win32/GDI prototype below has explicit
    argtypes/restype. Without them ctypes truncates pointer-sized handles
    (HWND/HINSTANCE/HBRUSH/...) to 32 bits on 64-bit Python, which silently
    breaks window creation. Also note `ctypes.wintypes` has NO `HCURSOR`, so the
    cursor field must be typed as HANDLE.
    """

    def __init__(self, width: int = 460, height: int = 300) -> None:
        self._width = width
        self._height = height
        self._hwnd = None
        self._spin = 0.0
        self._bar = 0.0
        self._closed = False
        self._ok = False
        self._u32 = None
        self._g32 = None

    # ── colours (GDI uses COLORREF = 0x00BBGGRR) ──────────────────────────
    _BG = 0x15110F      # #0f1115
    _BLUE = 0xFFA25A    # #ffa25a
    _TEAL = 0xC4D136    # #36d1c4
    _TRACK = 0x332A23   # #232a33
    _GRAY = 0xA5988B    # #8b98a5

    def start(self) -> None:
        if sys.platform != "win32":
            return
        threading.Thread(target=self._run, name="win32-splash", daemon=True).start()

    @staticmethod
    def _declare_prototypes(u32, g32) -> None:
        """Pin exact argtypes/restype so 64-bit handles aren't truncated."""
        u32.RegisterClassW.argtypes = [ctypes.POINTER(_SplashWndClass)]
        u32.RegisterClassW.restype = ctypes.c_ushort

        u32.CreateWindowExW.argtypes = [
            wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
            ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID]
        u32.CreateWindowExW.restype = wintypes.HWND

        u32.DefWindowProcW.argtypes = [
            wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
        u32.DefWindowProcW.restype = ctypes.c_longlong

        u32.GetMessageW.argtypes = [
            ctypes.POINTER(_SplashMsg), wintypes.HWND, wintypes.UINT, wintypes.UINT]
        u32.GetMessageW.restype = ctypes.c_int

        u32.TranslateMessage.argtypes = [ctypes.POINTER(_SplashMsg)]
        u32.TranslateMessage.restype = ctypes.c_int

        u32.DispatchMessageW.argtypes = [ctypes.POINTER(_SplashMsg)]
        u32.DispatchMessageW.restype = ctypes.c_longlong

        u32.BeginPaint.argtypes = [wintypes.HWND, ctypes.POINTER(_SplashPaintStruct)]
        u32.BeginPaint.restype = wintypes.HDC

        u32.EndPaint.argtypes = [wintypes.HWND, ctypes.POINTER(_SplashPaintStruct)]
        u32.EndPaint.restype = ctypes.c_int

        u32.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(_SplashRect)]
        u32.GetClientRect.restype = ctypes.c_int

        u32.InvalidateRect.argtypes = [
            wintypes.HWND, ctypes.POINTER(_SplashRect), ctypes.c_int]
        u32.InvalidateRect.restype = ctypes.c_int

        u32.SetTimer.argtypes = [
            wintypes.HWND, ctypes.c_ulonglong, wintypes.UINT, ctypes.c_void_p]
        u32.SetTimer.restype = ctypes.c_ulonglong

        u32.DestroyWindow.argtypes = [wintypes.HWND]
        u32.DestroyWindow.restype = ctypes.c_int

        u32.PostMessageW.argtypes = [
            wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
        u32.PostMessageW.restype = ctypes.c_int

        u32.PostQuitMessage.argtypes = [ctypes.c_int]
        u32.PostQuitMessage.restype = None

        u32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
        u32.ShowWindow.restype = ctypes.c_int

        g32.CreateSolidBrush.argtypes = [wintypes.COLORREF]
        g32.CreateSolidBrush.restype = wintypes.HBRUSH

        g32.CreateFontW.argtypes = [
            ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
            wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
            wintypes.LPCWSTR]
        g32.CreateFontW.restype = wintypes.HFONT

        g32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
        g32.SelectObject.restype = wintypes.HGDIOBJ

        g32.SetBkMode.argtypes = [wintypes.HDC, ctypes.c_int]
        g32.SetBkMode.restype = ctypes.c_int

        g32.SetTextColor.argtypes = [wintypes.HDC, wintypes.COLORREF]
        g32.SetTextColor.restype = wintypes.COLORREF

        g32.TextOutW.argtypes = [
            wintypes.HDC, ctypes.c_int, ctypes.c_int, wintypes.LPCWSTR, ctypes.c_int]
        g32.TextOutW.restype = ctypes.c_int

        g32.PatBlt.argtypes = [
            wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            wintypes.DWORD]
        g32.PatBlt.restype = ctypes.c_int

        g32.Ellipse.argtypes = [
            wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        g32.Ellipse.restype = ctypes.c_int

        g32.Pie.argtypes = [
            wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        g32.Pie.restype = ctypes.c_int

        g32.Rectangle.argtypes = [
            wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int]
        g32.Rectangle.restype = ctypes.c_int

        g32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
        g32.DeleteObject.restype = ctypes.c_int

    def _run(self) -> None:
        import math

        try:
            self._run_inner(math)
        except Exception:
            LOG.exception("[SPLASH] native splash thread crashed")

    def _run_inner(self, math) -> None:
        u32 = ctypes.windll.user32
        g32 = ctypes.windll.gdi32
        k32 = ctypes.windll.kernel32
        self._u32 = u32
        self._g32 = g32
        self._math = math

        WM_DESTROY = 0x0002
        WM_PAINT = 0x000F
        WM_TIMER = 0x0113
        WM_CLOSE = 0x0010

        # Module-level Win32 types.
        WNDCLASS = _SplashWndClass
        RECT = _SplashRect
        PAINTSTRUCT = _SplashPaintStruct
        MSG = _SplashMsg
        WNDPROC = _SplashWNDPROC

        self._declare_prototypes(u32, g32)

        def _wndproc(hwnd, msg, wp, lp):
            if msg == WM_PAINT:
                ps = PAINTSTRUCT()
                hdc = u32.BeginPaint(hwnd, ctypes.byref(ps))
                self._draw(hdc)
                u32.EndPaint(hwnd, ctypes.byref(ps))
                return 0
            if msg == WM_TIMER:
                self._spin = (self._spin + 12) % 360
                self._bar += 6
                u32.InvalidateRect(hwnd, None, False)
                return 0
            if msg == WM_DESTROY:
                u32.PostQuitMessage(0)
                return 0
            return u32.DefWindowProcW(hwnd, msg, wp, lp)

        proc = WNDPROC(_wndproc)
        self._proc = proc  # keep ref alive

        hinst = k32.GetModuleHandleW(None)
        clsname = "SafeOPCSplash"
        wc = WNDCLASS()
        wc.style = 0
        wc.lpfnWndProc = proc
        wc.hInstance = hinst
        wc.hbrBackground = None
        wc.lpszClassName = clsname
        if not u32.RegisterClassW(ctypes.byref(wc)):
            LOG.error("[SPLASH] RegisterClassW failed — native splash will NOT show")
            return

        sw = u32.GetSystemMetrics(0)  # SM_CXSCREEN
        sh = u32.GetSystemMetrics(1)  # SM_CYSCREEN
        x = max(0, (sw - self._width) // 2)
        y = max(0, (sh - self._height) // 2)
        hwnd = u32.CreateWindowExW(
            0x8,  # WS_EX_TOPMOST
            clsname, "SafeOPC",
            0x80000000 | 0x10000000,  # WS_POPUP | WS_VISIBLE
            x, y, self._width, self._height,
            0, 0, hinst, None)
        if not hwnd:
            LOG.error("[SPLASH] CreateWindowExW failed (hwnd=%s) — native splash "
                      "will NOT show", hwnd)
            return
        self._hwnd = hwnd
        self._ok = True
        LOG.info("[SPLASH] native splash window created (hwnd=%s)", hwnd)
        u32.SetTimer(hwnd, 1, 40, None)  # ~25 fps animation

        msg = MSG()
        while u32.GetMessageW(ctypes.byref(msg), 0, 0, 0) > 0:
            u32.TranslateMessage(ctypes.byref(msg))
            u32.DispatchMessageW(ctypes.byref(msg))

    def _draw(self, hdc) -> None:
        import ctypes

        g32 = self._g32
        u32 = self._u32
        RECT = _SplashRect

        r = RECT()
        u32.GetClientRect(self._hwnd, ctypes.byref(r))
        w = r.right - r.left
        h = r.bottom - r.top
        cx = w // 2
        cy = h // 2 - 8

        # background (PatBlt with the current brush; FillRect is forwarded to
        # user32 on some Windows builds and isn't a direct gdi32 export)
        b = g32.CreateSolidBrush(self._BG)
        ob = g32.SelectObject(hdc, b)
        g32.PatBlt(hdc, r.left, r.top, r.right - r.left, r.bottom - r.top,
                  0x00F00021)  # PATCOPY
        g32.SelectObject(hdc, ob)
        g32.DeleteObject(b)

        # logo
        f = g32.CreateFontW(-34, 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 0, 0, "Segoe UI")
        old = g32.SelectObject(hdc, f)
        g32.SetBkMode(hdc, 1)  # TRANSPARENT
        g32.SetTextColor(hdc, self._BLUE)
        txt = "SafeOPC"
        g32.TextOutW(hdc, cx - 63, cy - 76, txt, len(txt))
        g32.SelectObject(hdc, old)
        g32.DeleteObject(f)

        # gray base disc
        rad = 26
        pb = g32.CreateSolidBrush(self._TRACK)
        pp = g32.CreatePen(0, 1, self._TRACK)
        ob = g32.SelectObject(hdc, pb)
        op = g32.SelectObject(hdc, pp)
        g32.Ellipse(hdc, cx - rad, cy - rad, cx + rad, cy + rad)
        g32.SelectObject(hdc, ob)
        g32.SelectObject(hdc, op)
        g32.DeleteObject(pb)
        g32.DeleteObject(pp)

        # rotating blue wedge (70°)
        m = self._math
        a1 = m.radians(self._spin)
        a2 = a1 + m.radians(70)
        p1x = int(cx + rad * m.cos(a1))
        p1y = int(cy - rad * m.sin(a1))
        p2x = int(cx + rad * m.cos(a2))
        p2y = int(cy - rad * m.sin(a2))
        bb = g32.CreateSolidBrush(self._BLUE)
        bp = g32.CreatePen(0, 1, self._BLUE)
        ob = g32.SelectObject(hdc, bb)
        op = g32.SelectObject(hdc, bp)
        g32.Pie(hdc, cx - rad, cy - rad, cx + rad, cy + rad, p1x, p1y, p2x, p2y)
        g32.SelectObject(hdc, ob)
        g32.SelectObject(hdc, op)
        g32.DeleteObject(bb)
        g32.DeleteObject(bp)

        # progress bar track
        barw = 240
        bx = cx - barw // 2
        by = cy + 62
        tb = g32.CreateSolidBrush(self._TRACK)
        tp = g32.CreatePen(0, 1, self._TRACK)
        ob = g32.SelectObject(hdc, tb)
        op = g32.SelectObject(hdc, tp)
        g32.Rectangle(hdc, bx, by, bx + barw, by + 6)
        g32.SelectObject(hdc, ob)
        g32.SelectObject(hdc, op)
        g32.DeleteObject(tb)
        g32.DeleteObject(tp)

        # sliding teal fill (ping-pong)
        span = int(barw * 0.4)
        pos = int(self._bar % (2 * (barw - span)))
        if pos > (barw - span):
            pos = 2 * (barw - span) - pos
        fb = g32.CreateSolidBrush(self._TEAL)
        fp = g32.CreatePen(0, 1, self._TEAL)
        ob = g32.SelectObject(hdc, fb)
        op = g32.SelectObject(hdc, fp)
        g32.Rectangle(hdc, bx + pos, by, bx + pos + span, by + 6)
        g32.SelectObject(hdc, ob)
        g32.SelectObject(hdc, op)
        g32.DeleteObject(fb)
        g32.DeleteObject(fp)

        # status text
        f2 = g32.CreateFontW(-12, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 0, 0, "Segoe UI")
        old = g32.SelectObject(hdc, f2)
        g32.SetTextColor(hdc, self._GRAY)
        tip = "正在启动，请稍候…"
        g32.TextOutW(hdc, cx - 60, cy + 88, tip, len(tip))
        g32.SelectObject(hdc, old)
        g32.DeleteObject(f2)

    def close(self) -> None:
        if self._closed or not self._ok:
            return
        self._closed = True
        try:
            self._u32.PostMessageW(self._hwnd, 0x0010, 0, 0)  # WM_CLOSE
        except Exception:
            pass


def _swap_to_app(window, port: int, splash, t0=None, timeout: float = 60.0) -> None:
    """Wait for the backend, then reveal the real UI and dismiss the splash."""
    import time as _t

    if t0 is None:
        t0 = _t.time()
    ready = _wait_for_server(port, timeout)
    if not ready:
        LOG.error("Backend did not start within %.0fs", timeout)
        splash.close()
        _show_error("SafeOPC 启动失败", "后端服务启动超时，请重试或查看日志。")
        os._exit(1)
        return
    LOG.info("[TIMING] backend ready at +%.2fs — loading real UI", _t.time() - t0)
    try:
        window.load_url(f"http://127.0.0.1:{port}/")
    except Exception as exc:
        LOG.exception("load_url failed: %s", exc)
        splash.close()
        _show_error("SafeOPC 启动失败", f"无法加载界面：{exc}")
        os._exit(1)
        return
    # Give the WebView a moment to render the real page before we drop the
    # splash, so the user doesn't see a flash of unstyled/blank content.
    _t.sleep(1.0)
    try:
        window.show()
    except Exception:
        pass
    LOG.info("[TIMING] app window revealed at +%.2fs — dismissing splash", _t.time() - t0)
    splash.close()
    LOG.info("Splash dismissed; app window is live.")


# ── Modes ───────────────────────────────────────────────────────────────────

def run_headless(port: int) -> None:
    """Server-only mode for smoke tests (no display required)."""
    port = find_free_port(port)
    LOG.info("HEADLESS mode: starting office-ui server on 127.0.0.1:%d", port)
    from opc.plugins.office_ui.server import run_server

    run_server(host="127.0.0.1", port=port)


def run_gui(port: int) -> None:
    """Native-window mode: instant Tk splash, parallel backend + WebView boot."""
    import time as _t

    t0 = _t.time()
    LOG.info("[TIMING] run_gui entered at +%.2fs", 0.0)
    try:
        import webview
    except Exception as exc:  # missing pywebview / WebView2 runtime
        _show_error(
            "SafeOPC 无法启动",
            "窗口组件加载失败。请确认系统已安装 Microsoft Edge WebView2 运行时"
            "（https://developer.microsoft.com/microsoft-edge/webview2/）。\n\n"
            + str(exc),
        )
        os._exit(1)

    # 1.5) Dev/test only: if config enables debug_cdp, expose WebView2's CDP
    #      port so Playwright can connect_over_cdp to SafeOPC's own window.
    try:
        from opc.core.config import OPCConfig, get_opc_home

        _cfg = OPCConfig.load(get_opc_home() / "config")
        _b = getattr(_cfg.system, "browser", None)
        if _b and getattr(_b, "debug_cdp", False):
            _cdp_port = int(getattr(_b, "cdp_port", 9222) or 9222)
            os.environ["WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"] = (
                f"--remote-debugging-port={_cdp_port}"
            )
            LOG.info("[CDP] WebView2 remote debugging enabled on port %d", _cdp_port)
    except Exception as _cdp_err:  # config unavailable -> skip, no CDP
        LOG.debug("debug_cdp check skipped: %s", _cdp_err)

    port = find_free_port(port)
    LOG.info("[TIMING] webview imported, port=%d at +%.2fs", port, _t.time() - t0)

    # 1) Show the native Win32 splash IMMEDIATELY (sub-second, no WebView2).
    #    This is what the user sees during the ~14s backend boot.
    splash = _NativeSplash()
    splash.start()
    LOG.info("[TIMING] native splash started at +%.2fs", _t.time() - t0)

    # 2) Boot the backend in a daemon thread in parallel with the splash.
    _start_server_thread(port)

    # 2.5) Auto-launch the CreatorHub sidecar (if enabled) so its in-app page
    #      is ready when the user opens it from the sidebar.
    _maybe_launch_creatorhub()

    # 3) Create the WebView window HIDDEN first; it is revealed by the swap
    #    thread once the real UI is ready (so it never covers the splash or
    #    flashes a blank white page).
    window = webview.create_window(
        "SafeOPC",
        html=PLACEHOLDER_HTML,
        width=1280,
        height=800,
        min_size=(960, 600),
        background_color="#0f1115",
        hidden=True,
    )
    LOG.info("[TIMING] webview window created (hidden) at +%.2fs", _t.time() - t0)

    # 4) Wait for the backend, then load the real UI and dismiss the splash.
    threading.Thread(
        target=_swap_to_app,
        args=(window, port, splash, t0),
        name="splash-swap",
        daemon=True,
    ).start()

    # 5) Enter the webview event loop (splash is already visible on top).
    webview.start()
    # Window closed -> exit the whole process (server thread is daemon).
    LOG.info("Window closed; exiting.")
    os._exit(0)


def main() -> None:
    try:
        _real_main()
    except Exception as exc:  # never exit silently
        import traceback

        tb = traceback.format_exc()
        LOG.exception("Fatal error during startup")
        _show_error("SafeOPC 启动失败", f"{exc}\n\n{tb}")
        os._exit(1)


def _apply_litellm_speedups() -> None:
    """Shave ~10s off cold start.

    LiteLLM tries to fetch a remote model-cost map on import and bangs into a
    ~10s network timeout on air-gapped / slow machines before falling back to
    its local copy. Point the URL at a local dead address (immediate refuse,
    not a timeout) and disable telemetry so startup doesn't block on the net.
    Must run BEFORE litellm is imported (i.e. before the server thread starts).
    """
    os.environ.setdefault(
        "LITELLM_MODEL_COST_MAP_URL", "http://127.0.0.1:1/model_prices.json"
    )
    os.environ.setdefault("LITELLM_TELEMETRY", "False")


def _real_main() -> None:
    _apply_litellm_speedups()
    port = int(os.environ.get("SAFEOPC_PORT", DEFAULT_PORT))
    opc_home = resolve_opc_home()
    _configure_logging(opc_home)
    seed_default_config(opc_home)

    if os.environ.get("SAFEOPC_HEADLESS") == "1":
        run_headless(port)
    else:
        run_gui(port)


if __name__ == "__main__":
    main()
