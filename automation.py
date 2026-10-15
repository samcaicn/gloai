# -*- coding: utf-8 -*-
# ***********************************************************************
# v1.4 自动化工作流：关键词触发 / 定时触发的「指定回复」规则引擎。
# 存储：automation_rules.json（与 forward_rules.json 同级，跟随冻结态 weauto 子目录）
# 规则结构：
# {
#   "rules": [
#     {
#       "id": "rule_xxx", "name": "规则名", "enabled": true,
#       "trigger": {"type": "keyword"|"timer", "value": "关键词"|"HH:MM"},
#       "scope": {"window": ""},   # 空=全部窗口；非空=目标窗口子串匹配
#       "action": {"type": "reply"|"active", "content": "要发送的内容"},
#       "cooldown": 60             # 秒（keyword 用）；timer 固定按天一次
#     }
#   ]
# }
# ***********************************************************************
import os
import sys
import json
import time
import threading
from datetime import datetime

from filelock import FileLock

try:
    import weauto_stdio as _weauto_stdio
    _weauto_stdio.ensure_stdio()
except Exception:
    pass


def _base_dir():
    if getattr(sys, "frozen", False):
        d = os.path.join(os.path.dirname(os.path.abspath(sys.executable)), "weauto")
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
        return d
    return os.path.dirname(os.path.abspath(__file__))


def _rules_path():
    return os.path.join(_base_dir(), "automation_rules.json")


def _lock_path():
    return _rules_path() + ".lock"


def load_rules():
    try:
        with open(_rules_path(), "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get("rules"), list):
            return data
    except Exception:
        pass
    return {"rules": []}


def save_rules(data):
    norm = {"rules": (data.get("rules", []) if isinstance(data, dict) else data)}
    try:
        os.makedirs(_base_dir(), exist_ok=True)
    except Exception:
        pass
    with FileLock(_lock_path()):
        tmp = _rules_path() + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(norm, f, ensure_ascii=False, indent=2)
        os.replace(tmp, _rules_path())


# ---- 进程内触发状态（cooldown / 每日一次）----
_last_fire = {}          # rule_id -> epoch seconds
_timer_fired_days = {}   # rule_id -> "YYYY-MM-DD"
_LOCK = threading.RLock()


def _check_cooldown(rule, now_ts):
    rule_id = rule.get("id") or id(rule)
    cd = int(rule.get("cooldown") or 0)
    if cd <= 0:
        return True
    with _LOCK:
        last = _last_fire.get(rule_id)
        if last is None or (now_ts - last) >= cd:
            _last_fire[rule_id] = now_ts
            return True
    return False


def _scope_match(rule, chat):
    window = (rule.get("scope") or {}).get("window") or ""
    if not window:
        return True
    return window in (chat or "")


def check_keyword(chat, content):
    """消息内容命中关键词规则（enabled + scope + keyword + cooldown）→ 返回规则；否则 None。"""
    if not content:
        return None
    data = load_rules()
    now_ts = time.time()
    for rule in data.get("rules", []):
        if not rule.get("enabled"):
            continue
        trig = rule.get("trigger") or {}
        if trig.get("type") != "keyword":
            continue
        kw = (trig.get("value") or "").strip()
        if not kw or kw not in content:
            continue
        if not _scope_match(rule, chat):
            continue
        if not _check_cooldown(rule, now_ts):
            continue
        return rule
    return None


def check_timers(now=None):
    """到点（HH:MM）的定时规则（每天触发一次）。"""
    now = now or datetime.now()
    hhmm = now.strftime("%H:%M")
    day = now.strftime("%Y-%m-%d")
    data = load_rules()
    fired = []
    for rule in data.get("rules", []):
        if not rule.get("enabled"):
            continue
        trig = rule.get("trigger") or {}
        if trig.get("type") != "timer":
            continue
        if (trig.get("value") or "").strip() != hhmm:
            continue
        rule_id = rule.get("id") or id(rule)
        with _LOCK:
            if _timer_fired_days.get(rule_id) == day:
                continue
            _timer_fired_days[rule_id] = day
        fired.append(rule)
    return fired


def _timer_loop(send_cb, stop_evt):
    while not stop_evt.is_set():
        try:
            for rule in check_timers():
                act = rule.get("action") or {}
                window = (rule.get("scope") or {}).get("window") or ""
                content = (act.get("content") or "").strip()
                if act.get("type") == "active" and window and content:
                    try:
                        send_cb(window, content, rule)
                    except Exception as e:
                        print(f"[automation] 定时发送失败: {e}")
        except Exception:
            pass
        stop_evt.wait(30)


def start_timer_loop(send_cb):
    """启动定时规则检查线程（幂等）。send_cb(window, content, rule) 由 bot 注入。"""
    with _LOCK:
        if globals().get("_loop_started"):
            return
        _stop_evt = threading.Event()
        t = threading.Thread(target=_timer_loop, args=(send_cb, _stop_evt), daemon=True,
                             name="automation-timer")
        t.start()
        globals()["_loop_started"] = True
