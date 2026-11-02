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
    {"cmd":"send","peer":"wxid_xxx|xxx@chatroom","text":"...","req":"id1"}
    {"cmd":"send_image","peer":"...","b64":"<base64 png/jpg>","req":"id2"}
    {"cmd":"sessions","limit":100,"req":"id3"}

  响应（均回带 req 原样回传）：
    {"type":"sent","peer":"...","ok":true|false,"msg":"...","req":"id1"}
    {"type":"sent_image","peer":"...","ok":true|false,"msg":"...","req":"id2"}
    {"type":"sessions","sessions":[{"id","name","is_group","summary","last_time"}],"req":"id3"}

行为：
  * WeChatDB + Listener 全会话增量监听（私聊+群聊，新会话自动发现）；
    只转发「他人发的文本消息」（type==1，sender_id==2 视为自己，跳过）。
  * 发送用 guia.quick_send(text, who)，who 传会话 username（wxid_xxx / xxx@chatroom）。
  * 发图用 guia.quick_send_image(path, who)：b64 先落临时文件再发。
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
        from wechatauto.guia import quick_send, quick_send_image
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
        kind = cmd.get("cmd")
        req = cmd.get("req") or ""

        if kind == "send":
            peer = (cmd.get("peer") or "").strip()
            text = cmd.get("text") or ""
            if not peer or not text:
                out({"type": "sent", "peer": peer, "ok": False, "msg": "peer/text 为空", "req": req})
                continue
            try:
                r = quick_send(text, peer)
                ok = bool(r.get("ok", True)) if isinstance(r, dict) else True
                msg = (r.get("msg") or "") if isinstance(r, dict) else ""
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
                # 扩展名按图片魔数判断（jpg/png），wechatauto 发图按扩展名处理
                ext = ".jpg" if raw[:3] == b"\xff\xd8\xff" else ".png"
                fd, tmp = tempfile.mkstemp(prefix="wxauto_", suffix=ext)
                with os.fdopen(fd, "wb") as f:
                    f.write(raw)
                r = quick_send_image(tmp, peer)
                ok = bool(r.get("ok", True)) if isinstance(r, dict) else True
                msg = (r.get("msg") or "") if isinstance(r, dict) else ""
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
                        continue  # 跳过公众号
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
