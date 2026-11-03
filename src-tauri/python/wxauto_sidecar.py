# -*- coding: utf-8 -*-
"""wxauto_sidecar.py —— bbt-zb-tauri 的微信收发通道（wechatauto 引擎 · 反检测版）

由 Rust (src-tauri/src/wxauto.rs) 以子进程方式启动，通过 stdin/stdout 行 JSON 通信。

反检测设计（v2）：
  * Listener 轮询 3~5s 随机（原 1.5s 恒定 → 降频 + 抖动，减少 DB 指纹）；
  * 收到消息后 1.2~3.5s 随机延迟再回复（模拟真人思考 + 打字起手）；
  * 发送消息前按字数模拟打字耗时（0.08~0.25s/字 随机）；
  * 两条消息间隔 ≥ 0.8s，群聊 ≥ 1.5s；
  * 心跳 20s（原 15s），减少固定指纹；
  * 启动时随机延迟 2~5s 再开始监听，避开整点启动特征。

消息类型（wechatauto db.Listener 透传的本地 local_type：1=文本 / 3=图片 / 34=语音 SILK）：
  * 文本   → 直接进自动回复管线
  * 图片   → MediaDownloader 解密 .dat → 离线 OCR(PP-OCRv4) → kind="image" 进管线
  * 语音   → MediaDownloader 读 media_*.db 的 SILK → 离线转写(sherpa-onnx) → kind="voice" 进管线
  * 其它（链接/名片/小程序等）暂忽略，仅记日志。
（db.Listener 回调里 type 是中文名「文本/图片/语音」，本脚本同时按 local_type 兜底，跨版本稳。）
OCR/ASR 引擎见同目录 ocrengine.py / asrengine.py（懒加载，缺依赖/缺模型即降级）。
"""

from __future__ import annotations

import base64
import json
import os
import random
import sys
import tempfile
import threading
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
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

# OCR / ASR 引擎（懒加载，进程内缓存）
_ocr = None
_asr = None
_md = None


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
    group_extra = random.uniform(0.3, 1.0) if is_group else 0.0
    return base + typing + group_extra


def ensure_send_gap(peer: str, is_group: bool) -> None:
    """确保两条消息之间有足够间隔，模拟真人连发节奏。"""
    gap = MSG_GAP_GROUP if is_group else MSG_GAP_PRIVATE
    last = _last_send_ts.get(peer, 0.0)
    elapsed = time.time() - last
    if elapsed < gap:
        time.sleep(gap - elapsed + random.uniform(0.1, 0.4))


def get_ocr():
    """返回 OCR 引擎（缓存），不可用返回 None。"""
    global _ocr
    if _ocr is not None:
        return _ocr if _ocr is not False else None
    try:
        from ocrengine import make_engine as make_ocr
        e = make_ocr()
        _ocr = e if e is not None else False
    except Exception as e:
        sys.stderr.write(f"[sidecar] OCR 模块加载失败: {e!r}\n")
        _ocr = False
    return _ocr if _ocr is not False else None


def get_asr():
    """返回 ASR 引擎（缓存），不可用返回 None。"""
    global _asr
    if _asr is not None:
        return _asr if _asr is not False else None
    try:
        from asrengine import make_engine as make_asr
        e = make_asr()
        _asr = e if e is not None else False
    except Exception as e:
        sys.stderr.write(f"[sidecar] ASR 模块加载失败: {e!r}\n")
        _asr = False
    return _asr if _asr is not False else None


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
        from wechatauto.media import MediaDownloader
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

    # ── 媒体字节提取（对齐 weauto：用 wechatauto MediaDownloader 从本地微信库
    #    解密 .dat 图片 / 读 media_*.db 的 SILK 语音；db.get_msg_image 等接口不存在）──
    MEDIA_TMP = os.path.join(tempfile.gettempdir(), "bbt_zb_media")
    try:
        os.makedirs(MEDIA_TMP, exist_ok=True)
    except Exception:
        pass

    def get_md() -> "MediaDownloader":
        global _md
        if _md is None:
            _md = MediaDownloader(db)
        return _md

    def fetch_media(user: str, local_id, kind: str):
        """返回 (bytes, path)；失败 (None, None)。

        user = 会话 wxid（群为 xxx@chatroom），local_id = 消息行号。
        图片走 MediaDownloader.download_image（AES+XOR 解密 .dat → jpg/png/gif）；
        语音走 download_voice（读 media_*.db 的 voice_data SILK 二进制）。
        """
        if not local_id:
            return None, None
        try:
            md = get_md()
            if kind == "image":
                p = md.download_image(user, int(local_id), save_dir=MEDIA_TMP)
            elif kind == "voice":
                p = md.download_voice(user, int(local_id), save_dir=MEDIA_TMP)
            else:
                return None, None
            if p and os.path.isfile(p):
                with open(p, "rb") as f:
                    return f.read(), p
        except Exception as e:
            sys.stderr.write(f"[sidecar] 媒体下载失败({kind} peer={user} lid={local_id}): {e!r}\n")
        return None, None

    def emit_in(peer: str, is_group: bool, kind: str, text: str, sender_name: str, media: str = "") -> None:
        out({
            "type": "in",
            "peer": peer,
            "is_group": is_group,
            "kind": kind,
            "text": text,
            "sender": sender_name,
            "sender_name": sender_name,
            "media": media,
        })

    def handle_image(local_id, peer: str, is_group: bool, sender_name: str) -> None:
        raw, _path = fetch_media(peer, local_id, "image")
        if not raw:
            out({"type": "status", "event": "warn",
                 "msg": f"图片取不到字节（peer={peer}）：MediaDownloader 解密失败，可能需先在微信中打开该图片（触发密钥派生）"})
            return
        engine = get_ocr()
        if engine is None:
            out({"type": "status", "event": "warn",
                 "msg": "OCR 引擎不可用，图片消息暂不自动回复（请把 PP-OCRv4 模型放进 ocr_models/）"})
            return
        try:
            text = engine.ocr_bytes(raw)
        except Exception as e:
            out({"type": "status", "event": "warn", "msg": f"OCR 失败: {e!r}"})
            return
        emit_in(peer, is_group, "image", text or "", sender_name, "图片")

    def handle_voice(local_id, peer: str, is_group: bool, sender_name: str) -> None:
        raw, _path = fetch_media(peer, local_id, "voice")
        if not raw:
            out({"type": "status", "event": "warn",
                 "msg": f"语音取不到字节（peer={peer}）：MediaDownloader 读 media_*.db 失败（语音可能未下载到本地）"})
            return
        engine = get_asr()
        if engine is None:
            out({"type": "status", "event": "warn",
                 "msg": "语音转写引擎不可用，语音消息暂不自动回复（请把 sherpa-onnx 模型放进 asr_models/ 并装 sherpa-onnx/pysilk）"})
            return
        try:
            text = engine.transcribe_silk(raw)
        except Exception as e:
            out({"type": "status", "event": "warn", "msg": f"语音转写失败: {e!r}"})
            return
        emit_in(peer, is_group, "voice", text or "", sender_name, "语音")

    def on_msg(msg: dict, lst) -> None:
        try:
            sender_id = msg.get("sender_id")
            if sender_id in (2, "2"):
                return  # 自己发的，跳过
            peer = (msg.get("username") or "").strip()
            if not peer:
                return
            is_group = peer.endswith("@chatroom")
            sender_name = nick_of(sender_id)

            # wechatauto db.Listener 透传的 type 是中文名（文本/图片/语音）；
            # 兜底按 local_type 数字再判一次，跨版本/语言更稳。
            KIND = {"文本": "text", "图片": "image", "语音": "voice"}
            kind = KIND.get(msg.get("type"))
            if kind is None and msg.get("local_id"):
                try:
                    row = db.get_message_row(peer, msg["local_id"])
                    if row:
                        kind = {1: "text", 3: "image", 34: "voice"}.get(row.get("local_type"))
                except Exception:
                    pass
            if not kind:
                out({"type": "status", "event": "warn", "msg": f"忽略未知消息类型 {msg.get('type')!r} from {peer}"})
                return

            if kind == "text":
                text = (msg.get("content") or "").strip()
                if not text:
                    return
                emit_in(peer, is_group, "text", text, sender_name)
            elif kind == "image":
                handle_image(msg.get("local_id"), peer, is_group, sender_name)
            elif kind == "voice":
                handle_voice(msg.get("local_id"), peer, is_group, sender_name)
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
                ensure_send_gap(peer, is_group)
                typing_delay = len(text) * random.uniform(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
                if typing_delay > 0.3:
                    time.sleep(min(typing_delay, 4.0))
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
                raw = base64.b64decode(b64)
                ext = ".jpg" if raw[:3] == b"\xff\xd8\xff" else ".png"
                fd, tmp = tempfile.mkstemp(prefix="wxauto_", suffix=ext)
                with os.fdopen(fd, "wb") as f:
                    f.write(raw)
                is_group = peer.endswith("@chatroom")
                ensure_send_gap(peer, is_group)
                time.sleep(random.uniform(0.5, 1.5))
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
