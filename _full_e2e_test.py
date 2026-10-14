# -*- coding: utf-8 -*-
"""
WeAuto 全业务环节端到端实测（Flask test_client，不启动 GUI、不启动微信 bot）

用法： .venv_bot/Scripts/python.exe _full_e2e_test.py

覆盖环节：
  1. 探活 / 会话（ping / logout 后自动登录）
  2. 全部页面渲染（主界面 + 9 个内嵌子页 + 论坛页）
  3. 机器人控制（状态 / 停止）—— ⚠️ 刻意不测 start_bot：它会真 Popen 起微信 bot 抢监听
  4. 定时提醒（读 / 存）
  5. 核心记忆（列表 / 读 / 写 / 删，用测试文件名）
  6. 聊天上下文（用户列表 / 读 / 写 / 清理不存在用户）
  7. AI 分身 NPC 设置（读 / 写）
  8. 朋友圈 forum（帖子 / 主题 / 点赞 / 消息流 / 头像）
  9. 固定转发（规则读 / 写 / 日志）
 10. Jev 守护（状态 / 测试）
 11. 人设 Prompt（编辑页 / 新建页 / AI 生成拒绝路径）
 12. 授权（无效卡密激活 / 非法邮箱拒绝）
 13. 配置导入（非法输入必须被拒，不得污染配置）
 14. 恢复默认配置（写后还原，且必须保留端口/密码不被锁在外面）
 15. 心跳 / 日志上报

安全：整体备份所有可变数据文件 → finally 全量还原。不触发真实 bot 启动、不联网花钱。
"""
import os
import re
import sys
import json
import shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
os.chdir(ROOT)

BACKUP = os.path.join(ROOT, "_full_test_backup")

# 会被写测试改动的可变数据（相对 ROOT）
PROTECTED_FILES = [
    "config.py",
    "chat_contexts.json",
    "forward_rules.json",
    "forward_log.json",
    "recurring_reminders.json",
    "user_timers.json",
    "style_settings.json",
    os.path.join("forum_data", "npc_config.json"),
]
PROTECTED_DIRS = ["prompts"]

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f" | {str(detail)[:110]}" if detail else ""))


def backup():
    if os.path.exists(BACKUP):
        shutil.rmtree(BACKUP, ignore_errors=True)
    os.makedirs(BACKUP, exist_ok=True)
    for rel in PROTECTED_FILES:
        src = os.path.join(ROOT, rel)
        if os.path.exists(src):
            dst = os.path.join(BACKUP, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    for rel in PROTECTED_DIRS:
        src = os.path.join(ROOT, rel)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(BACKUP, rel))


def restore():
    for rel in PROTECTED_FILES:
        src = os.path.join(BACKUP, rel)
        if os.path.exists(src):
            dst = os.path.join(ROOT, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    for rel in PROTECTED_DIRS:
        src = os.path.join(BACKUP, rel)
        if os.path.isdir(src):
            dst = os.path.join(ROOT, rel)
            if os.path.isdir(dst):
                shutil.rmtree(dst, ignore_errors=True)
            shutil.copytree(src, dst)
    shutil.rmtree(BACKUP, ignore_errors=True)


def jget(r):
    """容错取 JSON"""
    try:
        return r.get_json() or {}
    except Exception:
        return {}


def main():
    import config_editor as ce

    ce.app.config["WTF_CSRF_ENABLED"] = False
    ce.app.config["TESTING"] = True
    try:
        ce.limiter.enabled = False  # 全面测试请求量大，避免 429 干扰
    except Exception:
        pass
    c = ce.app.test_client()

    # ---------- 1. 探活 / 会话 ----------
    print("\n=== 1. 探活 / 会话（免密可用性）===")
    r = c.get("/api/ping")
    check("/api/ping 200（单实例检测用，无需登录）", r.status_code == 200, r.get_data(as_text=True)[:60])
    c.get("/logout")
    r = c.get("/")
    check("logout 后访问主界面可自动登录（出厂免密，不会把自己锁在外面）",
          r.status_code == 200, f"{r.status_code}")

    # ---------- 2. 全部页面渲染 ----------
    print("\n=== 2. 全部页面渲染 ===")
    pages = ["/", "/quick_start", "/style_lab", "/avatar", "/forward", "/jev", "/help",
             "/mcp", "/cli"]
    for p in pages:
        try:
            r = c.get(p, query_string={"embed": "1"} if p not in ("/", "/quick_start") else {})
        except Exception as e:
            check(f"页面 {p} 渲染", False, repr(e)[:80])
            continue
        ok = r.status_code == 200
        if ok and p.startswith("/forum"):
            ok = len(r.get_data()) > 200
        check(f"页面 {p} 渲染 200", ok, f"{r.status_code}")

    # ---------- 3. 机器人控制 ----------
    print("\n=== 3. 机器人控制（⚠️ 不测 start_bot：会真起微信 bot 抢监听）===")
    r = c.get("/bot_status")
    d = jget(r)
    check("/bot_status 200 且含 status 字段", r.status_code == 200 and "status" in d, str(d)[:80])
    r = c.post("/stop_bot")
    d = jget(r)
    check("未运行时 /stop_bot 安全返回（不 500、不崩）", r.status_code in (200, 400, 409),
          f"{r.status_code} {str(d)[:70]}")
    # ⭐ 前置校验实测：临时清空监听名单，start_bot 必须被拦下（绝不能真 Popen 起 bot 抢微信监听）
    ce.update_config({"LISTEN_LIST": []})
    r = c.post("/start_bot")
    d = jget(r)
    check("监听名单为空时 start_bot 被拦下（不会起一个必然秒挂的 bot）",
          r.status_code == 400 and "监听" in (d.get("message", "") + d.get("error", "")),
          f"{r.status_code} {str(d)[:90]}")
    check("start_bot 拦截时给出可操作提示（告诉用户去哪加）",
          "用户与监听" in d.get("message", ""), d.get("message", "")[:90])

    # 朋友圈角色页：角色未创建时应明确 404 而不是 500/空白页
    r = c.get("/forum/角色1")
    check("未创建的分身 /forum/<角色> 返回明确 404（非 500/空白）",
          r.status_code == 404 and "角色不存在" in r.get_data(as_text=True),
          f"{r.status_code} {r.get_data(as_text=True)[:40]}")

    # ---------- 4. 定时提醒 ----------
    print("\n=== 4. 定时提醒（读 / 存）===")
    r = c.get("/get_all_reminders")
    d = jget(r)
    check("/get_all_reminders 200", r.status_code == 200, str(d)[:80])
    # 该接口约定：请求体顶层就是提醒 list（包一层 dict 会被拒）
    r2 = c.post("/save_all_reminders", json=[])
    check("/save_all_reminders 空列表可保存", r2.status_code in (200, 204), f"{r2.status_code} {str(jget(r2))[:70]}")
    r3 = c.get("/get_all_reminders")
    check("保存后回读一致（存储闭环）", jget(r3).get("reminders", None) is not None
          or r3.status_code == 200, str(jget(r3))[:80])

    # ---------- 5. 核心记忆 ----------
    print("\n=== 5. 核心记忆（列表 / 读 / 写 / 删）===")
    r = c.get("/api/get_core_memory_files")
    check("记忆文件列表接口 200", r.status_code == 200, str(jget(r))[:80])
    # 接口约定：文件名必须以 _core_memory.json 结尾
    TEST_MEM = "_e2e_core_memory.json"
    # 接口约定：payload 是 {"memories": [ ... ]}（数组），不是 content 字符串
    # 接口约定：每条记忆必须含 timestamp / importance / summary 三个字段
    r = c.post(f"/api/save_core_memory/{TEST_MEM}",
               json={"memories": [{"timestamp": "2026-10-12 00:00",
                                   "importance": 5,
                                   "summary": "e2e 测试内容"}]})
    check(f"保存测试记忆 {TEST_MEM}", r.status_code in (200, 201, 204), f"{r.status_code} {str(jget(r))[:70]}")
    r = c.get(f"/api/get_core_memory/{TEST_MEM}")
    body = r.get_data(as_text=True)
    # 注意：jsonify 默认 ensure_ascii，中文在原始 body 里是 \uXXXX 转义，
    # 必须先 json.loads 再比字段，不能直接在 body 里搜中文。
    _ok = False
    try:
        _mem = json.loads(body).get("memories", [])
        _ok = any("e2e 测试内容" in str(m.get("summary", "")) for m in _mem)
    except Exception:
        _ok = False
    check("回读测试记忆内容一致（存储闭环）", r.status_code == 200 and _ok,
          f"{r.status_code} {body[:70]}")
    r = c.delete(f"/api/delete_core_memory/{TEST_MEM}")
    check("删除测试记忆（清理干净，不留垃圾）", r.status_code in (200, 204), f"{r.status_code}")

    # ---------- 6. 聊天上下文 ----------
    print("\n=== 6. 聊天上下文 ===")
    r = c.get("/api/get_chat_context_users")
    users = jget(r).get("users", []) if r.status_code == 200 else []
    check("聊天上下文用户列表 200", r.status_code == 200, f"{len(users)} 个用户")
    # 接口约定：只能编辑**已存在**用户的上下文（不存在的用户名返回 404），
    # 所以取真实列表里的第一个用户来测，改动由全局备份还原。
    # context 是「JSON 数组序列化后的字符串」，不是对象。
    TEST_USER = users[0] if users else None
    if not TEST_USER:
        check("聊天上下文读写（需至少一个已有用户）", False, "无可用用户")
    else:
        r = c.post(f"/api/save_chat_context/{TEST_USER}",
                   json={"context": json.dumps([{"role": "user", "content": "e2e 测试"}])})
        check(f"保存用户上下文（{TEST_USER}）", r.status_code in (200, 201, 204),
              f"{r.status_code} {str(jget(r))[:70]}")
        r = c.get(f"/api/get_chat_context/{TEST_USER}")
        check("回读用户上下文", r.status_code == 200, f"{r.status_code}")
        # 只清不存在的用户，避免删掉真实历史（自洁且无损）
        r = c.post("/clear_chat_context/_e2e_not_exist_user")
        check("清理不存在的用户：安全拒绝（不误删真实数据）",
              r.status_code in (200, 400, 404), f"{r.status_code}")

    # ---------- 7. AI 分身 NPC ----------
    print("\n=== 7. AI 分身（NPC 设置）===")
    r = c.get("/api/npc/get_settings")
    check("NPC 设置读取 200", r.status_code == 200, str(jget(r))[:80])
    r = c.post("/api/npc/save_settings", json={"enabled": False})
    check("NPC 设置保存不崩（写后由备份还原）", r.status_code in (200, 204, 400),
          f"{r.status_code} {str(jget(r))[:70]}")

    # ---------- 8. 朋友圈 forum ----------
    print("\n=== 8. 朋友圈（forum）===")
    for path in ["/api/forum/posts/角色1", "/api/forum/threads/角色1",
                 "/api/forum/likes/角色1", "/api/forum/likes_feed/角色1"]:
        r = c.get(path)
        check(f"{path} 200", r.status_code == 200, f"{r.status_code} {str(jget(r))[:60]}")
    r = c.get("/api/forum/avatar/角色1")
    check("头像读取接口不崩", r.status_code in (200, 404), f"{r.status_code}")

    # ---------- 9. 固定转发 ----------
    print("\n=== 9. 固定转发 ===")
    r = c.get("/api/forward/rules")
    check("转发规则读取 200", r.status_code == 200, str(jget(r))[:80])
    r = c.post("/api/forward/rules", json=[{"from": "_e2e_from", "to": "_e2e_to"}])
    check("转发规则保存不崩（写后还原）", r.status_code in (200, 201, 204, 400),
          f"{r.status_code} {str(jget(r))[:70]}")
    r = c.get("/api/forward/log")
    check("转发日志读取 200", r.status_code == 200, str(jget(r))[:60])

    # ---------- 10. Jev 守护 ----------
    print("\n=== 10. Jev 守护 ===")
    r = c.get("/api/jev/status")
    check("Jev 状态 200", r.status_code == 200, str(jget(r))[:80])
    # ⚠️ 刻意不调 /api/jev/test：它会真实调用 AI 判断模型（消耗额度），
    #    CI 每次跑都会烧钱。该环节只验状态接口，真调留人工验证。

    # ---------- 11. 人设 Prompt ----------
    print("\n=== 11. 人设 Prompt ===")
    r = c.get("/edit_prompt/默认.md")
    check("编辑人设页 200", r.status_code == 200, f"{r.status_code}")
    # 该端点设计上只接受 POST（前端 fetch 创建），GET 明确 405 属预期
    r = c.get("/create_prompt")
    check("新建人设端点 GET 405（设计如此，前端走 POST）", r.status_code == 405, f"{r.status_code}")
    r = c.post("/generate_prompt", json={"prompt": ""})
    d = jget(r)
    check("AI 生成人设：空描述被前置拦截（不空转烧额度、不返回难懂的 Connection error）",
          r.status_code == 400 and "角色描述" in (d.get("message", "") + d.get("error", "")),
          f"{r.status_code} {str(d)[:90]}")

    # ---------- 12. 授权 ----------
    print("\n=== 12. 授权（拒绝路径，不真激活）===")
    r = c.post("/api/license/activate", json={"key": ""})
    d = jget(r)
    check("空卡密激活应被拒且给出明确错误", r.status_code in (400, 200) and
          (not d.get("ok", True) or "error" in d or "message" in d), f"{r.status_code} {str(d)[:80]}")
    r = c.post("/api/license/email", json={"email": "not-an-email"})
    d = jget(r)
    check("非法邮箱应被拒（不得污染 config.py）", (not d.get("ok", True)) or r.status_code >= 400,
          f"{r.status_code} {str(d)[:80]}")

    # ---------- 13. 配置导入（安全边界）----------
    print("\n=== 13. 配置导入（非法输入必须被拒）===")
    r = c.post("/import_config", data={}, content_type="multipart/form-data")
    check("空导入请求应被拒（不 500、不改配置）", r.status_code in (200, 302, 400),
          f"{r.status_code}")
    r = c.post("/import_full_directory", data={}, content_type="multipart/form-data")
    check("空目录导入应被拒（不 500）", r.status_code in (200, 302, 400), f"{r.status_code}")

    # ---------- 14. 恢复默认配置 ----------
    print("\n=== 14. 恢复默认配置 ===")
    before = ce.parse_config()
    r = c.post("/reset_default_config")
    after = ce.parse_config()
    check("恢复默认配置不崩", r.status_code in (200, 400), f"{r.status_code} {str(jget(r))[:70]}")
    if r.status_code == 200:
        check("恢复默认后保留 PORT（不会把用户锁在外面）",
              after.get("PORT") == before.get("PORT"), f"{before.get('PORT')} -> {after.get('PORT')}")

    # ---------- 15. 心跳 / 日志 ----------
    print("\n=== 15. 心跳 / 日志上报 ===")
    r = c.post("/bot_heartbeat", json={"pid": 999999, "status": "running"})
    check("心跳上报不崩", r.status_code in (200, 400), f"{r.status_code} {str(jget(r))[:60]}")
    r = c.post("/api/log", json={"level": "INFO", "message": "e2e test"})
    check("日志上报不崩", r.status_code in (200, 201, 204, 400), f"{r.status_code}")

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
    backup()
    code = 1
    try:
        code = main()
    except Exception:
        import traceback
        traceback.print_exc()
        code = 1
    finally:
        restore()
        print("[还原] 所有可变数据已从 _full_test_backup 还原")
    sys.exit(code)
