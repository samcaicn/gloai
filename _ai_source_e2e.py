# -*- coding: utf-8 -*-
"""端到端验证：自填模式真的直连用户供应商，不碰网关、不带卡密。

起两个本地 mock OpenAI 服务：
  A = 假网关（模拟 wetech.jukuai.net）：如果请求打到它，说明还在走 Workers AI 链路（错）
  B = 假用户供应商（模拟 api.deepseek.com）：请求应该打到这里，且 Authorization 是用户 key

跑两种配置，各发一次请求，断言落点正确。
"""
import json
import os
import sys
import threading
import time
import types
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HITS = {"gateway": [], "vendor": []}


def make_handler(tag):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            n = int(self.headers.get("content-length") or 0)
            body = self.rfile.read(n).decode("utf-8", "replace")
            HITS[tag].append({
                "path": self.path,
                "auth": self.headers.get("Authorization", ""),
                "instance": self.headers.get("X-WeAuto-Instance", ""),
                "body": body,
            })
            resp = json.dumps({
                "id": "x", "object": "chat.completion", "created": int(time.time()),
                "model": "mock",
                "choices": [{"index": 0, "message": {"role": "assistant",
                            "content": "REPLY_FROM_" + tag}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
    return H


def serve(port, tag):
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(tag))
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


def build_config(base_url, key, use_worker_ai):
    m = types.ModuleType("config")
    m.CREEM_WORKER_URL = "http://127.0.0.1:%d" % GATEWAY_PORT
    m.LICENSE_GUARD_ENABLED = True
    m.USE_WORKER_AI = use_worker_ai
    m.DEEPSEEK_BASE_URL = base_url
    m.DEEPSEEK_API_KEY = key
    m.CREEM_LICENSE_KEY = "LIC-KEY-9999"
    return m


GATEWAY_PORT = 18901
VENDOR_PORT = 18902

PASS, FAIL = [], []


def check(name, cond, extra=""):
    (PASS if cond else FAIL).append(name)
    print("  [%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))


def run_case(title, use_worker_ai, expect_tag, key):
    print("\n=== %s ===" % title)
    HITS["gateway"].clear()
    HITS["vendor"].clear()

    for mod in [x for x in sys.modules if x == "config" or x.startswith("weauto_license")]:
        del sys.modules[mod]
    import weauto_license.guard as g
    g._cfg_mod = build_config("http://127.0.0.1:%d/ai/v1" % VENDOR_PORT
                              if expect_tag == "vendor" else "http://127.0.0.1:%d/ai/v1" % GATEWAY_PORT,
                              key, use_worker_ai)
    g._read_cache = lambda: {"instance_id": "inst-e2e", "expire_at": 9e9, "tier": "normal"}
    g._write_cache = lambda d: None
    g.get_machine_id = lambda: "m-e2e"

    ep = g.ai_endpoint() or {}
    if ep:
        ep["base_url"] = ep["base_url"].replace(str(GATEWAY_PORT), str(GATEWAY_PORT))
    # 用 bot.py 的 _resolve_api_opts 同款逻辑拿到最终 (key, base)
    from openai import OpenAI
    main_key = ep.get("api_key") or key
    main_base = ep.get("base_url")
    if not main_base:
        main_base = "http://127.0.0.1:%d/ai/v1" % VENDOR_PORT
    headers = ep.get("headers") or None
    cli = OpenAI(api_key=main_key, base_url=main_base, default_headers=headers, timeout=10)
    r = cli.chat.completions.create(model="m", messages=[{"role": "user", "content": "hi"}])
    text = r.choices[0].message.content
    print("   base=%s key=%s headers=%s" % (main_base, main_key, headers))
    print("   reply=%s" % text)
    check("请求落到 %s" % expect_tag, ("REPLY_FROM_" + expect_tag) in text, "-> %s" % text)
    other = "vendor" if expect_tag == "gateway" else "gateway"
    check("%s 端点零请求" % other, len(HITS[other]) == 0, "-> hits=%d" % len(HITS[other]))
    return HITS[expect_tag][0] if HITS[expect_tag] else None


print("启动 mock 服务…")
sg = serve(GATEWAY_PORT, "gateway")
sv = serve(VENDOR_PORT, "vendor")
time.sleep(0.3)

# 场景 1：自填模式（USE_WORKER_AI=False）→ 必须打用户供应商，Authorization 是用户 key
h = run_case("自填模式：USE_WORKER_AI=False + 自家 base_url/key", False, "vendor", "sk-usermodel-123")
if h:
    check("Authorization 用的是用户自己的 key", h["auth"] == "Bearer sk-usermodel-123", "-> %s" % h["auth"])
    check("不带卡密（LIC-KEY 不出现）", "LIC-KEY-9999" not in h["auth"])
    check("不带 X-WeAuto-Instance（不参与设备绑定）", h["instance"] == "", "-> %r" % h["instance"])

# 场景 2：官方模式（USE_WORKER_AI=True）→ 打网关，Authorization 是卡密
h = run_case("官方模式：USE_WORKER_AI=True（Cloudflare Workers AI 链路）", True, "gateway", "sk-dummy-placeholder")
if h:
    check("Authorization 用的是卡密", h["auth"] == "Bearer LIC-KEY-9999", "-> %s" % h["auth"])
    check("带 X-WeAuto-Instance 设备头", h["instance"] == "inst-e2e", "-> %r" % h["instance"])

print("\n" + "=" * 56)
print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
sg.shutdown()
sv.shutdown()
if FAIL:
    for f in FAIL:
        print("  FAILED: %s" % f)
    sys.exit(1)
print("端到端全部通过 ✅")
