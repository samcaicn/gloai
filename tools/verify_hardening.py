# -*- coding: utf-8 -*-
"""防破解整改回归验证（不跑 PyInstaller，直接验证运行时防护链）。

覆盖：
  1. release_secrets.enc 解密与密钥完整性
  2. config.py 签名：合法→通过；篡改→拒绝
  3. guard：use_worker_ai 恒 True / worker_url 来自 blob / ai_endpoint 走网关
  4. ledger：记账计数 / 篡改检测
  5. updater：Ed25519 验签通过 / 篡改拒绝
  6. scriptpack：验签公钥来自 blob
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

_ok = True


def check(name: str, cond: bool, detail: str = "") -> None:
    global _ok
    print(("PASS  " if cond else "FAIL  ") + name + (("  | " + detail) if detail else ""))
    if not cond:
        _ok = False


# 1) secrets blob
from weauto_license import secrets as s

check("release flag", s.is_release())
need = ["GATEWAY_HOST", "WEAUATO_CLIENT_SECRET", "LICENSE_SIG_SECRET", "CACHE_SIG_SALT",
        "DEVICE_SALT", "CONFIG_SIG_KEY", "QUOTA_LEDGER_SALT", "WPK_MASTER_HEX",
        "PACK_SIGNING_PUB", "UPDATER_VERIFY_KEY"]
missing = [k for k in need if not s.get(k)]
check("blob keys complete", not missing, str(missing))
check("CONFIG_SIG_KEY 32B", len(s.get_bytes("CONFIG_SIG_KEY") or b"") == 32)

# 2) config 签名
keys = json.load(open(os.path.join(ROOT, "weauto_license", "build_keys.json"), encoding="utf-8"))
work = os.path.join(ROOT, "_pybuild_work")
os.makedirs(work, exist_ok=True)
work_cfg = os.path.join(work, "config.py")
work_sig = work_cfg + ".sig"
shutil.copy(os.path.join(ROOT, "config.py"), work_cfg)
check("sign config (work copy)", s.sign_config_file(work_cfg, work_sig))
# 项目根放一份签名（模拟发行包内形态）验证 config_signature_valid
shutil.copy(work_sig, os.path.join(ROOT, "config.py.sig"))
check("config sig valid", s.config_signature_valid())
# 篡改
tamper_line = "\n# __tamper_test__\n"
with open(os.path.join(ROOT, "config.py"), "a", encoding="utf-8") as f:
    f.write(tamper_line)
check("config sig invalid after tamper", not s.config_signature_valid())
# 恢复（精确移除追加行）
with open(os.path.join(ROOT, "config.py"), "r", encoding="utf-8") as f:
    content = f.read()
content = content.replace(tamper_line, "")
with open(os.path.join(ROOT, "config.py"), "w", encoding="utf-8") as f:
    f.write(content)
os.remove(os.path.join(ROOT, "config.py.sig"))

# 3) guard
from weauto_license import guard as g

check("use_worker_ai forced True", g.use_worker_ai() is True)
_expect_host = (keys.get("GATEWAY_HOST") or "").rstrip("/")
check("worker_url from blob", bool(_expect_host) and g.worker_url() == _expect_host)
ep = g.ai_endpoint()
check("ai_endpoint gateway", bool(ep) and str(ep.get("base_url", "")).endswith("/ai/v1"))
check("ai_endpoint free-tier", (ep or {}).get("api_key") in ("", "free-tier"))

# 4) ledger
from weauto_license import ledger as L

a0, r0, lim0 = L.check_quota(30)
check("ledger allow initially", a0 and r0 == 30)
L.record_usage()
L.record_usage()
a2, r2, _ = L.check_quota(30)
check("ledger counts 2 used", r2 == 28, "remaining=%s" % r2)
# 篡改记账文件 → 应被识破（视为超限）
lp = L._ledger_path()
d = json.load(open(lp, encoding="utf-8"))
d["count"] = 0
json.dump(d, open(lp, "w", encoding="utf-8"))
a3, r3, _ = L.check_quota(30)
check("ledger tamper detected", (not a3) and r3 == 0, "remaining=%s" % r3)
if os.path.exists(lp):
    os.remove(lp)

# 5) updater 验签
from cryptography.hazmat.primitives.asymmetric import ed25519

sk = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(keys["UPDATER_SIGN_KEY"]))
tmp = os.path.join(work, "_verify_tmp.bin")
with open(tmp, "wb") as f:
    f.write(b"hello weauto")
digest = hashlib.sha256(open(tmp, "rb").read()).digest()
sig = sk.sign(digest)
pub = s.get_bytes("UPDATER_VERIFY_KEY")
ed25519.Ed25519PublicKey.from_public_bytes(pub).verify(sig, digest)
check("updater verify ok", True)
bad_sig = sk.sign(hashlib.sha256(b"tampered").digest())
try:
    ed25519.Ed25519PublicKey.from_public_bytes(pub).verify(bad_sig, digest)
    check("updater rejects tamper", False)
except Exception:
    check("updater rejects tamper", True)
if os.path.exists(tmp):
    os.remove(tmp)

# 6) scriptpack 公钥来自 blob
import scriptpack

pk = scriptpack._pack_verify_pubkey_b64()
check("scriptpack pub from blob", pk == keys["PACK_SIGNING_PUB"])

# 7) 反调试 / 自体检 / bat 搬运 sig（防破解整改新增）
from weauto_license import hardening as H

check("hardening exports", all(hasattr(H, n) for n in
      ["detect_debugger", "self_integrity_ok", "enforce_hardening", "recheck",
       "assert_hardening", "is_compromised"]))
check("detect_debugger 无环境误报", H.detect_debugger() is False)
check("self_integrity_ok 源码态 True", H.self_integrity_ok() is True)
try:
    import io as _io
    from updater import Updater as _U
    _buf = _io.StringIO()
    _U()._write_replace_bat(_buf, "C:/x/WeAuto.exe", "C:/x/WeAuto.exe.sig",
                            "C:/dst/WeAuto.exe", "C:/x/log.log")
    _bat = _buf.getvalue()
    check("bat 搬运 sig(SRCSIG/DSTSIG)",
          ("SRCSIG" in _bat) and ("DSTSIG" in _bat)
          and ('copy /Y "%SRCSIG%" "!DSTSIG!"' in _bat))
except Exception as _hb:
    check("bat 搬运 sig(SRCSIG/DSTSIG)", False, repr(_hb))

print("RESULT: " + ("ALL PASS" if _ok else "SOME FAILED"))
sys.exit(0 if _ok else 1)
