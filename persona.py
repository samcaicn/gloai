# -*- coding: utf-8 -*-
"""AI 分身 · 主人侧（身份档案 / 人设 / 教学聊天 / 提示词注入）。

范围（重要）
------------
**只保留主人部分**：没有访客、没有名片分享链接、没有会话轮询与接管。
本项目里「访客」就是**用户列表里的微信联系人**——分身人设一旦启用，
会通过 `bot.get_user_prompt()` 注入，对 bot 回复的所有联系人生效。

数据来源
--------
* 身份档案 + 人设 + 教学聊天：本模块，落在 `device_id.stable_data_dir()`
  （安装目录之外，卸载重装不丢）。
* 主人说话风格：复用既有 `style_learner`（从真实微信聊天记录学习），
  本模块只负责把它和身份档案拼在一起，不重复造轮子。

对外接口
--------
    load() / save(data)
    identity() / save_identity(dict)
    settings() / save_settings(dict)
    chat() / add_chat(role, content) / clear_chat()
    build_prompt()          -> str  完整人设提示词
    get_injection()         -> str  注入 system prompt 的文本（未启用返回 ''）
    export_json() / import_json(text)
    stats()                 -> dict
"""

import json
import os
import time

try:
    import device_id
except Exception:  # noqa: BLE001
    device_id = None

_PATH = None
_lock = None
try:
    import threading
    _lock = threading.Lock()
except Exception:  # noqa: BLE001
    _lock = None


def _path():
    global _PATH
    if _PATH is None:
        base = device_id.stable_data_dir() if device_id else os.path.dirname(os.path.abspath(__file__))
        _PATH = os.path.join(base, "persona.json")
    return _PATH


DEFAULT = {
    "version": 1,
    "device_id": "",
    "identity": {
        "nameCN": "", "title": "", "company": "", "city": "",
        "about": "", "companyDescription": "", "productDescription": "",
    },
    "settings": {
        "enabled": False,       # 是否把分身人设注入提示词
        "personaPrompt": "",    # 额外人设（可留空）
        "chatPurpose": "",      # 代聊目的，如「商务对接」
    },
    "chat": [],                 # 教学聊天：[{role:user|assistant, content, ts}]
    # 云端同步下来的主人说话风格（Android 端学出来的那份）。本地 style_learner
    # 学出来的风格优先；本地没有时才用它 —— 这样跨端能共享同一套说话风格。
    "remote_style": "",
    "updated_at": 0,
}


def _blank():
    return json.loads(json.dumps(DEFAULT))  # 深拷贝，避免共享引用


def load():
    try:
        with open(_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        if not isinstance(d, dict):
            return _blank()
    except Exception:
        return _blank()
    # 补齐缺失字段（兼容旧版本/手写文件）
    base = _blank()
    for k, v in base.items():
        if k not in d:
            d[k] = v
    for section in ("identity", "settings"):
        if not isinstance(d.get(section), dict):
            d[section] = base[section]
        else:
            for k, v in base[section].items():
                d[section].setdefault(k, v)
    if not isinstance(d.get("chat"), list):
        d["chat"] = []
    return d


def save(data):
    data = dict(data or {})
    data["version"] = 1
    data["updated_at"] = time.time()
    try:
        if device_id is not None:
            data["device_id"] = device_id.get_device_id()
    except Exception:
        pass
    p = _path()
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, p)
    return True


# ------------------------------------------------------------------ 读写片段
def identity():
    return load().get("identity", {})


def save_identity(fields):
    d = load()
    for k in DEFAULT["identity"]:
        if k in fields:
            d["identity"][k] = str(fields.get(k) or "").strip()
    save(d)
    return d["identity"]


def settings():
    return load().get("settings", {})


def save_settings(fields):
    d = load()
    s = d["settings"]
    s["enabled"] = bool(fields.get("enabled"))
    for k in ("personaPrompt", "chatPurpose"):
        if k in fields:
            s[k] = str(fields.get(k) or "").strip()
    save(d)
    return s


def chat():
    return load().get("chat", [])


def add_chat(role, content):
    d = load()
    d["chat"].append({
        "role": "assistant" if role == "assistant" else "user",
        "content": str(content or ""),
        "ts": time.time(),
    })
    # 教学聊天只保留最近 200 条，避免文件无限增长
    if len(d["chat"]) > 200:
        d["chat"] = d["chat"][-200:]
    save(d)
    return d["chat"]


def clear_chat():
    d = load()
    d["chat"] = []
    save(d)
    return True


# ------------------------------------------------------------------ 提示词
def _style_text():
    """主人的说话风格：本地 style_learner 优先，其次云端同步下来的那份。

    本地是本机真实聊天记录学出来的（最贴合）；本机还没学时，用云端那份
    （可能是 Android 端学出来的），从而实现跨端风格互通。
    """
    try:
        import style_learner
        t = (style_learner.load_profile() or {}).get("profile_text", "") or ""
        if str(t).strip():
            return t
    except Exception:
        pass
    try:
        return str(load().get("remote_style", "") or "")
    except Exception:
        return ""


def build_prompt():
    """拼出完整分身人设（身份 + 目的 + 额外人设 + 说话风格）。"""
    d = load()
    ident = d.get("identity", {})
    st = d.get("settings", {})

    lines = ["## 我的 AI 分身人设（回复时必须遵守）", ""]
    name = ident.get("nameCN", "").strip()
    if name:
        lines.append(f"- 我是{name}")
    bits = []
    if ident.get("title"):
        bits.append(f"职位是{ident['title']}")
    if ident.get("company"):
        bits.append(f"在{ident['company']}")
    if ident.get("city"):
        bits.append(f"常驻{ident['city']}")
    if bits:
        lines.append("- " + "，".join(bits))
    if ident.get("about"):
        lines.append(f"- 关于我：{ident['about']}")
    if ident.get("companyDescription"):
        lines.append(f"- 我们公司能提供：{ident['companyDescription']}")
    if ident.get("productDescription"):
        lines.append(f"- 我们的产品/服务：{ident['productDescription']}")
    if st.get("chatPurpose"):
        lines.append(f"- 对话目的：{st['chatPurpose']}")
    if st.get("personaPrompt"):
        lines.append(f"- 额外人设要求：{st['personaPrompt']}")

    style = _style_text()
    if style:
        lines.append("")
        lines.append(style.strip())

    lines.append("")
    lines.append("要求：用我本人的口吻回复；拿不准的信息如实说“这个我手头没准数，回头帮你核实”，"
                 "不要编造。保持自然口语，不要客服腔。")
    return "\n".join(lines)


def get_injection():
    """注入 system prompt 的文本；未启用或什么都没填时返回 ''（保证零影响）。"""
    try:
        d = load()
        if not d.get("settings", {}).get("enabled"):
            return ""
        ident = d.get("identity", {})
        has_content = any(str(v or "").strip() for v in ident.values())
        if not has_content and not d.get("settings", {}).get("personaPrompt"):
            return ""
        return "\n\n" + build_prompt()
    except Exception:
        return ""


# ------------------------------------------------------------------ 导入导出
def export_json():
    d = load()
    out = {
        "app": "WeAuto",
        "type": "ai-avatar-identity",
        "version": 1,
        "device_id": d.get("device_id", ""),
        "identity": d.get("identity", {}),
        "settings": d.get("settings", {}),
        "exported_at": time.time(),
    }
    return json.dumps(out, ensure_ascii=False, indent=2)


def import_json(text):
    """导入身份 JSON。返回 (ok, message)。"""
    try:
        obj = json.loads(text)
    except Exception as e:
        return False, f"不是合法 JSON：{e}"
    if not isinstance(obj, dict):
        return False, "JSON 顶层必须是对象"
    ident = obj.get("identity")
    if not isinstance(ident, dict):
        return False, "缺少 identity 字段"
    d = load()
    for k in DEFAULT["identity"]:
        d["identity"][k] = str(ident.get(k) or "").strip()
    if isinstance(obj.get("settings"), dict):
        for k in ("personaPrompt", "chatPurpose"):
            if k in obj["settings"]:
                d["settings"][k] = str(obj["settings"].get(k) or "").strip()
        d["settings"]["enabled"] = bool(obj["settings"].get("enabled", d["settings"].get("enabled", False)))
    save(d)
    return True, "身份已导入"


def stats():
    d = load()
    ident = d.get("identity", {})
    return {
        "filled": sum(1 for v in ident.values() if str(v or "").strip()),
        "fields": len(DEFAULT["identity"]),
        "chat_count": len(d.get("chat", [])),
        "owner_msgs": sum(1 for m in d.get("chat", []) if m.get("role") == "user"),
        "enabled": bool(d.get("settings", {}).get("enabled")),
        "updated_at": d.get("updated_at", 0),
        "path": _path(),
    }


def reset_all():
    """清空身份与人设（保留安装目录之外的数据文件结构）。"""
    try:
        p = _path()
        if os.path.exists(p):
            os.remove(p)
    except Exception:
        pass
    return True
