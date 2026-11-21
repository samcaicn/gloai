#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Assemble the runtime that ships inside the APK: the Alpine minirootfs plus pinned packages, as one .tar.xz.

Nothing runs apk: packages are unpacked the way apk lays them out, and /lib/apk/db/installed is written
from their signed metadata, so apk inside the runtime sees a consistent database.

Usage:
  build-runtime.py --resolve   re-resolve the package closure from the signed v3.22 APKINDEX, rewrite runtime/sources.lock
  build-runtime.py             build from runtime/sources.lock; write the asset, BundledRuntime.kt and runtime/packages.json
  build-runtime.py --check     build into a temp dir and compare every committed artifact with it;
                               exit 1 on a mismatch or on a missing artifact

Every download is https only and verified: the minirootfs by SHA-256; each .apk by SHA-256 from the lock,
by its own RSA signature (keys from the pinned minirootfs), by its control checksum (the APKINDEX `C:` value),
and its data part by the `datahash` in its signed .PKGINFO. --resolve also checks the APKINDEX signature.
Needs python3 >= 3.9 and openssl (for RSA signature checks).
"""

import argparse
import base64
import hashlib
import io
import json
import lzma
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zlib
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.join(ROOT, "tools", "sandbox", "runtime")
LOCK = os.path.join(HERE, "sources.lock")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "packages.json")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "rootfs.tar.xz")
KOTLIN = os.path.join(ROOT, "app", "src", "main", "java", "com", "wangzan101", "mika", "cap", "sandbox", "BundledRuntime.kt")
CLIENT = os.path.join(ROOT, "app", "src", "main", "assets", "sandbox", "mika")
CACHE = os.path.join(ROOT, "build", "sandbox-runtime", "dl")
CDN = os.environ.get("ALPINE_CDN", "https://dl-cdn.alpinelinux.org/alpine")
MIRROR_CN = os.environ.get("ALPINE_MIRROR_CN", "")
MIRROR_BACKUP = os.environ.get("ALPINE_MIRROR_BACKUP", "")
PROXY = os.environ.get("HTTP_PROXY", os.environ.get("HTTPS_PROXY", ""))
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

def _opener(url):
    """Open a URL, routing through PROXY when set.  Adds a browser User-Agent so that
    Chinese alpine mirrors (Tsinghua / Aliyun) don't 403 a bare urllib client."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    if PROXY:
        proxy = urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
        opener = urllib.request.build_opener(proxy)
    else:
        opener = urllib.request.build_opener()
    return opener.open(req, timeout=120)
ARCH = "aarch64"
# What `apk add` would be asked for. Keep in sync with core SANDBOX_PACKAGES (a JVM test checks it).
# Base set: Python/Node + Pillow + pypdf + tzdata + the pip toolchain.
# Doc set: poppler-utils (PDF->text/image), a CJK font + fontconfig (CJK output).
# Everyday CLI: curl/unzip/zip/git/jq for fetch/unpack/repo work in scripts.
WANT = ["python3", "nodejs", "py3-pillow", "py3-pypdf", "tzdata", "py3-pip", "py3-setuptools", "py3-wheel", "py3-packaging", "poppler-utils", "font-wqy-zenhei", "fontconfig", "curl", "unzip", "zip", "git", "jq"]
# Install scripts and triggers that a real `apk add` would run on the phone, pinned by SHA-256 of the script, and why
# the runtime is already right without running them. Any other install script or trigger stops the build.
SCRIPTS_NOT_RUN = {
    "icu-data-en/.post-install": ("1b60012707d8e5c6735bbe4620a08335e019618b776462da11fe2574d4a3dba4",
                                  "only prints a hint about icu-data-full"),
    # update-ca-certificates rebuilds /etc/ssl/certs/ca-certificates.crt; the minirootfs already has that file
    # from ca-certificates-bundle, built from the same Mozilla list at the same version
    "ca-certificates/.trigger": ("1bf77c8eae3220b1bd7dd86561d0899315f51d72257c1aeb4066072ed4304fe5",
                                 "same CA list as ca-certificates-bundle"),
    "fontconfig/.trigger": ("672cb2670f1085f515f5710e01c5a747830f14acc5fa20ded1fb7d46b282719d",
                             "only runs fc-cache --system-only to rebuild the font cache"),
    "mkfontscale/.trigger": ("7d6b49d96c7e0a5dee3882cd19f96c39194bdda1bbdee11e2efeb71deb443cd0",
                            "only runs mkfontscale to rebuild font-scale databases; fonts remain importable without it"),
}




def _openssl_path():
    """Find a usable openssl binary: Windows PATH, then common install locations."""
    import shutil
    found = shutil.which("openssl")
    if found:
        return found
    candidates = [
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\Git\mingw64\bin\openssl.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    die("openssl not found on PATH or common locations; install Git for Windows or OpenSSL")


def die(msg):
    print(f"build-runtime: {msg}", file=sys.stderr)
    sys.exit(1)


def fetch(url, dest):
    if not url.startswith("https://"):
        die(f"only https downloads allowed: {url}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    # Primary: the pinned URL (official CDN).  If it 404s (package rotated off the CDN),
    # retry the same path under the CN mirrors, then the proxy — same content, SHA still enforced.
    attempts = [url]
    if MIRROR_CN:
        attempts.append(url.replace(CDN, MIRROR_CN, 1) if url.startswith(CDN) else url)
    if MIRROR_BACKUP:
        attempts.append(url.replace(CDN, MIRROR_BACKUP, 1) if url.startswith(CDN) else url)
    last_err = None
    for candidate in dict.fromkeys(attempts):  # dedupe, keep order
        try:
            with _opener(candidate) as r, open(tmp, "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
            os.replace(tmp, dest)
            if candidate != url:
                print(f"build-runtime: fell back to {candidate} for {os.path.basename(dest)}")
            return
        except Exception as e:
            last_err = e
            if "404" in str(e):
                continue  # try next mirror
            break
    os.remove(tmp) if os.path.exists(tmp) else None
    die(f"all download attempts failed for {url}: {last_err}")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def cached(url, name, want_sha=None):
    """Download into the cache unless a copy with the right SHA-256 is there; return the bytes."""
    path = os.path.join(CACHE, name)
    if not (want_sha and os.path.isfile(path) and sha256(open(path, "rb").read()) == want_sha):
        fetch(url, path)
    data = open(path, "rb").read()
    if want_sha and sha256(data) != want_sha:
        os.remove(path)
        die(f"SHA-256 mismatch for {name}: {sha256(data)} (expected {want_sha})")
    return data


def gzip_streams(data):
    """Split concatenated gzip members; return their raw byte ranges (apk v2 packages are 2-3 members)."""
    out, pos = [], 0
    while pos < len(data):
        d = zlib.decompressobj(31)
        d.decompress(data[pos:])
        if not d.eof:
            die("truncated gzip member")
        end = len(data) - len(d.unused_data)
        out.append(data[pos:end])
        pos = end
    return out


def tar_members(gz_bytes):
    """Read one gzip member as a tar; return [(TarInfo, bytes|None)]. apk control/signature tars have no end marker."""
    raw = zlib.decompress(gz_bytes, 31)
    res = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:", ignore_zeros=False) as t:
        for m in t:
            res.append((m, t.extractfile(m).read() if m.isreg() else None))
    return res


def rsa_verify(keys, sig_name, sig, signed):
    """sig_name is `.SIGN.RSA.<key>` (SHA-1) or `.SIGN.RSA256.<key>` (SHA-256)."""
    m = re.fullmatch(r"\.SIGN\.(RSA|RSA256)\.(.+)", sig_name)
    if not m or m.group(2) not in keys:
        die(f"unknown signature {sig_name}")
    digest = "-sha1" if m.group(1) == "RSA" else "-sha256"
    with tempfile.TemporaryDirectory() as d:
        k, s, b = (os.path.join(d, x) for x in ("k", "s", "b"))
        open(k, "wb").write(keys[m.group(2)])
        open(s, "wb").write(sig)
        open(b, "wb").write(signed)
        openssl = _openssl_path()
        r = subprocess.run([openssl, "dgst", digest, "-verify", k, "-signature", s, b], capture_output=True)
    if r.returncode != 0:
        die(f"bad signature ({sig_name})")


def q1(digest):
    return "Q1" + base64.b64encode(digest).decode()


def parse_index(text):
    pkgs = []
    for blk in text.split("\n\n"):
        f = {}
        lines = []
        for line in blk.split("\n"):
            if len(line) > 1 and line[1] == ":":
                f.setdefault(line[0], line[2:])
                lines.append(line)
        if "P" in f:
            f["_lines"] = lines
            pkgs.append(f)
    return pkgs


def read_lock():
    alpine, pkgs = None, []
    for line in open(LOCK, encoding="utf-8"):
        parts = line.split()
        if not parts or parts[0].startswith("#"):
            continue
        if parts[0] == "alpine":
            alpine = {"version": parts[1], "url": parts[2], "sha256": parts[3]}
        elif parts[0] == "apk":
            pkgs.append({"repo": parts[1], "name": parts[2], "version": parts[3], "checksum": parts[4], "sha256": parts[5]})
    if not alpine:
        die("sources.lock has no alpine line")
    return alpine, pkgs


def branch(version):
    return "v" + ".".join(version.split(".")[:2])


def minirootfs(alpine):
    data = cached(alpine["url"], os.path.basename(alpine["url"]), alpine["sha256"])
    raw = zlib.decompress(data, 31)
    keys = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as t:
        for m in t:
            if m.isreg() and m.name.lstrip("./").startswith("etc/apk/keys/"):
                keys[os.path.basename(m.name)] = t.extractfile(m).read()
    return raw, keys


def apk_url(alpine, p):
    return f"{CDN}/{branch(alpine['version'])}/{p['repo']}/{ARCH}/{p['name']}-{p['version']}.apk"


# ---------------------------------------------------------------- resolve

def resolve():
    alpine, _ = read_lock()
    base_raw, keys = minirootfs(alpine)
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        base = parse_index(t.extractfile("./lib/apk/db/installed").read().decode())
    index = {}
    for repo in ("main", "community"):
        url = f"{CDN}/{branch(alpine['version'])}/{repo}/{ARCH}/APKINDEX.tar.gz"
        path = os.path.join(CACHE, f"APKINDEX-{repo}.tar.gz")
        fetch(url, path)
        sig, body = gzip_streams(open(path, "rb").read())[:2]
        (sm, sdata), = tar_members(sig)
        rsa_verify(keys, sm.name, sdata, body)
        text = dict((m.name, d) for m, d in tar_members(body))["APKINDEX"].decode()
        for p in parse_index(text):
            p["_repo"] = repo
            index.setdefault(p["P"], p)  # main wins over community
    provides = {}
    for p in index.values():
        for pr in p.get("p", "").split():
            provides.setdefault(re.split(r"[<>=~]", pr)[0], []).append(p)
    have = set()
    for p in base:
        have.add(p["P"])
        have.update(re.split(r"[<>=~]", pr)[0] for pr in p.get("p", "").split())
        if p["P"] in index and index[p["P"]]["V"] != p["V"]:
            # Tolerate a minirootfs lagging the rolling branch index: overwrite this base entry
            # with the index copy (adds the APKINDEX `C:` checksum, `p`/`i`/`o` etc.), so the
            # package is re-fetched and the written lock pins the index version.  Equivalent to
            # `apk upgrade` post-install; the minirootfs release may simply not have caught up.
            ip = index[p['P']]
            q = next(x for x in base if x['P'] == p['P'])
            q.clear()
            for k, v in ip.items():
                q[k] = v
            q['_repo'] = ip.get('_repo', 'main')
            print(f"build-runtime: minirootfs {p['P']} {p['V']} -> index {ip['V']} (adopted)")
    chosen = []

    def add(p):
        chosen.append(p)
        have.add(p["P"])
        have.update(re.split(r"[<>=~]", pr)[0] for pr in p.get("p", "").split())
        for dep in p.get("D", "").split():
            need(dep)

    def need(dep):
        if dep.startswith("!"):
            return
        name = re.split(r"[<>=~]", dep)[0]
        if name in have:
            return
        cand = index.get(name) or sorted(provides.get(name, []), key=lambda c: c["P"])[:1]
        if isinstance(cand, list):
            cand = cand[0] if cand else None
        if cand is None:
            die(f"cannot resolve {dep}")
        if cand["P"] not in have:
            add(cand)

    for w in WANT:
        need(w)
    # install_if: apk adds these on its own when every condition is installed
    changed = True
    while changed:
        changed = False
        for p in index.values():
            cond = p.get("i", "").split()
            if cond and p["P"] not in have and all(re.split(r"[<>=~]", c)[0] in have for c in cond):
                add(p)
                changed = True

    lines = [
        "# Runtime bundled in the APK. Generated by build-runtime.py --resolve; edit only the alpine line by hand.",
        "# alpine <version> <minirootfs url> <sha256>",
        "# apk <repo> <name> <version> <APKINDEX checksum> <sha256 of the .apk>",
        f"alpine {alpine['version']} {alpine['url']} {alpine['sha256']}",
    ]
    for p in chosen:
        e = {"repo": p["_repo"], "name": p["P"], "version": p["V"]}
        data = cached(apk_url(alpine, e), f"{p['P']}-{p['V']}.apk")
        lines.append(f"apk {p['_repo']} {p['P']} {p['V']} {p['C']} {sha256(data)}")
    open(LOCK, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"resolved {len(chosen)} packages into {os.path.relpath(LOCK, ROOT)}")


# ---------------------------------------------------------------- build

class Tree:
    """Ordered path -> (TarInfo, bytes). First position wins, so the output order stays stable."""

    def __init__(self):
        self.entries = {}
        self.owner = {}

    def put(self, info, data, owner=None):
        name = info.name.lstrip("./").rstrip("/")
        if not name:
            return
        info.name = name
        old = self.entries.get(name)
        if old and old[0].isdir() and info.isdir():
            return
        if old and owner and self.owner.get(name) not in (None, owner) and not info.isdir():
            die(f"{owner} and {self.owner[name]} both install /{name}")
        self.entries[name] = (info, data)
        if owner:
            self.owner[name] = owner


def norm(info, mtime=None):
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.pax_headers = {}
    if mtime is not None:
        info.mtime = mtime
    return info


def unpack_apk(alpine, p, keys):
    data = cached(apk_url(alpine, p), f"{p['name']}-{p['version']}.apk", p["sha256"])
    parts = gzip_streams(data)
    if len(parts) != 3:
        die(f"{p['name']}: expected 3 gzip members, got {len(parts)}")
    sig, control, payload = parts
    (sm, sdata), = tar_members(sig)
    rsa_verify(keys, sm.name, sdata, control)
    if q1(hashlib.sha1(control).digest()) != p["checksum"]:
        die(f"{p['name']}: control checksum differs from the lock")
    ctl = dict((m.name, d) for m, d in tar_members(control))
    info = {}
    for line in ctl[".PKGINFO"].decode().splitlines():
        if " = " in line:
            k, v = line.split(" = ", 1)
            info.setdefault(k, []).append(v)
    if info.get("datahash", [""])[0] != sha256(payload):
        die(f"{p['name']}: data hash differs from its signed .PKGINFO")
    if info["pkgname"][0] != p["name"] or info["pkgver"][0] != p["version"]:
        die(f"{p['name']}: .PKGINFO names {info['pkgname'][0]} {info['pkgver'][0]}")
    # Upgrade/removal scripts and triggers go into scripts.tar for later apk runs. Install scripts and triggers are not
    # run here, so each one must be listed in SCRIPTS_NOT_RUN with its exact content.
    scripts = {n: d for n, d in ctl.items() if n.startswith(".") and n != ".PKGINFO"}
    for n in sorted(scripts):
        if n in (".pre-install", ".post-install", ".trigger"):
            pinned = SCRIPTS_NOT_RUN.get(f"{p['name']}/{n}")
            if not pinned or pinned[0] != sha256(scripts[n]):
                die(f"{p['name']} has {n} ({sha256(scripts[n])}); check what it does and list it in SCRIPTS_NOT_RUN")
    info["_scripts"] = scripts
    raw = zlib.decompress(payload, 31)
    members = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as t:
        for m in t:
            csum = m.pax_headers.get("APK-TOOLS.checksum.SHA1")
            members.append((m, t.extractfile(m).read() if m.isreg() else None, csum))
    return info, members


def installed_entry(index_lines, members):
    """One /lib/apk/db/installed record: the package's index header, then F:/M: per directory, R:/a:/Z: per file."""
    out = list(index_lines)
    dirs = {}
    order = []
    for m, _, csum in members:
        name = m.name.lstrip("./").rstrip("/")
        if m.isdir():
            if name not in dirs:
                dirs[name] = (m, [])
                order.append(name)
        else:
            parent = os.path.dirname(name)
            if parent not in dirs:
                dirs[parent] = (None, [])
                order.append(parent)
            dirs[parent][1].append((m, csum))
    for d in order:
        m, files = dirs[d]
        if d == "" and not files:
            continue
        out.append(f"F:{d}")
        if m is not None and (m.uid, m.gid, m.mode & 0o7777) != (0, 0, 0o755):
            out.append(f"M:{m.uid}:{m.gid}:{m.mode & 0o7777:o}")
        for f, csum in files:
            out.append(f"R:{os.path.basename(f.name)}")
            if (f.uid, f.gid, f.mode & 0o7777) != (0, 0, 0o644):
                out.append(f"a:{f.uid}:{f.gid}:{f.mode & 0o7777:o}")
            if csum:
                out.append("Z:" + q1(bytes.fromhex(csum)))
    return "\n".join(out) + "\n\n"


PIP_LOCK = os.path.join(HERE, "pip-requires.lock")

def index_header(p, info, payload_size):
    """The installed-db header for a package, rebuilt from its signed .PKGINFO (same fields apk writes)."""
    g = lambda k: info.get(k, [""])[0]
    h = [f"C:{p['checksum']}", f"P:{p['name']}", f"V:{p['version']}", f"A:{g('arch')}", f"S:{payload_size}",
         f"I:{g('size')}", f"T:{g('pkgdesc')}", f"U:{g('url')}", f"L:{g('license')}", f"o:{g('origin')}",
         f"m:{g('maintainer')}", f"t:{g('builddate')}", f"c:{g('commit')}"]
    if info.get("provider_priority"):
        h.append(f"k:{g('provider_priority')}")
    if info.get("depend"):
        h.append("D:" + " ".join(info["depend"]))
    if info.get("provides"):
        h.append("p:" + " ".join(info["provides"]))
    if info.get("install_if"):
        h.append("i:" + " ".join(info["install_if"]))
    if info.get("replaces"):
        h.append("r:" + " ".join(info["replaces"]))
    return h


def build(out_asset):
    alpine, pkgs = read_lock()
    base_raw, keys = minirootfs(alpine)
    tree = Tree()
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        db_info = t.getmember("./lib/apk/db/installed")
        epoch = db_info.mtime
        installed = t.extractfile(db_info).read().decode()
        for m in t.getmembers():
            tree.put(norm(m), t.extractfile(m).read() if m.isreg() else None)
    base_names = {p["P"] for p in parse_index(installed)}
    manifest = []
    db = installed.rstrip("\n") + "\n\n"
    extra_scripts = []
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        base_scripts = t.extractfile("./lib/apk/db/scripts.tar").read()
        triggers = t.extractfile("./lib/apk/db/triggers").read().decode()
    for p in pkgs:
        if p["name"] in base_names:
            die(f"{p['name']} is already in the minirootfs")
        info, members = unpack_apk(alpine, p, keys)
        for m, data, _ in members:
            tree.put(norm(m), data, owner=p["name"])
        size = len(cached(apk_url(alpine, p), f"{p['name']}-{p['version']}.apk", p["sha256"]))
        db += installed_entry(index_header(p, info, size), members)
        for sname, sdata in sorted(info["_scripts"].items()):
            extra_scripts.append((f"{p['name']}-{p['version']}.{p['checksum']}{sname}", sdata))
        if info.get("triggers"):
            triggers += f"{p['checksum']} {' '.join(info['triggers'][0].split())}\n"
        manifest.append({"name": p["name"], "version": p["version"], "license": info.get("license", [""])[0],
                         "origin": info.get("origin", [""])[0], "url": info.get("url", [""])[0], "repo": p["repo"],
                         "commit": info.get("commit", [""])[0]})
    for p in parse_index(installed):
        manifest.append({"name": p["P"], "version": p["V"], "license": p.get("L", ""), "origin": p.get("o", ""),
                         "url": p.get("U", ""), "repo": "main", "commit": p.get("c", "")})
    manifest.sort(key=lambda e: e["name"])

    # Remove PEP668 EXTERNALLY-MANAGED marker so pip can install into system
    # site-packages directly (required for on-demand package installs). The directory is read from
    # the image: python3 comes from WANT, and its minor version moves with Alpine, so a path pinned
    # here leaves the real marker in place and turns into "pip refuses to install" on the phone.
    tree.entries.pop(f"{python_lib(tree)}/EXTERNALLY-MANAGED", None)

    # Bake the pinned pip wheels into site-packages (SHA-256-verified, reproducible).
    pip_wheels = bake_pip_wheels(tree, epoch)
    if pip_wheels:
        print(f"build-runtime: baked {len(pip_wheels)} pinned wheels: {', '.join(pip_wheels)}")

    def gen(path, data, mode=0o644):
        ti = tarfile.TarInfo(path)
        ti.size = len(data)
        ti.mode = mode
        tree.put(norm(ti, epoch), data)

    world = sorted(set(open_world(base_raw) + WANT))
    gen("lib/apk/db/installed", db.encode())
    gen("lib/apk/db/triggers", triggers.encode())
    sbuf = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(base_scripts), mode="r:") as bt, \
            tarfile.open(fileobj=sbuf, mode="w:", format=tarfile.USTAR_FORMAT) as st:
        for m in bt.getmembers():
            st.addfile(m, bt.extractfile(m))
        for sname, sdata in extra_scripts:
            ti = norm(tarfile.TarInfo(sname), epoch)
            ti.size, ti.mode = len(sdata), 0o755
            st.addfile(ti, io.BytesIO(sdata))
    gen("lib/apk/db/scripts.tar", sbuf.getvalue())
    gen("etc/apk/world", ("\n".join(world) + "\n").encode())
    b = branch(alpine["version"])
    # apk source list: official first, CN mirror second, optional backup third.
    # Alpine's `apk` tries them in order and falls back when one is slow/blocked.
    repos = [f"{CDN}/{b}/main", f"{CDN}/{b}/community"]
    if MIRROR_CN:
        repos.append(f"{MIRROR_CN}/{b}/main")
        repos.append(f"{MIRROR_CN}/{b}/community")
    if MIRROR_BACKUP:
        repos.append(f"{MIRROR_BACKUP}/{b}/main")
        repos.append(f"{MIRROR_BACKUP}/{b}/community")
    gen("etc/apk/repositories", ("\n".join(repos) + "\n").encode())
    gen("etc/hosts", b"127.0.0.1 localhost\n::1 localhost\n")
    # Package-manager source configs: CN mirrors first, official fallback.  pip.conf lives in /etc/pip/pip.conf
    # (honored by root pip); .npmrc at / so the sandbox's npm uses npmmirror.  These are static text, so they
    # work even when the proxy is not configured (NO_PROXY keeps CN mirror traffic direct).
    gen("etc/pip/pip.conf", """[global]
index-url = https://pypi.tuna.tsinghua.edu.cn/simple
trusted-host = pypi.tuna.tsinghua.edu.cn
""".encode(), 0o644)
    gen(".npmrc", b"registry=https://registry.npmmirror.com\n")
    gen("root/.npmrc", b"registry=https://registry.npmmirror.com\n")
    # Proxy environment for the sandbox's network tools (curl, pip, npm, apk).
    # When PROXY is set, bake it into /etc/profile.d so the phone's network commands
    # use it without the user having to configure it.  CN mirrors stay direct via NO_PROXY.
    if PROXY:
        proxy_script = f"""#!/bin/sh
# Mika sandbox network config (auto-generated).
# Domestic CN package mirrors go direct; only non-CN hosts use the proxy.
export HTTP_PROXY="{PROXY}"
export HTTPS_PROXY="{PROXY}"
export NO_PROXY="mirrors.aliyun.com,registry.npmmirror.com,registry.npm.taobao.org,pypi.tuna.tsinghua.edu.cn,pypi.mirrors.ustc.edu.cn,*.cn,localhost,127.0.0.1"
export no_proxy="$NO_PROXY"
"""
        gen("etc/profile.d/99-proxy.sh", proxy_script.encode(), 0o755)
    gen("usr/local/bin/mika", open(CLIENT, "rb").read(), 0o755)

    buf = io.BytesIO()
    total = 0
    with tarfile.open(fileobj=buf, mode="w:", format=tarfile.PAX_FORMAT) as out:
        for name, (info, data) in tree.entries.items():
            if info.isreg():
                info.size = len(data)
                total += len(data)
                out.addfile(info, io.BytesIO(data))
            else:
                out.addfile(info)
    tar = buf.getvalue()
    xz = lzma.compress(tar, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=9)
    os.makedirs(os.path.dirname(out_asset), exist_ok=True)
    open(out_asset, "wb").write(xz)
    return {
        "alpine": alpine["version"], "sha256": sha256(xz), "bytes": len(xz), "fileBytes": total,
        "entries": len(tree.entries), "packages": manifest,
    }


def open_world(base_raw):
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        return t.extractfile("./etc/apk/world").read().decode().split()


def version_id(r):
    return f"{r['alpine']}-{r['sha256'][:12]}"


def read_pip_lock():
    """Wheels to bake into site-packages. Every field is verified at build time (SHA-256), so the
    build is reproducible offline: re-download only these exact files."""
    path = PIP_LOCK
    # Required, not optional: a checkout that lost this file would otherwise build a runtime with
    # no site-packages at all, rewrite the asset and BundledRuntime.kt from it, and say nothing.
    if not os.path.isfile(path):
        die("runtime/pip-requires.lock is missing; it lists the wheels baked into site-packages")
    data = json.load(open(path, encoding="utf-8"))
    if data.get("spec") != "mika.pip-requires/v1":
        die(f"{path}: unknown spec {data.get('spec')}")
    for w in data.get("wheels", []):
        for field in ("pkg", "version", "filename", "sha256", "url"):
            if not w.get(field):
                die(f"{path}: wheel entry missing {field}")
    return data["wheels"]


def python_lib(tree):
    """The runtime's CPython library directory, read from the image rather than pinned in code."""
    found = sorted({name.split("/")[2] for name in tree.entries
                    if name.startswith("usr/lib/python3.") and name.count("/") >= 2})
    if len(found) != 1:
        die(f"expected one usr/lib/python3.* directory in the runtime, found {', '.join(found) or 'none'}")
    return "usr/lib/" + found[0]


def wheel_requires(filename):
    """The CPython a wheel is built for, as (minor, exact), or None when any Python 3 will do.

    `cp312`-`cp312` runs on 3.12 alone; `cp311`-`abi3` is the stable ABI, so 3.11 and later; a
    `py3`/`none` wheel names no version at all. A wheel's own tags are the authority here, so the
    check needs no table kept in step with the lock.
    """
    parts = filename[:-len(".whl")].split("-") if filename.endswith(".whl") else filename.split("-")
    if len(parts) < 3:
        return None
    python, abi = parts[-3], parts[-2]
    m = re.fullmatch(r"cp3(\d+)", python)
    if not m:
        return None
    return int(m.group(1)), abi != "abi3"


def bake_pip_wheels(tree, epoch):
    """Unpack each pinned wheel into site-packages. A wheel is a zip of the installed layout
    (package dir + .dist-info), so unzip == install for both pure and native wheels; no pip runs.
    Every wheel is SHA-256-verified against the lock, and each non-regular member is rejected."""
    wheels = read_pip_lock()
    lib = python_lib(tree)
    sp = f"{lib}/site-packages"
    minor = int(lib.rpartition(".")[2])
    for w in wheels:
        name = w["filename"]
        # A wheel built for one CPython lands in site-packages either way and only fails at import
        # on the phone, where nothing in the build would have said why.
        need = wheel_requires(name)
        if need is not None:
            need_minor, exact = need
            if (minor != need_minor if exact else minor < need_minor):
                die(f"{name} needs CPython 3.{need_minor}{'' if exact else ' or newer'}, "
                    f"the runtime has 3.{minor}: re-resolve the wheels in runtime/pip-requires.lock")
        data = cached(w["url"], f"pip-{name}", w["sha256"])
        zf = zipfile.ZipFile(io.BytesIO(data))
        for zi in zf.infolist():
            if zi.is_dir():
                continue
            dest = f"{sp}/{zi.filename}"
            mode = 0o755 if (zi.external_attr >> 16) & 0o755 else 0o644
            ti = tarfile.TarInfo(dest)
            ti.size = zi.file_size
            ti.mode = mode
            ti.mtime = epoch
            tree.put(norm(ti, epoch), zf.read(zi))
    return [(f"{w['pkg']}=={w['version']}") for w in wheels]


def kotlin(r):
    asset_path = os.path.relpath(ASSET, os.path.join(ROOT, "app", "src", "main", "assets")).replace(os.sep, "/")
    return f"""package com.wangzan101.mika.cap.sandbox

// Generated by tools/sandbox/build-runtime.py; do not edit.

/** 随安装包自带的运行环境(assets/{asset_path})。 */
object BundledRuntime {{
    const val ASSET = "runtime/rootfs.tar.xz"
    const val VERSION = "{version_id(r)}"
    const val SHA256 = "{r['sha256']}"
    const val BYTES = {r['bytes']}L
    /** 解出的普通文件总字节数与条目数:解包时的上限按它们定,多一点余量。 */
    const val FILE_BYTES = {r['fileBytes']}L
    const val ENTRIES = {r['entries']}
}}
"""


def manifest(r):
    """The committed packages.json, byte for byte: the asset, the Kotlin file and this are one build's output."""
    return json.dumps({"version": version_id(r), "alpine": r["alpine"], "packages": r["packages"]},
                      indent=1, ensure_ascii=False) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--resolve", action="store_true")
    g.add_argument("--check", action="store_true")
    a = ap.parse_args()
    if a.resolve:
        resolve()
        return
    if a.check:
        with tempfile.TemporaryDirectory() as d:
            r = build(os.path.join(d, "rootfs.tar.xz"))
        # --check proves the committed artifacts match a rebuild, so an absent one fails here:
        # skipping its comparison would report "ok" for an artifact it never looked at.
        for path in (KOTLIN, ASSET, MANIFEST):
            if not os.path.isfile(path):
                die(f"{os.path.relpath(path, ROOT)} is missing; --check compares it with a rebuild")
        committed = open(KOTLIN, encoding="utf-8").read()
        if kotlin(r) != committed:
            die(f"rebuilt runtime {r['sha256']} differs from BundledRuntime.kt")
        if sha256(open(ASSET, "rb").read()) != r["sha256"]:
            die("assets/runtime/rootfs.tar.xz differs from the rebuild")
        if open(MANIFEST, encoding="utf-8").read() != manifest(r):
            die(f"{os.path.relpath(MANIFEST, ROOT)} differs from the rebuild")
        print(f"ok {version_id(r)} ({r['bytes']} bytes)")
        return
    r = build(ASSET)
    open(KOTLIN, "w", encoding="utf-8").write(kotlin(r))
    open(MANIFEST, "w", encoding="utf-8").write(manifest(r))
    print(f"built {version_id(r)}: {r['bytes']} bytes compressed, {r['fileBytes']} bytes of files, {r['entries']} entries")


if __name__ == "__main__":
    main()
