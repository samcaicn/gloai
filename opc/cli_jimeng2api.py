"""``opc-jimeng2api`` CLI — built-in jimeng2api (Jimeng / Dreamina) skill surface.

This is the single entry point for the jimeng2api built-in skill. SafeOPC
installs an ``opc-jimeng2api`` shim (via :mod:`opc.layer3_agent.skill_installer`)
that dispatches here with ``python -m opc.cli_jimeng2api``.

jimeng2api (https://github.com/carzygod/jimeng2api) is a local Node.js reverse
proxy that exposes Jimeng / Dreamina image & video generation as OpenAI-
compatible HTTP endpoints on port 5100. This CLI manages the service lifecycle
(clone / install / build / start / stop / status) and wraps the generation
endpoints so the agent can produce images and videos without hand-writing curl.

    opc-jimeng2api start        # clone/install (first time) + launch + wait for health
    opc-jimeng2api status       # is the proxy reachable?
    opc-jimeng2api stop         # stop the background proxy
    opc-jimeng2api config       # print the effective, merged configuration (JSON)
    opc-jimeng2api gen-image    # POST /v1/images/generations
    opc-jimeng2api gen-video    # POST /v1/video/generations (async -> task_id)
    opc-jimeng2api poll         # GET  /v1/video/generations/{task_id}

Configuration resolves as: packaged defaults ->
``<opc_home>/config/jimeng2api.yaml`` -> CLI flags. The schema lives at
``opc/skills_assets/jimeng2api/config.schema.json``.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
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

_SKILL_DIR = Path(__file__).resolve().parent / "skills_assets" / "jimeng2api"
_DEFAULTS_PATH = _SKILL_DIR / "config.default.yaml"

_DEFAULT_PORT = 5100
_HEALTH_TIMEOUT = 2.0
_START_WAIT_ATTEMPTS = 60  # ~30s at 0.5s intervals


# --------------------------------------------------------------------------- #
# Config resolution
# --------------------------------------------------------------------------- #
def _load_defaults() -> dict[str, Any]:
    if _DEFAULTS_PATH.exists():
        with _DEFAULTS_PATH.open("r", encoding="utf-8") as fh:
            return yaml.safe_load(fh) or {}
    return {}


def _load_user_config(opc_home: Path) -> dict[str, Any]:
    user_path = opc_home / "config" / "jimeng2api.yaml"
    if user_path.exists():
        try:
            with user_path.open("r", encoding="utf-8") as fh:
                return yaml.safe_load(fh) or {}
        except Exception as exc:  # pragma: no cover - defensive
            sys.stderr.write(f"opc-jimeng2api: failed to read {user_path}: {exc}\n")
    return {}


def resolve_config(opc_home: Path, overrides: dict[str, Any] | None = None) -> dict[str, Any]:
    """Merge defaults -> user file -> CLI overrides into one config dict."""
    cfg = _load_defaults()
    cfg.update(_load_user_config(opc_home))
    if overrides:
        cfg.update({k: v for k, v in overrides.items() if v is not None})

    install_dir = str(cfg.get("install_dir") or "").strip()
    if not install_dir:
        install_dir = str(opc_home / "integrations" / "jimeng2api")
    cfg["install_dir"] = install_dir
    cfg.setdefault("host", "127.0.0.1")
    cfg.setdefault("port", _DEFAULT_PORT)
    cfg.setdefault("repo_url", "https://github.com/carzygod/jimeng2api.git")
    cfg.setdefault("admin_key", "")
    cfg.setdefault("api_key", "")
    cfg.setdefault("auto_launch", True)
    cfg.setdefault("open_page", True)
    return cfg


# --------------------------------------------------------------------------- #
# Service lifecycle helpers
# --------------------------------------------------------------------------- #
def _repo_dir(cfg: dict[str, Any]) -> Path:
    return Path(cfg["install_dir"])


def _pidfile(repo_dir: Path) -> Path:
    return repo_dir / ".jimeng2api.pid"


def _logfile(repo_dir: Path) -> Path:
    return repo_dir / "jimeng2api.log"


def _tcp_alive(host: str, port: int, timeout: float = _HEALTH_TIMEOUT) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _is_installed(repo_dir: Path) -> bool:
    return (repo_dir / "package.json").exists() and (repo_dir / "node_modules").exists()


def _ensure_installed(cfg: dict[str, Any], force: bool = False) -> int:
    repo_dir = _repo_dir(cfg)
    repo_dir.mkdir(parents=True, exist_ok=True)
    if _is_installed(repo_dir) and not force:
        print(f"jimeng2api already installed at {repo_dir}")
        return 0

    git = shutil.which("git")
    npm = shutil.which("npm") or shutil.which("node")
    if not git:
        sys.stderr.write("opc-jimeng2api: 'git' not found on PATH; cannot clone.\n")
        return 1
    if not npm:
        sys.stderr.write("opc-jimeng2api: 'npm'/'node' not found on PATH; cannot build.\n")
        return 1

    if not (repo_dir / "package.json").exists():
        print(f"Cloning {cfg['repo_url']} -> {repo_dir}")
        code = subprocess.call(["git", "clone", "--depth", "1", cfg["repo_url"], "."],
                               cwd=str(repo_dir))
        if code != 0:
            sys.stderr.write("opc-jimeng2api: git clone failed.\n")
            return code

    print("Installing dependencies (npm install)...")
    code = subprocess.call(["npm", "install"], cwd=str(repo_dir))
    if code != 0:
        sys.stderr.write("opc-jimeng2api: npm install failed.\n")
        return code

    print("Building (npm run build)...")
    code = subprocess.call(["npm", "run", "build"], cwd=str(repo_dir))
    if code != 0:
        sys.stderr.write("opc-jimeng2api: npm run build failed.\n")
        return code

    print(f"jimeng2api installed at {repo_dir}")
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
            # Already running; verify the port answers.
            if _tcp_alive(cfg["host"], int(cfg["port"])):
                print(f"jimeng2api already running (pid {old_pid})")
                return 0
            # Stale pidfile; clear it.
            pid_file.unlink(missing_ok=True)

    env = dict(os.environ)
    admin_key = cfg.get("admin_key") or secrets.token_hex(16)
    env["JIMENG_ADMIN_KEY"] = str(admin_key)
    # Persist the generated admin key so restarts are stable.
    _write_user_admin_key(cfg, admin_key)

    log_path = _logfile(repo_dir)
    log_fh = log_path.open("ab", buffering=0)
    flags = 0
    if os.name == "nt":
        flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    proc = subprocess.Popen(
        ["npm", "run", "start"],
        cwd=str(repo_dir),
        env=env,
        stdout=log_fh,
        stderr=subprocess.STDOUT,
        creationflags=flags,
    )
    pid_file.write_text(str(proc.pid))
    print(f"jimeng2api launched (pid {proc.pid}); logging to {log_path}")

    host, port = cfg["host"], int(cfg["port"])
    print(f"Waiting for jimeng2api at http://{host}:{port} ", end="", flush=True)
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
            f"opc-jimeng2api: proxy did not come up at http://{host}:{port}. "
            f"See {log_path}\n"
        )
        return 1
    print(f"jimeng2api is live at http://{host}:{port}")
    return 0


def _write_user_admin_key(cfg: dict[str, Any], admin_key: str) -> None:
    """Persist a generated admin key into the user config without clobbering it."""
    opc_home = get_opc_home()
    user_path = opc_home / "config" / "jimeng2api.yaml"
    existing: dict[str, Any] = {}
    if user_path.exists():
        try:
            existing = yaml.safe_load(user_path.read_text(encoding="utf-8")) or {}
        except Exception:
            existing = {}
    if not existing.get("admin_key"):
        existing["admin_key"] = admin_key
        user_path.parent.mkdir(parents=True, exist_ok=True)
        user_path.write_text(yaml.safe_dump(existing, allow_unicode=True), encoding="utf-8")


def _process_alive(pid: int) -> bool:
    if os.name == "nt":
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}"],
            capture_output=True, text=True,
        )
        return str(pid) in out.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _stop_service(cfg: dict[str, Any]) -> int:
    repo_dir = _repo_dir(cfg)
    pid_file = _pidfile(repo_dir)
    if not pid_file.exists():
        print("jimeng2api not running (no pidfile)")
        return 0
    try:
        pid = int(pid_file.read_text().strip())
    except (ValueError, OSError):
        pid_file.unlink(missing_ok=True)
        print("jimeng2api not running (bad pidfile)")
        return 0
    if not _process_alive(pid):
        pid_file.unlink(missing_ok=True)
        print("jimeng2api not running")
        return 0
    print(f"Stopping jimeng2api (pid {pid})...")
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
    pid_file.unlink(missing_ok=True)
    print("stopped")
    return 0


# --------------------------------------------------------------------------- #
# HTTP helpers (OpenAI-compatible endpoints)
# --------------------------------------------------------------------------- #
def _auth_headers(cfg: dict[str, Any]) -> dict[str, str]:
    api_key = cfg.get("api_key") or ""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    return headers


def _http_post_json(cfg: dict[str, Any], path: str, payload: dict[str, Any]) -> dict[str, Any]:
    url = f"http://{cfg['host']}:{int(cfg['port'])}{path}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers=_auth_headers(cfg), method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        sys.stderr.write(f"opc-jimeng2api: POST {url} -> {exc.code}: {body}\n")
        return {"error": f"HTTP {exc.code}", "detail": body}
    except urllib.error.URLError as exc:
        sys.stderr.write(f"opc-jimeng2api: cannot reach {url}: {exc.reason}\n")
        return {"error": "connection_failed", "detail": str(exc.reason)}


def _http_get_json(cfg: dict[str, Any], path: str) -> dict[str, Any]:
    url = f"http://{cfg['host']}:{int(cfg['port'])}{path}"
    req = urllib.request.Request(url, headers=_auth_headers(cfg), method="GET")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        sys.stderr.write(f"opc-jimeng2api: GET {url} -> {exc.code}: {body}\n")
        return {"error": f"HTTP {exc.code}", "detail": body}
    except urllib.error.URLError as exc:
        sys.stderr.write(f"opc-jimeng2api: cannot reach {url}: {exc.reason}\n")
        return {"error": "connection_failed", "detail": str(exc.reason)}


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #
def _cmd_status(cfg: dict[str, Any]) -> int:
    ok = _tcp_alive(cfg["host"], int(cfg["port"]))
    print(json.dumps({"ok": ok, "host": cfg["host"], "port": int(cfg["port"])},
                     ensure_ascii=False))
    return 0 if ok else 1


def _cmd_setup(cfg: dict[str, Any], force: bool = False) -> int:
    return _ensure_installed(cfg, force=force)


def _discover_office_ui_port(opc_home: Path) -> int:
    """Find the port the SafeOPC office-UI server is listening on.

    The server writes its bound port to ``<opc_home>/office_ui.port`` on
    startup; falling back to the ``SAFEOPC_PORT`` env var, then the default.
    """
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
    """Ask SafeOPC's built-in browser to open ``url``.

    Returns True if the request was accepted by a running office-UI server,
    False otherwise (caller should fall back to the OS browser / print URL).
    """
    port = _discover_office_ui_port(opc_home)
    endpoint = f"http://127.0.0.1:{port}/api/ui/open-browser"
    payload = json.dumps({"url": url, "title": title}).encode("utf-8")
    req = urllib.request.Request(
        endpoint,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return bool(data.get("ok"))
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
        return False


def _cmd_start(cfg: dict[str, Any], no_browser: bool, opc_home: Path, force: bool = False) -> int:
    if cfg.get("auto_launch", True):
        if not _is_installed(_repo_dir(cfg)):
            if _ensure_installed(cfg, force=force) != 0:
                return 1
        elif force:
            if _ensure_installed(cfg, force=True) != 0:
                return 1
        if _start_service(cfg) != 0:
            return 1
    else:
        print("auto_launch is false; not launching the proxy.")

    url = f"http://{cfg['host']}:{int(cfg['port'])}"
    if no_browser or not cfg.get("open_page", True):
        print(f"Open this URL in SafeOPC's built-in browser: {url}")
        return 0

    # Prefer SafeOPC's built-in (in-app) browser when the desktop app is
    # running; fall back to the OS default browser / manual URL otherwise.
    if _open_in_app_browser(url, "jimeng2api", opc_home):
        print("Opened the admin page in SafeOPC's built-in browser.")
        return 0
    try:
        webbrowser.open(url)
        print("Opened the admin page in your default browser.")
    except Exception as exc:  # pragma: no cover - environment dependent
        sys.stderr.write(f"opc-jimeng2api: could not auto-open browser: {exc}\n")
        print(f"Open this URL manually: {url}")
    return 0


def _cmd_stop(cfg: dict[str, Any]) -> int:
    return _stop_service(cfg)


def _cmd_config(cfg: dict[str, Any]) -> int:
    print(json.dumps(cfg, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def _cmd_gen_image(cfg: dict[str, Any], args: argparse.Namespace) -> int:
    if not cfg.get("api_key"):
        sys.stderr.write(
            "opc-jimeng2api: api_key is not set. Open the admin page, create an "
            "API key, and write it to <opc_home>/config/jimeng2api.yaml.\n"
        )
        return 1
    payload: dict[str, Any] = {
        "model": args.model,
        "prompt": args.prompt,
    }
    if args.ratio:
        payload["ratio"] = args.ratio
    if args.size:
        payload["size"] = args.size
    if args.negative_prompt:
        payload["negative_prompt"] = args.negative_prompt
    result = _http_post_json(cfg, "/v1/images/generations", payload)
    if "error" in result:
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def _cmd_gen_video(cfg: dict[str, Any], args: argparse.Namespace) -> int:
    if not cfg.get("api_key"):
        sys.stderr.write(
            "opc-jimeng2api: api_key is not set. Open the admin page, create an "
            "API key, and write it to <opc_home>/config/jimeng2api.yaml.\n"
        )
        return 1
    payload: dict[str, Any] = {
        "model": args.model,
        "prompt": args.prompt,
    }
    if args.duration is not None:
        payload["duration"] = args.duration
    if args.resolution:
        payload["resolution"] = args.resolution
    if args.ratio:
        payload["ratio"] = args.ratio
    result = _http_post_json(cfg, "/v1/video/generations", payload)
    if "error" in result:
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def _cmd_poll(cfg: dict[str, Any], args: argparse.Namespace) -> int:
    task_id = args.task_id
    endpoint = f"/v1/video/generations/{task_id}"
    last = None
    for _ in range(args.max_wait):
        last = _http_get_json(cfg, endpoint)
        status = str(last.get("status", "")).lower()
        if status in ("succeeded", "completed", "done", "failed", "error"):
            if status in ("failed", "error"):
                sys.stderr.write(f"opc-jimeng2api: task {task_id} ended as {status}.\n")
                print(json.dumps(last, ensure_ascii=False, indent=2))
                return 1
            print(json.dumps(last, ensure_ascii=False, indent=2))
            return 0
        time.sleep(args.interval)
    sys.stderr.write(
        f"opc-jimeng2api: timed out waiting for task {task_id}.\n"
    )
    if last:
        print(json.dumps(last, ensure_ascii=False, indent=2))
    return 1


# --------------------------------------------------------------------------- #
# Arg parsing
# --------------------------------------------------------------------------- #
def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="opc-jimeng2api",
        description="jimeng2api built-in skill CLI: run the Jimeng proxy and generate images/videos.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True, metavar="<command>")

    sub.add_parser("status", help="Check if the proxy is reachable.")
    sub.add_parser("stop", help="Stop the background proxy.")
    sub.add_parser("config", help="Print the effective, merged configuration (JSON).")

    setup_p = sub.add_parser("setup", help="Clone + npm install + build (first time).")
    setup_p.add_argument("--force", action="store_true", help="Reinstall even if present.")

    start_p = sub.add_parser("start", help="Install (if needed) + launch + wait for health.")
    start_p.add_argument("--no-browser", action="store_true", help="Do not open the admin page.")
    start_p.add_argument("--force", action="store_true", help="Reinstall dependencies before launching.")
    start_p.add_argument("--host", default=None, help="Override the bind host.")
    start_p.add_argument("--port", type=int, default=None, help="Override the bind port.")

    img_p = sub.add_parser("gen-image", help="Generate an image (POST /v1/images/generations).")
    img_p.add_argument("--model", required=True, help="Image model id, e.g. jimeng-image-4.6.")
    img_p.add_argument("--prompt", required=True, help="Text prompt.")
    img_p.add_argument("--ratio", default=None, help="Aspect ratio, e.g. 16:9.")
    img_p.add_argument("--size", default=None, help="Size, e.g. 1024x1024.")
    img_p.add_argument("--negative-prompt", default=None, help="Negative prompt.")
    img_p.add_argument("--output", default=None, help="Optional path to save the result (ignored if remote URL).")

    vid_p = sub.add_parser("gen-video", help="Generate a video (POST /v1/video/generations).")
    vid_p.add_argument("--model", required=True, help="Video model id, e.g. seedance-2.0-fast.")
    vid_p.add_argument("--prompt", required=True, help="Text prompt.")
    vid_p.add_argument("--duration", type=int, default=None, help="Duration in seconds.")
    vid_p.add_argument("--resolution", default=None, help="Resolution, e.g. 720p.")
    vid_p.add_argument("--ratio", default=None, help="Aspect ratio, e.g. 16:9.")

    poll_p = sub.add_parser("poll", help="Poll a video task until done.")
    poll_p.add_argument("--task-id", required=True, help="Task id returned by gen-video.")
    poll_p.add_argument("--interval", type=float, default=5.0, help="Poll interval seconds.")
    poll_p.add_argument("--max-wait", type=int, default=120, help="Max poll attempts.")
    poll_p.add_argument("--output", default=None, help="Optional path to save the result.")

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
    if opts.cmd == "gen-video":
        return _cmd_gen_video(cfg, opts)
    if opts.cmd == "poll":
        return _cmd_poll(cfg, opts)
    parser.error("unknown command")
    return 2  # pragma: no cover


if __name__ == "__main__":
    raise SystemExit(main())
