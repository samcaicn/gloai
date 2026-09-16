# -*- coding: utf-8 -*-
"""
wxauto_compat.py — 微信 4.x 引擎兼容层（WeAuto v3.25.1 专用）

背景
----
本项目 v3.25.1 的 bot.py 基于旧版 ``wxauto`` 3.x 的 API 编写（依赖 UIA 自动化，
只能驱动微信 3.x）。微信 4.x 后 UIA 外壳失效，旧方案彻底不可用。

本模块把 bot.py 实际用到的旧版 ``wxauto.WeChat`` API 契约**完整复刻**到微信 4.x
引擎 ``wechatauto``（解密本地数据库 + guia 侧栏搜索发送）之上，使 bot.py 的全部
业务逻辑（17 项菜单功能：群聊/表情/私聊/API模型 等）**完全不用改**即可跑在微信 4.x 上。

复刻的旧 wxauto API（bot.py 用到者）
-----------------------------------
- ``WeChat()``                         构造（懒初始化，import 不依赖微信运行）
- ``wx.nickname``                      自己的微信昵称
- ``wx.Show()``                        兼容占位（4.x 引擎无需置顶，空操作）
- ``wx.AddListenChat(nickname=, callback=)``  开始监听某会话，直接回调 callback(msg, chat)
- ``wx.listen``                        dict，其 .keys() 为当前正在监听的昵称集合（keep_alive 用）
- ``wx.KeepRunning()``                阻塞保活（引擎监听在后台线程）
- ``wx.SendMsg(msg, who)`` / ``SendFiles(filepath, who)`` / ``SendImage``
- ``wx.GetAllSubWindow()``            返回会话窗口对象列表，每个有 .who 与 .ChatInfo()
- ``wx.VoiceCall(who)``               4.x 引擎暂不支持，降级为日志告警（不崩溃）
- 消息对象 Msg：``.type``(内容类型 text/voice/link/quote/merge/image...)、
  ``.content``、``.sender``、``.attr``(friend/self/tickle/sys)、
  ``.to_text()``、``.get_url()``、``.quote_content``、``.get_messages()``

关键修正（与本机实测一致）
--------------------------
wechatauto 库自带转换假设 ``sender_id == 2`` 为“自己”，但本机实测是
``sender_id == 1`` = 我、``== 2`` = 对方（反向）。本层**不使用**库的 Message 构造，
而是基于原始 row dict 用正确方向逻辑自建 ``Msg``。
"""

import os
import re
import sys
import threading
import time
import logging

logger = logging.getLogger("wxauto_compat")

# 本机方向：sender_id == 1 才是“我”，== 2 是对方（与 wechatauto 库默认相反）
SELF_SENDER_ID = 1

# wechatauto 媒体类型 -> (v3.25.1 内容类型, 富文本标记)
_MEDIA = {
    "图片": ("image", "[图片]"),
    "动画表情": ("image", "[动画表情]"),
    "表情": ("image", "[表情]"),
    "贴纸": ("image", "[贴纸]"),
    "语音": ("voice", "[语音]"),
    "视频": ("video", "[视频]"),
    "文件": ("file", "[文件]"),
    "文件/链接/卡片": ("file", "[文件]"),
    "链接": ("link", "[链接]"),
    "分享": ("link", "[链接]"),
    "引用": ("quote", "[引用]"),
    "合并转发": ("merge", "[合并转发]"),
    "位置": ("location", "[位置]"),
}


class Msg:
    """复刻 wxauto 的 Message 对象，暴露 v3.25.1 bot.py 用到的全部字段与方法。

    v3.25.1 同时使用两个维度：
      - ``.type``   内容类型：text / voice / link / quote / merge / image / file / sys ...
      - ``.attr``   消息类别：friend / self / tickle(拍一拍) / sys ...
    """

    __slots__ = (
        "type", "content", "sender", "attr", "sender_remark",
        "_url", "_quote_content", "_messages", "_voice_text",
        "local_id", "create_time",
    )

    def __init__(self, mtype, content, sender, attr="friend", sender_remark="",
                 url="", quote_content="", messages=None, voice_text="",
                 local_id=None, create_time=None):
        self.type = mtype
        self.content = content
        self.sender = sender
        self.attr = attr
        self.sender_remark = sender_remark
        self._url = url
        self._quote_content = quote_content
        self._messages = messages or []
        self._voice_text = voice_text
        self.local_id = local_id
        self.create_time = create_time

    def to_text(self):
        """语音消息转文本（尽力而为）。"""
        if self.type == "voice":
            return (self._voice_text or self.content or "")
        return self.content or ""

    def get_url(self):
        return self._url or ""

    def get_messages(self):
        return self._messages or []

    @property
    def quote_content(self):
        return self._quote_content or ""

    def __repr__(self):
        return "<Msg type=%s attr=%s sender=%r content=%r>" % (
            self.type, self.attr, self.sender, (self.content or "")[:30])


class _Chat:
    """GetListenMessage / 回调里的 key 对象，需有 .who 属性（bot.py 用 chat.who）。"""

    __slots__ = ("who",)

    def __init__(self, who):
        self.who = who

    def __hash__(self):
        return hash(self.who)

    def __eq__(self, other):
        return isinstance(other, _Chat) and other.who == self.who


class _ChatEx:
    """GetAllSubWindow 返回的会话窗口对象：.who + .ChatInfo()."""

    __slots__ = ("who", "_username")

    def __init__(self, who, username):
        self.who = who
        self._username = username

    def ChatInfo(self):
        return {
            "who": self.who,
            "username": self._username,
            "chat_type": "group" if str(self._username).endswith("@chatroom") else "friend",
        }


class _A_MyIcon:
    """兼容 wx.A_MyIcon.Name。"""

    def __init__(self, owner):
        self._owner = owner

    @property
    def Name(self):
        return self._owner._get_self_name()


def _ensure_wechatauto():
    """导入 wechatauto（优先项目内 vendor 的已打补丁版本）。返回所需符号。

    注意：要导入包 ``wechatauto``，必须把它所在的**父目录**（即 ``vendor``）
    而不是包目录本身插到 sys.path 头部。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    vendored = os.path.join(here, "vendor", "wechatauto")
    vendor_parent = os.path.dirname(vendored)   # .../vendor
    if os.path.isdir(vendored) and vendor_parent not in sys.path:
        sys.path.insert(0, vendor_parent)
    for _m in list(sys.modules):
        if _m == "wechatauto" or _m.startswith("wechatauto."):
            del sys.modules[_m]
    try:
        import wechatauto                                   # noqa: F401
        from wechatauto import db as wa_db
        from wechatauto.guia import (quick_send, quick_send_file, quick_send_image)
    except Exception as e:  # pragma: no cover - 环境缺失时给出可操作提示
        raise ImportError(
            "未找到 wechatauto 引擎。请保留本项目的 vendor/wechatauto，"
            "并执行依赖安装(cryptography/zstandard/winsdk/pypinyin/uiautomation)。"
            "原始错误: %r" % e
        )
    return wa_db, quick_send, quick_send_file, quick_send_image


def _clean_text(content):
    """清理 wechatauto 友好解码后的噪声。"""
    if not content:
        return ""
    text = content
    text = re.sub(r"[A-Za-z0-9+/]{300,}={0,2}", "", text)
    text = re.sub(r"<\?xml.*?\?>.*$", "", text, flags=re.S)
    return text.strip()


def _parse(row, who, username, member_resolver=None):
    """把 wechatauto 的原始消息 row 解析成 v3.25.1 的 Msg（两轴模型）。"""
    content = row.get("content")
    if content is None:
        content = ""
    elif isinstance(content, bytes):
        try:
            content = content.decode("utf-8", "ignore")
        except Exception:
            content = str(content)
    elif not isinstance(content, str):
        # wechatauto 偶尔把纯数字/其它类型消息以非 str 传入，统一转 str，
        # 否则后续 .strip()/re.sub 会抛 AttributeError('int' object has no attribute 'strip')
        content = str(content)
    sender_id = row.get("sender_id")
    is_self = (sender_id == SELF_SENDER_ID) or (
        bool(row.get("self_wxid")) and str(sender_id) == str(row.get("self_wxid"))
    )
    # type 同样可能以 int(如 1) 传入，先转 str 再 strip
    mtype = str(row.get("type") or "").strip()
    is_group = str(username).endswith("@chatroom")

    # 系统消息
    if mtype in ("系统消息", "系统") or "system" in mtype.lower():
        return Msg("sys", _clean_text(content), who, attr="sys")

    # 拍一拍 -> tickle
    if ("拍一拍" in content) or ("拍了拍" in content) or mtype in ("拍一拍", "拍了拍"):
        return Msg("text", _clean_text(content), who, attr="tickle")

    # 群消息先剥离 `wxid:\n` 前缀拿到发送成员
    member_wxid = ""
    if is_group:
        mm = re.match(r"^\s*(wxid_[0-9A-Za-z_-]+|[A-Za-z0-9_-]+):\s*\n?", content)
        if mm:
            member_wxid = mm.group(1)
            text_body = content[mm.end():]
        else:
            text_body = content
    else:
        text_body = content

    # 媒体消息（图片/语音/视频/文件/链接/引用/合并转发/位置）
    if mtype in _MEDIA:
        ctype, marker = _MEDIA[mtype]
        attr = "self" if is_self else "friend"
        if is_self:
            sender = who
        elif is_group and member_wxid and member_resolver:
            sender = member_resolver(member_wxid) or member_wxid
        elif is_group and member_wxid:
            sender = member_wxid
        else:
            sender = who
        url = row.get("url") or ""
        quote = row.get("quote_content") or row.get("refermsg") or ""
        messages = row.get("messages") or []
        voice_text = row.get("text") or ""
        return Msg(ctype, marker, sender, attr=attr,
                   url=url, quote_content=quote, messages=messages, voice_text=voice_text)

    # 文本消息
    if is_group:
        if is_self:
            return Msg("text", _clean_text(text_body), who, attr="self")
        if member_wxid and member_resolver:
            sender = member_resolver(member_wxid) or member_wxid
        elif member_wxid:
            sender = member_wxid
        else:
            sender = who
        return Msg("text", _clean_text(text_body), sender, attr="friend")
    else:
        if is_self:
            return Msg("text", _clean_text(text_body), who, attr="self")
        return Msg("text", _clean_text(text_body), who, attr="friend")


class WeChat:
    """复刻 wxauto.WeChat 的微信 4.x 兼容实现。"""

    def __init__(self):
        self._wa_db = None
        self._quick_send = None
        self._quick_send_file = None
        self._quick_send_image = None
        self._db = None
        self._self_wxid = ""
        self._self_name = None
        self._listener = None
        self._lock = threading.Lock()
        self._queue = []                 # [(chat, Msg), ...]
        self._listening = set()          # 已注册监听的 username
        self._callbacks = {}             # username -> 用户回调
        self.listen = {}                 # nickname -> 会话句柄（keep_alive 用）
        self.A_MyIcon = _A_MyIcon(self)
        self._inited = False

    # ---- 懒初始化（import 阶段不触发，避免无微信时无法加载模块）----
    def _init(self):
        if self._inited:
            return
        wa_db, qs, qsf, qsi = _ensure_wechatauto()
        import wechatauto
        real = wechatauto.WeChat()
        self._db = real._db
        info = self._db.get_self_info()
        self._self_wxid = info.get("username") or ""
        self._self_name = info.get("nick_name") or self._self_wxid
        self._quick_send = qs
        self._quick_send_file = qsf
        self._quick_send_image = qsi
        # 自建原生 Listener（绕开库的逆向 _db_row_to_message）
        self._listener = wa_db.Listener(self._db, interval=1.0)
        self._listener.start()
        self._inited = True

    def _get_self_name(self):
        self._init()
        return self._self_name or ""

    @property
    def nickname(self):
        return self._get_self_name()

    # ---- 名称解析 ----
    def _resolve_username(self, name):
        """把显示名/备注/群名解析成 wechatauto 可用的 username（好友 wxid 或 群 wxid@chatroom）。

        这是「找不到人/找不到群」bug 的根因所在：原实现只精确匹配好友表的
        nick_name/remark，匹配不上就原样返回显示名，导致：
          - 群名：search_contact 只查好友表、查不到群，于是用群显示名而非
            wxid@chatroom 去注册监听 → 群消息永不被监听（“找不到群”）。
          - 备注≠昵称 或 无精确命中：同样用显示名注册 → 监听失效。
        修复：好友精确→群解析→好友子串兜底→首个命中，均失败才 best-effort 返回原名。
        """
        if not name:
            return name
        if name in ("filehelper", "文件传输助手"):
            return "filehelper"
        # 已经是 username（好友/群 wxid 或文件传输助手）直接复用，避免二次误解析
        if "@" in name or name.startswith("wxid_"):
            return name
        # 1) 好友：search_contact 精确匹配 nick_name / remark
        try:
            hits = list(self._db.search_contact(name))
        except Exception:
            hits = []
        for h in hits:
            if name in (h.get("nick_name"), h.get("remark")):
                return h["username"]
        # 2) 群：群不在好友表，需单独按群名解析（精确优先、子串兜底）
        try:
            gid = self._db.group_name_to_id(name)
            if gid:
                return gid
        except Exception:
            pass
        # 3) 好友：子串包含兜底（备注/昵称含目标名）
        for h in hits:
            disp = h.get("remark") or h.get("nick_name") or ""
            if name and name in disp:
                return h["username"]
        # 4) 好友：实在不行取首个命中
        if hits:
            return hits[0]["username"]
        # 5) 全部失败：best-effort 返回原名，让上层决定（通常仍能 UI 侧栏/搜索兜底）
        return name

    def _display_name(self, who):
        """open_chat 需要显示名（侧栏标题），若传入的是 wxid 则反查显示名。

        这是「回复时找不到人」bug 的另一半：bot 的回调 who 有时是 wxid
        （如联系人微信号本身就是 SamCai_，或 LISTEN_LIST 配了 wxid，或群 wxid），
        open_chat 按显示名搜侧栏必然落空 → 发送失败“找不到人”。
        反向解析用 wechatauto 的 get_nickname（username -> remark/nick_name）。
        显示名查不到时会原样返回自身，因此无条件反查是安全的。
        """
        if not who:
            return who
        try:
            nm = self._db.get_nickname(who)
            if nm and nm != who:
                return nm
        except Exception:
            pass
        return who

    def _resolve_member(self, group_username, member_wxid):
        try:
            members = self._db.get_group_members(group_username) or []
            for m in members:
                if m.get("username") == member_wxid:
                    return m.get("remark") or m.get("nick_name") or member_wxid
        except Exception:
            pass
        return member_wxid

    # ---- 监听 ----
    def AddListenChat(self, who=None, nickname=None, callback=None, savepic=True):
        """开始监听某会话。

        v3.25.1 调用形式：``AddListenChat(nickname=user_name, callback=message_listener)``。
        每收到一条消息直接回调 ``callback(msg, chat)``。
        """
        self._init()
        name = nickname or who
        if name is None:
            return None
        username = self._resolve_username(name)
        with self._lock:
            if username not in self._listening:
                self._listening.add(username)
                self._listener.add_listener(username, self._make_cb(name, username, callback))
            # 即使已监听，也更新用户回调（keep_alive 重加时用到）
            self._callbacks[username] = callback
        handle = _ChatEx(name, username)
        self.listen[name] = handle
        return handle

    def _make_cb(self, who, username, user_callback=None):
        def cb(row, listener):
            try:
                msg = _parse(
                    row, who, username,
                    member_resolver=lambda mid: self._resolve_member(username, mid),
                )
                if msg is None:
                    return
                if user_callback is not None:
                    try:
                        user_callback(msg, _Chat(who))
                    except Exception as e:
                        logger.error("wxauto_compat 用户回调异常: %r", e, exc_info=True)
                else:
                    with self._lock:
                        self._queue.append((_Chat(who), msg))
            except Exception as e:
                logger.error("wxauto_compat 内部回调异常: %r", e)
        return cb

    def GetListenMessage(self):
        """轮询式取消息（兼容保留，v3.25.1 实际走回调）。"""
        with self._lock:
            q = self._queue
            self._queue = []
        if not q:
            return {}
        result = {}
        for chat, m in q:
            result.setdefault(chat, []).append(m)
        return result

    def GetSessionList(self):
        self._init()
        try:
            sessions = self._db.get_sessions(limit=500)
            return [_Chat(s.get("nick_name") or s.get("username")) for s in sessions]
        except Exception:
            return []

    def GetAllSubWindow(self):
        """返回所有聊天窗口对象列表（get_chat_type_info 用）。"""
        self._init()
        try:
            sessions = self._db.get_sessions(limit=500)
            return [_ChatEx(s.get("nick_name") or s.get("username") or s.get("username"),
                            s.get("username") or "") for s in sessions]
        except Exception as e:
            logger.error("GetAllSubWindow 失败: %r", e)
            return []

    # ---- 发送 ----
    def SendMsg(self, msg, who):
        self._init()
        return self._quick_send(msg, self._display_name(who), verify=False)

    def SendFiles(self, filepath, who):
        self._init()
        return self._quick_send_file(filepath, self._display_name(who), verify=False)

    def SendImage(self, filepath, who):
        self._init()
        return self._quick_send_image(filepath, self._display_name(who), verify=False)

    def ChatWith(self, who):
        # wechatauto 通过 quick_send 直接搜索侧栏发送，无需预先 ChatWith
        return True

    def Show(self):
        """兼容占位：4.x 引擎无需置顶窗口。"""
        logger.debug("wxauto_compat: Show() 在微信4.x引擎下为兼容空操作")
        return True

    def VoiceCall(self, who):
        """4.x 引擎(wechatauto)暂不支持语音通话，降级为日志告警以免崩溃。"""
        logger.warning("wxauto_compat: VoiceCall 在微信4.x引擎(wechatauto)下暂不支持，已忽略 (who=%s)", who)
        return None

    def KeepRunning(self):
        """阻塞保活（引擎监听在后台线程运行）。"""
        logger.info("wxauto_compat: KeepRunning 进入阻塞保活（微信4.x引擎监听在后台线程）")
        try:
            while True:
                time.sleep(1)
        except (KeyboardInterrupt, SystemExit):
            pass

    def close(self):
        """停止监听线程并释放实例（bot.py 异常重置 wx 前调用）。"""
        try:
            if self._listener is not None:
                self._listener.stop()
        except Exception:
            pass
        self._listener = None
        self._inited = False
        with self._lock:
            self._listening.clear()
            self._callbacks.clear()
            self._queue = []
        self.listen.clear()


if __name__ == "__main__":
    import json
    print("== 单聊-对方发 ==")
    print(_parse({"content": "你好", "type": "文本", "sender_id": 2, "username": "wxid_friend"},
                 "张三", "wxid_friend"))
    print("== 单聊-自己发 ==")
    print(_parse({"content": "我在忙", "type": "文本", "sender_id": 1, "username": "wxid_friend"},
                 "张三", "wxid_friend"))
    print("== 群聊-成员发 ==")
    print(_parse({"content": "wxid_abc:\n晚上开会", "type": "文本", "sender_id": 2, "username": "xxx@chatroom"},
                 "测试群", "xxx@chatroom",
                 member_resolver=lambda mid: "李四" if mid == "wxid_abc" else mid))
    print("== 系统消息 ==")
    print(_parse({"content": "你已添加...", "type": "系统消息", "sender_id": 0, "username": "wxid_friend"},
                 "张三", "wxid_friend"))
