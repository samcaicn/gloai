# -*- coding: utf-8 -*-
"""主人说话风格学习 / 模仿。

思路
----
只统计「主人亲手发的消息」（chat_history 中 source='owner'），
排除 bot 代发与收到的消息，这样学到的才是真人风格而不是 AI 腔。

全部分析**纯本地完成**，不调用任何 API：
    * 不依赖 key（项目里 key 可能是占位值，联网分析会直接失败）
    * 离线可跑，结果可解释、可人工微调

产出两份东西：
    1. 结构化特征（features）—— 给 UI 展示图表/指标
    2. 自然语言风格画像（profile_text）—— 直接注入 system prompt，让 AI 模仿

对外接口
--------
    analyze(limit=2000)      -> dict  分析并落盘
    load_profile()           -> dict  读取已保存画像
    get_style_injection()    -> str   注入提示词的文本（未启用返回空串）
    set_enabled(bool)                 开关
"""

import json
import os
import re
import time
import logging
import threading
from collections import Counter

logger = logging.getLogger(__name__)

_HERE = os.path.dirname(os.path.abspath(__file__))
PROFILE_PATH = os.path.join(_HERE, 'style_profile.json')
SETTINGS_PATH = os.path.join(_HERE, 'style_settings.json')

_lock = threading.Lock()

# 最少需要多少条主人才发言才谈得上"风格"
MIN_SAMPLES = 10

DEFAULT_SETTINGS = {
    "enabled": False,      # 是否把风格画像注入提示词
    "max_samples": 2000,   # 参与分析的最大样本数
    "show_samples": True,  # 画像里是否附上主人真实例句
}

# emoji / 颜文字大致范围
_EMOJI_RE = re.compile(
    "[\U0001F000-\U0001FAFF"
    "\U00002600-\U000027BF"
    "\U0001F1E6-\U0001F1FF"
    "\U00002190-\U000021FF"
    "\U00002B00-\U00002BFF]+",
    flags=re.UNICODE,
)

# 常见中文语气词（句末/句中）
_TONE_WORDS = ['吧', '呢', '啊', '哦', '呀', '嘛', '啦', '咯', '嘞', '喽', '哈', '嘿', '唉', '额', '嗯']

# 常见叠词/口头禅候选（用于快速识别）
_FILLER_PATTERNS = ['哈哈', '嘿嘿', '呵呵', '嘻嘻', '嗯嗯', '好好', '是是', '对对', '行行',
                    'ok', 'OK', 'okk', 'emmm', 'emm', '额额', '啊啊', '哦哦', '呀呀']

# 句末标点归类
_PUNCT_ENDINGS = ['。', '！', '？', '～', '~', '…', '.', '!', '?']

# 分析 n-gram 时剔除的纯功能词字符
_STOP_CHARS = set('的了是在我你他她它们这那就都很也还要会说着过把被给让从对与和及或')


# ---------------------------------------------------------------- 设置 / 画像读写
def load_settings():
    s = dict(DEFAULT_SETTINGS)
    try:
        if os.path.exists(SETTINGS_PATH):
            with open(SETTINGS_PATH, 'r', encoding='utf-8') as f:
                s.update(json.load(f))
    except Exception as e:
        logger.warning(f"读取风格设置失败，使用默认值: {e}")
    return s


def save_settings(settings):
    try:
        with _lock:
            with open(SETTINGS_PATH, 'w', encoding='utf-8') as f:
                json.dump(settings, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        logger.error(f"保存风格设置失败: {e}")
        return False


def set_enabled(enabled):
    s = load_settings()
    s['enabled'] = bool(enabled)
    return save_settings(s)


def load_profile():
    """读取已保存的风格画像；不存在则返回空结构。"""
    empty = {'ok': False, 'reason': '尚未生成风格画像', 'features': {}, 'profile_text': '',
             'updated_at': 0, 'sample_count': 0}
    try:
        if not os.path.exists(PROFILE_PATH):
            return empty
        with open(PROFILE_PATH, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return empty
        return data
    except Exception as e:
        logger.error(f"读取风格画像失败: {e}")
        empty['reason'] = f'读取失败: {e}'
        return empty


def save_profile(profile):
    try:
        with _lock:
            with open(PROFILE_PATH, 'w', encoding='utf-8') as f:
                json.dump(profile, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        logger.error(f"保存风格画像失败: {e}")
        return False


# ---------------------------------------------------------------- 统计工具
def _text_only(s):
    """去掉空白，保留内容字符。"""
    return re.sub(r'\s+', '', s or '')


def _avg_len(texts):
    if not texts:
        return 0.0
    return round(sum(len(t) for t in texts) / len(texts), 1)


def _median_len(texts):
    if not texts:
        return 0
    ls = sorted(len(t) for t in texts)
    n = len(ls)
    return ls[n // 2] if n % 2 else (ls[n // 2 - 1] + ls[n // 2]) // 2


def _ending_stats(texts):
    """句末标点习惯。"""
    c = Counter()
    for t in texts:
        t = t.rstrip()
        if not t:
            continue
        last = t[-1]
        if last in '。.':
            c['句号'] += 1
        elif last in '！!':
            c['感叹号'] += 1
        elif last in '？?':
            c['问号'] += 1
        elif last in '～~':
            c['波浪号'] += 1
        elif last in '…':
            c['省略号'] += 1
        else:
            c['无标点'] += 1
    total = sum(c.values()) or 1
    return {k: {'count': v, 'pct': round(v * 100.0 / total, 1)} for k, v in c.most_common()}


def _emoji_stats(texts):
    """emoji / 表情使用率与高频 emoji。"""
    used = 0
    counter = Counter()
    for t in texts:
        found = _EMOJI_RE.findall(t)
        if found:
            used += 1
            for e in found:
                counter[e] += 1
    total = len(texts) or 1
    return {
        'usage_pct': round(used * 100.0 / total, 1),
        'top': [{'emoji': e, 'count': n} for e, n in counter.most_common(8)],
    }


def _tone_stats(texts):
    """语气词使用。"""
    counter = Counter()
    for t in texts:
        for w in _TONE_WORDS:
            if w in t:
                counter[w] += 1
    total = len(texts) or 1
    return [{'word': w, 'pct': round(n * 100.0 / total, 1)}
            for w, n in counter.most_common(6)]


def _filler_stats(texts):
    """叠词 / 口头禅。"""
    counter = Counter()
    joined = []
    for t in texts:
        joined.append(t)
        low = t.lower()
        for p in _FILLER_PATTERNS:
            if p.lower() in low:
                counter[p] += 1
    total = len(texts) or 1
    return [{'phrase': p, 'pct': round(n * 100.0 / total, 1)}
            for p, n in counter.most_common(8)]


def _ngram_stats(texts, n=2, topk=12):
    """高频字级 n-gram（口头禅/常用搭配）。无第三方分词依赖，中文够用。"""
    counter = Counter()
    for t in texts:
        s = re.sub(r'[^\u4e00-\u9fffA-Za-z0-9]', '', t)
        if len(s) < n:
            continue
        for i in range(len(s) - n + 1):
            g = s[i:i + n]
            # 纯功能词 n-gram 没信息量，丢掉
            if all(ch in _STOP_CHARS for ch in g):
                continue
            counter[g] += 1
    out = []
    for g, cnt in counter.most_common(topk * 3):
        if cnt < 2:  # 只出现一次的不算口头禅
            continue
        out.append({'gram': g, 'count': cnt})
        if len(out) >= topk:
            break
    # 去掉被更高频长 n-gram 完全包含的短 n-gram，减少冗余
    filtered = []
    for item in out:
        g = item['gram']
        if any(g != o['gram'] and g in o['gram'] and o['count'] >= item['count'] for o in out):
            continue
        filtered.append(item)
    return filtered[:topk]


def _latin_stats(texts):
    """中英混用 / 纯英文比例。"""
    has_latin = 0
    for t in texts:
        if re.search(r'[A-Za-z]', t):
            has_latin += 1
    total = len(texts) or 1
    return {'pct': round(has_latin * 100.0 / total, 1)}


def _question_exclaim(texts):
    q = sum(1 for t in texts if '？' in t or '?' in t)
    e = sum(1 for t in texts if '！' in t or '!' in t)
    total = len(texts) or 1
    return {'question_pct': round(q * 100.0 / total, 1),
            'exclaim_pct': round(e * 100.0 / total, 1)}


# ---------------------------------------------------------------- 画像生成
def _build_profile_text(f, samples):
    """把统计特征写成给 AI 看的自然语言风格指令。"""
    lines = ["## 主人的说话风格（回复时必须模仿）", ""]

    avg = f.get('avg_len', 0)
    med = f.get('median_len', 0)
    if avg <= 8:
        len_desc = f"句子很短，平均约 {avg} 字（中位数 {med} 字），几乎没有长句"
    elif avg <= 20:
        len_desc = f"句子偏短，平均约 {avg} 字（中位数 {med} 字），简洁为主"
    elif avg <= 45:
        len_desc = f"句子中等长度，平均约 {avg} 字（中位数 {med} 字）"
    else:
        len_desc = f"句子偏长，平均约 {avg} 字（中位数 {med} 字），表达较完整"
    lines.append(f"- 句长：{len_desc}")

    short_pct = f.get('short_pct', 0)
    if short_pct >= 40:
        lines.append(f"- 有 {short_pct}% 的发言是不超过 5 个字的短回复（嗯/好/行 这类）")

    end = f.get('endings') or {}
    if end:
        top = list(end.items())[0]
        lines.append(f"- 句末习惯：最常用「{top[0]}」（占 {top[1]['pct']}%）；"
                     + "、".join(f"{k} {v['pct']}%" for k, v in list(end.items())[:3]))

    emoji = f.get('emoji') or {}
    if emoji.get('usage_pct', 0) >= 5:
        tops = "、".join(x['emoji'] for x in (emoji.get('top') or [])[:4])
        lines.append(f"- 表情：约 {emoji['usage_pct']}% 的发言会带表情，常用 {tops}")
    else:
        lines.append("- 表情：基本不用表情/emoji，回复时也尽量不用")

    tone = f.get('tone_words') or []
    if tone:
        lines.append("- 语气词偏好：" + "、".join(f"{x['word']}({x['pct']}%)" for x in tone[:4]))

    fillers = f.get('fillers') or []
    if fillers:
        lines.append("- 口头禅/叠词：" + "、".join(f"{x['phrase']}({x['pct']}%)" for x in fillers[:5]))

    grams = f.get('ngrams') or []
    if grams:
        lines.append("- 常用搭配/高频词块：" + "、".join(g['gram'] for g in grams[:8]))

    qe = f.get('qe') or {}
    if qe.get('question_pct', 0) >= 15:
        lines.append(f"- 爱提问：{qe['question_pct']}% 的发言带问号")
    if qe.get('exclaim_pct', 0) >= 15:
        lines.append(f"- 情绪外放：{qe['exclaim_pct']}% 的发言带感叹号")

    latin = f.get('latin') or {}
    if latin.get('pct', 0) >= 20:
        lines.append(f"- 有中英混用习惯（{latin['pct']}% 的发言含英文）")

    lines.append("")
    lines.append("要求：用上面的风格回复，保持自然口语化、像真人打字，不要书面腔、"
                 "不要客服腔、不要过度礼貌，也不要每次都带总结性结尾。")

    if samples:
        lines.append("")
        lines.append("### 主人的真实例句（供参考语气，不要照抄内容）")
        for s in samples[:8]:
            s = s.replace('\n', ' ').strip()
            if not s:
                continue
            if len(s) > 60:
                s = s[:60] + '…'
            lines.append(f"- {s}")

    return "\n".join(lines)


# ---------------------------------------------------------------- 主分析入口
def analyze(limit=None, with_samples=True):
    """分析主人风格并落盘。返回画像 dict。"""
    settings = load_settings()
    if limit is None:
        limit = int(settings.get('max_samples', 2000))

    try:
        import chat_history
    except Exception as e:
        return {'ok': False, 'reason': f'chat_history 不可用: {e}', 'features': {},
                'profile_text': '', 'sample_count': 0}

    rows = chat_history.fetch_by_source('owner', limit=limit, order='DESC')
    texts = [r[0].strip() for r in rows if r and r[0] and r[0].strip()]

    if len(texts) < MIN_SAMPLES:
        return {
            'ok': False,
            'reason': f'主人发言样本不足（{len(texts)}/{MIN_SAMPLES} 条）。'
                      f'请多用微信亲手发些消息，bot 会自动记录并学习。',
            'features': {},
            'profile_text': '',
            'sample_count': len(texts),
        }

    # 过滤掉明显的系统/撤回提示等噪声
    texts = [t for t in texts if t not in ('[收到拍一拍消息]',) and not t.startswith('[图片识别结果]')]

    stripped = [_text_only(t) for t in texts]
    stripped = [t for t in stripped if t]
    if not stripped:
        return {'ok': False, 'reason': '没有可用于分析的文本内容', 'features': {},
                'profile_text': '', 'sample_count': 0}

    short_cnt = sum(1 for t in stripped if len(t) <= 5)
    long_cnt = sum(1 for t in stripped if len(t) >= 30)

    features = {
        'sample_count': len(stripped),
        'avg_len': _avg_len(stripped),
        'median_len': _median_len(stripped),
        'short_pct': round(short_cnt * 100.0 / len(stripped), 1),
        'long_pct': round(long_cnt * 100.0 / len(stripped), 1),
        'endings': _ending_stats(texts),
        'emoji': _emoji_stats(texts),
        'tone_words': _tone_stats(texts),
        'fillers': _filler_stats(texts),
        'ngrams': _ngram_stats(stripped, n=2, topk=12),
        'trigrams': _ngram_stats(stripped, n=3, topk=8),
        'qe': _question_exclaim(texts),
        'latin': _latin_stats(texts),
    }

    samples = texts[:10] if with_samples else []
    profile_text = _build_profile_text(features, samples)

    profile = {
        'ok': True,
        'reason': '',
        'features': features,
        'profile_text': profile_text,
        'sample_count': len(stripped),
        'updated_at': time.time(),
    }
    save_profile(profile)
    logger.info(f"风格画像已生成：样本 {len(stripped)} 条")
    return profile


def get_style_injection():
    """返回注入到 system prompt 的风格指令；未启用或无画像时返回空串。"""
    try:
        settings = load_settings()
        if not settings.get('enabled'):
            return ''
        profile = load_profile()
        if not profile.get('ok') or not profile.get('profile_text'):
            return ''
        return "\n\n" + profile['profile_text']
    except Exception as e:
        logger.debug(f"获取风格注入失败（忽略）: {e}")
        return ''


def clear_profile():
    """清空画像（保留开关设置）。"""
    try:
        if os.path.exists(PROFILE_PATH):
            os.remove(PROFILE_PATH)
        return True
    except Exception as e:
        logger.error(f"清空风格画像失败: {e}")
        return False
