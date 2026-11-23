"""File transfer contract at the desktop entry points. No phone or model credentials needed."""
import base64
import subprocess
import sys
import threading
import hashlib
import tempfile
import unittest
from pathlib import Path

import test_mika_cli
from test_mika_cli import DISCOVERY, SCRIPT, SNAPSHOT, TOKEN, api
from test_mika_desk import desk, request


class TaskFilesTest(unittest.TestCase):
    invoke = test_mika_cli.CliTest.invoke

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.token_file = self.directory / "credential"
        self.token_file.write_text(TOKEN)
        self.source = self.directory / "表格.csv"
        self.data = bytes(range(256)) * 1400
        self.source.write_bytes(self.data)
        self.received = bytearray()

    def respond(self, method, path, body):
        if path == "/api/v1":
            return 200, {**DISCOVERY, "capabilities": ["tasks", "task_files"]}, {}
        if path == "/api/v1/uploads":
            self.assertEqual({"name": self.source.name, "size": len(self.data)}, body)
            return 201, {"upload_id": "test-upload"}, {}
        if path == "/api/v1/uploads/test-upload":
            self.assertEqual(len(self.received), body["offset"])
            self.received.extend(base64.b64decode(body["data"], validate=True))
            return 200, {"next_offset": len(self.received)}, {}
        if path == "/api/v1/tasks":
            self.assertEqual(["test-upload"], body["attachments"])
            self.assertEqual(self.data, self.received)
            return 201, SNAPSHOT, {}
        if path == "/api/v1/tasks/t1":
            return 200, {**SNAPSHOT, "files": [{"id": "export", "name": "核对.csv", "size": len(self.data),
                                                                  "sha256": hashlib.sha256(self.data).hexdigest()}]}, {}
        if path.startswith("/api/v1/tasks/t1/files/export?offset="):
            pos = int(path.split("=")[1])
            return 200, {"offset": pos, "data": base64.b64encode(self.data[pos:pos + 131072]).decode()}, {}

    def test_cli_transfers_binary_chunks_and_does_not_replace_an_existing_file(self):
        out = self.directory / "result.csv"
        with api(self.respond) as phone:
            rc, task = self.invoke("task", "run", "--attach", str(self.source), server=phone)
            self.assertEqual((0, "t1"), (rc.returncode, task["task_id"]))
            rc, saved = self.invoke("task", "download", "t1", "export", "--output", str(out), server=phone)
            self.assertEqual((0, len(self.data)), (rc.returncode, saved["size"]))
            self.assertEqual(self.data, out.read_bytes())
            out.write_bytes(b"already here")
            rc, _ = self.invoke("task", "download", "t1", "export", "--output", str(out), server=phone)
            self.assertNotEqual(0, rc.returncode)
            self.assertEqual(b"already here", out.read_bytes())

    def test_interrupted_result_never_leaves_a_partial_download(self):
        def truncated(method, path, body):
            if path.endswith("offset=131072"):
                return 200, {"offset": 131072, "data": ""}, {}
            return self.respond(method, path, body)
        with api(truncated) as phone:
            out = self.directory / "incomplete.csv"
            rc, error = self.invoke("task", "download", "t1", "export", "--output", str(out), server=phone)
            self.assertNotEqual(0, rc.returncode)
            self.assertEqual("file_changed", error["error"]["code"])
            self.assertFalse(out.exists())

    def test_output_name_is_reserved_for_verified_bytes(self):
        for action in ("terminate", "create_during_download"):
            with self.subTest(action=action):
                second = threading.Event()
                resume = threading.Event()

                def wait_on_second_chunk(method, path, body):
                    if path.endswith("offset=131072"):
                        second.set()
                        resume.wait(10)
                    return self.respond(method, path, body)

                with api(wait_on_second_chunk) as phone:
                    out = self.directory / action
                    with subprocess.Popen([sys.executable, str(SCRIPT), "--url",
                            f"http://127.0.0.1:{phone.server_port}", "--token-file", str(self.token_file),
                            "task", "download", "t1", "export", "--output", str(out)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                        try:
                            self.assertTrue(second.wait(5))
                            self.assertFalse(out.exists(), "unverified bytes are visible at the final path")
                            if action == "terminate":
                                process.terminate()
                                process.wait(timeout=5)
                                self.assertFalse(out.exists())
                            else:
                                out.write_bytes(b"created by another process")
                                resume.set()
                                process.communicate(timeout=10)
                                self.assertNotEqual(0, process.returncode)
                                self.assertEqual(b"created by another process", out.read_bytes())
                        finally:
                            resume.set()
                            if process.poll() is None:
                                process.kill()

    def test_changed_file_of_the_same_size_is_not_delivered(self):
        def changed(method, path, body):
            response = self.respond(method, path, body)
            if path.endswith("offset=131072"):
                response[1]["data"] = base64.b64encode(b"x" * 131072).decode()
            return response
        with api(changed) as phone:
            out = self.directory / "changed.csv"
            rc, error = self.invoke("task", "download", "t1", "export", "--output", str(out), server=phone)
            self.assertNotEqual(0, rc.returncode)
            self.assertEqual("file_changed", error["error"]["code"])
            self.assertFalse(out.exists())

    def test_bad_hash_preserves_an_existing_destination(self):
        def changed(method, path, body):
            response = self.respond(method, path, body)
            if path.endswith("offset=131072"):
                response[1]["data"] = base64.b64encode(b"x" * 131072).decode()
            return response
        with api(changed) as phone:
            out = self.directory / "existing.csv"
            out.write_bytes(b"keep the old export")
            rc, error = self.invoke("task", "download", "t1", "export", "--output", str(out), server=phone)
            self.assertNotEqual(0, rc.returncode)
            self.assertEqual("output", error["error"]["code"])
            self.assertEqual(b"keep the old export", out.read_bytes())

    def test_old_phone_and_local_path_injection_cannot_turn_into_a_task(self):
        with api() as phone:
            rc, error = self.invoke("task", "run", "--attach", str(self.source), server=phone)
            self.assertEqual("incompatible_api", error["error"]["code"])
            self.assertNotEqual(0, rc.returncode)
            with desk("--url", "http://127.0.0.1:" + str(phone.server_port), "--token-file", str(self.token_file)) as url:
                self.assertEqual(400, request(url, {"action": "run", "goal": "", "attach": [str(self.source)]})[0])
                self.assertEqual(400, request(url, {"action": "run", "goal": "", "attachments": ["../x"]})[0])
                self.assertEqual(403, request(url, {}, path="/desk/upload", headers={"Origin": "http://elsewhere"})[0])
            self.assertFalse(any(method == "POST" for method, _, _ in phone.calls))
