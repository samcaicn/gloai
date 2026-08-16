"""Tests for opc/plugins/office_ui/auth_device.py — CLIENT architecture.

Loads the module in isolation (stdlib) so it runs without booting the full
OPC engine. Covers three modes:
  * dev stub (local simulation of the remote server — NOT production authority)
  * production proxy (client forwards to a real remote auth server)
  * default (no server configured → 503, device stays unregistered)
"""
import asyncio
import importlib.util
import os
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE_PATH = os.path.join(HERE, "..", "opc", "plugins", "office_ui", "auth_device.py")

spec = importlib.util.spec_from_file_location("auth_device", MODULE_PATH)
auth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auth)


def check(name, cond):
    print(("PASS" if cond else "FAIL"), "-", name)
    if not cond:
        check.failed += 1


check.failed = 0


async def test_stub_mode():
    os.environ["SAFEOPC_AUTH_STUB"] = "1"
    os.environ.pop("SAFEOPC_AUTH_SERVER", None)
    settings = auth.resolve_settings()
    check("stub mode on", settings["dev_stub_enabled"] is True)
    check("no base url in stub", settings["auth_server_base_url"] == "")

    home = tempfile.mkdtemp(prefix="da_stub_")
    # register('') → pending, token issued, gate blocks
    r = await auth.stub_register(home, "", settings)
    check("stub register('') token", bool(r["token"]))
    check("stub register('') pending", r["approvalStatus"] == "pending_approval")
    auth.cache_server_response(home, r)
    check("gate blocks while pending", auth.is_execution_allowed(home, settings) is False)

    # verify reads stub authority
    v = await auth.stub_verify(home, r["token"])
    check("stub verify pending", v["approvalStatus"] == "pending_approval")

    # demo code → active (服务端策略的本地模拟)
    r2 = await auth.stub_register(home, "SAFEOPC-DEMO", settings)
    check("stub demo code active", r2["approvalStatus"] == "active")
    auth.cache_server_response(home, r2)
    check("gate allows when active", auth.is_execution_allowed(home, settings) is True)

    # operator dev-approve flips a pending device
    ru = await auth.stub_register(tempfile.mkdtemp(prefix="da_stub2_"), "WRONGCODE", settings)
    check("unknown code → requestId", bool(ru["requestId"]))
    ap = await auth.stub_dev_approve(home, settings, request_id=ru["requestId"])
    check("stub dev-approve ok", ap.get("ok") is True)

    del os.environ["SAFEOPC_AUTH_STUB"]


async def test_proxy_mode():
    """Client forwards to a real remote auth server; local only caches."""
    import aiohttp.web
    import aiohttp

    remote_app = aiohttp.web.Application()

    async def remote_register(request):
        body = await request.json()
        # 远端服务器是权威：这里直接判定 active（真实服务器按 join_code 策略）
        return aiohttp.web.json_response({
            "token": "remote-tok-123",
            "deviceId": "remote-dev",
            "tenantId": "tenant-x",
            "isNewDevice": True,
            "approvalStatus": "active",
            "nextStep": "approved",
            "requestId": None,
        })

    async def remote_verify(request):
        body = await request.json()
        return aiohttp.web.json_response({
            "valid": True,
            "approvalStatus": "active",
            "tenantId": "tenant-x",
        })

    remote_app.router.add_post("/device/register", remote_register)
    remote_app.router.add_post("/device/verify", remote_verify)

    runner = aiohttp.web.AppRunner(remote_app)
    await runner.setup()
    site = aiohttp.web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    base_url = f"http://127.0.0.1:{port}"

    try:
        settings = auth.resolve_settings()
        check("proxy mode: no stub", settings["dev_stub_enabled"] is False)

        home = tempfile.mkdtemp(prefix="da_proxy_")
        # client → remote register → cache → gate allow
        reg = await auth.proxy_to_server(base_url, "register", {"joinCode": "ANY"})
        check("proxy register returns token", reg.get("token") == "remote-tok-123")
        auth.cache_server_response(home, reg)
        check("proxy cached active → gate allows", auth.is_execution_allowed(home, settings) is True)

        ver = await auth.proxy_to_server(base_url, "verify", {"token": "remote-tok-123"})
        check("proxy verify active", ver["approvalStatus"] == "active")
    finally:
        await runner.cleanup()


async def test_default_mode():
    os.environ.pop("SAFEOPC_AUTH_STUB", None)
    os.environ.pop("SAFEOPC_AUTH_SERVER", None)
    settings = auth.resolve_settings()
    check("default: stub off", settings["dev_stub_enabled"] is False)
    check("default: no base url", settings["auth_server_base_url"] == "")
    # 未配置远端服务器：本地无裁决 → 设备未注册 → 门禁拦截
    home = tempfile.mkdtemp(prefix="da_def_")
    check("default gate blocks (unregistered)", auth.is_execution_allowed(home, settings) is False)


async def main():
    await test_stub_mode()
    await test_proxy_mode()
    await test_default_mode()
    check("fingerprint stable", auth.collect_fingerprint() == auth.collect_fingerprint())


if __name__ == "__main__":
    asyncio.run(main())
    print("\n" + ("ALL PASSED" if check.failed == 0 else f"{check.failed} FAILED"))
    raise SystemExit(1 if check.failed else 0)
