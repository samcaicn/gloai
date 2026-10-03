# -*- coding: utf-8 -*-
"""Jev 判断式 AI 闸门（WeAuto EXE 版）

技术来源与许可
--------------
移植自 jev-chat-jarvis（Android）的 tools/jev —— https://github.com/jev-chat/jev-chat-jarvis
Copyright (c) 2026 Finderchangchang and the jev-chat contributors，MIT 许可（见其 LICENSE/NOTICE）。
本文件保留其 MIT 声明；题目集（JUDGE_QUESTIONS）与 build_state/build_rank_question 为其作品的
Python 改写版。

Jev 是什么
----------
Jev 不是聊天模型，是「判断模型」：喂 state + questions，返回结构化判断，题型只有三种
    noul   是非   -> {"noul": 0..1}
    choice 单选   -> {"choice": "<criteria 里的 key>"}
    score  打分   -> {"score": 0..bins-1}
一次 7 题在原实现里约 900ms。它不生成任何回复文字，只回答"这段对话现在是什么状况"。

为什么放在 Cloudflare Worker 上
-------------------------------
原实现调 OpenRouter /alpha/decisions（需境外账号 + 美元结算）。本项目改为调自建 Worker
的 /ai/jev/decisions：Worker 内部用 Cloudflare Workers AI 的 JSON Mode（json_schema）跑
同一套题目，返回结构保持 {answers:{...}} 兼容 —— 不出 Cloudflare、无需 OpenRouter。
Jev 判断是 Workers AI 推理能力，与 Creem 卡密无关。

客户端 ↔ CF 后台的通信鉴权（防白嫖 Workers AI 额度）
----------------------------------------------------
Jev 端点默认在 Worker 端「配置即启用」HMAC-SHA256 客户端签名：两端（EXE 配置
WEAUATO_CLIENT_SECRET 与 Worker 端 env.WEAUATO_CLIENT_SECRET）填入相同密钥后，
每次请求携带：
    X-WeAuto-Ts    unix 秒（±60s 有效，防重放）
    X-WeAuto-Nonce 随机 hex
    X-WeAuto-Sig   HMAC_SHA256(密钥, "POST\n<path>\n<ts>\n<nonce>\n<body>")
Worker 端未配置该密钥时端点保持公开（开发/向后兼容）。这不是支付卡密，
是「自己的客户端 ↔ 自己的后台」之间的通信密钥，密钥不进公开仓库（用 wrangler secret 注入）。

在 WeAuto 里的作用
------------------
出厂即自动运行，**零配置**（没有开关、没有 URL 输入框、没有密钥框）：
回复前先判断「对方真实意图 / 危险度 / 需要什么 / 下一步最佳动作」，
  1. guidance_text()  -> 注入 system 提示，让主模型照着判
  2. should_hold()    -> 危险度过高时收声不回（出厂关闭）

所有参数都是本文件顶部的内置常量（JEV_*），用户与 UI 都不需要碰。
要调行为改常量即可，不需要动 config.py。

设计红线（24h 鲁棒性）
----------------------
- 纯标准库，不引第三方依赖。
- 出厂即用，绝不因此让 bot 变哑巴或变慢：端点连续失败 JEV_CIRCUIT_FAILS 次
  就在本进程内熔断（不再每条都白等超时），成功一次即恢复。
- 任何异常、超时、非 200、解析失败一律返回 None / False —— 降级为"照常回复"。
- 密钥不落日志（_redact）。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------- 题目集（MIT，来自 jev-chat-jarvis）

# instructions / criteria 用英文写（Jev 主训练语言是英文），聊天正文保留中文原文。
# 改这里等于改校准过的判据，除非明确知道在做什么，否则别动。
JUDGE_QUESTIONS = {
    "literal_question": {
        "type": "noul",
        "instructions": (
            "Is the other person's latest message meant purely literally, with no subtext? "
            "Judge from the whole thread, not one sentence in isolation."
        ),
        "criteria": {
            "true": (
                "The latest message is a straightforward statement, question, or plan "
                "with no implied accusation, test, sarcasm, hint, or unsaid request."
            ),
            "false": (
                "There is subtext: a test of whether you remember or care, sarcasm, "
                "an implied complaint, a hint they will not say outright, a trap question, "
                "an accusation dressed as a question, or a cold/short line that really means blame."
            ),
        },
    },
    "true_intent": {
        "type": "choice",
        "instructions": (
            "What is the other person's true intent in the latest message, given the full conversation? "
            "Prefer tone and context over surface wording. "
            "If they are checking whether you remember something or still care, choose confirm_you_care "
            "even if the words look like a request to 'say it' or to do something. "
            "If they already accepted and closed the matter peacefully, choose close_topic. "
            "Ending the relationship, deleting you, or 'don't talk to me' is vent_anger, never close_topic."
        ),
        "criteria": {
            "confirm_you_care": (
                "They are testing whether you remember, pay attention, or still care. "
                "Signals: 'did you forget again', 'then say it', 'you better', sarcastic 'busy person', "
                "asking you to prove you know a past conversation."
            ),
            "vent_anger": (
                "They are angry or hurt and mainly want the feeling acknowledged. "
                "They are blaming or raising the temperature; a specific plan is not the main point yet."
            ),
            "request_action": (
                "They want a concrete action, time, deliverable, or commitment from you now, "
                "and this is a real ask, not a loyalty test."
            ),
            "seek_explanation": (
                "They want a factual explanation of why something happened. "
                "They asked why or what is going on, not mainly for an apology or a new plan."
            ),
            "casual_chat": (
                "Light talk, banter, sharing, teasing with a laugh, or friendly logistics "
                "with no emotional test and no conflict."
            ),
            "close_topic": (
                "Peaceful wrap-up only: they accepted an apology, confirmed a happy plan, said thanks, "
                "or clearly signaled they need nothing more. "
                "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'."
            ),
        },
    },
    "danger_level": {
        "type": "score",
        "instructions": (
            "How close is this conversation to a fight or to hurting the relationship? "
            "Match the current scene. "
            "If they genuinely accepted an apology or confirmed a happy plan, score the cooled-down present, "
            "not an earlier complaint. "
            "If an ultimatum (break up, report to the boss, stop covering for you) is still in force "
            "and has not been withdrawn, stay in that high bin even if the latest line names a specific task."
        ),
        "criteria": [
            "Light chat or joking; no complaint, no test, no deadline.",
            "Mild tease or a small reminder that is easy to laugh off; a clumsy reply would only feel slightly awkward.",
            "A mild complaint or 'please remember next time' said without heat; they still send warm or practical follow-ups.",
            "Noticeable unhappiness; they mention being forgotten, ignored, or kept waiting, but still give you a chance to make it right.",
            "Sarcasm, cold short replies, or 'you better'; they are testing you, and a sloppy or fake-confident reply will escalate.",
            "Openly upset; they accuse you of not listening or not caring; they expect a real response, not a joke.",
            "Clearly angry and blaming you; a wrong reply will turn this into a fight.",
            "Last-chance warning. They will not cover for you, do not want to keep talking unless this changes, "
            "or tell you to finish a named checklist yourself because trust is almost gone.",
            "An ultimatum is already on the table even if they also give a practical next step: "
            "break up if you forget again, report you tonight, or stop working together if you miss this.",
            "Active rupture: they said it is over, told you not to reply, deleted you, or are exploding.",
        ],
    },
    "should_reply_now": {
        "type": "noul",
        "instructions": (
            "Should your next message contain substantive content? "
            "Substantive means: admitting a specific known fault, giving a concrete time/plan/deliverable, "
            "explaining facts you actually know, or reciting the recalled content they asked you to say. "
            "This is NOT 'should you send any message'. Timing is irrelevant. "
            "Answer FALSE if the thing they want you to recite or prove is not present in this snippet. "
            "Answer FALSE if they already accepted and closed the topic."
        ),
        "criteria": {
            "true": (
                "The needed fact, named fault, or named time/place is already in this snippet, "
                "and they are waiting for that substance now."
            ),
            "false": (
                "Do not put substance in the next message: the recalled content is not in this snippet, "
                "they are testing whether you remember, a holding line is enough, "
                "saying less is safer, or they already closed the topic."
            ),
        },
    },
    "best_action": {
        "type": "choice",
        "instructions": (
            "What type of next action is best? Do not decide whether to send a message immediately. "
            "Ignore timing. Choose only the action type. "
            "If they asked you to recall a specific past message or event and you have not shown that "
            "you actually remember it, choose check_history — do not apologize or invent a plan instead."
        ),
        "criteria": {
            "check_history": "Look up prior chat or facts before taking a position.",
            "apologize": "Lead with a sincere apology for a real mistake or hurt already identified.",
            "give_commitment": "Give a concrete promise, deadline, or arrangement they asked for.",
            "explain": "Explain what happened or why, without leading with apology or a new plan.",
            "acknowledge": "Show you heard them and care, without new facts, an apology, or a plan.",
            "say_less": "Keep it short or add nothing. Extra words would over-explain or pour fuel on it.",
            "make_plan": "Propose or confirm logistics (time, place, task) for a non-conflict request.",
        },
    },
    "she_needs": {
        "type": "choice",
        "instructions": (
            "What does the other person need from you right now? Judge the LATEST message first. "
            "If they genuinely accepted (thanks / got it / 没事了 / 那就这样 / 收到了 / 过去了), "
            "you MUST choose nothing, even if earlier they wanted action or an apology. "
            "Sarcastic 'I'm used to it', 'whatever', 'I don't want to hear it' is NOT genuine satisfaction. "
            "If they asked you to recap a named time/place/date, choose action. "
            "If they are testing whether you remember or still care, and the content is unnamed, choose care."
        ),
        "criteria": {
            "apology": "They need a sincere apology for hurt or a mistake, and have not accepted one yet.",
            "action": "They need a concrete action, time, commitment, or follow-through, and have not accepted one yet.",
            "explanation": "They need a clear explanation of what happened or why, and have not received it.",
            "care": "They need proof you remember, listen, or care — a loyalty or attention test.",
            "nothing": (
                "They need nothing further. Genuine acceptance, a peaceful closed topic, "
                "warm casual chat with no ask, or a rupture where they told you not to reply."
            ),
        },
    },
    "tension_resolved": {
        "type": "noul",
        "instructions": (
            "Has interpersonal tension already been resolved? "
            "Answer true only if there was never tension, or the other person has clearly accepted, "
            "cooled down, joked again, or said it is fine. "
            "A sarcastic 'you better', an unanswered test, leftover blame, or an open ultimatum means false."
        ),
        "criteria": {
            "true": "No remaining tension: they accepted, joked again, said it's fine, or the chat was never tense.",
            "false": "Tension is still present: they are waiting, testing, angry, sarcastic, or the issue is open.",
        },
    },
}

# 判据 -> 中文人话（注入提示词用）
_INTENT_CN = {
    "confirm_you_care": "在试探你还记不记得 / 在不在意",
    "vent_anger": "在发泄情绪，想被看见、被承认",
    "request_action": "要一个具体的行动、时间或承诺",
    "seek_explanation": "想知道事情的原委",
    "casual_chat": "轻松闲聊，没有情绪诉求",
    "close_topic": "已经收尾，不需要你再多说",
}
_ACTION_CN = {
    "check_history": "先确认事实/翻记录，别急着下结论",
    "apologize": "先真诚道歉（针对已经明确的那件事）",
    "give_commitment": "给出明确承诺 / 时间 / 安排",
    "explain": "解释发生了什么、为什么",
    "acknowledge": "表达你在听、你在意，不加新事实，也不道歉",
    "say_less": "少说，甚至不补内容，多说会火上浇油",
    "make_plan": "确认或提出具体安排（时间/地点/事项）",
}
_NEEDS_CN = {
    "apology": "一个道歉",
    "action": "一个具体行动或答复",
    "explanation": "一个解释",
    "care": "你在意他/她的证明",
    "nothing": "什么都不需要了",
}

# ---------------------------------------------------------------- 配置读取

# 浏览器 UA：Cloudflare WAF 对 Python-urllib 默认 UA 直接 403（与 creem 直连同一个坑）
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

_ROOT = os.path.dirname(os.path.abspath(__file__))
_CFG_CACHE = {"ts": 0.0, "values": {}}
_CFG_TTL = 30.0  # 秒：WebUI 改完配置不必重启 bot 也能生效

# ------------------------------------------------------------------ 零配置常量
# Jev 判断式 AI 是产品内置能力，**不设任何用户可见配置项**：出厂即自动运行，
# 用户不需要知道自己需要配 URL / 密钥 / 开关。以下为出厂固定值，代码里直接写死。
# 唯一仍从 config.py 读的是「客户端↔Worker 通信密钥」与卡密（用于签名与脱敏），
# 那两项属于授权体系而非 Jev 业务参数。
JEV_ENDPOINT = "https://weauto.safeopc.cn/ai/jev/decisions"  # 自建 Worker 端点
JEV_MODEL_NAME = ""          # 留空 = 用 Worker 端 wrangler.toml 的默认模型（实测 70B 判得准）
JEV_TIMEOUT_SEC = 8.0        # 单次判断超时（秒）；超时/失败一律降级为照常回复
JEV_RELATIONSHIP = "微信联系人"  # 填进判断的「我和对方是什么关系」
JEV_CONTEXT_TURNS = 6        # 带进判断的最近对话条数（一问一答算 2 条）
JEV_INJECT_GUIDANCE = True   # 把判断结论注入 system 提示，指导主模型语气与内容
JEV_HOLD_ON_DANGER = False   # 危险度过高时 bot 收声不回（交给真人处理）
JEV_DANGER_HOLD_LEVEL = 8    # 收声阈值 0..9（8 = 最后通牒级别）
# 端点不可用时自动降级的场景：连续失败到该次数后，本进程内熔断 Jev（不再每条都等超时），
# 保证网络异常时 bot 不会因判断而整体变慢。成功一次即恢复。
JEV_CIRCUIT_FAILS = 3

# 仍需从 config.py 读取的项（授权体系，非 Jev 业务参数）
_DEFAULTS = {
    "CREEM_LICENSE_KEY": "",
    "WEAUATO_CLIENT_SECRET": "",     # 客户端↔后台通信密钥（防白嫖）；留空=公开模式
}


def _cfg(key, default=None):
    """从 config.py 读配置（带 30s 缓存，读不到用默认值）。绝不抛异常。"""
    if default is None:
        default = _DEFAULTS.get(key)
    now = time.time()
    cache = _CFG_CACHE
    if now - cache["ts"] < _CFG_TTL and key in cache["values"]:
        return cache["values"][key]
    try:
        with open(os.path.join(_ROOT, "config.py"), "r", encoding="utf-8") as f:
            content = f.read()
    except Exception:
        return default
    values = {}
    for name, dv in _DEFAULTS.items():
        m = re.search(rf"^{re.escape(name)}\s*=\s*(.+)$", content, re.M)
        if not m:
            values[name] = dv
            continue
        raw = m.group(1).strip()
        low = raw.lower()
        if low in ("true", "false"):
            values[name] = low == "true"
        else:
            try:
                import ast

                values[name] = ast.literal_eval(raw)
            except Exception:
                values[name] = raw.strip("'\"")
    cache["ts"] = now
    cache["values"] = values
    return values.get(key, default)


def reload_config():
    """强制下一次读取重新解析 config.py（WebUI 保存配置后调用）。"""
    _CFG_CACHE["ts"] = 0.0
    _CFG_CACHE["values"] = {}


def _redact(text):
    """日志/异常里抹掉卡密与客户端通信密钥。"""
    if not isinstance(text, str):
        text = str(text)
    for k in (_cfg("CREEM_LICENSE_KEY", "") or "", _cfg("WEAUATO_CLIENT_SECRET", "") or ""):
        if k and len(k) >= 6:
            text = text.replace(k, "[REDACTED]")
    return text


def _client_secret():
    """取客户端↔后台通信密钥（WEAUATO_CLIENT_SECRET）；空串=公开模式。"""
    try:
        return (_cfg("WEAUATO_CLIENT_SECRET", "") or "").strip()
    except Exception:
        return ""


def _sign(secret, path, ts, nonce, body_bytes):
    """HMAC-SHA256 签名，与 Worker 端算法一致。

    msg = "POST\\n<path>\\n<ts>\\n<nonce>\\n" + body_bytes
    """
    msg = ("POST\n" + path + "\n" + ts + "\n" + nonce + "\n").encode("utf-8") + body_bytes
    return hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


def _instance_id():
    """机器指纹：与 weauto_license 的激活实例保持一致；取不到就用主机名。"""
    try:
        from weauto_license.guard import get_machine_id

        return str(get_machine_id())
    except Exception:
        try:
            import socket

            return socket.gethostname()
        except Exception:
            return "weauto"


def enabled():
    """Jev 是否生效。零配置：出厂恒为 True，用户无开关可关。

    保留 enabled() 这个函数名是为了让 bot.py / config_editor.py 的调用点
    语义不变（未来若要做「企业版关闭」，只需改这一个函数的返回值）。
    唯一会返回 False 的情况是端点连续失败触发了本进程内的熔断
    （见 _CIRCUIT，见下）—— 此时降级为「不做判断、照常回复」，绝不阻塞 bot。
    """
    try:
        return not _circuit_open()
    except Exception:
        return True


# ---------------------------------------------------------------- 构造请求

def build_state(history, incoming_text, relationship="微信联系人"):
    """把 WeAuto 的 chat_contexts 历史 + 当前消息，转成 Jev 的 state。

    history: [{"role": "user"|"assistant", "content": str}]（bot.py 的 chat_contexts 格式）
             user=对方，assistant=我自己。
    """
    msgs = []
    for item in (history or []):
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        text = item.get("content")
        if not text:
            continue
        msgs.append({"from": "her" if role == "user" else "me", "text": str(text)})
    msgs.append({"from": "her", "text": str(incoming_text or "")})
    msgs = msgs[-20:]
    return {
        "chat": {
            "relationship": relationship or "微信联系人",
            "messages": msgs,
            "latest_from": "her",
        }
    }


def build_rank_question(candidates):
    """3 条候选回复 -> Jev 单选排序题（criteria 保留候选原文）。"""
    if len(candidates) != 3:
        raise ValueError("build_rank_question 需要恰好 3 条候选回复")
    keys = ("reply_a", "reply_b", "reply_c")
    return {
        "best_reply": {
            "type": "choice",
            "instructions": (
                "Which candidate reply is the most appropriate next message, "
                "given the conversation and the other person's true need? "
                "Penalize dismissive, over-promising, or off-topic replies."
            ),
            "criteria": {k: t for k, t in zip(keys, candidates)},
        }
    }


# ---------------------------------------------------------------- 熔断器
# 零配置后 Jev 每条消息都会跑（无开关），所以「端点挂了不能拖慢 bot」这件事
# 比原来更重要：连续失败 JEV_CIRCUIT_FAILS 次后，本进程内直接跳过判断，
# 不再每条都白等一次超时；成功一次即完全恢复。
_CIRCUIT = {"fails": 0, "open": False}
_CIRCUIT_LOCK = threading.Lock()


def _circuit_open() -> bool:
    """熔断是否已打开（打开=暂时不做判断）。"""
    with _CIRCUIT_LOCK:
        return bool(_CIRCUIT["open"])


def _circuit_record(ok: bool) -> None:
    """记录一次调用结果，维护熔断状态。"""
    with _CIRCUIT_LOCK:
        if ok:
            _CIRCUIT["fails"] = 0
            _CIRCUIT["open"] = False
        else:
            _CIRCUIT["fails"] += 1
            if _CIRCUIT["fails"] >= JEV_CIRCUIT_FAILS:
                _CIRCUIT["open"] = True


def circuit_status():
    """给 WebUI 展示的熔断状态（只读，不改状态）。"""
    with _CIRCUIT_LOCK:
        return {"fails": _CIRCUIT["fails"], "open": bool(_CIRCUIT["open"]),
                "threshold": JEV_CIRCUIT_FAILS}


# ---------------------------------------------------------------- HTTP

def _jev_candidate_urls():
    """返回候选 Worker 域名列表（主 + 备），用于单域名抖动时 failover。

    端点是内置常量（零配置）；两个对外域名（wetech.jukuai.net 主用、
    weauto.safeopc.cn 备用）都会尝试，path 完全一致，签名可复用。
    """
    known = ["wetech.jukuai.net", "weauto.safeopc.cn"]
    try:
        p = urllib.parse.urlparse(JEV_ENDPOINT)
        path = p.path or "/ai/jev/decisions"
        scheme = p.scheme or "https"
        host = p.hostname or known[1]
    except Exception:
        scheme, host, path = "https", known[1], "/ai/jev/decisions"
    hosts = [host] + [h for h in known if h != host]
    return [f"{scheme}://{h}{path}" for h in hosts]


def _post_decisions(questions, state, timeout=None, model=None, circuit=True):
    """POST 到 Worker 的 /ai/jev/decisions。返回 ( answers_dict | None, 错误信息|None )。

    用守护线程 + join 兜底：即使 urllib 卡死也不会拖住消息回调线程。
    内部按 _jev_candidate_urls() 顺序 failover：主域名失败（网络/5xx）自动试备用域名。
    circuit=False 时不参与熔断计数（WebUI 手动自检用 —— 用户点一次测试不该
    把线上判断熔断掉，也不该被已熔断的状态挡住）。
    """
    if timeout is None:
        timeout = JEV_TIMEOUT_SEC
    payload = {
        "state": state,
        "questions": questions,
    }
    if model:
        payload["model"] = model
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    # 客户端↔后台通信签名（防白嫖）：填了 WEAUATO_CLIENT_SECRET 才带，否则公开模式。
    # 两个域名 path 相同，签名一次即可复用。
    auth_headers = {}
    secret = _client_secret()
    urls = _jev_candidate_urls()
    if secret:
        try:
            path = urllib.parse.urlparse(urls[0]).path or "/ai/jev/decisions"
            ts = str(int(time.time()))
            nonce = secrets.token_hex(16)
            sig = _sign(secret, path, ts, nonce, body)
            auth_headers = {
                "X-WeAuto-Ts": ts,
                "X-WeAuto-Nonce": nonce,
                "X-WeAuto-Sig": sig,
            }
        except Exception:
            auth_headers = {}
    box = {"res": None, "err": None}

    def _work():
        last_err = "无可用域名"
        for url in urls:
            try:
                req = urllib.request.Request(
                    url,
                    data=body,
                    method="POST",
                    headers={
                        "Content-Type": "application/json; charset=utf-8",
                        "Accept": "application/json",
                        "X-WeAuto-Instance": _instance_id(),
                        # Cloudflare WAF 会把 Python-urllib 的默认 UA 挡成 403（实测 same as creem 直连），
                        # 必须带浏览器 UA，否则请求根本到不了 Worker。
                        "User-Agent": _UA,
                        **auth_headers,
                    },
                )
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    data = json.loads(resp.read().decode("utf-8", errors="replace"))
                answers = data.get("answers") if isinstance(data, dict) else None
                if isinstance(answers, dict):
                    box["res"] = answers
                    return
                last_err = "响应缺少 answers"
            except urllib.error.HTTPError as e:
                try:
                    detail = e.read().decode("utf-8", errors="replace")[:300]
                except Exception:
                    detail = ""
                last_err = f"HTTP {e.code}: {_redact(detail)}"
            except Exception as e:
                last_err = _redact(f"{type(e).__name__}: {e}")
        box["err"] = last_err

    t = threading.Thread(target=_work, daemon=True)
    t.start()
    t.join(timeout * len(urls) + 2)
    if t.is_alive():
        if circuit:
            _circuit_record(False)
        return None, f"超时（>{timeout * len(urls) + 2:.0f}s）未返回"
    if circuit:
        _circuit_record(box["res"] is not None)
    return box["res"], box["err"]


# ---------------------------------------------------------------- 解析

def _answers_of(answers):
    """answers -> 扁平中文可解释的 verdict；缺字段不报错（跳过即可）。"""
    verdict = {}

    def _noul(key):
        a = answers.get(key)
        if not isinstance(a, dict):
            return None
        if "noul" in a:
            try:
                return float(a["noul"]) >= 0.5
            except (TypeError, ValueError):
                return None
        return None

    def _choice(key):
        a = answers.get(key)
        if isinstance(a, dict) and a.get("choice") is not None:
            return str(a["choice"])
        return None

    def _score(key):
        a = answers.get(key)
        if not isinstance(a, dict):
            return None
        v = a.get("score")
        try:
            return int(round(float(v)))
        except (TypeError, ValueError):
            return None

    for k in ("literal_question", "should_reply_now", "tension_resolved"):
        v = _noul(k)
        if v is not None:
            verdict[k] = v
    for k in ("true_intent", "best_action", "she_needs"):
        v = _choice(k)
        if v is not None:
            verdict[k] = v
    v = _score("danger_level")
    if v is not None:
        verdict["danger_level"] = max(0, min(9, v))
    return verdict


# ---------------------------------------------------------------- 对外主入口

def judge(user_id, incoming_text, history=None):
    """跑一次完整判断。返回 verdict dict；任何失败返回 None（调用方照常回复）。

    verdict 形如：
        {"true_intent": "vent_anger", "danger_level": 6, "she_needs": "care",
         "best_action": "acknowledge", "should_reply_now": False,
         "literal_question": False, "tension_resolved": False}
    """
    if not enabled():
        return None
    try:
        if not incoming_text or not str(incoming_text).strip():
            return None
        hist = (history or [])[-(JEV_CONTEXT_TURNS * 2):]
        state = build_state(hist, incoming_text, JEV_RELATIONSHIP)
        answers, err = _post_decisions(JUDGE_QUESTIONS, state, model=(JEV_MODEL_NAME or None))
        if err or not answers:
            return None
        verdict = _answers_of(answers)
        return verdict or None
    except Exception:
        return None


def guidance_text(verdict):
    """把判断结论转成给主模型的中文指令段（注入 system）。无可用结论返回空串。"""
    if not verdict or not isinstance(verdict, dict):
        return ""
    if not JEV_INJECT_GUIDANCE:
        return ""
    lines = ["【对话判断（Jev 判断模型给出，按它调整你的语气与内容）】"]
    intent = verdict.get("true_intent")
    if intent:
        lines.append(f"- 对方真实意图：{intent}（{_INTENT_CN.get(intent, '')}）")
    danger = verdict.get("danger_level")
    if danger is not None:
        lines.append(f"- 关系危险度：{danger}/9" + ("（偏高，措辞要格外小心）" if danger >= 6 else ""))
    needs = verdict.get("she_needs")
    if needs:
        lines.append(f"- 对方现在需要：{_NEEDS_CN.get(needs, needs)}")
    action = verdict.get("best_action")
    if action:
        lines.append(f"- 建议动作：{_ACTION_CN.get(action, action)}")
    if "should_reply_now" in verdict:
        lines.append(
            "- 下一条是否需要实质内容："
            + ("是（给出具体事实/时间/承诺）" if verdict["should_reply_now"] else "否（别硬凑内容，宁可短）")
        )
    if "literal_question" in verdict and not verdict["literal_question"]:
        lines.append("- 注意：对方话里有话，别只按字面回应")
    if "tension_resolved" in verdict:
        lines.append("- 紧张是否已化解：" + ("已化解" if verdict["tension_resolved"] else "未化解"))
    lines.append("- 不要编造上面没有的事实；不确定就说不确定。")
    return "\n".join(lines)


def should_hold(verdict):
    """是否应当收声不回（交给真人处理）。

    出厂关闭（JEV_HOLD_ON_DANGER=False）：危险度 >= JEV_DANGER_HOLD_LEVEL 时
    bot 不自动回复，只在日志里留痕。任何异常一律返回 False（绝不误伤正常回复）。
    """
    try:
        if not JEV_HOLD_ON_DANGER:
            return False
        if not verdict or not isinstance(verdict, dict):
            return False
        level = JEV_DANGER_HOLD_LEVEL
        danger = verdict.get("danger_level")
        return danger is not None and int(danger) >= level
    except Exception:
        return False


def rank(user_id, incoming_text, candidates, history=None):
    """对 3 条候选回复排序，返回最好的那条下标（0/1/2）；失败返回 None。"""
    if not enabled() or len(candidates) != 3:
        return None
    try:
        state = build_state(history or [], incoming_text, JEV_RELATIONSHIP)
        answers, err = _post_decisions(
            build_rank_question(candidates), state, model=(JEV_MODEL_NAME or None)
        )
        if err or not answers:
            return None
        picked = _answers_of(answers).get("best_reply") if isinstance(answers, dict) else None
        # best_reply 是 choice 题型，_answers_of 只认已知 key，这里单独取
        a = answers.get("best_reply")
        if isinstance(a, dict) and a.get("choice"):
            picked = str(a["choice"])
        mapping = {"reply_a": 0, "reply_b": 1, "reply_c": 2}
        return mapping.get(picked)
    except Exception:
        return None


def self_test(timeout=None):
    """连通性自检（WebUI「测试 Jev」按钮用）。返回 (ok: bool, message: str)。"""
    t0 = time.time()
    answers, err = _post_decisions(
        JUDGE_QUESTIONS,
        build_state([], "在吗", JEV_RELATIONSHIP),
        timeout=timeout,
        model=(JEV_MODEL_NAME or None),
        circuit=False,   # 手动自检不参与熔断
    )
    ms = int((time.time() - t0) * 1000)
    if err:
        return False, f"调用失败（{ms}ms）：{err}"
    verdict = _answers_of(answers or {})
    return True, f"OK（{ms}ms）· 拿到 {len(verdict)} 项判断：{verdict}"
