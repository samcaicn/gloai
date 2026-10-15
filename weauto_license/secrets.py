# -*- coding: utf-8 -*-
"""WeAuto 发行版密钥体系（防反编译/防破解整改核心）。

设计目标
--------
1) 任何敏感密钥不再明文存在于 config.py / 源码常量 / 仓库：
   - WEAUTO_CLIENT_SECRET（Jev 通信 HMAC）
   - LICENSE_SIG_SECRET（授权通信 HMAC）
   - CACHE_SIG_SALT（激活缓存 HMAC 盐）
   - DEVICE_SALT（设备指纹盐）
   - CONFIG_SIG_KEY（config.py 完整性签名）
   - QUOTA_LEDGER_SALT（本地配额记账 HMAC 盐）
   - WPK_MASTER_HEX（话术包 AES master）
   - UPDATER_VERIFY_KEY（更新包验签公钥，Ed25519）
   - PACK_SIGNING_PUB（.wpk 验签公钥，ECDSA P-256）
2) 构建期随机生成全部密钥 → 用主密钥 AES-256-GCM 加密成 release_secrets.enc
   → 作为 data 打进 EXE；主密钥在 weauto_license/_build_key.py（构建期随机生成，
   经 PyArmor 混淆后进 PYZ 字节码）。没有任何一处明文密钥随包分发。
3) 开发态（无 _release.py / 无 blob）完整回退旧路径（config.py / 环境变量 /
   旧默认值），本地开发行为不变。

使用
----
    from weauto_license import secrets as _sec
    secret = _sec.get("WEAUATO_CLIENT_SECRET") or fallback
    key = _sec.get_bytes("CONFIG_SIG_KEY")   # 返回 bytes 或 None
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys

_BLOB_NAME = "release_secrets.enc"
_BLOB_AAD = b"weauto-secrets-v1"
_CACHE: dict = {}


def is_release() -> bool:
    """与 guard.py 同一套发行判定：_release.py 存在 或 环境变量。"""
    try:
        from weauto_license import _release as _rel
        return bool(getattr(_rel, "RELEASE", False))
    except Exception:
        return os.environ.get("WEAUTO_RELEASE_BUILD", "") == "1"


def project_root() -> str:
    """开发态=项目根；冻结态=EXE 目录下的 weauto 子目录（config.py 等 add-data 落在这里）。"""
    if getattr(sys, "frozen", False):
        d = os.path.join(os.path.dirname(sys.executable), "weauto")
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
        return d
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _blob_path() -> str:
    # frozen 态 _MEIPASS 是临时解包目录，data 文件也落在那里；
    # 优先 _MEIPASS，其次 EXE 目录，最后项目根（开发）。
    for base in (
        getattr(sys, "_MEIPASS", None),
        os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else None,
        project_root(),
    ):
        if not base:
            continue
        p = os.path.join(base, _BLOB_NAME)
        if os.path.exists(p):
            return p
    return os.path.join(project_root(), _BLOB_NAME)


def _build_key_hex() -> str:
    """构建期注入的主密钥（_build_key.py 由构建脚本生成，PyArmor 混淆后进 PYZ）。"""
    try:
        from weauto_license import _build_key as _bk
        v = getattr(_bk, "MASTER_HEX", "")
        if isinstance(v, str) and len(v) == 64:
            return v
    except Exception:
        pass
    # 开发态回退：无 _build_key.py 时用环境变量（仅开发用，不随发行包分发）
    return os.environ.get("WEAUTO_DEV_MASTER_HEX", "")


def _load_blob() -> dict:
    """解密 release_secrets.enc；失败返回空 dict（调用方回退默认值）。绝不抛异常。"""
    if not is_release():
        return {}
    if "blob" in _CACHE:
        return _CACHE["blob"]
    out: dict = {}
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        mk_hex = _build_key_hex()
        if len(mk_hex) != 64:
            return {}
        mk = bytes.fromhex(mk_hex)
        with open(_blob_path(), "rb") as f:
            raw = f.read()
        nonce, ct = raw[:12], raw[12:]
        data = AESGCM(mk).decrypt(nonce, ct, _BLOB_AAD)
        obj = json.loads(data.decode("utf-8"))
        if isinstance(obj, dict):
            out = obj
    except Exception:
        out = {}
    _CACHE["blob"] = out
    return out


def get(name: str, default: str = "") -> str:
    """取一个密钥/配置字符串。发行版从加密 blob 读；开发态回退 default。"""
    blob = _load_blob()
    v = blob.get(name)
    if isinstance(v, str) and v:
        return v
    return default


def get_bytes(name: str):
    """取 hex 编码的密钥为 bytes；缺/非法返回 None。"""
    v = get(name)
    if not v:
        return None
    try:
        return bytes.fromhex(v)
    except Exception:
        return None


# ---------------------------------------------------------------- config.py 签名
# 发行版 config.py 是明文 add-data，用户可改（这也是原直连旁路的入口）。
# 构建期对 config.py 计算 HMAC-SHA256（密钥 CONFIG_SIG_KEY）写 config.py.sig；
# 发行版启动时校验：被篡改 → 告警，且 AI 相关配置一律不再信任（强制走网关）。

def _config_paths():
    root = project_root()
    return os.path.join(root, "config.py"), os.path.join(root, "config.py.sig")


def config_signature_valid() -> bool:
    """发行版下校验 config.py 完整性。开发态恒 True。"""
    if not is_release():
        return True
    key = get_bytes("CONFIG_SIG_KEY")
    if not key:
        return False  # 构建异常（无签名密钥）→ 按不信任处理
    try:
        cfg_path, sig_path = _config_paths()
        with open(cfg_path, "rb") as f:
            raw = f.read()
        with open(sig_path, "rb") as f:
            want = f.read()
        got = hmac.new(key, raw, hashlib.sha256).digest()
        return hmac.compare_digest(got, want)
    except Exception:
        return False


def sign_config_file(cfg_path: str, sig_path: str) -> bool:
    """对一份 config.py 内容重签（config_editor 保存后调用，保证合法修改不被误判）。

    发行版进程内持有 CONFIG_SIG_KEY 才能重签；开发态/无密钥直接跳过。
    """
    key = get_bytes("CONFIG_SIG_KEY")
    if not key:
        return False
    try:
        with open(cfg_path, "rb") as f:
            raw = f.read()
        sig = hmac.new(key, raw, hashlib.sha256).digest()
        with open(sig_path, "wb") as f:
            f.write(sig)
        return True
    except Exception:
        return False


def sign_project_config() -> bool:
    """对项目当前 config.py 重签（供 config_editor 保存后调用）。"""
    cfg_path, sig_path = _config_paths()
    return sign_config_file(cfg_path, sig_path)


# ---------------------------------------------------------------- 安全默认值（发行版强制）
# 发行版下 AI 链路只认这几项，config.py 里被篡改的 DEEPSEEK_BASE_URL / API_KEY
# 等字段一律不参与。base_url 是产品自己的 Worker，域名以构建期 GATEWAY_HOST
# （blob）为准，这里是开发/异常态回退。

def _gateway_host() -> str:
    host = get("GATEWAY_HOST", "")
    if isinstance(host, str) and host.startswith("http"):
        return host.rstrip("/")
    return "https://weauto-app.pages.dev"


GATEWAY_BASE_URL = _gateway_host() + "/ai/v1"


def secure_gateway() -> dict:
    """发行版强制网关配置（不读 config 的任何 AI 自填字段）。"""
    return {
        "base_url": GATEWAY_BASE_URL,
        "api_key": "free-tier",      # 卡密为空时的凭证，Worker 端按机器指纹配额
        "headers": {},
    }


def redact(text) -> str:
    """把已知密钥从日志/异常里抹掉。"""
    if not isinstance(text, str):
        text = str(text)
    blob = _load_blob()
    for k, v in blob.items():
        if isinstance(v, str) and len(v) >= 8:
            text = text.replace(v, "[REDACTED]")
    return text
