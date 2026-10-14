"""线上探针：验证 weauto-license Worker（weauto-app.pages.dev）的免费配额 + 双凭证闸门。

2026-10-14 商业模式转向「默认激活 + 云端每日回复配额」后：
  - /ai/v1 无凭证 / 卡密无效 → 放行到 proxyAi 免费配额（每日 N 条，KV 计数，超限 429）
  - Jev（/ai/jev/decisions）保持原墙：无签名 → 403 integrity_signature（防白嫖烧 AI）
  - Android 完整性头（x-sig-fp 等）仍走 verifyClientIntegrity（防马甲包）
本脚本打真线上一遍验活：
  1. /health 墙与签名的启用状态
  2. jev 无签名        → 403 integrity_signature（Jev 墙完好）
  3. jev + 合法 EXE 签名 → 200 真判断（EXE 修复生效）
  4. jev + 假签名      → 401 bad_sig（精确错误）
  5. /ai/v1 无凭证     → 200 免费 AI（带 X-Quota-* 头）或 429 配额超限
  6. /ai/v1 假卡密+实例 → 回落免费配额（200/429），不再 401
  7. /validate 非墙上路由 → 正常走 Creem
密钥从 config.py 读（出厂值与 Worker secret 同值），不硬编码。
"""
import hashlib
import hmac
import json
import secrets
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, ".")
import config  # noqa: E402

BASE = "https://weauto-app.pages.dev"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
SECRET = getattr(config, "WEAUATO_CLIENT_SECRET", "") or ""

JEV_BODY = json.dumps({
    "state": {"chat": {"relationship": "test", "messages": [{"from": "her", "text": "在吗"}], "latest_from": "her"}},
    "questions": {"literal_question": {"type": "noul", "instructions": "literal?", "criteria": {"true": "y", "false": "n"}}},
}, ensure_ascii=False).encode("utf-8")

AI_BODY = json.dumps({
    "model": "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "messages": [{"role": "user", "content": "hi"}],
    "max_tokens": 3,
}).encode()

def _req(path, body=None, headers=None, method="POST"):
    req = urllib.request.Request(BASE + path, data=body if method == "POST" else None,
                                 method=method, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300], dict(e.headers)
    except Exception as e:
        return -1, f"{type(e).__name__}: {e}", {}

results = []
def check(name, cond, detail=""):
    results.append(cond)
    print(("PASS  " if cond else "FAIL  ") + name + (("  -> " + detail[:160]) if detail else ""))

st, body, hdrs = _req("/health", method="GET")
h = json.loads(body) if st == 200 else {}
check("/health 200", st == 200, f"{st}")
check("  integrity_enforced=true（Android 墙仍开）", h.get("integrity_enforced") is True)
check("  jev_require_auth=true（EXE 签名仍启用）", h.get("jev_require_auth") is True)

st, body, _ = _req("/ai/jev/decisions", JEV_BODY, {"Content-Type": "application/json"})
check("jev 无签名 → 403 integrity_signature（Jev 墙完好）", st == 403 and "integrity_signature" in body, f"{st} {body[:100]}")

ts, nonce = int(time.time()), secrets.token_hex(16)
msg = ("POST\n/ai/jev/decisions\n%s\n%s\n" % (ts, nonce)).encode("utf-8") + JEV_BODY
sig = hmac.new(SECRET.encode("utf-8"), msg, hashlib.sha256).hexdigest()
st, body, _ = _req("/ai/jev/decisions", JEV_BODY, {
    "Content-Type": "application/json", "X-WeAuto-Ts": str(ts), "X-WeAuto-Nonce": nonce, "X-WeAuto-Sig": sig,
})
check("jev + 合法 EXE 签名 → 200 真判断", st == 200 and "answers" in body, f"{st} {body[:150]}")

st, body, _ = _req("/ai/jev/decisions", JEV_BODY, {
    "Content-Type": "application/json", "X-WeAuto-Ts": str(ts), "X-WeAuto-Nonce": nonce, "X-WeAuto-Sig": "00" * 32,
})
check("jev + 假签名 → 401 bad_sig", st == 401 and "bad_sig" in body, f"{st} {body[:100]}")

st, body, hdrs = _req("/ai/v1/chat/completions", AI_BODY, {"Content-Type": "application/json"})
qrem = hdrs.get("X-Quota-Remaining", "")
ok_free = (st == 200 and qrem != "") or (st == 429 and "daily_quota_exceeded" in body)
check("/ai/v1 无凭证 → 免费配额放行（200 带配额头 或 429 超限）", ok_free, f"{st} quota_remaining={qrem} {body[:120]}")

st, body, _ = _req("/ai/v1/chat/completions", AI_BODY, {
    "Content-Type": "application/json", "Authorization": "Bearer FAKE-KEY-1234", "X-WeAuto-Instance": "FAKE-INST",
})
check("/ai/v1 假卡密+实例 → 回落免费配额（不再 401）", st != 401, f"{st} {body[:120]}")

st, body, _ = _req("/validate", json.dumps({"key": "FAKE", "instance_id": "FAKE"}).encode(), {"Content-Type": "application/json"})
check("/validate（非墙上路由）→ 非墙拦截", not (st == 403 and "integrity" in body), f"{st} {body[:100]}")

print("\n==== live probe: %d/%d pass ====" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
