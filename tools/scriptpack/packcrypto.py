"""WeAuto 话术包（.wpk）密码学与文件格式——纯 Python 标准库实现。

设计文档：android/docs/scriptpack-spec.md（v1.0）。
Android 端逐字节一致的对应实现：
  android/app/src/main/java/com/jev/probe/core/scriptpack/AppPackCrypto.kt
  android/app/src/main/java/com/jev/probe/core/scriptpack/ScriptPack.kt

刻意不依赖任何第三方库（无 cryptography / pycryptodome），保证 GitHub CI
与任何 Python 3.8+ 环境零安装直接运行：

  - AES-256-GCM（手写 AES 块加密 + GHASH）
  - ECDSA P-256 签名/验签（手写域运算，RFC 6979 确定性 k）
  - HKDF-SHA256（hmac/hashlib）
"""

import base64
import hashlib
import hmac
import json
import os
import struct

# ---------------------------------------------------------------- 常量

MAGIC = b"WPK1"
FORMAT_VERSION = 1
FLAG_DEVICE_BINDING = 0x01
FLAG_ONLINE_ACTIVATION = 0x02
INFO_PREFIX = "weauto-scriptpack-v1|"

NONCE_LEN = 12
TAG_LEN = 16
KDF_SALT_LEN = 16
DEVICE_HASH_LEN = 16

# 测试用 master key（32B）。正式发行时由商户各自生成，Python 侧用
# --master-hex / PACK_MASTER_HEX 覆盖，Android 侧替换 AppPackCrypto.MASTER_OBFUSCATED。
# 注意：master 泄密不等于可伪造话术包——没有签名私钥，包连验签都过不了。
TEST_MASTER_HEX = "9f2b7c41d3e5a6081b4c9d2e7f0a3b5c8d6e4f2a1c0b9d8e7f6a5b4c3d2e1f0a"

# P-256 域参数（NIST FIPS 186-4 / SEC 2）
P256_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
P256_A = P256_P - 3
P256_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
P256_GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
P256_GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
P256_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
P256_G = (P256_GX, P256_GY)

# SPKI 前缀（EC / prime256v1 的 AlgorithmIdentifier + BIT STRING 头 + 未用位字节）。
# **末尾是 0x00（BIT STRING unused-bits），不是点标记 0x04**——点标记由
# spki_from_point 拼。历史 bug：前缀末尾多带一个 04，导致签发出的 SPKI 多一字节、
# 外层长度声明不符，OpenSSL / Android KeyFactory 全部拒收，而 Python 自测因
# 两头同源发现不了。改动此处必须同步 selftest 的 SPKI 结构断言与 CI 的
# openssl 交叉验证。
_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


class PackCryptoError(Exception):
    """解密/验签失败等密码学错误（消息不含任何密钥材料）。"""


# ---------------------------------------------------------------- 工具

def b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def device_hash_hex(device_id: str) -> str:
    """设备指纹 = SHA-256(DeviceId 稳定值) 前 16B 的 hex（32 字符）。
    与 Android 端 DeviceId.shortHash 完全一致，可互相粘贴使用。"""
    return hashlib.sha256(device_id.encode("utf-8")).hexdigest()[:32]


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def derive_key(master: bytes, pack_id: str, salt: bytes, device_hash: bytes) -> bytes:
    """AES-256 密钥 = HKDF-SHA256(master || device_hash, salt, info, 32)。
    device_hash 未绑设备时为 16B 全零（Android 端同理）。"""
    assert len(device_hash) == DEVICE_HASH_LEN
    return hkdf_sha256(
        ikm=master + device_hash,
        salt=salt,
        info=(INFO_PREFIX + pack_id).encode("utf-8"),
        length=32,
    )


# ---------------------------------------------------------------- AES-256-GCM

_SBOX = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
]
_RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40]


def _xtime(x: int) -> int:
    return ((x << 1) ^ 0x1B) & 0xFF if x & 0x80 else (x << 1)


def _expand_key(key: bytes):
    assert len(key) == 32, "AES-256 需要 32 字节密钥"
    nk, nr = 8, 14
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        t = list(w[i - 1])
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [_SBOX[b] for b in t]
            t[0] ^= _RCON[i // nk - 1]
        elif i % nk == 4:
            t = [_SBOX[b] for b in t]
        w.append([a ^ b for a, b in zip(w[i - nk], t)])
    return w


def _encrypt_block(w, block: bytes) -> bytes:
    nr = 14
    s = [list(block[4 * c:4 * c + 4]) for c in range(4)]  # s[c][r]

    def add_round_key(rnd: int) -> None:
        for c in range(4):
            for r in range(4):
                s[c][r] ^= w[rnd * 4 + c][r]

    add_round_key(0)
    for rnd in range(1, nr + 1):
        for c in range(4):
            for r in range(4):
                s[c][r] = _SBOX[s[c][r]]
        for r in range(1, 4):
            row = [s[c][r] for c in range(4)]
            row = row[r:] + row[:r]
            for c in range(4):
                s[c][r] = row[c]
        if rnd != nr:
            for c in range(4):
                a0, a1, a2, a3 = s[c]
                t = a0 ^ a1 ^ a2 ^ a3
                s[c] = [
                    a0 ^ t ^ _xtime(a0 ^ a1),
                    a1 ^ t ^ _xtime(a1 ^ a2),
                    a2 ^ t ^ _xtime(a2 ^ a3),
                    a3 ^ t ^ _xtime(a3 ^ a0),
                ]
        add_round_key(rnd)
    return bytes(s[c][r] for c in range(4) for r in range(4))


_GF_R = 0xE1000000000000000000000000000000


def _gf_mult(x: int, y: int) -> int:
    z, v = 0, y
    for i in range(127, -1, -1):
        if (x >> i) & 1:
            z ^= v
        v = (v >> 1) ^ _GF_R if v & 1 else v >> 1
    return z


def _ghash(h: int, aad: bytes, ct: bytes) -> int:
    def blocks(data: bytes):
        for i in range(0, len(data), 16):
            yield data[i:i + 16].ljust(16, b"\x00")

    y = 0
    for b in list(blocks(aad)) + list(blocks(ct)):
        y = _gf_mult(y ^ int.from_bytes(b, "big"), h)
    tail = struct.pack(">QQ", len(aad) * 8, len(ct) * 8)
    y = _gf_mult(y ^ int.from_bytes(tail, "big"), h)
    return y


def _inc32(block: bytes) -> bytes:
    n = (int.from_bytes(block[12:], "big") + 1) & 0xFFFFFFFF
    return block[:12] + n.to_bytes(4, "big")


def aes_gcm_encrypt(key: bytes, nonce: bytes, pt: bytes, aad: bytes) -> bytes:
    """返回 ct || tag(16B)。nonce 必须 12B。"""
    if len(nonce) != NONCE_LEN:
        raise PackCryptoError("nonce 长度必须为 12 字节")
    w = _expand_key(key)
    h = int.from_bytes(_encrypt_block(w, b"\x00" * 16), "big")
    j0 = nonce + b"\x00\x00\x00\x01"
    # 标准 GCM：密钥流从 inc32(J0) 开始，J0 只用于算 tag。
    # 曾在这里写成 ctr = j0（off-by-one），自签自验发现不了，只有外部实现（Android
    # JCE / Node crypto）才验得出来——NIST 向量与交叉验证已进 selftest 和 CI。
    ctr = _inc32(j0)
    ct = bytearray()
    for i in range(0, len(pt), 16):
        ks = _encrypt_block(w, ctr)
        chunk = pt[i:i + 16]
        ct += bytes(a ^ b for a, b in zip(chunk, ks))
        ctr = _inc32(ctr)
    ct = bytes(ct)
    s = _ghash(h, aad, ct)
    tag = (s ^ int.from_bytes(_encrypt_block(w, j0), "big")).to_bytes(16, "big")
    return ct + tag


def aes_gcm_decrypt(key: bytes, nonce: bytes, ct_tag: bytes, aad: bytes) -> bytes:
    """校验 tag 后返回明文；任何不符抛 PackCryptoError。"""
    if len(nonce) != NONCE_LEN or len(ct_tag) < TAG_LEN:
        raise PackCryptoError("密文长度非法")
    ct, tag = ct_tag[:-TAG_LEN], ct_tag[-TAG_LEN:]
    w = _expand_key(key)
    h = int.from_bytes(_encrypt_block(w, b"\x00" * 16), "big")
    j0 = nonce + b"\x00\x00\x00\x01"
    s = _ghash(h, aad, ct)
    expect = (s ^ int.from_bytes(_encrypt_block(w, j0), "big")).to_bytes(16, "big")
    if not hmac.compare_digest(expect, tag):
        raise PackCryptoError("GCM tag 校验失败（密钥不匹配 / 包被改动 / 设备不匹配）")
    ctr = _inc32(j0)
    pt = bytearray()
    for i in range(0, len(ct), 16):
        ks = _encrypt_block(w, ctr)
        chunk = ct[i:i + 16]
        pt += bytes(a ^ b for a, b in zip(chunk, ks))
        ctr = _inc32(ctr)
    return bytes(pt)


# ---------------------------------------------------------------- ECDSA P-256

def _inv(a: int, m: int) -> int:
    return pow(a, m - 2, m)


def _pt_add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % P256_P == 0:
        return None
    if p1 == p2:
        lam = (3 * x1 * x1 + P256_A) * _inv(2 * y1, P256_P) % P256_P
    else:
        lam = (y2 - y1) * _inv(x2 - x1, P256_P) % P256_P
    x3 = (lam * lam - x1 - x2) % P256_P
    return (x3, (lam * (x1 - x3) - y1) % P256_P)


def _pt_mul(k: int, point):
    result = None
    addend = point
    while k:
        if k & 1:
            result = _pt_add(result, addend)
        addend = _pt_add(addend, addend)
        k >>= 1
    return result


def _rfc6979_k(priv: int, z: int) -> int:
    x = priv.to_bytes(32, "big")
    h1 = z.to_bytes(32, "big")
    v = b"\x01" * 32
    k = b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        cand = int.from_bytes(v, "big")
        if 1 <= cand < P256_N:
            return cand
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def _der_int(n: int) -> bytes:
    b = n.to_bytes((n.bit_length() + 8) // 8 or 1, "big")
    return b"\x02" + bytes([len(b)]) + b


def der_signature(r: int, s: int) -> bytes:
    body = _der_int(r) + _der_int(s)
    return b"\x30" + bytes([len(body)]) + body


def der_parse_signature(sig: bytes):
    if not sig or sig[0] != 0x30:
        raise PackCryptoError("签名不是 DER SEQUENCE")
    body = sig[2:]
    if sig[1] != len(body) or body[0] != 0x02:
        raise PackCryptoError("签名 DER 结构非法")
    rlen = body[1]
    r = int.from_bytes(body[2:2 + rlen], "big")
    rest = body[2 + rlen:]
    if rest[0] != 0x02:
        raise PackCryptoError("签名 DER 结构非法（缺 s）")
    slen = rest[1]
    s = int.from_bytes(rest[2:2 + slen], "big")
    return r, s


def spki_from_point(point) -> bytes:
    return _SPKI_PREFIX + b"\x04" + point[0].to_bytes(32, "big") + point[1].to_bytes(32, "big")


def point_from_spki(spki: bytes):
    if not spki.startswith(_SPKI_PREFIX):
        raise PackCryptoError("公钥不是 prime256v1 SPKI")
    raw = spki[len(_SPKI_PREFIX):]  # 04||X||Y（点标记由 spki_from_point 拼在这里）
    if len(raw) != 65 or raw[0] != 0x04:
        raise PackCryptoError("公钥点格式非法")
    return (int.from_bytes(raw[1:33], "big"), int.from_bytes(raw[33:65], "big"))


def ecdsa_sign(priv: int, msg: bytes) -> bytes:
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    while True:
        k = _rfc6979_k(priv, z)
        r = _pt_mul(k, P256_G)[0] % P256_N
        if r == 0:
            continue
        s = _inv(k, P256_N) * (z + r * priv) % P256_N
        if s == 0:
            continue
        return der_signature(r, s)


def ecdsa_verify(pub_point, msg: bytes, sig: bytes) -> bool:
    try:
        r, s = der_parse_signature(sig)
        if not (1 <= r < P256_N and 1 <= s < P256_N):
            return False
        z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
        w = _inv(s, P256_N)
        point = _pt_add(_pt_mul(z * w % P256_N, P256_G), _pt_mul(r * w % P256_N, pub_point))
        return point is not None and point[0] % P256_N == r
    except PackCryptoError:
        return False
    except Exception:
        return False


# ---------------------------------------------------------------- .wpk 装配/解析

def build_header(script: dict, salt: bytes, nonce: bytes, device_hash_hexes) -> dict:
    return {
        "pack_id": script["pack_id"],
        "name": script.get("name", script["pack_id"]),
        "version": int(script.get("version", 1)),
        "author": script.get("author", ""),
        "issued_at": int(script["issued_at"]),
        "expires_at": int(script["expires_at"]),
        "device_hashes": list(device_hash_hexes or []),
        "kdf_salt": b64url_encode(salt),
        "nonce": b64url_encode(nonce),
        "payload_sha256": b64url_encode(sha256(_canonical_payload(script))),
        "min_app_version": script.get("min_app_version", "0.0.0"),
        # 离线授权：回复次数额度（0/缺省 = 纯话术包不带次数）。header 被 ECDSA 签名覆盖，
        # 后台签发后不可篡改；Worker 激活时只读 header，无需解密 payload。
        "reply_quota": int(script.get("reply_quota", 0) or 0),
    }


def _canonical_payload(script: dict) -> bytes:
    """参与摘要/加密的 payload 字节：固定字段序的紧凑 JSON（ensure_ascii=False）。"""
    payload = {
        "system_prompt": script["system_prompt"],
        "style": script.get("style", {}),
        "few_shot": script.get("few_shot", []),
        "params": script.get("params", {}),
        "relationship_hint": script.get("relationship_hint", ""),
        "expires_at": int(script["expires_at"]),
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sign_pack(script: dict, master: bytes, priv: int, device_hash: bytes = None) -> bytes:
    """script.json 内容 + master + 签名私钥 -> .wpk 字节。

    device_hash：绑一台设备时传该设备的 16B hash（见 device_hash_hex），
    header.device_hashes 记其 hex；不传 = 不绑设备（任何设备可导入）。
    v1.0 语义：一个包最多绑一台设备，多设备就签多个包。
    """
    salt = os.urandom(KDF_SALT_LEN)
    nonce = os.urandom(NONCE_LEN)
    bound = device_hash is not None
    if bound and len(device_hash) != DEVICE_HASH_LEN:
        raise PackCryptoError("device_hash 必须为 16 字节")
    header = build_header(script, salt, nonce, [device_hash.hex()] if bound else [])
    flags = FLAG_DEVICE_BINDING if bound else 0
    if script.get("requires_online_activation"):
        flags |= FLAG_ONLINE_ACTIVATION

    header_bytes = json.dumps(header, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    payload = _canonical_payload(script)
    key = derive_key(master, header["pack_id"], salt,
                     device_hash if bound else bytes(DEVICE_HASH_LEN))
    # payload_len = 密文区字节数（= 明文 + 16B tag）；AAD = 文件中密文之前的全部字节（含该字段）
    aad = MAGIC + struct.pack("<BBH", FORMAT_VERSION, flags, len(header_bytes)) \
        + header_bytes + struct.pack("<I", len(payload) + TAG_LEN)
    ct_tag = aes_gcm_encrypt(key, nonce, payload, aad)
    sig = ecdsa_sign(priv, aad + ct_tag)

    return aad + ct_tag + struct.pack("<H", len(sig)) + sig


def read_header(data: bytes) -> dict:
    """只解明文头（导入列表/到期展示用，不验签不解密）。"""
    if len(data) < 8 or data[:4] != MAGIC:
        raise PackCryptoError("不是 .wpk 文件（magic 不匹配）")
    ver, flags, hlen = struct.unpack_from("<BBH", data, 4)
    if ver != FORMAT_VERSION:
        raise PackCryptoError(f"不支持的格式版本 {ver}")
    if len(data) < 8 + hlen:
        raise PackCryptoError("文件被截断（header）")
    header = json.loads(data[8:8 + hlen].decode("utf-8"))
    header["_flags"] = flags
    return header


def parse_and_decrypt(data: bytes, master: bytes, device_hash: bytes, now_ts: int,
                      pub_point=None) -> dict:
    """完整校验链：magic/版本 -> 长度 -> 签名 -> 设备 -> 到期 -> 解密 -> 双写到期/摘要。
    成功返回 payload dict；任何一步失败抛 PackCryptoError。"""
    if pub_point is None:
        raise PackCryptoError("缺少验签公钥")
    header = read_header(data)
    ver, flags, hlen = struct.unpack_from("<BBH", data, 4)
    header_bytes = data[8:8 + hlen]
    off = 8 + hlen
    if len(data) < off + 4:
        raise PackCryptoError("文件被截断（payload_len）")
    (payload_len,) = struct.unpack_from("<I", data, off)
    off += 4
    if len(data) < off + payload_len + 2:
        raise PackCryptoError("文件被截断（ciphertext/sig）")
    ct_tag = data[off:off + payload_len]
    off += payload_len
    (sig_len,) = struct.unpack_from("<H", data, off)
    off += 2
    if len(data) < off + sig_len:
        raise PackCryptoError("文件被截断（signature）")
    sig = data[off:off + sig_len]

    aad = data[:8 + hlen + 4]
    if not ecdsa_verify(pub_point, aad + ct_tag, sig):
        raise PackCryptoError("签名校验失败（伪造或改动的包）")

    device_hashes = header.get("device_hashes", [])
    dh = device_hash if device_hashes else bytes(DEVICE_HASH_LEN)
    if device_hashes and device_hash.hex() not in device_hashes:
        raise PackCryptoError("设备不在允许列表（绑机话术包）")
    if now_ts >= header["expires_at"]:
        raise PackCryptoError("话术包已到期")

    salt = b64url_decode(header["kdf_salt"])
    nonce = b64url_decode(header["nonce"])
    key = derive_key(master, header["pack_id"], salt, dh)
    pt = aes_gcm_decrypt(key, nonce, ct_tag, aad)
    if sha256(pt).hex() != header["payload_sha256"] \
            and b64url_encode(sha256(pt)) != header["payload_sha256"]:
        raise PackCryptoError("payload 摘要不符")
    payload = json.loads(pt.decode("utf-8"))
    if int(payload.get("expires_at", 0)) != int(header["expires_at"]):
        raise PackCryptoError("header 与 payload 的 expires_at 不一致")
    if now_ts >= payload["expires_at"]:
        raise PackCryptoError("话术包已到期（payload）")
    payload["_header"] = header
    return payload


# ---------------------------------------------------------------- 密钥对生成

def gen_keypair():
    """返回 (priv_int, point)。私钥由 os.urandom 生成，仅存在调用方内存。"""
    while True:
        priv = int.from_bytes(os.urandom(32), "big")
        if 1 <= priv < P256_N:
            return priv, _pt_mul(priv, P256_G)
