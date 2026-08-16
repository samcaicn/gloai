#!/usr/bin/env python3
"""upload_nsis_to_cos.py — 手工上传 NSIS 安装包到腾讯云 COS 并更新 latest.json

COS 布局 (与 server/core/update/update_server.py 一致):
    cos://<bucket>/<UPDATE_COS_PREFIX>/
    └── tupai/
        ├── latest.json
        └── windows/tupai_<version>_x64-setup.exe

latest.json 结构:
    {
      "version": "1.8.9",
      "platforms": {
        "windows": {
          "filename": "tupai_1.8.9_x64-setup.exe",
          "size": 8460000,
          "sha256": "..."
        }
      },
      "release_notes": "..."
    }

用法:
    # 自动查找最新构建的 NSIS 包并上传
    python update/upload_nsis_to_cos.py

    # 指定安装包路径
    python update/upload_nsis_to_cos.py "D:\\path\\to\\tupai_1.8.9_x64-setup.exe"

    # 指定版本 + 更新说明
    python update/upload_nsis_to_cos.py --version 1.8.9 --notes "修复 xxx"

    # 预览不实际上传
    python update/upload_nsis_to_cos.py --dry-run

配置来源 (按优先级):
    1. --env-file 指定的 .env 文件
    2. D:\\1data\\ai\\tupaisaasmcp\\.env.local
    3. 环境变量 COS_BUCKET / COS_REGION / COS_SECRET_ID / COS_SECRET_KEY

依赖: 仅 Python 3.8+ 标准库 (COS v5 签名自实现, 无需 qcloud_cos SDK)
参考: deploy/scf/dump_log_to_cos.py
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import http.client
import json
import os
import re
import socket
import ssl
import sys
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Dict, Optional, Tuple

# ── 默认配置 ──────────────────────────────────────────────────────────────────
DEFAULT_ENV_FILE = r"D:\1data\ai\tupaisaasmcp\.env.local"
DEFAULT_BRAND = "tupai"
DEFAULT_PLATFORM_KEY = "windows"          # update_server TAURI_TO_BRAND_KEY
DEFAULT_UPDATE_PREFIX = "update"          # config.py UPDATE_COS_PREFIX 默认值
DEFAULT_ARCH_SUFFIX = "x64-setup.exe"     # Tauri NSIS x64 产物后缀

# NSIS 构建产物默认搜索路径
DEFAULT_BUNDLE_DIR = r"E:\tupautochromium-cache\target\release-nsis\bundle\nsis"

# 项目根 (用于读取 tauri.conf.json 版本号)
PROJECT_ROOT = Path(__file__).resolve().parent.parent
TAURI_CONF = PROJECT_ROOT / "src-tauri" / "tauri.conf.json"


# ── .env 加载 ─────────────────────────────────────────────────────────────────
def load_env(path: Path) -> Dict[str, str]:
    env: Dict[str, str] = {}
    if not path.is_file():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        if "#" in v:
            v = v.split("#", 1)[0]
        env[k.strip()] = v.strip()
    return env


def get_cos_config(env_file: Optional[Path]) -> Dict[str, str]:
    env: Dict[str, str] = {}
    if env_file and env_file.is_file():
        env = load_env(env_file)
    # 环境变量覆盖
    for k in ("COS_BUCKET", "COS_REGION", "COS_SECRET_ID", "COS_SECRET_KEY",
              "COS_UPDATE_PREFIX"):
        if os.environ.get(k):
            env[k] = os.environ[k]

    bucket = env.get("COS_BUCKET", "").strip()
    region = env.get("COS_REGION", "").strip() or "ap-shanghai"
    sid = env.get("COS_SECRET_ID", "").strip() or env.get("SECRET_ID", "").strip()
    sk = env.get("COS_SECRET_KEY", "").strip() or env.get("SECRET_KEY", "").strip()
    prefix = env.get("COS_UPDATE_PREFIX", DEFAULT_UPDATE_PREFIX).strip().strip("/")

    missing = [k for k, v in [
        ("COS_BUCKET", bucket), ("COS_SECRET_ID", sid), ("COS_SECRET_KEY", sk),
    ] if not v]
    if missing:
        raise SystemExit(
            f"[cfg] 缺少 COS 凭证: {', '.join(missing)}\n"
            f"       env_file={env_file}\n"
            f"       请在 .env.local 中配置或通过环境变量/CLI 传入"
        )
    return {"bucket": bucket, "region": region, "sid": sid, "sk": sk, "prefix": prefix}


# ── COS v5 签名 + HTTP ────────────────────────────────────────────────────────
def _cos_sign(method: str, key: str, host: str, sk: str, sid: str) -> str:
    """COS v5 简单签名 (仅 host 头, 无 query 参数)。

    CanonicalRequest = METHOD + "\\n" + URI + "\\n" + QueryString + "\\n"
                      + CanonicalHeaders + "\\n" + SignedHeaders + "\\n"
                      + HashedRequestPayload   (简单签名省略)
    """
    ts = int(time.time())
    exp = ts + 600
    sign_time = f"{ts};{exp}"
    canonical_request = f"{method.lower()}\n/{key}\n\nhost={host}\n"
    hashed_canonical = hashlib.sha1(canonical_request.encode("utf-8")).hexdigest()
    string_to_sign = f"sha1\n{sign_time}\n{hashed_canonical}\n"
    sign_key = hmac.new(sk.encode("utf-8"), sign_time.encode("utf-8"), hashlib.sha1).hexdigest()
    signature = hmac.new(sign_key.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).hexdigest()
    return (
        f"q-sign-algorithm=sha1"
        f"&q-ak={sid}"
        f"&q-sign-time={sign_time}"
        f"&q-key-time={sign_time}"
        f"&q-header-list=host"
        f"&q-url-param-list="
        f"&q-signature={signature}"
    )


def cos_request(cfg: Dict[str, str], method: str, key: str,
                body: Optional[bytes] = None,
                content_type: str = "application/octet-stream",
                timeout: int = 120) -> Tuple[int, bytes]:
    """发起 COS HTTP 请求, 返回 (status, response_body)。

    method: GET / PUT
    body:   PUT 的请求体 (bytes)
    """
    host = f"{cfg['bucket']}.cos.{cfg['region']}.myqcloud.com"
    auth = _cos_sign(method, key, host, cfg["sk"], cfg["sid"])
    headers = {
        "Host": host,
        "Authorization": auth,
    }
    if body is not None:
        headers["Content-Length"] = str(len(body))
        headers["Content-Type"] = content_type

    ctx = ssl.create_default_context()
    conn = http.client.HTTPSConnection(host, timeout=timeout, context=ctx)
    try:
        conn.request(method, f"/{key}", body=body, headers=headers)
        r = conn.getresponse()
        resp = r.read()
        return r.status, resp
    finally:
        conn.close()


def cos_put_object(cfg: Dict[str, str], key: str, body: bytes,
                   content_type: str = "application/octet-stream",
                   timeout: int = 300) -> Tuple[bool, int, str]:
    """PUT Object, 返回 (ok, status, msg)。"""
    try:
        status, resp = cos_request(cfg, "PUT", key, body, content_type, timeout)
        if 200 <= status < 300:
            return True, status, "ok"
        return False, status, resp.decode("utf-8", errors="replace")[:800]
    except (socket.timeout, OSError) as e:
        return False, -1, f"network: {e!r}"


def cos_get_object(cfg: Dict[str, str], key: str, timeout: int = 15) -> Tuple[bool, int, bytes]:
    """GET Object, 返回 (ok, status, body)。404 视为不存在 (ok=False, status=404)。"""
    try:
        status, resp = cos_request(cfg, "GET", key, None, "", timeout)
        if 200 <= status < 300:
            return True, status, resp
        return False, status, resp
    except (socket.timeout, OSError) as e:
        return False, -1, f"network: {e!r}".encode()


# ── 文件工具 ──────────────────────────────────────────────────────────────────
def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):  # 1MB chunks
            h.update(chunk)
    return h.hexdigest()


def find_nsis_bundle(bundle_dir: Path) -> Optional[Path]:
    """在 bundle_dir 中查找最新的 *-setup.exe。"""
    if not bundle_dir.is_dir():
        return None
    candidates = sorted(
        bundle_dir.glob("*-setup.exe"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return candidates[0] if candidates else None


def parse_version_from_filename(name: str) -> Optional[str]:
    """从文件名提取版本号, 如 'tupai_1.8.9_x64-setup.exe' -> '1.8.9'。"""
    m = re.search(r"(\d+\.\d+\.\d+)", name)
    return m.group(1) if m else None


def get_version_from_tauri_conf() -> Optional[str]:
    """从 tauri.conf.json 读取 version。"""
    if not TAURI_CONF.is_file():
        return None
    try:
        data = json.loads(TAURI_CONF.read_text(encoding="utf-8"))
        return data.get("version")
    except Exception:
        return None


def normalize_filename(version: str, brand: str = DEFAULT_BRAND) -> str:
    """生成符合 update_server 约定的文件名: <brand>_<version>_x64-setup.exe。"""
    return f"{brand}_{version}_{DEFAULT_ARCH_SUFFIX}"


# ── latest.json 更新 ──────────────────────────────────────────────────────────
def fetch_latest_json(cfg: Dict[str, str], brand: str) -> Dict:
    """从 COS 拉取现有 latest.json, 不存在则返回空骨架。"""
    key = f"{cfg['prefix']}/{brand}/latest.json"
    ok, status, body = cos_get_object(cfg, key)
    if ok:
        try:
            return json.loads(body.decode("utf-8"))
        except Exception as e:
            print(f"[warn] latest.json 解析失败 ({e}), 将重建", file=sys.stderr)
    elif status != 404:
        print(f"[warn] 读取 latest.json 返回 status={status}, 将重建", file=sys.stderr)
    return {"version": "", "platforms": {}, "release_notes": ""}


def build_latest_json(existing: Dict, version: str, platform_key: str,
                      filename: str, size: int, sha256: str,
                      release_notes: Optional[str]) -> Dict:
    manifest = dict(existing)
    manifest["version"] = version
    manifest.setdefault("platforms", {})
    manifest["platforms"][platform_key] = {
        "filename": filename,
        "size": size,
        "sha256": sha256,
    }
    # release_notes: 优先 CLI 传入; 否则保留已有; 否则空串
    if release_notes is not None:
        manifest["release_notes"] = release_notes
    else:
        manifest.setdefault("release_notes", "")
    return manifest


# ── 主流程 ────────────────────────────────────────────────────────────────────
def main() -> int:
    p = argparse.ArgumentParser(
        description="手工上传 NSIS 安装包到腾讯云 COS 并更新 latest.json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("setup", nargs="?", default=None,
                   help="NSIS 安装包路径 (省略则自动查找最新构建产物)")
    p.add_argument("--brand", default=DEFAULT_BRAND,
                   help=f"品牌目录名 (默认 {DEFAULT_BRAND})")
    p.add_argument("--platform", default=DEFAULT_PLATFORM_KEY,
                   help=f"平台 key (默认 {DEFAULT_PLATFORM_KEY}, 对应 windows)")
    p.add_argument("--version", default=None,
                   help="版本号 X.Y.Z (省略则从文件名/tauri.conf.json 解析)")
    p.add_argument("--notes", default=None,
                   help="更新说明 (release_notes)")
    p.add_argument("--env-file", default=None,
                   help=f"COS .env 配置文件路径 (默认 {DEFAULT_ENV_FILE})")
    p.add_argument("--bundle-dir", default=DEFAULT_BUNDLE_DIR,
                   help=f"NSIS 构建产物搜索目录 (默认 {DEFAULT_BUNDLE_DIR})")
    p.add_argument("--dry-run", action="store_true",
                   help="只预览不实际上传")
    p.add_argument("--keep-name", action="store_true",
                   help="不重命名, 用原文件名作为 COS key (默认会规范化为 tupai_<ver>_x64-setup.exe)")
    args = p.parse_args()

    env_file = Path(args.env_file) if args.env_file else Path(DEFAULT_ENV_FILE)
    cfg = get_cos_config(env_file)

    # 1. 定位安装包
    if args.setup:
        setup_path = Path(args.setup)
    else:
        setup_path = find_nsis_bundle(Path(args.bundle_dir))
    if not setup_path or not setup_path.is_file():
        print(f"[err] 未找到 NSIS 安装包: {setup_path or '(自动查找失败)'}", file=sys.stderr)
        print(f"      搜索目录: {args.bundle_dir}", file=sys.stderr)
        print(f"      或通过参数指定: python upload_nsis_to_cos.py <path-to-setup.exe>", file=sys.stderr)
        return 1

    # 2. 解析版本
    version = args.version or parse_version_from_filename(setup_path.name) or get_version_from_tauri_conf()
    if not version:
        print(f"[err] 无法解析版本号, 请用 --version 指定", file=sys.stderr)
        return 2

    # 3. 规范化文件名
    if args.keep_name:
        cos_filename = setup_path.name
    else:
        cos_filename = normalize_filename(version, args.brand)

    size = setup_path.stat().st_size
    print(f"[info] 安装包: {setup_path}")
    print(f"[info] 版本:   {version}")
    print(f"[info] COS 文件名: {cos_filename}")
    print(f"[info] 大小:   {size} bytes ({size / 1048576:.2f} MB)")

    # 4. 计算 sha256
    print("[info] 计算 sha256 ...")
    sha256 = compute_sha256(setup_path)
    print(f"[info] sha256: {sha256}")

    # 5. 构造 COS key
    pkg_key = f"{cfg['prefix']}/{args.brand}/{args.platform}/{cos_filename}"
    manifest_key = f"{cfg['prefix']}/{args.brand}/latest.json"
    print(f"[info] COS 安装包 key:  {pkg_key}")
    print(f"[info] COS manifest key: {manifest_key}")
    print(f"[info] bucket: {cfg['bucket']}  region: {cfg['region']}  prefix: {cfg['prefix']}")

    if args.dry_run:
        print("\n[dry-run] 以上为预览, 跳过实际上传。")
        # 预览 manifest (dry-run 不拉取远端, 避免网络开销)
        manifest = build_latest_json({}, version, args.platform,
                                     cos_filename, size, sha256, args.notes)
        print("[dry-run] latest.json 预览:")
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
        return 0

    # 6. 上传安装包
    print(f"\n[upload] 读取安装包到内存 ({size} bytes) ...")
    body = setup_path.read_bytes()
    print(f"[upload] PUT {pkg_key} ...")
    t0 = time.time()
    ok, status, msg = cos_put_object(cfg, pkg_key, body,
                                     content_type="application/octet-stream",
                                     timeout=300)
    dur = int(time.time() - t0)
    if not ok:
        print(f"[upload] FAIL  status={status}  {dur}s  msg={msg}", file=sys.stderr)
        return 3
    print(f"[upload] OK  status={status}  {dur}s  ({size} bytes)")

    # 7. 更新 latest.json
    print(f"\n[manifest] 拉取现有 latest.json ...")
    existing = fetch_latest_json(cfg, args.brand)
    manifest = build_latest_json(existing, version, args.platform,
                                 cos_filename, size, sha256, args.notes)
    manifest_body = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    print(f"[manifest] PUT {manifest_key} ...")
    ok, status, msg = cos_put_object(cfg, manifest_key, manifest_body,
                                     content_type="application/json; charset=utf-8",
                                     timeout=30)
    if not ok:
        print(f"[manifest] FAIL  status={status}  msg={msg}", file=sys.stderr)
        return 4
    print(f"[manifest] OK  status={status}")

    # 8. 汇总
    ts = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")
    print(f"\n{'=' * 60}")
    print(f"[done] {ts}")
    print(f"  brand:    {args.brand}")
    print(f"  version:  {version}")
    print(f"  platform: {args.platform}")
    print(f"  file:     {cos_filename}")
    print(f"  size:     {size} bytes")
    print(f"  sha256:   {sha256}")
    download_host = f"https://{cfg['bucket']}.cos.{cfg['region']}.myqcloud.com"
    print(f"  url:      {download_host}/{pkg_key}")
    print(f"  manifest: {download_host}/{manifest_key}")
    print(f"{'=' * 60}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
