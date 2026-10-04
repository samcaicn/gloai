# -*- coding: utf-8 -*-
"""AI 分身（主人侧）+ 设备唯一 ID 回归测试。

覆盖：
  1) 设备 ID：稳定、可重算（删缓存后不变）、隐私（缓存里不含原始硬件标识）、
     数据目录在安装目录之外（卸载不丢）
  2) 主人侧 persona：身份/设置读写、提示词注入（关闭时零影响）、教学聊天、导入导出
  3) 无访客残留：persona / 模板里不出现访客/名片/接管相关代码
  4) 页面可达：模板能编译、导航里有入口、bot.py 有注入点
"""
import os
import sys
import json
import shutil
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PASS = FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {extra}")


print("=== 1) device_id：稳定性 / 可重算 / 隐私 ===")
import device_id

d1 = device_id.get_device_id()
d2 = device_id.get_device_id()
check("设备 ID 形如 dev_ 开头", d1.startswith("dev_"), d1)
check("多次调用稳定一致", d1 == d2, f"{d1} != {d2}")
check("长度合理(32位hash)", len(d1) == 36, d1)

# 可重算：删除缓存后按硬件算出的 ID 必须完全一致
cache = os.path.join(device_id.stable_data_dir(), "device.json")
backup = None
if os.path.exists(cache):
    with open(cache, "r", encoding="utf-8") as f:
        backup = f.read()
device_id.reset()
d3 = device_id.get_device_id()
check("删缓存后重算仍为同一 ID（卸载重装不丢）", d1 == d3, f"{d1} != {d3}")
if backup is not None:
    with open(cache, "w", encoding="utf-8") as f:
        f.write(backup)

# 隐私：缓存文件里不得出现任何原始硬件标识 / 主机名 / MAC
with open(cache, "r", encoding="utf-8") as f:
    raw_cache = f.read()
leaks = []
try:
    import winreg
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography") as k:
        guid, _ = winreg.QueryValueEx(k, "MachineGuid")
    if guid and guid in raw_cache:
        leaks.append("MachineGuid")
except Exception:
    pass
import platform
import uuid
if platform.node() and platform.node() in raw_cache:
    leaks.append("hostname")
if str(uuid.getnode()) in raw_cache:
    leaks.append("MAC(mac)")
check("缓存不含原始硬件标识/主机名/MAC", not leaks, f"泄漏={leaks}")
check("缓存只含哈希等元信息", "device_id" in raw_cache and "signal_count" in raw_cache)

# 数据目录必须在安装目录之外（否则卸载会被删）
install_dir = os.path.dirname(os.path.abspath(__file__))
data_dir = os.path.normcase(os.path.normpath(device_id.stable_data_dir()))
inst = os.path.normcase(os.path.normpath(install_dir))
check("数据目录不在安装目录内（卸载不丢）", not data_dir.startswith(inst), f"{data_dir} vs {inst}")
check("数据目录存在", os.path.isdir(data_dir), data_dir)

print("=== 2) persona：主人侧读写（用临时目录，不污染真实数据）===")
import persona

_tmp = tempfile.mkdtemp(prefix="weauto-persona-test-")
persona._PATH = os.path.join(_tmp, "persona.json")

d0 = persona.load()
check("默认身份为空", all(not str(v).strip() for v in d0["identity"].values()))
check("默认关闭人设注入", d0["settings"]["enabled"] is False)
check("未配置时注入为空（零影响）", persona.get_injection() == "")

persona.save_identity({"nameCN": "张三", "title": "品牌总监", "company": "某酒业",
                       "city": "成都", "about": "说话很直接"})
ident = persona.identity()
check("身份已保存", ident["nameCN"] == "张三" and ident["company"] == "某酒业", ident)
check("身份落盘", os.path.exists(persona._PATH))

check("关闭时仍不注入", persona.get_injection() == "")
persona.save_settings({"enabled": "1", "chatPurpose": "商务对接"})
check("设置已保存并启用", persona.settings()["enabled"] is True)

inj = persona.get_injection()
check("启用后注入非空", bool(inj.strip()))
check("注入含身份信息", "张三" in inj and "某酒业" in inj)
check("注入含对话目的", "商务对接" in inj)
check("注入含分身标题", "AI 分身" in inj)

persona.add_chat("user", "我一般这么回客户：价格好说")
persona.add_chat("assistant", "记下了，你习惯先聊量再谈价")
check("教学聊天已记录", len(persona.chat()) == 2)
check("统计：主人消息 1 条", persona.stats()["owner_msgs"] == 1, persona.stats())

# 导出 / 导入 往返
exported = persona.export_json()
persona.reset_all()
check("清空后身份为空", persona.identity()["nameCN"] == "")
ok, msg = persona.import_json(exported)
check("导入成功", ok, msg)
check("导入后身份还原", persona.identity()["nameCN"] == "张三", persona.identity())
check("导入后设置还原", persona.settings()["chatPurpose"] == "商务对接")

ok2, msg2 = persona.import_json("不是JSON")
check("非法 JSON 被拒绝", (not ok2) and "JSON" in msg2, msg2)

persona.clear_chat()
check("清空教学聊天", len(persona.chat()) == 0)
shutil.rmtree(_tmp, ignore_errors=True)

print("=== 3) 无访客残留 ===")
src = open(os.path.join(install_dir, "persona.py"), encoding="utf-8").read()
# 只查代码标识符（不查中文词：文档字符串里会写「不含名片分享」这类说明）
for bad in ("visitor", "visitorOpenid", "cardId", "?card=", "humanMode",
            "pollSession", "startPoll", "owner/session", "toggle_mode"):
    check(f"persona.py 不含 {bad}", bad not in src)

tpl_path = os.path.join(install_dir, "templates", "avatar.html")
tpl = open(tpl_path, encoding="utf-8").read()
for bad in ("访客消息", "visitor", "名片链接", "我来接", "交还 AI"):
    check(f"avatar.html 不含 {bad}", bad not in tpl)

print("=== 4) 页面可达性 ===")
from jinja2 import Environment, FileSystemLoader
env = Environment(loader=FileSystemLoader(os.path.join(install_dir, "templates")))
try:
    env.get_template("avatar.html")
    check("avatar.html 可被 Jinja 编译", True)
except Exception as e:
    check("avatar.html 可被 Jinja 编译", False, str(e))

nav = open(os.path.join(install_dir, "templates", "config_editor.html"), encoding="utf-8").read()
check("导航里有 AI 分身入口", "url_for('avatar_page')" in nav)

ce = open(os.path.join(install_dir, "config_editor.py"), encoding="utf-8").read()
check("config_editor 注册 /avatar 路由", "@app.route('/avatar'" in ce)
check("路由带 login_required", "/avatar', methods=['GET', 'POST'])\n@login_required" in ce)

bot = open(os.path.join(install_dir, "bot.py"), encoding="utf-8").read()
check("bot.py 注入分身人设", "_persona.get_injection()" in bot)

print()
print(f"结果：{PASS} 通过 / {FAIL} 失败")
sys.exit(1 if FAIL else 0)
