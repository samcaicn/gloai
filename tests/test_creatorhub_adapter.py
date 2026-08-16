"""Integration tests for the SafeOPC ↔ CreatorHub adapter.

These run against an in-process stub HTTP server that mimics the CreatorHub
FastAPI surface, so they need **no** real CreatorHub, no browser, and no
network egress. They cover:

* functional paths (health, accounts, share-link parsing, collections,
  monitors, contents, published)
* boundary cases (empty share text, limit clamping, GET returning lists)
* error / exception paths (connection refused, non-2xx, timeout)
* launcher config merging (no pip / no browser involved)
"""
from __future__ import annotations

import asyncio
import http.server
import json
import re
import threading
import time
from pathlib import Path

import pytest

from opc.integrations.creatorhub_adapter.client import (
    CreatorHubAPIError,
    CreatorHubClient,
    CreatorHubConnectionError,
    CreatorHubTimeoutError,
)
from opc.integrations.creatorhub_adapter.launcher import CreatorHubLauncher


# ── stub server ─────────────────────────────────────────────────
class _Stub(http.server.BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # silence
        pass

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/health":
            return self._send(200, {"status": "ok", "version": "1.0"})
        if p.startswith("/api/accounts") and "/environment" in p:
            return self._send(200, {"backend": "cdp", "backend_label": "系统 Chrome · CDP"})
        if p == "/api/accounts":
            return self._send(200, [{"id": 1, "platform": "xhs", "nickname": "tester"}])
        if p == "/api/collections":
            return self._send(200, [{"job_id": "j1"}])
        if p == "/api/monitors":
            return self._send(200, [{"tid": "m1"}])
        if p == "/api/contents":
            return self._send(200, [{"cid": "c1"}])
        if p == "/api/publish/published":
            # 真实端点要求必填 query 参数 account_id；桩只校验存在性即可。
            if "account_id" not in self.path:
                return self._send(422, {"detail": "account_id required"})
            return self._send(200, [{"id": "p1"}])
        if p.startswith("/api/collections/") and p.endswith("/contents"):
            return self._send(200, [{"cid": "c2"}])
        if p.startswith("/api/collections/"):
            return self._send(200, {"job_id": p.rstrip("/").split("/")[-1]})
        if p.startswith("/api/monitors/"):
            return self._send(200, {"tid": p.rstrip("/").split("/")[-1]})
        if p == "/slow":
            time.sleep(3)
            return self._send(200, {})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        p = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw) if raw else {}
        except ValueError:
            body = {}
        if p == "/api/share-download/links":
            limit = int(body.get("limit", 10))
            text = body.get("share_text", "")
            links = re.findall(r"https?://\S+", text)[:limit]
            return self._send(200, {
                "ok": bool(links), "normalized_text": text,
                "links": [{"url": u} for u in links], "count": len(links),
                "_echo_limit": limit,
            })
        if p == "/api/share-download":
            return self._send(200, {"accepted": True, "_echo": body})
        if p == "/api/collections":
            return self._send(200, {"job_id": "created", "_echo": body})
        if p == "/api/monitors":
            return self._send(200, {"tid": "created", "_echo": body})
        if p.startswith("/api/collections/") and p.endswith("/cancel"):
            return self._send(200, {"cancelled": True})
        if p == "/boom":
            return self._send(400, {"detail": "bad request"})
        return self._send(404, {"error": "not found"})


@pytest.fixture
def base_url():
    server = http.server.HTTPServer(("127.0.0.1", 0), _Stub)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{port}"
    server.shutdown()


def run(coro):
    return asyncio.run(coro)


# ── functional ─────────────────────────────────────────────────
def test_health(base_url):
    out = run(CreatorHubClient(base_url=base_url).health())
    assert out["status"] == "ok"


def test_list_accounts(base_url):
    out = run(CreatorHubClient(base_url=base_url).list_accounts())
    assert isinstance(out, list) and out[0]["platform"] == "xhs"


def test_list_accounts_with_platform(base_url):
    out = run(CreatorHubClient(base_url=base_url).list_accounts(platform="douyin"))
    assert isinstance(out, list)


def test_account_environment(base_url):
    out = run(CreatorHubClient(base_url=base_url).account_environment(1))
    assert out["backend"] == "cdp"


def test_parse_share_links(base_url):
    text = "看看这个 https://v.douyin.com/abc/ 和 https://www.xiaohongshu.com/explore/xyz"
    out = run(CreatorHubClient(base_url=base_url).parse_share_links(text))
    assert out["count"] == 2
    assert out["links"][0]["url"].startswith("https")


def test_parse_share_links_empty(base_url):
    out = run(CreatorHubClient(base_url=base_url).parse_share_links("没有链接的文案"))
    assert out["count"] == 0
    assert out["ok"] is False


def test_parse_share_links_limit_clamp(base_url):
    text = " ".join(f"https://e{x}.com" for x in range(50))
    out = run(CreatorHubClient(base_url=base_url).parse_share_links(text, limit=999))
    assert out["_echo_limit"] == 20  # clamped to upper bound
    assert out["count"] == 20


def test_share_download(base_url):
    out = run(CreatorHubClient(base_url=base_url).share_download("https://v.douyin.com/abc/"))
    assert out["accepted"] is True


def test_collections_crud(base_url):
    created = run(CreatorHubClient(base_url=base_url).create_collection(name="demo"))
    assert created["job_id"] == "created"
    listed = run(CreatorHubClient(base_url=base_url).list_collections())
    assert listed[0]["job_id"] == "j1"
    got = run(CreatorHubClient(base_url=base_url).get_collection("j1"))
    assert got["job_id"] == "j1"
    contents = run(CreatorHubClient(base_url=base_url).list_collection_contents("j1"))
    assert contents[0]["cid"] == "c2"
    cancelled = run(CreatorHubClient(base_url=base_url).cancel_collection("j1"))
    assert cancelled["cancelled"] is True


def test_monitors_crud(base_url):
    created = run(CreatorHubClient(base_url=base_url).create_monitor(spec={"kw": "x"}))
    assert created["tid"] == "created"
    listed = run(CreatorHubClient(base_url=base_url).list_monitors())
    assert listed[0]["tid"] == "m1"
    got = run(CreatorHubClient(base_url=base_url).get_monitor("m1"))
    assert got["tid"] == "m1"


def test_contents_and_published(base_url):
    assert run(CreatorHubClient(base_url=base_url).list_contents())[0]["cid"] == "c1"
    assert run(CreatorHubClient(base_url=base_url).list_published(1))[0]["id"] == "p1"


# ── error / exception paths ────────────────────────────────────
def test_connection_error():
    # Port 1 is essentially never listening.
    with pytest.raises(CreatorHubConnectionError):
        run(CreatorHubClient(base_url="http://127.0.0.1:1", timeout=2).health())


def test_api_error(base_url):
    with pytest.raises(CreatorHubAPIError) as exc:
        run(CreatorHubClient(base_url=base_url)._request("POST", "/boom"))
    assert exc.value.status == 400
    assert "bad request" in (exc.value.detail or "")


def test_timeout(base_url):
    with pytest.raises(CreatorHubTimeoutError):
        run(CreatorHubClient(base_url=base_url, timeout=1)._request("GET", "/slow"))


def test_context_manager(base_url):
    async def _go():
        async with CreatorHubClient(base_url=base_url) as c:
            return await c.health()
    assert run(_go())["status"] == "ok"


# ── launcher config merge (no pip / no browser) ────────────────
def test_launcher_write_config(tmp_path):
    # Build a fake CreatorHub dir with a minimal example config.
    ch_dir = tmp_path / "creatorhub"
    ch_dir.mkdir()
    (ch_dir / "config.example.yaml").write_text(
        "server:\n  host: 0.0.0.0\n  port: 8000\n"
        "engine:\n  xhs_browser_mode: auto\n  media_dir: ./data/media\n"
        "storage:\n  db_path: ./data/creatorhub.db\n",
        encoding="utf-8",
    )
    data_root = tmp_path / "data"
    launcher = CreatorHubLauncher(
        creatorhub_dir=ch_dir, data_root=data_root, port=8123, host="127.0.0.1")
    cfg_path = launcher.write_config()
    import yaml
    with cfg_path.open(encoding="utf-8") as fh:
        cfg = yaml.safe_load(fh)
    assert cfg["server"]["port"] == 8123
    assert cfg["server"]["host"] == "127.0.0.1"
    assert cfg["engine"]["xhs_browser_mode"] == "auto"
    assert str(data_root / "profiles") in cfg["engine"]["profiles_dir"]
    assert str(data_root / "creatorhub.db") == cfg["storage"]["db_path"]


def test_launcher_provision_marker_idempotent(tmp_path):
    # The marker lets `ensure_venv` skip the (slow, no-op) pip install on
    # every launch. Verify the hash/marker bookkeeping behaves.
    ch_dir = tmp_path / "creatorhub"
    ch_dir.mkdir()
    req = ch_dir / "requirements.txt"
    req.write_text("fastapi==0.115.6\nuvicorn\n", encoding="utf-8")

    launcher = CreatorHubLauncher(
        creatorhub_dir=ch_dir, data_root=tmp_path / "data", port=8124, host="127.0.0.1")

    h1 = launcher._requirements_hash()
    assert h1 and len(h1) == 64  # sha256 hex
    assert launcher._is_provisioned() is False

    launcher._write_provision_marker()
    assert launcher._is_provisioned() is True  # skip install path

    # Changing requirements invalidates the marker -> reinstall would run.
    req.write_text("fastapi==0.115.6\nuvicorn\nhttpx\n", encoding="utf-8")
    assert launcher._is_provisioned() is False

