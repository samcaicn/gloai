"""``opc-raphael-web2api`` CLI — built-in raphael-web2api (Raphael AI) skill surface.

This is the single entry point for the raphael-web2api built-in skill. SafeOPC
installs an ``opc-raphael-web2api`` shim (via :mod:`opc.layer3_agent.skill_installer`)
that dispatches here with ``python -m opc.cli_raphael_web2api``.

raphael-web2api is a local Python + Playwright + FastAPI reverse-proxy that turns
Raphael AI (https://raphael.app — the free, no-login AI image generator) into an
OpenAI-compatible image API on port 8771. This CLI manages the service lifecycle
(setup venv / start / stop / status) and wraps ``POST /v1/images/generations`` so
the agent can produce images without hand-writing curl.

    opc-raphael-web2api start      # setup venv (first time) + launch + wait for health
    opc-raphael-web2api status     # is the proxy reachable?
    opc-raphael-web2api stop       # stop the background proxy
    opc-raphael-web2api config     # print the effective, merged configuration (JSON)
    opc-raphael-web2api gen-image  # POST /v1/images/generations

Configuration resolves as: packaged defaults ->
``<opc_home>/config/raphael-web2api.yaml`` -> CLI flags. The schema lives at
``opc/skills_assets/raphael-web2api/config.schema.json``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import webbrowser
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import yaml

from opc.core.config import get_opc_home

_SKILL_DIR = Path(__file__).resolve().parent / "skills_assets" / "raphael-web2api"
_DEFAULTS_PATH = _SKILL_DIR / "config.default.yaml"

_DEFAULT_PORT = 8771
_HEALTH_TIMEOUT = 2.0
_START_WAIT_ATTEMPTS = 120  # ~60s at 0.5s intervals (browser + CF clearance)


# --------------------------------------------------------------------------- #
# Config resolution
# --------------------------------------------------------------------------- #
def _load_defaults() -> dict[str, Any]:
    if _DEFAULTS_PATH.exists():
        with _DEFAULTS_PATH.open("r", encoding="utf-8") as fh:
            return yaml.safe_load(fh) or {}
    return {}


def _load_user_config(opc_home: Path) -> dict[str, Any]:
    user_path = opc_home / "config" / "raphael-web2api.yaml"
    if user_path.exists():
        try:
            with user_path.open("r", encoding="utf-8") as fh:
                return yaml.safe_load(fh) or {}
        except Exception as exc:  # pragma: no cover - defensive
            sys.stderr.write(f"opc-raphael-web2api: failed to read {user_path}: {exc}\n")
    return {}


def resolve_config(opc_home: Path, overrides: dict[str, Any] | None = None) -> dict[str, Any]:
    """Merge defaults -> user file -> CLI overrides into one config dict."""
    cfg = _load_defaults()
    cfg.update(_load_user_config(opc_home))
    if overrides:
        cfg.update({k: v for k, v in overrides.items() if v is not None})

    install_dir = str(cfg.get("install_dir") or "").strip()
    if not install_dir:
        install_dir = str(opc_home / "integrations" / "raphael-web2api")
    cfg["install_dir"] = install_dir
    cfg.setdefault("host", "127.0.0.1")
    cfg.setdefault("port", _DEFAULT_PORT)
    cfg.setdefault("headless", True)
    cfg.setdefault("cookies_path", "")
    cfg.setdefault("pw_browsers_path", "")
    # Proxies: RAPHAEL_PROXIES env overrides the file/default list. This is the
    # egress-IP rotation pool that bypasses Raphael's per-IP anonymous limit.
    env_proxies = os.getenv("RAPHAEL_PROXIES")
    if env_proxies:
        cfg["proxies"] = [p.strip() for p in re.split(r"[\n,]", env_proxies) if p.strip()]
    cfg.setdefault("proxies", [])
    cfg.setdefault("rotate_proxies", True)
    cfg.setdefault("max_proxy_retries", 8)
    cfg.setdefault("auto_launch", True)
    cfg.setdefault("open_page", True)
    return cfg


# --------------------------------------------------------------------------- #
# Service lifecycle helpers
# --------------------------------------------------------------------------- #
def _repo_dir(cfg: dict[str, Any]) -> Path:
    return Path(cfg["install_dir"])


def _venv_python(repo_dir: Path) -> str:
    if os.name == "nt":
        return str(repo_dir / ".venv" / "Scripts" / "python.exe")
    return str(repo_dir / ".venv" / "bin" / "python3")


def _pidfile(repo_dir: Path) -> Path:
    return repo_dir / ".raphael-web2api.pid"


def _safe_unlink(path: Path) -> None:
    """Best-effort delete that ignores sandbox/locked-environment failures.

    Some sandboxes intercept ``unlink`` and refuse to delete (e.g. no recycle
    bin available). A stale pidfile is non-critical, so never let that block
    the lifecycle commands.
    """
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


def _logfile(repo_dir: Path) -> Path:
    return repo_dir / "raphael-web2api.log"


def _tcp_alive(host: str, port: int, timeout: float = _HEALTH_TIMEOUT) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _is_installed(repo_dir: Path) -> bool:
    vpy = _venv_python(repo_dir)
    return os.path.exists(vpy) and os.path.exists(repo_dir / "server.py")


def _ensure_installed(cfg: dict[str, Any], force: bool = False) -> int:
    repo_dir = _repo_dir(cfg)
    repo_dir.mkdir(parents=True, exist_ok=True)
    # Always refresh the vendored server + requirements (cheap) so the runtime
    # copy stays in sync with the bundled skill after updates — otherwise a
    # user who already ran `setup` once would keep running a stale server.py.
    for fname in ("server.py", "requirements.txt"):
        src = _SKILL_DIR / fname
        if src.exists():
            shutil.copyfile(src, repo_dir / fname)

    vpy = _venv_python(repo_dir)
    if os.path.exists(vpy) and not force:
        print(f"raphael-web2api already installed at {repo_dir}")
        return 0

    if not os.path.exists(vpy) or force:
        print(f"Creating venv at {repo_dir / '.venv'}")
        if subprocess.call([sys.executable, "-m", "venv", str(repo_dir / ".venv")]) != 0:
            sys.stderr.write("opc-raphael-web2api: venv creation failed.\n")
            return 1

    print("Installing dependencies (fastapi, uvicorn, playwright)...")
    pip = [vpy, "-m", "pip", "install", "--quiet", "-r", str(repo_dir / "requirements.txt")]
    if subprocess.call(pip) != 0:
        # Retry once without --quiet for diagnostics.
        sys.stderr.write("opc-raphael-web2api: pip install failed (trying verbose)...\n")
        if subprocess.call([vpy, "-m", "pip", "install", "-r", str(repo_dir / "requirements.txt")]) != 0:
            sys.stderr.write("opc-raphael-web2api: pip install failed.\n")
            return 1

    print(f"raphael-web2api installed at {repo_dir}")
    return 0


def _start_service(cfg: dict[str, Any]) -> int:
    repo_dir = _repo_dir(cfg)
    pid_file = _pidfile(repo_dir)
    if pid_file.exists():
        try:
            old_pid = int(pid_file.read_text().strip())
        except (ValueError, OSError):
            old_pid = None
        if old_pid and _process_alive(old_pid):
            if _tcp_alive(cfg["host"], int(cfg["port"])):
                print(f"raphael-web2api already running (pid {old_pid})")
                return 0
            _safe_unlink(pid_file)

    # The pidfile pid may be a supervisor that exited while its uvicorn worker
    # kept the port. If the port is held by an orphan, kill it before binding.
    if _tcp_alive(cfg["host"], int(cfg["port"])):
        holder = _pid_holding_port(int(cfg["port"]))
        if holder:
            print(f"Port {int(cfg['port'])} still held by orphan pid {holder}; clearing it.")
            if os.name == "nt":
                subprocess.call(["taskkill", "/F", "/T", "/PID", str(holder)])
            else:
                try:
                    os.killpg(os.getpgid(holder), 15)
                except OSError:
                    try:
                        os.kill(holder, 15)
                    except OSError:
                        pass
            time.sleep(1.0)

    env = dict(os.environ)
    env["RAPHAEL_PORT"] = str(int(cfg["port"]))
    env["RAPHAEL_HEADLESS"] = "true" if cfg.get("headless", True) else "false"
    cookies = str(cfg.get("cookies_path") or "").strip()
    if cookies:
        env["RAPHAEL_COOKIES"] = cookies
    pw = str(cfg.get("pw_browsers_path") or "").strip()
    if pw:
        env["RAPHAEL_PW_BROWSERS_PATH"] = pw

    proxies = cfg.get("proxies") or []
    if proxies:
        env["RAPHAEL_PROXIES"] = "\n".join(str(p) for p in proxies)
    env["RAPHAEL_ROTATE_PROXIES"] = "true" if cfg.get("rotate_proxies", True) else "false"
    env["RAPHAEL_MAX_PROXY_RETRIES"] = str(int(cfg.get("max_proxy_retries", 8)))

    vpy = _venv_python(repo_dir)
    if not os.path.exists(vpy):
        sys.stderr.write("opc-raphael-web2api: venv missing; run 'setup' first.\n")
        return 1

    log_path = _logfile(repo_dir)
    log_fh = log_path.open("ab", buffering=0)
    flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if os.name == "nt" else 0
    proc = subprocess.Popen(
        [vpy, str(repo_dir / "server.py")],
        cwd=str(repo_dir),
        env=env,
        stdout=log_fh,
        stderr=subprocess.STDOUT,
        creationflags=flags,
    )
    pid_file.write_text(str(proc.pid))
    print(f"raphael-web2api launched (pid {proc.pid}); logging to {log_path}")

    host, port = cfg["host"], int(cfg["port"])
    print(f"Waiting for raphael-web2api at http://{host}:{port} ", end="", flush=True)
    alive = False
    for i in range(_START_WAIT_ATTEMPTS):
        if _tcp_alive(host, port):
            alive = True
            break
        if i % 4 == 0:
            print(".", end="", flush=True)
        time.sleep(0.5)
    print()
    if not alive:
        sys.stderr.write(
            f"opc-raphael-web2api: proxy did not come up at http://{host}:{port}. "
            f"See {log_path}\n"
        )
        return 1
    print(f"raphael-web2api is live at http://{host}:{port}")
    return 0


def _process_alive(pid: int) -> bool:
    if os.name == "nt":
        try:
            out = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}"],
                capture_output=True,
            )
        except (OSError, ValueError):
            return _ps_pid_alive(pid)
        # ``tasklist`` on localized Windows emits the system code page (e.g.
        # GBK on zh-CN); decode defensively so a non-UTF-8 header never
        # crashes the reader thread and yields a ``None`` stdout.
        text = out.stdout.decode("gbk", errors="replace") if out.stdout else ""
        return str(pid) in text
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _ps_pid_alive(pid: int) -> bool:
    """Fallback liveness probe for environments where ``tasklist`` is absent."""
    try:
        out = subprocess.run(
            ["ps", "-p", str(pid), "-o", "pid="],
            capture_output=True,
        )
        text = out.stdout.decode("utf-8", errors="replace") if out.stdout else ""
        return str(pid) in text
    except (OSError, ValueError):
        # Cannot determine -> assume alive so the caller still attempts a kill.
        return True


def _pid_holding_port(port: int) -> int | None:
    """Return the PID currently LISTENING on ``port`` (Windows ``netstat``)."""
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True)
    except (OSError, ValueError):
        return None
    text = out.stdout.decode("gbk", errors="replace") if out.stdout else ""
    for line in text.splitlines():
        if "LISTENING" not in line or f":{port}" not in line:
            continue
        parts = line.split()
        if not parts:
            continue
        try:
            return int(parts[-1])
        except ValueError:
            continue
    return None


def _stop_service(cfg: dict[str, Any]) -> int:
    repo_dir = _repo_dir(cfg)
    pid_file = _pidfile(repo_dir)
    host, port = cfg["host"], int(cfg["port"])
    killed = False

    if pid_file.exists():
        try:
            pid = int(pid_file.read_text(encoding="utf-8", errors="replace").strip())
        except (ValueError, OSError):
            pid = None
        if pid and _process_alive(pid):
            print(f"Stopping raphael-web2api (pid {pid})...")
            if os.name == "nt":
                subprocess.call(["taskkill", "/F", "/T", "/PID", str(pid)])
            else:
                try:
                    os.killpg(os.getpgid(pid), 15)
                except OSError:
                    try:
                        os.kill(pid, 15)
                    except OSError:
                        pass
            killed = True
        _safe_unlink(pid_file)

    # Robust fallback: uvicorn may spawn a worker that outlives the pidfile pid.
    # If the port is still held, kill whoever actually owns it.
    if _tcp_alive(host, port):
        holder = _pid_holding_port(port)
        if holder:
            print(f"Port {port} still held by pid {holder}; killing it.")
            if os.name == "nt":
                subprocess.call(["taskkill", "/F", "/T", "/PID", str(holder)])
            else:
                try:
                    os.killpg(os.getpgid(holder), 15)
                except OSError:
                    try:
                        os.kill(holder, 15)
                    except OSError:
                        pass
            killed = True

    if not killed:
        print("raphael-web2api not running")
    else:
        print("stopped")
    return 0


# --------------------------------------------------------------------------- #
# HTTP helpers (OpenAI-compatible endpoint)
# --------------------------------------------------------------------------- #
def _http_post_json(cfg: dict[str, Any], path: str, payload: dict[str, Any]) -> dict[str, Any]:
    url = f"http://{cfg['host']}:{int(cfg['port'])}{path}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        sys.stderr.write(f"opc-raphael-web2api: POST {url} -> {exc.code}: {body}\n")
        return {"error": f"HTTP {exc.code}", "detail": body}
    except urllib.error.URLError as exc:
        sys.stderr.write(f"opc-raphael-web2api: cannot reach {url}: {exc.reason}\n")
        return {"error": "connection_failed", "detail": str(exc.reason)}


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #
def _cmd_status(cfg: dict[str, Any]) -> int:
    ok = _tcp_alive(cfg["host"], int(cfg["port"]))
    print(json.dumps({"ok": ok, "host": cfg["host"], "port": int(cfg["port"])}, ensure_ascii=False))
    return 0 if ok else 1


def _cmd_setup(cfg: dict[str, Any], force: bool = False) -> int:
    return _ensure_installed(cfg, force=force)


def _discover_office_ui_port(opc_home: Path) -> int:
    port_file = opc_home / "office_ui.port"
    if port_file.exists():
        try:
            return int(port_file.read_text(encoding="utf-8").strip())
        except Exception:
            pass
    env_port = os.environ.get("SAFEOPC_PORT")
    if env_port:
        try:
            return int(env_port)
        except Exception:
            pass
    return 8765


def _open_in_app_browser(url: str, title: str, opc_home: Path) -> bool:
    port = _discover_office_ui_port(opc_home)
    endpoint = f"http://127.0.0.1:{port}/api/ui/open-browser"
    payload = json.dumps({"url": url, "title": title}).encode("utf-8")
    req = urllib.request.Request(
        endpoint, data=payload, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return bool(data.get("ok"))
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
        return False


def _cmd_start(cfg: dict[str, Any], no_browser: bool, opc_home: Path, force: bool = False) -> int:
    if cfg.get("auto_launch", True):
        # Always ensure: copies the latest vendored server.py/requirements.txt
        # and only (re)creates the venv when missing or --force is given.
        if _ensure_installed(cfg, force=force) != 0:
            return 1
        if _start_service(cfg) != 0:
            return 1
    else:
        print("auto_launch is false; not launching the proxy.")

    url = f"http://{cfg['host']}:{int(cfg['port'])}/health"
    if no_browser or not cfg.get("open_page", True):
        print(f"Open this URL in SafeOPC's built-in browser: {url}")
        return 0

    if _open_in_app_browser(url, "raphael-web2api", opc_home):
        print("Opened the health page in SafeOPC's built-in browser.")
        return 0
    try:
        webbrowser.open(url)
        print("Opened the health page in your default browser.")
    except Exception as exc:  # pragma: no cover - environment dependent
        sys.stderr.write(f"opc-raphael-web2api: could not auto-open browser: {exc}\n")
        print(f"Open this URL manually: {url}")
    return 0


def _cmd_stop(cfg: dict[str, Any]) -> int:
    return _stop_service(cfg)


def _cmd_config(cfg: dict[str, Any]) -> int:
    print(json.dumps(cfg, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def _cmd_gen_image(cfg: dict[str, Any], args: argparse.Namespace) -> int:
    payload: dict[str, Any] = {"prompt": args.prompt}
    if args.aspect_ratio:
        payload["aspect_ratio"] = args.aspect_ratio
    if args.n is not None:
        payload["n"] = args.n
    if args.negative_prompt:
        payload["negative_prompt"] = args.negative_prompt
    if args.model:
        payload["model"] = args.model
    result = _http_post_json(cfg, "/v1/images/generations", payload)
    if "error" in result:
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


# --------------------------------------------------------------------------- #
# Arg parsing
# --------------------------------------------------------------------------- #
def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="opc-raphael-web2api",
        description="raphael-web2api built-in skill CLI: run the Raphael AI proxy and generate images.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True, metavar="<command>")

    sub.add_parser("status", help="Check if the proxy is reachable.")
    sub.add_parser("stop", help="Stop the background proxy.")
    sub.add_parser("config", help="Print the effective, merged configuration (JSON).")

    setup_p = sub.add_parser("setup", help="Create venv + pip install (first time).")
    setup_p.add_argument("--force", action="store_true", help="Reinstall even if present.")

    start_p = sub.add_parser("start", help="Setup (if needed) + launch + wait for health.")
    start_p.add_argument("--no-browser", action="store_true", help="Do not open the health page.")
    start_p.add_argument("--force", action="store_true", help="Recreate venv before launching.")
    start_p.add_argument("--host", default=None, help="Override the bind host.")
    start_p.add_argument("--port", type=int, default=None, help="Override the bind port.")

    img_p = sub.add_parser("gen-image", help="Generate an image (POST /v1/images/generations).")
    img_p.add_argument("--prompt", required=True, help="Text prompt.")
    img_p.add_argument("--aspect-ratio", default=None, help="Aspect ratio, e.g. 1:1, 16:9.")
    img_p.add_argument("--n", type=int, default=None, help="Number of images (1..4).")
    img_p.add_argument("--negative-prompt", default=None, help="Negative prompt.")
    img_p.add_argument("--model", default=None, help="Model name (best-effort UI pick; optional).")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    opts = parser.parse_args(argv)
    opc_home = get_opc_home()

    overrides: dict[str, Any] = {}
    if opts.cmd == "start":
        overrides["host"] = opts.host
        overrides["port"] = opts.port
    cfg = resolve_config(opc_home, overrides)

    if opts.cmd == "status":
        return _cmd_status(cfg)
    if opts.cmd == "stop":
        return _cmd_stop(cfg)
    if opts.cmd == "config":
        return _cmd_config(cfg)
    if opts.cmd == "setup":
        return _cmd_setup(cfg, force=opts.force)
    if opts.cmd == "start":
        return _cmd_start(cfg, no_browser=opts.no_browser, opc_home=opc_home, force=opts.force)
    if opts.cmd == "gen-image":
        return _cmd_gen_image(cfg, opts)
    parser.error("unknown command")
    return 2  # pragma: no cover


if __name__ == "__main__":
    raise SystemExit(main())
