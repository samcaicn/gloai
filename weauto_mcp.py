#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WeAuto MCP Server —— 让 Codex / WorkBuddy 等 agent 通过 MCP 协议驱动微信机器人。

传输方式：stdio（默认）。agent 方在 mcp.json 里这样配即可：

  Windows（自包含 exe）：
    {"mcpServers": {"weauto": {"command": "C:/.../weauto_mcp.exe", "args": []}}}

  或用本项目 venv 的 python 直接跑脚本：
    {"mcpServers": {"weauto": {"command": "C:/.../WeChatBot_WXAUTO_SE-3.25.1/.venv_bot/Scripts/python.exe",
                               "args": ["C:/.../weauto_mcp.py"]}}}

对外暴露的工具（与 cli.py 一一对应）：
  status            运行状态/配置摘要（无需微信）
  history           读取聊天记录（本地 SQLite，无需微信）
  style             主人风格画像（本地，无需微信）
  config_get        读取配置项（密钥脱敏，无需微信）
  config_set        写入配置项（原子写回 config.py，无需微信）
  listen_list       监听名单
  listen_add        加入监听名单
  listen_remove     移出监听名单
  send_message      发微信消息（需微信已登录）
  whois             解析名字/微信ID（需微信已登录）

注意：
- 纯本地工具不需要微信客户端即可工作。
- send_message / whois 在微信未登录时返回明确的 error 文本，不会崩溃。
- 默认不主动给任何人发消息（AUTO_MESSAGE_USER_LIST 空）；要主动聊天须显式加白名单。
"""
import os
import sys
import re
import ast
import json
import logging

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if ROOT_DIR not in sys.path:
    sys.path.insert(0, ROOT_DIR)

# --- 冻结(单文件exe)模式引导（与 bot.py / config_editor.py 一致）---
# 把 config.py 外置到 exe 同目录（可写、跨运行持久），使 config_get/set 在冻结态下
# 也能读写同一份配置；其余本地模块(chat_history/style_learner/wxauto_compat)由 PyInstaller
# 直接打包进 exe 内部，无需外置。
if getattr(sys, "frozen", False):
    import shutil as _shutil
    _exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    _meipass = getattr(sys, "_MEIPASS", None)
    if _exe_dir not in sys.path:
        sys.path.insert(0, _exe_dir)
    if _meipass:
        for _item in ("config.py",):
            _src = os.path.join(_meipass, _item)
            _dst = os.path.join(_exe_dir, _item)
            if os.path.exists(_src) and not os.path.exists(_dst):
                try:
                    _shutil.copy(_src, _dst)
                except Exception:
                    pass
        _orig_abspath = os.path.abspath
        def _patched_abspath(p):
            r = _orig_abspath(p)
            if r.startswith(_meipass):
                r = _exe_dir + r[len(_meipass):]
            return r
        os.path.abspath = _patched_abspath
    os.chdir(_exe_dir)
    ROOT_DIR = _exe_dir

logging.basicConfig(level=logging.WARNING, format="%(levelname)s:%(message)s")
logger = logging.getLogger("weauto_mcp")

try:
    from mcp.server.fastmcp import FastMCP
except Exception as e:  # pragma: no cover - 依赖缺失时给出清晰报错
    raise SystemExit("无法导入 mcp（请先 pip install 'mcp>=1.9,<2'）：%s" % e)

MCP_VERSION = "1.0.0"
_SECRET_HINTS = ("KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD")


def _is_secret(name):
    u = (name or "").upper()
    return any(h in u for h in _SECRET_HINTS)


def _mask(val):
    if val in (None, "", "sk-dummy-placeholder", "dummy"):
        return "(未设置)"
    return "<redacted>"


# ----------------------------------------------------------------- 配置读写（复用 cli 逻辑）
def _read_config():
    import config
    cfg = {}
    for k in dir(config):
        if k.isupper():
            cfg[k] = getattr(config, k)
    return cfg


def _set_config_value(key, raw_value):
    try:
        val = ast.literal_eval(raw_value)
    except Exception:
        val = raw_value
    lit = json.dumps(val, ensure_ascii=False)
    cfg_path = os.path.join(ROOT_DIR, "config.py")
    if not os.path.exists(cfg_path):
        raise FileNotFoundError("找不到 config.py: %s" % cfg_path)
    text = open(cfg_path, encoding="utf-8").read()
    pat = re.compile(r"^(%s\s*=\s*).*$" % re.escape(key), re.M)
    if pat.search(text):
        new_text = pat.sub(lambda m: m.group(1) + lit, text)
    else:
        new_text = text.rstrip() + "\n\n%s = %s\n" % (key, lit)
    tmp = cfg_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(new_text)
    os.replace(tmp, cfg_path)  # 原子替换，避免半写损坏配置
    return True


# ----------------------------------------------------------------- 懒加载微信连接
_wx = None


def _get_wx():
    """懒加载并复用微信连接；失败抛清晰异常。"""
    global _wx
    if _wx is None:
        from wxauto_compat import WeChat
        _wx = WeChat()
    return _wx


def _close_wx():
    global _wx
    if _wx is not None:
        try:
            _wx.close()
        except Exception:
            pass
        _wx = None


# ================================================================= MCP server
mcp = FastMCP("WeAuto")


@mcp.tool()
def status() -> str:
    """返回 WeAuto 运行状态与配置摘要（无需微信登录）。含版本、登录开关、主动消息白名单、监听名单、风格样本数、聊天统计。"""
    import config
    import chat_history
    import style_learner
    data = {
        "version": getattr(config, "VERSION", "unknown"),
        "bypass_login": getattr(config, "BYPASS_LOGIN", None),
        "enable_auto_message": getattr(config, "ENABLE_AUTO_MESSAGE", None),
        "auto_message_user_list": getattr(config, "AUTO_MESSAGE_USER_LIST", []),
        "listen_list": getattr(config, "LISTEN_LIST", []),
        "style_enabled": style_learner.load_settings().get("enabled"),
        "style_sample_count": style_learner.load_profile().get("sample_count", 0),
        "chat_stats": chat_history.stats(),
    }
    return json.dumps(data, ensure_ascii=False, indent=2)


@mcp.tool()
def history(source: str = "all", limit: int = 50, order: str = "DESC") -> str:
    """读取本地聊天记录（SQLite，无需微信）。source: all|owner|bot|friend|group；owner=主人亲发，bot=机器人代发。"""
    import chat_history
    if source not in ("all", "owner", "bot", "friend", "group"):
        return json.dumps({"ok": False, "error": "source 必须是 all/owner/bot/friend/group"}, ensure_ascii=False)
    limit = max(1, min(int(limit), 2000))
    if source == "all":
        rows = chat_history.fetch_recent(limit)
        out = [{"ts": r[0], "who": r[1], "direction": r[2],
                "source": r[3], "content": r[4]} for r in rows]
    else:
        rows = chat_history.fetch_by_source(source, limit=limit, order=order)
        out = [{"content": r[0], "ts": r[1]} for r in rows]
    return json.dumps({"ok": True, "source": source, "count": len(out), "messages": out},
                      ensure_ascii=False, indent=2)


@mcp.tool()
def style(action: str = "show", enable: bool = False) -> str:
    """主人对话风格画像（本地统计，无需微信）。action: show|analyze|enable|disable。"""
    import style_learner
    if action == "analyze":
        prof = style_learner.analyze()
    elif action == "enable":
        style_learner.set_enabled(True)
        prof = style_learner.load_profile()
    elif action == "disable":
        style_learner.set_enabled(False)
        prof = style_learner.load_profile()
    else:
        prof = style_learner.load_profile()
    out = dict(prof)
    out["settings"] = style_learner.load_settings()
    if action in ("enable", "disable"):
        out["enabled"] = bool(style_learner.load_settings().get("enabled"))
    return json.dumps({"ok": True, "profile": out}, ensure_ascii=False, indent=2)


@mcp.tool()
def config_get(key: str) -> str:
    """读取一个配置项。密钥类（含 KEY/SECRET/TOKEN/PASSWORD）返回值会被脱敏，不会泄露真值。"""
    import config
    if not hasattr(config, key):
        return json.dumps({"ok": False, "error": "未知配置项: %s" % key}, ensure_ascii=False)
    val = getattr(config, key)
    if _is_secret(key):
        return json.dumps({"ok": True, "key": key, "set": bool(val) and val not in ("", "sk-dummy-placeholder", "dummy"),
                           "value": _mask(val)}, ensure_ascii=False)
    return json.dumps({"ok": True, "key": key, "value": val}, ensure_ascii=False, indent=2)


@mcp.tool()
def config_set(key: str, value: str) -> str:
    """写入一个配置项（原子写回 config.py）。value 传 Python 字面量，如 'True'、'[\"SamCai_\"]'、'你好'。"""
    try:
        _set_config_value(key, value)
    except Exception as e:
        return json.dumps({"ok": False, "error": "写入配置失败: %s" % e}, ensure_ascii=False)
    return json.dumps({"ok": True, "key": key,
                       "value": _mask(value) if _is_secret(key) else value}, ensure_ascii=False, indent=2)


@mcp.tool()
def listen_list() -> str:
    """列出当前监听名单 LISTEN_LIST（无需微信）。"""
    import config
    lst = list(getattr(config, "LISTEN_LIST", []))
    return json.dumps({"ok": True, "listen_list": lst}, ensure_ascii=False, indent=2)


@mcp.tool()
def listen_add(wxname: str, role: str = "") -> str:
    """把某人加入监听名单（原子写回 config.py，无需微信）。首次添加会自动清掉占位项。"""
    import config
    placeholder = [["微信名1", "角色1"]]
    lst = list(getattr(config, "LISTEN_LIST", []))
    if lst == placeholder:
        lst = []
    for item in lst:
        if item and item[0] == wxname:
            return json.dumps({"ok": True, "changed": False, "listen_list": lst}, ensure_ascii=False, indent=2)
    lst.append([wxname, role])
    _set_config_value("LISTEN_LIST", lst)
    return json.dumps({"ok": True, "changed": True, "listen_list": lst}, ensure_ascii=False, indent=2)


@mcp.tool()
def listen_remove(wxname: str) -> str:
    """把某人移出监听名单（原子写回 config.py，无需微信）。"""
    import config
    before = list(getattr(config, "LISTEN_LIST", []))
    after = [x for x in before if x and x[0] != wxname]
    changed = len(after) != len(before)
    if changed:
        _set_config_value("LISTEN_LIST", after)
    return json.dumps({"ok": True, "changed": changed, "listen_list": after}, ensure_ascii=False, indent=2)


@mcp.tool()
def send_message(who: str, msg: str = "", msg_type: str = "text", path: str = "") -> str:
    """发送微信消息（需微信已登录）。who: 显示名/wxid/群wxid@chatroom。msg_type: text|file|image；file/image 需给 path。"""
    try:
        wx = _get_wx()
        if msg_type == "file":
            if not path:
                return json.dumps({"ok": False, "error": "file 类型需提供 path"}, ensure_ascii=False)
            wx.SendFiles(path, who)
            res = {"type": "file", "path": path}
        elif msg_type == "image":
            if not path:
                return json.dumps({"ok": False, "error": "image 类型需提供 path"}, ensure_ascii=False)
            wx.SendImage(path, who)
            res = {"type": "image", "path": path}
        else:
            if not msg:
                return json.dumps({"ok": False, "error": "text 类型需提供 msg"}, ensure_ascii=False)
            wx.SendMsg(msg, who)
            res = {"type": "text", "msg": msg}
        return json.dumps({"ok": True, "who": who, **res}, ensure_ascii=False, indent=2)
    except Exception as e:
        return json.dumps({"ok": False, "error": "发送失败（微信未登录/未安装？）: %s" % e}, ensure_ascii=False)
    finally:
        _close_wx()


@mcp.tool()
def whois(name: str) -> str:
    """解析微信名字/微信ID（需微信已登录）。返回 username(wxid 或 xxx@chatroom) 与 display_name。"""
    try:
        wx = _get_wx()
        wx._init()
        username = wx._resolve_username(name)
        display = wx._display_name(name)
        return json.dumps({"ok": True, "input": name, "username": username,
                          "display_name": display}, ensure_ascii=False, indent=2)
    except Exception as e:
        return json.dumps({"ok": False, "error": "解析失败（微信未登录/未安装？）: %s" % e}, ensure_ascii=False)
    finally:
        _close_wx()


def main():
    # 默认 stdio 传输；保持 stdin=协议通道，日志走 stderr。
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
