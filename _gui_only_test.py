# -*- coding: utf-8 -*-
"""GUI-only 清理回归测试：1) noconsole stdio 兜底  2) Jev 零配置  3) 熔断器

运行：./.venv_bot/Scripts/python.exe _gui_only_test.py
"""
# GUI-only：本脚本自己也不该往控制台吐东西（保持与产品一致）
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import weauto_stdio  # noqa: E402

weauto_stdio.ensure_stdio()

import json  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402

OK = 0
FAIL = 0


def check(desc, cond, extra=""):
    global OK, FAIL
    if cond:
        OK += 1
        print("  PASS  %s" % desc)
    else:
        FAIL += 1
        print("  FAIL  %s  %s" % (desc, extra))


def section(title):
    print("\n=== %s ===" % title)


# ---------------------------------------------------------------- 1. stdio
section("1. weauto_stdio：无控制台环境下 print / StreamHandler 不炸")

# 模拟 noconsole：把三个流都置 None。
# 注意 1：此时 ensure_stdio() 装上去的是 devnull，之后所有 print 都会消失，
#        所以本节的检查结果先攒到 _s1，最后统一回显（否则看不到 PASS/FAIL）。
# 注意 2：logging.StreamHandler.__init__ 会在**构造那一刻**把 sys.stderr 绑成
#        自己的 stream。所以要复现真实事故（noconsole 下 bot 建 handler 时
#        sys.stderr 还是 None），必须在 ensure_stdio **之前**构造，否则测不到。
_orig = (sys.stdin, sys.stdout, sys.stderr)
sys.stdin = sys.stdout = sys.stderr = None
weauto_stdio._done = False          # 绕开幂等保护，强制重跑

import logging  # noqa: E402

def _s1_check(desc, cond, extra=""):
    _s1.append((desc, bool(cond), extra))

_s1 = []

# ① 先在「流为 None」的状态下探清 CPython 的真实行为。
#    实测结论（3.10）：StreamHandler(None) 会回落到当时的 sys.stderr（也是 None），
#    首次 emit 走 Handler.handleError 被静默吞掉 —— 不会掀翻 bot。
#    但 sys.stdout.write() 这类绕过 print/logging 的写法会真的抛。
#    本模块的价值就是把这些「隐式静默」变成「显式 devnull 兜底」。
_s1 = []

_write_ok = True
_write_err = ""
try:
    sys.stdout.write("")            # 绕过 print，直接写流
except Exception as e:
    _write_ok = False
    _write_err = "%s: %s" % (type(e).__name__, e)
_s1_check("流为 None 时 sys.stdout.write() 会抛（这才是需要兜底的真风险）",
          not _write_ok, _write_err)

# StreamHandler 在 None 流下不抛（记录为事实，避免以后重复误判）
_legacy_ok = True
_legacy_err = ""
try:
    lg_legacy = logging.getLogger("_legacy")
    lg_legacy.handlers.clear()
    lg_legacy.propagate = False
    lg_legacy.addHandler(logging.StreamHandler())
    lg_legacy.error("emit into None stream")
except Exception as e:
    _legacy_ok = False
    _legacy_err = "%s: %s" % (type(e).__name__, e)
_s1_check("StreamHandler 在 None 流下由 logging 内部静默吞掉（已实测，非假设）",
          _legacy_ok, _legacy_err)

attached = weauto_stdio.ensure_stdio(['WeAuto.exe'])

def c1(desc, cond, extra=""):
    _s1.append((desc, bool(cond), extra))

c1("ensure_stdio 后 sys.stdout 不是 None", sys.stdout is not None)
c1("ensure_stdio 后 sys.stderr 不是 None", sys.stderr is not None)
c1("ensure_stdio 后 sys.stdin 不是 None", sys.stdin is not None)
c1("无父控制台时 attached 为 False（不凭空造黑框）", attached is False)
c1("兜底流被正确标记 has_console()=False", weauto_stdio.has_console() is False)

# 兜底的核心价值：绕过 print 的直接写流也不再抛
_w_ok = True
_w_err = ""
try:
    sys.stdout.write("")
    sys.stderr.write("")
except Exception as e:
    _w_ok = False
    _w_err = "%s: %s" % (type(e).__name__, e)
c1("兜底后 sys.stdout.write() 不再抛（= weauto_stdio 的实际价值）", _w_ok, _w_err)

# print 必须不抛（输出进 devnull，无所谓）
print_ok = True
try:
    print("这条会走 devnull，不应抛异常")
except Exception as e:
    print_ok = False
    _s1.append(("print 抛异常: %s" % e, False, ""))
c1("print() 在 noconsole 兜底下不抛 AttributeError", print_ok)

# ② 修复后的写法：显式传流（bot.py / vendor logger 现在就是这么做的）必须正常
h2_ok = True
h2_err = ""
try:
    lg2 = logging.getLogger("_t2")
    lg2.handlers.clear()
    lg2.propagate = False
    lg2.addHandler(logging.StreamHandler(sys.stderr))
    lg2.error("emit 一次（丢弃到 devnull）")
except Exception as e:
    h2_ok = False
    h2_err = "%s: %s" % (type(e).__name__, e)
c1("显式传流的 StreamHandler 正常 emit（= 修复后的写法）", h2_ok, h2_err)

# 幂等
weauto_stdio.ensure_stdio(['WeAuto.exe'])
c1("ensure_stdio 可重复调用（幂等）", sys.stdout is not None)

# 还原流，再统一回显本节结果
sys.stdin, sys.stdout, sys.stderr = _orig
for _d, _ok, _x in _s1:
    check(_d, _ok, _x)


# ---------------------------------------------------------------- 2. jev 零配置
section("2. jev_guard：零配置 + 无 config.py 也能跑")

import jev_guard  # noqa: E402
import config as cfgmod  # noqa: E402

cfg_keys = {k for k in vars(cfgmod) if k.startswith("JEV") or k == "ENABLE_JEV_GUARD"}
check("config.py 已无任何 JEV_* 配置项", not cfg_keys, "-> 残留 %s" % sorted(cfg_keys))
check("config.py 仍保留 WEAUATO_CLIENT_SECRET（签名密钥，非业务参数）",
      hasattr(cfgmod, "WEAUATO_CLIENT_SECRET"))

for name in ("JEV_ENDPOINT", "JEV_TIMEOUT_SEC", "JEV_RELATIONSHIP", "JEV_CONTEXT_TURNS",
             "JEV_INJECT_GUIDANCE", "JEV_HOLD_ON_DANGER", "JEV_DANGER_HOLD_LEVEL",
             "JEV_CIRCUIT_FAILS", "JEV_MODEL_NAME"):
    check("内置常量存在：%s" % name, hasattr(jev_guard, name))

# enabled() 出厂恒 True（未熔断时）
jev_guard._CIRCUIT["fails"] = 0
jev_guard._CIRCUIT["open"] = False
check("未熔断时 enabled() == True（出厂自动运行）", jev_guard.enabled() is True)

# 读端点不再碰 config.py
urls = jev_guard._jev_candidate_urls()
check("候选端点含主域名 wetech.jukuai.net",
      any("wetech.jukuai.net" in u for u in urls), "-> %s" % urls)
check("候选端点含备用域名 weauto.safeopc.cn",
      any("weauto.safeopc.cn" in u for u in urls), "-> %s" % urls)
check("端点 path 为 /ai/jev/decisions",
      all(u.endswith("/ai/jev/decisions") for u in urls), "-> %s" % urls)

# guidance_text 依赖内置常量而非 config
g = jev_guard.guidance_text({"true_intent": "vent_anger", "danger_level": 7,
                            "she_needs": "care", "best_action": "acknowledge",
                            "should_reply_now": True, "literal_question": False,
                            "tension_resolved": False})
check("guidance_text 能产出中文指令", isinstance(g, str) and "Jev 判断模型" in g, "-> %r" % g[:60])
check("guidance_text 含危险度提示", "关系危险度：7/9" in g, "-> %r" % g)
check("guidance_text 含「话里有话」提示", "话里有话" in g)
check("guidance_text 空输入返回空串", jev_guard.guidance_text(None) == "")
check("guidance_text 非 dict 返回空串", jev_guard.guidance_text("x") == "")

# should_hold 出厂关闭
check("should_hold 出厂关闭（高危也不收声）",
      jev_guard.should_hold({"danger_level": 9}) is False)
check("should_hold 缺字段不收声",
      jev_guard.should_hold({"foo": 1}) is False)
check("should_hold(None) 不收声", jev_guard.should_hold(None) is False)

# rank 非 3 候选直接 None，不发请求
check("rank 候选数 !=3 时返回 None（不发请求）",
      jev_guard.rank("u", "hi", ["a", "b"]) is None)

# judge 空消息不请求
check("judge 空文本返回 None", jev_guard.judge("u", "   ", []) is None)


# ---------------------------------------------------------------- 3. 熔断器
section("3. 熔断器：端点连续失败后跳过判断，恢复后自动继续")

def _reset():
    with jev_guard._CIRCUIT_LOCK:
        jev_guard._CIRCUIT["fails"] = 0
        jev_guard._CIRCUIT["open"] = False

_reset()
check("初始未熔断", jev_guard._circuit_open() is False)

for i in range(jev_guard.JEV_CIRCUIT_FAILS - 1):
    jev_guard._circuit_record(False)
check("失败 %d 次仍未熔断" % (jev_guard.JEV_CIRCUIT_FAILS - 1),
      jev_guard._circuit_open() is False,
      "-> fails=%d" % jev_guard._CIRCUIT["fails"])

jev_guard._circuit_record(False)
check("失败达阈值 %d 次后熔断" % jev_guard.JEV_CIRCUIT_FAILS,
      jev_guard._circuit_open() is True)
check("熔断时 enabled() == False（跳过判断、照常回复）", jev_guard.enabled() is False)

jev_guard._circuit_record(True)
check("成功一次即完全恢复", jev_guard._circuit_open() is False)
check("恢复后 enabled() 回到 True", jev_guard.enabled() is True)
check("恢复后失败计数清零", jev_guard._CIRCUIT["fails"] == 0)

st = jev_guard.circuit_status()
check("circuit_status 可读且含阈值", st.get("threshold") == jev_guard.JEV_CIRCUIT_FAILS,
      "-> %s" % st)

# 熔断判定必须线程安全（并发记账不丢计数）
_reset()
N = 60
def _hammer():
    for _ in range(N):
        jev_guard._circuit_record(False)
ths = [threading.Thread(target=_hammer) for _ in range(6)]
[t.start() for t in ths]
[t.join() for t in ths]
check("并发 %d 次失败记账无丢失" % (N * 6), jev_guard._CIRCUIT["fails"] == N * 6,
      "-> %d" % jev_guard._CIRCUIT["fails"])
_reset()


# ---------------------------------------------------------------- 4. 自检不污染熔断
section("4. self_test 绕过熔断（手动测试不影响线上判断）")

_reset()
calls = []
_real_post = jev_guard._post_decisions
def _spy(questions, state, timeout=None, model=None, circuit=True):
    calls.append(circuit)
    return ({"danger_level": {"score": 3}}, None)
jev_guard._post_decisions = _spy
try:
    ok, msg = jev_guard.self_test()
    check("self_test 返回成功", ok is True, "-> %s" % msg)
    check("self_test 以 circuit=False 调用（不记熔断）", calls == [False], "-> %s" % calls)
    check("self_test 后熔断状态未变", jev_guard._CIRCUIT["open"] is False)

    jev_guard.judge("u", "在吗", [])
    check("judge 以 circuit=True 调用（正常参与熔断）", calls[-1] is True, "-> %s" % calls)
finally:
    jev_guard._post_decisions = _real_post
    _reset()


# ---------------------------------------------------------------- 5. Worker 联通
section("5. 真实 Worker 连通（判断服务是否真的活着）")
try:
    ok, msg = jev_guard.self_test(timeout=20)
    check("线上自检通过", ok, "-> %s" % msg[:200])
except Exception as e:
    check("线上自检通过", False, "-> 异常 %s: %s" % (type(e).__name__, e))


print("\n" + "=" * 56)
print("通过 %d / 失败 %d" % (OK, FAIL))
print("=" * 56)
sys.exit(1 if FAIL else 0)
