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
# 配对私钥在 weauto_license/pack_signing_key.pem（本地 600 权限，永不进仓库）。
PACK_VERIFY_PUBKEY_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEme+K+KHbIccO7/vfRVJ1cE2aRJgaZ8X2qWxlMhkIhnkXwpQ2EXh4nG2nVVv3bfS9FuoFP9k1po47OtGIM75GYw=="
)

# master key（与生成端一致）。泄密不致命：没有签名私钥，包连验签都过不了。
# 生成端可用 PACK_MASTER_HEX 覆盖；此处与 tools/scriptpack/packcrypto.py 的 TEST_MASTER_HEX 保持一致。
MASTER_HEX = os.environ.get("PACK_MASTER_HEX", "9f2b7c41d3e5a6081b4c9d2e7f0a3b5c8d6e4f2a1c0b9d8e7f6a5b4c3d2e1f0a")


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
        return pc.point_from_spki(base64.b64decode(PACK_VERIFY_PUBKEY_B64))
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
                "expired": time.time() >= h.get("expires_at", 0),
                "device_bound": bool(h.get("device_hashes")),
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
    now = int(now_ts if now_ts is not None else time.time())
    for meta in list_packs():
        if meta["pack_id"] == pack_id and not meta["error"]:
            try:
                with open(os.path.join(PROMPTS_DIR, meta["file"]), "rb") as f:
                    data = f.read()
                dh = bytes.fromhex(device_hash_hex or _device_hash_hex())
                pub = _pub_point()
                payload = pc.parse_and_decrypt(
                    data, bytes.fromhex(MASTER_HEX), dh, now, pub_point=pub
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


if __name__ == "__main__":
    print("可用话术包：")
    for m in list_packs():
        mark = "[过期]" if m["expired"] else "[有效]"
        err = (" 错误:" + m["error"]) if m["error"] else ""
        print("  %s %s v%s by %s 到期=%s%s" % (
            mark, m["name"], m["version"], m["author"], m["expires_at"], err))
