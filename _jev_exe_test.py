# -*- coding: utf-8 -*-
"""jev_guard 离线自测：mock 一个 decisions 服务，覆盖解析、注入、收声、降级。
跑法：python _jev_exe_test.py
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ["no_proxy"] = "127.0.0.1,localhost"
os.environ["NO_PROXY"] = "127.0.0.1,localhost"

# 与本文件同目录（jev_guard.py 就在旁边）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jev_guard  # noqa: E402

PASS = 0
FAIL = 0


def ok(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("PASS  " + name)
    else:
        FAIL += 1
        print("FAIL  " + name + (("  -> " + str(extra)) if extra else ""))


GOOD_ANSWERS = {
    "literal_question": {"noul": 0},
    "true_intent": {"choice": "vent_anger"},
    "danger_level": {"score": 6},
    "should_reply_now": {"noul": 0},
    "best_action": {"choice": "acknowledge"},
    "she_needs": {"choice": "care"},
    "tension_resolved": {"noul": 0},
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        if self.path == "/auth":
            import hmac as _h, hashlib as _hl2
            ts = self.headers.get("x-weauto-ts")
            nonce = self.headers.get("x-weauto-nonce")
            sig = self.headers.get("x-weauto-sig")
            length = int((self.headers.get("content-length", "0") or "0"))
            raw = self.rfile.read(length) if length else b""
            expect = _h.new(b"s3cr3t", b"POST\n/auth\n" + (ts or "").encode() + b"\n" + (nonce or "").encode() + b"\n" + raw, _hl2.sha256).hexdigest()
            if ts and nonce and sig and _h.compare_digest(expect, sig):
                payload = {"answers": GOOD_ANSWERS}
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(401)
                self.end_headers()
            return
        if self.path == "/slow":
            time.sleep(30)
            return
        if self.path == "/500":
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"boom")
            return
        if self.path == "/garbage":
            body = b"<html>not json</html>"
            self.send_response(200)
            self.send_header("content-type", "text/html")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/missing":
            payload = {"nope": 1}
        else:
            payload = {"answers": GOOD_ANSWERS, "provider": "cloudflare-workers-ai"}
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)  # 多线程：/slow 不得阻塞后续请求
PORT = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{PORT}"


def set_cfg(**kw):
    values = dict(jev_guard._DEFAULTS)
    values.update(kw)
    jev_guard._CFG_CACHE["ts"] = time.time()
    jev_guard._CFG_CACHE["values"] = values


# ---------- 1. 开关（Jev 无需卡密，只看 ENABLE_JEV_GUARD）----------
set_cfg(ENABLE_JEV_GUARD=False)
ok("默认关闭时 enabled()=False", jev_guard.enabled() is False)
set_cfg(ENABLE_JEV_GUARD=True)
ok("开启即生效（无需卡密）-> enabled()=True", jev_guard.enabled() is True)
set_cfg(ENABLE_JEV_GUARD=True, JEV_BASE_URL=BASE + "/ok")
ok("开启且端点可达 -> enabled()=True", jev_guard.enabled() is True)

# ---------- 2. 正常解析 ----------
v = jev_guard.judge("u1", "算了，我习惯了", [{"role": "user", "content": "你又忘了吧"},
                                            {"role": "assistant", "content": "抱歉"}])
ok("judge 拿到 verdict", isinstance(v, dict) and len(v) > 0, v)
ok("noul 0 -> False", v.get("literal_question") is False)
ok("choice 原样", v.get("true_intent") == "vent_anger")
ok("score 6", v.get("danger_level") == 6)
ok("she_needs", v.get("she_needs") == "care")
ok("best_action", v.get("best_action") == "acknowledge")

# ---------- 3. guidance 注入 ----------
g = jev_guard.guidance_text(v)
ok("guidance 非空", bool(g))
ok("guidance 含危险度", "危险度：6/9" in g, g)
ok("guidance 含建议动作", "建议动作" in g)
ok("guidance 含话里有话提示", "话里有话" in g)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/ok", JEV_INJECT_GUIDANCE=False)
ok("关闭注入 -> 空串", jev_guard.guidance_text(v) == "")
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/ok", JEV_INJECT_GUIDANCE=True)

# ---------- 4. 收声 ----------
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/ok",
        JEV_HOLD_ON_DANGER=False, JEV_DANGER_HOLD_LEVEL=8)
ok("默认不收声", jev_guard.should_hold(v) is False)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/ok",
        JEV_HOLD_ON_DANGER=True, JEV_DANGER_HOLD_LEVEL=8)
ok("危险度 6 < 阈值 8 -> 不收声", jev_guard.should_hold(v) is False)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/ok",
        JEV_HOLD_ON_DANGER=True, JEV_DANGER_HOLD_LEVEL=5)
ok("危险度 6 >= 阈值 5 -> 收声", jev_guard.should_hold(v) is True)
ok("verdict 为 None 不收声", jev_guard.should_hold(None) is False)

# ---------- 5. 降级 ----------
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/500")
ok("HTTP 500 -> None", jev_guard.judge("u", "x") is None)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/garbage")
ok("非 JSON -> None", jev_guard.judge("u", "x") is None)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/missing")
ok("缺 answers -> None", jev_guard.judge("u", "x") is None)
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL="http://127.0.0.1:1/nope")
ok("连不上 -> None", jev_guard.judge("u", "x") is None)

set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/slow", JEV_TIMEOUT=2.0)
t0 = time.time()
r = jev_guard.judge("u", "x")
cost = time.time() - t0
ok("慢响应不拖死（<=6s 返回 None）", r is None and cost < 6, f"cost={cost:.1f}s")

# ---------- 6. state 构造 ----------
st = jev_guard.build_state([{"role": "user", "content": "在吗"}, {"role": "assistant", "content": "在的"}],
                           "你又忘了吧", "女朋友")
ok("state: 对方 -> her", st["chat"]["messages"][0]["from"] == "her")
ok("state: 我 -> me", st["chat"]["messages"][1]["from"] == "me")
ok("state: 最新消息在最后且为 her",
   st["chat"]["messages"][-1] == {"from": "her", "text": "你又忘了吧"})
ok("state: relationship", st["chat"]["relationship"] == "女朋友")
ok("state: latest_from", st["chat"]["latest_from"] == "her")

# ---------- 7. rank ----------
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="k", JEV_BASE_URL=BASE + "/rank")
ok("rank: 非 3 条候选 -> None", jev_guard.rank("u", "x", ["a", "b"]) is None)

# ---------- 8. 脱敏 ----------
set_cfg(ENABLE_JEV_GUARD=True, CREEM_LICENSE_KEY="secret-key-123456", JEV_BASE_URL=BASE + "/ok")
ok("密钥被脱敏", "secret-key-123456" not in jev_guard._redact("key=secret-key-123456"))


# ---------- 9. 签名单测 ----------
import hmac as _hmac, hashlib as _hl
set_cfg(ENABLE_JEV_GUARD=True, JEV_BASE_URL=BASE + "/ok", WEAUATO_CLIENT_SECRET="s3cr3t")
_body = json.dumps({"state": {}, "questions": {}}).encode("utf-8")
_sig = jev_guard._sign("s3cr3t", "/ai/jev/decisions", "100", "nonce", _body)
_exp = _hmac.new(b"s3cr3t", b"POST\n/ai/jev/decisions\n100\nnonce\n" + _body, _hl.sha256).hexdigest()
ok("_sign 与手算 HMAC 一致", _sig == _exp)

# ---------- 10. 签名端到端（mock 后端强制校验）----------
# 配了密钥 -> 自动带签名 -> /auth 接受
set_cfg(ENABLE_JEV_GUARD=True, JEV_BASE_URL=BASE + "/auth", WEAUATO_CLIENT_SECRET="s3cr3t")
v_auth = jev_guard.judge("u", "x")
ok("配了密钥 -> 签名被后端接受", isinstance(v_auth, dict) and len(v_auth) > 0, v_auth)
# 未配密钥 -> 不带签名 -> /auth 拒绝 -> 降级 None
set_cfg(ENABLE_JEV_GUARD=True, JEV_BASE_URL=BASE + "/auth", WEAUATO_CLIENT_SECRET="")
ok("未配密钥 -> 无签名 -> 后端拒 -> None", jev_guard.judge("u", "x") is None)

srv.shutdown()
print(f"\n结果: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
