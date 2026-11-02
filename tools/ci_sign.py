# -*- coding: utf-8 -*-
# tools/ci_sign.py —— CI 签名入口：零第三方依赖、强制纯 Python Ed25519、全量诊断落盘。
#
# 用法（workflow 内一行调用）：
#   python tools/ci_sign.py <path-to-exe>
# 退出码：0=签名成功；1=失败（原因与 traceback 已写入 ci-diag.txt 并同时打印）。
#
# 设计动机：GitHub Actions 的 bash 步骤默认 `bash -e -o pipefail`，bash 层的
# OUT=$(cmd); RC=$? 写法会在 cmd 非零时被 -e 直接杀掉，诊断代码无法执行。
# 把全部逻辑放进 Python（try/except）即可绕开该陷阱。

import datetime
import os
import re
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sign_release as S  # noqa: E402

DIAG = "ci-diag.txt"


def log(msg):
    print(msg, flush=True)
    try:
        with open(DIAG, "a", encoding="utf-8") as f:
            f.write(msg.rstrip("\n") + "\n")
    except Exception:
        pass


def main() -> int:
    # 每次运行重写诊断文件（历史版本已在 git 里）
    try:
        with open(DIAG, "w", encoding="utf-8") as f:
            f.write("")
    except Exception:
        pass

    log(f"=== sign @ {datetime.datetime.utcnow().isoformat()}Z")
    log(f"python={sys.version.split()[0]}")
    log(f"backend={'cryptography' if S.HAVE_CRYPTO else 'pure-python(rfc8032)'}")

    if len(sys.argv) < 2:
        log("FAIL: 缺少 exe 参数")
        return 1
    exe = sys.argv[1]
    log(f"exe={exe} exists={os.path.exists(exe)}")

    key = os.environ.get("BBT_SIGN_KEY", "").strip()
    log(f"key_len={len(key)} (应为64)")
    ok = bool(re.fullmatch(r"[0-9a-fA-F]{64}", key or ""))
    log(f"key_format={'ok' if ok else 'BAD(非64位纯hex)'}")
    if not ok:
        log("FAIL: BBT_SIGN_KEY 非法 —— 请粘贴 weauto tools/build_keys.json 中 UPDATER_SIGN_KEY 的裸 hex")
        return 1

    try:
        sk = bytes.fromhex(key)
        digest = S.sha256_file(exe)
        # 强制纯 Python 后端签名：与客户端 ed25519_dalek 完全兼容（标准 RFC 8032），
        # CI 上不依赖 cryptography 是否安装成功。
        sig = S.pure_sign(sk, digest)
        pub = S.pure_publickey(sk)
        with open(exe + ".sig", "wb") as f:
            f.write(sig)
        log(f"SIGNED: {exe}.sig ({len(sig)} bytes)")
        log(f"public_key={pub.hex()} (应与 update.rs::UPDATER_VERIFY_KEY 一致)")
        return 0
    except Exception:
        log("FAIL: exception:")
        log(traceback.format_exc())
        return 1


if __name__ == "__main__":
    sys.exit(main())
