# -*- coding: utf-8 -*-
"""updater 热更新通道自测：离线 mock 单测（默认）+ 线上 E2E（--live）。

跑法：
    python _updater_e2e_test.py            # 离线部分，CI 安全，不联网
    python _updater_e2e_test.py --live     # 追加线上全链（真下载 83MB 构件+验签）

覆盖（对应 2026-10-15 efbbc3a 修复与 E2E 实测）：
    离线（mock requests.get / subprocess.Popen / time.sleep）：
      1. _http_get 首轮成功不触发回退
      2. _http_get 系统代理失败→直连回退成功（proxies 全置 None）
      3. _http_get 两轮全败抛出最后一个异常
      4. _check_artifact_updates 3 次退避重试后返回 error
      5. _check_artifact_updates 第 2 次重试即成功
      6. 版本比较矩阵：本地无状态/更旧→有更新；相等/更新→无更新
      7. 状态文件读写往返（root_dir 重定向到临时目录，不污染仓库）
      8. apply_artifact_update：下载 3 次重试后成功
      9. apply_artifact_update：下载 3 次全败→失败且信息含"下载更新包失败"
     10. apply_artifact_update：验签失败（错签名）→拒绝安装
     11. apply_artifact_update：验签通过（真 Ed25519 密钥对）→成功
     12. apply_artifact_update：发行版缺 .sig → 拒绝安装
     13. apply_artifact_update：非 exe 运行环境→拒绝自更新
     14. apply_artifact_update：构件包内无 WeAuto.exe → 失败
    线上（--live，需联网；仓库需带 secrets blob 走真验签）：
     15. 真检查→真下载→真验签→bat 生成→状态回写全链（不执行 bat）
"""
import json
import os
import shutil
import sys
import tempfile
import zipfile
from unittest import mock

# 与本文件同目录（updater.py 就在旁边）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import updater  # noqa: E402
import requests  # noqa: E402

LIVE = "--live" in sys.argv

# 记录所有测试用临时目录，退出前统一清理（离线用例会建十几个）
_TMP_DIRS = []


def _mkdtemp(prefix=None):
    d = tempfile.mkdtemp(prefix=prefix)
    _TMP_DIRS.append(d)
    return d


import atexit  # noqa: E402


@atexit.register
def _cleanup_tmp():
    for d in _TMP_DIRS:
        shutil.rmtree(d, ignore_errors=True)

PASS = 0
FAIL = 0


def ok(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("PASS  " + name)
    else:
        FAIL += 1
        print("FAIL  " + name + (("  -> " + str(extra)) if extra else ""))


# ---------------------------------------------------------------- mock 响应
class FakeResp:
    def __init__(self, payload, status=200):
        self._payload = payload
        self.status_code = status

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}")

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def make_updater(tmp):
    """实例：root_dir/temp_dir/状态文件全部重定向到临时目录。"""
    u = updater.Updater()
    u.root_dir = tmp
    u.temp_dir = os.path.join(tmp, "temp_update")
    u._artifact_state_path = lambda: os.path.join(tmp, ".weauto_artifact.json")
    return u


def write_state(u, updated_at):
    with open(u._artifact_state_path(), "w", encoding="utf-8") as f:
        json.dump({"updated_at": updated_at, "artifact_id": 1}, f)


def make_zip(path, exe_bytes=b"MZ-fake-exe", sig_bytes=b"x" * 64, with_sig=True):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("WeAuto.exe", exe_bytes)
        if with_sig:
            z.writestr("WeAuto.exe.sig", sig_bytes)
    return path


# ================================================================ 离线部分
print("---- 离线单测（mock，不联网）----")

# 1. 首轮成功不触发回退
with mock.patch.object(updater.requests, "get",
                       return_value=FakeResp({"ok": True})) as mg:
    r = updater._http_get("https://example.invalid/x", timeout=5)
    ok("1 _http_get 首轮成功不触发回退", mg.call_count == 1
       and mg.call_args.kwargs.get("proxies") is None)

# 2. 系统代理失败→直连回退成功
seq = [requests.ConnectionError("dead proxy 127.0.0.1:63706"),
       FakeResp({"ok": True})]
with mock.patch.object(updater.requests, "get", side_effect=seq) as mg, \
     mock.patch.object(updater.time, "sleep"):
    r = updater._http_get("https://example.invalid/x", timeout=5)
    second_proxies = mg.call_args_list[-1].kwargs.get("proxies")
    ok("2 _http_get 死代理回退直连成功",
       mg.call_count == 2 and r.json().get("ok")
       and second_proxies == {"http": None, "https": None})

# 3. 两轮全败抛最后一个异常
with mock.patch.object(updater.requests, "get",
                       side_effect=[requests.ConnectionError("boom-1"),
                                    requests.ConnectionError("boom-2")]) as mg:
    try:
        updater._http_get("https://example.invalid/x", timeout=5)
        ok("3 _http_get 两轮全败抛异常", False, "未抛异常")
    except requests.ConnectionError as e:
        ok("3 _http_get 两轮全败抛异常", mg.call_count == 2 and "boom-2" in str(e))

# 4. 检查更新 3 次重试后返回 error
with mock.patch.object(updater.requests, "get",
                       side_effect=requests.ConnectionError("net down")), \
     mock.patch.object(updater.time, "sleep") as ms:
    u = make_updater(_mkdtemp())
    res = u._check_artifact_updates()
    ok("4 检查更新 3 次退避重试后报错",
       res.get("has_update") is False and "检查更新代理失败" in res.get("error", "")
       and ms.call_count == 2)

# 5. 第 2 次重试即成功
tmp = _mkdtemp()
u = make_updater(tmp)
write_state(u, "2026-10-14T00:00:00Z")
seq = [requests.ConnectionError("flaky"), FakeResp(
    {"ok": True, "version": "2026-10-15T07:47:16Z",
     "download_url": "https://blob.invalid/a.zip", "artifact_id": 42})]
with mock.patch.object(updater.requests, "get", side_effect=seq), \
     mock.patch.object(updater.time, "sleep"):
    res = u._check_artifact_updates()
    ok("5 检查更新重试第 2 次成功",
       res.get("has_update") is True and res.get("version") == "2026-10-15T07:47:16Z"
       and res.get("artifact_id") == 42 and res.get("source") == "WeAuto Worker Update Proxy")

# 6. 版本比较矩阵
CASES = [
    ("本地无状态", None, "2026-10-15T07:47:16Z", True),
    ("本地更旧", "2026-10-14T00:00:00Z", "2026-10-15T07:47:16Z", True),
    ("版本相等", "2026-10-15T07:47:16Z", "2026-10-15T07:47:16Z", False),
    ("本地更新", "2026-10-16T00:00:00Z", "2026-10-15T07:47:16Z", False),
]
all_ok = True
for label, local, remote, expect in CASES:
    t = _mkdtemp()
    uu = make_updater(t)
    if local is not None:
        write_state(uu, local)
    with mock.patch.object(updater.requests, "get", return_value=FakeResp(
            {"ok": True, "version": remote, "download_url": "u", "artifact_id": 1})):
        r = uu._check_artifact_updates()
        if bool(r.get("has_update")) != expect:
            all_ok = False
            print("   子用例失败:", label, r.get("has_update"))
ok("6 版本比较矩阵（无/旧→更新，等/新→不更新）", all_ok)

# 7. 状态文件读写往返
t = _mkdtemp()
u = make_updater(t)
ok("7a 无状态文件时读取为 None", u._get_local_artifact_updated_at() is None)
u._save_local_artifact_updated_at("2026-10-15T07:47:16Z", 99)
ok("7b 状态写回后读取一致",
   u._get_local_artifact_updated_at() == "2026-10-15T07:47:16Z")

# ---- apply_artifact_update 系列（mock 下载 + Popen + 验签）----
INFO = {"version": "2026-10-15T07:47:16Z", "artifact_id": 42,
        "download_url": "https://blob.invalid/a.zip"}

# 8. 下载 3 次重试后成功（跳过验签：is_release False）
t = _mkdtemp()
u = make_updater(t)
zp = make_zip(os.path.join(t, "a.zip"))
with open(zp, "rb") as f:
    zip_bytes = f.read()
import io  # noqa: E402
seq = [requests.ConnectionError("reset"), requests.ConnectionError("reset"),
       FakeResp({"ok": True})]
with mock.patch.object(updater.requests, "get", side_effect=seq) as mg, \
     mock.patch.object(updater.time, "sleep"), \
     mock.patch("subprocess.Popen") as mp, \
     mock.patch.object(sys, "executable", os.path.join(t, "WeAuto.exe")), \
     mock.patch("weauto_license.secrets.is_release", return_value=False):
    # 第三次成功需要返回可下载的 zip 流：FakeResp 不支持 iter_content，换成真流
    class StreamResp:
        status_code = 200

        def raise_for_status(self):
            pass

        def iter_content(self, chunk_size=8192):
            yield zip_bytes

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False
    seq[2] = StreamResp()
    res = u.apply_artifact_update(INFO)
    bat = os.path.join(u.temp_dir, "apply_update.bat")
    ok("8 下载 3 次重试后成功且 bat 已生成",
       res.get("success") is True and os.path.exists(bat)
       and mp.call_count == 1 and mg.call_count == 3)

# 9. 下载 3 次全败
t = _mkdtemp()
u = make_updater(t)
with mock.patch.object(updater.requests, "get",
                       side_effect=requests.ConnectionError("net down")), \
     mock.patch.object(updater.time, "sleep"), \
     mock.patch("subprocess.Popen") as mp:
    res = u.apply_artifact_update(INFO)
    ok("9 下载 3 次全败→失败且不拉起替换",
       res.get("success") is False and "下载更新包失败" in res.get("output", "")
       and mp.call_count == 0)

# ---- 验签双路径（真 Ed25519 密钥对）----
try:
    from cryptography.hazmat.primitives.asymmetric import ed25519
    import hashlib
    _sk = ed25519.Ed25519PrivateKey.generate()
    _pub_raw = _sk.public_key().public_bytes_raw()
    HAVE_CRYPTO = True
except Exception:
    HAVE_CRYPTO = False

EXE_BYTES = b"MZ" + os.urandom(2048)

# 10. 验签失败（错签名）→拒绝安装
t = _mkdtemp()
u = make_updater(t)
make_zip(os.path.join(t, "a.zip"), EXE_BYTES, b"\x00" * 64)
with open(os.path.join(t, "a.zip"), "rb") as f:
    zb = f.read()

class StreamResp2:
    status_code = 200

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size=8192):
        yield zb

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

if HAVE_CRYPTO:
    with mock.patch.object(updater.requests, "get", return_value=StreamResp2()), \
         mock.patch("subprocess.Popen") as mp, \
         mock.patch.object(sys, "executable", os.path.join(t, "WeAuto.exe")), \
         mock.patch("weauto_license.secrets.is_release", return_value=True), \
         mock.patch("weauto_license.secrets.get_bytes", return_value=_pub_raw):
        res = u.apply_artifact_update(INFO)
        ok("10 验签失败（篡改签名）→拒绝安装",
           res.get("success") is False and "签名校验失败" in res.get("output", "")
           and mp.call_count == 0)

    # 11. 验签通过→成功（注意：验签对象是 exe 的 SHA-256 摘要，须签摘要）
    t = _mkdtemp()
    u = make_updater(t)
    good_sig = _sk.sign(hashlib.sha256(EXE_BYTES).digest())
    make_zip(os.path.join(t, "a.zip"), EXE_BYTES, good_sig)
    with open(os.path.join(t, "a.zip"), "rb") as f:
        zb = f.read()

    class StreamResp3:
        status_code = 200

        def raise_for_status(self):
            pass

        def iter_content(self, chunk_size=8192):
            yield zb

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False
    with mock.patch.object(updater.requests, "get", return_value=StreamResp3()), \
         mock.patch("subprocess.Popen") as mp, \
         mock.patch.object(sys, "executable", os.path.join(t, "WeAuto.exe")), \
         mock.patch("weauto_license.secrets.is_release", return_value=True), \
         mock.patch("weauto_license.secrets.get_bytes", return_value=_pub_raw):
        res = u.apply_artifact_update(INFO)
        new_exe = os.path.join(u.temp_dir, "weauto_exe", "WeAuto.exe")
        ok("11 验签通过→成功并写回状态",
           res.get("success") is True and "签名校验通过" in res.get("output", "")
           and os.path.exists(new_exe)
           and u._get_local_artifact_updated_at() == INFO["version"])

    # 12. 发行版缺 .sig→拒绝
    t = _mkdtemp()
    u = make_updater(t)
    make_zip(os.path.join(t, "a.zip"), EXE_BYTES, None, with_sig=False)
    with open(os.path.join(t, "a.zip"), "rb") as f:
        zb = f.read()

    class StreamResp4:
        status_code = 200

        def raise_for_status(self):
            pass

        def iter_content(self, chunk_size=8192):
            yield zb

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False
    with mock.patch.object(updater.requests, "get", return_value=StreamResp4()), \
         mock.patch("subprocess.Popen") as mp, \
         mock.patch.object(sys, "executable", os.path.join(t, "WeAuto.exe")), \
         mock.patch("weauto_license.secrets.is_release", return_value=True), \
         mock.patch("weauto_license.secrets.get_bytes", return_value=_pub_raw):
        res = u.apply_artifact_update(INFO)
        ok("12 发行版缺 .sig→拒绝安装",
           res.get("success") is False and "缺少签名文件" in res.get("output", ""))
else:
    ok("10-12 验签路径（cryptography 缺失，跳过）", True, "skipped")

# 13. 非 exe 环境拒绝自更新
t = _mkdtemp()
u = make_updater(t)
make_zip(os.path.join(t, "a.zip"), EXE_BYTES, b"x" * 64)
with open(os.path.join(t, "a.zip"), "rb") as f:
    zb = f.read()

class StreamResp5:
    status_code = 200

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size=8192):
        yield zb

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False
with mock.patch.object(updater.requests, "get", return_value=StreamResp5()), \
     mock.patch("subprocess.Popen") as mp, \
     mock.patch.object(sys, "executable", os.path.join(t, "python")), \
     mock.patch("weauto_license.secrets.is_release", return_value=False):
    res = u.apply_artifact_update(INFO)
    ok("13 非 exe 运行环境→拒绝自更新",
       res.get("success") is False and "非 exe" in res.get("output", ""))

# 14. 构件包内无 WeAuto.exe
t = _mkdtemp()
u = make_updater(t)
with zipfile.ZipFile(os.path.join(t, "a.zip"), "w") as z:
    z.writestr("readme.txt", "no exe here")
with open(os.path.join(t, "a.zip"), "rb") as f:
    zb = f.read()

class StreamResp6:
    status_code = 200

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size=8192):
        yield zb

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False
with mock.patch.object(updater.requests, "get", return_value=StreamResp6()), \
     mock.patch("subprocess.Popen") as mp, \
     mock.patch.object(sys, "executable", os.path.join(t, "WeAuto.exe")), \
     mock.patch("weauto_license.secrets.is_release", return_value=False):
    res = u.apply_artifact_update(INFO)
    ok("14 构件包无 WeAuto.exe→失败",
       res.get("success") is False and "未找到 WeAuto.exe" in res.get("output", ""))

# ================================================================ 线上部分
if LIVE:
    print("---- 线上 E2E（真 Worker / 真下载 / 真验签）----")
    # 模拟无代理正常用户（本机注册表可能残留死代理，由 _http_get 自行回退）
    updater.requests.Session.trust_env = False
    t = _mkdtemp(prefix="weauto_live_")
    u = make_updater(t)
    u._save_local_artifact_updated_at("2026-10-14T00:00:00Z", 1)
    print("[live] 开始检查更新...", flush=True)
    info = None
    for i in range(8):
        info = u._check_artifact_updates()
        print("[live] 检查第 %d 次: error=%s" % (i + 1, str(info.get("error"))[:80]), flush=True)
        if not info.get("error"):
            break
        import time as _tt
        _tt.sleep(2)
    ok("15a 线上检查返回最新构件",
       info.get("has_update") is True and bool(info.get("version"))
       and bool(info.get("download_url")),
       info.get("error"))
    if info.get("has_update"):
        print("[live] 开始下载构件（约 83MB，可能数分钟）...", flush=True)
        # ⚠️ 安全三连（缺一不可）：
        #   1) sys.executable 重定向到临时目录——否则 bat 的 copy 目标是真的
        #      python.exe，会把本进程的解释器覆盖成 WeAuto.exe；
        #   2) mock subprocess.Popen——不真拉起替换/重启（真链已在手动 E2E 验证）；
        #   3) 临时目录由 atexit 清理。
        fake_exe = os.path.join(t, "WeAuto.exe")
        with open(fake_exe, "wb") as f:
            f.write(b"MZ-old-build")
        with mock.patch.object(sys, "executable", fake_exe), \
             mock.patch("subprocess.Popen") as mp:
            res = u.apply_artifact_update(info)
        bat = os.path.join(u.temp_dir, "apply_update.bat")
        new_exe = os.path.join(u.temp_dir, "weauto_exe", "WeAuto.exe")
        bat_text = open(bat, encoding="utf-8").read() if os.path.exists(bat) else ""
        bat_ok = ("copy /Y" in bat_text and fake_exe in bat_text
                  and "start" in bat_text and 'del "%~f0"' in bat_text)
        ok("15b 线上下载+验签+bat 全链成功",
           res.get("success") is True and os.path.exists(new_exe) and bat_ok
           and mp.call_count == 1,
           res.get("output", "")[-200:])
        ok("15c 状态回写且二次检查无更新",
           u._get_local_artifact_updated_at() == info.get("version")
           and u._check_artifact_updates().get("has_update") is False)
        if os.path.exists(new_exe):
            import hashlib
            print("   新构件 sha256:", hashlib.sha256(
                open(new_exe, "rb").read()).hexdigest()[:16], "版本:", info.get("version"),
                flush=True)

print()
print("=" * 56)
print(f"结果: {PASS} 通过 / {FAIL} 失败" + ("（含 --live 线上全链）" if LIVE else "（离线部分）"))
sys.exit(1 if FAIL else 0)
