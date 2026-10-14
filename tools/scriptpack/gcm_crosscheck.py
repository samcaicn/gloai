#!/usr/bin/env python3
"""GCM 交叉验证驱动：Python 手写实现 <-> Node/openssl 实现，双向比对。

为什么需要它：selftest 里的 NIST 向量是"死"的，只能防回归；这里用随机输入
和**完全独立的** Node crypto 实现逐字节比对，能抓出向量没覆盖到的偏差
（历史上抓到过一次：密钥流起点写成 J0 而非 inc32(J0)）。

用法：
    python3 gcm_crosscheck.py            # 需要 PATH 里有 node
    python3 gcm_crosscheck.py --node /path/to/node

退出码 0 = 双向一致；1 = 不一致或 node 不可用（CI 里让它红）。
"""
import json
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import packcrypto as pc  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
JS = os.path.join(HERE, "gcm_crosscheck.js")
ROUNDS = 12


def find_node(explicit=None):
    if explicit:
        return explicit
    for c in ("node", "nodejs"):
        p = shutil.which(c)
        if p:
            return p
    return None


def node_run(node, mode, payload):
    r = subprocess.run(
        [node, JS, mode],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=60,
    )
    if r.returncode != 0:
        raise RuntimeError("node %s 失败: %s" % (mode, r.stderr.strip()))
    return r.stdout.strip()


def main() -> int:
    explicit = None
    args = sys.argv[1:]
    if args:
        if args[0] == "--node" and len(args) > 1:
            explicit = args[1]
        elif not args[0].startswith("-"):
            explicit = args[0]
    node = find_node(explicit)
    if not node:
        print("gcm_crosscheck: 找不到 node，跳过（CI 请安装 node）")
        return 1

    bad = []
    for i in range(ROUNDS):
        key = os.urandom(32)
        nonce = os.urandom(12)
        # 长度覆盖：空 / 不满块 / 整块 / 多块
        pt = os.urandom([0, 1, 15, 16, 17, 64, 200, 1000][i % 8])
        aad = os.urandom([0, 3, 16, 48][i % 4])

        # 方向 1：Python 加密 -> Node 解出明文
        blob = pc.aes_gcm_encrypt(key, nonce, pt, aad)
        got = node_run(node, "decrypt", {
            "key": key.hex(), "nonce": nonce.hex(),
            "ct": blob.hex(), "aad": aad.hex(),
        })
        if got != pt.hex():
            bad.append("round %d: python->node 明文不一致 (len=%d)" % (i, len(pt)))

        # 方向 2：Node 加密 -> Python 解出明文
        node_blob = node_run(node, "encrypt", {
            "key": key.hex(), "nonce": nonce.hex(),
            "pt": pt.hex(), "aad": aad.hex(),
        })
        try:
            back = pc.aes_gcm_decrypt(key, nonce, bytes.fromhex(node_blob), aad)
        except pc.PackCryptoError as e:
            bad.append("round %d: node->python 被拒: %s" % (i, e))
            continue
        if back != pt:
            bad.append("round %d: node->python 明文不一致 (len=%d)" % (i, len(pt)))

        # 方向 3：Node 的密文必须与 Python 的密文逐字节相同（实现真正等价）
        if node_blob != blob.hex():
            bad.append("round %d: 密文不等价 (len=%d)" % (i, len(pt)))

    if bad:
        print("gcm_crosscheck 失败 %d 项:" % len(bad))
        for b in bad:
            print("  -", b)
        return 1
    print("gcm_crosscheck: %d 轮双向一致 ✅（node=%s）" % (ROUNDS, node))
    return 0


if __name__ == "__main__":
    sys.exit(main())
