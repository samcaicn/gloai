#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""HunyuanOCR-1.5 GGUF 流式断点续传下载（hf-mirror 国内源）。

用法（沙箱外 / .venv_bot）：
    .venv_bot/Scripts/python.exe _dl_hunyuan.py
"""
import os
import sys
import time

import requests

MIRROR = "https://hf-mirror.com"
REPO = "prithivMLmods/HunyuanOCR-1.5-GGUF-Updated"
HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(HERE, "vision_server", "models")
os.makedirs(MODEL_DIR, exist_ok=True)

# 基于 Qwen2.5-VL-3B 的 OCR 专用模型；Q4_K_M 质量均衡，mmproj-q8_0 省显存
FILES = [
    "HunyuanOCR.Q4_K_M.gguf",
    "HunyuanOCR.mmproj-q8_0.gguf",
]


def dl(fname):
    url = f"{MIRROR}/{REPO}/resolve/main/{fname}"
    path = os.path.join(MODEL_DIR, fname)
    resume = os.path.getsize(path) if os.path.exists(path) else 0
    headers = {"Range": f"bytes={resume}-"} if resume else {}
    if resume:
        print(f"[dl] {fname} 续传 {resume/1024/1024:.1f}MB ...")
    else:
        print(f"[dl] {fname} 开始下载 ...")
    t0 = time.time()
    last = 0
    with requests.get(url, headers=headers, stream=True, timeout=30,
                      allow_redirects=True) as r:
        r.raise_for_status()
        mode = "ab" if (resume and r.status_code == 206) else "wb"
        total = resume
        with open(path, mode) as f:
            for chunk in r.iter_content(1024 * 1024):
                if not chunk:
                    continue
                f.write(chunk)
                total += len(chunk)
                now = time.time()
                if now - last >= 2:
                    last = now
                    print(f"    {fname}: {total/1024/1024:.1f}MB "
                          f"({(total-resume)/(now-t0+1e-9)/1024/1024:.2f} MB/s)",
                          end="\r")
    print(f"\n[ok] {fname} -> {total/1024/1024:.1f}MB")
    return total


def main():
    for fn in FILES:
        try:
            dl(fn)
        except Exception as e:
            print(f"[ERR] {fn}: {e}")
            sys.exit(1)
    print("ALL DONE")


if __name__ == "__main__":
    main()
