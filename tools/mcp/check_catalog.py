#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Validate and (optionally) probe the bundled MCP service catalog.

Offline mode (default) re-checks the catalog against the same structural rules the app's parser
enforces — format version, unique IDs, transport/auth/field targets, template references and locale
completeness — and emits a JSON report. A preset is only ever listed as structurally "ok", never as a
reachable service.

Online mode (--online) additionally performs a secret-free MCP handshake against each remote
endpoint. A preset is marked "verified" only when the protocol handshake succeeds, and
"auth_required" only when it answers 401 with OAuth discovery the app can complete (resource
metadata + authorization server with dynamic client registration); any network failure is reported
as "unreachable" and never as verified. stdio presets are "unsupported" here
because they run locally and cannot be probed over the network.

The report is written to stdout (or --output). It never modifies the catalog or claims anything that
was not actually observed.

Usage:
  check_catalog.py CATALOG.json [--online [--require-online]] [--timeout SECONDS] [--output REPORT.json]

--require-online exits 1 unless every remote preset is "verified" or "auth_required".
"""

import argparse
import concurrent.futures
import json
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit

FORMAT_VERSION = 1
LOCALES = ["en", "zh", "zh-Hant", "ar", "fa", "ja", "ko", "es", "pt", "ru", "id", "tr"]

idPattern = re.compile(r"[a-z0-9][a-z0-9.-]*")
fieldIdPattern = re.compile(r"[A-Za-z][A-Za-z0-9_-]*")
envPattern = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
headerPattern = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+")
pinnedVersion = re.compile(r"[0-9][A-Za-z0-9.+-]*")
template = re.compile(r"\{\{([A-Za-z][A-Za-z0-9_-]*)\}\}")
reservedHeaders = {
    "authorization", "accept", "content-type", "content-length", "host",
    "mcp-session-id", "mcp-protocol-version",
}

INITIALIZE_BODY = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "mika-catalog-check", "version": "0.0.0"},
    },
})


def _https_url(value):
    try:
        u = urlsplit(value)
    except (ValueError, AttributeError):
        return False
    return bool(value and u.scheme == "https" and u.hostname)


def validate_preset(preset):
    """Return None when valid, else a reason string. Mirrors the app parser's validatePreset."""
    pid = preset.get("id", "")
    if not idPattern.fullmatch(pid):
        return "invalid id"
    if not preset.get("name", "").strip() or not preset.get("category", "").strip():
        return "blank name/category"
    summary = preset.get("summary") or {}
    if not summary or any(not str(v).strip() for v in summary.values()):
        return "missing summary"
    if preset.get("transport") not in ("streamable-http", "http-sse", "stdio"):
        return f"unsupported transport {preset.get('transport')!r}"
    if preset.get("auth") not in ("none", "oauth", "fields"):
        return f"unsupported auth {preset.get('auth')!r}"
    if not _https_url(preset.get("officialSource", "")):
        return "official source must be an https URL"

    stdio = preset.get("transport") == "stdio"
    endpoint = preset.get("endpoint", "")
    command = preset.get("command", [])
    if stdio:
        if endpoint or not command:
            return "stdio preset needs argv only"
        if any(not str(c).strip() or "\x00" in c for c in command):
            return "invalid stdio argv"
    else:
        if not endpoint.startswith("https://") or command:
            return "remote preset needs an official https endpoint"
        if preset.get("packageSpec") is not None:
            return "remote preset cannot declare a stdio package"

    spec = preset.get("packageSpec")
    if spec:
        if not stdio:
            return "package declared for non-stdio preset"
        if spec.get("ecosystem") not in ("npm", "pypi"):
            return "unsupported package ecosystem"
        if not spec.get("name", "").strip() or not pinnedVersion.fullmatch(spec.get("version", "")):
            return "package version must be exact"
        if not _https_url(spec.get("source", "")):
            return "package source must be an https URL"

    fields = preset.get("fields", [])
    fieldIds = set()
    for f in fields:
        fid = f.get("id", "")
        if not fieldIdPattern.fullmatch(fid) or fid in fieldIds:
            return f"invalid or duplicate field id {fid!r}"
        fieldIds.add(fid)
        label = f.get("label") or {}
        if not label or any(not str(v).strip() for v in label.values()):
            return "field label missing"
        target = f.get("target", "")
        if target not in ("bearer", "header", "env", "url", "argv"):
            return f"unsupported field target {target!r}"
        secret = bool(f.get("secret"))
        if secret:
            if target not in ("bearer", "header", "env"):
                return "secrets cannot enter url or argv"
            if preset.get("auth") != "fields":
                return "oauth/no-auth presets cannot require secret fields"
        name = f.get("name", "")
        if target == "bearer":
            if stdio or not secret or name:
                return "invalid bearer target"
        elif target == "header":
            if stdio or not headerPattern.fullmatch(name):
                return "invalid header target"
            if not secret and name.lower() in reservedHeaders:
                return "reserved header cannot be replaced"
            if secret and name.lower() == "authorization":
                return "use bearer for Authorization"
        elif target == "env":
            if not stdio or not envPattern.fullmatch(name):
                return "invalid environment target"
        elif target == "url":
            if stdio or secret or name:
                return "invalid url field"
        elif target == "argv":
            if not stdio or secret or name:
                return "invalid argv field"

    by_id = {f.get("id"): f for f in fields}
    for m in template.finditer(endpoint):
        ref = m.group(1)
        if ref not in by_id or by_id[ref].get("target") != "url" or not by_id[ref].get("required"):
            return f"url template uses the wrong field {ref!r}"
    for c in command:
        for m in template.finditer(c):
            ref = m.group(1)
            if ref not in by_id or by_id[ref].get("target") != "argv" or not by_id[ref].get("required"):
                return f"argv template uses the wrong field {ref!r}"
    for f in fields:
        if f.get("target") == "url" and not any(m.group(1) == f.get("id") for m in template.finditer(endpoint)):
            return f"url field {f.get('id')!r} is not referenced"
        if f.get("target") == "argv" and not any(
            m.group(1) == f.get("id") for c in command for m in template.finditer(c)
        ):
            return f"argv field {f.get('id')!r} is not referenced"

    for name, value in (preset.get("headers") or {}).items():
        if not headerPattern.fullmatch(name) or name.lower() in reservedHeaders:
            return "invalid fixed header"
        if not value.strip() or "\r" in value or "\n" in value or "{{" in value:
            return "fixed headers must be non-secret literals"
    return None


def validate_catalog(catalog):
    """Return a report dict; catalog is the decoded JSON object (may be None on a parse error)."""
    if not isinstance(catalog, dict):
        return {"ok": False, "error": "not a JSON object"}
    if catalog.get("formatVersion") != FORMAT_VERSION:
        return {"ok": False, "error": f"unsupported formatVersion {catalog.get('formatVersion')!r}"}
    presets = catalog.get("presets")
    if not isinstance(presets, list):
        return {"ok": False, "error": "presets is not a list"}

    entries = []
    seen = set()
    for p in presets:
        pid = p.get("id") if isinstance(p, dict) else None
        if not isinstance(p, dict):
            entries.append({"id": None, "status": "invalid", "reason": "not an object"})
            continue
        if pid in seen:
            entries.append({"id": pid, "status": "invalid", "reason": "duplicate id"})
            continue
        seen.add(pid)
        reason = validate_preset(p)
        if reason is None:
            missing = [loc for loc in LOCALES if loc not in (p.get("summary") or {})]
            field_missing = [loc for f in p.get("fields", []) for loc in LOCALES if loc not in (f.get("label") or {})]
            if missing or field_missing:
                entries.append({"id": pid, "status": "invalid",
                                "reason": f"missing locales summary={missing} fieldLabels={field_missing}"})
            else:
                entries.append({"id": pid, "status": "ok"})
        else:
            entries.append({"id": pid, "status": "invalid", "reason": reason})

    valid = sum(1 for e in entries if e["status"] == "ok")
    return {
        "ok": valid == len(entries),
        "formatVersion": FORMAT_VERSION,
        "total": len(entries),
        "valid": valid,
        "invalid": [e for e in entries if e["status"] == "invalid"],
    }


def classify_probe(kind, detail="", auth=None):
    """Map a raw probe outcome to a catalog status. Network failures are never 'verified'.

    'auth_required' means the endpoint is a live MCP server that answered 401 with OAuth resource
    metadata. For an oauth preset the app must also be able to finish sign-in on its own (an
    authorization server with dynamic client registration); a fields preset sends the user's token
    itself, so published resource metadata is enough.
    """
    if kind == "mcp_ok":
        return "verified"
    if kind == "oauth_ok":
        return "auth_required"
    if kind == "oauth_no_registration":
        return "auth_required" if auth == "fields" else "unsupported"
    if kind in ("not_mcp", "oauth_unusable", "templated"):
        return "unsupported"
    if kind in ("network_error", "http_error", "timeout"):
        return "unreachable"
    return "unsupported"


_resourceMetadataParam = re.compile(r'resource_metadata=(?:"([^"]+)"|([^,\s]+))')


def _get_json(url, timeout):
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "mika-catalog-check/0.0.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return None
            obj = json.loads(resp.read(65536).decode("utf-8", "replace"))
            return obj if isinstance(obj, dict) else None
    except Exception:  # noqa: BLE001 — any failure just means "not found here"
        return None


def _well_known(base, suffix):
    u = urlsplit(base)
    path = u.path.rstrip("/")
    return f"{u.scheme}://{u.netloc}/.well-known/{suffix}{path}"


def discover_oauth(endpoint, www_authenticate, timeout):
    """Follow the app's OAuth discovery (RFC 9728 -> RFC 8414 -> RFC 7591). Returns (kind, detail).

    Order mirrors McpOAuth.discover: origin resource metadata, path-inserted resource metadata, then
    the WWW-Authenticate resource_metadata hint; without resource metadata the endpoint origin is the
    authorization server (the app's fallback too). 'oauth_ok' requires a registration endpoint,
    because the app signs in as a dynamically registered public client and has no other way to obtain
    a client id; resource metadata without one is 'oauth_no_registration'.
    """
    u = urlsplit(endpoint)
    origin = f"{u.scheme}://{u.netloc}"
    candidates = [origin + "/.well-known/oauth-protected-resource"]
    if u.path not in ("", "/"):
        candidates.append(_well_known(endpoint, "oauth-protected-resource"))
    m = _resourceMetadataParam.search(www_authenticate or "")
    if m:
        candidates.append(m.group(1) or m.group(2))
    prm_url, prm = None, None
    for c in candidates:
        prm = _get_json(c, timeout)
        if prm and prm.get("authorization_servers"):
            prm_url = c
            break
        prm = None
    issuer = (prm.get("authorization_servers") or [None])[0] if prm else origin + "/"
    meta = None
    for c in (_well_known(issuer, "oauth-authorization-server"), _well_known(issuer, "openid-configuration"),
              issuer.rstrip("/") + "/.well-known/openid-configuration"):
        meta = _get_json(c, timeout)
        if meta and meta.get("authorization_endpoint") and meta.get("token_endpoint"):
            break
        meta = None
    via = f"resource metadata {prm_url}" if prm_url else "no resource metadata, origin authorization server"
    if meta is None:
        if prm_url:
            return "oauth_no_registration", f"http 401; {via}; issuer {issuer} metadata missing"
        return "oauth_unusable", f"http 401; {via}; no authorization server metadata"
    if not meta.get("registration_endpoint"):
        if prm_url is None:
            return "oauth_unusable", f"http 401; {via}; no dynamic client registration"
        return "oauth_no_registration", f"http 401; {via}; issuer {issuer} has no dynamic client registration"
    return "oauth_ok", f"http 401; {via}; issuer {issuer}"


def probe_http(endpoint, timeout, transport="streamable-http"):
    """Secret-free MCP probe. Streamable HTTP posts initialize; legacy SSE opens the event stream.

    Returns (kind, detail).
    """
    if "{{" in endpoint:
        return "templated", "endpoint needs user configuration"
    if transport == "http-sse":
        req = urllib.request.Request(endpoint, headers={
            "Accept": "text/event-stream", "User-Agent": "mika-catalog-check/0.0.0"}, method="GET")
    else:
        req = urllib.request.Request(
            endpoint,
            data=INITIALIZE_BODY.encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                "User-Agent": "mika-catalog-check/0.0.0",
            },
            method="POST",
        )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            ct = resp.headers.get("Content-Type", "")
            status = resp.status
            body = resp.readline() if transport == "http-sse" else resp.read(4096)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return discover_oauth(endpoint, e.headers.get("WWW-Authenticate", ""), timeout)
        # A live server answering non-MCP (e.g. REST 4xx/redirect) — not a reachable MCP endpoint.
        return "not_mcp", f"http {e.code}"
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", e)
        if isinstance(reason, TimeoutError) or "timed out" in str(reason).lower():
            return "timeout", str(reason)
        return "network_error", str(reason)
    except TimeoutError:
        return "timeout", "timed out"
    except Exception as e:  # noqa: BLE001 — never fail the whole run on one preset
        return "network_error", str(e)

    if status != 200 or not body:
        return "not_mcp", f"http {status}"
    if transport == "http-sse":
        return ("mcp_ok", "") if "event-stream" in ct else ("not_mcp", "not an event stream")

    # A successful initialize is JSON-RPC (application/json) or an SSE stream. If it echoes a
    # JSON-RPC result we have real MCP; otherwise we still got a live answer, so treat it as unsupported.
    text = body.decode("utf-8", "replace")
    try:
        obj = json.loads(text)
        if isinstance(obj, dict) and obj.get("jsonrpc") == "2.0":
            return "mcp_ok", ""
    except (ValueError, UnicodeDecodeError):
        pass
    if "event-stream" in ct or text.lstrip().startswith("event:"):
        return "mcp_ok", ""
    return "not_mcp", "not an MCP response"


def run(catalog_text, online=False, timeout=10):
    try:
        catalog = json.loads(catalog_text)
    except ValueError as e:
        return json.dumps({"ok": False, "error": f"invalid JSON: {e}"}, ensure_ascii=False, indent=1) + "\n"

    report = validate_catalog(catalog)
    if not online:
        return json.dumps(report, ensure_ascii=False, indent=1) + "\n"

    report["probed"] = True
    def one(p):
        pid = p.get("id")
        if p.get("transport") == "stdio":
            return {"id": pid, "status": "unsupported", "reason": "stdio runs locally"}
        kind, detail = probe_http(p.get("endpoint", ""), timeout, p.get("transport", "streamable-http"))
        return {"id": pid, "status": classify_probe(kind, detail, p.get("auth")), "reason": detail}

    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(one, catalog.get("presets", [])))
    report["online"] = results
    counts = {}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    report["onlineSummary"] = counts
    return json.dumps(report, ensure_ascii=False, indent=1) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("catalog")
    ap.add_argument("--online", action="store_true", help="also perform a secret-free MCP handshake per remote preset")
    ap.add_argument("--require-online", action="store_true",
                    help="exit 1 unless every remote preset is verified or auth_required")
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--output", help="write the report to this path instead of stdout")
    a = ap.parse_args(argv)

    with open(a.catalog, encoding="utf-8") as fh:
        text = fh.read()
    out = run(text, online=a.online, timeout=a.timeout)
    if a.output:
        with open(a.output, "w", encoding="utf-8") as fh:
            fh.write(out)
    else:
        sys.stdout.write(out)
    report = json.loads(out)
    if not report.get("ok", False):
        return 1
    if a.require_online and any(r["status"] not in ("verified", "auth_required", "unsupported")
                                or (r["status"] == "unsupported" and r.get("reason") != "stdio runs locally")
                                for r in report.get("online", [])):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
