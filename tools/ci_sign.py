# -*- coding: utf-8 -*-
# tools/ci_sign.py —— CI 签名入口：零第三方依赖、强制纯 Python Ed25519、全量诊断落盘。
#
# 用法（workflow 内一行调用）：
#   python tools/ci_sign.py <path-to-exe>
# 退出码：0=签名成功；1=失败（原因与 traceback 已写入 ci-diag.txt 并同时打印）。
#
# 设计动机：
# 1) GitHub Actions 的 bash 步骤默认 `bash -e -o pipefail`，bash 层 OUT=$(cmd); RC=$?
#    写法会在 cmd 非零时被 -e 直接杀掉，诊断代码无法执行 -> 逻辑全放 Python。
# 2) Windows runner 的 python 默认 stdout=cp1252，print 中文会 UnicodeEncodeError
#    （上一轮失败的真凶：签名其实成功了，是打印中文那行崩掉）-> 输出全部 ASCII，
#    并在启动时 reconfigure(stdout, errors="replace") 双保险。

import datetime
import os
import re
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sign_release as S  # noqa: E402

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

DIAG = "ci-diag.txt"


def log(msg):
    """ASCII 消息同时写 stdout（Actions 日志）与 ci-diag.txt。"""
    try:
        msg.encode("ascii")
    except UnicodeEncodeError:
        msg = msg.encode("ascii", "replace").decode("ascii")
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

    log("=== sign @ {}Z".format(datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")))
    log("python={}".format(sys.version.split()[0]))
    log("backend={}".format("cryptography" if S.HAVE_CRYPTO else "pure-python(rfc8032)"))

    if len(sys.argv) < 2:
        log("FAIL: missing exe argument")
        return 1
    exe = sys.argv[1]
    log("exe={} exists={}".format(exe, os.path.exists(exe)))

    key = os.environ.get("BBT_SIGN_KEY", "").strip()
    log("key_len={} (expect 64)".format(len(key)))
    ok = bool(re.fullmatch(r"[0-9a-fA-F]{64}", key or ""))
    log("key_format={}".format("ok" if ok else "BAD(not 64-hex)"))
    if not ok:
        log("FAIL: BBT_SIGN_KEY invalid - paste bare hex of weauto build_keys.json UPDATER_SIGN_KEY")
        return 1

    try:
        sk = bytes.fromhex(key)
        digest = S.sha256_file(exe)
        # 强制纯 Python 后端签名：标准 RFC 8032，与客户端 ed25519_dalek 完全兼容，
        # CI 上不依赖 cryptography 是否安装成功。
        sig = S.pure_sign(sk, digest)
        pub = S.pure_publickey(sk)
        with open(exe + ".sig", "wb") as f:
            f.write(sig)
        log("SIGNED: {}.sig ({} bytes)".format(exe, len(sig)))
        log("public_key={} (must equal update.rs::UPDATER_VERIFY_KEY)".format(pub.hex()))
        return 0
    except Exception:
        log("FAIL: exception:")
        log(traceback.format_exc())
        return 1


if __name__ == "__main__":
    sys.exit(main())
