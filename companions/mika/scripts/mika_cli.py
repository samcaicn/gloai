#!/usr/bin/env python3
"""Mika's task API client. Standard library only; usable outside the source tree."""

import argparse
import base64
import binascii
import contextlib
import errno
import http.client
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

VERSION = "0.1.0"
API = "/api/v1"
DEFAULT_PACKAGE = "com.wangzan101.mika"
MAX_RESPONSE = 4 * 1024 * 1024
FILE_CHUNK = 128 * 1024
MAX_ATTACH_BYTES = 200 * 1024 * 1024
MAX_ATTACH_FILES = 9
HANDOFF = {"waiting_for_answer", "waiting_on_phone"}
TERMINAL = {"done", "failed", "cancelled"}
STATUSES = TERMINAL | HANDOFF | {"starting", "queued", "running", "paused"}
RETRY_HINT = "Unlock the phone, open Mika once, then retry connect."


class CliError(Exception):
    def __init__(self, code, message, exit_code=2, **details):
        super().__init__(message)
        self.code, self.exit_code, self.details = code, exit_code, details


class RawExport(str):
    """Export body already formatted for stdout. Do not JSON-encode it again."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("arguments", message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def clean_token(value):
    if not isinstance(value, str) or not value or not re.fullmatch(r"[A-Za-z0-9._~+/=-]+", value):
        raise CliError("credentials", "The connection token is missing or invalid; reconnect to Mika.")
    return value


def redact(value, secrets):
    if isinstance(value, str):
        for secret in secrets:
            value = value.replace(secret, "[redacted]")
        return value
    if isinstance(value, dict):
        return {k: redact(v, secrets) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, secrets) for v in value]
    return value


class Client:
    def __init__(self, base, token, timeout=15):
        self.base, self.token, self.timeout = base, token, timeout
        # Local/private phone traffic should not be sent through an ambient HTTP proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path="", body=None, *, mutation=False, timeout=None, task_id=None,
                accept="application/json", expect="application/json", max_bytes=MAX_RESPONSE):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": accept, "Content-Type": "application/json",
            "X-Mika-Client": "mika-cli/" + VERSION,
        })
        try:
            with self.opener.open(req, timeout=timeout or self.timeout) as response:
                raw = response.read(max_bytes + 1)
                if len(raw) > max_bytes:
                    if "/export" in path:
                        raise CliError("export_too_large", "The run record is larger than the CLI can read.")
                    raise ValueError("invalid response")
                if response.headers.get_content_type() != expect:
                    raise ValueError("invalid response")
                if expect != "application/json":
                    return raw.decode("utf-8")
                result = json.loads(raw)
                if not isinstance(result, dict) or "error" in result:
                    raise ValueError("invalid response")
                return result
        except urllib.error.HTTPError as exc:
            # Any server-side failure after a write may have happened after acceptance.
            if mutation and (exc.code >= 500 or 300 <= exc.code < 400):
                exc.close()
                self.unknown(task_id)
            message, code = "The phone rejected the request (HTTP %s)." % exc.code, "http_error"
            try:
                payload = json.loads(exc.read(MAX_RESPONSE))
                error = payload.get("error", {})
                if isinstance(error, dict) and isinstance(error.get("message"), str):
                    message = error["message"]
                if isinstance(error, dict) and isinstance(error.get("code"), str):
                    code = error["code"]
            except (ValueError, OSError, AttributeError):
                pass
            finally:
                exc.close()
            raise CliError(code, message, 1, http_status=exc.code) from None
        except (urllib.error.URLError, OSError, http.client.HTTPException, ValueError):
            if mutation:
                self.unknown(task_id)
            raise CliError("connection", "Could not read a valid response from Mika; check the connection and run mika connect.") from None
        except KeyboardInterrupt:
            if mutation:
                self.unknown(task_id)
            raise

    @staticmethod
    def unknown(task_id=None):
        details = {"task_id": task_id} if task_id else {}
        raise CliError("outcome_unknown", "The phone may have accepted the operation, but its response was not received. "
                       "It was not retried. Check task status or task list before submitting again.", 4, **details)

    def discover(self):
        try:
            info = self.request("GET")
        except CliError as exc:
            if exc.details.get("http_status") == 404:
                raise CliError("incompatible_api", "This Mika APK does not provide task API v1. Install a build with CLI support; no MCP fallback is used.") from None
            raise
        capabilities = info.get("capabilities")
        if (type(info.get("api_version")) is not int or info["api_version"] != 1
                or not isinstance(capabilities, list) or "tasks" not in capabilities):
            raise CliError("incompatible_api", "The phone does not advertise a compatible Mika task API v1.")
        return info


def adb_unavailable():
    return CliError("adb_unavailable", "adb was not found or cannot be run. Install Android platform-tools or provide a runnable --adb path.")


def device_state_error(state):
    if state == "unauthorized":
        message = "The phone has not authorized this computer. Unlock the phone, accept the USB debugging prompt, then retry."
    elif state == "offline":
        message = "The phone is offline. Reconnect the cable or re-enable USB debugging on the phone, then retry."
    else:
        message = "The selected phone is not online; the CLI will not switch devices."
    return CliError("device_offline", message, state=state)


class Adb:
    # adb executables whose server this process has already started; one start per process is enough.
    _started = set()
    _start_lock = threading.Lock()

    def __init__(self, executable="adb", device=None):
        self.executable, self.device = executable, device

    def run(self, *args, timeout=20):
        prefix = [self.executable] + (["-s", self.device] if self.device else [])
        try:
            result = subprocess.run(prefix + list(args), stdin=subprocess.DEVNULL, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
        except OSError:
            raise adb_unavailable() from None
        except (subprocess.TimeoutExpired, UnicodeError):
            raise CliError("adb", "ADB did not complete; check the selected phone's connection.") from None
        if result.returncode:
            # The official bootstrap script may print a token, including on failure.
            raise CliError("adb", "ADB could not complete the operation. Check the selected device; open Mika and run mika connect.")
        return result.stdout.strip()

    def start_server(self):
        # Once per process and executable. Output is discarded so a daemon started here never holds the pipes
        # of a later captured call.
        with Adb._start_lock:
            if self.executable in Adb._started:
                return
            self._start_server()
            Adb._started.add(self.executable)

    def _start_server(self):
        try:
            result = subprocess.run([self.executable, "start-server"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=60)
        except OSError:
            raise adb_unavailable() from None
        except subprocess.TimeoutExpired:
            raise CliError("adb", "ADB server did not start within 60 seconds; close other ADB tools and retry.") from None
        if result.returncode:
            raise CliError("adb", "ADB server could not start; check the Android platform-tools install and retry.")

    def devices(self):
        devices = []
        for line in self.run("devices").splitlines():
            # ADB uses a tab after the serial. Wireless service names can contain spaces.
            columns = line.split("\t", 1) if "\t" in line else line.split(maxsplit=1)
            if len(columns) == 2 and columns[0] != "List" and columns[1].strip():
                devices.append({"device": columns[0], "state": columns[1].split()[0]})
        return devices

    def select(self):
        devices = self.devices()
        if not self.device:
            online = [d["device"] for d in devices if d["state"] == "device"]
            if len(online) == 1:
                self.device = online[0]
            elif len(devices) == 1:
                # The only phone is not ready; report its state rather than a selection problem.
                self.device = devices[0]["device"]
            else:
                raise CliError("device_selection", "Expected one online phone. Run mika devices and choose --device SERIAL.", devices=devices)
        listed = next((d["state"] for d in devices if d["device"] == self.device), None)
        if listed not in {None, "device"}:
            raise device_state_error(listed)
        try:
            state = self.run("get-state")
        except CliError as exc:
            if exc.code != "adb":
                raise
            state = None
        if state != "device":
            raise device_state_error(state or listed or "unknown")


def base_url(raw):
    try:
        url = urllib.parse.urlsplit(raw)
        valid = (not any(ord(c) < 32 for c in raw) and url.scheme in {"http", "https"} and url.hostname and url.port != 0
                 and url.username is None and url.password is None and not url.query and not url.fragment
                 and url.path.rstrip("/") in {"", API})
    except ValueError:
        valid = False
    if not valid:
        raise CliError("arguments", "--url must be an HTTP(S) origin or /api/v1 URL, without credentials, query or fragment.")
    return urllib.parse.urlunsplit((url.scheme, url.netloc, API, "", ""))


def progress(args, message):
    # Progress is for a person watching; stdout stays machine-readable JSON.
    if getattr(args, "progress", False):
        print("mika: " + message, file=sys.stderr, flush=True)


@contextlib.contextmanager
def connection(args, secrets):
    if args.url:
        if args.device or not args.token_file:
            raise CliError("arguments", "Use --url with --token-file, without --device.")
        base = base_url(args.url)
        try:
            token = clean_token(Path(args.token_file).read_text(encoding="utf-8").strip())
        except (OSError, UnicodeError):
            raise CliError("credentials", "Could not read the token file.") from None
        secrets.append(token)
        yield Client(base, token, args.request_timeout), {"transport": "http"}
        return
    if args.token_file:
        raise CliError("arguments", "--token-file requires --url; ADB reads the current token from the phone.")
    adb = Adb(args.adb, args.device or os.environ.get("ANDROID_SERIAL"))
    package = getattr(args, "mika_package", DEFAULT_PACKAGE)
    phone_files = f"/sdcard/Android/data/{package}/files"
    progress(args, "starting ADB server...")
    adb.start_server()
    adb.select()
    if args.command == "connect":
        progress(args, "running phone bootstrap...")
        try:
            adb.run("shell", "sh", phone_files + "/connect-computer.sh", timeout=60)
        except CliError as exc:
            if exc.code != "adb":
                raise
            raise CliError("adb", "Mika's phone bootstrap did not complete. " + RETRY_HINT) from None
    try:
        info = json.loads(adb.run("shell", "cat", phone_files + "/mcp-usb.json"))
        if not isinstance(info, dict):
            raise ValueError()
    except ValueError:
        raise CliError("connection_info", "Mika's connection information is invalid. " + RETRY_HINT) from None
    except CliError as exc:
        if exc.code != "adb":
            raise
        raise CliError("connection_info", "Mika's connection information could not be read. " + RETRY_HINT) from None
    token = clean_token(info.get("token"))
    secrets.append(token)
    boot = adb.run("shell", "settings", "get", "global", "boot_count")
    if not boot.isdigit() or info.get("boot") != boot:
        raise CliError("stale_connection", "The phone has rebooted; run mika connect to obtain current connection information.")
    if type(info.get("api_version")) is not int or info["api_version"] != 1:
        raise CliError("incompatible_api", "This Mika APK does not advertise task API v1; install a build with CLI support.")
    port = info.get("port")
    if type(port) is not int or not 1 <= port <= 65535:
        raise CliError("connection_info", "Mika's advertised port is invalid.")
    local = adb.run("forward", "tcp:0", "tcp:" + str(port))
    if not local.isascii() or not local.isdigit() or not 1 <= int(local) <= 65535:
        raise CliError("adb", "ADB did not return a valid temporary forwarding port.")
    try:
        yield Client("http://127.0.0.1:" + local + API, token, args.request_timeout), {"transport": "adb", "device": adb.device}
    finally:
        # Never remove all forwards or a mapping created by another invocation.
        try:
            adb.run("forward", "--remove", "tcp:" + local, timeout=5)
        except CliError:
            print("mika: could not confirm removal of the temporary ADB forward on port " + local + ".", file=sys.stderr)


def text_input(text, filename, label):
    if filename is not None:
        try:
            text = sys.stdin.read() if filename == "-" else Path(filename).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            raise CliError("input", "Could not read the " + label + " file as UTF-8.") from None
    if not text or not text.strip():
        raise CliError("input", label + " must not be empty.")
    return text


def task_snapshot(result, client, *, mutation=False, task_id=None):
    if (not isinstance(result.get("task_id"), str) or not result["task_id"]
            or not isinstance(result.get("status"), str) or result["status"] not in STATUSES):
        if mutation:
            client.unknown(task_id)
        raise CliError("protocol", "Mika returned an invalid task snapshot.")
    if task_id is not None and result["task_id"] != task_id:
        if mutation:
            client.unknown(task_id)
        raise CliError("protocol", "Mika returned a different task ID.")
    return result


def recent_tasks(client, limit):
    result = client.request("GET", "/tasks?limit=" + str(limit))
    if not isinstance(result.get("tasks"), list):
        raise CliError("protocol", "Mika returned an invalid task list.")
    for task in result["tasks"]:
        if not isinstance(task, dict):
            raise CliError("protocol", "Mika returned an invalid task list.")
        task_snapshot(task, client)
    return result


def incompatible(info, capability, remedy):
    """Fail a capability-gated action with the phone version, the missing capability and a remedy."""
    version = info.get("server_version")
    if not isinstance(version, str) or not version:
        version = None
    details = {"capability": capability}
    if version:
        details["server_version"] = version
    raise CliError("incompatible_api",
                   "The Mika app on the phone%s does not advertise '%s'. %s"
                   % ((" (version %s)" % version) if version else "", capability, remedy), **details)


def require_files(info):
    if "task_files" not in info["capabilities"]:
        incompatible(info, "task_files", "Update Mika on the phone to attach and download files.")


def discard_upload(client, upload_id):
    with contextlib.suppress(CliError):
        client.request("POST", "/uploads/" + upload_id + "/discard", {})


def upload_file(client, name, size, stream):
    if not 0 <= size <= MAX_ATTACH_BYTES:
        raise CliError("input", "Attachments must fit within 200 MiB.")
    upload_id = client.request("POST", "/uploads", {"name": name, "size": size}).get("upload_id")
    if not isinstance(upload_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", upload_id):
        raise CliError("protocol", "Invalid upload receipt.")
    try:
        offset = 0
        while offset < size:
            chunk = stream.read(min(FILE_CHUNK, size - offset))
            if not chunk:
                raise CliError("input", "The source file ended before the upload was complete.")
            reply = client.request("POST", "/uploads/" + upload_id, {
                "offset": offset, "data": base64.b64encode(chunk).decode("ascii")})
            offset += len(chunk)
            if reply.get("next_offset") != offset:
                raise CliError("protocol", "The phone did not confirm this attachment chunk.")
        return upload_id
    except BaseException:
        discard_upload(client, upload_id)
        raise


def result_file(client, task_id, file_id):
    result = task_snapshot(client.request("GET", "/tasks/" + task_id), client, task_id=task_id)
    for item in result.get("files", []):
        if isinstance(item, dict) and item.get("id") == file_id:
            if type(item.get("size")) is not int or not 0 <= item["size"] <= 100 * 1024 * 1024:
                raise CliError("protocol", "Invalid result file size.")
            return item
    raise CliError("file_not_found", "This file is not available for the selected task.", 1, http_status=404)


def file_chunks(client, task_id, item):
    digest = hashlib.sha256()
    offset = 0
    while True:
        part = client.request("GET", "/tasks/%s/files/%s?offset=%d" % (task_id, item["id"], offset))
        try:
            data = base64.b64decode(part["data"], validate=True)
        except (KeyError, TypeError, ValueError, binascii.Error):
            raise CliError("protocol", "Invalid file data from the phone.") from None
        remaining = item["size"] - offset
        if part.get("offset") != offset or len(data) != min(FILE_CHUNK, remaining):
            raise CliError("file_changed", "The result file is incomplete or changed on the phone.")
        digest.update(data)
        if len(data) == remaining and item.get("sha256") is not None and digest.hexdigest() != item["sha256"]:
            raise CliError("file_changed", "The phone file no longer matches what the task exported.")
        yield data
        offset += len(data)
        if offset == item["size"]:
            return


def submit_task(client, goal, conversation, upload_ids, request_id):
    body = {"goal": goal}
    if conversation is not None:
        body["conversation"] = conversation
    if upload_ids:
        body["attachments"] = upload_ids
    if request_id is not None:
        body["request_id"] = request_id
    result = client.request("POST", "/tasks", body, mutation=True, task_id=request_id)
    return task_snapshot(result, client, mutation=True, task_id=request_id)


def run_with_files(client, args, info):
    request_id = args.request_id
    if "task_recovery" in info["capabilities"]:
        request_id = request_id or str(uuid.uuid4())
    elif request_id is not None:
        incompatible(info, "task_recovery",
                     "Rerun without --request-id to submit anyway, or update Mika on the phone to keep requests recoverable.")
    ids = list(getattr(args, "attachments", []))  # The local desk has already staged these.
    paths = [Path(name) for name in getattr(args, "attach", [])]
    if ids or paths:
        require_files(info)
    try:
        if paths:
            if len(paths) > MAX_ATTACH_FILES or any(not p.is_file() for p in paths) or sum(p.stat().st_size for p in paths) > MAX_ATTACH_BYTES:
                raise CliError("input", "Choose up to 9 regular files totalling at most 200 MiB.")
            for path in paths:
                with path.open("rb") as stream:
                    ids.append(upload_file(client, path.name, os.fstat(stream.fileno()).st_size, stream))
        task = submit_task(client, args.goal, args.conversation, ids, request_id)
        ids.clear()  # The phone consumed these in the accepted submission.
        return task
    except CliError as exc:
        if exc.code == "outcome_unknown":
            ids.clear()  # Submission may still be consuming them. Unused uploads expire.
        raise
    except OSError:
        raise CliError("input", "Could not read an attachment on this computer.") from None
    finally:
        # Browser-staged uploads belong to the browser. Clean up files staged by this CLI invocation.
        if paths:
            for upload_id in ids:
                discard_upload(client, upload_id)


def publish_download(staged, target):
    # Both paths are on the same filesystem. Publishing must fail if another writer won the name.
    if os.name == "nt":
        os.rename(staged, target)  # Windows rename never replaces an existing name.
        return
    try:
        os.link(staged, target)
    except OSError as exc:
        if exc.errno not in {errno.EPERM, errno.ENOTSUP, errno.ENOSYS}:
            raise
        # exFAT and some network shares have no hard links. Only create a destination
        # after verification, with exclusive creation even if it appeared mid-transfer.
        with staged.open("rb") as source, target.open("xb") as output:
            try:
                shutil.copyfileobj(source, output, length=1024 * 1024)
                output.flush()
            except OSError:
                raise CliError("output", "The verified file could not be fully written to this volume. Check the output file.") from None


def save_result(client, task_id, file_id, output):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", file_id):
        raise CliError("arguments", "Use a file ID returned by task status.")
    item = result_file(client, task_id, file_id)
    target = Path(output)
    try:
        if os.path.lexists(target):
            raise FileExistsError
        with tempfile.TemporaryDirectory(prefix=".mika-download-", dir=target.parent) as directory:
            staged = Path(directory) / "file"
            with staged.open("xb") as stream:
                for chunk in file_chunks(client, task_id, item):
                    stream.write(chunk)
            publish_download(staged, target)
    except FileExistsError:
        raise CliError("output", "The output file already exists. Choose a new file name.") from None
    except OSError as exc:
        raise CliError("output", "Could not save the result file: " + str(exc.strerror or exc)) from None
    return {"file": str(target), "size": item["size"], "task_id": task_id}


def execute(args, secrets):
    # Validate inputs before opening a connection or invoking the bootstrap script.
    if args.command == "notification-doctor":
        if args.url or args.token_file:
            raise CliError("arguments", "notification-doctor requires ADB; it does not use HTTP credentials.")
        try:
            from mika_notifications import inspect_notifications, validate
        except ModuleNotFoundError:
            sys.path.append(str(Path(__file__).resolve().parent))
            from mika_notifications import inspect_notifications, validate
        try:
            validate(args.package, args.dependency, args.listener, args.user)
        except ValueError as error:
            raise CliError("arguments", str(error)) from None
        adb = Adb(args.adb, args.device or os.environ.get("ANDROID_SERIAL"))
        adb.start_server()
        adb.select()
        return inspect_notifications(adb, args.package, args.dependency, args.listener, args.user)
    if args.command == "task":
        if args.action == "run":
            if (getattr(args, "attach", None) or getattr(args, "attachments", None)) and not args.goal and args.goal_file is None:
                args.goal = ""
            else:
                args.goal = text_input(args.goal, args.goal_file, "goal")
            if args.request_id is not None:
                try:
                    if str(uuid.UUID(args.request_id)) != args.request_id:
                        raise ValueError
                except ValueError:
                    raise CliError("arguments", "request_id must be a canonical lowercase UUID.") from None
        if args.action in {"answer", "steer"}:
            args.text = text_input(args.text, args.text_file, args.action)
        if hasattr(args, "task_id") and not re.fullmatch(r"[A-Za-z0-9_-]+", args.task_id):
            name = "id" if args.action == "export" else "task_id"
            raise CliError("arguments", name + " must be an ID returned by Mika.")
    if args.command == "devices":
        if args.url or args.token_file:
            raise CliError("arguments", "devices lists ADB devices; it does not use --url or --token-file.")
        adb = Adb(args.adb)
        progress(args, "starting ADB server...")
        adb.start_server()
        return {"devices": adb.devices()}
    with connection(args, secrets) as (client, transport):
        progress(args, "contacting the phone...")
        info = client.discover()
        if args.command == "connect":
            return {"connected": True, **transport, "api_version": info["api_version"], "server_version": info.get("server_version")}
        if args.command == "doctor":
            device = client.request("GET", "/device") if "device_state" in info.get("capabilities", []) else {"status": "unsupported"}
            return {"connection": "ok", **transport, "api_version": 1, "device_state": device, "model_configuration": "unverified"}
        action = args.action
        if action == "steer" and "task_steer" not in info.get("capabilities", []):
            incompatible(info, "task_steer", "Update Mika on the phone to steer a running task.")
        path = "/tasks" + ("/" + args.task_id if hasattr(args, "task_id") else "")
        if action == "run":
            return run_with_files(client, args, info)
        if action == "download":
            require_files(info)
            return save_result(client, args.task_id, args.file_id, args.output)
        if action == "export":
            if "run_export" not in info.get("capabilities", []):
                incompatible(info, "run_export", "Update Mika on the phone to export runs.")
            return export_record(client, args.task_id, args.events)
        if action == "list":
            return recent_tasks(client, args.limit)
        if action in {"status", "wait"}:
            result = task_snapshot(client.request("GET", path), client, task_id=args.task_id)
            if action == "status":
                return result
            if result["status"] in HANDOFF | TERMINAL:
                return {**result, "changed": False}
            return task_snapshot(client.request("POST", path + "/wait", {"timeout_s": args.timeout},
                                               timeout=max(client.timeout, args.timeout + 5)), client, task_id=args.task_id)
        body = {"text": args.text} if action in {"answer", "steer"} else {}
        result = client.request("POST", path + "/" + action, body, mutation=True, task_id=args.task_id)
        if result.get("task_id") != args.task_id or type(result.get("accepted")) is not bool:
            client.unknown(args.task_id)
        if action == "steer" and (result["accepted"] is not True or
                                  not isinstance(result.get("message_id"), str) or not result["message_id"]):
            client.unknown(args.task_id)
        return result


def export_record(client, task_id, events):
    # A run record can be much larger than a task snapshot. Still bounded so a stuck read cannot grow forever.
    limit = 32 * 1024 * 1024
    if events:
        text = client.request("GET", "/tasks/" + task_id + "/export?events=jsonl",
                              accept="application/x-ndjson, application/json", expect="application/x-ndjson", max_bytes=limit)
        # Only LF separates records. Other line separators can occur inside JSON strings.
        lines = [line for line in text.split("\n") if line.strip(" \t\r")]
        if not lines:
            raise CliError("protocol", "Mika returned an invalid run export.")
        try:
            header = json.loads(lines[0])
            parsed = [json.loads(line) for line in lines[1:]]
        except ValueError:
            raise CliError("protocol", "Mika returned an invalid run export.") from None
        check_export(header, events=False)
        if "events" in header or any(not isinstance(event, dict) or not isinstance(event.get("type"), str) for event in parsed):
            raise CliError("protocol", "Mika returned an invalid run export.")
        return RawExport(text if text.endswith("\n") else text + "\n")
    result = client.request("GET", "/tasks/" + task_id + "/export", max_bytes=limit)
    check_export(result, events=True)
    return result


def check_export(result, events):
    required = ("exportFormat", "partial", "includesScreenshots", "task_id", "conversation_id", "run_id", "run")
    if not isinstance(result, dict) or any(key not in result for key in required):
        raise CliError("protocol", "Mika returned an invalid run export.")
    if (result["exportFormat"] != 1 or type(result["partial"]) is not bool or type(result["includesScreenshots"]) is not bool
            or result["includesScreenshots"] is not False):
        raise CliError("protocol", "Mika returned an invalid run export.")
    for key in ("task_id", "conversation_id", "run_id"):
        if result[key] is not None and not isinstance(result[key], str):
            raise CliError("protocol", "Mika returned an invalid run export.")
    if result["run"] is not None and not isinstance(result["run"], dict):
        raise CliError("protocol", "Mika returned an invalid run export.")
    if events and not isinstance(result.get("events"), list):
        raise CliError("protocol", "Mika returned an invalid run export.")


def bounded_int(low, high):
    def parse(value):
        try:
            number = int(value)
        except ValueError:
            raise argparse.ArgumentTypeError("must be an integer") from None
        if not low <= number <= high:
            raise argparse.ArgumentTypeError("must be between %s and %s" % (low, high))
        return number
    return parse


def request_timeout(value):
    try:
        number = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be a number") from None
    if not math.isfinite(number) or not 1 <= number <= 120:
        raise argparse.ArgumentTypeError("must be between 1 and 120 seconds")
    return number


def application_id(value):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", value):
        raise argparse.ArgumentTypeError("must be an Android application ID")
    return value


def parser():
    common = Parser(add_help=False)
    for flag, help_text in [
        ("--device", "ADB serial; required when more than one phone is online"),
        ("--url", "Phone HTTP origin or /api/v1 URL; bypasses ADB"),
        ("--token-file", "UTF-8 file containing the token for --url"),
        ("--adb", "ADB executable (default: adb)"),
    ]:
        common.add_argument(flag, help=help_text, default=argparse.SUPPRESS)
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS, help="Compact JSON output, including errors")
    common.add_argument("--request-timeout", type=request_timeout, default=argparse.SUPPRESS, help="HTTP timeout in seconds (default: 15; bounded waits get at least timeout + 5)")
    connection_options = Parser(add_help=False, parents=[common])
    connection_options.add_argument("--package", dest="mika_package", type=application_id, default=argparse.SUPPRESS,
                                    help=f"Mika application ID for ADB (default: {DEFAULT_PACKAGE})")
    p = Parser(prog="mika", description="Delegate tasks to Mika on Android using task API v1.",
               parents=[connection_options])
    p.add_argument("--version", action="version", version="mika " + VERSION)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("connect", help="Enable ADB computer access and verify task API", parents=[connection_options])
    sub.add_parser("doctor", help="Read connection and phone state without starting a task", parents=[connection_options])
    sub.add_parser("devices", help="List ADB devices", parents=[common])
    diagnostics = sub.add_parser("notification-doctor", parents=[common],
                                help="Inspect Android notification configuration without opening apps or reading message text")
    diagnostics.add_argument("--package", required=True, help="Android app whose notifications need investigation")
    diagnostics.add_argument("--dependency", action="append", default=[], help="Related package to check; repeat when needed")
    diagnostics.add_argument("--listener", help="Optional notification listener in package/class form")
    diagnostics.add_argument("--user", type=int, default=0, help="Android user/profile ID (default: 0)")
    ui = sub.add_parser("ui", help="Open the phone owner's local task desk", parents=[connection_options])
    ui.add_argument("--no-open", action="store_true", help="Print the desk URL instead of opening a browser")
    task = sub.add_parser("task", help="Run and inspect phone tasks", parents=[connection_options])
    actions = task.add_subparsers(dest="action", required=True)
    run = actions.add_parser("run", parents=[connection_options], help="Submit once; return the task ID immediately")
    goal = run.add_mutually_exclusive_group()
    goal.add_argument("--goal")
    goal.add_argument("--goal-file", help="UTF-8 file, or - for stdin")
    run.add_argument("--attach", action="append", default=[], metavar="FILE", help="Attach a local file (repeatable)")
    download = actions.add_parser("download", parents=[connection_options], help="Save one file exported by a task")
    download.add_argument("task_id")
    download.add_argument("file_id", help="ID in the task's files list")
    download.add_argument("--output", required=True, help="New file path on this computer")
    run.add_argument("--conversation", help="Continue a conversation returned by a previous remote task")
    run.add_argument("--request-id", help="A lowercase UUID chosen before submission. Query it as the task ID after a lost receipt.")
    for name in ["status", "wait", "answer", "steer", "cancel"]:
        cmd = actions.add_parser(name, parents=[connection_options])
        cmd.add_argument("task_id")
        if name == "wait":
            cmd.add_argument("--timeout", type=bounded_int(1, 50), default=30, help="One bounded wait, 1–50 seconds; never cancels the task")
        if name in {"answer", "steer"}:
            answer = cmd.add_mutually_exclusive_group(required=True)
            answer.add_argument("--text")
            answer.add_argument("--text-file", help="UTF-8 file, or - for stdin")
    listing = actions.add_parser("list", parents=[connection_options])
    listing.add_argument("--limit", type=bounded_int(1, 50), default=10)
    export = actions.add_parser("export", parents=[connection_options], help="Export a redacted run by task ID or run ID")
    export.add_argument("task_id", help="Task ID or run ID")
    export.add_argument("--events", action="store_true", help="Write the header followed by JSONL events")
    return p


def json_flag(argv):
    """Recognize exactly the long-option prefixes accepted for --json."""
    for token in argv:
        if token == "--":
            break
        name = token.split("=", 1)[0]
        if len(name) > 2 and name.startswith("--") and "--json".startswith(name):
            return True
    return False


def main(argv=None):
    # Pipes default to the locale code page on Windows; agents read stdout as UTF-8 and stdin is documented as UTF-8.
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    argv = list(sys.argv[1:] if argv is None else argv)
    secrets, code = [], 0
    compact = json_flag(argv)
    try:
        args = parser().parse_args(argv)
        for key, default in {"device": None, "url": None, "token_file": None, "adb": "adb", "request_timeout": 15}.items():
            if not hasattr(args, key):
                setattr(args, key, default)
        if hasattr(args, "mika_package"):
            if args.command in {"devices", "notification-doctor"}:
                raise CliError("arguments", "Put the Mika --package option on connect, doctor, task or ui.")
            if args.url:
                raise CliError("arguments", "The Mika --package option cannot be used with --url.")
        if args.command == "ui":
            if compact:
                raise CliError("arguments", "The desk is interactive. Use mika ui without --json.")
            if bool(args.url) != bool(args.token_file) or (args.url and args.device):
                raise CliError("arguments", "Choose one ADB device or use --url with --token-file.")
            if args.url:
                base_url(args.url)
            try:
                from mika_desk import serve
            except ModuleNotFoundError:
                # A copied Skill folder run as `python3 -I .../mika_cli.py` leaves this folder off sys.path.
                sys.path.append(str(Path(__file__).resolve().parent))
                from mika_desk import serve
            return serve(args)
        args.progress = args.command in {"devices", "connect", "doctor"}
        result = execute(args, secrets)
        if not isinstance(result, RawExport) and isinstance(result.get("status"), str) and result["status"] in {"failed", "cancelled"}:
            code = 1
    except CliError as exc:
        result = {"error": {"code": exc.code, "message": str(exc), **exc.details}}
        code = exc.exit_code
    except KeyboardInterrupt:
        result = {"error": {"code": "interrupted", "message": "Stopped waiting locally; no task cancellation was requested."}}
        code = 130
    if isinstance(result, RawExport):
        text = result
        for secret in secrets:
            text = text.replace(secret, "[redacted]")
        # Keep the phone's JSONL delimiters intact when stdout is redirected on Windows.
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(newline="\n")
        sys.stdout.write(text)
        return code
    redacted = redact(result, secrets)
    style = {"separators": (",", ":") if compact else None, "indent": None if compact else 2}
    try:
        output = json.dumps(redacted, ensure_ascii=False, **style)
        output.encode("utf-8")
    except UnicodeEncodeError:
        # A phone string cut through a surrogate pair has no UTF-8 form. It is still JSON, so escape
        # it instead of dying on an empty stdout: the caller always gets one document.
        output = json.dumps(redacted, **style)
    print(output, file=sys.stdout if compact or code == 0 else sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
