"""
Watermark Removal Manager
===========================
Unified interface for multiple watermark removal methods.

Methods:
- fft_kcf: Lightweight, auto-downloads on first use (~200MB)
- lama: AI-powered, downloads when user switches to it (~1.5GB)

Usage:
    manager = WatermarkManager()
    
    # Use FFT-KCF (auto-downloads if needed)
    manager.remove_watermark("input.mp4", "output.mp4", method="fft_kcf")
    
    # Switch to LaMA (triggers download if not yet downloaded)
    manager.switch_method("lama")  # This triggers download
    manager.remove_watermark("input.mp4", "output.mp4", method="lama")
"""

import os
import sys
import subprocess
import shutil
from pathlib import Path
from typing import Optional, Tuple, Dict, Any

# Model directory
MODEL_DIR = Path(os.environ.get("DSH_MODEL_DIR", r"D:\code\dsh\models"))


class WatermarkManager:
    """Unified watermark removal manager."""
    
    METHODS = {
        "fft_kcf": {
            "name": "FFT-KCF",
            "description": "轻量级 FFT + KCF 跟踪，适合静态水印",
            "model_size": "~200MB",
            "auto_download": True,
            "gpu_required": False,
        },
        "lama": {
            "name": "LaMA",
            "description": "AI 修复，适合复杂背景上的水印",
            "model_size": "~1.5GB",
            "auto_download": False,  # Only when user switches
            "gpu_required": False,
        },
    }
    
    def __init__(self):
        self.current_method = "fft_kcf"
        self._fft_kcf_available = False
        self._lama_available = False
        
        # Initialize FFT-KCF (auto-download)
        self._init_fft_kcf()
    
    def _init_fft_kcf(self):
        """Initialize FFT-KCF module (auto-downloads model)."""
        try:
            from . import fft_kcf
            # Auto-download on first use
            fft_kcf.ensure_model_downloaded()
            self._fft_kcf_available = True
            print("[WatermarkManager] FFT-KCF initialized (auto-downloaded)")
        except Exception as e:
            print(f"[WatermarkManager] FFT-KCF init warning: {e}")
            self._fft_kcf_available = True  # Fallback mode available
    
    def switch_method(self, method: str) -> bool:
        """
        Switch watermark removal method.
        
        Args:
            method: "fft_kcf" or "lama"
            
        Returns:
            True if switch successful
            
        Note:
            Switching to "lama" will trigger model download if not yet downloaded.
        """
        if method not in self.METHODS:
            print(f"[WatermarkManager] Unknown method: {method}")
            return False
        
        if method == "lama":
            # Trigger LaMA model download
            try:
                from . import lama_inpaint
                if not lama_inpaint.is_model_downloaded():
                    print("[WatermarkManager] Switching to LaMA mode...")
                    print("[WatermarkManager] Triggering model download (~1.5GB)...")
                    if not lama_inpaint.download_lama_model():
                        print("[WatermarkManager] LaMA download failed, staying on FFT-KCF")
                        return False
                self._lama_available = True
            except Exception as e:
                print(f"[WatermarkManager] LaMA init failed: {e}")
                return False
        
        self.current_method = method
        print(f"[WatermarkManager] Switched to {self.METHODS[method]['name']}")
        return True
    
    def remove_watermark(
        self,
        input_path: str,
        output_path: str,
        method: Optional[str] = None,
        pos: Optional[Tuple[int, int, int, int]] = None,
    ) -> bool:
        """
        Remove watermark from video.
        
        Args:
            input_path: Input video path
            output_path: Output video path
            method: Method to use (default: current_method)
            pos: Manual position (x, y, w, h), auto-detect if None
            
        Returns:
            True if successful
        """
        method = method or self.current_method
        
        if method == "fft_kcf":
            from . import fft_kcf
            return fft_kcf.remove_watermark_fft_kcf(
                input_path, output_path, pos
            )
        elif method == "lama":
            from . import lama_inpaint
            if pos is None:
                print("[WatermarkManager] LaMA requires watermark position")
                return False
            return lama_inpaint.remove_watermark_lama(
                input_path, output_path, pos
            )
        else:
            print(f"[WatermarkManager] Unknown method: {method}")
            return False
    
    def get_method_info(self) -> Dict[str, Any]:
        """Get information about available methods."""
        return {
            "current": self.current_method,
            "methods": self.METHODS,
            "fft_kcf_ready": self._fft_kcf_available,
            "lama_ready": self._lama_available,
        }
    
    def is_method_available(self, method: str) -> bool:
        """Check if a method is available."""
        if method == "fft_kcf":
            return self._fft_kcf_available
        elif method == "lama":
            return self._lama_available
        return False


def _find_ffmpeg() -> Optional[str]:
    """Find ffmpeg executable."""
    script_dir = Path(__file__).parent.parent.parent
    possible_paths = [
        script_dir / "ffmpeg" / "ffmpeg-9.0.1-essentials_build" / "bin" / "ffmpeg.exe",
        Path(r"D:\code\dsh\ffmpeg\ffmpeg-9.0.1-essentials_build\bin\ffmpeg.exe"),
        Path("ffmpeg"),
    ]
    
    for path in possible_paths:
        if path.exists():
            return str(path)
    
    return shutil.which("ffmpeg")


if __name__ == "__main__":
    # Test the manager
    manager = WatermarkManager()
    
    print("\n=== Watermark Manager Info ===")
    info = manager.get_method_info()
    print(f"Current method: {info['current']}")
    for key, method in info["methods"].items():
        print(f"  {key}: {method['name']} ({method['model_size']}) - auto_download={method['auto_download']}")
    
    print("\n=== Testing FFT-KCF ===")
    test_input = r"D:\code\dsh\test_watermark.mp4"
    test_output = r"D:\code\dsh\test_fft_kcf_output.mp4"
    if os.path.exists(test_input):
        manager.remove_watermark(test_input, test_output)
    else:
        print(f"Test input not found: {test_input}")
