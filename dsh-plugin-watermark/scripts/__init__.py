"""
Watermark removal toolkit for DSH Skill Platform.

Methods:
- fft_kcf: Lightweight FFT + KCF tracking, auto-downloads on first use (~200MB)
- lama: AI-powered inpainting, downloads when user switches to it (~1.5GB)
"""

from .watermark_manager import WatermarkManager

__all__ = ["WatermarkManager"]
