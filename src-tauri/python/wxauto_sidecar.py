# -*- coding: utf-8 -*-
"""wxauto_sidecar.py —— bbt-zb-tauri 的微信收发通道（wechatauto 引擎）

由 Rust (src-tauri/src/wxauto.rs) 以子进程方式启动，通过 stdin/stdout 行 JSON 通信：

  stdout -> Rust（每行一个 JSON 对象，ensure_ascii=False, utf-8）
    {"type":"status","event":"ready"|"error","self":"昵称","msg":"..."}
    {"type":"in","peer":"wxid_xxx|xxx@chatroom","is_group":true|false,
     "sender":"10001","sender_name":"昵称","text":"..."}
    {"type":"sent","peer":"...","ok":true|false,"msg":"..."}
    {"type":"ping","ts":1690000000}

  stdin <- Rust
    {"cmd":"send","peer":"wxid_xxx|xxx@chatroom","text":"..."}

行为：
  * WeChatDB + Listener 全会话增量监听（私聊+群聊，新会话自动发现）；
    只转发「他人发的文本消息」（type==1，sender_id==2 视为自己，跳过）。
  * 发送用 guia.quick_send(text, who)，who 传会话 username（wxid_xxx / xxx@chatroom）。
  * 心跳每 15s 一条 ping，Rust 侧据此判活。
"""

from __future__ import annotations

import json
import os
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


def out(obj: dict) -> None:
    try:
        sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


def main() -> int:
    # vendored wechatauto（默认 weauto 工程的 vendor 目录，可被 WX_VENDOR 覆盖）
    vendor = os.environ.get("WX_VENDOR") or r"C:\d\weauto\vendor"
    if os.path.isdir(vendor) and vendor not in sys.path:
        sys.path.insert(0, vendor)

    try:
        from wechatauto.db import WeChatDB, Listener
        from wechatauto.guia import quick_send
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
            # 只回「别人发的文本」；type 1=文本；sender_id 2=自己
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

    lst = Listener(db, interval=1.5)
    lst.add_all(on_msg, discover=True)
    lst.start()

    # 心跳线程
    def heartbeat() -> None:
        while True:
            out({"type": "ping", "ts": int(time.time())})
            time.sleep(15)

    threading.Thread(target=heartbeat, daemon=True).start()

    # stdin 命令循环（Rust 关闭 stdin 或进程退出即结束）
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except Exception:
            continue
        if cmd.get("cmd") != "send":
            continue
        peer = (cmd.get("peer") or "").strip()
        text = cmd.get("text") or ""
        if not peer or not text:
            out({"type": "sent", "peer": peer, "ok": False, "msg": "peer/text 为空"})
            continue
        try:
            r = quick_send(text, peer)
            ok = bool(r.get("ok", True)) if isinstance(r, dict) else True
            msg = (r.get("msg") or "") if isinstance(r, dict) else ""
            out({"type": "sent", "peer": peer, "ok": ok, "msg": str(msg)})
        except Exception as e:
            out({"type": "sent", "peer": peer, "ok": False, "msg": repr(e)})

    lst.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
