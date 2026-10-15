# -*- coding: utf-8 -*-
"""jev_guard 离线自测：mock 一个 decisions 服务，覆盖解析、注入、收声、降级、重试。

跑法：python _jev_exe_test.py（纯标准库 + 本机 mock，不碰线上 Worker）

架构说明（2026-10-14 重写）：
    jev_guard 是「零配置」架构——端点/超时/阈值全部是模块顶部的 JEV_* 常量，
    config.py 里没有任何 JEV_* 项。因此本测试通过 monkeypatch
    `jev_guard._jev_candidate_urls` 把请求打到本机 mock 服务（唯一注入点），
    并把超时常量临时改小（1.0s/1.5s）让重试用例秒级完成。
    ⚠️ 不要再改回 config.py 驱动（ENABLE_JEV_GUARD / JEV_BASE_URL 已不存在）。
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
    "language": {"choice": "zh"},
}

RANK2_ANSWERS = {"answers": {"best_reply": {"choice": "reply_b"}}}
RANK3_ANSWERS = {"answers": {"best_reply": {"choice": "reply_c"}}}

# ---------------------------------------------------------------- mock 服务
_hits = {}          # path -> 请求次数
_hits_lock = threading.Lock()
_flaky_headers = []  # /flaky 每次请求的 (ts, nonce)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _hit(self, path):
        with _hits_lock:
            _hits[path] = _hits.get(path, 0) + 1

    def _send(self, obj, status=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        path = self.path
        self._hit(path)
        if path == "/auth":
            # 强制校验 HMAC 客户端签名（与 Worker 端算法一致）
            import hmac as _h, hashlib as _hl2
            ts = self.headers.get("x-weauto-ts")
            nonce = self.headers.get("x-weauto-nonce")
            sig = self.headers.get("x-weauto-sig")
            length = int((self.headers.get("content-length", "0") or "0"))
            raw = self.rfile.read(length) if length else b""
            expect = _h.new(b"s3cr3t", b"POST\n/auth\n" + (ts or "").encode() + b"\n" + (nonce or "").encode() + b"\n" + raw, _hl2.sha256).hexdigest()
            if ts and nonce and sig and _h.compare_digest(expect, sig):
                self._send({"answers": GOOD_ANSWERS})
            else:
                self._send({"ok": False}, 401)
            return
        if path == "/flaky":
            # 第一次 500（瞬时故障），之后 200；记录每次请求的签名头
            with _hits_lock:
                _flaky_headers.append((self.headers.get("x-weauto-ts"),
                                       self.headers.get("x-weauto-nonce")))
            if _hits[path] == 1:
                self._send({"ok": False}, 500)
            else:
                self._send({"answers": GOOD_ANSWERS})
            return
        if path == "/always500":
            self._send({"ok": False}, 500)
            return
        if path == "/400":
            self._send({"ok": False, "error": "bad_request"}, 400)
            return
        if path == "/slow":
            time.sleep(30)
            return
        if path == "/slow1250":
            time.sleep(1.25)
            self._send({"answers": GOOD_ANSWERS})
            return
        if path == "/garbage":
            body = b"<html>not json</html>"
            self.send_response(200)
            self.send_header("content-type", "text/html")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/missing":
            self._send({"nope": 1})
            return
        if path == "/rank2":
            self._send(RANK2_ANSWERS)
            return
        if path == "/rank3":
            self._send(RANK3_ANSWERS)
            return
        self._send({"answers": GOOD_ANSWERS, "provider": "cloudflare-workers-ai"})


srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)  # 多线程：/slow 不得阻塞后续请求
PORT = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{PORT}"


def use(path):
    """把 jev_guard 的候选端点指到本机 mock（唯一注入点）。"""
    jev_guard._jev_candidate_urls = lambda: [BASE + path]


def set_cfg(**kw):
    values = dict(jev_guard._DEFAULTS)
    values.update(kw)
    jev_guard._CFG_CACHE["ts"] = time.time()
    jev_guard._CFG_CACHE["values"] = values


def reset_circuit():
    with jev_guard._CIRCUIT_LOCK:
        jev_guard._CIRCUIT["fails"] = 0
        jev_guard._CIRCUIT["open"] = False


def hits(path):
    with _hits_lock:
        return _hits.get(path, 0)


# 超时常量临时改小：重试用例秒级完成（finally 里还原）
_orig_timeouts = (jev_guard.JEV_TIMEOUT_SEC, jev_guard.JEV_RETRY_TIMEOUT_SEC)
jev_guard.JEV_TIMEOUT_SEC = 1.0
jev_guard.JEV_RETRY_TIMEOUT_SEC = 1.5

try:
    # ---------- 0. 出厂常量与题目集 ----------
    ok("JEV_TIMEOUT_SEC 是正数", isinstance(jev_guard.JEV_TIMEOUT_SEC, (int, float)) and jev_guard.JEV_TIMEOUT_SEC > 0)
    ok("JEV_RETRY_TIMEOUT_SEC >= JEV_TIMEOUT_SEC（重试不低于首次）",
       jev_guard.JEV_RETRY_TIMEOUT_SEC >= jev_guard.JEV_TIMEOUT_SEC)
    ok("JEV_MAX_RETRIES 存在且 >=0", isinstance(jev_guard.JEV_MAX_RETRIES, int) and jev_guard.JEV_MAX_RETRIES >= 0)
    ok("JEV_LANGUAGE_FOLLOW 出厂开启（上游 v1.7 语言跟随）", jev_guard.JEV_LANGUAGE_FOLLOW is True)
    ok("题目集 8 题（v1.4 的 7 题 + language）", len(jev_guard.JUDGE_QUESTIONS) == 8,
       sorted(jev_guard.JUDGE_QUESTIONS))
    ok("language 是 choice 题且有 3 个选项",
       jev_guard.JUDGE_QUESTIONS["language"]["type"] == "choice"
       and set(jev_guard.JUDGE_QUESTIONS["language"]["criteria"]) == {"zh", "en", "other"})

    # ---------- 1. 正常解析 ----------
    use("/ok")
    reset_circuit()
    v = jev_guard.judge("u1", "算了，我习惯了", [{"role": "user", "content": "你又忘了吧"},
                                                {"role": "assistant", "content": "抱歉"}])
    ok("judge 拿到 verdict", isinstance(v, dict) and len(v) > 0, v)
    ok("noul 0 -> False", v.get("literal_question") is False)
    ok("choice 原样", v.get("true_intent") == "vent_anger")
    ok("score 6", v.get("danger_level") == 6)
    ok("she_needs", v.get("she_needs") == "care")
    ok("best_action", v.get("best_action") == "acknowledge")
    ok("language 进入 verdict", v.get("language") == "zh", v)

    # ---------- 2. guidance 注入 ----------
    g = jev_guard.guidance_text(v)
    ok("guidance 非空", bool(g))
    ok("guidance 含危险度", "危险度：6/9" in g, g)
    ok("guidance 含建议动作", "建议动作" in g)
    ok("guidance 含话里有话提示", "话里有话" in g)
    ok("guidance 含语言跟随（中文）", "对方使用语言：中文" in g, g)
    g_en = jev_guard.guidance_text(dict(v, language="en"))
    ok("guidance 语言跟随（英文）", "对方使用语言：英文" in g_en, g_en)
    ok("guidance 语言行在最前（语言是基调）", g.splitlines()[1].startswith("- 对方使用语言"), g.splitlines()[1])
    _orig_lf = jev_guard.JEV_LANGUAGE_FOLLOW
    jev_guard.JEV_LANGUAGE_FOLLOW = False
    try:
        ok("关闭语言跟随 -> guidance 无语言行", "对方使用语言" not in jev_guard.guidance_text(v))
    finally:
        jev_guard.JEV_LANGUAGE_FOLLOW = _orig_lf
    ok("guidance 空输入返回空串", jev_guard.guidance_text(None) == "")
    ok("guidance 非 dict 返回空串", jev_guard.guidance_text("x") == "")

    # ---------- 3. 收声 ----------
    ok("默认不收声（JEV_HOLD_ON_DANGER 出厂 False）", jev_guard.should_hold(v) is False)
    ok("verdict 为 None 不收声", jev_guard.should_hold(None) is False)
    _orig_hold, _orig_lvl = jev_guard.JEV_HOLD_ON_DANGER, jev_guard.JEV_DANGER_HOLD_LEVEL
    try:
        jev_guard.JEV_HOLD_ON_DANGER, jev_guard.JEV_DANGER_HOLD_LEVEL = True, 8
        ok("危险度 6 < 阈值 8 -> 不收声", jev_guard.should_hold(v) is False)
        jev_guard.JEV_DANGER_HOLD_LEVEL = 5
        ok("危险度 6 >= 阈值 5 -> 收声", jev_guard.should_hold(v) is True)
    finally:
        jev_guard.JEV_HOLD_ON_DANGER, jev_guard.JEV_DANGER_HOLD_LEVEL = _orig_hold, _orig_lvl

    # ---------- 4. 降级（任何失败都降级为照常回复） ----------
    use("/always500")
    reset_circuit()
    n0 = hits("/always500")
    ok("HTTP 500 -> None", jev_guard.judge("u", "x") is None)
    ok("5xx 会重试一次（两次请求）", hits("/always500") - n0 == 2,
       "hits=%d" % (hits("/always500") - n0))
    use("/400")
    reset_circuit()
    n0 = hits("/400")
    ok("HTTP 400 -> None", jev_guard.judge("u", "x") is None)
    ok("4xx 不重试（只请求一次）", hits("/400") - n0 == 1, "hits=%d" % (hits("/400") - n0))
    use("/garbage")
    reset_circuit()
    ok("非 JSON -> None", jev_guard.judge("u", "x") is None)
    use("/missing")
    reset_circuit()
    ok("缺 answers -> None", jev_guard.judge("u", "x") is None)
    use("/nope")  # 未注册路径也走默认 200+answers；改用连不上场景：
    jev_guard._jev_candidate_urls = lambda: ["http://127.0.0.1:1/nope"]
    reset_circuit()
    ok("连不上 -> None", jev_guard.judge("u", "x") is None)

    # ---------- 5. 超时与重试（上游 v1.7「30s 超时重试」的本地缩小版） ----------
    use("/flaky")
    reset_circuit()
    _flaky_headers.clear()
    n0 = hits("/flaky")
    v2 = jev_guard.judge("u", "x")
    ok("瞬时故障后重试成功（首次 500 -> 重试 200）", isinstance(v2, dict) and len(v2) > 0, v2)
    ok("重试共两次请求", hits("/flaky") - n0 == 2, "hits=%d" % (hits("/flaky") - n0))
    ok("两次请求 nonce 不同（每次尝试重签，防重放窗口内 ts 保持新鲜）",
       len({h[1] for h in _flaky_headers}) == 2, _flaky_headers)

    use("/slow1250")
    reset_circuit()
    t0 = time.time()
    v3 = jev_guard.judge("u", "x")
    cost = time.time() - t0
    ok("首次超时后重试拿到结果（1.0s 超时 -> 1.5s 重试成功）", isinstance(v3, dict), v3)
    ok("重试路径耗时合理（<4s）", cost < 4, "cost=%.1fs" % cost)

    use("/slow")
    reset_circuit()
    t0 = time.time()
    r = jev_guard.judge("u", "x")
    cost = time.time() - t0
    ok("持续慢响应不拖死（<=6s 返回 None）", r is None and cost < 6, "cost=%.1fs" % cost)

    # ---------- 6. 熔断器（重试在内部，一次逻辑调用 = 一次记账） ----------
    use("/always500")
    reset_circuit()
    for _ in range(jev_guard.JEV_CIRCUIT_FAILS - 1):
        jev_guard.judge("u", "x")
    ok("连续失败 %d 次仍未熔断" % (jev_guard.JEV_CIRCUIT_FAILS - 1),
       not jev_guard._circuit_open(), "fails=%d" % jev_guard._CIRCUIT["fails"])
    jev_guard.judge("u", "x")
    ok("失败达阈值 %d 次后熔断" % jev_guard.JEV_CIRCUIT_FAILS, jev_guard._circuit_open())
    ok("熔断时 enabled() == False（跳过判断、照常回复）", jev_guard.enabled() is False)
    use("/ok")
    jev_guard._circuit_record(True)
    ok("成功一次即完全恢复", not jev_guard._circuit_open())
    ok("恢复后失败计数清零", jev_guard._CIRCUIT["fails"] == 0)
    st = jev_guard.circuit_status()
    ok("circuit_status 可读且含阈值", st.get("threshold") == jev_guard.JEV_CIRCUIT_FAILS, st)

    # ---------- 7. state 构造 ----------
    st = jev_guard.build_state([{"role": "user", "content": "在吗"}, {"role": "assistant", "content": "在的"}],
                               "你又忘了吧", "女朋友")
    ok("state: 对方 -> her", st["chat"]["messages"][0]["from"] == "her")
    ok("state: 我 -> me", st["chat"]["messages"][1]["from"] == "me")
    ok("state: 最新消息在最后且为 her",
       st["chat"]["messages"][-1] == {"from": "her", "text": "你又忘了吧"})
    ok("state: relationship", st["chat"]["relationship"] == "女朋友")
    ok("state: latest_from", st["chat"]["latest_from"] == "her")

    # ---------- 8. rank（上游 v1.7 候选条数 1/2/3） ----------
    use("/rank3")
    reset_circuit()
    n0 = hits("/rank3")
    ok("rank: 1 条候选 -> None（上游：1 条不排序，出得更快）",
       jev_guard.rank("u", "x", ["only"]) is None)
    ok("rank: 1 条候选不发请求", hits("/rank3") == n0, "hits=%d" % hits("/rank3"))
    ok("rank: 4 条候选 -> None", jev_guard.rank("u", "x", ["a", "b", "c", "d"]) is None)
    ok("rank: 空候选 -> None", jev_guard.rank("u", "x", []) is None)

    use("/rank2")
    reset_circuit()
    ok("rank: 2 条候选 -> 下标 1", jev_guard.rank("u", "x", ["a", "b"]) == 1)
    use("/rank3")
    reset_circuit()
    ok("rank: 3 条候选 -> 下标 2", jev_guard.rank("u", "x", ["a", "b", "c"]) == 2)

    q1 = jev_guard.build_rank_question(["x"])
    ok("build_rank_question: 1 条 -> 只有 reply_a", set(q1["best_reply"]["criteria"]) == {"reply_a"})
    q2 = jev_guard.build_rank_question(["x", "y"])
    ok("build_rank_question: 2 条 -> reply_a/b", set(q2["best_reply"]["criteria"]) == {"reply_a", "reply_b"})
    q3 = jev_guard.build_rank_question(["x", "y", "z"])
    ok("build_rank_question: 3 条 -> reply_a/b/c", set(q3["best_reply"]["criteria"]) == {"reply_a", "reply_b", "reply_c"})
    try:
        jev_guard.build_rank_question([])
        ok("build_rank_question: 0 条抛 ValueError", False)
    except ValueError:
        ok("build_rank_question: 0 条抛 ValueError", True)
    try:
        jev_guard.build_rank_question(["a", "b", "c", "d"])
        ok("build_rank_question: 4 条抛 ValueError", False)
    except ValueError:
        ok("build_rank_question: 4 条抛 ValueError", True)

    # ---------- 9. 脱敏 ----------
    set_cfg(CREEM_LICENSE_KEY="secret-key-123456")
    ok("密钥被脱敏", "secret-key-123456" not in jev_guard._redact("key=secret-key-123456"))
    set_cfg(CREEM_LICENSE_KEY="")

    # ---------- 10. 签名单测 ----------
    import hmac as _hmac, hashlib as _hl
    _body = json.dumps({"state": {}, "questions": {}}).encode("utf-8")
    _sig = jev_guard._sign("s3cr3t", "/ai/jev/decisions", "100", "nonce", _body)
    _exp = _hmac.new(b"s3cr3t", b"POST\n/ai/jev/decisions\n100\nnonce\n" + _body, _hl.sha256).hexdigest()
    ok("_sign 与手算 HMAC 一致", _sig == _exp)

    # ---------- 11. 签名端到端（mock 后端强制校验） ----------
    use("/auth")
    reset_circuit()
    set_cfg(WEAUATO_CLIENT_SECRET="s3cr3t")
    v_auth = jev_guard.judge("u", "x")
    ok("配了密钥 -> 签名被后端接受", isinstance(v_auth, dict) and len(v_auth) > 0, v_auth)
    set_cfg(WEAUATO_CLIENT_SECRET="")
    ok("未配密钥 -> 无签名 -> 后端拒 -> None", jev_guard.judge("u", "x") is None)

    # ---------- 12. self_test 不参与熔断 ----------
    use("/ok")
    set_cfg(WEAUATO_CLIENT_SECRET="")
    reset_circuit()
    okc, msg = jev_guard.self_test()
    ok("self_test 成功且不熔断", okc is True and not jev_guard._circuit_open(), msg)

finally:
    jev_guard.JEV_TIMEOUT_SEC, jev_guard.JEV_RETRY_TIMEOUT_SEC = _orig_timeouts
    srv.shutdown()

print(f"\n结果: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
