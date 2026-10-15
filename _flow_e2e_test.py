# -*- coding: utf-8 -*-
"""
WeAuto 业务流程端到端实测（Flask test_client，不启动微信 bot、不联网付费）
用法： .venv_bot/Scripts/python.exe _flow_e2e_test.py

覆盖：
  1. CLI 只读命令（铁律入口必须可用）：version / status / history / listen list / config get
  2. CLI 安全边界：写类命令必须被拒绝（网页端不得变成任意改配置/发消息入口）
  3. 授权购买链接：tier 与邮箱预填（含 + 的百分号编码，防被解成空格）
  4. MCP 诊断接口
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
os.chdir(ROOT)

BAK = os.path.join(ROOT, "config.py.bak_test")
CFG = os.path.join(ROOT, "config.py")

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("  [PASS] " if cond else "  [FAIL] ") + name + ((" | " + str(detail)) if detail else ""))


def main():
    if not os.path.exists(BAK):
        shutil.copy(CFG, BAK)
    import config_editor as ce
    ce.app.config["WTF_CSRF_ENABLED"] = False
    ce.app.config["TESTING"] = True
    c = ce.app.test_client()

    print("\n=== 1. CLI 只读命令（铁律保留入口）===")
    st = c.get("/api/license/status").get_json() or {}
    activated = bool(st.get("activated"))
    # 免费模式（无卡密）默认激活：CLI/MCP 照常可用，仅每日条数受云端限制
    active = activated or bool(st.get("free_mode"))
    print(f"  当前授权状态: activated={activated} free_mode={st.get('free_mode')}")
    for cmd, keyword in [("version", None), ("status", None), ("history", None),
                         ("listen list", None), ("config get MAX_GROUPS", "MAX_GROUPS")]:
        r = c.post("/api/cli/run", json={"cmd": cmd})
        d = r.get_json() or {}
        ok = bool(d.get("ok"))
        out = ((d.get("stdout") or "") + (d.get("stderr") or "")).strip()
        detail = out[:90].replace("\n", " ")
        if active:
            good = ok and ((keyword in out) if keyword else True)
            label = f"CLI `{cmd}` 可执行"
        else:
            # 已填卡密但激活失败：门禁必须拦截，且必须给出清晰的购买/激活引导（UX 关键）
            good = (not ok) and ("未激活" in out) and ("购买" in out or "/buy" in out)
            label = f"CLI `{cmd}` 被门禁拦截且给出激活引导"
        check(label, good, detail)

    print("\n=== 2. CLI 安全边界（写类命令必须被拒）===")
    for cmd in ["send 你好", "config set MAX_GROUPS 99", "listen add 某人", "restart"]:
        r = c.post("/api/cli/run", json={"cmd": cmd})
        d = r.get_json() or {}
        check(f"CLI 拒绝写命令 `{cmd}`", d.get("ok") is False, (d.get("error") or "")[:80])

    print("\n=== 3. 授权购买链接（Creem 收款板块已隐藏 → 断言下线）===")
    r = c.get("/api/license/buy_url?tier=normal&go=1")
    check("buy_url 已下线（Creem 收款板块隐藏，返回 404）", r.status_code == 404, f"{r.status_code}")
    r2 = c.get("/api/license/buy_url?tier=premium&email=a%2Bb%40example.com")
    check("buy_url 任意参数均下线", r2.status_code == 404, f"{r2.status_code}")
    # 注：线上收款改走 暗号→本地离线授权（scriptpack .wpk），不再有公开购买链接暴露面。

    print("\n=== 4. MCP 诊断接口 ===")
    r = c.get("/api/mcp/check")
    check("MCP 诊断接口 200", r.status_code == 200, str(r.get_json())[:110])

    print("\n=== 5. CLI / MCP 页面授权状态前置提示 ===")
    for path in ["/cli?embed=1", "/mcp?embed=1"]:
        rr = c.get(path)
        html = rr.get_data(as_text=True)
        check(f"{path} 渲染 200", rr.status_code == 200, f"{rr.status_code}")
        if active:
            check(f"{path} 有授权能力时不显示未激活横幅（不打扰）", "未激活授权" not in html)
        else:
            check(f"{path} 未激活时前置显示授权横幅", "未激活授权" in html)
            check(f"{path} 横幅提供购买引导（浮层，不弹新窗口）", "openBuyFromSub" in html)

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
    except Exception:
        import traceback
        traceback.print_exc()
        code = 1
    finally:
        if os.path.exists(BAK):
            shutil.copy(BAK, CFG)
            print("[还原] config.py 已从 config.py.bak_test 还原")
    sys.exit(code)
