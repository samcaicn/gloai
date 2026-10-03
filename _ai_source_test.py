# -*- coding: utf-8 -*-
"""AI 来源（Cloudflare Workers AI ↔ 用户自填大模型）路由回归测试。

覆盖：
  1. guard.use_worker_ai() 的 True/False/'auto' 三态判定
  2. guard.ai_endpoint() 在自填模式下必须返回 None（不碰网关、不用卡密）
  3. config_editor.ai_source_mode() / resolve_ai_opts() 与 guard 同源一致
  4. bot.py 的 _resolve_api_opts() 回落逻辑（子功能继承主模型）
  5. 不发起任何真实网络请求（mock 掉 guard 的 _post 与 _read_cache）

运行： ./.venv_bot/Scripts/python.exe _ai_source_test.py
"""
import os
import sys
import types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PASS, FAIL = [], []


def check(name, cond, extra=""):
    if cond:
        PASS.append(name)
        print("  [PASS] %s %s" % (name, extra))
    else:
        FAIL.append(name)
        print("  [FAIL] %s %s" % (name, extra))


# ── 造一个假的 config 模块，guard._cfg 通过它读配置 ──
def make_config(**kw):
    m = types.ModuleType("config")
    m.CREEM_WORKER_URL = kw.pop("worker_url", "https://wetech.jukuai.net")
    m.LICENSE_GUARD_ENABLED = kw.pop("guard_enabled", True)
    m.USE_WORKER_AI = kw.pop("USE_WORKER_AI", "auto")
    m.DEEPSEEK_BASE_URL = kw.pop("DEEPSEEK_BASE_URL", "https://wetech.jukuai.net/ai/v1")
    m.DEEPSEEK_API_KEY = kw.pop("DEEPSEEK_API_KEY", "sk-dummy-placeholder")
    m.CREEM_LICENSE_KEY = kw.pop("CREEM_LICENSE_KEY", "LIC-KEY-1234")
    for k, v in kw.items():
        setattr(m, k, v)
    return m


def load_guard(cfg):
    """重新加载 guard，使其绑定到指定 config，并屏蔽网络。"""
    for mod in list(sys.modules):
        if mod == "config" or mod.startswith("weauto_license"):
            del sys.modules[mod]
    import weauto_license.guard as g
    g._cfg_mod = cfg
    # 屏蔽网络与本地缓存，保证测试离线可跑
    g._post = lambda *a, **k: ({"ok": True, "instance_id": "inst-1", "expires_at": 9e9}, 200)
    g._read_cache = lambda: {"instance_id": "inst-1", "expire_at": 9e9, "tier": "normal"}
    g._write_cache = lambda d: None
    g.get_machine_id = lambda: "machine-test"
    return g


GATEWAY = "https://wetech.jukuai.net/ai/v1"
REAL_KEY = "sk-abc1234567890xyz"
VENDOR = "https://api.deepseek.com/v1"

print("\n=== 1. guard.use_worker_ai() 三态判定 ===")
cases = [
    # (说明, config kwargs, 期望)
    ("USE_WORKER_AI=True 强制官方（即使填了别家地址+真 key）",
     dict(USE_WORKER_AI=True, DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY), True),
    ("USE_WORKER_AI=False 强制自填（即使地址是网关）",
     dict(USE_WORKER_AI=False, DEEPSEEK_BASE_URL=GATEWAY, DEEPSEEK_API_KEY=REAL_KEY), False),
    ("auto + 网关地址 + 占位 key -> 官方 Workers AI",
     dict(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL=GATEWAY, DEEPSEEK_API_KEY="sk-dummy-placeholder"), True),
    ("auto + 网关地址 + 空 key -> 官方 Workers AI",
     dict(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL=GATEWAY, DEEPSEEK_API_KEY=""), True),
    ("auto + 别家地址 + 真 key -> 自填，不走 Workers AI",
     dict(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY), False),
    ("auto + 别家地址 + 占位 key -> 仍算官方（key 不真，没法用）",
     dict(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY="sk-dummy-placeholder"), True),
    ("auto + 空 base_url + 真 key -> 官方（没地址可用）",
     dict(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL="", DEEPSEEK_API_KEY=REAL_KEY), True),
    ("字符串 'True' 也认（表单写入的形态）",
     dict(USE_WORKER_AI='True', DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY), True),
    ("字符串 'False' 也认",
     dict(USE_WORKER_AI='False', DEEPSEEK_BASE_URL=GATEWAY, DEEPSEEK_API_KEY=REAL_KEY), False),
]
for desc, kwargs, expect in cases:
    g = load_guard(make_config(**kwargs))
    got = g.use_worker_ai()
    check(desc, got == expect, "-> got=%s expect=%s" % (got, expect))

print("\n=== 2. guard.ai_endpoint() 自填模式必须返回 None ===")
g = load_guard(make_config(USE_WORKER_AI=False, DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY))
check("自填模式 ai_endpoint() is None", g.ai_endpoint() is None, "-> %r" % (g.ai_endpoint(),))
g = load_guard(make_config(USE_WORKER_AI='auto', DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY))
check("auto 判定为自填时 ai_endpoint() is None", g.ai_endpoint() is None)
g = load_guard(make_config(USE_WORKER_AI=True))
ep = g.ai_endpoint()
check("官方模式 ai_endpoint() 指向网关 /ai/v1",
      bool(ep) and ep.get("base_url") == GATEWAY, "-> %r" % (ep,))
check("官方模式 api_key 用卡密而非占位 key",
      bool(ep) and ep.get("api_key") == "LIC-KEY-1234", "-> %r" % (ep.get("api_key") if ep else None))
check("官方模式带 X-WeAuto-Instance 头",
      bool(ep) and ep.get("headers", {}).get("X-WeAuto-Instance") == "inst-1")
g = load_guard(make_config(guard_enabled=False))
# 注意：本仓库存在 weauto_license/_release.py（RELEASE=True），发行版固杀下门禁恒开，
# 所以 guard_enabled() 为 True、ai_endpoint() 仍返回端点 —— 这正是「反破解固杀」的设计。
# 这里断言的是：即便 config 写 LICENSE_GUARD_ENABLED=False，端点也不会被悄悄放行成 None。
if getattr(g, "RELEASE_BUILD", False):
    check("发行版固杀：config 关不掉门禁，ai_endpoint() 仍返回端点", g.ai_endpoint() is not None)
else:
    check("门禁关闭（开发态）时 ai_endpoint() is None", g.ai_endpoint() is None)

print("\n=== 3. config_editor.ai_source_mode / resolve_ai_opts 与 guard 一致 ===")
import config_editor as ce
for desc, kwargs, expect in cases:
    mode = ce.ai_source_mode(vars(make_config(**kwargs)))
    want = "worker" if expect else "custom"
    check(desc.replace("-> got", "[ce]"), mode == want, "-> got=%s expect=%s" % (mode, want))

# resolve_ai_opts：官方模式 -> 网关 + 卡密
k, b = ce.resolve_ai_opts(vars(make_config(USE_WORKER_AI=True)))
check("resolve_ai_opts 官方模式 = 网关+卡密", b == GATEWAY and k == "LIC-KEY-1234", "-> %s | %s" % (k, b))

# resolve_ai_opts：自填模式 -> 用户自己的 key + 地址
k, b = ce.resolve_ai_opts(vars(make_config(USE_WORKER_AI=False, DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY)))
check("resolve_ai_opts 自填模式 = 自己的 key+地址", k == REAL_KEY and b == VENDOR, "-> %s | %s" % (k, b))

# 自填模式 + 子功能（识图）未单配 -> 应继承主模型
k, b = ce.resolve_ai_opts(
    vars(make_config(USE_WORKER_AI=False, DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY)),
    sub_key="", sub_base="")
check("自填模式子功能未单配 -> 继承主模型 key+地址", k == REAL_KEY and b == VENDOR, "-> %s | %s" % (k, b))

# 自填模式 + 子功能显式给了别家地址 -> 用子功能的
k, b = ce.resolve_ai_opts(
    vars(make_config(USE_WORKER_AI=False, DEEPSEEK_BASE_URL=VENDOR, DEEPSEEK_API_KEY=REAL_KEY)),
    sub_key="sk-silicon999", sub_base="https://api.siliconflow.cn/v1")
check("自填模式子功能显式配置 -> 用子功能的", k == "sk-silicon999" and b == "https://api.siliconflow.cn/v1",
      "-> %s | %s" % (k, b))

print("\n=== 4. bot.py _resolve_api_opts 回落逻辑（纯函数抽取测试）===")
# 直接复刻 bot.py 的实现做等价验证（避免 import bot 触发微信依赖）
GW_SUF = ('/ai/v1', '/ai')
MAIN_KEY, MAIN_BASE = REAL_KEY, VENDOR

def resolve_local(local_key, local_base, ai_proxy):
    if ai_proxy:
        return ai_proxy.get("api_key") or local_key, ai_proxy.get("base_url") or local_base
    k = (local_key or "").strip()
    b = (local_base or "").strip()
    if (not k) and (not b or b.rstrip('/').endswith(GW_SUF)):
        return MAIN_KEY, MAIN_BASE
    if not k:
        k = MAIN_KEY
    if not b:
        b = MAIN_BASE
    return k, b

proxy = {"api_key": "LIC-KEY-1234", "base_url": GATEWAY, "headers": {"X": "1"}}
k, b = resolve_local("", "", proxy)
check("走网关：子功能空配置 -> 网关+卡密", k == "LIC-KEY-1234" and b == GATEWAY, "-> %s | %s" % (k, b))
k, b = resolve_local("sk-silicon999", "https://api.siliconflow.cn/v1", proxy)
check("走网关：忽略子功能本地配置（统一由 Worker 决定上游）", k == "LIC-KEY-1234" and b == GATEWAY)
k, b = resolve_local("", "", {})
check("自填：子功能空配置 -> 继承主模型", k == MAIN_KEY and b == MAIN_BASE, "-> %s | %s" % (k, b))
k, b = resolve_local("", GATEWAY, {})
check("自填：子功能 key 空且 base 仍是网关 -> 继承主模型",
      k == MAIN_KEY and b == MAIN_BASE, "-> %s | %s" % (k, b))
k, b = resolve_local("sk-moon", "https://api.moonshot.cn/v1", {})
check("自填：子功能显式配置 -> 用子功能的", k == "sk-moon" and b == "https://api.moonshot.cn/v1")

print("\n=== 5. 出厂默认配置必须是走官方 Workers AI ===")
import config as real_cfg
check("config.USE_WORKER_AI 出厂为 'auto'（不改变默认体验）",
      getattr(real_cfg, "USE_WORKER_AI", None) == 'auto', "-> %r" % getattr(real_cfg, "USE_WORKER_AI", None))
check("config.MODEL 出厂为 Workers AI 模型",
      str(getattr(real_cfg, "MODEL", "")).startswith("@cf/"), "-> %r" % getattr(real_cfg, "MODEL", None))
check("出厂配置判定为 worker 模式", ce.ai_source_mode(vars(real_cfg)) == "worker")

print("\n" + "=" * 56)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
if FAIL:
    for f in FAIL:
        print("  FAILED: %s" % f)
    sys.exit(1)
print("全部通过 ✅")
