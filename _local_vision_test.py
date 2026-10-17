# -*- coding: utf-8 -*-
"""本地视觉模块冒烟测试：GPU 探测 → 多源安装 → OCR 实测。

需真实环境（本机 Python/.venv），不进 CI（EXE 冻结环境无法在线装包）。
用法：.venv_bot/Scripts/python.exe _local_vision_test.py [图片路径]
"""
import glob
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from local_vision import detect_gpu, ensure_rapidocr, recognize_image_ocr


def main():
    gpus = detect_gpu()
    print('[1] GPU 探测:', gpus or '无（将用 CPU）')

    print('[2] 依赖安装（首次约 100MB，多镜像源）...')
    t0 = time.time()
    ok, info = ensure_rapidocr('auto')
    print(f'    ensure_rapidocr: ok={ok} 耗时={time.time()-t0:.1f}s info={info}')
    if not ok:
        sys.exit(1)

    img = sys.argv[1] if len(sys.argv) > 1 else None
    if not img:
        cands = sorted(glob.glob(os.path.join(
            os.path.dirname(os.path.abspath(__file__)), '_msg_test', '*.png')))
        img = cands[0] if cands else None
    if not img:
        print('[3] 无测试图片，跳过识别'); return

    print(f'[3] OCR 实测: {img}')
    t0 = time.time()
    ok2, text, info2 = recognize_image_ocr(img, 'auto', auto_install=False)
    dt = time.time() - t0
    print(f'    ok={ok2} 耗时={dt:.2f}s info={info2}')
    print(f'    text: {(text or "")[:300] or "(空)"}')


if __name__ == '__main__':
    main()
