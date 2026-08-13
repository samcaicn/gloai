#!/usr/bin/env python3
"""Rasterize src-tauri/icons/app-icon.svg to a 1024×1024 PNG for `tauri icon`."""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "src-tauri" / "icons" / "app-icon.svg"
OUT = Path(tempfile.mkdtemp(prefix="dsh-gui-icon-")) / "icon-1024.png"


def crop_padding(image: Image.Image) -> Image.Image:
    pixels = image.load()
    width, height = image.size
    min_x, min_y, max_x, max_y = width, height, 0, 0
    found = False
    for y in range(height):
        for x in range(width):
            red, green, blue, alpha = pixels[x, y]
            if alpha < 8:
                continue
            if red > 240 and green > 240 and blue > 240:
                continue
            found = True
            min_x = min(min_x, x)
            min_y = min(min_y, y)
            max_x = max(max_x, x)
            max_y = max(max_y, y)
    if not found:
        return image
    return image.crop((min_x, min_y, max_x + 1, max_y + 1))


def main() -> int:
    if not SVG.is_file():
        print(f"missing {SVG}", file=sys.stderr)
        return 1
    thumb_dir = tempfile.mkdtemp(prefix="dsh-gui-ql-")
    subprocess.run(
        ["qlmanage", "-t", "-s", "1024", "-o", thumb_dir, str(SVG)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    thumbs = list(Path(thumb_dir).glob("*.png"))
    if not thumbs:
        print("qlmanage did not write a PNG", file=sys.stderr)
        return 1
    image = crop_padding(Image.open(thumbs[0]).convert("RGBA"))
    canvas = Image.new("RGBA", (1024, 1024), (18, 18, 20, 255))
    fitted = image.copy()
    fitted.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
    origin = ((1024 - fitted.width) // 2, (1024 - fitted.height) // 2)
    canvas.alpha_composite(fitted, origin)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT, format="PNG")
    print(OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
