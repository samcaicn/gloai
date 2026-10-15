# -*- coding: utf-8 -*-
"""WeAuto EXE 打包产物实测（CI 与本地共用）。

为什么不用「文件存在 + 体积 > 0」那种弱校验：
  PyInstaller onefile 改了源码没重新打包时，EXE 照样能生成、体积也正常，
  只看 size 的校验会**漏过**这类最常见的假绿。所以这里直接读 EXE 内部归档，
  验证里面**真的**带着本次改动的资源内容。

⚠️ 实现要点（都踩过）：
  1. onefile EXE **不是普通 zip**（前面有 bootloader），`zipfile` 会报
     "File is not a zip file"。必须用 `PyInstaller.archive.readers.CArchiveReader`。
  2. `CArchiveReader.extract(name)` 返回 **bytes**，不是写文件。
  3. 条目名用**反斜杠**（`templates\\config_editor.html`），不是正斜杠。
  4. `config_editor` 入口在包里是 `.pyc`，marshal 常量受 Python 版本影响解不开；
     不要试图反序列化，直接在**原始字节**里 grep 特征串。

检查项
------
 1. PE Subsystem == 2（GUI-only 硬要求，否则双击弹 cmd 黑框）
 2. 归档条目数下限 + splash 资源
 3. **内容断言**：templates/config_editor.html 必须含 v1.4 新功能特征串
    （自动化中心 / 群聊精细化 / 感知联动），且必须**不含**被废弃的旧串
    （Creem 收款浮层 / 旧域名 / 旧账户明文 key / 占位 key）
 4. config.py 出厂值不得含任何真实或占位密钥（会原样分发给所有付费用户），
    且登录密码总开关出厂为 False（默认免密）
 5. 体积下限（防「打了一半」的截断包）

用法：
  python _exe_ci_test.py C:/_wd/WeAuto.exe
退出码：0 = 全过；1 = 有失败项。
"""

import argparse
import os
import re
import struct
import sys

# Windows runner 上 Python stdout 默认 cp1252，print 中文会 UnicodeEncodeError 直接崩溃
#（CI 踩过的真坑，与断言内容无关）。强制 UTF-8 输出。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

SIZE_FLOOR = 40 * 1024 * 1024   # 实测 ~90MB；留足余量只拦「明显截断」

# ---- templates/config_editor.html 必须出现的特征串 ----
# 每次改了 UI/新功能就往这里加一条断言 —— 这是「改了没重新打包」的唯一防线。
# v1.4：Creem 收款板块已隐藏，改为 群聊精细化 / 自动化中心 / 感知联动 特征。
REQUIRED_TOKENS = [
    ('section-automation',        "v1.4 自动化中心面板"),
    ('GROUP_WHITELIST',           "v1.4 群白名单配置"),
    ('GROUP_BLACKLIST',           "v1.4 群黑名单配置"),
    ('BANNED_WORDS',              "v1.4 禁词配置"),
    ('GROUP_RATE_LIMIT_WINDOW',   "v1.4 群内频控窗口"),
    ('ENABLE_IMAGE_RECOG_AUTO_REPLY', "v1.4 感知联动开关"),
    ('loadAutomationRules',       "自动化中心加载逻辑"),
]

# ---- 必须不出现的废弃串（出现即回归）----
# Creem 收款板块 2026-10-14 已隐藏：付款浮层/浮层打开函数/账户 key 一律禁止回归。
FORBIDDEN_SUBSTRINGS = [
    ('buyOverlay',        "Creem 付款浮层（已隐藏）"),
    ('openBuyOverlay',    "Creem 浮层打开函数（已隐藏）"),
    ('creem_',            "Creem 账户明文痕迹"),
    ('weauto.safeopc.cn', "已删除的备用域名"),
    ('sk-dummy',          "占位 API key"),
]

OK = 0
FAIL = 0


def check(desc, cond, extra=""):
    global OK, FAIL
    if cond:
        OK += 1
        print("  PASS  %s" % desc)
    else:
        FAIL += 1
        print("  FAIL  %s   %s" % (desc, extra))


def section(t):
    print("\n=== %s ===" % t)


def read_entry(arch, basename):
    """按 basename 找条目并读出 bytes；找不到返回 None。

    容错：条目名分隔符可能是 / 或 \\，也可能带目录前缀。
    """
    norm = basename.replace("/", "\\")
    for key in arch.toc:
        k = key.replace("/", "\\")
        if k == norm or k.endswith("\\" + norm):
            return arch.extract(key)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("exe")
    args = ap.parse_args()

    path = args.exe

    section("0. 文件本体")
    if not os.path.isfile(path):
        check("EXE 存在: %s" % path, False)
        print("\n无法继续，退出。")
        return 1
    size = os.path.getsize(path)
    print("  体积 = %.1f MB" % (size / 1024.0 / 1024.0))
    check("体积 >= %.0fMB（防截断包）" % (SIZE_FLOOR / 1024 / 1024), size >= SIZE_FLOOR,
          "-> %.1f MB" % (size / 1024.0 / 1024.0))

    # ---- 1. PE Subsystem ----
    section("1. PE 头：GUI-only（双击不弹 cmd 黑框）")
    with open(path, "rb") as f:
        dos = f.read(64)
        if dos[:2] != b"MZ":
            check("MZ 头有效", False, "-> 不是 PE 文件")
            return 1
        e_lfanew = struct.unpack_from("<I", dos, 0x3C)[0]
        f.seek(e_lfanew)
        sig = f.read(4)
        f.seek(e_lfanew + 4 + 20 + 68)
        sub = struct.unpack("<H", f.read(2))[0]
    check("PE 签名有效", sig == b"PE\0\0", "-> %r" % sig)
    check("Subsystem == 2 (GUI)", sub == 2,
          "-> %d（3=CUI，双击会弹黑框）" % sub)

    # ---- 2. 读归档 ----
    section("2. 读取 PyInstaller 归档（防「改了源码没重新打包」）")
    try:
        from PyInstaller.archive.readers import CArchiveReader
    except ImportError as e:
        check("能 import PyInstaller（CI 需先 pip install pyinstaller）", False, "-> %s" % e)
        return 1
    try:
        # onefile 不能用 zipfile（前面有 bootloader），必须走 CArchiveReader
        arch = CArchiveReader(path)
    except Exception as e:
        check("EXE 可作为 PyInstaller 归档打开", False, "-> %s" % e)
        return 1
    keys = list(arch.toc.keys())
    check("归档条目数 > 500（真实值 ~630）", len(keys) > 500, "-> %d" % len(keys))
    check("含 splash 启动画面资源（Splash-*.res）",
          any(k.lower().startswith("splash") for k in keys),
          "-> %s" % [k for k in keys if "splash" in k.lower()])

    for key, desc in [("config_editor.html", "主界面模板"),
                      ("config.py", "配置文件")]:
        check("已打包 %s（%s）" % (key, desc), read_entry(arch, key) is not None)
    for d in ("emojis", "prompts", "Demo_Image"):
        check("已打包目录 %s" % d, any(d in k for k in keys))

    # ---- 3. config_editor.html 内容断言 ----
    section("3. 主界面模板内容断言（v1.4 功能特征 + 禁止 Creem 回归）")
    data = read_entry(arch, "config_editor.html")
    if data is None:
        check("读出 config_editor.html", False)
    else:
        html = data.decode("utf-8", "replace")
        check("读出 config_editor.html（%d 字节）" % len(html), len(html) > 10000)
        for token, desc in REQUIRED_TOKENS:
            check("含「%s」-> %s" % (token, desc), token in html)
        for bad, why in FORBIDDEN_SUBSTRINGS:
            check("不含%s：%s" % (bad, why), bad not in html)
        # 付款链路不得有裸 window.open(（Creem 隐藏后更不允许付款相关弹窗回归）。
        stripped = re.sub(r"window\.open(?:BuyOverlay|BuyInternal)", "WO", html)
        all_open = re.findall(r"window\.open\s*\(", stripped)
        check("全页 window.open( 仅剩用户主动触发点（<=2）", len(all_open) <= 2,
              "-> %d 次" % len(all_open))
        for bad in ('window.openBuyInternal("http', 'window.open(buyUrl',
                    'window.open(url'):
            check("付款链路无裸 window.open：%s" % bad, bad not in html)

    # ---- 4. config.py 出厂值 ----
    section("4. config.py 出厂值（会原样分发给所有付费用户）")
    cfg = read_entry(arch, "config.py")
    if cfg is None:
        check("读出 config.py", False)
    else:
        txt = cfg.decode("utf-8", "replace")
        check("读出 config.py（%d 字节）" % len(txt), len(txt) > 500)
        leaked = re.findall(r"sk-[A-Za-z0-9_\-]{16,}", txt)
        check("无 sk- 形态真实密钥", not leaked, "-> 泄漏 %r" % (leaked[:2],))
        leaked2 = re.findall(r"creem_[A-Za-z0-9]{10,}", txt)
        check("无 creem_ 形态密钥", not leaked2, "-> 泄漏 %r" % (leaked2[:2],))
        for ph in ("sk-dummy", "your-api-key", "xxxxx"):
            check("无占位串 %s" % ph, ph not in txt)
        m = re.search(r"^ENABLE_LOGIN_PASSWORD\s*=\s*(\w+)", txt, re.M)
        check("ENABLE_LOGIN_PASSWORD 出厂 False（默认免密）",
              m is not None and m.group(1) == "False",
              "-> %s" % (m.group(1) if m else "未找到"))

    # ---- 5. 字节码层特征串 ----
    section("4b. 字节码层特征串（.pyc 不反序列化，直接 grep 字节）")
    hit = None
    for k in keys:
        if k.replace("/", "\\").split("\\")[-1].startswith("config_editor"):
            hit = k
            break
    if hit is None:
        check("能在归档里定位 config_editor 字节码", False)
    else:
        blob = arch.extract(hit)
        # config_editor.py 源码中的 v1.4 字段名（submit_config 解析逻辑），
        # 证明「改了后端没重新打包」会在这里被拦下。
        check("%s 含 IMAGE_RECOG_AUTO_REPLY_RULES 字节特征" % hit,
              b"IMAGE_RECOG_AUTO_REPLY_RULES" in blob)

    print("\n" + "=" * 56)
    print("EXE 实测：通过 %d / 失败 %d" % (OK, FAIL))
    print("=" * 56)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
