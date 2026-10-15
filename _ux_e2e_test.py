# -*- coding: utf-8 -*-
"""
WeAuto 界面 / 业务流程端到端实测（Flask test_client，不启动 GUI、不启动微信 bot）
用法： .venv_bot/Scripts/python.exe _ux_e2e_test.py

覆盖：
  1. 首次启动引导流程（无 API key -> 重定向快速上手）
  2. 主界面渲染 + 精简验证（已删字段不在界面 / 保留字段在界面）
  3. 各内嵌子页面渲染（快速上手 / CLI / MCP / 帮助 / 风格实验室 / 头像）
  4. 【核心】保存零回归：按界面真实字段构造 POST /submit_config，
     断言已从界面删除的 11 个字段保存后仍保持原值（不被清零/覆盖为 False）
  5. 只读业务接口（机器人状态 / 授权状态）

安全：自动备份并在结束时还原 config.py，避免污染仓库。
"""
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
os.chdir(ROOT)

BAK = os.path.join(ROOT, "config.py.bak_test")
CFG = os.path.join(ROOT, "config.py")

# 已从界面删除、改由 config.py 默认值自动决定的字段
REMOVED_KEYS = [
    "AVERAGE_TYPING_SPEED", "RANDOM_TYPING_SPEED_MIN", "RANDOM_TYPING_SPEED_MAX",
    "QUEUE_WAITING_TIME", "SEPARATE_ROW_SYMBOLS", "MAX_GROUPS",
    "MIN_COUNTDOWN_HOURS", "MAX_COUNTDOWN_HOURS",
    "QUIET_TIME_START", "QUIET_TIME_END", "IGNORE_GROUP_CHAT_FOR_AUTO_MESSAGE",
]

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("  [PASS] " if cond else "  [FAIL] ") + name + ((" | " + str(detail)) if detail else ""))


def parse_form(html):
    """从渲染后的 HTML 解析 configForm 内的真实表单字段（模拟浏览器提交语义）"""
    m = re.search(r'<form\b[^>]*id="configForm"[^>]*>(.*?)</form>', html, re.S | re.I)
    body = m.group(1) if m else html
    form = {}

    for tag in re.findall(r"<input\b[^>]*>", body, re.I):
        nm = re.search(r'name="([^"]+)"', tag)
        if not nm:
            continue
        name = nm.group(1)
        tm = re.search(r'type="([^"]+)"', tag)
        typ = tm.group(1).lower() if tm else "text"
        if typ in ("file", "button", "submit", "reset", "image"):
            continue
        vm = re.search(r'value="([^"]*)"', tag)
        val = vm.group(1) if vm else ""
        if typ in ("checkbox", "radio"):
            # 浏览器语义：未勾选不提交；未指定 value 时提交 "on"
            if "checked" not in tag:
                continue
            if not val:
                val = "on"
        form.setdefault(name, []).append(val)

    for name, inner in re.findall(r'<select\b[^>]*name="([^"]+)"[^>]*>(.*?)</select>', body, re.S | re.I):
        sel = re.search(r'<option\b[^>]*value="([^"]*)"[^>]*selected', inner, re.I)
        if sel:
            form.setdefault(name, []).append(sel.group(1))
        else:
            first = re.search(r'<option\b[^>]*value="([^"]*)"', inner, re.I)
            if first:
                form.setdefault(name, []).append(first.group(1))

    for name, inner in re.findall(r'<textarea\b[^>]*name="([^"]+)"[^>]*>(.*?)</textarea>', body, re.S | re.I):
        form.setdefault(name, []).append(inner)

    return form


def main():
    if not os.path.exists(BAK):
        shutil.copy(CFG, BAK)
        print("[备份] config.py -> config.py.bak_test")

    import config_editor as ce

    ce.app.config["WTF_CSRF_ENABLED"] = False
    ce.app.config["TESTING"] = True
    client = ce.app.test_client()

    print("\n=== 1. 首次启动引导流程 ===")
    cfg0 = ce.parse_config()
    has_key = bool((cfg0.get("DEEPSEEK_API_KEY") or "").strip())
    r = client.get("/")
    loc = r.headers.get("Location") or ""
    try:
        import weauto_license.guard as _g
        _guard = bool(_g.guard_enabled())
    except Exception:
        _guard = False
    if has_key or _guard:
        check("已有 API key 或门禁启用 -> 直接进入主界面（不强制引导）", r.status_code == 200,
              f"{r.status_code}（key={has_key}, guard={_guard}）")
    else:
        check("无 key 且无门禁 -> 引导到快速上手",
              r.status_code in (301, 302) and "quick_start" in loc, f"{r.status_code} -> {loc}")

    print("\n=== 2. 主界面渲染 + 精简验证 ===")
    r = client.get("/?skipped=true")
    html = r.get_data(as_text=True)
    check("主界面渲染 200", r.status_code == 200, f"{len(html)} 字节")
    for k in REMOVED_KEYS:
        check(f"已精简：{k} 不在界面", f'name="{k}"' not in html)
    for k in ["ENABLE_EMOJI_SENDING", "ENABLE_AUTO_MESSAGE", "ENABLE_MEMORY", "nickname"]:
        check(f"保留项：{k} 在界面", f'name="{k}"' in html)

    print("\n=== 3. 内嵌子页面渲染 ===")
    for path in ["/quick_start?embed=1", "/cli?embed=1", "/mcp?embed=1",
                 "/help?embed=1", "/style_lab?embed=1", "/avatar?embed=1"]:
        rr = client.get(path)
        check(f"子页 {path} 渲染 200", rr.status_code == 200, f"实际 {rr.status_code}")

    print("\n=== 4. 保存零回归（按界面真实字段提交）===")
    before = ce.parse_config()
    form = parse_form(html)
    print(f"  从界面解析到 {len(form)} 个字段")
    from werkzeug.datastructures import MultiDict
    md = MultiDict()
    for k, vals in form.items():
        if k in ("password", "confirm_password"):
            continue
        for v in vals:
            md.add(k, v)
    # 把一个可见字段改成独特值，用于确证"保存链路真的生效"（排除零回归假阳性）
    md["EMOJI_SENDING_PROBABILITY"] = "77"
    r = client.post("/submit_config", data=md)
    body = r.get_data(as_text=True)
    check("保存配置成功（200/204）", r.status_code in (200, 204), f"status={r.status_code} body={body[:150]}")
    after = ce.parse_config()
    check("保存确证：EMOJI_SENDING_PROBABILITY 写回 77（证明保存真的生效，非零回归假阳性）",
          str(after.get("EMOJI_SENDING_PROBABILITY")) == "77", f"={after.get('EMOJI_SENDING_PROBABILITY')!r}")
    for k in REMOVED_KEYS:
        check(f"零回归：{k} 保存后保持原值", before.get(k) == after.get(k),
              f"{before.get(k)!r} -> {after.get(k)!r}")
    # 保留的 boolean 项应能被界面正确写回（验证开关仍可用）
    for k in ["ENABLE_EMOJI_SENDING", "ENABLE_AUTO_MESSAGE", "ENABLE_MEMORY"]:
        check(f"保留开关 {k} 仍在配置中", k in after, f"={after.get(k)!r}")

    print("\n=== 5. 只读业务接口 ===")
    r = client.get("/bot_status")
    check("机器人状态接口可用", r.status_code == 200, r.get_data(as_text=True)[:100])
    r = client.get("/api/license/status")
    check("授权状态接口可用", r.status_code == 200, r.get_data(as_text=True)[:100])

    total = len(results)
    passed = sum(1 for _, ok in results if ok)
    print(f"\n===== 结果：{passed}/{total} 通过 =====")
    if passed != total:
        print("失败项：")
        for n, ok in results:
            if not ok:
                print("   - " + n)
    return 0 if passed == total else 1


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    except Exception as e:
        import traceback
        traceback.print_exc()
        code = 1
    finally:
        if os.path.exists(BAK):
            shutil.copy(BAK, CFG)
            print("[还原] config.py 已从 config.py.bak_test 还原")
    sys.exit(code)
