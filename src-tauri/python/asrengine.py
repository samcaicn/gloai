# -*- coding: utf-8 -*-
"""asrengine.py —— 离线语音转写引擎（微信 SILK 语音 → 文本）

识别链路：
  微信语音(SILK_V3) --pilk 解码--> PCM(int16) --重采样到 16k--> sherpa-onnx 离线识别
  sherpa-onnx 支持 zipformer / paraformer 等多种中文模型，完全离线、无需 GPU。

模型目录（ASR_MODEL_DIR，默认脚本同级的 asr_models/）需放：
  manifest.json  描述模型类型与文件路径，例如：
    {
      "type": "zipformer",                 // 或 "paraformer"
      "tokens": "tokens.txt",
      "encoder": "encoder-epoch-99-avg-1.onnx",
      "decoder": "decoder-epoch-99-avg-1.onnx",
      "joiner":  "joiner-epoch-99-avg-1.onnx",
      "num_threads": 2
    }
    # paraformer 则给 "model": "model.onnx" 即可（其余同上）

依赖（本机需装）：
  pip install sherpa-onnx pysilk  (pilk/pysilk 用于 SILK 解码；若不可用可换 pySILKCodec)
若 bbt-zb 自带语音识别模型/代码，按其结构把 onnx + tokens 放进 ASR_MODEL_DIR 并改 manifest 即可。
"""

from __future__ import annotations

import json
import math
import os
import struct
import tempfile
from pathlib import Path


class AsrEngineError(Exception):
    pass


def _resample_linear(samples: list[int], in_rate: int, out_rate: int) -> list[int]:
    """极简线性重采样（语音足够用，避免引入 scipy）。返回 16-bit int 列表。"""
    if in_rate == out_rate or not samples:
        return samples
    ratio = out_rate / in_rate
    n_out = int(len(samples) * ratio)
    out = []
    for i in range(n_out):
        pos = i / ratio
        i0 = int(math.floor(pos))
        frac = pos - i0
        i1 = min(i0 + 1, len(samples) - 1)
        v = samples[i0] * (1 - frac) + samples[i1] * frac
        out.append(int(round(v)))
    return out


def _pcm_to_wav_bytes(samples: list[int], rate: int) -> bytes:
    """int16 PCM 列表 → WAV 字节（16-bit 单声道）。"""
    buf = bytearray()
    buf += b"RIFF"
    n = len(samples) * 2 + 36
    buf += struct.pack("<I", n)
    buf += b"WAVE"
    buf += b"fmt "
    buf += struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    buf += b"data"
    buf += struct.pack("<I", len(samples) * 2)
    for s in samples:
        s = max(-32768, min(32767, s))
        buf += struct.pack("<h", s)
    return bytes(buf)


class AsrEngine:
    def __init__(self, model_dir: str):
        self.model_dir = model_dir
        manifest_path = os.path.join(model_dir, "manifest.json")
        if not os.path.isfile(manifest_path):
            raise AsrEngineError(f"缺少 ASR manifest：{manifest_path}")
        with open(manifest_path, "r", encoding="utf-8") as f:
            self.manifest = json.load(f)
        self._load()

    def _load(self):
        import sherpa_onnx as sherpa

        self.sherpa = sherpa
        m = self.manifest
        mtype = (m.get("type") or "zipformer").lower()
        tokens = os.path.join(self.model_dir, m["tokens"])
        num_threads = int(m.get("num_threads", 2))
        if mtype == "paraformer":
            model = os.path.join(self.model_dir, m["model"])
            cfg = sherpa.OfflineRecognizer.from_paraformer(
                paraformer=model, tokens=tokens, num_threads=num_threads
            )
        elif mtype in ("zipformer", "transducer"):
            cfg = sherpa.OfflineRecognizer.from_zipformer(
                encoder=os.path.join(self.model_dir, m["encoder"]),
                decoder=os.path.join(self.model_dir, m["decoder"]),
                joiner=os.path.join(self.model_dir, m["joiner"]),
                tokens=tokens,
                num_threads=num_threads,
            )
        else:
            raise AsrEngineError(f"不支持的 ASR 类型: {mtype}")
        self.recognizer = cfg

    def transcribe_silk(self, silk_bytes: bytes) -> str:
        """SILK 字节 → 调用 sherpa-onnx 识别 → 文本。"""
        import numpy as np

        # 1) SILK 解码：pilk 优先，失败回退 pySILKCodec
        pcm_path = None
        try:
            try:
                from pilk import decode as silk_decode

                with tempfile.NamedTemporaryFile(suffix=".sil", delete=False) as sf:
                    sf.write(silk_bytes)
                    silk_path = sf.name
                pcm_path = silk_path + ".pcm"
                rate = silk_decode(silk_path, pcm_path, 24000)
            except Exception:
                from pySILKCodec import decode as silk_decode2  # 备选包名

                with tempfile.NamedTemporaryFile(suffix=".sil", delete=False) as sf:
                    sf.write(silk_bytes)
                    silk_path = sf.name
                pcm_path = silk_path + ".pcm"
                rate = silk_decode2(silk_path, pcm_path, 24000)
            with open(pcm_path, "rb") as pf:
                raw = pf.read()
            samples = list(np.frombuffer(raw, dtype="<i2").astype("int32"))
            in_rate = int(rate) if rate else 24000
        finally:
            for p in (silk_path if "silk_path" in dir() else None, pcm_path):
                if p and os.path.exists(p):
                    try:
                        os.remove(p)
                    except Exception:
                        pass

        # 2) 重采样到 16k（中文模型一般 16k）
        samples = _resample_linear(samples, in_rate, 16000)
        wav = _pcm_to_wav_bytes(samples, 16000)

        # 3) sherpa 识别
        stream = self.recognizer.create_stream()
        stream.accept_wave_file(wav)
        self.recognizer.decode_stream(stream)
        return (stream.result.text or "").strip()


def make_engine(model_dir: str | None = None):
    """工厂：目录/模型不可用则返回 None（调用方降级为「不支持语音」）。"""
    if model_dir is None:
        model_dir = os.environ.get("ASR_MODEL_DIR") or str(Path(__file__).resolve().parent / "asr_models")
    if not os.path.isdir(model_dir):
        return None
    try:
        return AsrEngine(model_dir)
    except Exception as e:
        import sys
        sys.stderr.write(f"[asrengine] 初始化失败，语音转写不可用：{e!r}\n")
        return None
