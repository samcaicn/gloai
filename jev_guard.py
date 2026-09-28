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
Jev 判断是 Workers AI 推理能力，公开可用，不需要卡密（卡密仅服务 Creem 支付授权）。

在 WeAuto 里的作用
------------------
回复前先判断「对方真实意图 / 危险度 / 需要什么 / 下一步最佳动作」：
  1. guidance_text()  -> 注入 system 提示，让主模型照着判（默认开）
  2. should_hold()    -> 危险度过高时收声不回（默认关，需显式开启）

设计红线（24h 鲁棒性）
----------------------
- 纯标准库，不引第三方依赖。
- 默认关闭（ENABLE_JEV_GUARD=False）；开启即用，绝不因此让 bot 变哑巴或变慢。
- 任何异常、超时、非 200、解析失败一律返回 None / False —— 降级为"照常回复"。
- 密钥不落日志（_redact）。
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.error
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

_DEFAULTS = {
    "ENABLE_JEV_GUARD": False,
    "JEV_BASE_URL": "https://weauto.safeopc.cn/ai/jev/decisions",
    "JEV_MODEL": "",                 # 留空 = 用 Worker 端 wrangler.toml 的默认模型
    "JEV_TIMEOUT": 8.0,
    "JEV_RELATIONSHIP": "微信联系人",
    "JEV_CONTEXT_TURNS": 6,          # 带进判断的最近对话轮数（一问一答算 2 条）
    "JEV_INJECT_GUIDANCE": True,     # 把判断结论注入 system 提示
    "JEV_HOLD_ON_DANGER": False,     # 危险度过高时收声不回
    "JEV_DANGER_HOLD_LEVEL": 8,      # 收声阈值（0..9）
    "CREEM_LICENSE_KEY": "",
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
    """日志/异常里抹掉卡密。"""
    if not isinstance(text, str):
        text = str(text)
    for k in (_cfg("CREEM_LICENSE_KEY", "") or "",):
        if k and len(k) >= 6:
            text = text.replace(k, "[REDACTED]")
    return text


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
    """闸门是否生效。只看 ENABLE_JEV_GUARD 开关（无需卡密，公开可用）。"""
    try:
        return bool(_cfg("ENABLE_JEV_GUARD", False))
    except Exception:
        return False


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


# ---------------------------------------------------------------- HTTP

def _post_decisions(questions, state, timeout=None, model=None):
    """POST 到 Worker 的 /ai/jev/decisions。返回 ( answers_dict | None, 错误信息|None )。

    用守护线程 + join 兜底：即使 urllib 卡死也不会拖住消息回调线程。
    """
    if timeout is None:
        try:
            timeout = float(_cfg("JEV_TIMEOUT", 8.0))
        except Exception:
            timeout = 8.0
    payload = {
        "state": state,
        "questions": questions,
    }
    if model:
        payload["model"] = model
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    box = {"res": None, "err": None}

    def _work():
        try:
            req = urllib.request.Request(
                _cfg("JEV_BASE_URL", _DEFAULTS["JEV_BASE_URL"]),
                data=body,
                method="POST",
                headers={
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                    "X-WeAuto-Instance": _instance_id(),
                    # Cloudflare WAF 会把 Python-urllib 的默认 UA 挡成 403（实测 same as creem 直连），
                    # 必须带浏览器 UA，否则请求根本到不了 Worker。
                    "User-Agent": _UA,
                },
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))
            answers = data.get("answers") if isinstance(data, dict) else None
            if not isinstance(answers, dict):
                box["err"] = "响应缺少 answers"
                return
            box["res"] = answers
        except urllib.error.HTTPError as e:
            try:
                detail = e.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                detail = ""
            box["err"] = f"HTTP {e.code}: {_redact(detail)}"
        except Exception as e:
            box["err"] = _redact(f"{type(e).__name__}: {e}")

    t = threading.Thread(target=_work, daemon=True)
    t.start()
    t.join(timeout + 2)
    if t.is_alive():
        return None, f"超时（>{timeout + 2:.0f}s）未返回"
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
        turns = _cfg("JEV_CONTEXT_TURNS", 6)
        try:
            turns = int(turns)
        except Exception:
            turns = 6
        hist = (history or [])[-(turns * 2):]
        state = build_state(hist, incoming_text, _cfg("JEV_RELATIONSHIP", "微信联系人"))
        answers, err = _post_decisions(JUDGE_QUESTIONS, state, model=(_cfg("JEV_MODEL", "") or None))
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
    if not _cfg("JEV_INJECT_GUIDANCE", True):
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

    默认关闭（JEV_HOLD_ON_DANGER=False）：开启后，危险度 >= JEV_DANGER_HOLD_LEVEL 时
    bot 不自动回复，只在日志里留痕。任何异常一律返回 False（绝不误伤正常回复）。
    """
    try:
        if not _cfg("JEV_HOLD_ON_DANGER", False):
            return False
        if not verdict or not isinstance(verdict, dict):
            return False
        level = int(_cfg("JEV_DANGER_HOLD_LEVEL", 8))
        danger = verdict.get("danger_level")
        return danger is not None and int(danger) >= level
    except Exception:
        return False


def rank(user_id, incoming_text, candidates, history=None):
    """对 3 条候选回复排序，返回最好的那条下标（0/1/2）；失败返回 None。"""
    if not enabled() or len(candidates) != 3:
        return None
    try:
        state = build_state(history or [], incoming_text, _cfg("JEV_RELATIONSHIP", "微信联系人"))
        answers, err = _post_decisions(
            build_rank_question(candidates), state, model=(_cfg("JEV_MODEL", "") or None)
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
    if not _cfg("ENABLE_JEV_GUARD", False):
        return False, "未开启（config.py 的 ENABLE_JEV_GUARD = False）"
    t0 = time.time()
    answers, err = _post_decisions(
        JUDGE_QUESTIONS,
        build_state([], "在吗", _cfg("JEV_RELATIONSHIP", "微信联系人")),
        timeout=timeout,
        model=(_cfg("JEV_MODEL", "") or None),
    )
    ms = int((time.time() - t0) * 1000)
    if err:
        return False, f"调用失败（{ms}ms）：{err}"
    verdict = _answers_of(answers or {})
    return True, f"OK（{ms}ms）· 拿到 {len(verdict)} 项判断：{verdict}"
