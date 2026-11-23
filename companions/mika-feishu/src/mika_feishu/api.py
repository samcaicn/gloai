"""Versioned public Mika API; no dependency on upstream Python internals."""
import ipaddress
import http.client
import json
import re
import socket
import time
import urllib.parse


class ApiError(Exception):
    def __init__(self, code, *, uncertain=False, status=None):
        super().__init__(code)
        self.code, self.uncertain, self.status = code, uncertain, status


def private_addresses(host, port):
    try:
        addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except (OSError, ValueError) as exc:
        raise ValueError("Cannot resolve the phone address; check the LAN or Tailscale address") from exc
    for entry in addresses:
        a = ipaddress.ip_address(entry[4][0].split("%")[0])
        if isinstance(a, ipaddress.IPv6Address) and a.ipv4_mapped:
            a = a.ipv4_mapped
        networks = ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                    "100.64.0.0/10", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10")
        if not any(a in ipaddress.ip_network(n) for n in networks):
            raise ValueError("Only loopback, LAN and private VPN/Tailscale addresses are allowed")
    return addresses


def validate_url(url):
    p = urllib.parse.urlsplit(url)
    if (p.scheme not in {"http", "https"} or not p.hostname or p.username or
            p.password or p.query or p.fragment or p.path.rstrip("/") not in {"", "/api/v1"}):
        raise ValueError("Use http://PHONE:PORT without credentials, query parameters or other paths")
    private_addresses(p.hostname, p.port or (443 if p.scheme == "https" else 80))
    return urllib.parse.urlunsplit((p.scheme, p.netloc, "/api/v1", "", ""))


class PrivateHTTPConnection(http.client.HTTPConnection):
    def connect(self):
        try:
            addresses = private_addresses(self.host, self.port)
        except ValueError:
            raise ApiError("invalid_private_address") from None
        error = OSError("No phone address available")
        for family, kind, protocol, _, address in addresses:
            sock = None
            try:
                sock = socket.socket(family, kind, protocol)
                sock.settimeout(self.timeout)
                sock.connect(address)
                self.sock = sock
                return
            except OSError as exc:
                error = exc
                if sock is not None:
                    sock.close()
        raise error


class PrivateHTTPSConnection(http.client.HTTPSConnection, PrivateHTTPConnection):
    # HTTPSConnection.connect calls super().connect(), which resolves against this instance's MRO
    # to PrivateHTTPConnection.connect, so the per-connect address check still runs; it then wraps
    # the checked socket with the original hostname for SNI and verification.
    pass


class MikaApi:
    def __init__(self, url, token, timeout=12):
        self.base = validate_url(url)
        self.target = urllib.parse.urlsplit(self.base)
        self.token = token
        self.timeout = timeout
        self.info = None
        self.checked_at = 0

    def request(self, method, path="", payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        headers = {
            "Authorization": "Bearer " + self.token, "Accept": "application/json",
            "Content-Type": "application/json", "User-Agent": "mika-feishu-connector/0.1"}
        writing = method == "POST" and not path.endswith("/wait")
        connect = PrivateHTTPSConnection if self.target.scheme == "https" else PrivateHTTPConnection
        conn = connect(self.target.hostname, self.target.port, timeout=self.timeout)
        try:
            conn.request(method, self.target.path + path, body=data, headers=headers)
            with conn.getresponse() as response:
                if not 200 <= response.status < 300:
                    # Reject redirects, and treat 5xx after a write as potentially executed.
                    raise ApiError("http_" + str(response.status), uncertain=writing and response.status >= 500,
                                   status=response.status)
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise ApiError("response_too_large", uncertain=writing)
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise ValueError()
                return result
        except (OSError, ValueError, http.client.HTTPException):
            raise ApiError("connection_or_response_error", uncertain=writing) from None
        finally:
            conn.close()

    def check(self, force=False):
        if not force and self.info and time.monotonic() - self.checked_at < 30:
            return self.info
        info = self.request("GET")
        if type(info.get("api_version")) is not int or info["api_version"] != 1:
            self.info = None
            raise ApiError("incompatible_api_version")
        if (not isinstance(info.get("capabilities"), list) or
                not all(isinstance(c, str) for c in info["capabilities"]) or
                not isinstance(info.get("limits", {}), dict)):
            self.info = None
            raise ApiError("incompatible_capability_response")
        if "tasks" not in info["capabilities"]:
            self.info = None
            raise ApiError("missing_tasks_capability")
        self.info, self.checked_at = info, time.monotonic()
        return info

    def task(self, task_id):
        self.check()
        result = self.request("GET", "/tasks/" + self.safe_id(task_id))
        if result.get("task_id") != task_id or not isinstance(result.get("status"), str):
            raise ApiError("incompatible_task_response")
        return result

    @staticmethod
    def safe_id(value):
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise ApiError("invalid_task_id")
        return value

    def start(self, goal, conversation=None):
        info = self.check(force=True)
        self.limit(info, "max_goal", goal, 4000)
        payload = {"goal": goal}
        if conversation:
            payload["conversation"] = conversation
        return self.request("POST", "/tasks", payload)

    def action(self, task_id, action, text=None):
        if action not in {"answer", "steer", "cancel"}:
            raise ApiError("unsupported_action")
        info = self.check(force=True)
        if action == "steer" and "task_steer" not in info.get("capabilities", []):
            raise ApiError("missing_task_steer_capability")
        if text is not None:
            self.limit(info, "max_" + action, text, 2000 if action == "answer" else 4000)
        return self.request("POST", "/tasks/" + self.safe_id(task_id) + "/" + action,
                            {} if text is None else {"text": text})

    @staticmethod
    def limit(info, name, text, fallback):
        maximum = info.get("limits", {}).get(name, fallback)
        if type(maximum) is not int or maximum < 1:
            raise ApiError("incompatible_limits")
        # Kotlin String.length counts UTF-16 units.
        if not text.strip() or len(text.encode("utf-16-le")) // 2 > min(maximum, fallback):
            raise ApiError("text_empty_or_too_long")
