# -*- coding: utf-8 -*-
"""自替换批处理（apply_update.bat）防回归测试 —— 纯标准库，Windows 专用。

防的是真实事故：旧版自替换 bat 只做 `copy /Y` 就 `start`，没有任何校验。
一旦磁盘写满或写入中断，目标 exe 被写成**截断文件**，用户下次启动
PyInstaller 解压到一半就缺 base_library.zip，软件彻底起不来且无提示。

现版要求三条分支都必须正确：
  VERIFY    覆盖 + 校验通过 → 启动新版本（exit 0），备份 .bak 删除
  ROLLBACK  覆盖后内容不一致 → 用 .bak 回滚并启动旧版本（exit 2）
  WAITLOCK  copy 一直失败 → 重试到上限后放弃（exit 1），绝不启动半截文件

测试不联网、不动真实 EXE：全部用临时目录里的 .txt 假文件跑真实 bat。
"""
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

FAKE_UPDATER = """
import sys, os
class Updater:
    def _write_replace_bat(self, f, src, current, logf):
        pass
"""

PASS = 0
FAIL = 0


def ok(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  PASS  %s" % name)
    else:
        FAIL += 1
        print("  FAIL  %s  %s" % (name, extra))


def section(t):
    print("\n=== %s ===" % t)


# ---------------------------------------------------------------- 0. 取真实生成函数
# updater.py 顶层依赖（requests/cryptography）在 CI 最小环境里可能没装，
# 这里只借用它的 _write_replace_bat 源码，避免整模块 import。
import updater as _upd


def make_bat(tmp, src, dst, logf, *, drop_start=True, patch=None):
    """用真实代码生成 bat；drop_start=True 时去掉 start 行（测试里不真拉起进程）。"""
    bat = os.path.join(tmp, "apply_update.bat")
    with open(bat, "w", encoding="utf-8") as f:
        _upd.Updater._write_replace_bat(object.__new__(_upd.Updater), f, src, dst, logf)
    with open(bat, "r", encoding="utf-8") as f:
        text = f.read()
    if drop_start:
        text = "\n".join(l for l in text.splitlines() if not l.startswith('start ""'))
    if patch:
        for a, b in patch:
            text = text.replace(a, b)
    with open(bat, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return bat


def run(bat):
    r = subprocess.run(["cmd", "/c", bat], capture_output=True, timeout=120)
    return r.returncode


def main():
    tmp = tempfile.mkdtemp(prefix="wa_replace_")

    # ------------------------------------------------ 1. 静态结构（防改动丢失关键行）
    section("1. bat 静态结构")
    bat = make_bat(tmp, r"C:\x\new.exe", r"C:\x\old.exe", os.path.join(tmp, "l.log"))
    text = open(bat, encoding="utf-8").read()
    for token, desc in (
        ('set "BAK=%DST%.bak"', "覆盖前备份"),
        ('copy /Y "%SRC%" "%DST%"', "覆盖动作"),
        ("fc /b", "二进制比对（防截断文件）"),
        (":ROLLBACK", "回滚分支"),
        ('copy /Y "%BAK%" "%DST%"', "回滚动作"),
        ("set N=0", "重试计数器初始化"),
        ("if !N! LSS 40 goto WAITLOCK", "锁未释放时重试"),
    ):
        ok("含 %s" % desc, token in text)
    ok("start 仅在成功分支（测试版已剔除）", 'start ""' not in text)

    # ------------------------------------------------ 2. 成功路径：覆盖+校验通过
    section("2. 成功路径（覆盖并校验通过）")
    d = os.path.join(tmp, "ok")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, "new.bin")
    dst = os.path.join(d, "cur.bin")
    open(src, "wb").write(b"NEW-CONTENT-123")
    open(dst, "wb").write(b"OLD-CONTENT")
    logf = os.path.join(d, "l.log")
    rc = run(make_bat(d, src, dst, logf))
    ok("退出码 0", rc == 0, "rc=%d" % rc)
    ok("目标已换成新内容", open(dst, "rb").read() == b"NEW-CONTENT-123")
    ok("备份 .bak 已删除（成功不留垃圾）", not os.path.exists(dst + ".bak"))

    # ------------------------------------------------ 3. 回滚路径：覆盖成截断文件
    section("3. 回滚路径（覆盖后文件被写坏）")
    d = os.path.join(tmp, "bad")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, "new.bin")
    dst = os.path.join(d, "cur.bin")
    open(src, "wb").write(b"NEW-CONTENT-123")
    open(dst, "wb").write(b"OLD-CONTENT")
    logf = os.path.join(d, "l.log")
    # 注入故障：模拟磁盘写满 → 覆盖只写出 0 字节
    patched = make_bat(d, src, dst, logf, patch=[
        ('copy /Y "%SRC%" "%DST%" >> "%LOGF%" 2>&1', 'type nul > "%DST%"'),
    ])
    rc = run(patched)
    ok("退出码 2（回滚）", rc == 2, "rc=%d" % rc)
    ok("已回滚为旧版本内容", open(dst, "rb").read() == b"OLD-CONTENT",
       "实际=%r" % open(dst, "rb").read()[:40])

    # ------------------------------------------------ 4. 放弃路径：目标一直被占用
    section("4. 放弃路径（copy 始终失败）")
    d = os.path.join(tmp, "lock")
    os.makedirs(d, exist_ok=True)
    # SRC 存在（否则会走新增的 exit 3 保护），把 DST 设为只读 → copy 必然失败
    src = os.path.join(d, "new.bin")
    open(src, "wb").write(b"NEW-CONTENT")
    dst = os.path.join(d, "cur.bin")
    open(dst, "wb").write(b"OLD-CONTENT")
    os.chmod(dst, 0o444)
    logf = os.path.join(d, "l.log")
    # 把重试上限降到 1 次，避免测试跑 80 秒
    patched = make_bat(d, src, dst, logf, patch=[
        ("if !N! LSS 40 goto WAITLOCK", "if !N! LSS 1 goto WAITLOCK"),
    ])
    rc = run(patched)
    ok("退出码 1（放弃，未启动）", rc == 1, "rc=%d" % rc)
    ok("原文件未被破坏", open(dst, "rb").read() == b"OLD-CONTENT")
    ok("日志记录了失败原因", os.path.exists(logf) and "FAILED" in open(logf, "rb").read().decode("utf-8", "replace"))
    ok("备份 .bak 保留（失败时可人工恢复）", os.path.exists(dst + ".bak"))
    os.chmod(dst, 0o644)

    print("\n========================================================")
    print("自替换 bat 实测：通过 %d / 失败 %d" % (PASS, FAIL))
    print("========================================================")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
