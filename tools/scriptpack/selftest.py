#!/usr/bin/env python3
"""WeAuto 话术包自测：round-trip + 全部拒收路径。纯标准库，CI 零依赖直接跑。

覆盖：
  1. FIPS-197 AES-256 ECB 已知答案测试（验证手写 AES 内核）
  1b. AES-256-GCM 已知答案测试（NIST 向量，钉死 CTR 起点与 GHASH）
  2. ECDSA P-256 域运算性质（验证手写曲线实现）
  3. 话术包 round-trip：签发 -> 验签 -> 解密 -> 内容一致
  4. 改一个密文字节     -> 拒收（GCM tag / 签名）
  5. 改 header 到期时间 -> 拒收（签名覆盖）
  6. 已过期的包         -> 拒收
  7. 绑机包换一台设备   -> 拒收
  8. 用错误私钥签名     -> 拒收（伪造）
  9. 改 signature 字节  -> 拒收
 10. SPKI 公钥 DER 结构自洽 + 坐标 round-trip（曾因前缀多一个 04 字节被
     OpenSSL/Android KeyFactory 拒收，Python 两头同源发现不了——CI 另有
     openssl 交叉验证兜底）
"""

import json
import os
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import packcrypto as pc  # noqa: E402

FAILED = []


def header_hlen(data: bytes) -> int:
    return struct.unpack_from("<H", data, 6)[0]


def check(name: str, cond: bool, detail: str = "") -> None:
    status = "PASS" if cond else "FAIL"
    print("  [%s] %s%s" % (status, name, (" — " + detail) if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def test_aes_kat() -> None:
    print("AES-256 ECB 已知答案测试（FIPS-197 C.3）")
    key = bytes(range(32))
    pt = bytes.fromhex("00112233445566778899aabbccddeeff")
    ct = pc._encrypt_block(pc._expand_key(key), pt)
    check("AES-256(0..31, 0011..eeff) == 8ea2b7ca...",
          ct.hex() == "8ea2b7ca516745bfeafc49904b496089", ct.hex())


def test_gcm_kat() -> None:
    """NIST SP 800-38D / McGrew-Viega GCM 已知答案测试（AES-256）。

    只自签自验永远发现不了 GCM 实现的 off-by-one——密钥流必须从 inc32(J0) 起，
    J0 只用于算 tag。曾写成 ctr = j0：空明文（TC14）恰好蒙对，多分组全错，
    Android JCE 一验就拒。这里用独立向量把 CTR 和 GHASH 都钉死。
    """
    print("AES-256-GCM 已知答案测试（NIST / McGrew-Viega TC14-TC15）")
    k = bytes(32)
    iv = bytes(12)
    out = pc.aes_gcm_encrypt(k, iv, b"", b"")
    check("TC14 空明文 tag == 530f8afb...", out[-16:].hex() == "530f8afbc74536b9a963b4f1c4cb738b",
          out[-16:].hex())
    out = pc.aes_gcm_encrypt(k, iv, bytes(16), b"")
    check("TC15 密文 == cea7403d...", out[:-16].hex() == "cea7403d4d606b6e074ec5d3baf39d18",
          out[:-16].hex())
    check("TC15 tag == d0d1c8a7...", out[-16:].hex() == "d0d1c8a799996bf0265b98b5d48ab919",
          out[-16:].hex())
    # AAD 只影响 tag，不影响密文 —— 钉死 GHASH 的 AAD 路径
    with_aad = pc.aes_gcm_encrypt(k, iv, bytes(16), b"header")
    check("AAD 不改变密文", with_aad[:-16] == out[:-16])
    check("AAD 改变 tag", with_aad[-16:] != out[-16:])
    check("带 AAD 的 tag 可被验过",
          pc.aes_gcm_decrypt(k, iv, with_aad, b"header") == bytes(16))


def test_ecdsa_domain() -> None:
    print("ECDSA P-256 域运算性质")
    G = pc.P256_G
    check("1*G == G", pc._pt_mul(1, G) == G)
    neg = (G[0], (pc.P256_P - G[1]) % pc.P256_P)
    check("(n-1)*G == -G", pc._pt_mul(pc.P256_N - 1, G) == neg)
    check("G + (-G) == 无穷远", pc._pt_add(G, neg) is None)
    check("n*G == 无穷远", pc._pt_mul(pc.P256_N, G) is None)
    check("2G + 3G == 5G", pc._pt_add(pc._pt_mul(2, G), pc._pt_mul(3, G)) == pc._pt_mul(5, G))
    x, y = G
    check("G 在曲线上", (y * y - (x * x * x + pc.P256_A * x + pc.P256_B)) % pc.P256_P == 0)


def sample_script(days: int = 365) -> dict:
    now = int(time.time())
    return {
        "pack_id": "wpk_selftest_v1",
        "name": "自测话术包",
        "version": 1,
        "author": "selftest",
        "issued_at": now,
        "expires_at": now + days * 86400,
        "min_app_version": "1.5.0",
        "system_prompt": "你是自测助手，只输出 JSON 数组。",
        "style": {"tone": "测试语气", "persona": "测试人设", "taboo": "不要解释"},
        "few_shot": [{"user": "在吗", "assistant": "在的，说吧"}],
        "params": {"temperature": 0.7, "candidates": 3},
        "relationship_hint": "测试关系",
    }


def test_pack_pipeline() -> None:
    print("话术包 round-trip")
    priv, point = pc.gen_keypair()
    master = bytes.fromhex(pc.TEST_MASTER_HEX)
    script = sample_script()
    data = pc.sign_pack(script, master, priv)
    dh = bytes.fromhex("ab" * 16)
    payload = pc.parse_and_decrypt(data, master, dh, int(time.time()), point)
    check("解密后 system_prompt 一致", payload["system_prompt"] == script["system_prompt"])
    check("解密后 style 一致", payload["style"] == script["style"])
    check("解密后 few_shot 一致", payload["few_shot"] == script["few_shot"])
    check("header 可单独读取", pc.read_header(data)["pack_id"] == "wpk_selftest_v1")

    print("拒收路径")
    bad = bytearray(data)
    bad[-40] ^= 0xFF  # 踩中 signature 区
    try:
        pc.parse_and_decrypt(bytes(bad), master, dh, int(time.time()), point)
        check("改签名字节被拒", False)
    except pc.PackCryptoError:
        check("改签名字节被拒", True)

    # 踩中密文区：header 之后偏移 8+hlen+4 起。定位到 payload 中段即可。
    bad = bytearray(data)
    mid = len(bad) // 2
    bad[mid] ^= 0x01
    try:
        pc.parse_and_decrypt(bytes(bad), master, dh, int(time.time()), point)
        check("改密文字节被拒", False)
    except pc.PackCryptoError:
        check("改密文字节被拒", True)

    # 改 header 的 expires_at（+10 年）再原样放回 -> 签名必须拒绝
    header = pc.read_header(data)
    bumped = {k: v for k, v in header.items() if not k.startswith("_")}
    bumped["expires_at"] = bumped["expires_at"] + 10 * 365 * 86400
    hbytes = json.dumps(bumped, ensure_ascii=False, separators=(",", ":"),
                        sort_keys=True).encode("utf-8")
    ver, flags = data[4], data[5]
    tail = data[8 + header_hlen(data):]  # payload_len 起的原文
    forged = pc.MAGIC + bytes([ver, flags]) + len(hbytes).to_bytes(2, "little") \
        + hbytes + tail
    try:
        pc.parse_and_decrypt(forged, master, dh, int(time.time()), point)
        check("改 header 到期时间被拒", False)
    except pc.PackCryptoError:
        check("改 header 到期时间被拒", True)

    # 过期包
    old = sample_script(days=-1)
    expired = pc.sign_pack(old, master, priv)
    try:
        pc.parse_and_decrypt(expired, master, dh, int(time.time()), point)
        check("过期包被拒", False)
    except pc.PackCryptoError as e:
        check("过期包被拒", True, str(e))

    # 绑机包：A 机签，B 机拒
    dh_a = bytes.fromhex("cd" * 16)
    bound = pc.sign_pack(sample_script(), master, priv, dh_a)
    payload = pc.parse_and_decrypt(bound, master, dh_a, int(time.time()), point)
    check("绑机包本机可解", payload["system_prompt"] == "你是自测助手，只输出 JSON 数组。")
    dh_b = bytes.fromhex("ef" * 16)
    try:
        pc.parse_and_decrypt(bound, master, dh_b, int(time.time()), point)
        check("绑机包换设备被拒", False)
    except pc.PackCryptoError as e:
        check("绑机包换设备被拒", True, str(e))

    # 伪造：另一把私钥签同一个 script
    priv2, _ = pc.gen_keypair()
    forged_pack = pc.sign_pack(sample_script(), master, priv2)
    try:
        pc.parse_and_decrypt(forged_pack, master, dh, int(time.time()), point)
        check("错误私钥签名被拒", False)
    except pc.PackCryptoError:
        check("错误私钥签名被拒", True)

    # v1.1：带 reply_quota 但不绑机的包必须拒收（次数=商品）
    q_script = sample_script()
    q_script["reply_quota"] = 100
    q_pack = pc.sign_pack(q_script, master, priv)
    try:
        pc.parse_and_decrypt(q_pack, master, dh, int(time.time()), point)
        check("带次数未绑机包被拒", False)
    except pc.PackCryptoError:
        check("带次数未绑机包被拒", True)
    # 反向：带次数且绑本机 → 必须放行
    q_pack2 = pc.sign_pack(q_script, master, priv, dh)
    try:
        pc.parse_and_decrypt(q_pack2, master, dh, int(time.time()), point)
        check("带次数绑机包可解", True)
    except pc.PackCryptoError as e:
        check("带次数绑机包可解", False, str(e))
    # header 里 reply_quota 可读
    check("header reply_quota=100", pc.read_header(q_pack2).get("reply_quota") == 100)

    # GCM round-trip 与 tamper
    key = os.urandom(32)
    nonce = os.urandom(12)
    ct = pc.aes_gcm_encrypt(key, nonce, b"hello wpk", b"aad")
    check("GCM round-trip", pc.aes_gcm_decrypt(key, nonce, ct, b"aad") == b"hello wpk")
    try:
        pc.aes_gcm_decrypt(key, nonce, ct[:-1] + bytes([ct[-1] ^ 0xFF]), b"aad")
        check("GCM tamper 被拒", False)
    except pc.PackCryptoError:
        check("GCM tamper 被拒", True)


def _der_walk(b: bytes, i: int = 0, end: int = None) -> int:
    """DER 长度走查：返回消耗到的偏移。任何长度声明与实际不符都会抛错。"""
    end = len(b) if end is None else end
    tag = b[i]
    i += 1
    n = b[i]
    i += 1
    if n & 0x80:
        k = n & 0x7F
        if k == 0 or k > 4:
            raise ValueError("bad DER long-form length")
        n = int.from_bytes(b[i:i + k], "big")
        i += k
    if i + n > end:
        raise ValueError("DER length overruns buffer")
    if tag in (0x30, 0x31):  # SEQUENCE / SET：递归
        j = i
        while j < i + n:
            j = _der_walk(b, j, i + n)
    return i + n


def test_spki_structure() -> None:
    print("SPKI 公钥 DER 结构")
    priv, point = pc.gen_keypair()
    spki = pc.spki_from_point(point)
    check("SPKI 总长 91（26 前缀 + 1 点标记 + 64 坐标）", len(spki) == 91, str(len(spki)))
    check("外层 SEQUENCE 长度自洽", spki[0] == 0x30 and spki[1] == 89)
    check("BIT STRING 长度 66（0 未用位 + 65 点）", spki[23] == 0x03 and spki[24] == 0x42)
    check("未用位字节为 0x00", spki[25] == 0x00)
    expect_point = b"\x04" + point[0].to_bytes(32, "big") + point[1].to_bytes(32, "big")
    check("点区（偏移 26 起）== 04||X||Y，无多余字节", spki[26:] == expect_point)
    try:
        _der_walk(spki)
        check("DER 长度走查完全消耗", True)
    except ValueError as e:
        check("DER 长度走查完全消耗", False, str(e))
    back = pc.point_from_spki(spki)
    check("SPKI 坐标 round-trip", back == point, "%s != %s" % (back, point))


def main() -> int:
    test_aes_kat()
    test_gcm_kat()
    test_ecdsa_domain()
    test_spki_structure()
    test_pack_pipeline()
    print()
    if FAILED:
        print("自测失败 %d 项：%s" % (len(FAILED), ", ".join(FAILED)))
        return 1
    print("全部通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
