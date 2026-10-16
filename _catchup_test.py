# -*- coding: utf-8 -*-
"""补漏线程（_catchup_missed_once）离线单测——mock DB，不连微信、不发消息。

覆盖（对应 2026-10-16 修复）：
  1. 昵称解析（原 bug：get_messages(昵称) 永远空表，补漏形同虚设）
  2. 跨候选表取 create_time 最新（孤表旧消息竞争不过现表）
  3. 自己回声跳过（sender_id==1）
  4. 已回复判定（chat_history 最后一条 outgoing 且不早于该消息）
  5. 防风暴（_catchup_done）
  6. 群消息 "wxid:\n" 前缀剥离 + @chatroom 群判定（原实现对昵称永假）
  7. 解析异常/filehelper/无候选 等边界不炸

用法： .venv_bot/Scripts/python.exe _catchup_test.py
"""
import os
import sys
import types

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
os.chdir(ROOT)

SELF_ID = 1  # wechat_compat.SELF_SENDER_ID（本机微信 4.x 单聊实测 1=自己）


class FakeMsg:
    def __init__(self, mtype, content, sender, attr="friend", sender_remark="",
                 url="", quote_content="", messages=None, voice_text="",
                 local_id=None, create_time=None,
                 chat_username="", type_code=None):
        self.type = mtype
        self.content = content
        self.sender = sender
        self.attr = attr
        self.create_time = create_time
        self.chat_username = chat_username
        self.type_code = type_code


class FakeChat:
    def __init__(self, who):
        self.who = who


def install_fake_wechat_compat():
    """把 sys.modules 里的 wechat_compat 换成假模块。

    _catchup_missed_once / _catchup_newest_incoming 都是函数内
    `from wechat_compat import ...`，会走 sys.modules 缓存——换成假的即可
    拦住真实 Chat(who)（其构造会拉起 WeChatGUI/WeChatDB，测试里不能碰）。
    """
    fake = types.ModuleType("wechat_compat")
    fake.Msg = FakeMsg
    fake.Chat = FakeChat
    fake.SELF_SENDER_ID = SELF_ID
    sys.modules["wechat_compat"] = fake


class MockDB:
    """按 username 精确返回预置消息表（复刻 _find_msg_table 的 md5 定位语义：
    昵称查不到任何表）。"""

    def __init__(self, tables=None, contacts=None, groups=None,
                 raise_on_search=False):
        self.tables = tables or {}
        self.contacts = contacts or {}
        self.groups = groups or {}
        self.raise_on_search = raise_on_search
        self.get_calls = []

    def search_contact(self, keyword):
        if self.raise_on_search:
            raise RuntimeError("contact.db 炸了")
        return list(self.contacts.get(keyword, []))

    def group_name_to_id(self, name):
        return self.groups.get(name)

    def get_messages(self, user, limit=20, offset=0):
        self.get_calls.append(user)
        return list(self.tables.get(user, []))


class FakeHistory:
    def __init__(self, latest=None):
        self.latest = latest

    def latest_message(self, who):
        return self.latest


results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("  [PASS] " if cond else "  [FAIL] ") + name + ((" | " + str(detail)) if detail else ""))


def row(sender_id, mtype, content, ct, sender_username=""):
    return {"sender_id": sender_id, "type": mtype, "content": content,
            "create_time": ct, "sender_username": sender_username,
            "sort_seq": ct}


def run(bot, monkey_impl):
    """跑一轮补漏，返回 _message_listener_impl 收到的 (msg, chat) 列表。

    必须 try/finally 恢复真函数：补漏内部按模块全局名调用
    _message_listener_impl，替换生效；但若不恢复，后续场景
    （如第 8 节 feed_listener）调到的就是 lambda，真逻辑被跳过。
    """
    seen = []
    orig = bot._message_listener_impl
    bot._message_listener_impl = lambda msg, chat: seen.append((msg, chat))
    try:
        bot._catchup_missed_once()
    finally:
        bot._message_listener_impl = orig
    return seen


def reset(bot):
    bot._catchup_inflight = set()
    bot._catchup_done = {}
    bot._catchup_primed = False
    bot.user_names = []
    bot.forward_hub = None


def main():
    import bot  # 先用真 wechat_compat 完成 bot 模块级导入，再换假模块
    install_fake_wechat_compat()
    reset(bot)

    # ---------- 1. _resolve_catchup_users 直接单测 ----------
    print("\n=== 1. _resolve_catchup_users 解析 ===")
    db = MockDB(contacts={"凯哥": [
        {"username": "wxid_old1"}, {"username": "wxid_old1"},  # 重复身份去重
        {"username": "SamCai_"},
    ]})
    us = bot._resolve_catchup_users("凯哥", db)
    check("多身份去重保序", us == ["wxid_old1", "SamCai_"], us)
    check("filehelper 特判", bot._resolve_catchup_users("文件传输助手", db) == ["filehelper"])
    check("空 who 返回空", bot._resolve_catchup_users("", db) == [])
    db2 = MockDB(contacts={"运营群": [{"username": "wxid_x"}]},
                 groups={"运营群": "888@chatroom"})
    us2 = bot._resolve_catchup_users("运营群", db2)
    check("群名后缀触发群解析（即使好友表有命中）",
          us2 == ["wxid_x", "888@chatroom"], us2)
    db3 = MockDB()
    check("全落空回退 [who]", bot._resolve_catchup_users("查无此人", db3) == ["查无此人"])
    db4 = MockDB(raise_on_search=True, groups={"异常群": "999@chatroom"})
    us4 = bot._resolve_catchup_users("异常群", db4)
    check("search_contact 抛异常不炸，走群解析", us4 == ["999@chatroom"], us4)

    # ---------- 2. 昵称解析 + 孤表跨表（原 bug 回归） ----------
    print("\n=== 2. 昵称解析 + 跨候选表取最新（原 bug 回归） ===")
    reset(bot)
    bot.user_names = ["凯哥"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={
            # 孤表（旧身份，search_contact 第一个命中）：只有旧消息 + 自己的回声
            "wxid_old1": [row(SELF_ID, "文本", "[自动回复] 在的", 5000),
                          row(2, "文本", "好久不见", 1000)],
            # 现表：真正的未读新消息
            "SamCai_": [row(2, "文本", "在吗", 3000)],
        },
        contacts={"凯哥": [{"username": "wxid_old1"}, {"username": "SamCai_"}]},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    ok = (len(seen) == 1 and seen[0][0].content == "在吗"
          and seen[0][1].who == "凯哥" and seen[0][0].chat_username == "SamCai_")
    check("昵称可解析、跨表取 create_time 最新、chat_username 落现表", ok,
          f"n={len(seen)} content={seen[0][0].content if seen else None!r}")

    # ---------- 3. 自己回声跳过 ----------
    print("\n=== 3. 自己回声跳过（sender_id==1） ===")
    reset(bot)
    bot.user_names = ["凯哥"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [
            row(SELF_ID, "文本", "[自动回复] 你好呀", 4000),
            row(2, "文本", "在吗", 3000),
        ]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    check("跳过自己回声，取更早的对方消息",
          len(seen) == 1 and seen[0][0].content == "在吗",
          f"content={seen[0][0].content if seen else None!r}")

    # ---------- 4. 已回复判定 ----------
    print("\n=== 4. 已回复判定（chat_history） ===")
    reset(bot)
    bot.user_names = ["凯哥"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "文本", "在吗", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot.chat_history = FakeHistory(latest={"direction": "outgoing", "ts": 3500})
    seen = run(bot, None)
    check("最后一条是发出且时间不早于该消息 → 不补", len(seen) == 0)
    bot.chat_history = FakeHistory(latest={"direction": "incoming", "ts": 3500})
    bot._catchup_done = {}
    seen = run(bot, None)
    check("最后一条是收到 → 仍补", len(seen) == 1)
    bot.chat_history = FakeHistory(latest={"direction": "outgoing", "ts": 2000})
    bot._catchup_done = {}
    seen = run(bot, None)
    check("发出但早于该消息（先回后问）→ 仍补", len(seen) == 1)

    # ---------- 5. 防风暴 ----------
    print("\n=== 5. 防风暴（_catchup_done） ===")
    reset(bot)
    bot.user_names = ["凯哥"]
    bot._catchup_done["凯哥"] = 3000.0
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "文本", "在吗", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    check("同一条已补过 → 跳过", len(seen) == 0)

    # ---------- 6. 群消息前缀剥离 ----------
    print("\n=== 6. 群消息 wxid 前缀剥离 + @chatroom 群判定 ===")
    reset(bot)
    bot.user_names = ["快剧运营"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"777@chatroom": [
            row(5, "文本", "wxid_member1:\n大家好呀", 6000, "wxid_member1")]},
        groups={"快剧运营": "777@chatroom"},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    ok = (len(seen) == 1 and seen[0][0].sender == "wxid_member1"
          and seen[0][0].content == "大家好呀"
          and seen[0][0].chat_username == "777@chatroom")
    check("群名前剥 wxid、chat_username 落 @chatroom 表", ok,
          f"sender={seen[0][0].sender if seen else None!r} "
          f"content={seen[0][0].content if seen else None!r}")

    # 6b. sender 以 DB 索引字段为准：content 前缀与 sender_username 不一致时
    #     （自定义微信号成员，前缀仍是 wxid_ 历史格式）取 sender_username
    reset(bot)
    bot.user_names = ["快剧运营"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"777@chatroom": [
            row(5, "文本", "wxid_member1:\n大家好呀", 6000, "customid_999")]},
        groups={"快剧运营": "777@chatroom"},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    ok = (len(seen) == 1 and seen[0][0].sender == "customid_999"
          and seen[0][0].content == "大家好呀")
    check("sender 取 DB 索引字段（非 content 前缀），前缀只用于剥正文", ok,
          f"sender={seen[0][0].sender if seen else None!r}")

    # 6c. _extract_group_sender 放宽到通用 username（vendor.wx 兜底解析）
    sys.path.insert(0, os.path.join(ROOT, "vendor"))
    from wechatauto.wx import _extract_group_sender as _egs
    check("wxid_ 前缀可解析", _egs("wxid_abc123:\n你好") == "wxid_abc123")
    check("自定义微信号前缀可解析（原来只认 wxid_，漏判）",
          _egs("customid_999:\n你好") == "customid_999")
    check("冒号后非换不当前缀（正文「你好: 在吗」不误判）",
          _egs("你好: 在吗") == "")
    check("空前缀不炸", _egs("") == "" and _egs(None) == "")
    check("中文昵称不做前缀（username 规则不含中文）", _egs("张三:\n大家好") == "")

    # ---------- 7. 边界不炸 ----------
    print("\n=== 7. 边界：无候选 / 空表 / 只有占位符 ===")
    reset(bot)
    bot.user_names = ["查无此人", "凯哥"]
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "图片", "[图片]", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot.chat_history = FakeHistory(latest=None)
    seen = run(bot, None)
    check("无候选 + 仅占位符消息 → 静默跳过不炸", len(seen) == 0)

    # ---------- 8. 监听覆盖推进水位 × 补漏不重复补（2026-10-16 二次修复） ----------
    print("\n=== 8. 监听推水位 × 补漏联动 ===")

    class ConsumeHub:
        """水位推进后即「消费」消息并 return，把流程截断在 AI 管线之前。"""
        def handle_incoming(self, *a, **k):
            return True

    def feed_listener(bot, who, content, ct, attr="friend"):
        """模拟实时监听收到一条消息（走 _message_listener_impl 入口）。"""
        msg = FakeMsg("text", content, "wxid_peer", attr=attr, create_time=ct)
        bot._message_listener_impl(msg, FakeChat(who))

    def setup_feed(bot):
        bot.forward_hub = ConsumeHub()
        bot.chat_history = FakeHistory(latest=None)
        bot.record_user_interaction = lambda *a, **k: None

    # 8a 核心场景：监听见过并「主动不回复」的消息，补漏不得再补
    reset(bot)
    bot.user_names = ["凯哥"]
    setup_feed(bot)
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "文本", "在吗", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot._catchup_primed = True
    feed_listener(bot, "凯哥", "在吗", 3000)          # 监听到位但选择不回（概率/禁词/hold）
    check("监听覆盖后水位已推进", bot._catchup_done.get("凯哥") == 3000.0,
          bot._catchup_done)
    seen = run(bot, None)
    check("监听已覆盖的消息补漏不再重复补（修复前每 30s 补一次）",
          len(seen) == 0, f"seen={len(seen)}")

    # 8b 启动保护：首扫完成前（primed=False）监听不得推水位，否则挡住启动前未读
    reset(bot)
    bot.user_names = ["凯哥"]
    setup_feed(bot)
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "文本", "在吗", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot._catchup_primed = False
    feed_listener(bot, "凯哥", "在吗", 3000)
    check("首扫前监听不推水位（启动前未读可补）", bot._catchup_done.get("凯哥", 0.0) == 0.0,
          bot._catchup_done)
    seen = run(bot, None)
    check("启动窗口内的新消息补漏首扫照常补", len(seen) == 1, f"seen={len(seen)}")

    # 8c 水位只升不降（乱序/过期回调不得回撤）
    reset(bot)
    setup_feed(bot)
    bot._catchup_primed = True
    bot._catchup_done["凯哥"] = 5000.0
    feed_listener(bot, "凯哥", "旧消息", 3000)
    check("过期消息回调不回撤水位", bot._catchup_done.get("凯哥") == 5000.0,
          bot._catchup_done)

    # 8d 自己发出的回显不推进 incoming 水位
    reset(bot)
    setup_feed(bot)
    bot._catchup_primed = True
    feed_listener(bot, "凯哥", "[自动回复] 在的", 4000, attr="self")
    check("self 回显不推水位", bot._catchup_done.get("凯哥", 0.0) == 0.0,
          bot._catchup_done)

    # 8e 群消息同样覆盖
    reset(bot)
    setup_feed(bot)
    bot._catchup_primed = True
    feed_listener(bot, "快剧运营", "大家好", 7000, attr="group")
    check("群消息也推进水位", bot._catchup_done.get("快剧运营") == 7000.0,
          bot._catchup_done)

    # 8f 回归：监听真正回复过的消息，chat_history 挡箭牌仍然有效（不依赖水位）
    reset(bot)
    bot.user_names = ["凯哥"]
    setup_feed(bot)
    bot.chat_history = FakeHistory(latest={"direction": "outgoing", "ts": 3500})
    bot.wx = types.SimpleNamespace(_db=MockDB(
        tables={"SamCai_": [row(2, "文本", "在吗", 3000)]},
        contacts={"凯哥": [{"username": "SamCai_"}]},
    ))
    bot._catchup_primed = True
    seen = run(bot, None)
    check("已回复消息仍由 chat_history 挡住（既有逻辑不破）", len(seen) == 0)

    # ---------- 汇总 ----------
    failed = [n for n, ok in results if not ok]
    print("\n===== 汇总: %d/%d 通过 =====" % (len(results) - len(failed), len(results)))
    if failed:
        for n in failed:
            print("  FAILED:", n)
        sys.exit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
