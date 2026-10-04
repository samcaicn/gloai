# -*- coding: utf-8 -*-
"""cloud_sync.py 客户端 vs 本地 mock Worker（与 cf_worker.js /device/* 契约一致）。

验证点：
  - HMAC 签名（与 Worker deviceAuth ③ 同算法）能过
  - hello 拉回套餐/人格版本
  - push 上传后 rev 增长；pull 回灌本地
  - 双向 sync 决策（本地新->push，云端新->pull）
  - 同一 wxKey 在「不同设备」上读到同一份人格（跨端互通核心）
  - 重装（本地无人格）从云端恢复
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SECRET = "testsecret"
# 让 cloud_sync._cfg 读到密钥
os.environ["WEAUTO_WEAUATO_CLIENT_SECRET"] = SECRET

import device_id
import persona
import cloud_sync

# 全部落到临时目录，不污染真实数据
TMP = tempfile.mkdtemp(prefix="cloudsync_test_")
device_id.stable_data_dir = lambda: TMP
cloud_sync._state_path = lambda: os.path.join(TMP, "cloud_sync.json")

# 指向本地 mock
_port = {"v": 0}
cloud_sync.worker_url = lambda: "http://127.0.0.1:%d" % _port["v"]


# ----------------------------------------------------------------- mock Worker
def _now_ms():
    return int(time.time() * 1000)


def _verify(req, body_bytes):
    """复刻 cf_worker deviceAuth ③：X-WeAuto-Ts/Nonce/Sig = HMAC(method,path,ts,nonce,body)。"""
    ts = req.headers.get("X-WeAuto-Ts", "")
    nonce = req.headers.get("X-WeAuto-Nonce", "")
    sig = req.headers.get("X-WeAuto-Sig", "")
    path = urllib.parse.urlparse(req.path).path
    msg = (req.command + "\n" + path + "\n" + ts + "\n" + nonce + "\n").encode("utf-8") + body_bytes
    exp = hmac.new(SECRET.encode(), msg, hashlib.sha256).hexdigest()
    return hmac.compare_digest(exp, sig), ts


class H(BaseHTTPRequestHandler):
    store = {}  # key -> {persona, rev, source}
    plans = {}  # deviceId -> tier

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/device/persona"):
            ok, _ = _verify(self, b"")
            if not ok:
                return self._send(401, {"ok": False, "error": "bad_sig"})
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            wx = (q.get("wx") or [""])[0]
            dev = (q.get("device") or [""])[0]
            key = wx if wx else "dev:" + dev
            rec = self.store.get(key)
            if not rec:
                return self._send(200, {"ok": True, "persona": None})
            return self._send(200, {"ok": True, "persona": rec["persona"],
                                    "personaRev": rec["rev"], "personaSource": rec["source"]})
        return self._send(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n) if n else b""
        if self.path == "/device/hello":
            ok, _ = _verify(self, body)
            if not ok:
                return self._send(401, {"ok": False, "error": "bad_sig"})
            try:
                p = json.loads(body or b"{}")
            except Exception:
                return self._send(400, {"ok": False, "error": "bad_json"})
            # 计费/套餐（mock 默认给 basic）
            dev = p.get("deviceId", "")
            tier = self.plans.get(dev, "basic")
            key = (p.get("wxKey") or "") if p.get("wxKey") else "dev:" + dev
            rec = self.store.get(key)
            return self._send(200, {"ok": True, "tier": tier,
                                    "plan": {"name": "基础版", "quota": 100},
                                    "personaRev": rec["rev"] if rec else 0,
                                    "personaSource": rec["source"] if rec else None,
                                    "devices": [{"deviceId": dev, "platform": p.get("platform")}]})
        return self._send(404, {"ok": False, "error": "not_found"})

    def do_PUT(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n) if n else b""
        if self.path == "/device/persona":
            ok, _ = _verify(self, body)
            if not ok:
                return self._send(401, {"ok": False, "error": "bad_sig"})
            p = json.loads(body or b"{}")
            wx = p.get("wxKey") or ""
            dev = p.get("deviceId", "")
            key = wx if wx else "dev:" + dev
            rev = int(p.get("rev") or 0)
            existing = self.store.get(key)
            # 复刻 Worker：rev 小于服务端则拒绝（旧版本不能覆盖新版本）
            if existing and rev < existing["rev"]:
                return self._send(409, {"ok": False, "error": "stale_rev",
                                        "currentRev": existing["rev"]})
            new_rev = rev if rev else _now_ms()
            self.store[key] = {"persona": p.get("persona"), "rev": new_rev,
                               "source": p.get("platform", "unknown")}
            return self._send(200, {"ok": True, "personaRev": new_rev})
        if self.path == "/device/plan":
            ok, _ = _verify(self, body)
            if not ok:
                return self._send(401, {"ok": False, "error": "bad_sig"})
            p = json.loads(body or b"{}")
            self.plans[p.get("deviceId", "")] = "pro"
            return self._send(200, {"ok": True, "tier": "pro",
                                    "plan": {"name": "专业版", "quota": 2000}})
        return self._send(404, {"ok": False, "error": "not_found"})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
_port["v"] = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()


# ----------------------------------------------------------------- 测试
passed = failed = 0
_msgs = []


def check(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        _msgs.append("  ok   " + name)
    else:
        failed += 1
        _msgs.append("  FAIL " + name + ("  " + extra if extra else ""))


# 1) 本地人格写入
persona.save_identity({"nameCN": "张三", "title": "总监", "company": "某酒业", "city": "成都"})
persona.save_settings({"enabled": True, "personaPrompt": "说话直接", "chatPurpose": "商务"})
check("本地身份/设置写入", persona.identity()["nameCN"] == "张三")

# 2) 微信身份键
device_id.save_wx_identity("wxid_abc123", "张三昵称")
wxk = device_id.wx_key()
check("wx_key 生成", wxk.startswith("wx_") and len(wxk) == 35, wxk)
# 跨端一致性：同一 wxid 算同一 key
check("同一 wxid 跨端同 key", device_id.hash_wxid("wxid_abc123") == wxk)

# 3) hello（带签名）
r = cloud_sync.hello()
check("hello 成功", r.get("ok"), str(r))
st = cloud_sync.status()
check("状态写入 tier", st["tier"] == "basic", str(st.get("tier")))
check("状态写设备ID非空", bool(st["device_id"]))
check("微信身份已绑定", st["wx_bound"] is True)
check("归属范围=微信身份", "微信" in st["owner_scope"])

# 4) push 上传
r = cloud_sync.push(force=True)
check("push 成功", r.get("ok"), str(r))
rev_after_push = r.get("rev", 0)
check("push 返回 rev>0", rev_after_push > 0)

# 5) 重装模拟：清空本地人格，从云端 pull 恢复
persona.reset_all()
check("重装后本地身份清空", persona.identity()["nameCN"] == "")
r = cloud_sync.pull(force=True)
check("pull 从云端恢复成功", r.get("ok") and r.get("applied"), str(r))
check("恢复后身份回填", persona.identity()["nameCN"] == "张三", persona.identity()["nameCN"])

# 6) 双向 sync：本地较新 -> push。
# 说明：persona.save 会把 updated_at 改写为 now，所以要让本地明显晚于云端 —— 这里睡 1.3s 越过 1000ms 防抖。
time.sleep(1.3)
persona.save_identity({"nameCN": "张三改", "title": "总监"})
r = cloud_sync.sync(reason="manual")
check("sync 本地新->push", r.get("action") == "push", str(r))

# 7) 双向 sync：云端较新 -> pull
H.store[(wxk)] = {"persona": {"identity": {"nameCN": "云端李四"}, "settings": {"enabled": True}},
                 "rev": _now_ms() + 9999, "source": "android-apk"}
persona.save_identity({"nameCN": "本地旧", "title": "x"})
r = cloud_sync.sync(reason="manual")
check("sync 云端新->pull", r.get("action") == "pull", str(r))
check("pull 后本地=云端", persona.identity()["nameCN"] == "云端李四")

# 8) 跨端互通：另一台“设备”用同一 wxKey 读到同一份人格
other_dev = "dev_other_machine"
real_dev = device_id.get_device_id
device_id.get_device_id = lambda: other_dev
# 先确保云端有该 key 的内容（上面写的是 wxk）
# 用这台“新设备”去 pull，wxKey 相同 -> 应拿到同一份
r = cloud_sync.pull(force=True)
check("跨设备同 wxKey 读到云端人格", r.get("ok") and persona.identity()["nameCN"] == "云端李四", str(r))
device_id.get_device_id = real_dev

# 9) 套餐同步
lk, li = cloud_sync._license_credential()
# 没有真实卡密，跳过（依赖外部 Creem）；仅验证函数不崩
r = cloud_sync.sync_plan()
check("sync_plan 无卡密优雅失败", (not r.get("ok")) and "卡密" in (r.get("error") or ""), str(r))

# 10) 降级：worker_url 空 -> 不崩
cloud_sync.worker_url = lambda: ""
r = cloud_sync.sync(reason="noconn")
check("无云端地址降级不崩", (not r.get("ok")) and r.get("skipped") is not True, str(r))
cloud_sync.worker_url = lambda: "http://127.0.0.1:%d" % _port["v"]

# 11) HMAC 拦截（错误密钥服务端应 401 -> 客户端 ok=False）
os.environ["WEAUTO_WEAUATO_CLIENT_SECRET"] = "wrong"
r = cloud_sync.hello()
check("错误密钥被服务端拒", r.get("ok") is False and ("401" in (r.get("error") or "")), str(r))
os.environ["WEAUTO_WEAUATO_CLIENT_SECRET"] = SECRET

srv.shutdown()
print("=== cloud_sync EXE 端回归 ===")
print("\n".join(_msgs))
print(f"\n结果： {passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)
