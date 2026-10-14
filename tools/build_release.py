# -*- coding: utf-8 -*-
"""WeAuto 发行版构建统一入口（防反编译/防破解整改）。

职责（按顺序）：
  1. 获取构建密钥：优先环境变量 pc_build_key（CI 用 GitHub Secret），
     否则读取/生成 weauto_license/build_keys.json（本地构建，已被 .gitignore 排除）。
  2. 生成 weauto_license/_build_key.py（主密钥，将被 PyArmor 混淆进 PYZ）。
  3. 生成 release_secrets.enc（AES-256-GCM 加密的全部运行时密钥 blob，打进 EXE）。
  4. 写 weauto_license/_release.py（RELEASE=True，发行版门禁强制）。
  5. 净化 config.py（剥离 WEAUATO_CLIENT_SECRET 等明文密钥行）并生成 config.py.sig。
  6. PyArmor 混淆核心自有模块（weauto_license 包、jev_guard、scriptpack、device_id）。
  7. 调用 PyInstaller 构建（复用原 ARGS，混淆目录加入 pathex）。
  8. 对 WeAuto.exe 做 Ed25519 签名 → WeAuto.exe.sig（updater 验签用）。
  9. 打 artifact zip（WeAuto.exe + WeAuto.exe.sig），供 updater 下载。

用法：
  python tools/build_release.py [--skip-obfuscate] [--skip-build]
    默认全流程；--skip-obfuscate 跳过 PyArmor（调试用）；--skip-build 只做密钥/净化/混淆，不跑 PyInstaller。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets as pysecrets
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS_FILE = os.path.join(ROOT, "weauto_license", "build_keys.json")
BLOB_PATH = os.path.join(ROOT, "release_secrets.enc")
BUILD_KEY_PY = os.path.join(ROOT, "weauto_license", "_build_key.py")
RELEASE_PY = os.path.join(ROOT, "weauto_license", "_release.py")
CONFIG_PY = os.path.join(ROOT, "config.py")
CONFIG_SIG = CONFIG_PY + ".sig"
OBF_DIR = os.path.join(ROOT, "_pyobf_dist")
WORK_DIR = os.path.join(ROOT, "_pybuild_work")
DIST_DIR = os.path.join(ROOT, "_pybuild_dist")

# 运行时密钥字段（进 blob，客户端可读）
RUNTIME_KEYS = [
    "GATEWAY_HOST",
    "WEAUATO_CLIENT_SECRET",   # Jev 端点 HMAC 签名
    "LICENSE_SIG_SECRET",      # 授权端点 HMAC 签名（需同步到 Worker env）
    "CACHE_SIG_SALT",          # 激活缓存 HMAC 盐
    "DEVICE_SALT",             # 设备指纹盐
    "CONFIG_SIG_KEY",          # config.py 完整性 HMAC
    "QUOTA_LEDGER_SALT",       # 本地配额记账 HMAC 盐
    "WPK_MASTER_HEX",          # 话术包 AES master（hex 32B）
    "PACK_SIGNING_PUB",        # .wpk 验签公钥（SPKI base64，EC P-256）
    "UPDATER_VERIFY_KEY",      # 更新包验签公钥（Ed25519 raw，hex 32B）
]

# 构建期专用（客户端不需要，仅进 build_keys.json）
BUILD_ONLY_KEYS = [
    "PACK_SIGNING_PRIV",       # .wpk 签名私钥（hex 32B）——仅生成端
    "UPDATER_SIGN_KEY",        # 更新包签名私钥（Ed25519 raw，hex 32B）——仅构建机
]


def hex32() -> str:
    return pysecrets.token_hex(32)


def _load_or_create_keys() -> dict:
    """密钥优先级：env(WEAUTO_BUILD_KEYS_JSON) > 本地 build_keys.json > 全新生成。"""
    env_json = os.environ.get("pc_build_key", "")
    if env_json:
        d = json.loads(env_json)
        if isinstance(d, dict) and d.get("WEAUATO_CLIENT_SECRET"):
            return d
    if os.path.exists(KEYS_FILE):
        with open(KEYS_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and d.get("WEAUATO_CLIENT_SECRET"):
            return d
    # 全新生成（首次本地构建）
    d = {
        "GATEWAY_HOST": "https://weauto-app.pages.dev",
        "WEAUATO_CLIENT_SECRET": hex32(),
        "LICENSE_SIG_SECRET": hex32(),
        "CACHE_SIG_SALT": "weauto-cache-salt-" + pysecrets.token_hex(8),
        "DEVICE_SALT": "weauto-device-salt-" + pysecrets.token_hex(8),
        "CONFIG_SIG_KEY": hex32(),
        "QUOTA_LEDGER_SALT": hex32(),
        "WPK_MASTER_HEX": hex32(),
        "PACK_SIGNING_PRIV": hex32(),
        "UPDATER_SIGN_KEY": hex32(),
    }
    # ECDSA P-256 公钥由私钥派生（复用 _packcrypto_bundle 的域运算）
    from _packcrypto_bundle import P256_G, _pt_mul
    _priv = int(d["PACK_SIGNING_PRIV"], 16)
    _point = _pt_mul(_priv, P256_G)
    from _packcrypto_bundle import spki_from_point
    d["PACK_SIGNING_PUB"] = base64.b64encode(spki_from_point(_point)).decode("ascii")
    # Ed25519 公钥由私钥派生
    from cryptography.hazmat.primitives.asymmetric import ed25519
    _sk = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(d["UPDATER_SIGN_KEY"]))
    d["UPDATER_VERIFY_KEY"] = _sk.public_key().public_bytes_raw().hex()
    with open(KEYS_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, indent=2)
    print("[build] 已生成新构建密钥 ->", KEYS_FILE)
    print("[build] 警告：请将 LICENSE_SIG_SECRET 同步配置到 Cloudflare Worker 的 env "
          "(WEAUTO_LICENSE_SIG_SECRET)；并妥善保管 build_keys.json（不要提交仓库）。")
    return d


def write_build_key(keys: dict) -> None:
    """主密钥模块：构建期随机，PyArmor 混淆后进 PYZ。"""
    master = pysecrets.token_hex(32)
    with open(BUILD_KEY_PY, "w", encoding="utf-8") as f:
        f.write("# -*- coding: utf-8 -*-\n")
        f.write("# 构建期注入的主密钥（PyArmor 混淆后进 PYZ）。泄密=需重新发行整套密钥。\n")
        f.write('MASTER_HEX = "%s"\n' % master)
    keys["_MASTER_HEX"] = master
    print("[build] 已写入", BUILD_KEY_PY)


def write_release_blob(keys: dict) -> None:
    """把运行时密钥 AES-256-GCM 加密成 release_secrets.enc。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    master = bytes.fromhex(keys["_MASTER_HEX"])
    blob = {k: keys[k] for k in RUNTIME_KEYS if keys.get(k)}
    payload = json.dumps(blob, sort_keys=True).encode("utf-8")
    nonce = pysecrets.token_bytes(12)
    ct = AESGCM(master).encrypt(nonce, payload, b"weauto-secrets-v1")
    with open(BLOB_PATH, "wb") as f:
        f.write(nonce + ct)
    print("[build] 已写入", BLOB_PATH, "(%d keys)" % len(blob))


def write_release_marker() -> None:
    with open(RELEASE_PY, "w", encoding="utf-8") as f:
        f.write("RELEASE = True  # build_release.py generated release marker\n")
    print("[build] 已写入", RELEASE_PY)


def sanitize_config(keys: dict) -> None:
    """生成「净化版 config.py + 签名」到工作目录（发行包用），不改动工作区源码。

    剥离明文密钥行（WEAUATO_CLIENT_SECRET），并对净化后的内容做 HMAC 签名
    （config.py.sig）。发行版启动时校验签名，被篡改即强制走网关。
    """
    work_cfg = os.path.join(WORK_DIR, "config.py")
    work_sig = work_cfg + ".sig"
    os.makedirs(WORK_DIR, exist_ok=True)
    with open(CONFIG_PY, "r", encoding="utf-8") as f:
        lines = f.readlines()
    drop_prefixes = ("WEAUATO_CLIENT_SECRET",)
    out = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith(drop_prefixes):
            indent = line[: len(line) - len(line.lstrip())]
            out.append('%sWEAUATO_CLIENT_SECRET = ""  # 发行版密钥由构建期注入（weauto_license/secrets）\n' % indent)
            continue
        out.append(line)
    with open(work_cfg, "w", encoding="utf-8") as f:
        f.writelines(out)
    key = bytes.fromhex(keys["CONFIG_SIG_KEY"])
    with open(work_cfg, "rb") as f:
        raw = f.read()
    sig = hmac.new(key, raw, hashlib.sha256).digest()
    with open(work_sig, "wb") as f:
        f.write(sig)
    print("[build] 已生成净化版 config.py + config.py.sig ->", WORK_DIR)


def _pyarmor_exe() -> str:
    """用与当前解释器同版本目录下的 pyarmor（保证 pyd 与运行时 Python 版本一致）。"""
    exe = os.path.join(os.path.dirname(sys.executable), "pyarmor.exe")
    if os.path.exists(exe):
        return exe
    return shutil.which("pyarmor") or "pyarmor"


def obfuscate(keys: dict) -> None:
    """PyArmor 混淆核心自有模块（输出到 _pyobf_dist，PyInstaller 通过 pathex 使用）。

    注意：pyarmor 可执行文件必须与目标运行时同一 Python 版本（本机构建=venv_bot 3.10），
    否则生成的 pyarmor_runtime.pyd 在运行时 DLL 加载失败。
    """
    if os.path.exists(OBF_DIR):
        shutil.rmtree(OBF_DIR)
    targets = [
        os.path.join(ROOT, "weauto_license"),
        os.path.join(ROOT, "jev_guard.py"),
        os.path.join(ROOT, "scriptpack.py"),
        os.path.join(ROOT, "device_id.py"),
        os.path.join(ROOT, "_packcrypto_bundle.py"),
    ]
    cmd = [_pyarmor_exe(), "gen", "-O", OBF_DIR] + targets
    print("[build] PyArmor:", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("PyArmor 混淆失败: " + (r.stderr or r.stdout or "")[-1000:])
    print("[build] PyArmor 混淆完成 ->", OBF_DIR)


def run_pyinstaller() -> None:
    """调用 PyInstaller 构建（参数与 .github/workflows/build.yml 对齐）。"""
    args = [
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
        "--name", "WeAuto", "--onefile", "--noupx", "--noconsole",
        "--paths", os.path.join(ROOT, "vendor"),
        "--paths", OBF_DIR,
        "--workpath", WORK_DIR, "--distpath", DIST_DIR,
        "--hidden-import", "bot", "--hidden-import", "wechat_compat",
        "--hidden-import", "wechatauto", "--hidden-import", "forward_hub",
        "--hidden-import", "jev_guard", "--hidden-import", "weauto_stdio",
        "--hidden-import", "cli", "--hidden-import", "weauto_mcp",
        "--hidden-import", "weauto_license", "--hidden-import", "weauto_license.guard",
        "--hidden-import", "mcp", "--collect-all", "mcp",
        "--hidden-import", "uiautomation", "--hidden-import", "comtypes",
        "--hidden-import", "colorama", "--collect-all", "wechatauto",
        "--collect-all", "webview", "--collect-all", "clr_loader",
        "--collect-all", "pythonnet",
        "--hidden-import", "webview.platforms.edgechromium",
        "--hidden-import", "webview.platforms.winforms",
        "--version-file", os.path.join(ROOT, "version_info.txt"),
        "--manifest", os.path.join(ROOT, "manifest.xml"),
        "--splash", os.path.join(ROOT, "splash.png"),
        "--add-data", os.path.join(WORK_DIR, "config.py") + os.pathsep + ".",
        "--add-data", os.path.join(WORK_DIR, "config.py.sig") + os.pathsep + ".",
        "--add-data", os.path.join(ROOT, "templates") + os.pathsep + "templates",
        "--add-data", os.path.join(ROOT, "emojis") + os.pathsep + "emojis",
        "--add-data", os.path.join(ROOT, "prompts") + os.pathsep + "prompts",
        "--add-data", os.path.join(ROOT, "Demo_Image") + os.pathsep + "Demo_Image",
        "--add-data", BLOB_PATH + os.pathsep + ".",
        os.path.join(ROOT, "config_editor.py"),
    ]
    print("[build] PyInstaller:", " ".join(args[:12]), "...")
    r = subprocess.run(args, cwd=ROOT)
    if r.returncode != 0:
        raise SystemExit("PyInstaller 构建失败 rc=%d" % r.returncode)
    print("[build] PyInstaller 完成 ->", DIST_DIR)


def sign_exe(keys: dict) -> None:
    """对 WeAuto.exe 做 Ed25519 签名（SHA-256 摘要），写 WeAuto.exe.sig。"""
    from cryptography.hazmat.primitives.asymmetric import ed25519
    exe = os.path.join(DIST_DIR, "WeAuto.exe")
    if not os.path.exists(exe):
        raise SystemExit("未找到 " + exe)
    sk = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(keys["UPDATER_SIGN_KEY"]))
    with open(exe, "rb") as f:
        digest = hashlib.sha256(f.read()).digest()
    sig = sk.sign(digest)
    with open(exe + ".sig", "wb") as f:
        f.write(sig)
    print("[build] 已签名 WeAuto.exe -> WeAuto.exe.sig")
    # 校验自签
    pub = sk.public_key()
    pub.verify(sig, digest)
    print("[build] 自签校验通过")


def make_artifact_zip() -> None:
    exe = os.path.join(DIST_DIR, "WeAuto.exe")
    sig = exe + ".sig"
    if not (os.path.exists(exe) and os.path.exists(sig)):
        print("[build] 跳过 artifact zip（缺少 exe 或 sig）")
        return
    zpath = os.path.join(DIST_DIR, "weauto_artifact.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(exe, "WeAuto.exe")
        z.write(sig, "WeAuto.exe.sig")
    print("[build] 已生成", zpath)


def main() -> None:
    ap = argparse.ArgumentParser(description="WeAuto release build")
    ap.add_argument("--skip-obfuscate", action="store_true")
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--skip-sign", action="store_true")
    args = ap.parse_args()

    sys.path.insert(0, ROOT)  # 允许 import _packcrypto_bundle
    keys = _load_or_create_keys()
    write_build_key(keys)
    write_release_blob(keys)
    write_release_marker()
    sanitize_config(keys)
    if not args.skip_obfuscate:
        obfuscate(keys)
    else:
        print("[build] 已跳过 PyArmor 混淆")
    if args.skip_build:
        print("[build] 已跳过 PyInstaller（--skip-build）")
        return
    run_pyinstaller()
    if not args.skip_sign:
        sign_exe(keys)
        make_artifact_zip()


if __name__ == "__main__":
    main()
