"""Exercise the installed-style CLI entry and its browser boundary against the phone API."""
from argparse import Namespace
from contextlib import contextmanager
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

from test_mika_cli import api, DISCOVERY, SCRIPT, SNAPSHOT, TOKEN


@contextmanager
def desk(*args, env=None, script=SCRIPT):
    # Isolated mode, as an agent may run the copied Skill script, must still find the desk files.
    with subprocess.Popen([sys.executable, "-I", str(script), "ui", "--no-open", *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True, env=env) as proc:
        try:
            url = urlsplit(proc.stdout.readline().strip())
            if url.hostname != "127.0.0.1" or len(url.fragment) < 32:
                raise AssertionError("Desk did not print a local session link")
            yield url
        finally:
            proc.terminate()
            proc.wait(timeout=5)


def request(url, data=None, *, path="/desk", headers=None):
    origin = "http://" + url.netloc
    request_headers = {"Origin": origin, "X-Mika-Desk": url.fragment, "Content-Type": "application/json"}
    request_headers.update(headers or {})
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=5)
    try:
        conn.request("GET" if data is None else "POST", path, json.dumps(data) if data is not None else None,
                     request_headers)
        response = conn.getresponse()
        payload = response.read().decode()
        if TOKEN in payload:
            raise AssertionError("Phone token exposed in desk response")
        if response.getheader("Content-Type", "").startswith("application/json"):
            payload = json.loads(payload)
        return response.status, payload
    finally:
        conn.close()


class DeskTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.token = Path(temp.name) / "credential"
        self.token.write_text(TOKEN)

    def launch(self, phone):
        return desk("--url", f"http://127.0.0.1:{phone.server_port}", "--token-file", str(self.token))

    def test_page_and_task_operations_share_the_existing_service(self):
        def respond(method, path, body):
            if path.endswith("/tasks?limit=50"):
                return 200, {"tasks": [SNAPSHOT]}, {}
            if path.endswith(("/answer", "/cancel")):
                return 202, {"task_id": "t1", "accepted": True}, {}
            if path.endswith("/steer"):
                return 202, {"task_id": "t1", "accepted": True, "message_id": "s1"}, {}

        with api(respond) as phone, self.launch(phone) as url:
            self.assertIn('data-i18n="heading"', request(url, path="/")[1])
            for path in ["/desk.css", "/desk.js", "/notifications.js"]:
                self.assertEqual(200, request(url, path=path)[0])
            code, overview = request(url, {"action": "overview"})
            self.assertEqual(200, code)
            self.assertEqual([SNAPSHOT], overview["tasks"])
            self.assertIn("task_steer", overview["capabilities"])
            self.assertEqual({}, overview["device"])
            self.assertEqual((200, SNAPSHOT), request(url, {"action": "run", "goal": "中文任务"}))
            self.assertEqual((200, SNAPSHOT), request(url, {"action": "status", "task_id": "t1"}))
            for action in ["answer", "steer", "cancel"]:
                fields = {} if action == "cancel" else {"text": "补充"}
                code, receipt = request(url, {"action": action, "task_id": "t1", **fields})
                self.assertEqual(200, code)
                self.assertTrue(receipt["accepted"])
                self.assertNotIn("status", receipt)
            self.assertEqual(["/api/v1/tasks", "/api/v1/tasks/t1/answer", "/api/v1/tasks/t1/steer",
                              "/api/v1/tasks/t1/cancel"], [path for method, path, _ in phone.calls if method == "POST"])

    def test_watch_returns_recent_tasks_after_a_bounded_read_even_if_the_watched_id_expired(self):
        for wait_status in (200, 404):
            def respond(method, path, body):
                if path.endswith("/wait"):
                    return wait_status, {**SNAPSHOT, "changed": False}, {}
                if path.endswith("/tasks?limit=50"):
                    return 200, {"tasks": [] if wait_status == 404 else [SNAPSHOT]}, {}

            with self.subTest(wait_status=wait_status), api(respond) as phone, self.launch(phone) as url:
                code, view = request(url, {"action": "watch", "task_id": "t1"})
                self.assertEqual(200, code)
                self.assertEqual([] if wait_status == 404 else [SNAPSHOT], view["tasks"])
                self.assertEqual(["/api/v1", "/api/v1/tasks/t1/wait", "/api/v1/tasks?limit=50"],
                                 [path for _, path, _ in phone.calls])
                method, _, bound = phone.calls[1]
                self.assertEqual("POST", method)
                self.assertTrue(1 <= bound["timeout_s"] <= 5)

    def test_new_and_continued_turns_send_distinct_phone_requests(self):
        conversation = "mcp-3d18667b-238a-4985-9546-cc6ea6b94c2b"
        with api() as phone, self.launch(phone) as url:
            for fields in [{}, {"conversation": conversation}]:
                self.assertEqual(200, request(url, {"action": "run", "goal": "next", **fields})[0])
            self.assertEqual([{"goal": "next"}, {"goal": "next", "conversation": conversation}],
                             [body for method, path, body in phone.calls if method == "POST" and path == "/api/v1/tasks"])

    def test_browser_session_origin_host_and_command_allowlist(self):
        with api() as phone, self.launch(phone) as url:
            command = {"action": "run", "goal": "no"}
            for headers in [{"X-Mika-Desk": ""}, {"Origin": "https://other.invalid"}, {"Origin": "null"},
                            {"Host": "other.invalid"}, {"Content-Type": "text/plain"}]:
                self.assertIn(request(url, command, headers=headers)[0], (403, 415))
            for data in [{**command, "url": "http://other.invalid"}, {**command, "goal_file": "/etc/hosts"},
                         {"action": "connect"}, {"action": "status", "task_id": "../device"}]:
                self.assertEqual(400, request(url, data)[0])
            self.assertEqual(404, request(url, command, path="/mcp")[0])
            self.assertEqual(403, request(url, path="/", headers={"Host": "other.invalid"})[0])
            self.assertEqual([], phone.calls)

    def test_session_comes_only_from_the_printed_link(self):
        with api() as phone, self.launch(phone) as url:
            # No endpoint hands out the secret, and a guessed one is refused without contacting the phone.
            for path in ["/session", "/desk"]:
                self.assertEqual(403, request(url, {}, path=path, headers={"X-Mika-Desk": "open"})[0])
            self.assertEqual(403, request(url, {"action": "overview"}, headers={"X-Mika-Desk": url.fragment[:-1]})[0])
            self.assertEqual([], phone.calls)
        result = subprocess.run([sys.executable, str(SCRIPT), "ui", "--no-open", "--port", "18765"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)

    def test_every_text_has_chinese_and_english(self):
        desk_dir = SCRIPT.parent / "mika_desk"
        script = (desk_dir / "desk.js").read_text(encoding="utf-8")
        table = script[script.index("const STRINGS = {"):script.index("\n};", script.index("const STRINGS = {"))]
        strings = dict(re.findall(r'^  (\w+): \["([^"]+)", "[^"]+"\],$', table, re.M))
        self.assertEqual(len(strings), table.count("\n  "), "every entry needs non-empty Chinese and English text")
        used = set(re.findall(r'\bt\("(\w+)"', script + (desk_dir / "notifications.js").read_text(encoding="utf-8")))
        used |= set(re.findall(r'data-i18n(?:-placeholder|-label)?="(\w+)"', (desk_dir / "index.html").read_text(encoding="utf-8")))
        used |= set(re.findall(r'"(err[A-Z]\w*)"', script))
        self.assertEqual(set(), used - strings.keys())
        tones = script[script.index("const tones = {"):script.index("};", script.index("const tones = {"))]
        self.assertEqual(set(), set(re.findall(r"(\w+):", tones)) - strings.keys())
        self.assertIsNone(re.search(r"[\u4e00-\u9fff]", script.replace(table, "")), "Chinese text outside the table")

    def test_lost_write_is_not_retried_and_old_phones_cannot_steer(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks"]}, {}
            if method == "POST":
                return "drop"
        with api(respond) as phone, self.launch(phone) as url:
            code, outcome = request(url, {"action": "answer", "task_id": "t1", "text": "yes"})
            self.assertEqual(502, code)
            self.assertEqual("outcome_unknown", outcome["error"]["code"])
            self.assertEqual("t1", outcome["error"]["task_id"])
            _, denied = request(url, {"action": "steer", "task_id": "t1", "text": "update"})
            self.assertEqual("incompatible_api", denied["error"]["code"])
            self.assertEqual(1, sum(method == "POST" for method, _, _ in phone.calls))

    def test_target_choice_is_limited_to_online_devices_and_stays_pinned(self):
        with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
            import mika_desk
        args = Namespace(url=None, device=None, adb="adb")
        online = [{"device": "phone-a", "state": "device"}, {"device": "phone-b", "state": "device"},
                  {"device": "other", "state": "offline"}]
        with patch.dict(os.environ, {"ANDROID_SERIAL": ""}), mika_desk.Desk(args) as server:
            with patch.object(mika_desk.Adb, "start_server"), \
                    patch.object(mika_desk.Adb, "devices", return_value=online) as listing:
                for choice in [None, "other", "unknown"]:
                    with self.assertRaises(mika_desk.CliError) as caught:
                        server.pin_target(choice)
                    self.assertEqual(["phone-a", "phone-b"], caught.exception.details["devices"])
                    self.assertFalse(args.device)
                server.pin_target("phone-b")
                listing.return_value = [online[0]]  # The chosen phone disconnected.
                server.pin_target(None)
                with self.assertRaises(mika_desk.CliError) as caught:
                    server.pin_target("phone-a")
                self.assertEqual("device_pinned", caught.exception.code)
                self.assertEqual("phone-b", args.device)

    def test_a_lone_unready_phone_reports_its_state(self):
        with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
            import mika_desk
        args = Namespace(url=None, device=None, adb="adb")
        with patch.dict(os.environ, {"ANDROID_SERIAL": ""}), \
                patch.object(mika_desk.Adb, "start_server") as start, \
                patch.object(mika_desk.Adb, "devices", return_value=[{"device": "phone-a", "state": "unauthorized"}]), \
                mika_desk.Desk(args) as server:
            worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
            worker.start()
            try:
                url = urlsplit(server.origin + "/#" + server.key)
                results = [request(url, {"action": "overview"}) for _ in range(2)]
            finally:
                server.shutdown()
                worker.join(timeout=2)
        start.assert_called()
        for code, body in results:
            self.assertEqual(502, code)
            self.assertEqual("device_offline", body["error"]["code"])
            self.assertEqual("unauthorized", body["error"]["state"])
            self.assertIn("USB debugging prompt", body["error"]["message"])
        self.assertFalse(args.device)

    def test_error_messages_redact_the_phone_token(self):
        with api(lambda *_: (401, {"error": {"message": "echo " + TOKEN}}, {})) as phone, self.launch(phone) as url:
            code, body = request(url, {"action": "overview"})
            self.assertEqual(401, code)
            self.assertIn("[redacted]", body["error"]["message"])

    def test_history_reads_reuse_transport_and_reject_path_or_page_injection(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks", "history"], "history_enabled": True}, {}
            if path.startswith("/api/v1/history/"):
                return 200, {"conversations": [], "next_offset": None, "text": "saved"}, {}
            if path.startswith("/api/v1/tasks?"):
                return 200, {"tasks": []}, {}
        with api(respond) as phone, self.launch(phone) as url:
            self.assertTrue(request(url, {"action": "overview"})[1]["history_enabled"])
            data = {"action": "history_body", "conversation": "phone-chat", "run_id": "run-one",
                    "span_id": "0123456789abcdef", "offset": "3000"}
            self.assertEqual("saved", request(url, data)[1]["text"])
            for field, value in [("conversation", "../settings"), ("run_id", "a?limit=0"), ("offset", "-1")]:
                count = len(phone.calls)
                self.assertEqual(400, request(url, {**data, field: value})[0])
                self.assertEqual(count, len(phone.calls))
            self.assertTrue(all(method == "GET" for method, _, _ in phone.calls))
            self.assertIn(("GET", "/api/v1/history/conversations/phone-chat/runs/run-one/bodies/0123456789abcdef?offset=3000", None), phone.calls)

    def test_disabled_history_does_not_fetch_private_conversations(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks", "history"], "history_enabled": False}, {}
            if path.startswith("/api/v1/tasks?"):
                return 200, {"tasks": []}, {}
        with api(respond) as phone, self.launch(phone) as url:
            self.assertEqual(200, request(url, {"action": "overview"})[0])
            self.assertFalse(any("/history/" in path for _, path, _ in phone.calls))
