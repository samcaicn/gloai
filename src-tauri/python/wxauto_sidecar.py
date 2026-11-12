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
import queue
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


# ── 微信 4.x v2 图片：单字节 XOR key 探测（11-09 修复「支付截图变绿图」）──────
# vendor 的 MediaDownloader._derive_xor_key 假设 .dat 文件尾部两字节是 JPEG EOI
# (FF D9)，据此反推 key；但微信 4.x v2 的 JPEG 尾部并不是标准 EOI（本机实测
# _t.dat tail = 5f d8），推导必然失败 → 退回默认 0x88 → 解出的图「顶部状态栏 +
# 整片纯绿/灰」→ OCR 只读出 '5G-L' → 真实付款截图被当闲聊图回给团长 AI。
#
# 正解：同一张图的缩略图 <md5>_t.dat 是 JPEG，其 AES 加密的前 1024 字节本身
# 就是明文；对 XOR 尾部逐字节试 256 个 key，正确 key 下 JPEG 熵编码中的
# 「FF 00」字节填充会大量出现（本机实测 1099 次，错误 key 仅 ~30 次），
# 取最高分即可，无需 PIL 解码。该 key 对同 md5 的原图 .dat（wxgf/HEVC）同样有效。
# 已验证的 v2 XOR key 缓存：aes_key -> key。微信 4.x 同一账号/客户端的
# 单字节 XOR key 是固定的（本机实测所有图均为 0x09）。但缓存**每次仍做一次
# 单 key 解密校验**（JPEG 头 + FF00 填充分数），校验不过立即重新枚举 256 次并
# 更新缓存 —— 既把每张图从 256 次 AES 解密降到 1 次，又绝不会拿错 key 解坏图。
_V2_XOR_CACHE = {}


def _v2_key_score(d) -> int:
    return d.count(b"\xff\x00") * 3 + d.count(b"\xff\xd9")


def probe_v2_xor_key(md, dat_path: str):
    """反推 v2 .dat 的单字节 XOR key；失败返回 None（让 vendor 走旧行为）。"""
    try:
        from wechatauto.media import V2_MAGIC
    except Exception:
        return None
    base = dat_path[:-4] if dat_path.lower().endswith(".dat") else dat_path
    for cand in (base + "_t.dat", base + "_h.dat", dat_path):
        try:
            if not os.path.isfile(cand):
                continue
            with open(cand, "rb") as f:
                raw = f.read()
            if raw[:6] != V2_MAGIC:
                continue
            aes_key = md._resolve_aes_key()
            if not aes_key:
                continue
            # ① 缓存命中：只解 1 次并校验，通过就复用
            cached = _V2_XOR_CACHE.get(aes_key)
            if cached is not None:
                try:
                    d = md.decrypt_image(cand, aes_key=aes_key, xor_key=cached)
                    if d[:3] == b"\xff\xd8\xff" and _v2_key_score(d) >= 30:
                        return cached
                except Exception:
                    pass
                _V2_XOR_CACHE.pop(aes_key, None)  # 校验失败 → 丢弃缓存重新枚举
            # ② 完整枚举（首次 / key 变更 / 校验失败时）
            best_score, best_key = 0, None
            for k in range(256):
                try:
                    d = md.decrypt_image(cand, aes_key=aes_key, xor_key=k)
                except Exception:
                    continue
                if d[:3] != b"\xff\xd8\xff":
                    continue
                # JPEG 中 FF 后必须补 00；正确 key 下这种填充成百上千
                score = _v2_key_score(d)
                if score > best_score:
                    best_score, best_key = score, k
            if best_key is not None and best_score >= 30:
                _V2_XOR_CACHE[aes_key] = best_key
                return best_key
        except Exception as e:
            sys.stderr.write(f"[sidecar] XOR key 探测异常 {cand}: {e!r}\n")
    return None


# ── 前台窗口控制 ──────────────────────────────────────────────
# 单一来源：force_wechat_foreground 既要被主进程 on_msg 调用，也要被发送 worker
# 子进程（-c 独立命名空间）调用，故抽成字符串常量：exec 注入主进程命名空间（供
# on_msg），同时拼到 _SEND_WORKER_SRC 头部（供子进程），避免两份定义漂移。
_FORCE_FG_DEF = r'''
def force_wechat_foreground() -> bool:
    """把微信主窗口从后台/最小化强制拉到前台（后台监测来消息即自露脸 / 自动回复前置）。

    后台子进程里 ``SetForegroundWindow`` 受 Windows 前台锁限制会静默失败；补齐：
    ① 清除前台锁延迟（SPI_SETFOREGROUNDLOCKTIMEOUT=0）；② 允许任意进程置前；
    ③ 用 wechatauto 自带的 ``_wechat_hwnds()``（只匹配微信窗口，绝不扫描全部进程，
    避免个别进程 QueryFullProcessImageNameW 卡死拖垮整条链）找到窗口后
    AttachThreadInput + SetForegroundWindow 置前。纯 best-effort，异常全吞。
    """
    try:
        import ctypes
        import win32gui
        import win32con
        import win32process
        import win32api
        from wechatauto.uia_driver import WeChatUIA
        u = ctypes.windll.user32
        # ① 清除前台锁延迟（0x2001 = SPI_SETFOREGROUNDLOCKTIMEOUT，值 0 = 无延迟）
        try:
            u.SystemParametersInfoW(0x2001, 0, None, 0)
        except Exception:
            pass
        # ② 允许任意进程置前（ASFW_ANY = 0xFFFFFFFF）
        try:
            u.AllowSetForegroundWindow(0xFFFFFFFF)
        except Exception:
            pass
        # ③ 只匹配微信窗口（wechatauto 自带，稳，不扫全部进程）
        try:
            hwnds = WeChatUIA()._wechat_hwnds()
        except Exception:
            hwnds = []
        if not hwnds:
            return False
        cur = win32api.GetCurrentThreadId()
        fg = win32gui.GetForegroundWindow()
        fgt = win32process.GetWindowThreadProcessId(fg)[0] if fg else 0
        if fgt:
            try:
                win32process.AttachThreadInput(cur, fgt, True)
            except Exception:
                pass
        for h in hwnds:
            try:
                if win32gui.IsIconic(h):
                    win32gui.ShowWindow(h, win32con.SW_RESTORE)
                win32gui.ShowWindow(h, win32con.SW_SHOW)
                win32gui.BringWindowToTop(h)
                win32gui.SetForegroundWindow(h)
            except Exception:
                pass
        if fgt:
            try:
                win32process.AttachThreadInput(cur, fgt, False)
            except Exception:
                pass
        return True
    except Exception:
        return False
'''
exec(_FORCE_FG_DEF)

# stdout 写锁：on_msg 回调线程（每会话一条）、heartbeat 线程、stdin 主线程并发 out()，
# 无锁 write+flush 会导致 JSON 行交错，Rust 端 serde_json 解析失败丢消息。
_out_lock = threading.Lock()

# 所有 stdout 写出统一经主线程：子线程/子进程（do_send 会 spawn 发送子进程，
# 在 Windows 上该子进程会继承 sidecar 的 stdout 管道句柄，导致并发写 stdout 时
# Rust 端偶发收不到「sent」回执，表现为「worker 已成功发送但 request() 超时」）。
# 改为：任意线程调用 out() 只把字节入队，主线程的写出循环独占 stdout 写出，
# 彻底消除多线程/子进程写同一管道的竞争。
_resp_q: "queue.Queue[bytes]" = queue.Queue()

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


_SEND_WORKER_SRC = _FORCE_FG_DEF + r'''
import sys, json, os, time, tempfile, base64, threading
vendor = os.environ.get("WX_VENDOR") or r"C:\d\weauto\vendor"
sys.path.insert(0, vendor)

# 诊断日志同时落文件：Windows 上 Popen.communicate 超时后的二次 stderr 读取经常
# 拿到空内容（11-09 连续两次 60s 超时日志一片空白，完全看不出卡在哪一步）。
# 落文件后即使 worker 被 kill / _exit，也能读到最后一个阶段。
_WORKER_LOG = os.path.join(
    os.environ.get("APPDATA") or tempfile.gettempdir(),
    "com.bbtzb.tauri", "logs", "send_worker.log")
_stage = "boot"
_done = threading.Event()


def _dbg(s):
    """worker 诊断：stderr + 文件双写（超时被强制结束后文件仍可读）。"""
    line = "[worker] [%s] %s" % (_stage, s)
    try:
        sys.stderr.write(line + "\n")
        sys.stderr.flush()
    except Exception:
        pass
    try:
        os.makedirs(os.path.dirname(_WORKER_LOG), exist_ok=True)
        with open(_WORKER_LOG, "a", encoding="utf-8") as f:
            f.write("%s %s\n" % (time.strftime("%H:%M:%S"), line))
    except Exception:
        pass


def _watchdog(limit=35.0):
    """UIA 调用偶发无限卡死（COM 初始化 / 最小化窗口恢复 / 搜索框唤醒）。
    看门狗在**独立线程**运行，不改主逻辑的线程模型（COM 仍在主线程初始化），
    超时就代 worker 回一条明确失败并 os._exit：父进程无需白等 60s，
    且能从文件日志直接看到 last_stage。"""
    if _done.wait(limit):
        return
    _dbg("WATCHDOG %.0fs 未完成，强制退出" % limit)
    try:
        sys.stdout.write(json.dumps(
            {"ok": False, "msg": "worker UI 操作超时（stage=%s）" % _stage},
            ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        pass
    os._exit(3)


threading.Thread(target=_watchdog, daemon=True).start()
_dbg("boot")


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


def _wake_search_box(eng, win):
    """微信 4.x：搜索输入框平时**不物化**，右上角只有一个 Name='搜索' 的
    ButtonControl（mmui::XButton），点开后才会出现 EditControl。vendor 的
    open_chat()/existing _search_box() 直接找 EditControl，所以此前必然返回 False
    —— 这就是历史日志里 SEARCH_BOX_FOUND=False 的真因。
    """
    try:
        if eng._search_box(win) is not None:
            return True
        import uiautomation as auto
        btn = auto.ButtonControl(searchFromControl=win, searchDepth=0xFFFFFFFF, Name="搜索")
        if btn is not None and btn.Exists(0.6, 0.2):
            _dbg("click 搜索 button")
            btn.Click()
            time.sleep(0.7)
        else:
            _dbg("搜索 button not found")
            return False
        for _ in range(6):
            if eng._search_box(win) is not None:
                _dbg("search EditControl ready")
                return True
            time.sleep(0.25)
        _dbg("搜索框点击后 EditControl 仍未出现")
        return False
    except Exception as e:
        _dbg("wake_search_box err %r" % (e,))
        return False


def _open_by_enter(eng, win, kw, display):
    """搜索结果列表控件解析不到时，用「输入关键词 + 回车」让微信打开唯一命中项。
    必须校验 current_chat() 已切到目标显示名，否则绝不返回 True（防止发错人）。"""
    try:
        box = eng._search_box(win)
        if box is None:
            return False
        eng._paste_into(box, kw, clear=True)
        time.sleep(0.9)
        box.SendKeys("{Enter}", waitTime=0.1)
        time.sleep(1.0)
        cur = eng.current_chat() or ""
        _dbg("enter fallback kw=%r cur=%r" % (kw, cur))
        return bool(cur) and _same_chat(cur, display)
    except Exception as e:
        _dbg("enter fallback err %r" % (e,))
        return False


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


def _ready(eng):
    """只按句柄锚定微信主窗口，绝不调用 vendor 的 ensure_window(wake=True)。

    force_wechat_foreground() 已把窗口拉到前台；这里只需拿到 mmui 树并设置
    eng._win。若树未物化，宁可快速失败也不要走 ensure_window 的
    _wake_accessibility()/wake()（会尝试重启/激活微信并长等待，11-09 实测
    连续两次把发送 worker 卡满 60s 超时）。
    """
    try:
        w = eng._find_main()
        if w is not None:
            eng._win = w
            return True
    except Exception as e:
        _dbg("find_main err %r" % (e,))
    try:
        return bool(eng.ensure_window(wake=False))
    except Exception as e:
        _dbg("ensure_window(wake=False) err %r" % (e,))
        return False


def _send_direct(eng, text):
    """在当前已打开的会话里直接粘贴+回车，跳过 vendor send_text() 内部的
    ensure_window()（后者可能触发 wake 长等待）。调用前必须已 _ready() 成功。"""
    try:
        e = eng._chat_input()
        if e is None:
            _dbg("chat_input not found")
            return False
        eng._paste_into(e, text, clear=True)
        time.sleep(0.2)
        e.SendKeys("{Enter}", waitTime=0.05)
        return True
    except Exception as e:
        _dbg("send_direct err %r" % (e,))
        return False


try:
    d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    kind, peer = d.get("kind", "text"), d["peer"]
    ok, msg = False, "UIA 驱动不可用"

    # 解析显示名（联系人 / 群都适用）：微信搜索框只认显示名，不认 wxid / @chatroom，
    # 直接拿 wxid 去搜必失败。这里用本地 contact.db 把 peer 反查成昵称/群名，并
    # 额外构造一组搜索候选词。备注可能不唯一（本机实测「凯哥」同时命中 2 个联系人），
    # 所以把信息量更大的微信号 / 昵称全称排在前面，降低「搜到同名、把回复发错人」风险。
    _stage = "contact_lookup"
    display = peer
    keywords = []
    try:
        from wechatauto.db import WeChatDB
        wdb = WeChatDB()
        n = wdb.get_nickname(peer)
        if n:
            display = n
        for rec in wdb.search_contact(peer):
            for k in (rec.get("nick_name"), rec.get("remark")):
                if k and k not in keywords:
                    keywords.append(k)
    except Exception as e:
        _dbg("contact lookup err %r" % (e,))
    if not peer.startswith("wxid_") and "@chatroom" not in peer and peer not in keywords:
        keywords.insert(0, peer)  # 微信号 alias，通常最唯一
    if display and display not in keywords:
        keywords.append(display)
    keywords = keywords[:4]
    _dbg("display=%r keywords=%r" % (display, keywords))

    if kind == "text":
        _stage = "text_branch"
        _dbg("text branch start; require_foreground=%s" % d.get("require_foreground"))
        _dbg("env python=%s WX_VENDOR=%s cwd=%s" % (sys.executable, os.environ.get("WX_VENDOR"), os.getcwd()))
        # 发送前再兜底置前一次：后台子进程里窗口可能又被用户切走，确保 UIA 有点可点
        _stage = "force_fg"
        fg = force_wechat_foreground()
        _dbg("force_wechat_foreground -> %s" % fg)
        _stage = "import_uia"
        from wechatauto.uia_driver import WeChatUIA
        _dbg("importing WeChatUIA ok")
        _stage = "construct_uia"
        eng = WeChatUIA(timeout=6.0, search_timeout=1.0)
        _stage = "pre_send"
        _dbg("WeChatUIA() constructed")
        if d.get("require_foreground"):
            # 安全模式（require_foreground=true）：目标会话必须已经是当前前台会话，
            # 不切会话、不抢焦点。仅作二次校验，防止把文字发进别的会话。
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
        elif not _ready(eng):
            # 只锚定已存在的主窗口，绝不走 ensure_window 的 wake 分支（会尝试
            # 唤醒/激活微信并长等待，实测把发送 worker 卡满 60s 超时）。
            msg = "微信主窗口 UIA 树未物化（窗口可能最小化或未登录），本条未发送"
        else:
            # ① 侧栏点击（纯 UIA，最稳）：把目标会话自动切到前台
            # ② 全局搜索兜底：目标会话不在侧栏可见项里时（实测「凯哥」就不在），
            #    必须先点「搜索」按钮唤醒输入框再 open_chat；此前直接调 open_chat
            #    必然失败，是「后台监测到了却不回、也不切会话」的最后一个卡点。
            # 有限重试（≤3 次），单次 WeChatUIA 超时 6s 已封顶，远低于 55s 上限。
            for _ in range(3):
                _stage = "sidebar_click"
                side = _click_sidebar(eng, display)
                _dbg("sidebar click %r -> %s" % (display, side))
                _stage = "send_text_sidebar"
                if side and _send_direct(eng, d["text"]):
                    ok = True
                    _dbg("sent via sidebar")
                    break
                _stage = "wake_search"
                win = eng._win or eng._find_main()
                if win is not None and _wake_search_box(eng, win):
                    for kw in keywords:
                        _stage = "open_chat"
                        opened = eng.open_chat(kw)
                        _dbg("open_chat(%r) -> %s" % (kw, opened))
                        if not opened:
                            _stage = "enter_fallback"
                            opened = _open_by_enter(eng, win, kw, display)
                        _stage = "send_text_search"
                        if opened and _send_direct(eng, d["text"]):
                            ok = True
                            _dbg("sent via search kw=%r" % kw)
                            break
                    if ok:
                        break
                else:
                    _dbg("search box unavailable; win=%s" % (win is not None))
                time.sleep(0.8)
            if ok:
                msg = "已自动切到前台会话「%s」并发送" % display
            else:
                msg = "未能切到前台/发送失败（会话名 %s；侧栏无该会话且微信搜索未能打开）" % display

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
    _stage = "done"
    print(json.dumps({"ok": ok, "msg": msg}, ensure_ascii=False))
    _done.set()
except Exception as e:
    _stage = "exception"
    print(json.dumps({"ok": False, "msg": "worker 异常: %r" % (e,)}, ensure_ascii=False))
    _done.set()
'''


def _send_via_worker(payload: dict, timeout: float = 55.0):
    """在独立子进程里执行一次微信发送，返回 (ok, msg)。卡死可 kill。"""
    import subprocess
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    try:
        p = subprocess.Popen(
            [sys.executable, "-c", _SEND_WORKER_SRC],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception as e:
        return False, "无法启动发送子进程: %r" % (e,)
    try:
        out_b, err_b = p.communicate(data, timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        try:
            _, err_b = p.communicate(timeout=3)
        except Exception:
            err_b = b""
        # 🔴 原实现 `b"\n".join(str列表)` 必抛 TypeError 被吞 → tail 恒为空字节，
        # 这就是 11-09 两次 60s 超时日志里「worker stderr 末 10 行:」后面一片空白的原因。
        try:
            tail = "\n".join(err_b.decode("utf-8", "ignore").strip().splitlines()[-10:])
        except Exception:
            tail = ""
        # Windows 上二次 communicate 常拿不到已 flush 的 stderr；从 worker 文件日志兜底。
        try:
            lp = os.path.join(os.environ.get("APPDATA") or tempfile.gettempdir(),
                              "com.bbtzb.tauri", "logs", "send_worker.log")
            if os.path.isfile(lp):
                with open(lp, "r", encoding="utf-8", errors="ignore") as f:
                    fl = [x for x in f.read().splitlines() if x.strip()][-12:]
                if fl:
                    tail = (tail + "\n" + "\n".join(fl)).strip()
        except Exception:
            pass
        try:
            sys.stderr.write("[sidecar] send worker 超时（%ss）；worker 诊断:\n%s\n" % (timeout, tail))
            sys.stderr.flush()
        except Exception:
            pass
        return False, "UI 发送超时，已终止发送子进程；worker 诊断:\n%s" % tail
    # 正常/失败路径也必须把 worker stderr 透传出来：以前只有超时才打印，导致
    # 「切会话失败但没超时」时完全看不到 worker 卡在侧栏点击 / 搜索框 / send_text
    # 哪一步，排查只能靠猜。worker 的 _dbg 只有十几行，量很小。
    try:
        tail = "\n".join(err_b.decode("utf-8", "ignore").strip().splitlines()[-20:])
        if tail:
            sys.stderr.write("[sidecar] send worker stderr 末 20 行:\n%s\n" % tail)
            sys.stderr.flush()
    except Exception:
        pass
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
    # 仅入队：真正写出由主线程 _pump_output() 完成，避免多线程/子进程并发写 stdout 管道。
    try:
        _resp_q.put_nowait((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    except Exception:
        pass


def _pump_output() -> None:
    """主线程调用：把当前队列里所有待写出的响应行写到 stdout 并 flush（不阻塞）。"""
    while True:
        try:
            line = _resp_q.get_nowait()
        except queue.Empty:
            break
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
        _pump_output()  # 错误路径会立即 return，队列必须当场刷出，否则 Rust 收不到原因
        return 2

    try:
        db = WeChatDB()
        info = db.get_self_info() or {}
        self_nick = info.get("nick_name") or info.get("username") or ""
        self_user = info.get("username") or getattr(db, "wxid", "") or ""
    except Exception as e:
        out({"type": "status", "event": "error", "msg": f"微信本地库打开失败（微信未登录？）: {e}"})
        _pump_output()
        return 3

    out({"type": "status", "event": "ready", "self": self_nick, "self_user": self_user})
    _pump_output()  # ready 立即上报，别等 stdin 主循环起来（Rust 靠它判定启动成功）

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
            sys.stderr.write("[do_send] enter peer=%s req=%s rf=%s\n" % (peer, req, require_foreground))
            sys.stderr.flush()
            is_group = peer.endswith("@chatroom")
            ensure_send_gap(peer, is_group)
            typing_delay = len(text) * random.uniform(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
            if typing_delay > 0.3:
                time.sleep(min(typing_delay, 4.0))
            ok, msg = _send_via_worker(
                {"kind": "text", "peer": peer, "text": text,
                 "require_foreground": bool(require_foreground)}, 60.0)
            sys.stderr.write("[do_send] worker done peer=%s ok=%s msg=%s\n" % (peer, ok, (msg or "")[:160]))
            sys.stderr.flush()
            _last_send_ts[peer] = time.time()
            sys.stderr.write("[do_send] EMIT sent ok=%s req=%s payload_len=%d\n" % (ok, req, len(msg or "")))
            sys.stderr.flush()
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

    # ── 自消息判定（微信 4.x real_sender_id 的真实语义）──────────────────
    # Msg_xxx 表的 real_sender_id 是【该消息库 Name2Id 表的 rowid】，指向真实
    # 发送者 wxid，不是固定值 2。实测本机：rowid=1 -> 本机账号「正正」(自己)，
    # rowid=2 -> 客户 SamCai_(凯哥)，rowid=164 -> wxid_i80lfpy9bed822 …
    # 旧代码硬编码 sender_id==2 = 「自己发的」，把客户消息全部误杀（表现为
    # 「用另一个号发了却不回」），同时本机发出的 rowid=1 反而被当客户放行。
    # 注意：message_resource.db 的 SenderName2Id 是另一套编码（id=2 才是自己），
    # 不能拿来反查 real_sender_id —— 两套表混用正是这个 bug 的来源。
    _sender_wxid_cache: dict = {}

    def sender_wxid(peer: str, sender_id) -> str:
        """real_sender_id -> 真实 wxid（按消息库 Name2Id 反查，带缓存）。"""
        try:
            key = int(sender_id)
        except (TypeError, ValueError):
            return ""
        if key in _sender_wxid_cache:
            return _sender_wxid_cache[key]
        wxid = ""
        try:
            r = db._msg_conn(peer)
            if r:
                conn, _table = r
                row = conn.execute(
                    "SELECT user_name FROM Name2Id WHERE rowid=?", (key,)
                ).fetchone()
                if row and row[0]:
                    wxid = str(row[0])
        except Exception:
            pass
        _sender_wxid_cache[key] = wxid
        return wxid

    def is_self_sender(peer: str, sender_id, local_id=None) -> bool:
        """是否为本机账号发出（防自问自答）。反查失败时保守放行，
        宁可多放行也不再误杀客户消息；Rust 侧另有 self_nick 兜底。"""
        wxid = sender_wxid(peer, sender_id)
        if wxid and self_user:
            return wxid == self_user
        # 兜底：status==2 表示本机发出（Name2Id 取不到时用）
        try:
            if local_id:
                r = db._msg_conn(peer)
                if r:
                    conn, table = r
                    st = conn.execute(
                        "SELECT status FROM %s WHERE local_id=?" % table, (local_id,)
                    ).fetchone()
                    if st and int(st[0]) == 2:
                        return True
        except Exception:
            pass
        return False

    def nick_of(peer: str, sender) -> str:
        wxid = sender_wxid(peer, sender)
        if wxid and self_user and wxid == self_user:
            # 返回真实昵称，让 Rust 侧 `sender == self_nick` 兜底也能命中
            return self_nick or "我"
        if wxid:
            try:
                n = db.get_nickname(wxid)
                return n or wxid
            except Exception:
                return wxid
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
                # 先反推正确的单字节 XOR key 再解密：vendor 的默认推导在微信 4.x
                # v2 上会失败并退回 0x88，解出纯绿/纯灰坏图，付款截图 OCR 全丢。
                xor_key = None
                try:
                    row = db.get_message_row(user, int(local_id))
                    md5 = md._img_md5(row) if row else None
                    ct = row["create_time"] if row else None
                    dp = md._find_dat(user, md5, ct) if md5 else None
                    if not dp and md5:
                        dp = md._find_dat(user, md5, ct, thumbnail=True)
                    if dp:
                        xor_key = probe_v2_xor_key(md, dp)
                        sys.stderr.write(
                            "[sidecar] 图片 XOR key lid=%s -> %s\n" % (local_id, xor_key)
                        )
                except Exception as e:
                    sys.stderr.write(f"[sidecar] XOR key 探测跳过 lid={local_id}: {e!r}\n")
                p = md.download_image(user, int(local_id), save_dir=MEDIA_TMP,
                                      xor_key=xor_key)
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
        # 把 OCR 原文一并写进日志：以前只打「识别 4 字」，遇到「金额核对没触发」
        # 完全看不出图里到底有什么，只能重新解密图片复盘（11-09 实测踩过）。
        ocr_preview = (text or "").replace("\n", " / ")[:160]
        out({"type": "status", "event": "info",
             "msg": f"图片 OCR 完成 · 会话 {peer} · 识别 {len(text or '')} 字 · 文本：{ocr_preview!r}"})
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
            if not peer:
                out({"type": "status", "event": "warn", "msg": "入站消息缺少会话名，已忽略"})
                return
            # 自己发的消息不触发 AI（防自问自答死循环）。判定改为按 Name2Id
            # 反查真实 wxid，不再硬编码 sender_id==2（见 is_self_sender 注释）。
            if is_self_sender(peer, sender_id, msg.get("local_id")):
                out({"type": "status", "event": "debug",
                     "msg": f"忽略自己发的消息（sender_id={sender_id} · 反查={sender_wxid(peer, sender_id) or '?'}）· 会话 {peer}"})
                return
            is_group = peer.endswith("@chatroom")
            sender_name = nick_of(peer, sender_id)

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
                # 后台监测到进入管线的消息 → 先把微信窗口拉到前台（用户要的「自动露脸」）
                force_wechat_foreground()
                emit_in(peer, is_group, "text", text, sender_name, mentioned_self=mentioned_self)
            elif kind == "image":
                lid = msg.get("local_id")
                force_wechat_foreground()
                _media_pool.submit(handle_image, lid, peer, is_group, sender_name, mentioned_self)
            elif kind == "voice":
                lid = msg.get("local_id")
                force_wechat_foreground()
                _media_pool.submit(handle_voice, lid, peer, is_group, sender_name, mentioned_self)
        except Exception as e:
            sys.stderr.write(f"[sidecar] on_msg error: {e!r}\n")

    # 监听间隔随机化（3~5s），降频减少 DB 读取指纹
    lst = Listener(db, interval=random.uniform(LISTEN_INTERVAL_MIN, LISTEN_INTERVAL_MAX))

    # 修复：监听器在「会话首次被发现」时才建立水位线。若该会话是因某条消息刚出现
    # （例如新联系人首次发消息），那条触发发现的消息会被当成“历史”永远丢弃——
    # 首条消息永远收不到。对启动后新发现的会话，让水位线退一格（newest-1），使最新
    # 一条也能被投递一次，之后恢复正常增量。启动时就存在的会话走默认 add_listener，
    # 水位线=最新 sort_seq，不重放历史。
    _orig_add_listener = lst.add_listener

    def _add_listener_include_newest(user, callback):
        _orig_add_listener(user, callback)
        w = lst._watermark.get(user, 0)
        if w > 0:
            lst._watermark[user] = w - 1

    lst.add_listener = _add_listener_include_newest

    lst.add_all(on_msg, discover=True)
    lst.start()

    # 心跳线程
    def heartbeat() -> None:
        while True:
            out({"type": "ping", "ts": int(time.time())})
            time.sleep(HEARTBEAT_INTERVAL + random.uniform(-2, 2))

    threading.Thread(target=heartbeat, daemon=True).start()

    # stdin 命令循环：主线程统一泵出 stdout（避免多线程/子进程写管道竞争），
    # 另起一个线程把 stdin 行喂进 _cmd_q，主线程循环持续处理命令并泵出响应。
    _cmd_q: "queue.Queue[str]" = queue.Queue()

    def _stdin_reader() -> None:
        try:
            for raw in sys.stdin:
                _cmd_q.put(raw)
        except Exception:
            pass
        _cmd_q.put(None)  # EOF 哨兵

    threading.Thread(target=_stdin_reader, daemon=True).start()

    while True:
        # 主线程独占写出：把排队的响应行刷到 stdout（含 on_msg/心跳/发送回执）
        _pump_output()
        try:
            raw = _cmd_q.get(timeout=0.1)
        except queue.Empty:
            continue
        if raw is None:
            break
        line = raw.strip()
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
                    # contact.db 查无此人时 get_nickname 原样返回 username（不是空串），
                    # 必须把「昵称 == 原始 id」视为未解析，否则永远走不到下面的可读标签。
                    if name == uname:
                        name = ""
                    if not name:
                        # contact.db 查不到显示名的会话（企业微信外部群 @qy_g / @qy_u、
                        # 未备注的新联系人等）：用「摘要首行 + 最近发言人」拼可读标签，
                        # 避免配对下拉框里出现一排原始 wxid 让人无从下手。
                        sender = (s.get("last_sender") or "").strip()
                        summ_raw = (s.get("summary") or "").strip()
                        summ = summ_raw.splitlines()[0].strip()[:24] if summ_raw else ""
                        if uname.endswith("@qy_g"):
                            tag = "企微外部群"
                        elif uname.endswith("@qy_u") or uname.startswith("ww"):
                            tag = "企微联系人"
                        elif uname.endswith("@chatroom"):
                            tag = "未命名群"
                        else:
                            tag = "未命名联系人"
                        if summ and sender:
                            name = f"{tag} · {summ}（{sender}）"
                        elif summ or sender:
                            name = f"{tag} · {summ or sender}"
                        else:
                            name = f"{tag}（…{uname[-8:]}）"
                    sessions.append({
                        "id": uname,
                        "name": name,
                        "is_group": is_group,
                        "summary": s.get("summary") or "",
                        "last_time": s.get("last_time") or 0,
                    })
                out({"type": "sessions", "sessions": sessions, "req": req})
            except Exception as e:
                out({"type": "sessions", "sessions": [], "msg": repr(e), "req": req})

    _pump_output()  # 退出前把残余响应刷出
    lst.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
