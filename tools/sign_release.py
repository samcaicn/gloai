# -*- coding: utf-8 -*-
# tools/sign_release.py —— 发行版 EXE 的 Ed25519 签名（移植 weauto tools/build_release.py 第 8 步）。
#
# 用法：
#   python tools/sign_release.py <path-to-exe>            # 签名，生成 <exe>.sig（64 字节裸签名）
#   python tools/sign_release.py --verify <path-to-exe>   # 校验现有 .sig
#
# 密钥来源（优先级）：
#   1. 环境变量 BBT_SIGN_KEY —— Ed25519 私钥 64 hex 字符（CI 用 GitHub Secret）
#   2. tools/build_keys.json —— 本地构建用（已被 .gitignore 排除，禁止提交）
# 公钥（UPDATER_VERIFY_KEY）已编译期嵌入 src-tauri/src/update.rs，两端必须配对。
#
# 签名对象 = exe 文件的 SHA-256 摘要（与客户端 update.rs::verify_signature 一致）。

import hashlib
import json
import os
import sys

from cryptography.hazmat.primitives.asymmetric import ed25519

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS_FILE = os.path.join(ROOT, "tools", "build_keys.json")


def load_sign_key() -> bytes:
    env = os.environ.get("BBT_SIGN_KEY", "").strip()
    if env:
        return bytes.fromhex(env)
    if os.path.exists(KEYS_FILE):
        with open(KEYS_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
        if d.get("UPDATER_SIGN_KEY"):
            return bytes.fromhex(d["UPDATER_SIGN_KEY"])
    print("FAIL: 未找到签名私钥 —— 设置环境变量 BBT_SIGN_KEY（CI Secret）或提供 tools/build_keys.json", file=sys.stderr)
    sys.exit(2)


def sha256_file(path: str) -> bytes:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.digest()


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    verify_only = sys.argv[1] == "--verify"
    exe = sys.argv[2] if verify_only else sys.argv[1]
    if not os.path.exists(exe):
        print(f"FAIL: 文件不存在: {exe}", file=sys.stderr)
        sys.exit(2)
    sig_path = exe + ".sig"

    if verify_only:
        if not os.path.exists(sig_path):
            print(f"FAIL: 签名文件不存在: {sig_path}", file=sys.stderr)
            sys.exit(2)
        with open(KEYS_FILE, "r", encoding="utf-8") as f:
            pub_hex = json.load(f)["UPDATER_VERIFY_KEY"]
        vk = ed25519.Ed25519PublicKey.from_public_bytes(bytes.fromhex(pub_hex))
        with open(sig_path, "rb") as f:
            sig = f.read()
        vk.verify(sig, sha256_file(exe))
        print(f"VERIFY OK: {exe}")
        return

    sk = ed25519.Ed25519PrivateKey.from_private_bytes(load_sign_key())
    sig = sk.sign(sha256_file(exe))
    with open(sig_path, "wb") as f:
        f.write(sig)
    pub_hex = sk.public_key().public_bytes_raw().hex()
    print(f"SIGNED: {exe} -> {sig_path} ({len(sig)} bytes)")
    print(f"public key (应与 update.rs::UPDATER_VERIFY_KEY 一致): {pub_hex}")


if __name__ == "__main__":
    main()
