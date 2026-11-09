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
import re
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor

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

# stdout 写锁：on_msg 回调线程（每会话一条）、heartbeat 线程、stdin 主线程并发 out()，
# 无锁 write+flush 会导致 JSON 行交错，Rust 端 serde_json 解析失败丢消息。
_out_lock = threading.Lock()

# 媒体处理线程池：OCR/ASR 秒级耗时，丢后台线程做，不阻塞同会话后续文本消息。
_media_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="wxmedia")

# 发送线程池：wechatauto 的 send_msg 走 UI 自动化（会话查找 / UIA / OCR 兜底），
# 单次可能耗时数十秒甚至因窗口状态卡死。早期版本在 stdin 主循环里同步调用，
# 一次卡死就永久阻塞所有后续命令与自动回发（消息仍在上报但永远发不出去）。
# 这里单 worker 串行执行：既避免多个发送抢微信窗口焦点，又不阻塞 stdin 主循环。
#
# 线程无法被强制终止：实测 OCR 兜底会掉进 PIL.ImageGrab.grab()（guia.py:955
# _grab_screen）永不返回，单 worker 被永久占满。因此真正的发送改在**独立短命
# 子进程**里跑（_SEND_WORKER_SRC / _send_via_worker），外层带 timeout + kill；
# 卡死只损失这一条消息，不污染 sidecar 主进程与监听线程。
#
# 子进程内发送策略（见 _SEND_WORKER_SRC），由 require_foreground 分两路：
#   【安全模式 / 自动回复默认，require_foreground=True】
#     只读确认微信窗口在前台且当前会话 == 目标会话 → 直接 paste+Enter。
#     绝不 ensure_window()/SetForegroundWindow、绝不点侧栏切会话、绝不走搜索框。
#     粘贴前后各校验一次前台句柄，焦点被切走就放弃，避免把文字发进别的会话。
#     不满足则返回 NOT_FOREGROUND，Rust 侧记为「主动跳过」而非回发失败。
#   【手工发送 / 关闭安全模式，require_foreground=False】
#     ① 侧栏点击（纯 UIA，最稳）：ensure_window() 抢回前台 → DB 反查显示名 →
#        按会话列表首行精确匹配点开目标会话 → paste+Enter；全程不打截图。
#     ② 兜底：UIA 搜索框 open_chat（部分新会话/群搜不到，仅补充）。
#   文本路径已移除 guia.py OCR 兜底（实测会掉进 _grab_screen 永久卡死）；切不到时
#   明确报失败，由外层 60s 超时 kill 子进程，不堵通道。
_send_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="wxsend")
# 只读探测（前台会话）独立线程池：不与发送共用，避免慢发送把探测堵住。
_probe_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="wxprobe")


def _norm_chat(s):
    return "".join((s or "").split()).replace("\u200b", "")


def _same_chat(a, b):
    """会话名比对：容忍空白/不可见字符与群名尾部 (N) 人数后缀，但不做子串模糊匹配，
    避免把「张三」误判成「张三丰」而把消息发错人。"""
    import re
    x, y = _norm_chat(a), _norm_chat(b)
    if not x or not y:
        return False
    if x == y:
        return True
    cut = lambda s: re.sub(r"[（(]\d+[）)]$", "", s)
    return cut(x) == cut(y) and min(len(x), len(y)) >= 2


_SEND_WORKER_SRC = r'''
import sys, json, os, time, tempfile, base64
vendor = os.environ.get("WX_VENDOR") or r"C:\d\weauto\vendor"
sys.path.insert(0, vendor)


def _click_sidebar(eng, name):
    """纯 UIA 点侧栏会话项，把目标会话自动切到前台（不截图、不卡死）。

    按会话列表首项（会话名）精确匹配；命中即 Click，右侧面板即切到该会话。
    自动回复的目标几乎都是“刚来过消息”的会话，必在最近列表里，侧栏点击最稳。
    只取 Name 首行（真正的会话名）比对，避免误命中消息预览里的子串。
    """
    try:
        win = eng._win
        from wechatauto.uia_driver import SESSION_LIST_AID
        lst = win.ListControl(AutomationId=SESSION_LIST_AID)
        if not lst.Exists(1.0, 0.2):
            return False
        t = (name or "").strip()
        if not t:
            return False
        # ① 精确匹配首行（会话名）
        for c in lst.GetChildren():
            first = (c.Name or "").split("\n", 1)[0].strip()
            if first == t:
                c.Click(); time.sleep(1.0); return True
        # ② 兜底：首行包含（应对不可见字符 / 首尾空格）
        for c in lst.GetChildren():
            first = (c.Name or "").split("\n", 1)[0].strip()
            if t in first or first in t:
                c.Click(); time.sleep(1.0); return True
    except Exception:
        return False
    return False


def _norm_chat(s):
    return "".join((s or "").split()).replace("\u200b", "")


def _same_chat(a, b):
    import re
    x, y = _norm_chat(a), _norm_chat(b)
    if not x or not y:
        return False
    if x == y:
        return True
    cut = lambda s: re.sub(r"[（(]\d+[）)]$", "", s)
    return cut(x) == cut(y) and min(len(x), len(y)) >= 2


def _foreground_chat(eng):
    """只读：返回 (当前前台会话名, 微信窗口是否在前台, 前台 hwnd)。
    绝不激活窗口、绝不点击侧栏、绝不切会话。"""
    try:
        import win32gui
        import uiautomation as auto
        fg_hwnd = win32gui.GetForegroundWindow()
        hwnds = eng._wechat_hwnds()
        if fg_hwnd not in hwnds:
            return "", False, fg_hwnd
        c = auto.ControlFromHandle(fg_hwnd)
        if c is None:
            return "", False, fg_hwnd
        eng._win = c
        return (eng.current_chat() or "").strip(), True, fg_hwnd
    except Exception:
        return "", False, 0


def _send_in_foreground(eng, text, fg_hwnd):
    """在当前前台会话里输入并回车，全程不调用 ensure_window / 切会话。
    粘贴前后各校验一次前台句柄：焦点被切走就放弃，绝不把文字发到别的窗口。"""
    try:
        import win32gui
        if not fg_hwnd or win32gui.GetForegroundWindow() != fg_hwnd:
            return False
        e = eng._chat_input()
        if e is None:
            return False
        eng._paste_into(e, text, clear=True)
        time.sleep(0.2)
        if win32gui.GetForegroundWindow() != fg_hwnd:
            return False
        e.SendKeys("{Enter}", waitTime=0.05)
        return True
    except Exception:
        return False


try:
    d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    kind, peer = d.get("kind", "text"), d["peer"]
    ok, msg = False, "UIA 驱动不可用"

    # 解析显示名（联系人 / 群都适用）：微信搜索框只认显示名，不认 wxid / @chatroom，
    # 直接拿 wxid 去搜必失败。这里用本地 contact.db 把 peer 反查成昵称/群名。
    display = peer
    try:
        from wechatauto.db import WeChatDB
        n = WeChatDB().get_nickname(peer)
        if n:
            display = n
    except Exception:
        pass

    if kind == "text":
        from wechatauto.uia_driver import WeChatUIA
        eng = WeChatUIA()
        if d.get("require_foreground"):
            # 安全模式（自动回复默认）：目标会话必须已经是当前前台会话。
            # 不 SetForegroundWindow、不点侧栏切会话、不走搜索框兜底 —— 这三项正是
            # 每条自动回复都留下的明显行为特征。这里是 Rust 侧预检之后的二次校验，
            # 防止预检完用户把窗口切走，导致文字被发进别的会话。
            cur, fg, fg_hwnd = _foreground_chat(eng)
            if not fg:
                msg = "NOT_FOREGROUND: 微信窗口不在前台，本条未发送"
            elif not _same_chat(cur, display):
                msg = "NOT_FOREGROUND: 当前会话「%s」不是目标「%s」，本条未发送" % (cur or "未知", display)
            else:
                for _ in range(3):
                    if _send_in_foreground(eng, d["text"], fg_hwnd):
                        ok = True
                        break
                    time.sleep(0.5)
                if ok:
                    msg = "已在当前前台会话「%s」发送（未抢焦点/未切会话）" % display
                else:
                    msg = "NOT_FOREGROUND: 发送中前台会话变化或输入框不可用，本条未发送"
        elif not eng.ensure_window():
            msg = "无法将微信窗口置于前台（可能最小化到托盘或处于其它桌面）"
        else:
            # ① 侧栏点击（纯 UIA，最稳，不截图不卡死）：把目标会话自动切到前台
            if _click_sidebar(eng, display):
                for _ in range(3):
                    if eng.send_text(d["text"]):
                        ok = True
                        break
                    time.sleep(0.5)
            # ② 兜底：搜索框打开（UIA 搜索；部分新会话/群可能搜不到，仅作补充）
            if not ok:
                for _ in range(2):
                    eng.ensure_window()
                    if eng.open_chat(display) and eng.send_text(d["text"]):
                        ok = True
                        break
                    time.sleep(0.6)
            if ok:
                msg = "已自动切到前台会话「%s」并发送" % display
            else:
                msg = "未能切到前台/发送失败（会话名 %s；若不在最近列表，请先在微信里打开该会话一次）" % display

    if not ok and kind == "image":
        # 图片仍走 vendor 完整路径；子进程隔离 + 外层超时会 kill，不会堵死通道。
        try:
            from wechatauto.guia import WeChatGUI
            g = WeChatGUI()
            raw = base64.b64decode(d["b64"])
            ext = ".jpg" if raw[:3] == b"\xff\xd8\xff" else ".png"
            fd, tmp = tempfile.mkstemp(prefix="wxsend_", suffix=ext)
            os.close(fd)
            with open(tmp, "wb") as f:
                f.write(raw)
            try:
                r = g.send_image(tmp, peer)
            finally:
                try:
                    os.remove(tmp)
                except Exception:
                    pass
            if isinstance(r, dict):
                ok = bool(r.get("ok", False)); msg = str(r.get("msg") or "")
            else:
                ok = bool(getattr(r, "ok", False)); msg = str(getattr(r, "msg", "") or "")
        except Exception as e:
            msg = "图片兜底发送异常: %r" % (e,)
    print(json.dumps({"ok": ok, "msg": msg}, ensure_ascii=False))
except Exception as e:
    print(json.dumps({"ok": False, "msg": "worker 异常: %r" % (e,)}, ensure_ascii=False))
'''


def _send_via_worker(payload: dict, timeout: float = 45.0):
    """在独立子进程里执行一次微信发送，返回 (ok, msg)。卡死可 kill。"""
    import subprocess
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    try:
        p = subprocess.Popen(
            [sys.executable, "-c", _SEND_WORKER_SRC],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception as e:
        return False, "无法启动发送子进程: %r" % (e,)
    try:
        out_b, _ = p.communicate(data, timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        try:
            p.communicate(timeout=3)
        except Exception:
            pass
        return False, "UI 发送超时（截图/OCR 路径卡死），已终止发送子进程"
    lines = [l for l in out_b.decode("utf-8", "ignore").splitlines() if l.strip()]
    if not lines:
        return False, "发送子进程无回执 code=%s" % p.returncode
    try:
        r = json.loads(lines[-1])
        return bool(r.get("ok")), str(r.get("msg") or "")
    except Exception:
        return False, "发送子进程回执解析失败: %s" % lines[-1][:120]


# ── 离线 OCR 隔离进程 ────────────────────────────────────────────────
# 实测（2026-11-07 23:44）：sidecar 进程里（已加载 wechatauto / ctypes / UIA /
# 多线程 Listener）直接 make_engine().ocr_bytes() 会卡在 ONNX Runtime 初始化：
# 日志停在「解密结果 46770 字节」后 60s 不返回、也不抛异常；同一份代码在独立
# python 进程主线程/子线程里 2.1s 就识别完。因此 OCR 一律丢到短命子进程执行，
# 与发送 worker 同款：可超时 kill、绝不拖垮微信收消息链路。
_OCR_WORKER_SRC = r'''
# -*- coding: utf-8 -*-
import sys, os, json
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
def main():
    raw = sys.stdin.buffer.read()
    sys.path.insert(0, os.environ.get("OCR_SRC_DIR", ""))
    try:
        from ocrengine import make_engine
        e = make_engine()
        if e is None:
            print(json.dumps({"text": "", "err": "OCR 引擎不可用（缺依赖/缺模型）"}, ensure_ascii=False))
            return
        txt = e.ocr_bytes(raw) or ""
        print(json.dumps({"text": txt}, ensure_ascii=False))
    except Exception as ex:
        print(json.dumps({"text": "", "err": repr(ex)}, ensure_ascii=False))
main()
'''


def ocr_via_worker(raw: bytes, timeout: float = 45.0):
    """在独立子进程里跑离线 OCR，返回 (识别文本, 错误)。"""
    import subprocess
    env = os.environ.copy()
    # 落盘的 ocrengine.py 与 sidecar 同目录（app_data_dir），子进程需显式加进 sys.path
    env["OCR_SRC_DIR"] = os.path.dirname(os.path.abspath(__file__))
    try:
        p = subprocess.Popen(
            [sys.executable, "-c", _OCR_WORKER_SRC],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, env=env,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception as e:
        return "", "无法启动 OCR 子进程: %r" % (e,)
    try:
        out_b, _ = p.communicate(raw, timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        try:
            p.communicate(timeout=3)
        except Exception:
            pass
        return "", "OCR 子进程超时（45s），已终止"
    lines = [l for l in out_b.decode("utf-8", "ignore").splitlines() if l.strip()]
    if not lines:
        return "", "OCR 子进程无回执 code=%s" % p.returncode
    try:
        r = json.loads(lines[-1])
        return str(r.get("text") or ""), str(r.get("err") or "")
    except Exception:
        return "", "OCR 子进程回执解析失败: %s" % lines[-1][:120]


_CTRL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")   # 控制字符（保留 \n \t \r）
_GARBLED_RE = re.compile(r"^[\s\W_]+$")                       # 仅符号/空白，无字母数字中文


def clean_ocr_text(text: str) -> str:
    """清洗离线 OCR 输出，提升喂给团长 AI 的文本质量。

    处理 PP-OCRv4 常见噪声：控制字符（\\x00 等会破坏协议或干扰 AI）、行内多余
    空格与全角空格、整行仅 1~2 个孤立符号/乱码的误识别、连续空白压成单行分隔。
    保留有意义的换行与阅读顺序，不臆造内容。
    """
    if not text:
        return ""
    lines = []
    for raw in text.split("\n"):
        line = _CTRL_RE.sub("", raw)
        line = line.replace("\u3000", " ").replace("\t", " ")
        line = re.sub(r"[ \t]+", " ", line).strip()
        if not line:
            continue
        # 丢弃仅含符号/空白的孤立短行（OCR 误识别的碎字符）
        if len(line) <= 2 and _GARBLED_RE.match(line):
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def out(obj: dict) -> None:
    line = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
    with _out_lock:
        try:
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
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
        self_user = info.get("username") or getattr(db, "wxid", "") or ""
    except Exception as e:
        out({"type": "status", "event": "error", "msg": f"微信本地库打开失败（微信未登录？）: {e}"})
        return 3

    out({"type": "status", "event": "ready", "self": self_nick, "self_user": self_user})

    # 全局 WeChatGUI 单例（复用窗口句柄，避免反复初始化暴露）
    gui: WeChatGUI | None = None
    gui_lock = threading.Lock()

    def get_gui() -> WeChatGUI:
        nonlocal gui
        with gui_lock:
            if gui is None:
                gui = WeChatGUI()
            return gui

    def do_send(peer: str, text: str, req: str, require_foreground: bool = False) -> None:
        """后台线程投递一次发送：真正的 UI 操作在独立子进程里，卡死可 kill。"""
        try:
            is_group = peer.endswith("@chatroom")
            ensure_send_gap(peer, is_group)
            typing_delay = len(text) * random.uniform(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
            if typing_delay > 0.3:
                time.sleep(min(typing_delay, 4.0))
            ok, msg = _send_via_worker(
                {"kind": "text", "peer": peer, "text": text,
                 "require_foreground": bool(require_foreground)}, 60.0)
            _last_send_ts[peer] = time.time()
            out({"type": "sent", "peer": peer, "ok": ok, "msg": msg, "req": req})
        except Exception as e:
            out({"type": "sent", "peer": peer, "ok": False, "msg": repr(e), "req": req})

    def do_send_image(peer: str, b64: str, req: str) -> None:
        """后台线程投递一次图片发送，逻辑同 do_send（同样子进程隔离）。"""
        try:
            is_group = peer.endswith("@chatroom")
            ensure_send_gap(peer, is_group)
            time.sleep(random.uniform(0.5, 1.5))
            ok, msg = _send_via_worker({"kind": "image", "peer": peer, "b64": b64})
            _last_send_ts[peer] = time.time()
            out({"type": "sent_image", "peer": peer, "ok": ok, "msg": msg, "req": req})
        except Exception as e:
            out({"type": "sent_image", "peer": peer, "ok": False, "msg": repr(e), "req": req})

    def do_check_foreground(peer: str, req: str) -> None:
        """只读探测：微信窗口是否在前台 + 当前打开的是不是目标会话。
        不激活窗口、不点侧栏、不切会话；自动回复据此决定是否跳过本次发送。"""
        cur, fg, target = "", False, peer
        try:
            target = db.get_nickname(peer) or peer
        except Exception:
            pass
        try:
            import win32gui
            import uiautomation as auto
            from wechatauto.uia_driver import WeChatUIA
            eng = WeChatUIA()
            fg_hwnd = win32gui.GetForegroundWindow()
            if fg_hwnd in eng._wechat_hwnds():  # 只读 EnumWindows
                fg = True
                c = auto.ControlFromHandle(fg_hwnd)
                if c is not None:
                    eng._win = c
                    cur = (eng.current_chat() or "").strip()
        except Exception as e:
            out({"type": "status", "event": "debug", "msg": "前台会话探测异常: %r" % (e,)})
        out({"type": "check_foreground", "ok": bool(fg and _same_chat(cur, target)),
             "current": cur, "target": target, "foreground": fg, "req": req})

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

    def extract_mentioned_self(packed) -> bool:
        """从 packed_info_data XML 解析 <atuserlist>，判断是否 @ 了自己或 @所有人。"""
        if not packed:
            return False
        try:
            if isinstance(packed, bytes):
                packed = packed.decode("utf-8", errors="ignore")
            m = re.search(r"<atuserlist>([^<]*)</atuserlist>", packed)
            if not m:
                return False
            lst = [u for u in m.group(1).split(",") if u]
            if "notify@all" in lst:
                return True
            return bool(self_user) and self_user in lst
        except Exception:
            return False

    def emit_in(peer: str, is_group: bool, kind: str, text: str, sender_name: str,
                media: str = "", mentioned_self: bool = False) -> None:
        out({
            "type": "in",
            "peer": peer,
            "is_group": is_group,
            "kind": kind,
            "text": text,
            "sender": sender_name,
            "sender_name": sender_name,
            "media": media,
            "mentioned_self": mentioned_self,
        })

    def handle_image(local_id, peer: str, is_group: bool, sender_name: str, mentioned_self: bool) -> None:
        raw, _path = fetch_media(peer, local_id, "image")
        if not raw:
            out({"type": "status", "event": "warn",
                 "msg": f"图片取不到字节（peer={peer}）：MediaDownloader 解密失败，可能需先在微信中打开该图片（触发密钥派生）"})
            return
        # 隔离进程执行：sidecar 进程内直接初始化 ONNX Runtime 会卡死（见 _OCR_WORKER_SRC）
        text, err = ocr_via_worker(raw)
        if err:
            # OCR 子进程偶发超时/卡死：重试一次，避免图片消息静默丢失
            text, err = ocr_via_worker(raw)
        text = clean_ocr_text(text or "")
        if err and not text:
            out({"type": "status", "event": "warn",
                 "msg": f"图片 OCR 失败（{err}），将用占位文本让 AI 至少回应一句"})
        out({"type": "status", "event": "info",
             "msg": f"图片 OCR 完成 · 会话 {peer} · 识别 {len(text or '')} 字"})
        # 即使 OCR 没识别出文字也发占位，让团长 AI 能回应「收到图片」，绝不静默丢消息
        emit_in(peer, is_group, "image", text or "[客户发来一张图片，但未能从图片中识别出文字]",
                sender_name, "图片", mentioned_self)

    def do_ocr_last(peer: str, inject: bool, req: str) -> None:
        """调试/验收入口：直接取微信本地库最近一张图片 → 离线 OCR → 可选注入自动回复管线。

        真实入站图片依赖 Listener 的 watermark（sidecar 每次重启都会把水位线推到最新消息，
        重启前发的图片不会再被推送），用户「发了图片但没反应」很难判断是没进监听器还是
        OCR 失败。这个命令把「图片解密 + 离线 OCR + 团长 AI」整条链路独立出来验证，
        不依赖实时新消息；inject=True 时按 kind="image" 走完整管线（含回发）。
        """
        try:
            users = [peer] if peer else [
                (s.get("username") or "").strip() for s in (db.get_sessions(50) or [])
            ]
            picked = None
            for u in users:
                if not u:
                    continue
                try:
                    ids = db._find_media_rows(u, {3})[:1]
                except Exception:
                    continue
                if ids:
                    picked = (u, ids[0])
                    break
            if not picked:
                out({"type": "ocr_last", "ok": False, "msg": "本地库未找到图片消息", "req": req})
                return
            u, lid = picked
            out({"type": "status", "event": "info",
                 "msg": f"图片 OCR 自测 · 会话 {u} · local_id={lid} · 开始解密图片"})
            raw, path = fetch_media(u, lid, "image")
            out({"type": "status", "event": "info",
                 "msg": f"图片 OCR 自测 · 解密结果 {len(raw or b'')} 字节"})
            if not raw:
                out({"type": "ocr_last", "ok": False, "peer": u, "local_id": lid,
                     "msg": f"图片解密失败（MediaDownloader.download_image 返回空，local_id={lid}）",
                     "req": req})
                return
            text, err = ocr_via_worker(raw)
            text = clean_ocr_text(text or "")
            if err:
                out({"type": "ocr_last", "ok": False, "peer": u, "local_id": lid,
                     "msg": f"OCR 识别失败: {err}", "req": req})
                return
            out({"type": "status", "event": "info",
                 "msg": f"图片 OCR 自测完成 · 会话 {u} · local_id={lid} · 识别 {len(text)} 字"})
            out({"type": "ocr_last", "ok": True, "peer": u, "local_id": lid,
                 "path": path or "", "text": text, "req": req})
            if inject:
                # 即使没识别出文字也给占位，保证图片消息仍走团长 AI（由规则层决定是否回复）
                emit_in(u, u.endswith("@chatroom"), "image", text or "[图片：未识别到文字]",
                        "OCR自测", "图片", False)
        except Exception as e:
            out({"type": "ocr_last", "ok": False, "msg": f"OCR 自测异常: {e!r}", "req": req})

    def handle_voice(local_id, peer: str, is_group: bool, sender_name: str, mentioned_self: bool) -> None:
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
        emit_in(peer, is_group, "voice", text or "", sender_name, "语音", mentioned_self)

    def on_msg(msg: dict, lst) -> None:
        try:
            sender_id = msg.get("sender_id")
            peer = (msg.get("username") or "").strip()
            if sender_id in (2, "2"):
                # 自己发的消息不触发 AI（防自问自答死循环）。以前这里静默 return，
                # 用户看到「微信有消息但没反应」时日志一片空白，无法区分是消息没进来
                # 还是被过滤。这里补一条 debug，排查时可直接定位。
                out({"type": "status", "event": "debug",
                     "msg": f"忽略自己发的消息（sender_id=2）· 会话 {peer or '?'} · 需用另一个微信号发才会触发 AI"})
                return
            if not peer:
                out({"type": "status", "event": "warn", "msg": "入站消息缺少会话名，已忽略"})
                return
            is_group = peer.endswith("@chatroom")
            sender_name = nick_of(sender_id)

            # wechatauto db.Listener 透传的 type 是中文名（文本/图片/语音）；
            # 兜底按 local_type 数字再判一次，跨版本/语言更稳。
            KIND = {"文本": "text", "图片": "image", "语音": "voice"}
            kind = KIND.get(msg.get("type"))
            packed = None
            if kind is None and msg.get("local_id"):
                try:
                    row = db.get_message_row(peer, msg["local_id"])
                    if row:
                        kind = {1: "text", 3: "image", 34: "voice"}.get(row.get("local_type"))
                        packed = row.get("packed_info")
                except Exception:
                    pass
            # 群消息：额外从 packed_info 解析 @ 列表（text 消息 content 不带 @ 前缀）
            mentioned_self = False
            if is_group and packed is None and msg.get("local_id"):
                try:
                    row = db.get_message_row(peer, msg["local_id"])
                    if row:
                        packed = row.get("packed_info")
                except Exception:
                    packed = None
            if is_group:
                mentioned_self = extract_mentioned_self(packed)
            if not kind:
                out({"type": "status", "event": "warn", "msg": f"忽略未知消息类型 {msg.get('type')!r} from {peer}"})
                return

            # 入站消息只要被监听器抓到就留痕：后面任何一层过滤（自消息 / 空文本 /
            # 群 @ / 规则 / 冷却）都能从日志里分辨，不用再猜「消息到底有没有进来」。
            out({"type": "status", "event": "info",
                 "msg": f"收到入站消息 · 会话 {peer} · 类型 {kind} · sender={sender_id} · {'群' if is_group else '私聊'}"})

            if kind == "text":
                text = (msg.get("content") or "").strip()
                if not text:
                    out({"type": "status", "event": "debug", "msg": f"空文本消息已忽略 · 会话 {peer}"})
                    return
                emit_in(peer, is_group, "text", text, sender_name, mentioned_self=mentioned_self)
            elif kind == "image":
                lid = msg.get("local_id")
                _media_pool.submit(handle_image, lid, peer, is_group, sender_name, mentioned_self)
            elif kind == "voice":
                lid = msg.get("local_id")
                _media_pool.submit(handle_voice, lid, peer, is_group, sender_name, mentioned_self)
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
            # 异步投递：UI 发送耗时/卡死都不阻塞 stdin 主循环（收消息、后续命令照常）
            _send_pool.submit(do_send, peer, text, req, bool(cmd.get("require_foreground")))

        elif kind == "check_foreground":
            # 只读预检：不激活窗口、不切会话。结果供 Rust 侧「仅前台会话发送」闸门使用。
            _probe_pool.submit(
                do_check_foreground, (cmd.get("peer") or "").strip(), req)

        elif kind == "send_image":
            peer = (cmd.get("peer") or "").strip()
            b64 = cmd.get("b64") or ""
            if not peer or not b64:
                out({"type": "sent_image", "peer": peer, "ok": False, "msg": "peer/b64 为空", "req": req})
                continue
            _send_pool.submit(do_send_image, peer, b64, req)

        elif kind == "ocr_last":
            # 独立线程而非共享媒体池：图片下载/密钥派生偶发卡住时，4 个 worker 会被
            # 占满，OCR 自测命令会一直排队到超时。命令本身低频，独占线程最稳。
            out({"type": "status", "event": "info",
                 "msg": f"收到图片 OCR 自测命令 · peer={(cmd.get('peer') or '').strip() or '最近图片'}"})
            threading.Thread(target=do_ocr_last,
                             args=((cmd.get("peer") or "").strip(), bool(cmd.get("inject")), req),
                             daemon=True).start()

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
