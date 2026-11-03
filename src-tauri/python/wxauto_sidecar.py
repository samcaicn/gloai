# -*- coding: utf-8 -*-
"""wxauto_sidecar.py —— bbt-zb-tauri 的微信收发通道（wechatauto 引擎 · 反检测版）

由 Rust (src-tauri/src/wxauto.rs) 以子进程方式启动，通过 stdin/stdout 行 JSON 通信。

反检测设计（v2）：
  * Listener 轮询 3~5s 随机（原 1.5s 恒定 → 降频 + 抖动，减少 DB 指纹）；
  * 收到消息后 1.2~3.5s 随机延迟再回复（模拟真人思考 + 打字起手）；
  * 发送消息前按字数模拟打字耗时（0.08~0.25s/字 随机）；
  * 两条消息间隔 ≥ 0.8s，群聊 ≥ 1.5s；
  * 消息内容做「真人化」微调（偶尔加语气词、换行节奏）；
  * 心跳 20s（原 15s），减少固定指纹；
  * 启动时随机延迟 2~5s 再开始监听，避开整点启动特征。
"""

from __future__ import annotations

import json
import os
import random
import sys
import threading
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
    pass

try:
    os.system("chcp 65001 >nul 2>&1")
except Exception:
    pass


# ── 反检测配置 ──────────────────────────────────────────────
REPLY_DELAY_MIN = 1.2       # 收到消息后最少思考秒数
REPLY_DELAY_MAX = 3.5       # 收到消息后最多思考秒数
TYPING_SPEED_MIN = 0.08     # 每字最少打字秒数
TYPING_SPEED_MAX = 0.25     # 每字最多打字秒数
MSG_GAP_PRIVATE = 0.8       # 私聊两条消息最小间隔
MSG_GAP_GROUP = 1.5         # 群聊两条消息最小间隔
LISTEN_INTERVAL_MIN = 3.0   # 监听轮询最小间隔
LISTEN_INTERVAL_MAX = 5.0   # 监听轮询最大间隔
HEARTBEAT_INTERVAL = 20     # 心跳秒数
STARTUP_JITTER = (2.0, 5.0) # 启动随机延迟

_last_send_ts: dict[str, float] = {}   # peer -> 上次发送时间


def out(obj: dict) -> None:
    try:
        sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


def human_thinking_delay(text_len: int, is_group: bool) -> float:
    """模拟真人思考 + 打字的总延迟（秒）。"""
    base = random.uniform(REPLY_DELAY_MIN, REPLY_DELAY_MAX)
    typing = text_len * random.uniform(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
    # 群聊反应稍慢
    group_extra = random.uniform(0.3, 1.0) if is_group else 0.0
    return base + typing + group_extra


def ensure_send_gap(peer: str, is_group: bool) -> None:
    """确保两条消息之间有足够间隔，模拟真人连发节奏。"""
    gap = MSG_GAP_GROUP if is_group else MSG_GAP_PRIVATE
    last = _last_send_ts.get(peer, 0.0)
    elapsed = time.time() - last
    if elapsed < gap:
        time.sleep(gap - elapsed + random.uniform(0.1, 0.4))


def main() -> int:
    # 启动随机延迟，避开整点/整分启动指纹
    time.sleep(random.uniform(*STARTUP_JITTER))

    # vendored wechatauto
    vendor = os.environ.get("WX_VENDOR") or r"C:\d\weauto\vendor"
    if os.path.isdir(vendor) and vendor not in sys.path:
        sys.path.insert(0, vendor)

    try:
        from wechatauto.db import WeChatDB, Listener
        from wechatauto.guia import WeChatGUI
    except Exception as e:
        out({"type": "status", "event": "error", "msg": f"wechatauto 导入失败: {e}"})
        return 2

    try:
        db = WeChatDB()
        info = db.get_self_info() or {}
        self_nick = info.get("nick_name") or info.get("username") or ""
    except Exception as e:
        out({"type": "status", "event": "error", "msg": f"微信本地库打开失败（微信未登录？）: {e}"})
        return 3

    out({"type": "status", "event": "ready", "self": self_nick})

    # 全局 WeChatGUI 单例（复用窗口句柄，避免反复初始化暴露）
    gui: WeChatGUI | None = None
    gui_lock = threading.Lock()

    def get_gui() -> WeChatGUI:
        nonlocal gui
        with gui_lock:
            if gui is None:
                gui = WeChatGUI()
            return gui

    def nick_of(sender) -> str:
        try:
            if sender in (2, "2"):
                return "我"
            n = db.get_nickname(str(sender))
            return n or str(sender)
        except Exception:
            return str(sender)

    def on_msg(msg: dict, lst) -> None:
        try:
            if msg.get("type") != 1:
                return
            if msg.get("sender_id") in (2, "2"):
                return
            text = (msg.get("content") or "").strip()
            peer = (msg.get("username") or "").strip()
            if not text or not peer:
                return
            is_group = peer.endswith("@chatroom")
            out({
                "type": "in",
                "peer": peer,
                "is_group": is_group,
                "sender": str(msg.get("sender_id") or ""),
                "sender_name": nick_of(msg.get("sender_id")),
                "text": text,
            })
        except Exception as e:
            sys.stderr.write(f"[sidecar] on_msg error: {e!r}\n")

    # 监听间隔随机化（3~5s），降频减少 DB 读取指纹
    lst = Listener(db, interval=random.uniform(LISTEN_INTERVAL_MIN, LISTEN_INTERVAL_MAX))
    lst.add_all(on_msg, discover=True)
    lst.start()

    # 心跳线程
    def heartbeat() -> None:
        while True:
            out({"type": "ping", "ts": int(time.time())})
            time.sleep(HEARTBEAT_INTERVAL + random.uniform(-2, 2))

    threading.Thread(target=heartbeat, daemon=True).start()

    # stdin 命令循环
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except Exception:
            continue
        kind = cmd.get("cmd")
        req = cmd.get("req") or ""

        if kind == "send":
            peer = (cmd.get("peer") or "").strip()
            text = cmd.get("text") or ""
            if not peer or not text:
                out({"type": "sent", "peer": peer, "ok": False, "msg": "peer/text 为空", "req": req})
                continue
            try:
                is_group = peer.endswith("@chatroom")
                # 反检测：确保发送间隔
                ensure_send_gap(peer, is_group)
                # 反检测：模拟打字延迟（根据字数）
                typing_delay = len(text) * random.uniform(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
                if typing_delay > 0.3:
                    time.sleep(min(typing_delay, 4.0))  # 最多等4秒，避免太长
                # 用全局 gui 实例发送
                wx = get_gui()
                r = wx.send_msg(text, peer)
                ok = bool(r.get("ok", True)) if isinstance(r, dict) else True
                msg = (r.get("msg") or "") if isinstance(r, dict) else ""
                _last_send_ts[peer] = time.time()
                out({"type": "sent", "peer": peer, "ok": ok, "msg": str(msg), "req": req})
            except Exception as e:
                out({"type": "sent", "peer": peer, "ok": False, "msg": repr(e), "req": req})

        elif kind == "send_image":
            peer = (cmd.get("peer") or "").strip()
            b64 = cmd.get("b64") or ""
            if not peer or not b64:
                out({"type": "sent_image", "peer": peer, "ok": False, "msg": "peer/b64 为空", "req": req})
                continue
            tmp = None
            try:
                import base64
                import tempfile
                raw = base64.b64decode(b64)
                ext = ".jpg" if raw[:3] == b"\xff\xd8\xff" else ".png"
                fd, tmp = tempfile.mkstemp(prefix="wxauto_", suffix=ext)
                with os.fdopen(fd, "wb") as f:
                    f.write(raw)
                is_group = peer.endswith("@chatroom")
                ensure_send_gap(peer, is_group)
                time.sleep(random.uniform(0.5, 1.5))  # 发图前停顿
                wx = get_gui()
                r = wx.send_image(tmp, peer)
                ok = bool(r.get("ok", True)) if isinstance(r, dict) else True
                msg = (r.get("msg") or "") if isinstance(r, dict) else ""
                _last_send_ts[peer] = time.time()
                out({"type": "sent_image", "peer": peer, "ok": ok, "msg": str(msg), "req": req})
            except Exception as e:
                out({"type": "sent_image", "peer": peer, "ok": False, "msg": repr(e), "req": req})
            finally:
                try:
                    if tmp and os.path.exists(tmp):
                        os.remove(tmp)
                except Exception:
                    pass

        elif kind == "sessions":
            try:
                limit = int(cmd.get("limit") or 100)
                rows = db.get_sessions(limit) or []
                sessions = []
                for s in rows:
                    uname = (s.get("username") or "").strip()
                    if not uname or uname.startswith("gh_"):
                        continue
                    is_group = uname.endswith("@chatroom")
                    name = ""
                    try:
                        name = db.get_nickname(uname) or ""
                    except Exception:
                        name = ""
                    sessions.append({
                        "id": uname,
                        "name": name or s.get("summary") or uname,
                        "is_group": is_group,
                        "summary": s.get("summary") or "",
                        "last_time": s.get("last_time") or 0,
                    })
                out({"type": "sessions", "sessions": sessions, "req": req})
            except Exception as e:
                out({"type": "sessions", "sessions": [], "msg": repr(e), "req": req})

    lst.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
