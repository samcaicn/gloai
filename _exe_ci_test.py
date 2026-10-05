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
 3. **内容断言**：templates/config_editor.html 必须含本次改动的特征串
    （价格文案、付款浮层），且必须**不含**被废弃的旧串（自动弹窗/旧域名/旧价格）
 4. config.py 出厂值不得含任何真实或占位密钥（会原样分发给所有付费用户），
    且登录密码总开关出厂为 False（默认免密）
 5. 体积下限（防「打了一半」的截断包）

用法：
  python _exe_ci_test.py C:/_wd/WeAuto.exe
  python _exe_ci_test.py C:/_wd/WeAuto.exe --expect-price 19.9 --expect-price 59.9
退出码：0 = 全过；1 = 有失败项。
"""

import argparse
import os
import re
import struct
import sys

# Windows runner 上 Python stdout 默认 cp1252，print 中文会 UnicodeEncodeError 直接崩溃
#（CI #360/#361/#363 的真正根因，与断言内容无关）。强制 UTF-8 输出。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

SIZE_FLOOR = 40 * 1024 * 1024   # 实测 85.7MB；留足余量只拦「明显截断」

# ---- templates/config_editor.html 必须出现的特征串 ----
# 每次改了 UI/价格就往这里加一条断言 —— 这是「改了没重新打包」的唯一防线。
REQUIRED_TOKENS = [
    ('buyOverlay',              "付款浮层容器"),
    ('translateY(-80px)',       "iframe 上移裁掉 Creem footer"),
    ('height:104px',            "footer 不透明遮罩条"),
    ('openBuyOverlay',          "浮层打开函数"),
]

# ---- 必须不出现的废弃串（出现即回归）----
FORBIDDEN_SUBSTRINGS = [
    ('weauto.safeopc.cn', "已删除的备用域名"),
    ('creem_5fHTWB',      "旧账户明文 key"),
    ('sk-dummy',          "占位 API key"),
]

DEFAULT_PRICES = ['19.9', '59.9', '199']
OLD_PRICES = ['29.9', '39.9']

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
    ap.add_argument("--expect-price", action="append", default=[],
                    help="期望购买页卡片出现的价格（可多次）")
    args = ap.parse_args()

    path = args.exe
    prices = args.expect_price or DEFAULT_PRICES

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
    check("归档条目数 > 500（真实值 633）", len(keys) > 500, "-> %d" % len(keys))
    # splash 条目在 CArchive 里叫 `Splash-00.res`（不是 splash.png —— png 被
    # bootloader 转成 .res 了）。2026-10-05 加启动画面时就是按这个名字加的断言。
    check("含 splash 启动画面资源（Splash-*.res）",
          any(k.lower().startswith("splash") for k in keys),
          "-> %s" % [k for k in keys if "splash" in k.lower()])

    for key, desc in [("config_editor.html", "主界面模板"),
                      ("config.py", "配置文件")]:
        check("已打包 %s（%s）" % (key, desc), read_entry(arch, key) is not None)
    for d in ("emojis", "prompts", "Demo_Image"):
        check("已打包目录 %s" % d, any(d in k for k in keys))

    # ---- 3. config_editor.html 内容断言 ----
    section("3. 主界面模板内容断言")
    data = read_entry(arch, "config_editor.html")
    if data is None:
        check("读出 config_editor.html", False)
    else:
        html = data.decode("utf-8", "replace")
        check("读出 config_editor.html（%d 字节）" % len(html), len(html) > 10000)
        for token, desc in REQUIRED_TOKENS:
            check("含「%s」-> %s" % (token, desc), token in html)
        for p in prices:
            check("含价格 ¥%s" % p, ("¥%s" % p) in html, "-> 购买页卡片可能没同步")
        for old in OLD_PRICES:
            if old not in prices:
                check("已无旧价格 ¥%s" % old, ("¥%s" % old) not in html)
        for bad, why in FORBIDDEN_SUBSTRINGS:
            check("不含%s：%s" % (bad, why), bad not in html)
        # 付款区不得自动弹窗。
        # ⚠️ 必须用 `window\.open\s*\(` 精确匹配，且先剔除 openBuyOverlay/openBuyInternal
        #    这两个变体 —— 它们名字里含 "window.open" 子串，直接 count 会假阳性。
        i = html.find("async function openBuyInternal")
        seg = html[i:i + 700] if i >= 0 else ""
        check("openBuyInternal 存在（浮层入口）", i >= 0)
        seg_open = len(re.findall(r"window\.open\s*\(", seg))
        check("openBuyInternal 内不再 window.open（不弹新窗口）",
              i >= 0 and seg_open == 0, "-> 段内 %d 次" % seg_open)

        # 全页：真正的 window.open( 调用。唯一允许的是「打开论坛」（用户主动点击，
        # 与付款无关）；付款链路必须全是浮层。
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
        check("%s 含 buyOverlay 字节特征" % hit, b"buyOverlay" in blob)

    print("\n" + "=" * 56)
    print("EXE 实测：通过 %d / 失败 %d" % (OK, FAIL))
    print("=" * 56)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
