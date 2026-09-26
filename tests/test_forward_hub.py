# -*- coding: utf-8 -*-
"""forward_hub 单元测试：零微信依赖，纯逻辑覆盖（多对多 / 对称双向模型）。

运行：
    python tests/test_forward_hub.py
"""
import os
import sys
import time
import shutil
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.dirname(HERE)
sys.path.insert(0, PKG)

import forward_hub
from forward_hub import (_HUB, handle_incoming, save_rules, all_chats,
                         SALUTATION_AI_TIMEOUT)

ROBOT = "我"


class FakeMsg:
    def __init__(self, content, mtype="text", quote=None, merge=None):
        self.content = content
        self.type = mtype
        self.quote_content = quote
        self._merge = merge

    def to_text(self):
        return "[语音转写]"

    def get_url(self):
        return "http://card"

    def get_messages(self):
        return self._merge


class FakeWx:
    """模拟 wechat_compat.WeChat：文本/文件/图片发送 + 媒体下载（_dl 可控）。"""

    def __init__(self):
        self.sent = []      # (text, who)
        self.files = []     # (path, who)  SendFiles
        self.images = []    # (path, who)  SendImage
        self.dl_calls = 0
        self._dl = None     # DownloadMedia 返回值：路径 / None / callable

    def SendMsg(self, msg, who):
        self.sent.append((msg, who))
        return True

    def SendFiles(self, filepath, who):
        self.files.append((filepath, who))
        return True

    def SendImage(self, filepath, who):
        self.images.append((filepath, who))
        return True

    def DownloadMedia(self, msg, save_dir=None, timeout=20.0):
        self.dl_calls += 1
        if callable(self._dl):
            return self._dl(msg, save_dir, timeout)
        return self._dl


TMP = None


def setup_module(module):
    global TMP
    TMP = tempfile.mkdtemp()


def teardown_module(module):
    shutil.rmtree(TMP, ignore_errors=True)


def reset():
    global TMP
    if TMP is None:
        TMP = tempfile.mkdtemp()
    _HUB.__init__()
    _HUB._base = TMP
    _HUB._rules_path = os.path.join(TMP, "forward_rules.json")
    _HUB._log_path = os.path.join(TMP, "forward_log.json")
    _HUB.wx = FakeWx()
    _HUB.logger = None
    _HUB.robot_name = ROBOT
    _HUB._rules = None
    _HUB._buckets = {}
    _HUB._inflight = {}
    _HUB._ensure_files()
    _HUB._load_rules(force=True)


def set_rules(rules, enabled=True, rate=None):
    cfg = {
        "enabled": enabled,
        "rate_limit": rate or {"window_sec": 10, "max_msgs": 15},
        "rules": rules,
    }
    save_rules(cfg)


def base_rule(**kw):
    r = {
        "id": "r1", "name": "测试转发对", "enabled": True,
        "party_a": ["张三"], "party_b": ["李四"],
        "bidirectional": True, "source_label": True, "replace_alias": True,
        "salutation_mode": "auto",
        "label_format": "[来自 {src}{member}]",
        "ai_reply_to_source": False,
        "monitor_content": "all", "monitor_types": [], "monitor_keywords": [],
        "media_mode": "original",
        "respect_quiet_time": False,
    }
    r.update(kw)
    return r


def make_file(name):
    """在临时目录造一个真实文件，模拟媒体下载成功后的落盘路径。"""
    p = os.path.join(TMP, name)
    with open(p, "wb") as f:
        f.write(b"x")
    return p


# ---------------- 基础匹配 ----------------
def test_self_and_tickle_ignored():
    reset()
    set_rules([base_rule()])
    assert handle_incoming("张三", "张三", "text", "self", FakeMsg("x")) is False
    assert handle_incoming("张三", "张三", "tickle", "tickle", FakeMsg("x")) is False


def test_disabled_global():
    reset()
    set_rules([base_rule()], enabled=False)
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("你好")) is False


def test_non_configured_chat_passthrough():
    reset()
    set_rules([base_rule()])
    # 王五不在任何转发对里 -> 放行给 AI（False），且不发送
    assert handle_incoming("王五", "王五", "text", "friend", FakeMsg("你好")) is False
    assert _HUB.wx.sent == []


# ---------------- 正向 + 回程 对称双向 ----------------
def test_forward_a_to_b():
    reset()
    set_rules([base_rule()])
    consumed = handle_incoming("张三", "张三", "text", "friend", FakeMsg("你好"))
    assert consumed is True
    assert _HUB.wx.sent == [("[来自 张三] 你好", "李四")]


def test_forward_b_to_a():
    reset()
    set_rules([base_rule()])
    consumed = handle_incoming("李四", "李四", "text", "friend", FakeMsg("收到"))
    assert consumed is True
    assert _HUB.wx.sent == [("[来自 李四] 收到", "张三")]


# ---------------- 多对多 mesh ----------------
def test_many_to_many_mesh():
    reset()
    set_rules([base_rule(party_a=["张三", "王五"], party_b=["李四", "赵六"])])
    # 张三(甲方)来消息 -> 转发给乙方全体 李四、赵六
    _HUB.wx.sent.clear()
    consumed = handle_incoming("张三", "张三", "text", "friend", FakeMsg("全员通知"))
    assert consumed is True
    dsts = set(x[1] for x in _HUB.wx.sent)
    assert dsts == {"李四", "赵六"}
    # 赵六(乙方)来消息 -> 转发给甲方全体 张三、王五
    _HUB.wx.sent.clear()
    consumed = handle_incoming("赵六", "赵六", "text", "friend", FakeMsg("乙方回复"))
    assert consumed is True
    dsts = set(x[1] for x in _HUB.wx.sent)
    assert dsts == {"张三", "王五"}


def test_mesh_label_per_destination():
    reset()
    set_rules([base_rule(party_a=["客户群A@chatroom"], party_b=["李四", "赵六"])])
    consumed = handle_incoming("客户群A@chatroom", "小王", "text", "group", FakeMsg("在吗"))
    assert consumed is True
    # 两个乙方都收到，且都带群-成员标签
    labels = [x[0] for x in _HUB.wx.sent]
    assert all(l.startswith("[来自 客户群A@chatroom-小王]") for l in labels)
    assert set(x[1] for x in _HUB.wx.sent) == {"李四", "赵六"}


# ---------------- 群源标签 ----------------
def test_group_source_label():
    reset()
    set_rules([base_rule(party_a=["客户群A@chatroom"], party_b=["李四"])])
    consumed = handle_incoming("客户群A@chatroom", "小王", "text", "group", FakeMsg("在吗"))
    assert consumed is True
    assert _HUB.wx.sent == [("[来自 客户群A@chatroom-小王] 在吗", "李四")]


# ---------------- 单向（只转甲方->乙方，乙方->甲方放行）----------------
def test_single_direction_suppress():
    reset()
    set_rules([base_rule(bidirectional=False)])
    # 甲方 -> 乙方：照常转发并抑制 AI
    consumed = handle_incoming("张三", "张三", "text", "friend", FakeMsg("只发给乙方"))
    assert consumed is True
    assert _HUB.wx.sent == [("[来自 张三] 只发给乙方", "李四")]
    # 乙方 -> 甲方：单向模式下不转发，放行给 AI
    _HUB.wx.sent.clear()
    consumed = handle_incoming("李四", "李四", "text", "friend", FakeMsg("回给甲方"))
    assert consumed is False
    assert _HUB.wx.sent == []


# ---------------- 去重 ----------------
def test_dedup_within_window():
    reset()
    set_rules([base_rule()])
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("重复")) is True
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("重复")) is True  # 5s 内重复
    assert len(_HUB.wx.sent) == 1


# ---------------- 限流 ----------------
def test_rate_limit():
    reset()
    set_rules([base_rule()], rate={"window_sec": 10, "max_msgs": 15})
    for i in range(30):
        c = handle_incoming("张三", "张三", "text", "friend", FakeMsg("消息%02d" % i))
        assert c is True
    # 仅前 15 条被实际发送，其余被限流丢弃
    assert len(_HUB.wx.sent) == 15


# ---------------- 媒体 ----------------
def test_media_placeholder():
    reset()
    set_rules([base_rule(media_mode="placeholder")])
    consumed = handle_incoming("张三", "张三", "image", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.sent[0][1] == "李四"
    assert "[图片]" in _HUB.wx.sent[0][0]


def test_media_skip():
    reset()
    set_rules([base_rule(media_mode="skip")])
    consumed = handle_incoming("张三", "张三", "video", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.sent == []


# ---------------- 媒体原文件转发 ----------------
def test_media_original_image():
    reset()
    set_rules([base_rule(media_mode="original")])
    p = make_file("pic.jpg")
    _HUB.wx._dl = p
    consumed = handle_incoming("张三", "张三", "image", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.images == [(p, "李四")]          # 图片走 SendImage
    assert _HUB.wx.files == []
    assert not any("[图片]" in m for m, _ in _HUB.wx.sent)   # 不再发占位
    assert _HUB.wx.sent == [("[来自 张三]", "李四")]          # 只先发一条来源标签


def test_media_original_video_file():
    reset()
    set_rules([base_rule(media_mode="original")])
    p = make_file("clip.mp4")
    _HUB.wx._dl = p
    consumed = handle_incoming("张三", "张三", "video", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.files == [(p, "李四")]           # 视频走 SendFiles
    assert _HUB.wx.images == []


def test_media_original_fallback_placeholder():
    reset()
    set_rules([base_rule(media_mode="original")])
    _HUB.wx._dl = None                              # 提取失败
    consumed = handle_incoming("张三", "张三", "file", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.files == [] and _HUB.wx.images == []
    assert any("[文件]" in m for m, _ in _HUB.wx.sent)   # 自动降级占位


def test_media_sendimage_failure_falls_back_to_files():
    reset()
    set_rules([base_rule(media_mode="original")])
    p = make_file("pic2.jpg")
    _HUB.wx._dl = p

    def _boom(path, who):
        raise RuntimeError("SendImage 不可用")
    _HUB.wx.SendImage = _boom                       # 图片通道故障
    consumed = handle_incoming("张三", "张三", "image", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.files == [(p, "李四")]           # 回退 SendFiles


def test_voice_transcript_plus_original_file():
    reset()
    set_rules([base_rule(media_mode="original")])
    p = make_file("voice.silk")
    _HUB.wx._dl = p
    consumed = handle_incoming("张三", "张三", "voice", "friend", FakeMsg(""))
    assert consumed is True
    # 单发：原文件（文件名含转写文本）+ 可选来源标签，不再单独发文字气泡（避免双发）
    assert len(_HUB.wx.files) == 1
    fp, who = _HUB.wx.files[0]
    assert who == "李四"
    base = os.path.basename(fp)
    assert "[语音转写]" in base            # 转写文本已写入文件名
    assert base.endswith(".silk")          # 仍是原始语音文件
    # 不应再出现独立的「[语音消息]」文字气泡
    assert not any("[语音消息]" in m for m, _ in _HUB.wx.sent)


# ---------------- 监测内容：全部 / 指定类型 / 关键字 ----------------
def test_monitor_all_default_forwards_everything():
    reset()
    set_rules([base_rule()])  # 默认 monitor_content=all
    _HUB.wx.sent.clear()
    _HUB.wx._dl = make_file("voice.silk")
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("随便说点"))
    handle_incoming("张三", "张三", "voice", "friend", FakeMsg(""))
    assert len(_HUB.wx.sent) >= 1        # 文字已转发
    assert len(_HUB.wx.files) == 1       # 语音已单发


def test_monitor_types_only_text_drops_voice():
    reset()
    set_rules([base_rule(monitor_content="types", monitor_types=["text"])])
    _HUB.wx.sent.clear()
    _HUB.wx.files.clear()
    # 文字命中类型 -> 转发
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("hi")) is True
    assert len(_HUB.wx.sent) == 1
    # 语音不在类型列表 -> 不回应（不转发、不触发 AI）
    _HUB.wx.sent.clear()
    assert handle_incoming("张三", "张三", "voice", "friend", FakeMsg("")) is False
    assert len(_HUB.wx.sent) == 0
    assert len(_HUB.wx.files) == 0


def test_monitor_keywords_only_matching_text():
    reset()
    set_rules([base_rule(monitor_content="keywords", monitor_keywords=["订单", "退款"])])
    _HUB.wx.sent.clear()
    # 含关键字 -> 转发
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("我的订单到哪了")) is True
    assert len(_HUB.wx.sent) == 1
    # 不含关键字 -> 不回应
    _HUB.wx.sent.clear()
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("今天天气不错")) is False
    assert len(_HUB.wx.sent) == 0


def test_monitor_keywords_empty_means_all():
    reset()
    set_rules([base_rule(monitor_content="keywords", monitor_keywords=[])])
    _HUB.wx.sent.clear()
    assert handle_incoming("张三", "张三", "text", "friend", FakeMsg("无关内容")) is True
    assert len(_HUB.wx.sent) == 1


def test_monitor_types_applies_to_group_too():
    reset()
    # 把群加入甲方，使其成为被监测对象
    set_rules([base_rule(party_a=["群聊@chatroom"], party_b=["李四"],
                         monitor_content="types", monitor_types=["image"])])
    _HUB.wx.sent.clear()
    _HUB.wx.images.clear()
    _HUB.wx._dl = make_file("img.png")
    # 群里的图片命中类型 -> 转发
    assert handle_incoming("群聊@chatroom", "小王", "image", "group", FakeMsg(None)) is True
    assert len(_HUB.wx.images) == 1
    # 群里的文字不在类型列表 -> 不回应
    _HUB.wx.images.clear()
    assert handle_incoming("群聊@chatroom", "小王", "text", "group", FakeMsg("hi")) is False


def test_media_placeholder_mode_no_download():
    reset()
    set_rules([base_rule(media_mode="placeholder")])
    consumed = handle_incoming("张三", "张三", "image", "friend", FakeMsg(None))
    assert consumed is True
    assert _HUB.wx.dl_calls == 0                    # 占位模式不下载
    assert any("[图片]" in m for m, _ in _HUB.wx.sent)


# ---------------- 句首称呼语：识别 -> 换成「我的称呼」/ 认不出 -> 去掉 ----------------
def _roster_rule(a=None, b=None):
    """甲=张三(张总)、乙=李四(李经理) 的常用配置。"""
    return base_rule(party_a=a or ["张三=张总"], party_b=b or ["李四=李经理"])


def test_salutation_name_replaced_by_my_alias():
    """句首称呼是成员本名 -> 换成「我对他的称呼」。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李四，把合同发我"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 张总] 李经理，把合同发我"


def test_salutation_surname_plus_title():
    """句首是「姓氏 + 头衔」-> 回查到成员 -> 换成我的称呼。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李经理，麻烦看下方案"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 李经理，麻烦看下方案"


def test_salutation_member_without_alias_removed():
    """认出是谁但该成员没配称呼 -> 直接去掉称呼（对端不适用）。"""
    reset()
    set_rules([_roster_rule(b=["李四"])])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李四，把合同发我"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 把合同发我"


def test_salutation_unknown_person_removed():
    """是称谓但名单里没有这个人 -> 去掉。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("陈总，你好啊"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 你好啊"


def test_salutation_greeting_removed():
    """句首问候语（不属于任何人）-> 去掉。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("你好，在吗"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 在吗"


def test_salutation_kin_alias_hit():
    """亲戚类称呼：句首称呼正好是「我的称呼」-> 认出该成员并保留。"""
    reset()
    set_rules([_roster_rule(a=["王五=二舅"], b=["李四=李经理"])])
    _HUB.wx.sent.clear()
    handle_incoming("王五", "王五", "text", "friend", FakeMsg("二舅，吃饭了吗"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 二舅] 二舅，吃饭了吗"


def test_salutation_only_salutation_not_emptied():
    """整句只有称呼时不能把消息删空 -> 保留原文。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李四，"))
    text = _HUB.wx.sent[0][0]
    assert text.endswith("李四，") or text.endswith("李经理，")


def test_salutation_mode_off():
    """off：句首不处理；配合 replace_alias=False 完全保持原文。"""
    reset()
    set_rules([_roster_rule() | {"salutation_mode": "off", "replace_alias": False}])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李四，把合同发我"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张三] 李四，把合同发我"


def test_salutation_mode_rule_never_calls_ai():
    """rule 模式：即使注入了 AI 回调也不应触发（零延迟、零成本）。"""
    reset()
    set_rules([_roster_rule() | {"salutation_mode": "rule"}])
    calls = []

    def ai(text, roster):
        calls.append(text)
        return "不该被采用"

    _HUB.ai_resolver = ai
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("你好，在吗"))
    assert calls == []
    assert _HUB.wx.sent[0][0] == "[来自 张总] 在吗"


def test_salutation_ai_used_when_local_uncertain():
    """本地拿不准 + 形态疑似称呼 -> 问 AI，AI 结果通过校验后被采用。"""
    reset()
    set_rules([_roster_rule()])
    seen = {}

    def ai(text, roster):
        seen["text"] = text
        seen["roster"] = roster
        return "帮我订个餐"

    _HUB.ai_resolver = ai
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("小张，帮我订个餐"))
    assert seen.get("text") == "小张，帮我订个餐"
    assert any(m["name"] == "张三" for m in seen.get("roster", []))
    assert _HUB.wx.sent[0][0] == "[来自 张总] 帮我订个餐"


def test_salutation_ai_result_rejected_when_over_edited():
    """AI 改写了称呼以外的内容 -> 结果不可信，丢弃，退回本地结果。"""
    reset()
    set_rules([_roster_rule()])

    def ai(text, roster):
        return "已帮您安排妥当，请放心"      # 与原文尾部完全不一致

    _HUB.ai_resolver = ai
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("小张，帮我订个餐"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 小张，帮我订个餐"


def test_salutation_ai_timeout_falls_back():
    """AI 卡住（超过超时）-> 不死等，退回本地规则结果。"""
    reset()
    set_rules([_roster_rule()])

    def ai(text, roster):
        import time as _t
        _t.sleep(3)
        return "迟到的结果"

    _HUB.ai_resolver = ai
    _HUB.wx.sent.clear()
    t0 = time.time()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("小张，帮我订个餐"))
    cost = time.time() - t0
    assert cost < SALUTATION_AI_TIMEOUT, f"超时保护失效，耗时 {cost:.1f}s"
    assert _HUB.wx.sent[0][0] == "[来自 张总] 小张，帮我订个餐"


def test_salutation_not_triggered_without_separator():
    """整句没有分隔符 -> 不当成称呼，避免误删正文。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("李四直接找我"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 李经理直接找我"   # 只做正文本名替换，不误删


def test_salutation_self_reference_untouched():
    """句首是来源本人（自称）-> 不替换也不删除，保持原样。"""
    reset()
    set_rules([_roster_rule()])
    _HUB.wx.sent.clear()
    # 张三的会话里出现「张三，」属于自称，不是对别人的称呼
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("张三，我今天有事"))
    text = _HUB.wx.sent[0][0]
    assert text == "[来自 张总] 张总，我今天有事"   # 只做正文本名替换，称呼逻辑不动它


def test_salutation_common_chatter_not_sent_to_ai():
    """常见寒暄开头不值得为它跑一次 AI（省 token）。"""
    reset()
    set_rules([_roster_rule()])
    calls = []

    def ai(text, roster, source=None):
        calls.append(text)
        return "被 AI 改写"

    _HUB.ai_resolver = ai
    for msg in ("好的，我马上处理", "收到，稍后回复", "另外，还有一件事，麻烦你"):
        _HUB.wx.sent.clear()
        handle_incoming("张三", "张三", "text", "friend", FakeMsg(msg))
    assert calls == [], f"不应触发 AI，实际调用了 {calls}"


def test_salutation_ai_receives_source_hint():
    """AI 回调能拿到 source（用于提示不要把自称当称呼）。"""
    reset()
    set_rules([_roster_rule()])
    got = {}

    def ai(text, roster, source=None):
        got["source"] = source
        return text

    _HUB.ai_resolver = ai
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("小张，帮我订个餐"))
    assert got.get("source") == "张三"


# ---------------- 成员称呼（alias） ----------------
def test_alias_label_and_body_replace():
    """甲方张三（称呼 张总）发言 -> 乙方李四：标签与正文都用称呼。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
    )])
    _HUB.wx.sent.clear()
    consumed = handle_incoming("张三", "张三", "text", "friend", FakeMsg("我是张三，请找李四"))
    assert consumed is True
    assert len(_HUB.wx.sent) == 1
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 张总] 我是张总，请找李经理"


def test_alias_reverse_direction():
    """乙方李四（称呼 李经理）发言 -> 甲方张三：反向同样用乙方自己的称呼。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
    )])
    _HUB.wx.sent.clear()
    consumed = handle_incoming("李四", "李四", "text", "friend", FakeMsg("收到，张三那边我说过了"))
    assert consumed is True
    text, dst = _HUB.wx.sent[0]
    assert dst == "张三"
    assert text == "[来自 李经理] 收到，张总那边我说过了"


def test_alias_group_name_and_member():
    """群名有称呼 -> 标签用称呼；群成员未配置称呼 -> 保持原样。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "客户群A@chatroom", "alias": "A群"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
    )])
    _HUB.wx.sent.clear()
    handle_incoming("客户群A@chatroom", "张三", "text", "group", FakeMsg("张三在群里说话"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 A群-张三] 张三在群里说话"


def test_alias_group_sender_replaced():
    """群成员本身也在成员表里且有称呼 -> 标签中的成员名同样替换。"""
    reset()
    set_rules([base_rule(
        party_a=["客户群A@chatroom", {"name": "张三", "alias": "张总"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
    )])
    _HUB.wx.sent.clear()
    handle_incoming("客户群A@chatroom", "张三", "text", "group", FakeMsg("我来跟进"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 客户群A@chatroom-张总] 我来跟进"


def test_alias_replace_disabled():
    """关闭 replace_alias：正文与标签都保持真名。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
        replace_alias=False,
    )])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("我是张三"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 张三] 我是张三"


def test_alias_string_form_compatible():
    """兼容便捷写法 "会话名=称呼"（WebUI textarea 行格式）。"""
    reset()
    set_rules([base_rule(party_a=["张三=张总"], party_b=["李四=李经理"])])
    rule = forward_hub.get_rules()["rules"][0]
    assert rule["party_a"] == [{"name": "张三", "alias": "张总"}]
    assert rule["party_b"] == [{"name": "李四", "alias": "李经理"}]
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("我是张三"))
    assert _HUB.wx.sent[0] == ("[来自 张总] 我是张总", "李四")


def test_alias_long_name_first():
    """长名优先替换：张三丰不会被「张三」先替换成「张总丰」。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}, {"name": "张三丰", "alias": "张道长"}],
        party_b=["李四"],
    )])
    _HUB.wx.sent.clear()
    handle_incoming("张三丰", "张三丰", "text", "friend", FakeMsg("张三丰和张三都来了"))
    text, dst = _HUB.wx.sent[0]
    assert dst == "李四"
    assert text == "[来自 张道长] 张道长和张总都来了"


def test_alias_no_alias_members_untouched():
    """未配称呼的成员：正文与标签保持原样（向后兼容纯字符串成员）。"""
    reset()
    set_rules([base_rule(party_a=["张三"], party_b=["李四"])])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "text", "friend", FakeMsg("我是张三"))
    assert _HUB.wx.sent[0] == ("[来自 张三] 我是张三", "李四")


def test_all_chats_returns_names_only():
    """all_chats 用于注册监听，必须返回会话名而不是称呼。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}],
        party_b=[{"name": "李四", "alias": "李经理"}],
    )])
    assert set(all_chats()) == {"张三", "李四"}


def test_alias_applies_to_voice_transcript():
    """语音转写文本里的本名同样被替换为称呼。"""
    reset()
    set_rules([base_rule(
        party_a=[{"name": "张三", "alias": "张总"}],
        party_b=["李四"],
        media_mode="placeholder",
    )])
    _HUB.wx.sent.clear()
    handle_incoming("张三", "张三", "voice", "friend", FakeMsg(""))
    text = _HUB.wx.sent[0][0]
    assert "张总" in text


# ---------------- ai_reply_to_source 双发 ----------------
def test_ai_reply_to_source_passthrough():
    reset()
    set_rules([base_rule(ai_reply_to_source=True)])
    consumed = handle_incoming("张三", "张三", "text", "friend", FakeMsg("你好"))
    assert consumed is False   # 放行给 AI
    assert _HUB.wx.sent == [("[来自 张三] 你好", "李四")]


# ---------------- 流水只展示最新 10 条 ----------------
def test_log_display_limit_10():
    reset()
    set_rules([base_rule()], rate={"window_sec": 10, "max_msgs": 100})
    for i in range(15):
        handle_incoming("张三", "张三", "text", "friend", FakeMsg("m%02d" % i))
    logs = forward_hub.get_log()
    assert len(logs) == 10
    assert logs[0]["summary"] == "m14"      # 倒序：最新在最前


# ---------------- all_chats 不含机器人 ----------------
def test_all_chats_excludes_robot():
    reset()
    set_rules([base_rule(party_a=["张三"], party_b=["李四"])])
    chats = all_chats()
    assert "张三" in chats and "李四" in chats
    assert ROBOT not in chats


# ---------------- 非法配置：甲乙重叠 / 含机器人 ----------------
def test_overlap_invalid():
    reset()
    ok, errors = save_rules({
        "enabled": True, "rate_limit": {"window_sec": 10, "max_msgs": 15},
        "rules": [base_rule(party_a=["张三"], party_b=["张三"])],
    })
    assert ok is True  # 保存成功但规则被标 invalid
    cfg = forward_hub.get_rules()
    r = cfg["rules"][0]
    assert r["enabled"] is False
    assert r["invalid"]


def test_self_target_rejected():
    reset()
    ok, errors = save_rules({
        "enabled": True, "rate_limit": {"window_sec": 10, "max_msgs": 15},
        "rules": [base_rule(party_b=ROBOT)],
    })
    assert ok is True
    cfg = forward_hub.get_rules()
    r = cfg["rules"][0]
    assert r["enabled"] is False
    assert r["invalid"]


# ---------------- 坏 JSON 不崩溃 ----------------
def test_broken_json_safe():
    reset()
    with open(_HUB._rules_path, "w", encoding="utf-8") as f:
        f.write("{这不是合法json")
    cfg = forward_hub.get_rules()
    assert isinstance(cfg, dict)
    assert "rules" in cfg


# ---------------- 语音/链接/合并归一化 ----------------
def test_normalize_voice_link_merge():
    reset()
    set_rules([base_rule()])  # 默认 media_mode=original
    _HUB.wx.sent.clear()
    _HUB.wx.files.clear()
    p = make_file("voice.silk")
    _HUB.wx._dl = p
    handle_incoming("张三", "张三", "voice", "friend", FakeMsg(""))
    # 语音：原文件单发，转写文本嵌进文件名（不再单独发文字气泡）
    assert len(_HUB.wx.files) == 1
    assert "[语音转写]" in os.path.basename(_HUB.wx.files[0][0])
    handle_incoming("张三", "张三", "link", "friend", FakeMsg(""))
    assert ("[卡片链接]" in _HUB.wx.sent[-1][0])
    merge = [["小王", "hi", "12:00"], ["小李", "yo", "12:01"]]
    handle_incoming("张三", "张三", "merge", "friend", FakeMsg("", merge=merge))
    assert ("[合并转发消息]" in _HUB.wx.sent[-1][0])


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    passed = 0
    for t in tests:
        try:
            t()
            print("PASS %s" % t.__name__)
            passed += 1
        except AssertionError as e:
            print("FAIL %s: %s" % (t.__name__, e))
        except Exception as e:  # noqa: BLE001
            print("ERR  %s: %s" % (t.__name__, e))
    print("\n%d/%d passed" % (passed, len(tests)))
