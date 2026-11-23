"""Run with --device emulator-N on a disposable emulator with the debug and androidTest APKs installed.

Drops the socket after the real phone accepts a POST, then kills and relaunches
the app. All model replies come from RemoteTaskRecoveryDeviceTest.
"""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from test_mika_cli import SCRIPT


def until(read, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = read()
        if value:
            return value
        time.sleep(.25)
    raise AssertionError("Timed out waiting for " + read.__name__)


def run(serial):
    adb = ["adb", "-s", serial]
    package = "com.wangzan101.mika"

    def shell(*args, check=True):
        return subprocess.run(adb + ["shell", *args], check=check, capture_output=True, text=True, timeout=12)

    if not serial.startswith("emulator-") or shell("getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise ValueError("A disposable Android emulator is required")
    key, interrupted = str(uuid.uuid4()), str(uuid.uuid4())
    goal, other = "Recovery check " + key, "Interrupt check " + interrupted
    cli = [sys.executable, str(SCRIPT), "--json"]

    def command(target, *args, expected=0):
        p = subprocess.run(cli + target + ["task", *args], capture_output=True, text=True, timeout=30)
        assert p.returncode == expected, (p.returncode, p.stderr)
        return json.loads(p.stdout)

    def usb_info():
        proc = shell("cat", f"/sdcard/Android/data/{package}/files/mcp-usb.json", check=False)
        return json.loads(proc.stdout) if proc.returncode == 0 and proc.stdout.strip() else None

    def run_counts():
        records = json.loads(shell("run-as", package, "cat", "files/runs-v3.json").stdout)
        return [sum(r["goal"] == g for r in records) for g in (goal, other)]

    with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryFile(mode="w+") as log:
        test = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class",
            package + ".task.RemoteTaskRecoveryDeviceTest#concurrentRetriesStartOnlyOneTurn",
            "-e", "recoveryDriver", key, "-e", "interruptId", interrupted,
            package + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
        try:
            until(lambda: shell("run-as", package, "test", "-f", "cache/recovery-listening-" + key, check=False).returncode == 0)
            info = until(usb_info)
            credential = Path(tmp) / "connection"
            credential.write_text(info["token"])
            port = int(subprocess.check_output(adb + ["forward", "tcp:0", "tcp:" + str(info["port"])], text=True))
            posted = []

            class DropReceipt(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def forward(self):
                    data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=12)
                    try:
                        conn.request(self.command, self.path, body=data, headers={
                            "Content-Type": "application/json", "Authorization": self.headers["Authorization"]})
                        response = conn.getresponse()
                        raw = response.read()
                        if self.command == "POST" and response.status == 201:
                            posted.append(json.loads(raw)["task_id"])
                            self.connection.shutdown(socket.SHUT_RDWR)
                            self.close_connection = True
                            return
                        self.send_response(response.status)
                        self.send_header("Content-Type", "application/json")
                        self.end_headers()
                        self.wfile.write(raw)
                    finally:
                        conn.close()

                do_GET = do_POST = forward

            try:
                with ThreadingHTTPServer(("127.0.0.1", 0), DropReceipt) as proxy:
                    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
                    thread.start()
                    try:
                        error = command(["--url", f"http://127.0.0.1:{proxy.server_port}", "--token-file", str(credential)],
                                        "run", "--goal", goal, "--request-id", key, expected=4)["error"]
                        assert error["code"] == "outcome_unknown" and error["task_id"] == key and posted == [key]
                    finally:
                        proxy.shutdown()
                        thread.join()
            finally:
                subprocess.run(adb + ["forward", "--remove", "tcp:" + str(port)], check=True)

            target = ["--device", serial]
            until(lambda: shell("run-as", package, "test", "-f", "cache/recovery-ready-" + key, check=False).returncode == 0)
            done = command(target, "status", key)
            assert done["answer"] == "Recovered answer" and done["status"] == "done"
            assert command(target, "status", interrupted)["status"] == "running"
            assert run_counts() == [1, 1]
            before = shell("pidof", package).stdout.strip()
            shell("am", "force-stop", package)
            test.wait(timeout=20)
            assert not shell("pidof", package, check=False).stdout.strip()
            shell("am", "start", "-W", "-n", package + "/.ui.MainActivity")

            def listener_back():
                p = subprocess.run(cli + target + ["doctor"], capture_output=True, timeout=18)
                return p.returncode == 0

            until(listener_back)
            after = shell("pidof", package).stdout.strip()
            assert after and before != after
            assert command(target, "status", key) == done
            assert command(target, "run", "--goal", goal, "--request-id", key) == done
            state = command(target, "wait", interrupted, "--timeout", "1", expected=1)
            assert state["status"] == "failed" and state["failure_kind"] == "interrupted"
            retry = command(target, "run", "--goal", other, "--conversation", done["conversation"],
                            "--request-id", interrupted, expected=1)
            assert retry["task_id"] == interrupted and retry["failure_kind"] == "interrupted"
            assert run_counts() == [1, 1]
            print(f"Phone PIDs {before} -> {after}. Dropped one accepted receipt. Recovered the exact answer and an interrupted run.")
            print("Android run ledger after concurrent submissions and post-restart retries: one execution of each task.")
        finally:
            if test.poll() is None:
                shell("am", "force-stop", package)
                test.wait(timeout=20)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    run(parser.parse_args().device)
