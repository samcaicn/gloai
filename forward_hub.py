# -*- coding: utf-8 -*-

# ***********************************************************************
# Copyright (C) 2025, iwyxdxl
# Licensed under GNU GPL-3.0 or higher, see the LICENSE file for details.
#
# This file is part of WeAuto.
# WeAuto is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# WeAuto is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with WeAuto.  If not, see <http://www.gnu.org/licenses/>.
# ***********************************************************************

"""固定转发引擎（零 bot / 微信依赖，可独立单测）—— 多对多 / 纯配置 / 对称双向。

需求：后台配置若干「转发对」，每对含 甲方(party_a) 与 乙方(party_b)：
      - 甲方、乙方都可以是【多个】好友或群（逗号分隔的会话名列表）。
      - 某条消息只要出现在「甲方任一会话」，就原样转发给「乙方全部会话」，反之亦然。
      - 路由【只看会话归属】，绝不解析消息内容（无 #姓名、无引用解析、无焦点记忆）。
      - 默认双向（bidirectional）；若设单向则只转 甲方->乙方。
      - 转发默认「只转发、不触发 AI 回复」（改 ai_reply_to_source 可让源侧也收到 AI 回复）。
      - 每条转发都带来源标签，如 [来自 张三] / [来自 客户群A-小王]，让接收方知道是谁说的。
      - 每个成员可配「我对他的称呼」：写作 "会话名=称呼" 或 {"name":.., "alias":..}，
        可以是职位头衔（张总、李经理）也可以是亲戚关系（二舅、三姨）。
      - 转发前重写消息里的称呼（见 _process_salutation）：
          ① 句首称呼语：认出是名单里谁 -> 换成「我对他的称呼」；认不出归属 -> 直接去掉
             （原称呼是发件人视角，转发给对端后大概率不适用，甚至张冠李戴）
          ② 正文其余位置的成员本名 -> 换成「我对他的称呼」
        识别 = 本地规则（姓氏+称谓/亲戚词/问候语，零成本）优先，
               本地没把握时可选交给 AI 判断（bot.py 注入 ai_resolver）。

设计要点（来自对 WeChatBot_WXAUTO_SE 实测代码的锚定）：
  * 不依赖 bot.py / wechat_compat：发送时由 bot 通过 attach(wx, logger) 注入 WeChat 实例，
    因此本模块可在无微信环境下被单元测试完整覆盖。
  * 消息归一化在本模块内部完成（文本/语音/链接/引用/合并/媒体占位），
    因为 bot 的消息回调对「群聊」会在归一化之前就提前 return，钩子拿不到可读文本——
    所以归一化交给引擎，钩子只需把原始 msg 对象传进来。
  * 发送一律走注入的 wx.SendMsg（即 bot 包装过的 _auto_add_sendmsg），以复用
    「发送即互动」登记 + bot 回声剔除，避免转发内容污染「风格学习」语料。
  * 规则 / 流水 两个 JSON 均用 .tmp + os.replace 原子写盘。

用法（bot.py）：
    import forward_hub
    forward_hub.attach(wx, logger, robot_name=ROBOT_WX_NAME)
    ...
    if forward_hub.handle_incoming(who, sender, msgtype, msgattr, msg):
        return  # 已被转发消费，跳过 AI 处理
"""

import os
import sys
import json
import re
import time
import copy
import threading
from collections import deque

# --------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------
HUB_VERSION = "2.3.0"
MAX_SINGLE_MSG_LEN = 800        # 单次发送最大长度，超出分段
INFLIGHT_TTL = 5               # 去重窗口（秒）
DEFAULT_RATE_WINDOW = 10        # 限流窗口（秒）
DEFAULT_RATE_MAX = 15          # 限流窗口内最大条数
LOG_DISPLAY_LIMIT = 10          # WebUI 展示的最近流水条数
DEFAULT_LABEL_FORMAT = "[来自 {src}{member}]"

# ---- 成员「称呼」与句首称呼语处理 ----
# 配置行 / 便捷写法： "会话名=称呼"     例：张三=张总
ALIAS_SPLIT_RE = re.compile(r'\s*[=＝:：]\s*')

SALUTATION_AI_TIMEOUT = 8       # AI 判断超时（秒），超时即采用本地规则结果
SALUTATION_MAX_HEAD = 12        # 句首称呼短语最大长度，超过就不当称呼处理
SALUTATION_MAX_AI_PREFIX = 15   # AI 改写后新增的前缀长度上限（超过视为胡改，丢弃）

# 职位 / 头衔 / 地位类称谓（长词优先匹配）
TITLE_WORDS = (
    "董事长", "总经理", "总工", "老总", "老大", "老板娘", "老板", "领导", "经理", "总监", "主管",
    "主任", "行长", "队长", "组长", "律师", "教授", "教练", "工程师", "医生", "护士", "老师",
    "师傅", "会计", "秘书", "局长", "处长", "科长", "校长", "院长", "总", "哥", "姐", "弟", "妹",
    "先生", "女士", "小姐", "同学", "同志", "前辈", "大师", "大神", "兄弟", "老兄", "老弟",
)
# 亲戚 / 亲昵类称谓
KIN_WORDS = (
    "爷爷", "奶奶", "外公", "外婆", "姥姥", "姥爷", "爸爸", "妈妈", "老爸", "老妈",
    "伯伯", "伯父", "叔叔", "阿姨", "姑姑", "姑妈", "舅舅", "舅妈", "姨妈", "姨父",
    "哥哥", "姐姐", "弟弟", "妹妹", "嫂子", "姐夫", "妹夫", "侄子", "外甥",
    "舅", "姨", "姑", "叔", "伯", "哥", "姐",
)
ALL_HONOR_WORDS = tuple(sorted(set(TITLE_WORDS + KIN_WORDS), key=len, reverse=True))

# 句首纯问候（不属于任何人，直接去掉）
GREET_WORDS = (
    "大家好", "各位好", "各位", "诸位", "早上好", "中午好", "下午好", "晚上好",
    "你好", "您好", "你好呀", "嗨", "哈喽", "哈罗", "喂", "在吗", "在么",
)
GREET_WORDS_L = tuple(sorted(GREET_WORDS, key=len, reverse=True))

# 句首切分：head（称呼短语） + sep（分隔符） + 剩余
HEAD_SPLIT_RE = re.compile(
    r'^\s*([^\s,，、:：!！?？~～;；。.]{1,%d})\s*([,，、:：!！?？~～;；\s]?)\s*' % SALUTATION_MAX_HEAD
)
# 疑似称呼（本地没把握、值得问 AI）：句首 2~4 字后紧跟分隔标点
# 限定 2~4 字：中文称呼（张总/李经理/二舅/王哥）基本都在这个长度，
# 放太宽会把「好的，」「收到，」这类普通开头也送去问 AI，白白烧 token。
SALUTATION_SUSPECT_RE = re.compile(r'^\s*[一-龥A-Za-z]{2,4}\s*[,，、:：!！]')
# 句首常见「非称呼」词：命中就不问 AI（否则每条寒暄都要跑一次模型）
NON_SALUTATION_HEADS = frozenset((
    "好的", "收到", "是的", "是", "嗯", "哦", "明白", "了解", "可以", "行", "成", "妥",
    "没问题", "没毛病", "谢谢", "感谢", "多谢", "抱歉", "不好意思", "对不起", "打扰",
    "对了", "另外", "还有", "那么", "所以", "但是", "不过", "其实", "而且", "然后",
    "现在", "今天", "明天", "昨天", "刚才", "等下", "稍等", "这个", "那个", "这样", "那样",
    "请问", "麻烦", "帮我", "我", "我们", "你", "你们", "他", "她", "它", "大家",
    "ok", "okay", "yes", "no", "hi", "hello", "thanks",
))

# ---- 媒体原文件转发 ----
MEDIA_DIR_NAME = "forward_media"    # 媒体缓存目录（相对 base）
MEDIA_KEEP_FILES = 200              # 缓存上限，超出按 mtime 清理最旧的
MEDIA_DOWNLOAD_TIMEOUT = 20         # 单个媒体提取超时（秒），超时即降级

IMG_EXT = ('.png', '.jpg', '.jpeg', '.gif', '.bmp')

MEDIA_PLACEHOLDER = {
    'image': '[图片]',
    'video': '[视频]',
    'file': '[文件]',
    'emotion': '[表情]',
    'voice': '[语音]',
}

# 可选监测内容类型（用于「指定类型」模式）
MONITOR_TYPES = ('text', 'image', 'video', 'file', 'voice', 'link', 'quote', 'merge', 'emotion')
MONITOR_TYPE_LABELS = {
    'text': '文字', 'image': '图片', 'video': '视频', 'file': '文件',
    'voice': '语音', 'link': '链接', 'quote': '引用', 'merge': '合并转发', 'emotion': '表情',
}

# Windows 文件名非法字符（用于清洗转写文本写入文件名）
_FILENAME_FORBIDDEN = '\\/:*?"<>|\r\n\t'


def _safe_filename(s, max_len=40):
    """把字符串清洗为可用的文件名片段（去除 Windows 非法字符与控制字符，并截断）。"""
    if not s:
        return ""
    out = []
    for ch in s:
        if ch in _FILENAME_FORBIDDEN or ord(ch) < 32:
            out.append('_')
        else:
            out.append(ch)
    s = ''.join(out).strip().strip('.')
    if len(s) > max_len:
        s = s[:max_len].rstrip('.')
    return s or "语音"

DEFAULT_CONFIG = {
    "enabled": False,
    "log_enabled": True,
    "max_log_entries": 500,
    "rate_limit": {"window_sec": DEFAULT_RATE_WINDOW, "max_msgs": DEFAULT_RATE_MAX},
    "rules": [],
}


def _base_dir():
    """规则/流水文件所在目录：冻结态用 exe 同目录，源码态用本模块目录。"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


# --------------------------------------------------------------------------
# 成员 + 「称呼」
#
# 成员 = {"name": 微信会话名, "alias": 【我（主人）】对他的称呼}
#   name  用于匹配监听到的 who、注册 AddListenChat —— 必须是微信里真实存在的会话名
#   alias 是主人视角的称呼：亲戚关系（二舅、三姨）、职位头衔（张总、李经理）、
#         尊称（王老师、陈哥）…… 由后台按成员逐个配置，可留空
#
# 兼容写法（输入侧三种都收，落盘统一为对象）：
#   "张三"                 -> {"name": "张三", "alias": ""}
#   "张三=张总"            -> {"name": "张三", "alias": "张总"}   （WebUI 每行一条）
#   {"name":..,"alias":..} -> 原样
# --------------------------------------------------------------------------
def _norm_members(raw):
    """归一化成员列表 -> [{'name': str, 'alias': str}]（去重保首个、剔空）。"""
    out = []
    seen = set()
    for item in (raw or []):
        name, alias = '', ''
        if isinstance(item, dict):
            name = str(item.get('name') or item.get('chat') or '').strip()
            alias = str(item.get('alias') or item.get('call') or '').strip()
        elif isinstance(item, str):
            parts = ALIAS_SPLIT_RE.split(item, 1)
            name = (parts[0] or '').strip()
            alias = (parts[1] if len(parts) > 1 else '').strip()
        else:
            continue
        if not name or name in seen:
            continue
        seen.add(name)
        out.append({"name": name, "alias": alias})
    return out


def _member_names(party):
    """成员列表 -> 会话名列表（匹配 who / 注册监听用）。"""
    return [m['name'] for m in (party or []) if isinstance(m, dict) and m.get('name')]


def _roster(rule):
    """规则内全部成员（甲+乙），用于称呼识别与替换。"""
    return _norm_members((rule.get('party_a') or []) + (rule.get('party_b') or []))


def _alias_map(roster):
    """成员表 -> {会话名: 我的称呼}（仅收录配了称呼的成员）。"""
    return {m['name']: m['alias'] for m in roster
            if isinstance(m, dict) and m.get('name') and m.get('alias')
            and m['alias'] != m['name']}


def _apply_alias(text, amap):
    """正文其余位置出现的成员本名 -> 我的称呼（长名优先，避免短名先替换吃掉长名）。"""
    if not text or not amap:
        return text
    for name in sorted(amap.keys(), key=len, reverse=True):
        alias = amap[name]
        if alias and name in text:
            text = text.replace(name, alias)
    return text


# --------------------------------------------------------------------------
# 句首「称呼语」处理：识别归属 -> 替换为「我的称呼」；识别不出 -> 去掉
#
# 为什么要处理：消息是发件人写给某个人的，开头的称呼（"张总，"、"李经理："、
# "二舅 你好"）是发件人视角；转发给对端后，这个称呼大概率不适用甚至张冠李戴。
# 所以统一改写成「主人对该成员的称呼」，认不出是谁就直接去掉。
# --------------------------------------------------------------------------
def _head_split(text):
    """切出句首 (head, sep, rest)；没有分隔符就不认为是称呼语，返回 None。"""
    m = HEAD_SPLIT_RE.match(text or '')
    if not m:
        return None
    head, sep = m.group(1) or '', m.group(2) or ''
    if not head or not sep:
        return None
    return head, sep, text[m.end():]


def _strip_honor(head):
    """去掉结尾的称谓/亲戚词，返回核心字（"张总"->"张"）；本身不是称谓则返回 None。"""
    for w in ALL_HONOR_WORDS:
        if len(head) > len(w) and head.endswith(w):
            return head[:-len(w)]
    return None


def _find_member(key, roster):
    """按「核心字」找成员：先精确（姓名/称呼），再前缀/包含（姓氏匹配）。"""
    if not key:
        return None
    for m in roster:
        if m.get('name') == key or (m.get('alias') and m['alias'] == key):
            return m
    for m in roster:
        n = m.get('name') or ''
        if not n:
            continue
        if len(key) >= 1 and (n.startswith(key) or (len(key) >= 2 and key in n)):
            return m
    return None


def _salutation_local(text, roster, self_names=()):
    """本地规则处理句首称呼。

    self_names: 来源会话名 / 群内发言人 —— 命中它说明是「自称」而非称呼，保持原样。

    返回 (新文本, 已确定)：
      已确定=True  -> 本地有把握（命中成员 / 泛称 / 问候语 / 自称），不必再问 AI
      已确定=False -> 本地没把握，交给 AI
    """
    hs = _head_split(text)
    if not hs:
        return text, False
    head, sep, rest = hs
    if not rest.strip():
        return text, True                     # 整句只有称呼，别把消息删空

    # 1) 问候语（不属于任何人）-> 去掉
    for g in GREET_WORDS_L:
        if head == g:
            return rest, True
    # 2) head 本身就是某成员的姓名或「我的称呼」-> 命中该成员
    mem = None
    for m in roster:
        if m.get('name') == head or (m.get('alias') and m['alias'] == head):
            mem = m
            break
    # 3) head 是「姓氏 + 称谓」/「排行 + 亲戚称谓」-> 去掉称谓词后回查成员
    if mem is None:
        core = _strip_honor(head)
        if core:
            mem = _find_member(core, roster)
            if mem is None:
                return rest, True             # 认得出是称谓但不知是谁 -> 去掉
    if mem is None:
        return text, False                    # 本地没把握
    if mem.get('name') in self_names:
        return text, True                     # 自称（来源本人），不是称呼，不动
    alias = (mem.get('alias') or '').strip()
    return (f"{alias}{sep}{rest}" if alias else rest), True


def _salutation_ai(text, roster, resolver, source="", timeout=SALUTATION_AI_TIMEOUT):
    """用 AI 判断句首称呼归属并改写；失败/超时/改写越界一律返回 None（调用方保守处理）。"""
    if resolver is None:
        return None

    def _call():
        # 兼容两种回调签名：(text, roster) 与 (text, roster, source)
        try:
            return resolver(text, roster, source)
        except TypeError:
            return resolver(text, roster)

    try:
        ai = _call_with_timeout(_call, timeout=timeout)
    except Exception:  # noqa: BLE001
        return None
    if not isinstance(ai, str):
        return None
    ai = ai.strip().strip('"').strip('“').strip('”').strip('`').strip()
    if not ai or ai == text:
        return None
    # 安全校验：AI 只能动【开头】，尾部必须与原文一致，且新增前缀不能过长
    same = 0
    for a, b in zip(reversed(ai), reversed(text)):
        if a != b:
            break
        same += 1
    if same < len(text) * 0.5 or same < 1:
        return None
    if len(ai) - same > SALUTATION_MAX_AI_PREFIX:
        return None
    return ai


def _worth_asking_ai(text):
    """形态上值不值得为它花一次 AI 调用（auto 模式专用，省 token）。"""
    if not SALUTATION_SUSPECT_RE.match(text):
        return False
    hs = _head_split(text)
    if not hs:
        return False
    head = hs[0].strip()
    return head.lower() not in NON_SALUTATION_HEADS


def _process_salutation(text, roster, mode="auto", resolver=None,
                        timeout=SALUTATION_AI_TIMEOUT, self_names=()):
    """处理句首称呼语。

    mode:
      off  - 不处理
      rule - 只用本地规则（零成本、零延迟）
      ai   - 只用 AI 判断（无 resolver 时降级为本地规则）
      auto - 本地有把握就用本地结果；没把握且形态像称呼时才问 AI（默认，省 token）
    """
    if not text or not isinstance(text, str) or mode == "off":
        return text
    ruled, sure = _salutation_local(text, roster, self_names=self_names)
    if mode == "rule" or (mode == "auto" and sure):
        return ruled
    if resolver is None:
        return ruled
    if mode == "auto" and not _worth_asking_ai(text):
        return ruled                          # 形态上不像称呼，不问 AI
    src = ''
    if self_names:
        src = next((n for n in self_names if n), '') or ''
    return _salutation_ai(text, roster, resolver, source=src, timeout=timeout) or ruled


def _call_with_timeout(fn, *args, **kw):
    """在守护线程里调用可能阻塞的回调（AI 请求），超时即放弃。绝不卡住消息回调。"""
    timeout = kw.pop('timeout', SALUTATION_AI_TIMEOUT)
    box = {}

    def _run():
        try:
            box["v"] = fn(*args)
        except Exception as e:  # noqa: BLE001
            box["e"] = e

    t = threading.Thread(target=_run, daemon=True)
    t.start()
    t.join(timeout)
    if t.is_alive():
        return None
    return box.get("v")


# ---- 供 bot.py 注入的 AI 判断 prompt ----
SALUTATION_AI_PROMPT = """你是消息转发助手。下面是一条微信消息，它将被原样转发给其他人。
请判断消息【开头】是否含有对某个人的称呼语（例如「张总，」「李经理：」「二舅 你好」「王哥，」）。

已知成员名单（微信备注名 -> 主人对他的称呼）：
{roster}

处理规则：
1. 若开头称呼语指向名单中某个成员：把它替换为该成员对应的「主人对他的称呼」（保留原来的分隔符）。
2. 若该成员没有对应称呼：直接删掉称呼语。
3. 若开头称呼语不指向名单中任何人（泛称，如「老师，」「各位，」「你好，」）：直接删掉称呼语。
4. 若开头不是称呼语：原样输出，一个字都不要改。
5. 严禁改写、增删、润色称呼以外的任何文字与标点，严禁添加解释、引号、前缀。
{source_hint}
只输出处理后的消息文本本身。

原消息：
\"\"\"
{text}
\"\"\""""


def build_salutation_prompt(text, roster, source=None):
    """生成 AI 判断用的 prompt（roster / source 为空时也能安全生成）。"""
    lines = []
    for m in (roster or []):
        n = (m.get('name') or '').strip()
        a = (m.get('alias') or '').strip()
        if not n:
            continue
        lines.append(f"- {n} -> {a}" if a else f"- {n} -> （无称呼）")
    hint = ""
    if source:
        hint = f"注意：消息来自「{source}」，不要把来源本人当成被称呼的对象（自称不是称呼）。"
    body = "\n".join(lines) or "（名单为空）"
    try:
        return SALUTATION_AI_PROMPT.format(roster=body, text=text, source_hint=hint)
    except Exception:  # noqa: BLE001
        return (SALUTATION_AI_PROMPT
                .replace("{roster}", body)
                .replace("{source_hint}", hint)
                .replace("{text}", text))


class _ForwardHub:
    def __init__(self):
        self.wx = None
        self.logger = None
        self.robot_name = None
        self.ai_resolver = None   # 可选：AI 判断称呼归属 (text, roster) -> str|None
        self._base = _base_dir()
        self._rules_path = os.path.join(self._base, "forward_rules.json")
        self._log_path = os.path.join(self._base, "forward_log.json")
        self._rules = None
        self._rules_mtime = -1
        self._rules_load_time = 0
        self._lock = threading.RLock()
        self._inflight = {}     # hash -> 过期时间戳
        self._buckets = {}      # (src, dst) -> deque(时间戳)
        self._sal_cache = {}    # 称呼处理结果缓存 -> (过期时间戳, 结果)

    # ----- 路径常量（供 WebUI 进程与 bot 进程共享同一组文件）-----
    @property
    def rules_path(self):
        return self._rules_path

    @property
    def log_path(self):
        return self._log_path

    # ----- 注入与初始化 -----
    def attach(self, wx=None, logger=None, robot_name=None, ai_resolver=None):
        if wx is not None:
            self.wx = wx
        if logger is not None:
            self.logger = logger
        if robot_name is not None:
            self.robot_name = robot_name
        if ai_resolver is not None:
            self.ai_resolver = ai_resolver
        self._ensure_files()
        self._load_rules(force=True)
        self._info(f"固定转发引擎已挂载（robot={self.robot_name!r}，规则文件={self._rules_path}）")

    def _ensure_files(self):
        for p in (self._rules_path, self._log_path):
            if not os.path.exists(p):
                try:
                    with open(p, 'w', encoding='utf-8') as f:
                        if p == self._rules_path:
                            json.dump(copy.deepcopy(DEFAULT_CONFIG), f, ensure_ascii=False, indent=2)
                        else:
                            json.dump([], f, ensure_ascii=False, indent=2)
                except Exception as e:  # noqa: BLE001
                    self._error(f"固定转发：创建文件 {p} 失败: {e}")

    # ----- 日志 -----
    def _info(self, msg):
        if self.logger:
            try:
                self.logger.info(msg)
            except Exception:
                pass

    def _warn(self, msg):
        if self.logger:
            try:
                self.logger.warning(msg)
            except Exception:
                pass

    def _error(self, msg):
        if self.logger:
            try:
                self.logger.error(msg)
            except Exception:
                pass

    # ----- 原子写 -----
    def _atomic_write(self, path, data):
        tmp = path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)

    # ----- 规则加载（按 mtime 热重载）-----
    def _load_rules(self, force=False):
        with self._lock:
            try:
                mtime = os.path.getmtime(self._rules_path)
            except OSError:
                mtime = -1
            now = time.time()
            if (not force) and self._rules is not None and mtime == self._rules_mtime \
                    and (now - self._rules_load_time) < 60:
                return self._rules
            try:
                with open(self._rules_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                if not isinstance(data, dict):
                    raise ValueError("根必须是对象")
                data = self._normalize_config(data)
                self._rules = data
                self._rules_mtime = mtime
                self._rules_load_time = now
            except (OSError, ValueError) as e:
                self._error(f"固定转发：规则加载失败（沿用上一版/默认）: {e}")
                if self._rules is None:
                    self._rules = copy.deepcopy(DEFAULT_CONFIG)
            return self._rules

    def _normalize_config(self, data):
        """补全缺省字段、剔除非法规则（含自转发/空方/甲乙重叠），不抛异常。"""
        cfg = copy.deepcopy(DEFAULT_CONFIG)
        cfg.update({k: v for k, v in data.items() if k in DEFAULT_CONFIG})
        if not isinstance(cfg.get('rate_limit'), dict):
            cfg['rate_limit'] = {"window_sec": DEFAULT_RATE_WINDOW, "max_msgs": DEFAULT_RATE_MAX}
        rl = cfg['rate_limit']
        cfg['rate_limit'] = {
            "window_sec": int(rl.get('window_sec', DEFAULT_RATE_WINDOW)),
            "max_msgs": int(rl.get('max_msgs', DEFAULT_RATE_MAX)),
        }
        cfg['rules'] = []
        for i, r in enumerate(data.get('rules', []) or []):
            if not isinstance(r, dict):
                continue
            rule = self._normalize_rule(r, idx=i)
            if rule is not None:
                cfg['rules'].append(rule)
        return cfg

    def _normalize_rule(self, r, idx=0):
        rid = r.get('id') or f"r{idx + 1}"
        name = r.get('name') or rid
        party_a = _norm_members(r.get('party_a'))
        party_b = _norm_members(r.get('party_b'))
        names_a = _member_names(party_a)
        names_b = _member_names(party_b)
        enabled = bool(r.get('enabled', True))
        invalid = []
        if not party_a:
            invalid.append("甲方(party_a)为空")
        if not party_b:
            invalid.append("乙方(party_b)为空")
        if self.robot_name and (self.robot_name in names_a or self.robot_name in names_b):
            invalid.append(f"不能把机器人自己({self.robot_name})设为甲方或乙方")
        overlap = set(names_a) & set(names_b)
        if overlap:
            invalid.append("甲方与乙方重叠: " + "、".join(sorted(overlap)))
        rule = {
            "id": rid,
            "name": name,
            "enabled": enabled and not invalid,
            "invalid": invalid,
            "party_a": party_a,
            "party_b": party_b,
            "bidirectional": bool(r.get('bidirectional', True)),
            "source_label": bool(r.get('source_label', True)),
            "label_format": r.get('label_format') or DEFAULT_LABEL_FORMAT,
            # 句首称呼语处理：off=不处理 | rule=只本地规则 | auto=规则优先/疑似才问 AI | ai=只问 AI
            "salutation_mode": r.get('salutation_mode') or "auto",
            # 正文其余位置出现的成员本名 -> 替换为「我对他的称呼」
            "replace_alias": bool(r.get('replace_alias', True)),
            "ai_reply_to_source": bool(r.get('ai_reply_to_source', False)),
            # 监测内容：all=全部内容（默认）| types=仅指定类型 | keywords=仅含关键字的文本
            "monitor_content": r.get('monitor_content') or "all",
            "monitor_types": [t for t in (r.get('monitor_types') or []) if isinstance(t, str)],
            "monitor_keywords": [k for k in (r.get('monitor_keywords') or []) if isinstance(k, str) and k.strip()],
            # original=优先转发原文件（图片/视频/文件/语音），失败自动降级占位；
            # placeholder=只发 [图片] 占位；skip=丢弃
            "media_mode": r.get('media_mode') or "original",
            "respect_quiet_time": bool(r.get('respect_quiet_time', False)),
        }
        return rule

    def get_rules(self):
        return self._load_rules()

    def save_rules(self, data):
        """由 WebUI 调用：校验后原子写盘。返回 (ok, errors)。"""
        errors = []
        try:
            if not isinstance(data, dict):
                errors.append("根必须是对象")
            else:
                rl = data.get('rate_limit') or {}
                try:
                    int(rl.get('window_sec', DEFAULT_RATE_WINDOW))
                    int(rl.get('max_msgs', DEFAULT_RATE_MAX))
                except (TypeError, ValueError):
                    errors.append("rate_limit 数值非法")
                # 注：甲方/乙方为空或含机器人自己、甲乙重叠等情况不硬性拒绝，
                # 仅标记为 invalid（见 _normalize_rule），以便配置可被保存并在 UI 中以红色徽标提示。
            if errors:
                return False, errors
            norm = self._normalize_config(data)
            self._atomic_write(self._rules_path, norm)
            with self._lock:
                self._rules = norm
                try:
                    self._rules_mtime = os.path.getmtime(self._rules_path)
                except OSError:
                    pass
                self._rules_load_time = time.time()
            return True, []
        except Exception as e:  # noqa: BLE001
            return False, [f"写盘异常: {e}"]

    # ----- 流水 -----
    def _load_log(self):
        try:
            with open(self._log_path, 'r', encoding='utf-8') as f:
                self._log = json.load(f) or []
        except (OSError, ValueError):
            self._log = []

    def _append_log(self, entry):
        cfg = self._load_rules()
        if not cfg.get('log_enabled', True):
            return
        self._load_log()
        self._log.append(entry)
        max_n = int(cfg.get('max_log_entries', 500))
        if len(self._log) > max_n:
            self._log = self._log[-max_n:]
        try:
            self._atomic_write(self._log_path, self._log)
        except Exception as e:  # noqa: BLE001
            self._error(f"固定转发：流水写盘失败: {e}")

    def get_log(self, limit=LOG_DISPLAY_LIMIT):
        self._load_log()
        return self._log[-limit:][::-1]

    # ----- 监听名册（供 main() 注册监听）-----
    def all_chats(self):
        cfg = self._load_rules()
        chats = set()
        for r in cfg.get('rules', []):
            if not r.get('enabled'):
                continue
            for s in _member_names(r.get('party_a')) + _member_names(r.get('party_b')):
                if s and s != self.robot_name:
                    chats.add(s)
        return sorted(chats)

    # ----- 去重 / 限流 -----
    def _is_duplicate(self, source, target, content):
        h = f"F|{source}|{target}|{content}"
        now = time.time()
        expired = [k for k, v in self._inflight.items() if v < now]
        for k in expired:
            self._inflight.pop(k, None)
        if h in self._inflight:
            return True
        self._inflight[h] = now + INFLIGHT_TTL
        return False

    def _rate_limited(self, source, target):
        cfg = self._load_rules()
        rl = cfg.get('rate_limit', {})
        window = int(rl.get('window_sec', DEFAULT_RATE_WINDOW))
        max_n = int(rl.get('max_msgs', DEFAULT_RATE_MAX))
        key = (source, target)
        now = time.time()
        dq = self._buckets.get(key)
        if dq is None:
            dq = deque()
            self._buckets[key] = dq
        while dq and dq[0] <= now - window:
            dq.popleft()
        if len(dq) >= max_n:
            return True
        dq.append(now)
        return False

    # ----- 消息归一化 -----
    def _normalize(self, msgtype, msg, is_group, sender):
        """返回可读文本；媒体类型返回 None（由调用方按 media_mode 处理）。"""
        try:
            if msgtype == 'voice':
                # 语音作为媒体处理：原文件 + 转写文本在 _try_send_original 内
                # 合并为「单文件发送」（转写文本写入文件名），不再单独发文字气泡（避免双发）。
                return None
            if msgtype == 'link':
                url = ''
                try:
                    url = msg.get_url()
                except Exception:
                    url = ''
                return (f"[卡片链接]: {url}" if url else "[卡片链接]")
            if msgtype == 'quote':
                q = None
                try:
                    q = msg.quote_content
                except Exception:
                    q = None
                body = (msg.content if isinstance(msg.content, str) else '') or ''
                if q:
                    return f"[引用<{q}>消息]: {body}"
                return body
            if msgtype == 'merge':
                items = None
                try:
                    items = msg.get_messages()
                except Exception:
                    items = None
                if isinstance(items, list):
                    lines = []
                    for it in items:
                        if isinstance(it, list) and len(it) == 3:
                            s, c, t = it
                            if hasattr(c, 'suffix') and str(c.suffix).lower() in IMG_EXT:
                                lines.append(f"[{t}] {s}: [图片]")
                            else:
                                lines.append(f"[{t}] {s}: {c}")
                        else:
                            lines.append(str(it))
                    return "[合并转发消息]:\n" + "\n".join(lines)
                return "[合并转发消息]"
            if msgtype in ('image', 'video', 'file', 'emotion'):
                return None  # 媒体：交由调用方处理
            # text / 其它
            c = msg.content if hasattr(msg, 'content') else ''
            return c if isinstance(c, str) else str(c)
        except Exception as e:  # noqa: BLE001
            self._error(f"固定转发：归一化消息异常: {e}")
            c = getattr(msg, 'content', '')
            return c if isinstance(c, str) else str(c)

    # ----- 发送 -----
    def _send(self, text, who):
        if self.wx is None:
            raise RuntimeError("转发引擎未 attach 到微信实例")
        chunks = self._chunk(text)
        last_exc = None
        for ch in chunks:
            ok = False
            for attempt in range(3):  # 重试 2 次
                try:
                    self.wx.SendMsg(ch, who)
                    ok = True
                    break
                except Exception as e:  # noqa: BLE001
                    last_exc = e
                    self._warn(f"固定转发：发送失败（第{attempt + 1}次，目标={who}）: {e}")
                    time.sleep(0.3 * (attempt + 1))
            if not ok:
                raise last_exc or RuntimeError("发送失败")
            time.sleep(0.15)

    def _chunk(self, text):
        text = text or ''
        if len(text) <= MAX_SINGLE_MSG_LEN:
            return [text]
        out = []
        for i in range(0, len(text), MAX_SINGLE_MSG_LEN):
            out.append(text[i:i + MAX_SINGLE_MSG_LEN])
        return out

    # ----- 媒体原文件转发 -----
    def _media_dir(self):
        """媒体缓存目录（原文件转发前先落盘到这里，再 SendFiles 给目标）。"""
        d = os.path.join(self._base, MEDIA_DIR_NAME)
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:  # noqa: BLE001
            pass
        return d

    def _cleanup_media(self, keep=MEDIA_KEEP_FILES):
        """清理媒体缓存，避免长期运行占满磁盘（按 mtime 保留最新的 keep 个）。"""
        try:
            d = self._media_dir()
            paths = [os.path.join(d, n) for n in os.listdir(d)]
            paths = [p for p in paths if os.path.isfile(p)]
            if len(paths) <= keep:
                return
            paths.sort(key=lambda p: os.path.getmtime(p))
            for p in paths[:-keep]:
                try:
                    os.remove(p)
                except Exception:  # noqa: BLE001
                    pass
        except Exception as e:  # noqa: BLE001
            self._warn(f"固定转发：媒体缓存清理失败: {e}")

    def _send_file(self, path, msgtype, who):
        """发送本地文件：图片/表情优先 SendImage，其余（含回退）走 SendFiles。"""
        if msgtype in ('image', 'emotion'):
            fn = getattr(self.wx, 'SendImage', None)
            if fn is not None:
                try:
                    fn(path, who)
                    return
                except Exception as e:  # noqa: BLE001
                    self._warn(f"固定转发：SendImage 失败，回退 SendFiles: {e}")
        fn = getattr(self.wx, 'SendFiles', None)
        if fn is None:
            raise RuntimeError("当前微信实例不支持文件发送（无 SendFiles）")
        fn(path, who)

    def _try_send_original(self, msgtype, msg, dests, who, kind, rule, label=None):
        """尽力转发媒体原文件（图片/视频/文件/语音）。

        返回 True 表示至少对一个目标发送成功；否则调用方应降级为占位文本。
        依赖注入的 wx 提供 DownloadMedia（wechat_compat.WeChat 已实现）。
        """
        dl = getattr(self.wx, 'DownloadMedia', None)
        if dl is None:
            return False
        try:
            path = dl(msg, save_dir=self._media_dir(), timeout=MEDIA_DOWNLOAD_TIMEOUT)
        except Exception as e:  # noqa: BLE001
            self._error(f"固定转发：媒体提取异常: {e}")
            return False
        if not path or not os.path.exists(path):
            self._info("固定转发：媒体原文件不可用，降级为占位文本")
            return False

        # 语音：把转写文本写入文件名，使「原文件 + 转写文本」合并为单文件发送（非双发）
        if msgtype == 'voice':
            vt = ''
            try:
                vt = msg.to_text()
            except Exception:  # noqa: BLE001
                vt = ''
            if vt:
                _, ext = os.path.splitext(path)
                frag = _safe_filename(vt)
                new_name = f"语音_{frag}{ext}" if frag else f"语音{ext}"
                new_path = os.path.join(os.path.dirname(path), new_name)
                # 避免与目标文件名冲突（同一目录可能已有同名文件）
                if os.path.abspath(new_path) != os.path.abspath(path) and os.path.exists(new_path):
                    stem, ext2 = os.path.splitext(new_name)
                    i = 1
                    while os.path.exists(new_path):
                        new_path = os.path.join(os.path.dirname(path), f"{stem}_{i}{ext2}")
                        i += 1
                try:
                    if os.path.abspath(new_path) != os.path.abspath(path):
                        os.rename(path, new_path)
                        path = new_path
                except OSError:
                    pass

        key = f"[media]{msgtype}:{getattr(msg, 'local_id', '?')}"
        fname = os.path.basename(path)
        sent_any = False
        for dst in dests:
            if dst == who:
                continue
            if self._is_duplicate(who, dst, key):
                self._info(f"固定转发：媒体 {who}->{dst} 去重丢弃")
                continue
            if self._rate_limited(who, dst):
                self._warn(f"固定转发：媒体 {who}->{dst} 触发限流，丢弃")
                self._append_log(self._mk_entry(kind, who, dst, msgtype, fname, 'error'))
                continue
            try:
                if label:
                    self._send(label, dst)
                self._send_file(path, msgtype, dst)
                self._append_log(self._mk_entry(kind, who, dst, msgtype, fname, 'ok'))
                self._info(f"固定转发：媒体原文件 {who} -> {dst}（{fname}）成功")
                sent_any = True
            except Exception as e:  # noqa: BLE001
                self._error(f"固定转发：媒体 {who}->{dst} 发送失败: {e}")
                self._append_log(self._mk_entry(kind, who, dst, msgtype, fname, 'error'))
        if sent_any:
            self._cleanup_media()
        return sent_any

    # ----- 主入口：多对多对称双向 -----
    def handle_incoming(self, who, sender, msgtype, msgattr, msg):
        """返回 True 表示消息已被转发消费（调用方应跳过 AI 处理）；False 表示放行原流程。

        路由完全由「会话归属」决定，不解析消息内容：
          - who 命中某规则的 甲方  -> 转发给该规则的 乙方全体
          - who 命中某规则的 乙方  -> 转发给该规则的 甲方全体（双向时）
          - 同一会话同时出现在甲、乙 -> 无法确定方向，跳过该规则（防回环/重复）
        """
        if msgattr in ('self', 'tickle'):
            return False  # 自己的消息绝不过问（防回环）；拍一拍不转发
        cfg = self._load_rules()
        if not cfg.get('enabled', False):
            return False

        is_group = bool(who and '@chatroom' in who) or msgattr == 'group'

        matched = []
        for rule in cfg.get('rules', []):
            if not rule.get('enabled'):
                continue
            direction = self._direction(who, rule)
            if direction is None:
                continue
            kind, dests = direction
            # 单向模式：只转 甲方->乙方，乙方->甲方 忽略
            if kind == 'b_to_a' and not rule.get('bidirectional', True):
                continue
            matched.append((rule, kind, dests))

        if not matched:
            return False

        forwarded_any = False
        ai_reply = False
        raw_content = self._normalize(msgtype, msg, is_group, sender)
        for rule, kind, dests in matched:
            if self._deliver(rule, who, sender, msgtype, msg, is_group, dests, kind, raw_content):
                forwarded_any = True
                # 仅当本条确实命中监测/已转发，才允许 AI 回复源（使用 prompt）
                if rule.get('ai_reply_to_source'):
                    ai_reply = True

        if not forwarded_any:
            return False
        # ai_reply_to_source=True 时，仍放行给 AI 处理源侧
        return not ai_reply

    def _direction(self, who, rule):
        """返回 (kind, dests)。kind∈{'a_to_b','b_to_a'}，dests 为对端会话名列表（已剔除自身/机器人）。"""
        a = set(_member_names(rule.get('party_a')))
        b = set(_member_names(rule.get('party_b')))
        if self.robot_name:
            a.discard(self.robot_name)
            b.discard(self.robot_name)
        in_a = who in a
        in_b = who in b
        if in_a and in_b:
            return None  # 甲、乙重叠，方向不确定，跳过
        if in_a:
            return ('a_to_b', [d for d in b if d != who])
        if in_b:
            return ('b_to_a', [d for d in a if d != who])
        return None

    def _monitor_match(self, rule, msgtype, text):
        """监测内容过滤：返回 True 表示本条应转发/触发 AI；False 表示不匹配（不回应）。

        monitor_content:
          - 'all'      （默认）全部内容都转发
          - 'types'    仅当 msgtype 在 monitor_types 列表内才转发
          - 'keywords' 仅当文本（含语音转写/链接/引用正文）包含任一关键字才转发
                        未填关键字视为全部放行；媒体无文本则无法命中关键字
        """
        mode = rule.get('monitor_content') or 'all'
        if mode == 'all':
            return True
        if mode == 'types':
            types = [t for t in (rule.get('monitor_types') or []) if isinstance(t, str)]
            return msgtype in types
        if mode == 'keywords':
            kws = [k for k in (rule.get('monitor_keywords') or []) if isinstance(k, str) and k.strip()]
            if not kws:
                return True  # 未配置关键字 -> 等同全部
            hay = text if isinstance(text, str) else ''
            return any(k in hay for k in kws)
        return True

    def _process_salutation_cached(self, text, roster, mode, self_names=(), ttl=60):
        """带缓存的称呼处理：同一正文 + 同一名单在 ttl 秒内复用结果，避免重复问 AI。"""
        key = (mode, text,
               tuple((m.get('name'), m.get('alias')) for m in roster),
               tuple(n or '' for n in self_names))
        now = time.time()
        hit = self._sal_cache.get(key)
        if hit and hit[0] > now:
            return hit[1]
        out = _process_salutation(text, roster, mode, resolver=self.ai_resolver,
                                  timeout=SALUTATION_AI_TIMEOUT, self_names=self_names)
        if len(self._sal_cache) > 200:
            self._sal_cache.clear()
        self._sal_cache[key] = (now + ttl, out)
        return out

    # ----- 投递到对端全体 -----
    def _deliver(self, rule, who, sender, msgtype, msg, is_group, dests, kind, raw_content=None):
        """已匹配的会话 -> 依次投递给 dests。返回 True 表示已处理（抑制 AI）。"""
        if raw_content is None:
            raw_content = self._normalize(msgtype, msg, is_group, sender)
        content = raw_content
        mode = rule.get('media_mode', 'original')

        # 监测内容过滤：默认全部；指定类型 / 关键字 不匹配则本条「不回应」（不转发、不触发 AI）
        if not self._monitor_match(rule, msgtype, raw_content):
            self._info(f"固定转发：{who} 未命中监测内容（{rule.get('monitor_content')}），本条不转发")
            return False

        # 成员表 + 称呼映射：{会话名/成员名 -> 我对他的称呼}
        roster = _roster(rule)
        amap = _alias_map(roster)
        do_replace = bool(rule.get('replace_alias', True))

        # ① 句首称呼语：识别是谁 -> 换成「我的称呼」；认不出归属 -> 去掉
        sal_mode = rule.get('salutation_mode') or 'auto'
        if isinstance(content, str) and sal_mode != 'off':
            content = self._process_salutation_cached(
                content, roster, sal_mode, self_names=(who, sender))
        # ② 正文其余位置出现的成员本名 -> 「我的称呼」
        if do_replace and isinstance(content, str):
            content = _apply_alias(content, amap)

        # 来源标签（文本与媒体共用）：来源 / 群成员都用各自的称呼
        label = ''
        if rule.get('source_label', True):
            src_disp = (amap.get(who) or who) if do_replace else who
            member = ''
            if is_group and sender:
                mem_disp = (amap.get(sender) or sender) if do_replace else sender
                member = f"-{mem_disp}"
            try:
                label = rule.get('label_format', DEFAULT_LABEL_FORMAT).format(src=src_disp, member=member)
            except (KeyError, IndexError):
                label = f"[来自 {src_disp}{member}]"

        # 纯媒体（图片/视频/文件/表情）：优先转发原文件
        if content is None:
            if mode == 'skip':
                self._append_log(self._mk_entry(kind, who, ','.join(dests) or '(空)', msgtype, '[媒体已跳过]', 'skip'))
                return True  # 丢弃，且抑制 AI
            if mode == 'original' and self._try_send_original(
                    msgtype, msg, dests, who, kind, rule, label=label):
                return True  # 原文件已转发成功，不再发占位
            content = MEDIA_PLACEHOLDER.get(msgtype, '[媒体]')  # 原文件不可用 -> 降级占位

        if not content:
            return False

        if not content:
            return False

        text = f"{label} {content}" if label else content

        for dst in dests:
            if dst == who:
                continue
            if self._is_duplicate(who, dst, content):
                self._info(f"固定转发：{who}->{dst} 去重丢弃（5s 内重复）")
                continue
            if self._rate_limited(who, dst):
                self._warn(f"固定转发：{who}->{dst} 触发限流，丢弃并告警")
                self._append_log(self._mk_entry(kind, who, dst, msgtype, content[:40], 'error'))
                continue
            try:
                self._send(text, dst)
                self._append_log(self._mk_entry(kind, who, dst, msgtype, content[:40], 'ok'))
                self._info(f"固定转发：{who} -> {dst} 成功")
            except Exception as e:  # noqa: BLE001
                self._error(f"固定转发：{who}->{dst} 发送失败: {e}")
                self._append_log(self._mk_entry(kind, who, dst, msgtype, content[:40], 'error'))

        # 已处理该会话的消息（无论是否真的发出），抑制 AI 在源侧抢答
        return True

    # ----- 流水条目 -----
    def _mk_entry(self, direction, src, dst, msgtype, summary, result):
        return {
            "ts": time.time(),
            "time": time.strftime('%Y-%m-%d %H:%M:%S'),
            "direction": direction,   # a_to_b=甲方->乙方, b_to_a=乙方->甲方
            "src": src,
            "dst": dst,
            "msgtype": msgtype,
            "summary": summary,
            "result": result,         # ok | skip | error
        }


# --------------------------------------------------------------------------
# 模块级单例 + 便捷函数
# --------------------------------------------------------------------------
_HUB = _ForwardHub()


def attach(wx=None, logger=None, robot_name=None, ai_resolver=None):
    _HUB.attach(wx=wx, logger=logger, robot_name=robot_name, ai_resolver=ai_resolver)


def handle_incoming(who, sender, msgtype, msgattr, msg):
    return _HUB.handle_incoming(who, sender, msgtype, msgattr, msg)


def all_chats():
    return _HUB.all_chats()


def get_rules():
    return _HUB.get_rules()


def save_rules(data):
    return _HUB.save_rules(data)


def get_log(limit=LOG_DISPLAY_LIMIT):
    return _HUB.get_log(limit=limit)


def get_paths():
    return {
        "rules": _HUB.rules_path,
        "log": _HUB.log_path,
    }


def ensure_files():
    _HUB._ensure_files()
    _HUB._load_rules(force=True)
