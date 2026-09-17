#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WeAuto CLI —— 供 agent 工具（Codex / WorkBuddy 等）以命令行驱动。

设计目标
--------
1. 纯本地命令（history / style / status / config / listen）不需要微信客户端即可运行，
   统一输出 JSON，方便 agent 解析 stdout。
2. 需要微信的命令（send / whois）在微信未登录/未安装时返回清晰的错误 JSON，不崩溃、不卡进程。
3. data 类命令默认输出 JSON；加 --text 转人读格式。serve 为长驻进程（文本日志）。

示例（agent 调用）
-----------------
  python cli.py version
  python cli.py status
  python cli.py history --source owner --limit 20
  python cli.py style --analyze
  python cli.py send   --who 文件传输助手 --msg "你好"
  python cli.py send   --who 群名 --msg "晚安" --type file --path d:/a.pdf
  python cli.py whois  --name SamCai_
  python cli.py config get ENABLE_AUTO_MESSAGE
  python cli.py config set AUTO_MESSAGE_USER_LIST "['SamCai_']"
  python cli.py listen list
  python cli.py listen add "SamCai_" "朋友"
  python cli.py serve        # 前台启动 bot（长驻）

注意：需用本项目的 Python 运行（含 openai / bs4 / wechatauto 等依赖）。
"""
import os
import sys
import re
import ast
import json
import argparse
import logging

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if ROOT_DIR not in sys.path:
    sys.path.insert(0, ROOT_DIR)

logging.basicConfig(level=logging.WARNING, format="%(levelname)s:%(message)s")
logger = logging.getLogger("weauto_cli")

CLI_VERSION = "1.0.0"

# 这些配置项视为密钥，get/set 时不在 stdout 泄露真实值
_SECRET_HINTS = ("KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD")


def _is_secret(name):
    u = (name or "").upper()
    return any(h in u for h in _SECRET_HINTS)


def emit(obj, as_text=False):
    """输出成功结果（JSON 默认），并退出 0。"""
    if as_text:
        if isinstance(obj, dict):
            for k, v in obj.items():
                print(f"{k}: {v}")
        elif isinstance(obj, list):
            for item in obj:
                print(json.dumps(item, ensure_ascii=False))
        else:
            print(obj)
    else:
        print(json.dumps(obj, ensure_ascii=False, indent=2))
    sys.exit(0)


def err(msg, code=1):
    """输出错误结果 JSON 并退出。"""
    print(json.dumps({"ok": False, "error": str(msg)}, ensure_ascii=False, indent=2))
    sys.exit(code)


# --------------------------------------------------------------------- 配置读写
def _set_config_value(key, raw_value):
    """把 KEY = <value> 原子写回 config.py（保留其余内容）。"""
    try:
        val = ast.literal_eval(raw_value)
    except Exception:
        val = raw_value  # 非法字面量 → 按字符串处理
    # json.dumps(ensure_ascii=False) 对 str/int/bool/list/dict 都产出合法 Python 字面量，
    # 且中文保持可读（与 bot.persist_listen_list 的书写风格一致）。
    lit = json.dumps(val, ensure_ascii=False)
    cfg_path = os.path.join(ROOT_DIR, "config.py")
    if not os.path.exists(cfg_path):
        raise FileNotFoundError(f"找不到 config.py: {cfg_path}")
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


# --------------------------------------------------------------------- 命令实现
def cmd_version(args):
    import config
    emit({"ok": True, "version": getattr(config, "VERSION", "unknown"),
          "cli": CLI_VERSION})


def cmd_status(args):
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
    emit({"ok": True, **data}, args.text)


def cmd_history(args):
    import chat_history
    source = args.source
    limit = args.limit
    if source == "all":
        rows = chat_history.fetch_recent(limit)
        out = [{"ts": r[0], "who": r[1], "direction": r[2],
                "source": r[3], "content": r[4]} for r in rows]
    else:
        rows = chat_history.fetch_by_source(source, limit=limit, order=args.order)
        out = [{"content": r[0], "ts": r[1]} for r in rows]
    emit({"ok": True, "source": source, "count": len(out), "messages": out}, args.text)


def cmd_style(args):
    import style_learner
    if args.analyze:
        prof = style_learner.analyze()
    elif args.enable or args.disable:
        style_learner.set_enabled(args.enable)
        prof = style_learner.load_profile()
        prof = dict(prof)
        prof["enabled"] = bool(args.enable)
    else:
        prof = style_learner.load_profile()
    if args.profile:
        emit({"ok": True, "profile_text": prof.get("profile_text", "")}, args.text)
    else:
        out = dict(prof)
        out["settings"] = style_learner.load_settings()
        emit({"ok": True, "profile": out}, args.text)


def cmd_config(args):
    import config
    key = args.key
    if args.action == "get":
        if not hasattr(config, key):
            err(f"未知配置项: {key}")
        val = getattr(config, key)
        if _is_secret(key):
            set_flag = bool(val) and val not in ("", "sk-dummy-placeholder", "dummy")
            emit({"ok": True, "key": key, "set": set_flag, "value": "<redacted>"})
        else:
            emit({"ok": True, "key": key, "value": val})
    else:  # set
        try:
            _set_config_value(key, args.value)
        except Exception as e:
            err(f"写入配置失败: {e}")
        emit({"ok": True, "key": key,
              "value": "<redacted>" if _is_secret(key) else args.value})


def cmd_listen(args):
    # 直接读写 config（不经过 bot 模块，避免导入 bot 触发微信 GUI 初始化拖慢 agent 调用）
    import config
    action = args.action
    placeholder = [["微信名1", "角色1"]]
    lst = list(getattr(config, "LISTEN_LIST", []))
    if action == "list":
        emit({"ok": True, "listen_list": lst})
        return
    if action == "add":
        wxname = args.wxname
        role = args.role or ""
        if lst == placeholder:
            lst = []  # 首次添加时清掉占位项
        for item in lst:
            if item and item[0] == wxname:
                emit({"ok": True, "changed": False, "listen_list": lst})
                return
        lst.append([wxname, role])
        _set_config_value("LISTEN_LIST", lst)
        emit({"ok": True, "changed": True, "listen_list": lst})
    elif action == "remove":
        before = lst
        after = [x for x in before if x and x[0] != args.wxname]
        changed = len(after) != len(before)
        if changed:
            _set_config_value("LISTEN_LIST", after)
        emit({"ok": True, "changed": changed, "listen_list": after})


def cmd_send(args):
    from wechat_compat import WeChat
    wx = None
    try:
        wx = WeChat()
        if args.type == "file":
            if not args.path:
                err("--path 必填（file 类型）")
            wx.SendFiles(args.path, args.who)
            res = {"type": "file", "path": args.path}
        elif args.type == "image":
            if not args.path:
                err("--path 必填（image 类型）")
            wx.SendImage(args.path, args.who)
            res = {"type": "image", "path": args.path}
        else:
            if not args.msg:
                err("--msg 必填（text 类型）")
            wx.SendMsg(args.msg, args.who)
            res = {"type": "text", "msg": args.msg}
        emit({"ok": True, "who": args.who, **res})
    except Exception as e:
        err(f"发送失败（微信未登录/未安装？）: {e}")
    finally:
        if wx is not None:
            try:
                wx.close()
            except Exception:
                pass


def cmd_whois(args):
    from wechat_compat import WeChat
    wx = None
    try:
        wx = WeChat()
        wx._init()  # 需要微信已登录
        username = wx._resolve_username(args.name)
        display = wx._display_name(args.name)
        emit({"ok": True, "input": args.name, "username": username,
              "display_name": display})
    except Exception as e:
        err(f"解析失败（微信未登录/未安装？）: {e}")
    finally:
        if wx is not None:
            try:
                wx.close()
            except Exception:
                pass


def cmd_serve(args):
    # 前台启动 bot（长驻）。agent 通常用后台方式调用本命令。
    import bot
    try:
        bot.main()
    except KeyboardInterrupt:
        pass


# --------------------------------------------------------------------- 入口
def build_parser():
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument("--text", action="store_true", help="人读格式（默认 JSON）")

    p = argparse.ArgumentParser(
        prog="cli.py",
        description="WeAuto 命令行接口（供 agent 工具调用）",
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("version", parents=[parent], help="打印版本").set_defaults(func=cmd_version)

    sub.add_parser("status", parents=[parent], help="运行状态/配置摘要").set_defaults(func=cmd_status)

    h = sub.add_parser("history", parents=[parent], help="读取聊天记录（本地）")
    h.add_argument("--source", default="all",
                   choices=["all", "owner", "bot", "friend", "group"])
    h.add_argument("--limit", type=int, default=50)
    h.add_argument("--order", default="DESC", choices=["DESC", "ASC"])
    h.set_defaults(func=cmd_history)

    s = sub.add_parser("style", parents=[parent], help="主人风格画像（本地）")
    s.add_argument("--analyze", action="store_true", help="重新分析并落盘")
    s.add_argument("--profile", action="store_true", help="仅输出画像文本")
    s.add_argument("--enable", action="store_true", help="开启风格注入")
    s.add_argument("--disable", action="store_true", help="关闭风格注入")
    s.set_defaults(func=cmd_style)

    c = sub.add_parser("config", parents=[parent], help="读写配置项")
    c.add_argument("action", choices=["get", "set"])
    c.add_argument("key")
    c.add_argument("value", nargs="?", default=None)
    c.set_defaults(func=cmd_config)

    l = sub.add_parser("listen", parents=[parent], help="管理监听名单 LISTEN_LIST")
    lsub = l.add_subparsers(dest="action", required=True)
    lsub.add_parser("list").set_defaults(func=cmd_listen)
    la = lsub.add_parser("add")
    la.add_argument("wxname")
    la.add_argument("role", nargs="?", default="")
    la.set_defaults(func=cmd_listen)
    lr = lsub.add_parser("remove")
    lr.add_argument("wxname")
    lr.set_defaults(func=cmd_listen)

    se = sub.add_parser("send", parents=[parent], help="发送微信消息（需微信登录）")
    se.add_argument("--who", required=True, help="显示名 / wxid / 群wxid@chatroom")
    se.add_argument("--msg", default=None)
    se.add_argument("--type", default="text", choices=["text", "file", "image"])
    se.add_argument("--path", default=None, help="file/image 类型的文件路径")
    se.set_defaults(func=cmd_send)

    w = sub.add_parser("whois", parents=[parent], help="解析名字/微信ID（需微信登录）")
    w.add_argument("--name", required=True)
    w.set_defaults(func=cmd_whois)

    sub.add_parser("serve", parents=[parent], help="前台启动 bot（长驻）").set_defaults(func=cmd_serve)

    return p


def main():
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
