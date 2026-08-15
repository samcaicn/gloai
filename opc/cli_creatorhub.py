"""``opc-creatorhub`` CLI — built-in CreatorHub integration surface.

This is the single entry point for the CreatorHub built-in skill. SafeOPC
installs an ``opc-creatorhub`` shim (via :mod:`opc.layer3_agent.skill_installer`)
that dispatches here with ``python -m opc.cli_creatorhub``.

It wraps :mod:`opc.integrations.creatorhub_adapter.launcher` and adds the
"open the page" behavior the skill promises:

    opc-creatorhub open      # launch sidecar (if needed) + open http://host:port
    opc-creatorhub setup     # create isolated venv + write merged config
    opc-creatorhub start     # start the sidecar in the background
    opc-creatorhub status    # probe /health
    opc-creatorhub stop      # stop the running sidecar
    opc-creatorhub config    # print the effective, merged configuration (JSON)

Configuration is resolved as: packaged defaults -> user file
``<opc_home>/config/creatorhub.yaml`` -> CLI flags. The config schema lives at
``opc/skills_assets/creatorhub/config.schema.json``.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import webbrowser
from pathlib import Path
from typing import Any

import yaml

from opc.core.config import get_opc_home
from opc.integrations.creatorhub_adapter.launcher import (
    DEFAULT_HOST,
    DEFAULT_PORT,
    CreatorHubLauncher,
)

_SKILL_DIR = Path(__file__).resolve().parent / "skills_assets" / "creatorhub"
_DEFAULTS_PATH = _SKILL_DIR / "config.default.yaml"


def _load_defaults() -> dict[str, Any]:
    if _DEFAULTS_PATH.exists():
        with _DEFAULTS_PATH.open("r", encoding="utf-8") as fh:
            return yaml.safe_load(fh) or {}
    return {}


def _load_user_config(opc_home: Path) -> dict[str, Any]:
    user_path = opc_home / "config" / "creatorhub.yaml"
    if user_path.exists():
        try:
            with user_path.open("r", encoding="utf-8") as fh:
                return yaml.safe_load(fh) or {}
        except Exception as exc:  # pragma: no cover - defensive
            sys.stderr.write(f"opc-creatorhub: failed to read {user_path}: {exc}\n")
    return {}


def resolve_config(opc_home: Path, overrides: dict[str, Any] | None = None) -> dict[str, Any]:
    """Merge defaults -> user file -> CLI overrides into one config dict."""
    cfg = _load_defaults()
    cfg.update(_load_user_config(opc_home))
    if overrides:
        cfg.update({k: v for k, v in overrides.items() if v is not None})

    # Resolve the data_root default to an opc_home-relative path when empty.
    data_root = str(cfg.get("data_root") or "").strip()
    if not data_root:
        data_root = str(opc_home / "integrations" / "creatorhub")
    cfg["data_root"] = data_root
    cfg.setdefault("host", DEFAULT_HOST)
    cfg.setdefault("port", DEFAULT_PORT)
    return cfg


def _build_launcher(cfg: dict[str, Any]) -> CreatorHubLauncher:
    return CreatorHubLauncher(
        data_root=Path(cfg["data_root"]),
        port=int(cfg["port"]),
        host=str(cfg["host"]),
        platform=str(cfg.get("platform", "xhs")),
        xhs_browser_mode=str(cfg.get("xhs_browser_mode", "auto")),
    )


def _cmd_setup(cfg: dict[str, Any]) -> int:
    launcher = _build_launcher(cfg)
    launcher.ensure_venv()
    launcher.write_config()
    print(f"venv: {launcher.venv_dir}")
    print(f"config: {launcher.config_path}")
    return 0


def _cmd_start(cfg: dict[str, Any]) -> int:
    launcher = _build_launcher(cfg)
    launcher.write_config()
    launcher.start()
    print(f"CreatorHub started: http://{launcher.host}:{launcher.port}")
    return 0


def _cmd_status(cfg: dict[str, Any]) -> int:
    launcher = _build_launcher(cfg)
    result = launcher.health()
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result.get("ok") else 1


def _cmd_stop(cfg: dict[str, Any]) -> int:
    launcher = _build_launcher(cfg)
    print("stopped" if launcher.stop() else "not running")
    return 0


def _cmd_config(cfg: dict[str, Any]) -> int:
    print(json.dumps(cfg, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def _cmd_open(cfg: dict[str, Any], no_browser: bool) -> int:
    launcher = _build_launcher(cfg)
    url = f"http://{launcher.host}:{launcher.port}"

    if cfg.get("auto_launch", True) and not launcher.is_running():
        print("Sidecar not running — launching...")
        launcher.ensure_venv()
        launcher.write_config()
        launcher.start()

    # Wait for health (bounded).
    health = {"ok": False}
    for _ in range(40):
        health = launcher.health()
        if health.get("ok"):
            break
        import time

        time.sleep(0.5)

    if not health.get("ok"):
        sys.stderr.write(
            f"opc-creatorhub: sidecar did not become healthy at {url}: {health}\n"
        )
        return 1

    print(f"CreatorHub is live at {url}")
    if no_browser or not cfg.get("open_page", True):
        print(f"Open this URL in your browser: {url}")
        return 0

    try:
        webbrowser.open(url)
        print("Opened the page in your default browser.")
    except Exception as exc:  # pragma: no cover - environment dependent
        sys.stderr.write(f"opc-creatorhub: could not auto-open browser: {exc}\n")
        print(f"Open this URL manually: {url}")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="opc-creatorhub",
        description="CreatorHub built-in skill CLI: launch the Xiaohongshu sidecar and open its page.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True, metavar="<command>")

    sub.add_parser("setup", help="Create the isolated venv + write merged config.")
    sub.add_parser("start", help="Start the sidecar in the background.")
    sub.add_parser("status", help="Probe /health.")
    sub.add_parser("stop", help="Stop the running sidecar.")
    sub.add_parser("config", help="Print the effective, merged configuration (JSON).")

    open_p = sub.add_parser("open", help="Launch the sidecar (if needed) and open its page.")
    open_p.add_argument("--no-browser", action="store_true", help="Do not auto-open a browser; print the URL.")
    open_p.add_argument("--host", default=None, help="Override the bind host.")
    open_p.add_argument("--port", type=int, default=None, help="Override the bind port.")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    opts = parser.parse_args(argv)
    opc_home = get_opc_home()

    overrides: dict[str, Any] = {}
    if opts.cmd == "open":
        overrides["host"] = opts.host
        overrides["port"] = opts.port
    cfg = resolve_config(opc_home, overrides)

    if opts.cmd == "setup":
        return _cmd_setup(cfg)
    if opts.cmd == "start":
        return _cmd_start(cfg)
    if opts.cmd == "status":
        return _cmd_status(cfg)
    if opts.cmd == "stop":
        return _cmd_stop(cfg)
    if opts.cmd == "config":
        return _cmd_config(cfg)
    if opts.cmd == "open":
        return _cmd_open(cfg, no_browser=opts.no_browser)
    parser.error("unknown command")
    return 2  # pragma: no cover


if __name__ == "__main__":
    raise SystemExit(main())
