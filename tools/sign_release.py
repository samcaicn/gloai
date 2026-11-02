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
#
# 依赖：优先用 cryptography；没有则回退到内置纯 Python Ed25519（RFC 8032），
#       保证 CI/本地零第三方依赖也能签名（构建期一次签名，性能无要求）。

import hashlib
import json
import os
import sys

try:
    # Windows 默认 cp1252 下 print 中文会 UnicodeEncodeError，强制 UTF-8 + replace
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS_FILE = os.path.join(ROOT, "tools", "build_keys.json")

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519 as _crypto_ed25519
    HAVE_CRYPTO = True
except Exception:
    HAVE_CRYPTO = False


# ---------------------------------------------------------------- 纯 Python Ed25519 (RFC 8032)
_P = 2 ** 255 - 19
_Q = 2 ** 252 + 27742317777372353535851937790883648493


def _inv(x):
    return pow(x, _P - 2, _P)


_D = -121665 * _inv(121666) % _P
_I = pow(2, (_P - 1) // 4, _P)


def _xrecover(y):
    xx = (y * y - 1) * _inv(_D * y * y + 1)
    x = pow(xx, (_P + 3) // 8, _P)
    if (x * x - xx) % _P != 0:
        x = (x * _I) % _P
    if x % 2 != 0:
        x = _P - x
    return x


_By = 4 * _inv(5) % _P
_Bx = _xrecover(_By)
_B = (_Bx % _P, _By % _P, 1, (_Bx * _By) % _P)
_IDENT = (0, 1, 1, 0)


def _edwards_add(p1, p2):
    x1, y1, z1, t1 = p1
    x2, y2, z2, t2 = p2
    a = (y1 - x1) * (y2 - x2) % _P
    b = (y1 + x1) * (y2 + x2) % _P
    c = t1 * 2 * _D * t2 % _P
    dd = z1 * 2 * z2 % _P
    e = b - a
    f = dd - c
    g = dd + c
    h = b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _scalarmult(point, e):
    q = _IDENT
    for bit in bin(e)[2:]:
        q = _edwards_add(q, q)
        if bit == "1":
            q = _edwards_add(q, point)
    return q


def _point_compress(point):
    x, y, z, _t = point
    zi = _inv(z)
    x = x * zi % _P
    y = y * zi % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _secret_expand(secret):
    h = hashlib.sha512(secret).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= (1 << 254)
    return a, h[32:]


def pure_publickey(secret: bytes) -> bytes:
    a, _prefix = _secret_expand(secret)
    return _point_compress(_scalarmult(_B, a))


def pure_sign(secret: bytes, msg: bytes) -> bytes:
    a, prefix = _secret_expand(secret)
    A = _point_compress(_scalarmult(_B, a))
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), "little") % _Q
    R = _point_compress(_scalarmult(_B, r))
    h = int.from_bytes(hashlib.sha512(R + A + msg).digest(), "little") % _Q
    s = (r + h * a) % _Q
    return R + int.to_bytes(s, 32, "little")


def pure_verify(public: bytes, sig: bytes, msg: bytes) -> None:
    """纯 Python Ed25519 验签（--verify 用；失败抛 ValueError）。"""
    if len(public) != 32 or len(sig) != 64:
        raise ValueError("bad key/sig length")
    A = _decompress(public)
    Rs, S = sig[:32], sig[32:]
    R = _decompress(Rs)
    h = int.from_bytes(hashlib.sha512(Rs + public + msg).digest(), "little") % _Q
    left = _scalarmult(_B, int.from_bytes(S, "little"))
    right = _edwards_add(R, _scalarmult(A, h))
    if _point_compress(left) != _point_compress(right):
        raise ValueError("signature mismatch")


def _decompress(b: bytes):
    y = int.from_bytes(b, "little") & ((1 << 255) - 1)
    x_sign = b[31] >> 7
    xx = (y * y - 1) * _inv(_D * y * y + 1) % _P
    x = pow(xx, (_P + 3) // 8, _P)
    if (x * x - xx) % _P != 0:
        x = (x * _I) % _P
    if (x * x - xx) % _P != 0:
        raise ValueError("point decompression failed")
    if x % 2 != x_sign:
        x = _P - x
    return (x, y, 1, x * y % _P)


# ---------------------------------------------------------------- 密钥与文件
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


def load_verify_key() -> bytes:
    env = os.environ.get("BBT_VERIFY_KEY", "").strip()
    if env:
        return bytes.fromhex(env)
    with open(KEYS_FILE, "r", encoding="utf-8") as f:
        return bytes.fromhex(json.load(f)["UPDATER_VERIFY_KEY"])


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
    backend = "cryptography" if HAVE_CRYPTO else "pure-python(rfc8032)"

    if verify_only:
        if not os.path.exists(sig_path):
            print(f"FAIL: 签名文件不存在: {sig_path}", file=sys.stderr)
            sys.exit(2)
        pub = load_verify_key()
        with open(sig_path, "rb") as f:
            sig = f.read()
        digest = sha256_file(exe)
        if HAVE_CRYPTO:
            vk = _crypto_ed25519.Ed25519PublicKey.from_public_bytes(pub)
            vk.verify(sig, digest)
        else:
            pure_verify(pub, sig, digest)
        print(f"VERIFY OK: {exe} (backend={backend})")
        return

    sk_bytes = load_sign_key()
    digest = sha256_file(exe)
    if HAVE_CRYPTO:
        sk = _crypto_ed25519.Ed25519PrivateKey.from_private_bytes(sk_bytes)
        sig = sk.sign(digest)
        pub_hex = sk.public_key().public_bytes_raw().hex()
    else:
        sig = pure_sign(sk_bytes, digest)
        pub_hex = pure_publickey(sk_bytes).hex()
    with open(sig_path, "wb") as f:
        f.write(sig)
    print(f"SIGNED: {exe} -> {sig_path} ({len(sig)} bytes) [backend={backend}]")
    print(f"public key (应与 update.rs::UPDATER_VERIFY_KEY 一致): {pub_hex}")


if __name__ == "__main__":
    main()
