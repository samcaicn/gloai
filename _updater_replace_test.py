# -*- coding: utf-8 -*-
"""自替换批处理（apply_update.bat）防回归测试 —— 纯标准库，Windows 专用。

防的是真实事故：旧版自替换 bat 只做 `copy /Y` 就 `start`，没有任何校验。
一旦磁盘写满或写入中断，目标 exe 被写成**截断文件**，用户下次启动
PyInstaller 解压到一半就缺 base_library.zip，软件彻底起不来且无提示。

现版要求三条分支都必须正确：
  VERIFY    覆盖 + 校验通过 → 启动新版本（exit 0），备份 .bak 删除
  ROLLBACK  覆盖后内容不一致 → 用 .bak 回滚并启动旧版本（exit 2）
  WAITLOCK  copy 一直失败 → 重试到上限后放弃（exit 1），绝不启动半截文件

⚠️ 现版 bat 的首选写路径是**让位法**（先 ren 旧文件让位，再 copy 新文件），
WAITLOCK 里的 copy 只是 rename 失败后的兜底分支。注入故障必须打在**当前
真正会执行的那条 copy** 上，否则 replace 静默失效、测试假绿
（2026-10-16 CI 回归教训：注入还打在旧版 WAITLOCK copy 上，而 rename
在测试目录里总是成功，那条 copy 根本不执行）。

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
    def _write_replace_bat(self, f, src, src_sig, current, logf):
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


def make_bat(tmp, src, dst, logf, *, src_sig="", drop_start=True, patch=None):
    """用真实代码生成 bat；drop_start=True 时去掉 start 行（测试里不真拉起进程）。
    src_sig 为随包 .sig 路径；缺省空串时 bat 不输出 sig 搬运逻辑（非发行态）。"""
    bat = os.path.join(tmp, "apply_update.bat")
    with open(bat, "w", encoding="utf-8") as f:
        _upd.Updater._write_replace_bat(object.__new__(_upd.Updater), f, src, src_sig, dst, logf)
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
        ("if !N! LSS 60 goto WAITLOCK", "锁未释放时重试"),
        # 让位法（方案 A）关键行：bat 结构若再变，上面三条分支的注入点会失效，
        # 这里先炸，避免测试假绿
        ('ren "!DSTNAME!" "!OLDNAME!"', "让位法 rename"),
        ('copy /Y "%SRC%" "!DSTNAME!"', "让位法写入"),
        (":RNVERIFY", "让位后校验分支"),
        # exe 写入结果必须立即检查：sig copy 紧跟其后会掩盖 exe 的 errorlevel
        # （if not errorlevel 1 看的是最后一条命令）→ exe 没换却进 VERIFY → 静默失败
        ("if errorlevel 1 goto RNRESTORE", "让位后 exe 写入失败立即还原"),
        ("if not errorlevel 1 goto WLSIG", "WAITLOCK exe 成功才搬 sig"),
    ):
        ok("含 %s" % desc, token in text)
    ok("start 仅在成功分支（测试版已剔除）", 'start ""' not in text)
    # sig 搬运结构：仅当传入 src_sig 时才输出（自体检闭环）
    text_sig = open(make_bat(tmp, r"C:\x\new.exe", r"C:\x\old.exe",
                             os.path.join(tmp, "l2.log"), src_sig=r"C:\x\new.sig"),
                    encoding="utf-8").read()
    ok("含 SRCSIG 定义", 'set "SRCSIG=' in text_sig)
    ok("含 DSTSIG 目标", "DSTSIG" in text_sig)
    ok("含 sig 随 exe 搬运", 'copy /Y "%SRCSIG%" "!DSTSIG!"' in text_sig)
    # 未传 src_sig 时不得出现 sig 搬运逻辑（避免 if exist "" 空路径歧义）
    ok("无 src_sig 时不输出 SRCSIG", "SRCSIG" not in text)

    # ------------------------------------------------ 2. 成功路径：覆盖+校验通过
    section("2. 成功路径（覆盖并校验通过）")
    d = os.path.join(tmp, "ok")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, "new.bin")
    dst = os.path.join(d, "cur.bin")
    src_sig = os.path.join(d, "new.sig")
    open(src, "wb").write(b"NEW-CONTENT-123")
    open(src_sig, "wb").write(b"NEW-SIG-BYTES")
    open(dst, "wb").write(b"OLD-CONTENT")
    logf = os.path.join(d, "l.log")
    rc = run(make_bat(d, src, dst, logf, src_sig=src_sig))
    ok("退出码 0", rc == 0, "rc=%d" % rc)
    ok("目标已换成新内容", open(dst, "rb").read() == b"NEW-CONTENT-123")
    ok("备份 .bak 已删除（成功不留垃圾）", not os.path.exists(dst + ".bak"))
    # sig 随 exe 一并搬到 DSTDIR/WeAuto.exe.sig（自体检闭环）
    dst_sig = os.path.join(d, "WeAuto.exe.sig")
    ok("签名 .sig 已随新 exe 搬运", os.path.exists(dst_sig)
       and open(dst_sig, "rb").read() == b"NEW-SIG-BYTES")

    # ------------------------------------------------ 3. 回滚路径：覆盖成截断文件
    section("3. 回滚路径（覆盖后文件被写坏）")
    d = os.path.join(tmp, "bad")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, "new.bin")
    dst = os.path.join(d, "cur.bin")
    open(src, "wb").write(b"NEW-CONTENT-123")
    open(dst, "wb").write(b"OLD-CONTENT")
    logf = os.path.join(d, "l.log")
    # 注入故障：模拟磁盘写满 → 让位后写入只产出 0 字节文件。
    # 必须打**让位法那条 copy**（copy /Y "%SRC%" "!DSTNAME!"）：测试目录里
    # rename 总能成功，走的就是让位分支；打 WAITLOCK 的兜底 copy 不会被执行
    # （2026-10-16 回归教训：旧注入点成死代码，测试假绿）。
    patched = make_bat(d, src, dst, logf, patch=[
        ('copy /Y "%SRC%" "!DSTNAME!" >> "%LOGF%" 2>&1', 'type nul > "!DSTNAME!"'),
    ])
    rc = run(patched)
    ok("退出码 2（回滚）", rc == 2, "rc=%d" % rc)
    ok("已回滚为旧版本内容", open(dst, "rb").read() == b"OLD-CONTENT",
       "实际=%r" % open(dst, "rb").read()[:40])

    # ------------------------------------------------ 4. 放弃路径：目标一直被占用
    section("4. 放弃路径（copy 始终失败）")
    d = os.path.join(tmp, "lock")
    os.makedirs(d, exist_ok=True)
    src = os.path.join(d, "new.bin")
    open(src, "wb").write(b"NEW-CONTENT")
    dst = os.path.join(d, "cur.bin")
    open(dst, "wb").write(b"OLD-CONTENT")
    logf = os.path.join(d, "l.log")
    # 注入故障：rename 与兜底 copy 都恒失败（cmd /c exit 1 置 errorlevel）。
    # 不能用只读属性模拟「copy 必然失败」——Windows 的 copy /Y 会直接覆盖
    # 只读文件、ren 也不受只读属性阻拦（2026-10-16 回归教训：原实现靠
    # chmod 0o444，结果 copy 照常成功，走了 exit 0 成功分支）。
    # 把重试上限降到 1 次，避免测试跑 80 秒。
    patched = make_bat(d, src, dst, logf, patch=[
        ('ren "!DSTNAME!" "!OLDNAME!" >> "%LOGF%" 2>&1', 'cmd /c exit 1'),
        ('copy /Y "%SRC%" "%DST%" >> "%LOGF%" 2>&1', 'cmd /c exit 1'),
        ("if !N! LSS 60 goto WAITLOCK", "if !N! LSS 1 goto WAITLOCK"),
    ])
    rc = run(patched)
    ok("退出码 1（放弃，未启动）", rc == 1, "rc=%d" % rc)
    ok("原文件未被破坏", open(dst, "rb").read() == b"OLD-CONTENT")
    ok("日志记录了失败原因", os.path.exists(logf) and "FAILED" in open(logf, "rb").read().decode("utf-8", "replace"))
    ok("备份 .bak 保留（失败时可人工恢复）", os.path.exists(dst + ".bak"))

    # ------------------------------------------------ 5. errorlevel 掩盖回归（2026-10-17 真事）
    section("5. exe copy 失败不得被 sig copy 成功掩盖")
    d5 = os.path.join(tmp, "mask")
    os.makedirs(d5, exist_ok=True)
    src5 = os.path.join(d5, "new.bin")
    dst5 = os.path.join(d5, "cur.bin")
    sig5 = os.path.join(d5, "new.sig")
    open(src5, "wb").write(b"NEW-CONTENT")
    open(dst5, "wb").write(b"OLD-CONTENT")
    open(sig5, "wb").write(b"NEW-SIG")
    logf5 = os.path.join(d5, "l.log")
    # 注入：exe copy 恒失败，sig copy 照常成功。旧实现的 bug——`if not errorlevel 1`
    # 看的是最后一条命令（sig），exe 没换却进 VERIFY → fc 失败 → 回滚 rc=2，
    # 表现为「更新静默不生效」。新实现 exe 失败立即重试，必须 rc=1 放弃。
    patched5 = make_bat(d5, src5, dst5, logf5, src_sig=sig5, patch=[
        ('copy /Y "%SRC%" "!DSTNAME!" >> "%LOGF%" 2>&1', 'cmd /c exit 1'),
        ('copy /Y "%SRC%" "%DST%" >> "%LOGF%" 2>&1', 'cmd /c exit 1'),
        ("if !N! LSS 60 goto WAITLOCK", "if !N! LSS 1 goto WAITLOCK"),
    ])
    rc5 = run(patched5)
    ok("退出码 1（重试后放弃，未被掩盖）", rc5 == 1, "rc=%d" % rc5)
    ok("目标保持旧内容（未被半更新）", open(dst5, "rb").read() == b"OLD-CONTENT")

    print("\n========================================================")
    print("自替换 bat 实测：通过 %d / 失败 %d" % (PASS, FAIL))
    print("========================================================")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
