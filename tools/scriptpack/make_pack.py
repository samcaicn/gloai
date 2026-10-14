#!/usr/bin/env python3
"""WeAuto 话术包签发器：script.json + 签名私钥 -> .wpk

子命令：
  genkey --out k.pem [--pub-out p.pem]     生成 ECDSA P-256 签名密钥对（私钥永不出现在仓库/日志）
  sign   --script s.json --sign-key k.pem --out x.wpk [选项]
  info   --pack x.wpk                      只读 header 摘要（不解密）

sign 选项：
  --days N            有效期天数（默认 365，从当前时间起算）
  --expires TS|DATE   expires_at，unix 秒或 YYYY-MM-DD（覆盖 --days）
  --device-hex H      绑一台设备：传该机 16B hash 的 hex（32 字符，App 设置页话术包区块可见）
  --device-id ID      绑一台设备：直接传 DeviceId 原始值，本工具负责哈希
  --master-hex H      master key hex（默认读环境变量 PACK_MASTER_HEX，再退回内置测试值）

私钥文件格式（自描述 PEM，避免依赖 asn1 库）：
  -----BEGIN WEAUTO PACK SIGNING KEY-----   base64({"priv": hex, "pub": [xhex, yhex]})
"""

import argparse
import base64
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import packcrypto as pc  # noqa: E402

PRIV_PEM = "WEAUTO PACK SIGNING KEY"
PUB_PEM = "WEAUTO PACK VERIFY KEY"


def _pem_encode(label: str, obj: dict) -> str:
    body = base64.b64encode(json.dumps(obj).encode("utf-8")).decode("ascii")
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return "-----BEGIN %s-----\n%s\n-----END %s-----\n" % (label, "\n".join(lines), label)


def _pem_decode(path: str) -> dict:
    text = open(path, "r", encoding="utf-8").read()
    body = "".join(
        line for line in text.splitlines()
        if line and not line.startswith("-----")
    )
    return json.loads(base64.b64decode(body).decode("utf-8"))


def _master(cli_hex: str = None) -> bytes:
    hexval = cli_hex or os.environ.get("PACK_MASTER_HEX") or pc.TEST_MASTER_HEX
    return bytes.fromhex(hexval)


def _parse_expires(args, script: dict) -> int:
    if args.expires:
        s = str(args.expires)
        if s.isdigit():
            return int(s)
        import datetime
        return int(datetime.datetime.strptime(s, "%Y-%m-%d")
                   .replace(tzinfo=datetime.timezone.utc).timestamp())
    if "expires_at" in script and not args.days_explicit:
        return int(script["expires_at"])
    return int(time.time()) + args.days * 86400


def cmd_genkey(args) -> int:
    priv, point = pc.gen_keypair()
    priv_obj = {
        "priv": "%064x" % priv,
        "pub": ["%064x" % point[0], "%064x" % point[1]],
    }
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(_pem_encode(PRIV_PEM, priv_obj))
    os.chmod(args.out, 0o600)
    print("私钥已写入 %s（权限 600）。**不要提交到任何仓库、不要外发。**" % args.out)
    if args.pub_out:
        with open(args.pub_out, "w", encoding="utf-8") as f:
            f.write(_pem_encode(PUB_PEM, {"pub": priv_obj["pub"]}))
        print("公钥已写入 %s" % args.pub_out)
    print("\n把下面这行（SPKI base64）替换 Android 端 ScriptPack.PACK_VERIFY_PUBKEY_B64 后发版：")
    print(base64.b64encode(pc.spki_from_point(point)).decode("ascii"))
    return 0


def cmd_sign(args) -> int:
    script = json.load(open(args.script, "r", encoding="utf-8"))
    key = _pem_decode(args.sign_key)
    priv = int(key["priv"], 16)

    args.days_explicit = False
    if not args.days and "expires_at" not in script and not args.expires:
        args.days = 365
    elif args.days:
        args.days_explicit = True
    script["issued_at"] = int(time.time())
    script["expires_at"] = _parse_expires(args, script)
    if not script.get("pack_id"):
        print("错误：script.json 缺少 pack_id", file=sys.stderr)
        return 2

    device_hash = None
    if args.device_hex:
        device_hash = bytes.fromhex(args.device_hex)
    elif args.device_id:
        device_hash = bytes.fromhex(pc.device_hash_hex(args.device_id))

    data = pc.sign_pack(script, _master(args.master_hex), priv, device_hash)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(data)

    header = pc.read_header(data)
    print("已签发 %s（%d 字节）" % (args.out, len(data)))
    print("  pack_id      : %s" % header["pack_id"])
    print("  name         : %s" % header["name"])
    print("  expires_at   : %s（%s）" % (
        header["expires_at"],
        time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(header["expires_at"]))))
    print("  device_hashes: %s" % (header["device_hashes"] or "未绑设备"))
    return 0


def cmd_info(args) -> int:
    data = open(args.pack, "rb").read()
    h = pc.read_header(data)
    print(json.dumps({k: v for k, v in h.items() if not k.startswith("_")},
                     ensure_ascii=False, indent=2))
    left = h["expires_at"] - int(time.time())
    print("剩余 %d 天" % (left // 86400) if left > 0 else "已到期 %d 天" % (-left // 86400))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("genkey", help="生成签名密钥对")
    g.add_argument("--out", required=True)
    g.add_argument("--pub-out", default=None)
    g.set_defaults(func=cmd_genkey)

    s = sub.add_parser("sign", help="签发话术包")
    s.add_argument("--script", required=True)
    s.add_argument("--sign-key", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--days", type=int, default=0)
    s.add_argument("--expires", default=None)
    s.add_argument("--device-hex", default=None)
    s.add_argument("--device-id", default=None)
    s.add_argument("--master-hex", default=None)
    s.set_defaults(func=cmd_sign)

    i = sub.add_parser("info", help="查看 header 摘要")
    i.add_argument("--pack", required=True)
    i.set_defaults(func=cmd_info)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
