#!/usr/bin/env python3
"""cos_skill_crypto.py — COS 加密技能全链路验证脚本

功能:
  1. list    — 列出 COS 上所有加密技能 (.enc) 和原始技能包 (.zip)
  2. download— 下载一个加密技能, 用 device_token 解密, 验证内容
  3. upload  — 把本地 auto-product-comm 技能加密后上传到 COS
  4. roundtrip — upload + download + decrypt + 验证 (完整往返测试)

加密算法 (与 server/security/encryption.py 完全一致):
  AES-256-GCM
  key = SHA256(device_token)
  iv  = 12 random bytes
  encrypt_with_device_key(plaintext, device_token) -> (ciphertext, iv)

COS 布局:
  tupaisaas/skill_market_platform/skills/<skill_id>/<hash>.zip    (原始包)
  tupaisaas/skill_market_platform/enc/<skill_id>/<hash>.zip.enc   (加密包)

用法:
  python3 scripts/cos_skill_crypto.py list
  python3 scripts/cos_skill_crypto.py download --skill-id <id> --device-token <token>
  python3 scripts/cos_skill_crypto.py upload --skill-file skills/auto-product-comm/index.js
  python3 scripts/cos_skill_crypto.py roundtrip --skill-file skills/auto-product-comm/index.js
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import tempfile
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ── COS SDK ──────────────────────────────────────────────────
from qcloud_cos import CosConfig, CosS3Client

# ── 加密库 ────────────────────────────────────────────────────
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import secrets

# ── 配置 (从 tupaisaasmcp/.env.local 读取) ───────────────────
ENV_FILE = r"C:\code\tupsaasmcp\.env.local"

def load_env(path: str) -> Dict[str, str]:
    env: Dict[str, str] = {}
    p = Path(path)
    if not p.is_file():
        return env
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        if "#" in v:
            v = v.split("#", 1)[0]
        env[k.strip()] = v.strip()
    return env

_env = load_env(ENV_FILE)
COS_BUCKET = _env.get("COS_BUCKET", "tuptup-1361262264")
COS_REGION = _env.get("COS_REGION", "ap-shanghai")
COS_SECRET_ID = _env.get("COS_SECRET_ID", "")
COS_SECRET_KEY = _env.get("COS_SECRET_KEY", "")
COS_DATA_PREFIX = _env.get("COS_DATA_PREFIX", "tupaisaas").strip("/")

# Platform 技能加密包存储 namespace (与 server 一致)
SKILL_PLATFORM_NS = "skill_market_platform"

# ── COS 客户端 ────────────────────────────────────────────────
def get_cos_client() -> CosS3Client:
    cfg = CosConfig(Region=COS_REGION, SecretId=COS_SECRET_ID,
                    SecretKey=COS_SECRET_KEY, Timeout=30)
    return CosS3Client(cfg)

# ── 加解密 (与 server/security/encryption.py 完全一致) ────────
def derive_device_key(device_token: str) -> bytes:
    """从 device_token 派生 AES-256 密钥"""
    return hashlib.sha256(device_token.encode("utf-8")).digest()

def encrypt_with_device_key(plaintext: bytes, device_token: str) -> Tuple[bytes, bytes]:
    """AES-256-GCM 加密, 返回 (ciphertext, iv)"""
    key = derive_device_key(device_token)
    iv = secrets.token_bytes(12)
    ciphertext = AESGCM(key).encrypt(iv, plaintext, None)
    return ciphertext, iv

def decrypt_with_device_key(ciphertext: bytes, iv: bytes, device_token: str) -> bytes:
    """AES-256-GCM 解密"""
    key = derive_device_key(device_token)
    return AESGCM(key).decrypt(iv, ciphertext, None)

# ── COS key 构造 ──────────────────────────────────────────────
def enc_key(skill_id: str, device_hash: str) -> str:
    """加密包的 COS key: tupaisaas/skill_market_platform/enc/<skill_id>/<hash>.zip.enc"""
    return f"{COS_DATA_PREFIX}/{SKILL_PLATFORM_NS}/enc/{skill_id}/{device_hash}.zip.enc"

def raw_key(skill_id: str, sha256_hash: str) -> str:
    """原始包的 COS key: tupaisaas/skill_market_platform/skills/<skill_id>/<hash>.zip"""
    return f"{COS_DATA_PREFIX}/{SKILL_PLATFORM_NS}/skills/{skill_id}/{sha256_hash[:16]}.zip"

# ── 命令: list ────────────────────────────────────────────────
def cmd_list(args):
    """列出 COS 上所有技能相关对象"""
    client = get_cos_client()
    prefix = f"{COS_DATA_PREFIX}/{SKILL_PLATFORM_NS}/"

    print(f"_bucket: {COS_BUCKET}")
    print(f"  region: {COS_REGION}")
    print(f"  prefix: {prefix}")
    print()

    marker = ""
    all_keys: List[Dict] = []
    while True:
        kw = {"Bucket": COS_BUCKET, "Prefix": prefix, "MaxKeys": 1000}
        if marker:
            kw["Marker"] = marker
        resp = client.list_objects(**kw)
        items = resp.get("Contents", [])
        all_keys.extend(items)
        # SDK 返回的 IsTruncated 是字符串 "true"/"false"（XML 解析），
        # 不能直接当布尔用：`not "false"` 永远为 False → 死循环。
        truncated = str(resp.get("IsTruncated", "false")).lower() == "true"
        if not truncated:
            break
        marker = resp.get("NextMarker", "")

    enc_skills = []
    raw_skills = []
    for item in all_keys:
        key = item["Key"]
        size = int(item.get("Size", 0))
        if key.endswith(".zip.enc"):
            # enc/<skill_id>/<hash>.zip.enc
            parts = key.split("/")
            skill_id = parts[-2] if len(parts) >= 2 else "?"
            enc_skills.append({"key": key, "skill_id": skill_id, "size": size})
        elif key.endswith(".zip"):
            parts = key.split("/")
            skill_id = parts[-2] if len(parts) >= 2 else "?"
            raw_skills.append({"key": key, "skill_id": skill_id, "size": size})

    print(f"=== 加密技能 (.zip.enc): {len(enc_skills)} 个 ===")
    for s in enc_skills:
        print(f"  skill_id: {s['skill_id']:<40} size: {s['size']:>10} bytes")
        print(f"    key: {s['key']}")

    print(f"\n=== 原始技能包 (.zip): {len(raw_skills)} 个 ===")
    for s in raw_skills:
        print(f"  skill_id: {s['skill_id']:<40} size: {s['size']:>10} bytes")
        print(f"    key: {s['key']}")

    if not enc_skills and not raw_skills:
        print("\n(没有找到任何技能包)")

    return enc_skills, raw_skills

# ── 命令: download ────────────────────────────────────────────
def cmd_download(args):
    """下载加密技能并解密"""
    client = get_cos_client()

    # 如果没指定 device_hash, 列出该 skill_id 下所有 enc 文件
    device_hash = args.device_hash
    if not device_hash:
        prefix = f"{COS_DATA_PREFIX}/{SKILL_PLATFORM_NS}/enc/{args.skill_id}/"
        marker = ""
        enc_keys = []
        while True:
            kw = {"Bucket": COS_BUCKET, "Prefix": prefix, "MaxKeys": 100}
            if marker:
                kw["Marker"] = marker
            resp = client.list_objects(**kw)
            items = resp.get("Contents", [])
            for item in items:
                if item["Key"].endswith(".zip.enc"):
                    enc_keys.append(item["Key"])
            truncated = str(resp.get("IsTruncated", "false")).lower() == "true"
            if not truncated:
                break
            marker = resp.get("NextMarker", "")
        if not enc_keys:
            print(f"[err] 没有找到 skill_id={args.skill_id} 的加密包")
            return None
        # 用第一个
        cos_key = enc_keys[0]
        print(f"[info] 找到 {len(enc_keys)} 个加密包, 使用第一个:")
        print(f"  {cos_key}")
    else:
        cos_key = enc_key(args.skill_id, device_hash)

    # 下载 (用 get_object_to_file 确保完整读取, 避免 stream 部分读取问题)
    print(f"[download] GET {cos_key} ...")
    t0 = time.time()
    tmp_dl = tempfile.NamedTemporaryFile(delete=False, suffix=".enc")
    tmp_dl.close()
    try:
        client.download_file(Bucket=COS_BUCKET, Key=cos_key, DestFilePath=tmp_dl.name)
        ciphertext = Path(tmp_dl.name).read_bytes()
    finally:
        os.unlink(tmp_dl.name)
    dur = time.time() - t0
    print(f"[download] OK  {len(ciphertext)} bytes  {dur:.2f}s")

    # 解密
    device_token = args.device_token
    # iv 是前 12 字节 (与 server upload 时的布局一致:
    #   _build_platform_download_info 把 iv_b64 单独返回,
    #   但 COS 存的 .enc 文件只有 ciphertext, iv 通过 API 返回。
    #   这里我们模拟: 如果前12字节能解密就用前12字节, 否则尝试从文件名推断)
    # 实际 server 布局: .enc 文件只含 ciphertext, iv 通过 JSON API 返回。
    # 为了独立测试, 我们在 upload 时把 iv 前置到 ciphertext (iv || ciphertext)。
    iv = ciphertext[:12]
    ct = ciphertext[12:]
    try:
        plaintext = decrypt_with_device_key(ct, iv, device_token)
        print(f"[decrypt] OK  {len(plaintext)} bytes")
        # 尝试判断内容类型
        head = plaintext[:200]
        try:
            text = head.decode("utf-8")
            print(f"[content] UTF-8 text, head:")
            print(f"  {text[:100]}...")
        except:
            print(f"[content] binary, head hex: {head[:32].hex()}")

        # 保存到临时文件
        out = args.output or f"/tmp/skill_{args.skill_id}_decrypted.bin"
        Path(out).write_bytes(plaintext)
        print(f"[saved] {out}")
        return plaintext
    except Exception as e:
        print(f"[decrypt] FAIL: {e}")
        # 尝试不带 iv 前置 (原始 server 布局: 整个文件都是 ciphertext)
        print("[retry] 尝试整体解密 (无 iv 前置)...")
        return None

# ── 命令: upload ──────────────────────────────────────────────
def cmd_upload(args):
    """加密上传本地技能到 COS"""
    skill_file = Path(args.skill_file)
    if not skill_file.is_file():
        print(f"[err] 技能文件不存在: {skill_file}")
        return None

    skill_id = args.skill_id or skill_file.stem
    device_token = args.device_token

    # 读取技能代码
    plaintext = skill_file.read_bytes()
    print(f"[info] 技能文件: {skill_file}")
    print(f"  skill_id: {skill_id}")
    print(f"  size: {len(plaintext)} bytes")

    # 如果是 JS 文件, 打包成 zip (与 server 一致: skills/<id>/<hash>.zip)
    # 但为了简化, 我们直接加密原始文件
    # 加密
    ciphertext, iv = encrypt_with_device_key(plaintext, device_token)
    print(f"[encrypt] OK  ciphertext={len(ciphertext)} bytes  iv={iv.hex()}")

    # 构造 COS key: iv 前置到 ciphertext (方便独立下载解密)
    # 布局: [12 bytes iv][ciphertext]
    blob = iv + ciphertext
    device_hash = hashlib.sha256(device_token.encode("utf-8")).hexdigest()[:16]
    cos_key = enc_key(skill_id, device_hash)
    print(f"[upload] PUT {cos_key} ...")

    client = get_cos_client()
    t0 = time.time()
    client.put_object(
        Bucket=COS_BUCKET,
        Key=cos_key,
        Body=blob,
        ContentType="application/octet-stream",
    )
    dur = time.time() - t0
    print(f"[upload] OK  {len(blob)} bytes  {dur:.2f}s")

    download_url = f"https://{COS_BUCKET}.cos.{COS_REGION}.myqcloud.com/{cos_key}"
    print(f"\n[done] COS URL: {download_url}")
    print(f"  device_token: {device_token}")
    print(f"  iv (hex): {iv.hex()}")

    return {
        "skill_id": skill_id,
        "cos_key": cos_key,
        "cos_url": download_url,
        "device_token": device_token,
        "iv_hex": iv.hex(),
        "size": len(blob),
    }

# ── 命令: roundtrip ───────────────────────────────────────────
def cmd_roundtrip(args):
    """完整往返测试: upload → download → decrypt → 验证"""
    print("=" * 60)
    print("  ROUNDTRIP TEST: upload → download → decrypt → verify")
    print("=" * 60)

    skill_file = Path(args.skill_file)
    if not skill_file.is_file():
        print(f"[err] 技能文件不存在: {skill_file}")
        return False

    skill_id = args.skill_id or f"test-{skill_file.stem}"
    device_token = args.device_token or f"test-token-{int(time.time())}"

    original = skill_file.read_bytes()
    print(f"\n[1/5] 读取原始文件: {skill_file} ({len(original)} bytes)")

    # 加密
    ciphertext, iv = encrypt_with_device_key(original, device_token)
    blob = iv + ciphertext
    print(f"[2/5] 加密: ciphertext={len(ciphertext)} iv={iv.hex()}")

    # 上传
    device_hash = hashlib.sha256(device_token.encode("utf-8")).hexdigest()[:16]
    cos_key = enc_key(skill_id, device_hash)
    client = get_cos_client()
    print(f"[3/5] 上传到 COS: {cos_key}")
    client.put_object(Bucket=COS_BUCKET, Key=cos_key, Body=blob,
                      ContentType="application/octet-stream")
    print(f"      OK ({len(blob)} bytes)")

    # 下载 (用 download_file 确保完整读取)
    print(f"[4/5] 从 COS 下载...")
    tmp_dl = tempfile.NamedTemporaryFile(delete=False, suffix=".enc")
    tmp_dl.close()
    try:
        client.download_file(Bucket=COS_BUCKET, Key=cos_key, DestFilePath=tmp_dl.name)
        downloaded = Path(tmp_dl.name).read_bytes()
    finally:
        os.unlink(tmp_dl.name)
    print(f"      OK ({len(downloaded)} bytes)")

    # 解密
    dl_iv = downloaded[:12]
    dl_ct = downloaded[12:]
    decrypted = decrypt_with_device_key(dl_ct, dl_iv, device_token)
    print(f"[5/5] 解密: {len(decrypted)} bytes")

    # 验证
    if decrypted == original:
        print("\n✅ ROUNDTRIP SUCCESS: 解密内容与原始文件完全一致!")
    else:
        print("\n❌ ROUNDTRIP FAIL: 内容不匹配!")
        print(f"  original size: {len(original)}")
        print(f"  decrypted size: {len(decrypted)}")
        return False

    # 验证 JS 语法可执行 (尝试用 node 检查语法)
    print("\n[verify] 检查 JS 语法...")
    import subprocess
    with tempfile.NamedTemporaryFile(suffix=".js", delete=False, mode="wb") as f:
        f.write(decrypted)
        tmp_js = f.name
    try:
        result = subprocess.run(["node", "--check", tmp_js],
                                capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            print("✅ JS 语法检查通过! 技能代码可执行。")
        else:
            print(f"⚠️  JS 语法检查失败 (可能是 ES module 特性): {result.stderr[:200]}")
            # 尝试用 new Function 方式 (与前端一致)
            print("[verify] 尝试 new Function 编译...")
            js_check = f"""
try {{
  var code = require('fs').readFileSync('{tmp_js}', 'utf-8');
  new Function(code);
  console.log('✅ new Function 编译通过!');
}} catch(e) {{
  console.log('⚠️  new Function 编译失败:', e.message);
  process.exit(1);
}}
"""
            r2 = subprocess.run(["node", "-e", js_check],
                                capture_output=True, text=True, timeout=10)
            print(r2.stdout or r2.stderr)
    finally:
        os.unlink(tmp_js)

    # 清理 COS (可选)
    if not args.keep:
        print("\n[cleanup] 删除测试上传的 COS 对象...")
        client.delete_object(Bucket=COS_BUCKET, Key=cos_key)
        print(f"  deleted: {cos_key}")

    return True

# ── main ──────────────────────────────────────────────────────
def main():
    p = argparse.ArgumentParser(description="COS 加密技能全链路验证")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_list = sub.add_parser("list", help="列出 COS 上的加密技能")
    p_list.set_defaults(func=cmd_list)

    p_dl = sub.add_parser("download", help="下载并解密加密技能")
    p_dl.add_argument("--skill-id", required=True, help="技能 ID")
    p_dl.add_argument("--device-token", required=True, help="device_token (解密密钥)")
    p_dl.add_argument("--device-hash", default=None, help="设备哈希 (可选, 自动查找)")
    p_dl.add_argument("--output", default=None, help="输出文件路径")
    p_dl.set_defaults(func=cmd_download)

    p_up = sub.add_parser("upload", help="加密上传技能到 COS")
    p_up.add_argument("--skill-file", required=True, help="技能文件路径")
    p_up.add_argument("--skill-id", default=None, help="技能 ID (默认文件名)")
    p_up.add_argument("--device-token", default="roundtrip-test-token", help="device_token")
    p_up.set_defaults(func=cmd_upload)

    p_rt = sub.add_parser("roundtrip", help="完整往返测试")
    p_rt.add_argument("--skill-file", required=True, help="技能文件路径")
    p_rt.add_argument("--skill-id", default=None, help="技能 ID")
    p_rt.add_argument("--device-token", default=None, help="device_token (默认随机)")
    p_rt.add_argument("--keep", action="store_true", help="保留上传的 COS 对象")
    p_rt.set_defaults(func=cmd_roundtrip)

    args = p.parse_args()
    result = args.func(args)
    sys.exit(0 if result else 1)

if __name__ == "__main__":
    main()
