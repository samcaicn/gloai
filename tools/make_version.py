# -*- coding: utf-8 -*-
# tools/make_version.py —— 生成自动更新清单 updates/version.json（weauto version.json 结构）。
#
# 用法：
#   python tools/make_version.py <version> <exe_url> <sig_url> [notes]
#
# 输出为 weauto 升级器兼容的 JSON：
#   { "version": "...", "notes": "...", "exe_url": "...", "sig_url": "..." }
# 客户端 update.rs 读取 {update_base}/updates/version.json，对 exe 做 SHA-256 + Ed25519 验签。

import json
import os
import sys


def main() -> None:
    if len(sys.argv) < 4:
        print("usage: make_version.py <version> <exe_url> <sig_url> [notes]", file=sys.stderr)
        sys.exit(2)
    version = sys.argv[1]
    exe_url = sys.argv[2]
    sig_url = sys.argv[3]
    notes = sys.argv[4] if len(sys.argv) > 4 else "BBT-ZB 控制台 {}".format(version)

    out = {
        "version": version,
        "notes": notes,
        "exe_url": exe_url,
        "sig_url": sig_url,
    }
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(root, "updates", "version.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("WROTE {}".format(path))


if __name__ == "__main__":
    main()
