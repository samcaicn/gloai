# -*- coding: utf-8 -*-
"""fetch_models.py —— 拉取离线 OCR / ASR 模型到本地（用 bbt-zb 账号密码）

用途：
  bbt-zb 的 OCR 使用前端 PP-OCRv4（onnx）技术；语音转写可复用 bbt-zb 的模型。
  本脚本用你提供的 bbt-zb 账号密码登录，把模型文件下载到
  src-tauri/python/ocr_models/ 与 src-tauri/python/asr_models/，
  供 wxauto_sidecar + ocrengine/asrengine 离线调用（无任何远程依赖）。

用法（在你本机、有网络的环境执行）：
  python tools/fetch_models.py \
      --base-url https://bbt-zb.pages.dev \
      --telephone 18602890023 --password 666666

重要：
  * OCR/ASR 的「真实下载地址」需要在 bbt-zb 前端（用上面账号登录后）确认——
    打开 OCR 相关页面，看它从哪个 URL 加载 .onnx / 字典文件，把地址填到下面
    OCR_FILES / ASR_FILES 即可。脚本已给出常见占位，按需修改。
  * 若 bbt-zb 未直接暴露模型 URL，可改用官方 PP-OCRv4 模型（见 README 注释），
    效果一致（同为 PP-OCRv4 检测+识别）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys

try:
    import requests
except ImportError:
    sys.exit("需要 requests：pip install requests")


def login(base_url: str, telephone: str, password: str) -> str:
    """account/login → token（与 bbt-zb 前端一致的单端点表单协议）。"""
    url = base_url.rstrip("/") + "/api"
    resp = requests.post(
        url,
        data={
            "action": "account/login",
            "token": "",
            "condition": json.dumps({"telephone": telephone, "password": password}),
        },
        timeout=20,
    )
    resp.raise_for_status()
    result = resp.json().get("result", {})
    if not result.get("status"):
        raise RuntimeError(f"登录失败：{result.get('msg')}")
    token = result.get("data", {}).get("token")
    if not token:
        raise RuntimeError("响应缺少 token")
    return token


# ── 模型清单：把「本地文件名 → 远程 URL」填好再跑 ──────────────────────────
# 例：在 bbt-zb 前端 OCR 页面按 F12 → Network，找到加载的 .onnx / .txt 地址，
# 直接粘进来。以下为常见 PP-OCRv4 命名占位，URL 需替换为 bbt-zb 真实地址。
OCR_BASE = "https://bbt-zb.pages.dev/models"
ASR_BASE = "https://bbt-zb.pages.dev/models"

OCR_FILES = {
    "ch_PP-OCRv4_det_infer.onnx": f"{OCR_BASE}/ch_PP-OCRv4_det_infer.onnx",
    "ch_PP-OCRv4_rec_infer.onnx": f"{OCR_BASE}/ch_PP-OCRv4_rec_infer.onnx",
    "ppocr_keys_v1.txt": f"{OCR_BASE}/ppocr_keys_v1.txt",
    # 方向分类（可选，没有也能跑，只是不做 180° 校正）：
    # "ch_ppocr_mobile_v2.0_cls_infer.onnx": f"{OCR_BASE}/ch_ppocr_mobile_v2.0_cls_infer.onnx",
}

# ASR：sherpa-onnx 中文模型（zipformer 或 paraformer），连同 tokens 一起下载。
# 把 ASR 模型目录整体放 asr_models/，并写一个 manifest.json（见 asrengine.py 头部说明）。
ASR_FILES = {
    "tokens.txt": f"{ASR_BASE}/asr/tokens.txt",
    "encoder-epoch-99-avg-1.onnx": f"{ASR_BASE}/asr/encoder-epoch-99-avg-1.onnx",
    "decoder-epoch-99-avg-1.onnx": f"{ASR_BASE}/asr/decoder-epoch-99-avg-1.onnx",
    "joiner-epoch-99-avg-1.onnx": f"{ASR_BASE}/asr/joiner-epoch-99-avg-1.onnx",
}


def download(url: str, dest: str, token: str) -> None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with requests.get(url, headers=headers, stream=True, timeout=120) as r:
        if r.status_code == 404:
            raise RuntimeError(f"404（URL 可能不对，请在 bbt-zb 前端确认真实地址）: {url}")
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_content(1 << 16):
                f.write(chunk)
    if os.path.getsize(dest) < 1024:
        raise RuntimeError(f"下载文件过小（可能非模型）：{dest}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="https://bbt-zb.pages.dev")
    ap.add_argument("--telephone", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "src-tauri", "python"))
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    ocr_dir = os.path.join(out, "ocr_models")
    asr_dir = os.path.join(out, "asr_models")
    os.makedirs(ocr_dir, exist_ok=True)
    os.makedirs(asr_dir, exist_ok=True)

    print(f"[1/3] 登录 {args.base_url} ...")
    token = login(args.base_url, args.telephone, args.password)
    print("      登录成功，已拿到 token（仅用于本次下载鉴权）。")

    print("[2/3] 下载 OCR 模型 →", ocr_dir)
    for name, url in OCR_FILES.items():
        if name.startswith("#"):
            continue
        dest = os.path.join(ocr_dir, name)
        if os.path.exists(dest):
            print(f"      已存在，跳过: {name}")
            continue
        try:
            download(url, dest, token)
            print(f"      ✓ {name}")
        except Exception as e:
            print(f"      ✗ {name}: {e}")

    print("[3/3] 下载 ASR 模型 →", asr_dir)
    for name, url in ASR_FILES.items():
        if name.startswith("#"):
            continue
        dest = os.path.join(asr_dir, name)
        if os.path.exists(dest):
            print(f"      已存在，跳过: {name}")
            continue
        try:
            download(url, dest, token)
            print(f"      ✓ {name}")
        except Exception as e:
            print(f"      ✗ {name}: {e}")

    print("\n完成。若某文件 404，请到 bbt-zb 前端（登录后）按 F12 Network 找到真实模型地址，")
    print("填到本脚本顶部 OCR_FILES / ASR_FILES 后重跑；ASR 还需在 asr_models/ 写 manifest.json（格式见 asrengine.py）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
