"""A loopback-only browser desk for one Mika phone, using the existing CLI transport."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.resources import files
import json
import os
import re
import secrets
import sys
import threading
from urllib.parse import unquote
import webbrowser

from mika_cli import (Adb, CliError, MAX_ATTACH_BYTES, MAX_ATTACH_FILES, connection, device_state_error,
                      discard_upload, execute, file_chunks, recent_tasks, redact, require_files, result_file, upload_file)


ASSETS = {
    "/": ("index.html", "text/html"),
    "/desk.css": ("desk.css", "text/css"),
    "/desk.js": ("desk.js", "text/javascript"),
    "/notifications.js": ("notifications.js", "text/javascript"),
    # 3× header rendition of Android's drawable-nodpi/mika_launcher_art.png.
    "/mika-logo.png": ("mika-logo.png", "image/png"),
}
WATCH_SECONDS = 4
FIELDS = {"overview": set(), "watch": {"task_id"}, "select": {"device"}, "run": {"goal"}, "status": {"task_id"}, "cancel": {"task_id"},
          "answer": {"task_id", "text"}, "steer": {"task_id", "text"},
          "download": {"task_id", "file_id"}, "discard_upload": {"upload_id"},
          "history_list": {"offset"}, "history_turns": {"conversation", "offset"},
          "history_run": {"conversation", "run_id", "offset"},
          "history_body": {"conversation", "run_id", "span_id", "offset"}}


def command(data):
    if not isinstance(data, dict):
        raise ValueError
    action = data.get("action")
    if not isinstance(action, str) or action not in FIELDS:
        raise ValueError
    expected = FIELDS[action] | {"action"}
    if action == "run" and "conversation" in data:
        expected.add("conversation")
    if action == "run" and "request_id" in data:
        expected.add("request_id")
    if action == "run" and "attachments" in data:
        expected.add("attachments")
        ids = data["attachments"]
        if not isinstance(ids, list) or not 1 <= len(ids) <= MAX_ATTACH_FILES or not all(valid_id(item) for item in ids):
            raise ValueError
    if not all(isinstance(value, str) for key, value in data.items() if key != "attachments"):
        raise ValueError
    if data.keys() != expected:
        raise ValueError
    if any(not valid_id(data[key]) for key in ("task_id", "file_id", "upload_id") if key in data):
        raise ValueError
    if action.startswith("history_"):
        for field in ("conversation", "run_id", "span_id"):
            if field in data and not valid_id(data[field]):
                raise ValueError
        if not re.fullmatch(r"[0-9]{1,9}", data["offset"]):
            raise ValueError
    return action


def valid_id(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value) is not None


class Desk(ThreadingHTTPServer):
    def __init__(self, args):
        # A fresh loopback port each run; the session secret only travels in the
        # printed link's fragment, which browsers never send to the server.
        super().__init__(("127.0.0.1", 0), Handler)
        self.args = args
        if not args.url and not args.device:
            args.device = os.environ.get("ANDROID_SERIAL")
        self.lock = threading.Lock()
        self.key = secrets.token_urlsafe(32)
        self.host = f"127.0.0.1:{self.server_port}"
        self.origin = "http://" + self.host

    def list_devices(self):
        # Caller holds the lock. Adb starts the server at most once per process.
        adb = Adb(self.args.adb)
        adb.start_server()
        return adb.devices()

    def pin_target(self, choice):
        # A session owns one target even across disconnects and concurrent tabs.
        with self.lock:
            if self.args.url or self.args.device:
                if choice is not None and choice != self.args.device:
                    raise CliError("device_pinned", "The desk already has a target.", http_status=409)
                return
            devices = self.list_devices()
            online = [d["device"] for d in devices if d["state"] == "device"]
            if choice is None and len(online) == 1:
                choice = online[0]
            elif choice is None and len(devices) == 1:
                # The only phone is not ready; name its state as the CLI does.
                raise device_state_error(devices[0]["state"])
            if choice not in online:
                raise CliError("device_selection", "Choose an online phone.", devices=online, http_status=409)
            self.args.device = choice

    def dispatch(self, action, data, sensitive):
        self.pin_target(data.get("device"))
        if action == "discard_upload":
            with connection(self.args, sensitive) as (client, _):
                discard_upload(client, data["upload_id"])
                return {"discarded": True}
        if action.startswith("history_"):
            path = "/history/conversations"
            if "conversation" in data:
                path += "/" + data["conversation"]
            if "run_id" in data:
                path += "/runs/" + data["run_id"]
            if "span_id" in data:
                path += "/bodies/" + data["span_id"]
            with connection(self.args, sensitive) as (client, _):
                client.discover()
                return client.request("GET", path + "?offset=" + data["offset"])
        if action in {"overview", "select", "watch"}:
            with connection(self.args, sensitive) as (client, transport):
                info = client.discover()
                if action == "watch":
                    # A state change wakes the view early. At the deadline the same
                    # overview also picks up progress text and other remote tasks.
                    try:
                        client.request("POST", f"/tasks/{data['task_id']}/wait", {"timeout_s": WATCH_SECONDS},
                                       timeout=max(client.timeout, WATCH_SECONDS + 5))
                    except CliError as exc:
                        if exc.details.get("http_status") not in (404, 410):
                            raise
                        # The phone may have restarted and lost this task ID.
                tasks = recent_tasks(client, 50)["tasks"]
                device = {}
                if "device_state" in info["capabilities"]:
                    try:
                        device = client.request("GET", "/device").get("device") or {}
                    except CliError:
                        # The optional screen/helper status can be unavailable on a
                        # phone that just returned a valid list of tasks.
                        pass
                history = {}
                if info.get("history_enabled"):
                    try:
                        history = client.request("GET", "/history/conversations")
                    except CliError as exc:
                        if exc.code != "history_disabled":
                            raise
                        info["history_enabled"] = False
                return {"target": transport.get("device") or self.args.url,
                        "transport": transport["transport"], "server_version": info.get("server_version"),
                        "capabilities": info["capabilities"], "device": device, "tasks": tasks,
                        "history_enabled": info.get("history_enabled", False), "history": history}

        args = argparse.Namespace(**{**vars(self.args), "conversation": None, "request_id": None, **data, "command": "task",
                                     "goal_file": None, "text_file": None})
        return execute(args, sensitive)


class Handler(BaseHTTPRequestHandler):
    timeout = 10

    def log_message(self, *args):
        pass  # A desk's local session and phone activity do not belong in logs.

    def send_headers(self, status, size, mime):
        self.send_response(status)
        if mime.startswith("text/") or mime == "application/json":
            mime += "; charset=utf-8"
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                         "img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()

    def send(self, status, content, mime="text/plain"):
        self.send_headers(status, len(content), mime)
        try:
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Closing the page does not undo or repeat a phone operation.

    def do_GET(self):
        if self.headers.get("Host") != self.server.host:
            return self.send(403, b"Invalid host")
        asset = ASSETS.get(self.path)
        if not asset:
            return self.send(404, b"Not found")
        name, mime = asset
        self.send(200, files(__package__).joinpath(name).read_bytes(), mime)

    def do_POST(self):
        if self.headers.get("Host") != self.server.host or self.headers.get("Origin") != self.server.origin:
            return self.send(403, b"Invalid source")
        header = self.headers.get("X-Mika-Desk", "")
        if not secrets.compare_digest(header.encode(), self.server.key.encode()):
            return self.send(403, b"Invalid desk session")
        if self.path == "/desk/upload":
            return self.receive_file()
        if self.path != "/desk":
            return self.send(404, b"Not found")
        if self.headers.get_content_type() != "application/json":
            return self.send(415, b"Expected JSON")
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 64 * 1024:
                return self.send(413, b"Invalid body size")
            data = json.loads(self.rfile.read(size))
            action = command(data)
            for value in (v for v in data.values() if isinstance(v, str)):
                value.encode("utf-8")  # Reject unpaired surrogates before contacting the phone.
        except (ValueError, RecursionError):
            return self.send(400, b"Invalid desk command")

        sensitive = []
        try:
            if action == "download":
                return self.download(data, sensitive)
            result = self.server.dispatch(action, data, sensitive)
            status = 200
        except CliError as exc:
            return self.send_error_json(exc, sensitive)
        self.send(status, json.dumps(redact(result, sensitive)).encode(), "application/json")

    def send_error_json(self, exc, sensitive):
        status = exc.details.get("http_status", 502)
        result = {"error": {"code": exc.code, "message": str(exc), **exc.details}}
        self.send(status if 400 <= status <= 599 else 502,
                  json.dumps(redact(result, sensitive)).encode(), "application/json")

    def receive_file(self):
        if self.headers.get_content_type() != "application/octet-stream":
            return self.send(415, b"Expected a file")
        try:
            size = int(self.headers["Content-Length"])
            name = unquote(self.headers["X-Mika-Filename"], errors="strict")
            if not name or len(name) > 255:
                raise ValueError
        except (KeyError, ValueError, TypeError):
            return self.send(400, b"Invalid file headers")
        if not 0 <= size <= MAX_ATTACH_BYTES:
            return self.send(413, b"File too large")
        sensitive = []
        try:
            self.server.pin_target(None)
            with connection(self.server.args, sensitive) as (client, _):
                require_files(client.discover())
                upload_id = upload_file(client, name, size, self.rfile)
        except CliError as exc:
            return self.send_error_json(exc, sensitive)
        except OSError:
            return self.send(400, b"File transfer interrupted")
        self.send(201, json.dumps({"upload_id": upload_id}).encode(), "application/json")

    def download(self, data, sensitive):
        self.server.pin_target(None)
        with connection(self.server.args, sensitive) as (client, _):
            require_files(client.discover())
            item = result_file(client, data["task_id"], data["file_id"])
            chunks = file_chunks(client, data["task_id"], item)
            first = next(chunks)  # Surface missing files, including empty ones, before sending headers.
            self.send_headers(200, item["size"], "application/octet-stream")
            try:
                self.wfile.write(first)
                for chunk in chunks:
                    self.wfile.write(chunk)
            except (CliError, OSError):
                # The short response must fail the browser's Content-Length check.
                self.close_connection = True


def serve(args):
    try:
        server = Desk(args)
    except OSError as exc:
        print(f"Could not start the local Mika desk: {exc}", file=sys.stderr)
        return 2
    with server:
        link = server.origin + "/#" + server.key
        if args.no_open or not webbrowser.open(link):
            print(link, flush=True)
        print("Mika task desk is open locally. Press Ctrl+C to close it.", file=sys.stderr, flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0
