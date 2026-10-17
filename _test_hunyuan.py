#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""HunyuanOCR-1.5 本地服务实测：等待就绪 -> 发图 -> 计时输出。"""
import base64
import os
import sys
import time

import requests

BASE = "http://127.0.0.1:8081/v1"
HERE = os.path.dirname(os.path.abspath(__file__))
img_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "_msg_test", "test_img.png")

# 1. 等待 server 就绪
ready = False
for i in range(90):
    try:
        r = requests.get(BASE + "/models", timeout=2)
        if r.status_code == 200:
            ready = True
            print(f"server 就绪（等待 {i+1}s）")
            break
    except Exception:
        pass
    time.sleep(1)
if not ready:
    print("server 未就绪，退出")
    sys.exit(1)

# 2. 发图测 OCR（HunyuanOCR 官方通用文字识别指令）
with open(img_path, "rb") as f:
    b64 = base64.b64encode(f.read()).decode()
prompt = "Text Recognition."
data = {"messages": [{"role": "user", "content": [
    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
    {"type": "text", "text": prompt},
]}], "max_tokens": 1024}

t0 = time.time()
r = requests.post(BASE + "/chat/completions", json=data, timeout=(5, 300))
enc_t = time.time()
r.raise_for_status()
out = r.json()["choices"][0]["message"]["content"].strip()
t1 = time.time()
print(f"总耗时: {t1 - t0:.2f}s（含图片编码）")
print("usage:", r.json().get("usage"))
print("---- 识别结果 ----")
print(out)
