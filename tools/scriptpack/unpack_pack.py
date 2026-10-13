#!/usr/bin/env python3
"""WeAuto 话术包解包器：验签 + 解密 -> 明文 JSON（客服排查 / 自测用）

  unpack_pack.py --pack x.wpk --pub p.pem [--device-hex H] [--master-hex M] [--now TS]
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import packcrypto as pc  # noqa: E402
from make_pack import _pem_decode, _master  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--pub", required=True, help="make_pack.py genkey --pub-out 产出的公钥文件")
    ap.add_argument("--device-hex", default=None)
    ap.add_argument("--master-hex", default=None)
    ap.add_argument("--now", type=int, default=None)
    args = ap.parse_args()

    data = open(args.pack, "rb").read()
    pub = _pem_decode(args.pub)["pub"]
    point = (int(pub[0], 16), int(pub[1], 16))
    dh = bytes.fromhex(args.device_hex) if args.device_hex else bytes(pc.DEVICE_HASH_LEN)
    now = args.now if args.now is not None else int(time.time())

    try:
        payload = pc.parse_and_decrypt(data, _master(args.master_hex), dh, now, point)
    except pc.PackCryptoError as e:
        print("拒绝：%s" % e, file=sys.stderr)
        return 1
    payload.pop("_header", None)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
