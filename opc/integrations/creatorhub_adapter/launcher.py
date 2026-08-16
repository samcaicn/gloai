"""Sidecar launcher for the CreatorHub service.

Responsibilities (and what it deliberately does NOT do):

* Creates an **isolated venv** for CreatorHub so its FastAPI / Patchright /
  OpenCV dependencies never clash with SafeOPC's own environment.
* Installs CreatorHub's ``requirements.txt`` but **skips**
  ``patchright install chromium``. CreatorHub already prefers the system
  stable Chrome (see ``app/browser/manager.py`` ``_detect_chrome_major``),
  so no bundled browser is downloaded — satisfying the "use the system
  browser, do not bundle one" constraint.
* Writes a merged ``config.yaml`` that points data/profiles/media/db under a
  SafeOPC-managed data root and onto a configurable port.
* Starts the FastAPI service via ``uvicorn app.main:app``.

Usage::

    python launcher.py setup     # create venv + install deps (no browser)
    python launcher.py start     # launch uvicorn in background
    python launcher.py status    # probe /health
    python launcher.py stop      # stop a running instance
"""
from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

try:
    import yaml
except ImportError:  # pragma: no cover - yaml is a SafeOPC dependency
    yaml = None


# integrations/creatorhub lives at <repo>/integrations/creatorhub
REPO_ROOT = Path(__file__).resolve().parents[3]
CREATORHUB_DIR = REPO_ROOT / "integrations" / "creatorhub"
DEFAULT_DATA_ROOT = REPO_ROOT / ".opc" / "integrations" / "creatorhub"
DEFAULT_PORT = 8000
DEFAULT_HOST = "127.0.0.1"


class CreatorHubLauncher:
    def __init__(
        self,
        creatorhub_dir: Path = CREATORHUB_DIR,
        data_root: Path = DEFAULT_DATA_ROOT,
        port: int = DEFAULT_PORT,
        host: str = DEFAULT_HOST,
        platform: str = "xhs",
        xhs_browser_mode: str = "auto",
    ) -> None:
        self.creatorhub_dir = Path(creatorhub_dir)
        self.data_root = Path(data_root)
        self.port = int(port)
        self.host = host
        self.platform = platform
        self.xhs_browser_mode = xhs_browser_mode
        self.venv_dir = self.creatorhub_dir / ".venv"
        self.config_path = self.data_root / "config.yaml"
        self.requirements = self.creatorhub_dir / "requirements.txt"
        self.pid_file = self.data_root / "creatorhub.pid"
        # Marker written after a successful dependency install so that
        # subsequent launches skip the (slow, no-op) pip pass. Stores the
        # sha256 of requirements.txt; if requirements change, we reinstall.
        self.provision_marker = self.venv_dir / ".creatorhub_provisioned"

    # ── paths ───────────────────────────────────────────────────
    @property
    def venv_python(self) -> Path:
        if os.name == "nt":
            return self.venv_dir / "Scripts" / "python.exe"
        return self.venv_dir / "bin" / "python"

    # ── setup ───────────────────────────────────────────────────
    def _requirements_hash(self) -> str:
        import hashlib

        if not self.requirements.exists():
            return ""
        return hashlib.sha256(self.requirements.read_bytes()).hexdigest()

    def _is_provisioned(self) -> bool:
        if not self.provision_marker.exists():
            return False
        try:
            return self.provision_marker.read_text(encoding="utf-8").strip() == self._requirements_hash()
        except OSError:
            return False

    def _write_provision_marker(self) -> None:
        self.venv_dir.mkdir(parents=True, exist_ok=True)
        self.provision_marker.write_text(self._requirements_hash(), encoding="utf-8")

    def ensure_venv(self, force: bool = False) -> None:
        """Create the isolated venv and install CreatorHub deps.

        Skips the install pass entirely when the venv already exists and its
        ``requirements.txt`` is unchanged (tracked via a marker file). This is
        what keeps ``opc-creatorhub open`` fast on every call instead of
        re-running a no-op ``pip install`` each time.

        Pass ``force=True`` to reinstall regardless (used by ``setup --force``
        and as an escape hatch when deps drift).
        """
        fresh = not self.venv_dir.exists()
        if fresh:
            subprocess.run([sys.executable, "-m", "venv", str(self.venv_dir)], check=True)

        if self._is_provisioned() and not force:
            print("CreatorHub venv already provisioned — skipping install.")
            return

        print("Installing CreatorHub dependencies (first run / changed requirements)...")
        # Only upgrade pip when we are (re)installing anyway; the bundled pip
        # on a fresh venv is new enough for a normal install.
        if fresh or force:
            subprocess.run(
                [str(self.venv_python), "-m", "pip", "install", "--upgrade", "pip"],
                check=True,
            )
        # Install deps WITHOUT downloading a browser binary.
        subprocess.run(
            [str(self.venv_python), "-m", "pip", "install", "-r", str(self.requirements)],
            check=True,
        )
        self._write_provision_marker()

    def write_config(self) -> Path:
        if yaml is None:
            raise RuntimeError("PyYAML 未安装，无法生成 CreatorHub 配置")
        example = self.creatorhub_dir / "config.example.yaml"
        with example.open("r", encoding="utf-8") as fh:
            cfg = yaml.safe_load(fh) or {}
        cfg.setdefault("server", {})["host"] = self.host
        cfg["server"]["port"] = self.port
        engine = cfg.setdefault("engine", {})
        engine["profiles_dir"] = str(self.data_root / "profiles")
        engine["media_dir"] = str(self.data_root / "media")
        engine["platform"] = self.platform
        engine["xhs_browser_mode"] = self.xhs_browser_mode
        cfg.setdefault("storage", {})["db_path"] = str(self.data_root / "creatorhub.db")
        self.data_root.mkdir(parents=True, exist_ok=True)
        with self.config_path.open("w", encoding="utf-8") as fh:
            yaml.safe_dump(cfg, fh, allow_unicode=True, sort_keys=False)
        return self.config_path

    # ── run ─────────────────────────────────────────────────────
    def start(self, background: bool = True) -> Optional[subprocess.Popen]:
        self.data_root.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        venv_bin = str(self.venv_dir / ("Scripts" if os.name == "nt" else "bin"))
        env["PATH"] = venv_bin + os.pathsep + env.get("PATH", "")
        # CreatorHub 的 app/config.py::load_config 读取 CREATORHUB_CONFIG_PATH
        # （或 DY_CONFIG_PATH）作为配置文件路径，变量名必须与之一致，否则会
        # 静默回退到默认相对路径 ./data/creatorhub.db，把数据写到仓库里。
        env["CREATORHUB_CONFIG_PATH"] = str(self.config_path)
        proc = subprocess.Popen(
            [str(self.venv_python), "-m", "uvicorn", "app.main:app",
             "--host", self.host, "--port", str(self.port)],
            cwd=str(self.creatorhub_dir),
            env=env,
            stdout=(self.data_root / "creatorhub.out.log").open("a", encoding="utf-8"),
            stderr=subprocess.STDOUT,
            start_new_session=background,
        )
        self.pid_file.write_text(str(proc.pid), encoding="utf-8")
        return proc

    # ── status / stop ───────────────────────────────────────────
    def read_pid(self) -> Optional[int]:
        if not self.pid_file.exists():
            return None
        try:
            return int(self.pid_file.read_text(encoding="utf-8").strip())
        except (ValueError, OSError):
            return None

    def is_running(self) -> bool:
        pid = self.read_pid()
        if pid is None:
            return False
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    def stop(self) -> bool:
        pid = self.read_pid()
        if pid is None:
            return False
        try:
            if os.name == "nt":
                os.kill(pid, signal.SIGTERM)
            else:
                os.kill(pid, signal.SIGTERM)
        except OSError:
            return False
        # best-effort wait
        for _ in range(20):
            try:
                os.kill(pid, 0)
            except OSError:
                break
            time.sleep(0.25)
        if self.pid_file.exists():
            self.pid_file.unlink()
        return True

    def health(self, timeout: float = 3.0) -> dict:
        import httpx

        try:
            resp = httpx.get(f"http://{self.host}:{self.port}/health", timeout=timeout)
            return {"ok": resp.status_code == 200, "status_code": resp.status_code,
                    "body": resp.text[:200]}
        except httpx.HTTPError as exc:
            return {"ok": False, "error": str(exc)}


def _build_cli() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="CreatorHub sidecar launcher")
    sub = p.add_subparsers(dest="cmd", required=True)
    setup_p = sub.add_parser("setup", help="创建 venv 并安装依赖（不下载浏览器）")
    setup_p.add_argument("--force", action="store_true", help="即使已 provision 也重装依赖")
    sub.add_parser("start", help="启动 uvicorn")
    sub.add_parser("status", help="探活")
    sub.add_parser("stop", help="停止")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--data-root", default=str(DEFAULT_DATA_ROOT))
    return p


def main(argv: Optional[list] = None) -> int:
    args = _build_cli().parse_args(argv)
    launcher = CreatorHubLauncher(
        data_root=Path(args.data_root), port=args.port, host=args.host)
    if args.cmd == "setup":
        launcher.ensure_venv(force=args.force)
        launcher.write_config()
        print(f"venv: {launcher.venv_dir}")
        print(f"config: {launcher.config_path}")
        return 0
    if args.cmd == "start":
        launcher.write_config()
        launcher.start()
        print(f"CreatorHub 已启动: http://{launcher.host}:{launcher.port}")
        return 0
    if args.cmd == "status":
        print(launcher.health())
        return 0 if launcher.is_running() else 1
    if args.cmd == "stop":
        print("stopped" if launcher.stop() else "not running")
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
