# -*- coding: utf-8 -*-
"""ocrengine.py —— 离线图片 OCR 引擎（PP-OCRv4 · ONNX Runtime，无网络依赖）

设计目标：
  * 完全离线：模型文件放在本地目录（默认脚本同级的 ocr_models/，可用
    OCR_MODEL_DIR 环境变量覆盖），不调用任何远程 OCR 服务。
  * 与 bbt-zb 前端 OCR 同技术栈：PP-OCRv4 检测 + 识别（det + rec + 可选 cls），
    权重用 .onnx 格式（bbt-zb 前端即 PaddleOCR/PP-OCRv4 的 onnx 导出）。
  * 懒加载：首次 OCR 时才 import onnxruntime/opencv/numpy，缺依赖即返回 None，
    不影响纯文本消息收发。

所需文件（OCR_MODEL_DIR 下）：
  ch_PP-OCRv4_det_infer.onnx   检测模型
  ch_PP-OCRv4_rec_infer.onnx   识别模型
  ppocr_keys_v1.txt            中文字典（6629 类 + blank）
  ch_ppocr_mobile_v2.0_cls_infer.onnx  （可选）方向分类，省略则不做 180° 校正

依赖（本机需装）：
  pip install onnxruntime opencv-python-headless numpy
（bbt-zb 的 ocr 模型与字典可从 bbt-zb 站点用账号密码获取，见 tools/fetch_models.py）
"""

from __future__ import annotations

import os
from pathlib import Path

# 推理超参（与 PaddleOCR PP-OCRv4 默认对齐；如结果异常先调这里）
DET_LIMIT_SIDE_LEN = 960      # 检测前置：长边缩放到该值以内
DET_THRESHOLD = 0.3           # 概率图二值化阈值
DET_BOX_THRESHOLD = 0.5       # 后处理 box 阈值
DET_UNCLIP_RATIO = 2.0        # 框外扩比例
REC_IMG_H = 48                # 识别输入高度
REC_IMG_MAX_W = 320           # 识别输入最大宽度（短边 pad）
REC_BATCH = False


class OcrEngineError(Exception):
    pass


class OcrEngine:
    def __init__(self, model_dir: str):
        self.model_dir = model_dir
        self.det_path = os.path.join(model_dir, "ch_PP-OCRv4_det_infer.onnx")
        self.rec_path = os.path.join(model_dir, "ch_PP-OCRv4_rec_infer.onnx")
        self.cls_path = os.path.join(model_dir, "ch_ppocr_mobile_v2.0_cls_infer.onnx")
        self.dict_path = os.path.join(model_dir, "ppocr_keys_v1.txt")
        for p in (self.det_path, self.rec_path, self.dict_path):
            if not os.path.isfile(p):
                raise OcrEngineError(f"缺少 OCR 模型/字典文件：{p}（OCR_MODEL_DIR={model_dir}）")
        self._load()

    def _load(self):
        import numpy as np
        import onnxruntime as ort
        import cv2

        self.np = np
        self.cv2 = cv2
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 2
        self.det_sess = ort.InferenceSession(self.det_path, opts, providers=["CPUExecutionProvider"])
        self.rec_sess = ort.InferenceSession(self.rec_path, opts, providers=["CPUExecutionProvider"])
        self.has_cls = os.path.isfile(self.cls_path)
        if self.has_cls:
            self.cls_sess = ort.InferenceSession(self.cls_path, opts, providers=["CPUExecutionProvider"])
        else:
            self.cls_sess = None
        # 字典
        with open(self.dict_path, "r", encoding="utf-8") as f:
            lines = [l.strip("\n") for l in f]
        # ppocr_keys_v1 末尾通常是换行，过滤空串；索引 0 作 blank
        self.char_list = [c for c in lines if c != ""]
        # 若字典里没有显式 blank，识别时按索引 0 = blank 处理
        # 记录 det 输入名
        self.det_in = self.det_sess.get_inputs()[0].name
        self.rec_in = self.rec_sess.get_inputs()[0].name

    # ---- 检测 ----
    def _det_preprocess(self, img):
        cv2 = self.cv2
        h, w = img.shape[:2]
        # 等比缩放到长边 <= DET_LIMIT_SIDE_LEN
        ratio = min(DET_LIMIT_SIDE_LEN / max(h, w), 1.0)
        rh, rw = int(round(h * ratio)), int(round(w * ratio))
        if rh < 32 or rw < 32:
            rh, rw = max(rh, 32), max(rw, 32)
        resized = cv2.resize(img, (rw, rh))
        # pad 到 32 的倍数
        ph = (32 - rh % 32) % 32
        pw = (32 - rw % 32) % 32
        padded = cv2.copyMakeBorder(resized, 0, ph, 0, pw, cv2.BORDER_CONSTANT, value=0)
        blob = padded.astype("float32")[None]  # 1,H,W,3
        blob = blob.transpose(0, 3, 1, 2)       # 1,3,H,W
        blob = blob / 255.0
        return blob, (rw, rh), (pw, ph), (w, h)

    def _det_postprocess(self, pred, target_w_h, pad_w_h, orig_w_h):
        cv2 = self.cv2
        np = self.np
        rw, rh = target_w_h
        pw, ph = pad_w_h
        ow, oh = orig_w_h
        # pred: [1,1,H,W] 概率图（已下采样）；还原到 padded 尺寸
        prob = pred[0, 0]
        # 若 pred 比 padded 小（下采样 1/4），上采样回 padded 尺寸
        if prob.shape[0] != rh or prob.shape[1] != rw:
            prob = cv2.resize(prob, (rw, rh), interpolation=cv2.INTER_LINEAR)
        # 二值化
        mask = (prob > DET_THRESHOLD).astype("uint8") * 255
        # 找轮廓，取最小外接矩形（支持斜框）
        contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        boxes = []
        for cnt in contours:
            if cnt.shape[0] < 4:
                continue
            rect = cv2.minAreaRect(cnt)
            w_box, h_box = rect[1]
            if min(w_box, h_box) < 2 or max(w_box, h_box) < 8:
                continue
            box = cv2.boxPoints(rect)  # 4x2，顺时针/逆时针
            # 还原到原图坐标（去掉 pad，再按缩放比例映射）
            box[:, 0] = box[:, 0] * (ow / rw)
            box[:, 1] = box[:, 1] * (oh / rh)
            boxes.append(box.astype("int32"))
        return boxes

    def _get_rotate_crop(self, img, box):
        cv2 = self.cv2
        # 透视变换截出单个文本框
        w_box = int(max(
            np.linalg.norm(box[0] - box[1]),
            np.linalg.norm(box[2] - box[3]),
        ))
        h_box = int(max(
            np.linalg.norm(box[1] - box[2]),
            np.linalg.norm(box[3] - box[0]),
        ))
        w_box, h_box = max(w_box, 2), max(h_box, 2)
        src = box.astype("float32")
        dst = np.array([[0, 0], [w_box, 0], [w_box, h_box], [0, h_box]], dtype="float32")
        M = cv2.getPerspectiveTransform(src, dst)
        warped = cv2.warpPerspective(img, M, (w_box, h_box))
        return warped

    def _cls_correct(self, img):
        # 可选：方向分类（0°/180°）
        if not self.has_cls or self.cls_sess is None:
            return img
        cv2 = self.cv2
        np = self.np
        h, w = img.shape[:2]
        resize = cv2.resize(img, (320, 48))
        blob = resize.astype("float32").transpose(2, 0, 1)[None] / 255.0
        out = self.cls_sess.run(None, {self.cls_sess.get_inputs()[0].name: blob})[0]
        label = int(np.argmax(out))
        if label == 1:  # 180°
            img = cv2.rotate(img, cv2.ROTATE_180)
        return img

    def _rec_preprocess(self, img):
        cv2 = self.cv2
        np = self.np
        h, w = img.shape[:2]
        ratio = REC_IMG_H / float(h)
        new_w = max(int(round(w * ratio)), 1)
        if new_w > REC_IMG_MAX_W:
            new_w = REC_IMG_MAX_W
        resized = cv2.resize(img, (new_w, REC_IMG_H))
        # 右/下 pad 到 (REC_IMG_MAX_W, REC_IMG_H)
        pad_w = REC_IMG_MAX_W - new_w
        if pad_w > 0:
            resized = cv2.copyMakeBorder(resized, 0, 0, 0, pad_w, cv2.BORDER_CONSTANT, value=0)
        # BGR->RGB, /255, 归一化, NCHW
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
        blob = rgb.astype("float32") / 255.0
        mean = np.array([0.485, 0.456, 0.406], dtype="float32")
        std = np.array([0.229, 0.224, 0.225], dtype="float32")
        blob = (blob - mean) / std
        blob = blob.transpose(2, 0, 1)[None]
        return blob

    def _ctc_decode(self, logits):
        np = self.np
        # logits: [1, T, C] -> argmax over C
        pred = np.argmax(logits[0], axis=-1).tolist()
        chars = []
        prev = -1
        for p in pred:
            if p != prev and p != 0:  # 0 是 blank
                if 1 <= p <= len(self.char_list):
                    chars.append(self.char_list[p - 1])
            prev = p
        return "".join(chars)

    def ocr_bytes(self, raw: bytes) -> str:
        np = self.np
        cv2 = self.cv2
        # 解码图片字节
        arr = np.frombuffer(raw, dtype=np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            raise OcrEngineError("图片解码失败")
        blob, target, pad, orig = self._det_preprocess(img)
        det_out = self.det_sess.run(None, {self.det_in: blob})[0]
        # 部分导出输出为 [1,1,H,W]；统一取最后一维通道
        if det_out.ndim == 4 and det_out.shape[1] == 1:
            det_out = det_out[:, 0:1, :, :]
        boxes = self._det_postprocess(det_out, target, pad, orig)
        if not boxes:
            return ""
        # 按从上到下、从左到右排序
        boxes.sort(key=lambda b: (min(p[1] for p in b), min(p[0] for p in b)))
        texts = []
        for box in boxes:
            crop = self._get_rotate_crop(img, box)
            if crop.shape[0] < 2 or crop.shape[1] < 2:
                continue
            crop = self._cls_correct(crop)
            rec_blob = self._rec_preprocess(crop)
            rec_out = self.rec_sess.run(None, {self.rec_in: rec_blob})[0]
            text = self._ctc_decode(rec_out)
            if text:
                texts.append(text)
        return "\n".join(texts).strip()


def make_engine(model_dir: str | None = None):
    """工厂：模型目录不存在/缺文件则返回 None（调用方降级处理）。"""
    if model_dir is None:
        model_dir = os.environ.get("OCR_MODEL_DIR") or str(Path(__file__).resolve().parent / "ocr_models")
    if not os.path.isdir(model_dir):
        return None
    try:
        return OcrEngine(model_dir)
    except Exception as e:  # 缺依赖/缺文件都不致命
        import sys
        sys.stderr.write(f"[ocrengine] 初始化失败，OCR 不可用：{e!r}\n")
        return None
