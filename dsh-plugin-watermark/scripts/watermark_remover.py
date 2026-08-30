#!/usr/bin/env python3
"""
DSH Watermark Remover - Main Entry Point
=========================================
Unified video watermark removal tool with multiple methods.

Usage:
    # Auto-detect and remove (uses FFT-KCF, auto-downloads)
    python watermark_remover.py --input input.mp4 --output output.mp4
    
    # Specify position manually
    python watermark_remover.py --input input.mp4 --output output.mp4 --x 10 --y 10 --w 200 --h 50
    
    # Use LaMA (triggers download if needed)
    python watermark_remover.py --input input.mp4 --output output.mp4 --method lama --x 10 --y 10 --w 200 --h 50
    
    # Download LaMA model only
    python watermark_remover.py --download-lama
    
    # Show method info
    python watermark_remover.py --info
"""

import sys
import os
import argparse

# Add scripts directory to path
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, script_dir)

from watermark.watermark_manager import WatermarkManager


def main():
    parser = argparse.ArgumentParser(
        description="DSH Watermark Remover - Video watermark removal tool",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Auto-detect and remove (FFT-KCF, auto-downloads ~200MB)
  python watermark_remover.py -i input.mp4 -o output.mp4
  
  # Manual position with FFT-KCF
  python watermark_remover.py -i input.mp4 -o output.mp4 -x 10 -y 10 -w 200 -h 50
  
  # Use LaMA AI (downloads ~1.5GB on first use)
  python watermark_remover.py -i input.mp4 -o output.mp4 --method lama -x 10 -y 10 -w 200 -h 50
  
  # Download LaMA model only
  python watermark_remover.py --download-lama
        """
    )
    
    parser.add_argument("--input", "-i", help="Input video file path")
    parser.add_argument("--output", "-o", help="Output video file path")
    parser.add_argument("--method", "-m", choices=["fft_kcf", "lama"], default="fft_kcf",
                        help="Removal method (default: fft_kcf)")
    parser.add_argument("--x", type=int, help="Watermark X position")
    parser.add_argument("--y", type=int, help="Watermark Y position")
    parser.add_argument("--w", type=int, help="Watermark width")
    parser.add_argument("--h", type=int, help="Watermark height")
    parser.add_argument("--download-lama", action="store_true",
                        help="Download LaMA model only")
    parser.add_argument("--info", action="store_true",
                        help="Show method information")
    
    args = parser.parse_args()
    
    # Initialize manager
    manager = WatermarkManager()
    
    # Show info
    if args.info:
        info = manager.get_method_info()
        print("\n=== DSH Watermark Remover ===")
        print(f"Current method: {info['current']}\n")
        print("Available methods:")
        for key, method in info["methods"].items():
            status = "✓ Ready" if manager.is_method_available(key) else "✗ Not ready"
            auto = "auto-download" if method["auto_download"] else "manual download"
            print(f"  [{key}] {method['name']}")
            print(f"       {method['description']}")
            print(f"       Model size: {method['model_size']} ({auto})")
            print(f"       Status: {status}\n")
        return 0
    
    # Download LaMA only
    if args.download_lama:
        from watermark import lama_inpaint
        success = lama_inpaint.download_lama_model()
        return 0 if success else 1
    
    # Require input/output for removal
    if not args.input or not args.output:
        parser.error("--input and --output are required for watermark removal")
    
    # Switch method if needed
    if args.method != manager.current_method:
        if not manager.switch_method(args.method):
            print(f"Failed to switch to {args.method}")
            return 1
    
    # Determine watermark position
    pos = None
    if all(v is not None for v in [args.x, args.y, args.w, args.h]):
        pos = (args.x, args.y, args.w, args.h)
    
    # Remove watermark
    print(f"\nRemoving watermark from: {args.input}")
    print(f"Output: {args.output}")
    print(f"Method: {args.method}")
    if pos:
        print(f"Position: x={pos[0]}, y={pos[1]}, w={pos[2]}, h={pos[3]}")
    else:
        print("Position: auto-detect")
    
    success = manager.remove_watermark(args.input, args.output, pos=pos)
    
    if success:
        print("\n✓ Watermark removal complete!")
        return 0
    else:
        print("\n✗ Watermark removal failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
