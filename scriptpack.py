# -*- coding: utf-8 -*-
"""WeAuto 话术包（.wpk）客户端解析器。

从 Android 端（jev-chat-jarvis）移植的 ScriptPack 体系，纯 Python 标准库实现：
  - 生成端：tools/scriptpack/（make_pack.py + packcrypto.py，与 Android 完全同布局）
  - 本文件：运行时加载 prompts/*.wpk，验签 + 解密 + 到期判定后返回话术内容

安全链（任何一步失败即拒，[PackError.code] 指明原因）：
  1. magic / 版本 / 长度
  2. ECDSA P-256 验签（覆盖 header + 密文，防伪造、防改到期时间）
  3. 设备允许列表（绑机包）
  4. header.expires_at 到期判定
  5. AES-256-GCM 解密（tag 失败 = 密钥错 / 包被改）
  6. payload 摘要复核 + header/payload 双写 expires_at 一致性

用法：
  import scriptpack
  packs = scriptpack.list_packs()          # -> [{pack_id,name,version,author,expires_at,expired}]
  data  = scriptpack.load_pack("pack_id")  # -> {system_prompt, style, few_shot, params, relationship_hint} 或抛 PackError
"""

import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools", "scriptpack"))
try:
    import packcrypto as pc
except Exception:  # 打包进 EXE 时 tools 路径不可用 → 兜底尝试内置副本
    try:
        import _packcrypto_bundle as pc
    except Exception:
        pc = None

PROMPTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompts")

# 话术包验签公钥（SPKI base64，EC prime256v1）。
# 【防破解整改】发行版从构建期注入的 secrets blob 读取新密钥对的公钥；
# 配对私钥只在构建机 / CI secret 中，**不再随仓库分发**（旧私钥已作废删除）。
# 开发态回退到仓库内开发公钥，便于本地生成测试包。
_DEV_PACK_VERIFY_PUBKEY_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEme+K+KHbIccO7/vfRVJ1cE2aRJgaZ8X2qWxlMhkIhnkXwpQ2EXh4nG2nVVv3bfS9FuoFP9k1po47OtGIM75GYw=="
)


def _pack_verify_pubkey_b64() -> str:
    try:
        from weauto_license import secrets as _sec
        if _sec.is_release():
            v = _sec.get("PACK_SIGNING_PUB", "")
            if v:
                return v
    except Exception:
        pass
    return _DEV_PACK_VERIFY_PUBKEY_B64


# master key（与生成端一致）。泄密不致命：没有签名私钥，包连验签都过不了。
# 【防破解整改】发行版从 secrets blob 读取构建期生成的正式 master；
# 开发态保持与生成端 TEST_MASTER_HEX 一致（仅本地开发用）。
_DEV_MASTER_HEX = "9f2b7c41d3e5a6081b4c9d2e7f0a3b5c8d6e4f2a1c0b9d8e7f6a5b4c3d2e1f0a"


def _pack_master_hex() -> str:
    try:
        from weauto_license import secrets as _sec
        if _sec.is_release():
            v = _sec.get("WPK_MASTER_HEX", "")
            if v:
                return v
    except Exception:
        pass
    return os.environ.get("PACK_MASTER_HEX", _DEV_MASTER_HEX)


class PackError(Exception):
    """话术包错误。code 为机器可读原因（signature/expired/device/decrypt/...）。"""

    def __init__(self, code, detail=""):
        super().__init__("scriptpack: %s %s" % (code, detail))
        self.code = code
        self.detail = detail


def _pub_point():
    if pc is None:
        raise PackError("crypto-unavailable", "缺少 packcrypto 实现")
    try:
        import base64
        return pc.point_from_spki(base64.b64decode(_pack_verify_pubkey_b64()))
    except Exception as e:
        raise PackError("bad-pubkey", str(e))


def _device_hash_hex():
    """本机设备指纹（16B hex）。未取到设备 ID 时返回全零（不绑机包不受影响）。"""
    try:
        import device_id
        dev = device_id.get_device_id()
        if dev:
            return pc.device_hash_hex(dev)
    except Exception:
        pass
    return "00" * 16


def _trusted_now():
    """可信时钟（防改系统时间延长授权）。失败回退系统时间。"""
    try:
        from weauto_license import clock as _clk
        return _clk.trusted_now()
    except Exception:
        return int(time.time())


def list_packs():
    """扫描 prompts/*.wpk，返回可读 header 摘要（不验签不解密）。损坏文件跳过并附 error。"""
    out = []
    if pc is None:
        return out
    try:
        entries = sorted(os.listdir(PROMPTS_DIR))
    except Exception:
        return out
    for name in entries:
        if not name.lower().endswith(".wpk"):
            continue
        p = os.path.join(PROMPTS_DIR, name)
        try:
            with open(p, "rb") as f:
                data = f.read()
            h = pc.read_header(data)
            out.append({
                "file": name,
                "pack_id": h.get("pack_id"),
                "name": h.get("name"),
                "version": h.get("version"),
                "author": h.get("author", ""),
                "expires_at": h.get("expires_at", 0),
                "expired": _trusted_now() >= h.get("expires_at", 0),
                "device_bound": bool(h.get("device_hashes")),
                "reply_quota": int(h.get("reply_quota", 0) or 0),  # 离线授权次数额度
                "error": None,
            })
        except Exception as e:
            out.append({"file": name, "pack_id": None, "name": name, "version": 0,
                        "author": "", "expires_at": 0, "expired": False,
                        "device_bound": False, "error": str(e)})
    return out


def load_pack(pack_id, now_ts=None, device_hash_hex=None):
    """加载指定 pack_id 的话术包（验签+解密+到期）。成功返回 payload dict，失败抛 PackError。"""
    if pc is None:
        raise PackError("crypto-unavailable", "缺少 packcrypto 实现")
    now = int(now_ts if now_ts is not None else _trusted_now())
    for meta in list_packs():
        if meta["pack_id"] == pack_id and not meta["error"]:
            try:
                with open(os.path.join(PROMPTS_DIR, meta["file"]), "rb") as f:
                    data = f.read()
                dh = bytes.fromhex(device_hash_hex or _device_hash_hex())
                pub = _pub_point()
                payload = pc.parse_and_decrypt(
                    data, bytes.fromhex(_pack_master_hex()), dh, now, pub_point=pub
                )
                return payload
            except pc.PackCryptoError as e:
                raise PackError("reject", str(e))
            except PackError:
                raise
            except Exception as e:
                raise PackError("parse-error", str(e))
    raise PackError("not-found", "prompts/ 下没有 pack_id=%s 的话术包" % pack_id)


def active_prompt(pack_id, fallback_prompt=""):
    """取激活话术包的 system_prompt + 风格信息；包不存在/过期/损坏 → 回退 fallback_prompt。

    返回 {"system_prompt": str, "style": dict, "few_shot": list, "relationship_hint": str}
    """
    if not pack_id:
        return {"system_prompt": fallback_prompt, "style": {}, "few_shot": [],
                "relationship_hint": "", "pack": None}
    try:
        payload = load_pack(pack_id)
        sp = payload.get("system_prompt") or fallback_prompt
        return {
            "system_prompt": sp,
            "style": payload.get("style") or {},
            "few_shot": payload.get("few_shot") or [],
            "relationship_hint": payload.get("relationship_hint") or "",
            "pack": {"pack_id": pack_id,
                     "name": (payload.get("_header") or {}).get("name", pack_id)},
        }
    except PackError:
        return {"system_prompt": fallback_prompt, "style": {}, "few_shot": [],
                "relationship_hint": "", "pack": None}


def load_pack_meta(pack_id):
    """按 pack_id 找 prompts/*.wpk 的 header 摘要（不验签不解密）。找不到返回 None。"""
    for meta in list_packs():
        if meta["pack_id"] == pack_id and not meta["error"]:
            return meta
    return None


def import_offline_pack(src_path, dst_dir=None):
    """导入离线授权文件（.wpk）到 prompts/ 并做完整校验。

    - 验签 + 解密 + 到期判定（scriptpack 安全链）
    - 带 reply_quota 的包必须绑定本机（device_hashes 含本机 device_hash），否则 PackError(device)
    - 同名 pack_id 覆盖导入（幂等）
    返回 (meta, payload)；任何失败抛 PackError。
    """
    import shutil
    MAX_PACK_SIZE = 64 * 1024 * 1024  # 64MB：话术包含音视频素材场景放宽，仍防恶意超大文件塞盘
    with open(src_path, "rb") as f:
        data = f.read()
    if len(data) > MAX_PACK_SIZE:
        raise PackError("size", "授权文件过大（>64MB），已拒绝导入")
    h = pc.read_header(data)
    pack_id = h.get("pack_id")
    if not pack_id:
        raise PackError("bad-pack", "授权文件缺少 pack_id")
    # 完整安全链校验（验签/绑机/到期/解密）
    payload = load_pack(pack_id, device_hash_hex=_device_hash_hex())
    quota = int(h.get("reply_quota", 0) or 0)
    if quota > 0 and not h.get("device_hashes"):
        raise PackError("device", "带回复次数的授权文件必须绑定设备，本文件未绑机")
    if dst_dir is None:
        dst_dir = PROMPTS_DIR
    try:
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, os.path.basename(src_path))
        shutil.copyfile(src_path, dst)
    except Exception as e:
        raise PackError("io", "写入 prompts/ 失败: %s" % e)
    meta = load_pack_meta(pack_id)
    return meta, payload


def offline_license_info():
    """本机离线授权摘要（供 UI 展示）：识别码 / 已导入话术包 / 剩余次数。

    返回 dict：{device_id, device_hash_hex, short_code, pack_id, pack_name,
                quota_total, quota_used, expires_at, active}
    - quota_total/used 来自 ledger（授权记账）；话术包元信息来自 prompts/*.wpk。
    """
    info = {
        "device_id": "",
        "device_hash_hex": "",
        "short_code": "",
        "pack_id": "",
        "pack_name": "",
        "quota_total": 0,
        "quota_used": 0,
        "expires_at": 0,
        "active": False,
        "error": "",
    }
    try:
        import device_id as _did
        dev = _did.get_device_id()
        info["device_id"] = dev
        if dev:
            info["device_hash_hex"] = pc.device_hash_hex(dev)
            info["short_code"] = _did.eight_digit_code()
    except Exception as e:
        info["error"] = "device: %s" % e
    try:
        from weauto_license import ledger as _lg
        lic = _lg.offline_license()
        if lic:
            info["pack_id"] = lic.get("pack_id", "")
            info["quota_total"] = int(lic.get("total", 0) or 0)
            info["quota_used"] = int(lic.get("used", 0) or 0)
            info["expires_at"] = int(lic.get("expires_at", 0) or 0)
            if lic.get("pack_id"):
                meta = load_pack_meta(lic["pack_id"])
                if meta:
                    info["pack_name"] = meta.get("name", lic["pack_id"])
                    info["active"] = not meta.get("expired", False) \
                        and int(lic.get("expires_at", 0) or 0) >= _trusted_now()
                # 防"换包骗状态"：对激活包做完整验签+绑机+解密（load_pack），
                # 被替换/损坏/签名失效 → active 置 False 并标注原因。
                try:
                    load_pack(lic["pack_id"], device_hash_hex=info["device_hash_hex"])
                except Exception as e:
                    info["active"] = False
                    if not info["error"]:
                        info["error"] = "授权包校验失败: %s" % getattr(e, "detail", e)
    except Exception as e:
        if not info["error"]:
            info["error"] = "ledger: %s" % e
    return info


if __name__ == "__main__":
    print("可用话术包：")
    for m in list_packs():
        mark = "[过期]" if m["expired"] else "[有效]"
        err = (" 错误:" + m["error"]) if m["error"] else ""
        quota = (" 次数:%d" % m["reply_quota"]) if m["reply_quota"] else ""
        print("  %s %s v%s by %s 到期=%s%s%s" % (
            mark, m["name"], m["version"], m["author"], m["expires_at"], quota, err))
