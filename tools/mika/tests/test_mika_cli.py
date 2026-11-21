"""CLI process tests: no Android phone, model credentials or external network."""

import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

SKILL = Path(__file__).resolve().parents[1]
SCRIPT = SKILL / "scripts/mika_cli.py"
TOKEN = "unit-test-connection-token"
SNAPSHOT = {"task_id": "t1", "status": "running", "conversation": "mcp-test", "goal": "example"}
DISCOVERY = {"api_version": 1, "server_version": "test", "capabilities": ["tasks", "task_steer"]}


def load_cli():
    spec = importlib.util.spec_from_file_location("mika_cli_test", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@contextlib.contextmanager
def api(respond=None):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_request(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw) if raw else None
            self.server.calls.append((self.command, self.path, body))
            if self.headers.get("Authorization") != "Bearer " + self.server.token:
                reply = (401, {"error": {"code": "access_denied", "message": "Missing token"}}, {})
            elif respond:
                reply = respond(self.command, self.path, body)
            else:
                reply = None
            if reply == "drop":
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                self.close_connection = True
                return
            if reply is None:
                reply = (200, DISCOVERY if self.path == "/api/v1" else SNAPSHOT, {})
            code, value, headers = reply
            raw = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
            extra = dict(headers)
            mime = "application/json"
            for key in list(extra):
                if key.lower() == "content-type":
                    mime = extra.pop(key)
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(raw)))
            for name, header_value in extra.items():
                self.send_header(name, header_value)
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.calls, server.token = [], TOKEN
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class CliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.token_file = self.directory / "token"
        self.token_file.write_text(TOKEN)

    def invoke(self, *args, server=None, script=SCRIPT, stdin=None, env=None, json_option="--json", parse=True,
               encoding="utf-8"):
        command = [sys.executable, str(script)]
        if json_option is not None:
            command.append(json_option)
        if server:
            command += ["--url", "http://127.0.0.1:" + str(server.server_port), "--token-file", str(self.token_file)]
        command += list(args)
        process_env = {**os.environ, "PYTHONUTF8": "1"}
        process_env.pop("ANDROID_SERIAL", None)
        if env:
            process_env.update(env)
        result = subprocess.run(command, input=stdin, encoding=encoding, capture_output=True,
                                cwd=self.directory, env=process_env, timeout=15)
        self.assertNotIn(TOKEN if encoding else TOKEN.encode(), result.stdout + result.stderr)
        return result, json.loads(result.stdout) if parse else None

    def fake_adb(self, server, **overrides):
        configuration = {
            "devices": "List of devices attached\nphone-a\tdevice\n",
            "info": {"api_version": 1, "token": TOKEN, "port": 8765, "boot": "17"},
            "boot": "17", "local_port": server.server_port,
        }
        configuration.update(overrides)
        config = self.directory / "adb-config.json"
        config.write_text(json.dumps(configuration))
        log = self.directory / "adb-log.jsonl"
        log.write_text("")
        script = self.directory / "fake_adb.py"
        script.write_text(r'''
import json, os, sys, time
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
cfg = json.loads(Path(os.environ["FAKE_ADB_CONFIG"]).read_text())
if args[:1] == ["-s"]:
    args = args[2:]
if args == ["start-server"]:
    pass
elif args == ["devices"]:
    print(cfg["devices"])
elif args == ["get-state"]:
    if cfg.get("get_state_fails"):
        sys.exit(1)
    print("device")
elif args[:2] == ["shell", "sh"]:
    print("bootstrap " + cfg["info"]["token"])
    sys.exit(cfg.get("bootstrap_exit", 0))
elif args[:2] == ["shell", "cat"]:
    if cfg.get("cat_fails"):
        sys.exit(1)
    print(json.dumps(cfg["info"]))
elif args[:3] == ["shell", "settings", "get"]:
    print(cfg["boot"])
elif args[:2] == ["forward", "tcp:0"]:
    print(cfg["local_port"])
elif args[:2] == ["forward", "--remove"]:
    time.sleep(cfg.get("remove_sleep", 0))
else:
    sys.exit(1)
''')
        if os.name == "nt":
            # CreateProcess cannot run a Python script directly; a .cmd wrapper stands in for the adb executable.
            executable = self.directory / "fake-adb.cmd"
            executable.write_text('@"%s" "%s" %%*\r\n' % (sys.executable, script))
        else:
            executable = self.directory / "fake-adb"
            executable.write_text("#!" + sys.executable + "\n" + script.read_text())
            executable.chmod(0o700)
        env = {"FAKE_ADB_CONFIG": str(config), "FAKE_ADB_LOG": str(log)}
        return executable, env, config, lambda: [json.loads(line) for line in log.read_text().splitlines()]

    def test_an_abbreviated_json_option_still_means_compact_json_on_stdout(self):
        for option in ["--json", "--jso", "--j"]:
            with self.subTest(option=option):
                result, body = self.invoke("devices", "--adb", str(self.directory / "missing-adb"), json_option=option)
                self.assertEqual(2, result.returncode)
                self.assertEqual("adb_unavailable", body["error"]["code"])
                self.assertEqual(1, len(result.stdout.splitlines()))
                self.assertNotIn("{", result.stderr)

    def test_abbreviated_json_option_also_formats_success(self):
        with api() as server:
            result, body = self.invoke("doctor", server=server, json_option="--jso")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("http", body["transport"])
            self.assertEqual(1, len(result.stdout.splitlines()))

    def test_json_option_scan_stops_at_the_option_delimiter(self):
        cli = load_cli()
        self.assertFalse(cli.json_flag(["devices", "--", "--jso"]))
        self.assertFalse(cli.json_flag(["task", "run", "--goal=--json"]))
        self.assertTrue(cli.json_flag(["task", "run", "--jso", "--", "--goal", "x"]))

    def test_ui_rejects_an_abbreviated_json_option(self):
        result, body = self.invoke("ui", "--jso", json_option=None)
        self.assertEqual(2, result.returncode)
        self.assertEqual("arguments", body["error"]["code"])
        self.assertEqual("The desk is interactive. Use mika ui without --json.", body["error"]["message"])
        self.assertEqual(1, len(result.stdout.splitlines()))

    def test_submit_unicode_from_stdin_once_without_mcp(self):
        text = '查询版本\n保留 "引号"、$符号和 emoji 👋'
        with api() as server:
            result, body = self.invoke("task", "run", "--goal-file", "-", server=server, stdin=text)
            self.assertEqual(0, result.returncode)
            self.assertEqual("t1", body["task_id"])
            self.assertEqual([("GET", "/api/v1", None), ("POST", "/api/v1/tasks", {"goal": text})], server.calls)

    def test_unicode_is_utf8_even_when_the_console_code_page_is_not(self):
        # Windows pipes default to the locale code page; the CLI must not depend on it.
        text = "查询电量 👋"
        reply = (201, {**SNAPSHOT, "goal": text}, {})
        with api(lambda method, path, body: reply if method == "POST" else None) as server:
            result, body = self.invoke("task", "run", "--goal-file", "-", server=server, stdin=text,
                                       env={"PYTHONUTF8": "0", "PYTHONIOENCODING": "latin-1"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(text, body["goal"])
            self.assertEqual({"goal": text}, server.calls[-1][2])

    def test_direct_http_does_not_require_adb(self):
        with api() as server:
            result, body = self.invoke("connect", "--adb", "missing-adb-executable", server=server)
            self.assertEqual(0, result.returncode)
            self.assertTrue(body["connected"])
            self.assertEqual("http", body["transport"])

    def test_response_loss_is_unknown_and_not_retried(self):
        with api(lambda method, path, body: "drop" if method == "POST" else None) as server:
            result, body = self.invoke("task", "run", "--goal", "once", server=server)
            self.assertEqual(4, result.returncode)
            self.assertEqual("outcome_unknown", body["error"]["code"])
            self.assertEqual(1, len([c for c in server.calls if c[0] == "POST"]))

    def test_lost_run_returns_the_preassigned_queryable_task_id(self):
        accepted = {}
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks", "task_recovery"]}, {}
            if method == "POST":
                accepted[body["request_id"]] = {**SNAPSHOT, "task_id": body["request_id"], "goal": body["goal"]}
                return "drop"
            return 200, accepted[path.split("/")[-1]], {}
        with api(respond) as server:
            result, body = self.invoke("task", "run", "--goal", "once", server=server)
            self.assertEqual(4, result.returncode)
            key = body["error"]["task_id"]
            self.assertEqual("outcome_unknown", body["error"]["code"])
            result, task = self.invoke("task", "status", key, server=server)
            self.assertEqual(0, result.returncode)
            self.assertEqual(key, task["task_id"])
            self.assertEqual(1, sum(m == "POST" for m, _, _ in server.calls))

    def test_explicit_request_id_never_downgrades_on_an_old_phone(self):
        with api() as server:
            result, body = self.invoke("task", "run", "--goal", "once", "--request-id",
                                       "00000000-0000-4000-8000-000000000001", server=server)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("incompatible_api", body["error"]["code"])
            self.assertIn("task_recovery", body["error"]["message"])
            self.assertIn(DISCOVERY["server_version"], body["error"]["message"])
            self.assertIn("without --request-id", body["error"]["message"])
            self.assertEqual("task_recovery", body["error"]["capability"])
            self.assertEqual(DISCOVERY["server_version"], body["error"]["server_version"])
            self.assertFalse(any(m == "POST" for m, _, _ in server.calls))

    def test_capability_errors_stay_actionable_without_a_phone_version(self):
        old = {"api_version": 1, "capabilities": ["tasks"]}
        for args in [["task", "run", "--goal", "once", "--request-id", "00000000-0000-4000-8000-000000000002"],
                     ["task", "steer", "t1", "--text", "more"],
                     ["task", "export", "t1"],
                     ["task", "download", "t1", "f1", "--output", "out"]]:
            with self.subTest(args=args), api(lambda *_: (200, old, {})) as server:
                result, body = self.invoke(*args, server=server)
                self.assertEqual(2, result.returncode)
                error = body["error"]
                self.assertEqual("incompatible_api", error["code"])
                self.assertTrue(error["capability"])
                self.assertIn(error["capability"], error["message"])
                self.assertNotIn("server_version", error)
                self.assertNotIn("version", error["message"])

    def test_download_names_the_missing_files_capability(self):
        with api() as server:
            result, body = self.invoke("task", "download", "t1", "f1", "--output", "out", server=server)
            self.assertEqual(2, result.returncode)
            self.assertEqual("task_files", body["error"]["capability"])
            self.assertIn("task_files", body["error"]["message"])

    def test_unknown_answer_preserves_known_task_id(self):
        with api(lambda method, path, body: "drop" if method == "POST" else None) as server:
            result, body = self.invoke("task", "answer", "t1", "--text", "answer", server=server)
            self.assertEqual(4, result.returncode)
            self.assertEqual("t1", body["error"]["task_id"])

    def test_steer_sends_unicode_once_and_returns_message_id(self):
        text = '补充要求\n只读，不发送 "消息" 👋'
        ack = {"task_id": "t1", "message_id": "s1", "accepted": True}
        for file_input in [False, True]:
            with self.subTest(file_input=file_input), api(lambda method, path, body: (202, ack, {}) if path.endswith("/steer") else None) as server:
                source = self.directory / "steer.txt"
                source.write_text(text, encoding="utf-8")
                args = ["--text-file", str(source)] if file_input else ["--text-file", "-"]
                result, body = self.invoke("task", "steer", "t1", *args, server=server, stdin=None if file_input else text)
                self.assertEqual(0, result.returncode)
                self.assertEqual(ack, body)
                self.assertEqual([("GET", "/api/v1", None), ("POST", "/api/v1/tasks/t1/steer", {"text": text})], server.calls)

    def test_steer_requires_capability_and_never_falls_back_to_run(self):
        old = {**DISCOVERY, "capabilities": ["tasks"]}
        with api(lambda *_: (200, old, {})) as server:
            result, body = self.invoke("task", "steer", "t1", "--text", "more", server=server)
            self.assertEqual(2, result.returncode)
            self.assertEqual("incompatible_api", body["error"]["code"])
            self.assertEqual("task_steer", body["error"]["capability"])
            self.assertIn("task_steer", body["error"]["message"])
            self.assertEqual([("GET", "/api/v1", None)], server.calls)

    def test_uncertain_steer_is_not_repeated(self):
        for reply in ["drop", (202, {"task_id": "t1", "accepted": True}, {})]:
            with self.subTest(reply=reply), api(lambda method, path, body: reply if method == "POST" else None) as server:
                result, body = self.invoke("task", "steer", "t1", "--text", "more", server=server)
                self.assertEqual(4, result.returncode)
                self.assertEqual("outcome_unknown", body["error"]["code"])
                self.assertEqual("t1", body["error"]["task_id"])
                self.assertEqual(1, len([c for c in server.calls if c[0] == "POST"]))

    def test_write_500_or_malformed_acknowledgement_is_unknown(self):
        for reply in [(500, {}, {}), (200, {}, {}), (200, b"invalid", {})]:
            with self.subTest(reply=reply), api(lambda method, path, body: reply if method == "POST" else None) as server:
                result, body = self.invoke("task", "run", "--goal", "once", server=server)
                self.assertEqual(4, result.returncode)
                self.assertEqual("outcome_unknown", body["error"]["code"])

    def test_known_rejection_is_reported_without_retry(self):
        response = (409, {"error": {"code": "conflict", "message": "Use the phone"}}, {})
        with api(lambda method, path, body: response if method == "POST" else None) as server:
            result, body = self.invoke("task", "answer", "t1", "--text", "yes", server=server)
            self.assertEqual(1, result.returncode)
            self.assertEqual("conflict", body["error"]["code"])
            self.assertEqual(1, len([c for c in server.calls if c[0] == "POST"]))

    def test_wait_surfaces_an_existing_question_or_phone_handoff_immediately(self):
        for state in ["waiting_for_answer", "waiting_on_phone"]:
            with self.subTest(state=state), api(lambda method, path, body: (200, {**SNAPSHOT, "status": state}, {}) if path.endswith("/t1") else None) as server:
                result, body = self.invoke("task", "wait", "t1", "--timeout", "45", server=server)
                self.assertEqual(0, result.returncode)
                self.assertEqual(state, body["status"])
                self.assertFalse(body["changed"])
                self.assertFalse(any(c[0] == "POST" for c in server.calls))

    def test_wait_budget_expiry_keeps_running_task(self):
        with api(lambda method, path, body: (200, {**SNAPSHOT, "changed": False}, {}) if path.endswith("/wait") else None) as server:
            result, body = self.invoke("task", "wait", "t1", "--timeout", "1", server=server)
            self.assertEqual(0, result.returncode)
            self.assertEqual("running", body["status"])
            self.assertEqual(("POST", "/api/v1/tasks/t1/wait", {"timeout_s": 1}), server.calls[-1])
            self.assertFalse(any(c[1].endswith("/cancel") for c in server.calls))

    def test_cancel_acknowledgement_is_not_a_terminal_state(self):
        with api(lambda method, path, body: (202, {"task_id": "t1", "accepted": True}, {}) if path.endswith("/cancel") else None) as server:
            result, body = self.invoke("task", "cancel", "t1", server=server)
            self.assertEqual(0, result.returncode)
            self.assertTrue(body["accepted"])
            self.assertNotIn("status", body)

    def test_verified_is_not_invented_and_failed_tasks_exit_nonzero(self):
        for status in ["done", "failed", "cancelled"]:
            with self.subTest(status=status), api(lambda method, path, body: (200, {**SNAPSHOT, "status": status}, {}) if path.endswith("/t1") else None) as server:
                result, body = self.invoke("task", "status", "t1", server=server)
                self.assertEqual(0 if status == "done" else 1, result.returncode)
                self.assertNotIn("verified", body)

    def test_old_apk_does_not_trigger_mcp_fallback(self):
        with api(lambda *_: (404, {}, {})) as server:
            result, body = self.invoke("doctor", server=server)
            self.assertEqual("incompatible_api", body["error"]["code"])
            self.assertEqual([("GET", "/api/v1", None)], server.calls)

    def test_malformed_discovery_is_a_structured_error(self):
        with api(lambda *_: (200, {"api_version": 1, "capabilities": None}, {})) as server:
            result, body = self.invoke("doctor", server=server)
            self.assertEqual(2, result.returncode)
            self.assertEqual("incompatible_api", body["error"]["code"])

    def test_redirect_never_receives_credentials(self):
        with api() as other:
            redirect = (302, {}, {"Location": "http://127.0.0.1:%s/api/v1" % other.server_port})
            with api(lambda *_: redirect) as server:
                result, body = self.invoke("doctor", server=server)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], other.calls)

    def test_credentials_are_redacted_from_server_errors(self):
        with api(lambda *_: (401, {"error": {"code": "access_denied", "message": "echo " + TOKEN}}, {})) as server:
            result, body = self.invoke("doctor", server=server)
            self.assertIn("[redacted]", body["error"]["message"])

    def test_text_cut_through_a_surrogate_pair_still_prints_one_json_document(self):
        # A phone-side string truncated mid surrogate pair arrives as \ud800, which has no UTF-8
        # form. The result is still JSON, so it is escaped rather than crashing on an empty stdout.
        # Raw bytes: the wire carries the ASCII escape, which is what the phone actually sends.
        payload = json.dumps({**SNAPSHOT, "goal": "cut \ud800"}).encode()
        with api(lambda method, path, body: (200, payload, {}) if path.endswith("/t1") else None) as server:
            command = [sys.executable, str(SCRIPT), "--json", "task", "status", "t1",
                       "--url", "http://127.0.0.1:" + str(server.server_port), "--token-file", str(self.token_file)]
            result = subprocess.run(command, text=True, encoding="utf-8", capture_output=True,
                                    cwd=self.directory, env={**os.environ, "PYTHONUTF8": "1"}, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("cut \ud800", json.loads(result.stdout)["goal"])
            self.assertNotIn(TOKEN, result.stdout + result.stderr)

    def test_adb_connect_owns_only_its_temporary_forward(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server)
            result, body = self.invoke("connect", "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("phone-a", body["device"])
            self.assertIn(["-s", "phone-a", "forward", "tcp:0", "tcp:8765"], calls())
            self.assertEqual(["-s", "phone-a", "forward", "--remove", "tcp:" + str(server.server_port)], calls()[-1])
            root = "/sdcard/Android/data/com.wangzan101.mika/files"
            self.assertIn(["-s", "phone-a", "shell", "sh", root + "/connect-computer.sh"], calls())
            self.assertIn(["-s", "phone-a", "shell", "cat", root + "/mcp-usb.json"], calls())

    def test_adb_targets_the_selected_mika_package(self):
        package = "com.wangzan101.mika.dev"
        root = f"/sdcard/Android/data/{package}/files"
        with api() as server:
            adb, env, _, calls = self.fake_adb(
                server, info={"api_version": 1, "token": TOKEN, "port": 8766, "boot": "17"}
            )
            result, body = self.invoke("connect", "--package", package, "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, body)
            result, body = self.invoke("--package", package, "task", "status", "t1", "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, body)
            package_calls = [call[2:] for call in calls() if call[2:4] in (["shell", "sh"], ["shell", "cat"])]
            self.assertEqual([
                ["shell", "sh", root + "/connect-computer.sh"],
                ["shell", "cat", root + "/mcp-usb.json"],
                ["shell", "cat", root + "/mcp-usb.json"],
            ], package_calls)
            self.assertEqual(2, calls().count(["-s", "phone-a", "forward", "tcp:0", "tcp:8766"]))

    def test_adb_rejects_invalid_mika_packages_before_starting_adb(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server)
            for package in ("", "android", "com.1mika", "../mika", "com.mika;id"):
                with self.subTest(package=package):
                    result, body = self.invoke("connect", "--package", package, "--adb", str(adb), env=env)
                    self.assertEqual(2, result.returncode, body)
                    self.assertEqual("arguments", body["error"]["code"])
                    self.assertIn("Android application ID", body["error"]["message"])
            self.assertEqual([], calls())

    def test_mika_package_cannot_be_used_with_direct_http(self):
        with api() as server:
            result, body = self.invoke("doctor", "--package", "com.wangzan101.mika.dev", server=server)
            self.assertEqual(2, result.returncode, body)
            self.assertEqual("arguments", body["error"]["code"])
            self.assertIn("cannot be used with --url", body["error"]["message"])
            self.assertEqual([], server.calls)

    def test_notification_doctor_package_still_targets_the_app_to_inspect(self):
        cli = load_cli()
        args = cli.parser().parse_args(["notification-doctor", "--package", "android"])
        self.assertEqual("android", args.package)
        self.assertFalse(hasattr(args, "mika_package"))

    def test_unique_wireless_device_can_have_spaces_in_its_service_name(self):
        serial = "test-phone (2)._adb-tls-connect._tcp"
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, devices="List of devices attached\n" + serial + "\tdevice\n")
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, body)
            self.assertEqual(serial, body["device"])
            self.assertIn(["-s", serial, "get-state"], calls())

    def test_multiple_phones_need_explicit_device(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, devices="List of devices attached\nphone-a\tdevice\nphone-b\tdevice\n")
            result, body = self.invoke("connect", "--adb", str(adb), env=env)
            self.assertEqual("device_selection", body["error"]["code"])
            self.assertEqual([{"device": "phone-a", "state": "device"}, {"device": "phone-b", "state": "device"}], body["error"]["devices"])
            self.assertEqual([["start-server"], ["devices"]], calls())
            result, body = self.invoke("doctor", "--adb", str(adb), "--device", "phone-b", env=env)
            self.assertEqual(0, result.returncode)
            self.assertEqual("phone-b", body["device"])
            targeted = [c for c in calls()[2:] if c not in (["start-server"], ["devices"])]
            self.assertTrue(targeted and all(c[:2] == ["-s", "phone-b"] for c in targeted))

    def test_stale_boot_never_creates_a_forward(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, boot="18")
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual("stale_connection", body["error"]["code"])
            self.assertFalse(any("forward" in c for c in calls()))

    def test_current_token_is_read_each_time_and_not_cached(self):
        with api() as server:
            adb, env, config, _ = self.fake_adb(server)
            first, _ = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual(0, first.returncode)
            cfg = json.loads(config.read_text())
            server.token = cfg["info"]["token"] = "rotated-test-connection-token"
            config.write_text(json.dumps(cfg))
            second, _ = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual(0, second.returncode)
            self.assertNotIn(server.token, second.stdout + second.stderr)

    def test_error_still_releases_only_owned_forward(self):
        with api(lambda *_: (401, {"error": {"message": "rejected"}}, {})) as server:
            adb, env, _, calls = self.fake_adb(server)
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["-s", "phone-a", "forward", "--remove", "tcp:" + str(server.server_port)], calls()[-1])

    def test_bootstrap_failure_does_not_expose_script_output(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, bootstrap_exit=1)
            result, body = self.invoke("connect", "--adb", str(adb), env=env)
            self.assertEqual("adb", body["error"]["code"])
            self.assertIn("open Mika once", body["error"]["message"])
            self.assertFalse(any("forward" in c for c in calls()))

    def test_unreadable_connection_info_asks_for_unlock_and_open(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, cat_fails=True)
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual("connection_info", body["error"]["code"])
            self.assertIn("open Mika once", body["error"]["message"])
            self.assertFalse(any("forward" in c for c in calls()))

    def test_unrunnable_adb_is_adb_unavailable(self):
        not_a_program = self.directory / "not-adb"
        not_a_program.write_text("this is not an executable")
        for path in [str(self.directory / "missing-adb-executable"), str(not_a_program)]:
            with self.subTest(path=path):
                result, body = self.invoke("doctor", "--adb", path)
                self.assertEqual(2, result.returncode)
                self.assertEqual("adb_unavailable", body["error"]["code"])
                self.assertIn("--adb", body["error"]["message"])

    def test_device_states_name_the_phone_action(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, devices="List of devices attached\nphone-a\tunauthorized\n")
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual("device_offline", body["error"]["code"])
            self.assertEqual("unauthorized", body["error"]["state"])
            self.assertIn("USB debugging prompt", body["error"]["message"])
            self.assertFalse(any("forward" in c or "shell" in c for c in calls()))

            adb, env, _, calls = self.fake_adb(server, devices="List of devices attached\nphone-a\toffline\nphone-b\tdevice\n")
            result, body = self.invoke("doctor", "--adb", str(adb), "--device", "phone-a", env=env)
            self.assertEqual("device_offline", body["error"]["code"])
            self.assertEqual("offline", body["error"]["state"])
            self.assertIn("Reconnect the cable", body["error"]["message"])
            self.assertFalse(any("get-state" in c for c in calls()))

            adb, env, _, calls = self.fake_adb(server, devices="List of devices attached\nphone-a\toffline\nphone-b\tunauthorized\n")
            result, body = self.invoke("connect", "--adb", str(adb), env=env)
            self.assertEqual("device_selection", body["error"]["code"])
            self.assertEqual([{"device": "phone-a", "state": "offline"}, {"device": "phone-b", "state": "unauthorized"}],
                             body["error"]["devices"])

    def test_failed_get_state_is_device_offline_not_generic_adb_error(self):
        with api() as server:
            adb, env, _, calls = self.fake_adb(server, get_state_fails=True)
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual("device_offline", body["error"]["code"])
            self.assertIn("not online", body["error"]["message"])
            self.assertFalse(any("forward" in c for c in calls()))

    def test_forward_removal_gives_up_after_a_short_wait(self):
        with api() as server:
            adb, env, _, _ = self.fake_adb(server, remove_sleep=8)
            result, body = self.invoke("doctor", "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("could not confirm removal", result.stderr)
            self.assertEqual("ok", body["connection"])

    def test_progress_lines_are_stderr_only_and_never_for_task_commands(self):
        with api() as server:
            adb, env, _, _ = self.fake_adb(server)
            result, body = self.invoke("connect", "--adb", str(adb), env=env)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            for line in ["starting ADB server...", "running phone bootstrap...", "contacting the phone..."]:
                self.assertIn("mika: " + line, result.stderr)
            self.assertNotIn("mika:", result.stdout)
            result, _ = self.invoke("task", "run", "--goal", "quiet", server=server)
            self.assertEqual("", result.stderr)

    def test_adb_calls_never_wait_on_stdin_or_inherited_pipes(self):
        module = load_cli()
        with patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 20)) as run:
            with self.assertRaises(module.CliError) as caught:
                module.Adb("adb").run("devices")
            self.assertEqual("adb", caught.exception.code)
            self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stdin"])
        with patch.object(module.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 60)) as run:
            with self.assertRaises(module.CliError) as caught:
                module.Adb("adb").start_server()
            self.assertEqual("adb", caught.exception.code)
            kwargs = run.call_args.kwargs
            self.assertEqual((subprocess.DEVNULL, subprocess.DEVNULL, subprocess.DEVNULL, 60),
                             (kwargs["stdin"], kwargs["stdout"], kwargs["stderr"], kwargs["timeout"]))
        with patch.object(module.subprocess, "run", side_effect=PermissionError("denied")):
            with self.assertRaises(module.CliError) as caught:
                module.Adb("adb").start_server()
            self.assertEqual("adb_unavailable", caught.exception.code)

    def test_adb_server_starts_once_per_process(self):
        module = load_cli()
        done = subprocess.CompletedProcess(["adb", "start-server"], 0)
        with patch.object(module.subprocess, "run", return_value=done) as run:
            module.Adb("adb").start_server()
            module.Adb("adb", "phone-a").start_server()
            self.assertEqual(1, run.call_count)
            module.Adb("/other/adb").start_server()
            self.assertEqual(2, run.call_count)

    def test_skill_copy_runs_without_original_repository(self):
        copied = self.directory / "installed-skill"
        shutil.copytree(SKILL, copied, ignore=shutil.ignore_patterns("__pycache__", "*.egg-info", "build"))
        with api() as server:
            result, body = self.invoke("task", "run", "--goal", "standalone", server=server, script=copied / "scripts/mika_cli.py")
            self.assertEqual(0, result.returncode)
            self.assertEqual("t1", body["task_id"])

    def test_invalid_input_does_not_touch_adb(self):
        result, body = self.invoke("task", "run", "--goal", "  ", "--adb", "does-not-exist")
        self.assertEqual("input", body["error"]["code"])
        result, body = self.invoke("task", "wait", "t1", "--timeout", "51")
        self.assertEqual("arguments", body["error"]["code"])

    def test_interrupted_mutation_is_unknown(self):
        spec = importlib.util.spec_from_file_location("mika_cli_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        client = module.Client("http://127.0.0.1/api/v1", TOKEN)
        with patch.object(client.opener, "open", side_effect=KeyboardInterrupt):
            with self.assertRaises(module.CliError) as caught:
                client.request("POST", "/tasks", {"goal": "once"}, mutation=True)
        self.assertEqual("outcome_unknown", caught.exception.code)

    def test_export_fetches_the_record_as_one_json_object(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, DISCOVERY_EXPORT, {}
            if method == "GET" and path == "/api/v1/tasks/t1/export":
                return 200, EXPORT, {}
            return 500, {}, {}

        with api(respond) as server:
            result, body = self.invoke("task", "export", "t1", server=server)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(EXPORT, body)
            self.assertTrue(body["partial"])
            self.assertEqual([("GET", "/api/v1", None), ("GET", "/api/v1/tasks/t1/export", None)], server.calls)

    def test_export_requires_capability_and_unknown_id_is_explicit(self):
        with api(lambda *_: (200, DISCOVERY, {})) as server:
            result, body = self.invoke("task", "export", "t1", server=server)
            self.assertEqual(2, result.returncode)
            self.assertEqual("incompatible_api", body["error"]["code"])
            self.assertEqual("run_export", body["error"]["capability"])
            self.assertIn("run_export", body["error"]["message"])
            self.assertEqual([("GET", "/api/v1", None)], server.calls)
        message = "No remote task or run record with id missing"

        def respond(method, path, body):
            if path == "/api/v1":
                return 200, DISCOVERY_EXPORT, {}
            return 404, {"error": {"code": "not_found", "message": message}}, {}

        with api(respond) as server:
            result, body = self.invoke("task", "export", "missing", server=server)
            self.assertEqual(1, result.returncode)
            self.assertEqual("not_found", body["error"]["code"])
            self.assertEqual(message, body["error"]["message"])

    def test_events_flag_prints_jsonl_without_rewrapping(self):
        header = {key: value for key, value in EXPORT.items() if key != "events"}
        event = {"type": "task_started", "goal": "open\u2028settings"}
        payload = (json.dumps(header) + "\n" + json.dumps(event, ensure_ascii=False) + "\n").encode()

        with events_api(payload) as server:
            result, _ = self.invoke("task", "export", "t1", "--events", server=server, parse=False, encoding=None)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(payload, result.stdout)
            self.assertEqual([("GET", "/api/v1", None), ("GET", "/api/v1/tasks/t1/export?events=jsonl", None)], server.calls)

    def test_events_flag_rejects_malformed_json_after_valid_header(self):
        header = {key: value for key, value in EXPORT.items() if key != "events"}
        payload = (json.dumps(header) + '\n{"type":"task_started"\n').encode()
        with events_api(payload) as server:
            result, body = self.invoke("task", "export", "t1", "--events", server=server)
            self.assertEqual(2, result.returncode)
            self.assertEqual("protocol", body["error"]["code"])

    def test_malformed_export_id_does_not_connect(self):
        result, body = self.invoke("task", "export", "../secret", "--adb", "does-not-exist")
        self.assertEqual(2, result.returncode)
        self.assertEqual("arguments", body["error"]["code"])


EXPORT = {
    "exportFormat": 1, "partial": True, "includesScreenshots": False,
    "task_id": "t1", "conversation_id": "mcp-11111111-1111-1111-1111-111111111111",
    "run_id": "20261102-033700-abc123",
    "run": {"id": "20261102-033700-abc123", "goal": "open settings"},
    "events": [{"type": "task_started", "goal": "open settings"}],
}
DISCOVERY_EXPORT = {**DISCOVERY, "capabilities": ["tasks", "task_steer", "run_export"]}


def events_api(payload):
    def respond(method, path, body):
        if path == "/api/v1":
            return 200, DISCOVERY_EXPORT, {}
        if method == "GET" and path == "/api/v1/tasks/t1/export?events=jsonl":
            return 200, payload, {"Content-Type": "application/x-ndjson"}
        return 500, {}, {}

    return api(respond)


if __name__ == "__main__":
    unittest.main()
