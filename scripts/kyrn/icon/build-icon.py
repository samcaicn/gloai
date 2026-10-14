#!/usr/bin/env python3
"""Build every mu icon asset from the brand source image.

Source of truth: resources/brand/mu-icon-source.png (1254x1254 RGBA, from an image generator).
What is wrong with the source, and what this script does about it:

* The tile's body has alpha 250-254 instead of 255, the outside carries ~74k stray white pixels with
  alpha 1-8, and the anti-aliased rim is uneven. The alpha channel is therefore thrown away and
  rebuilt from an analytic shape: a rounded square whose corners are Lame curves
  (|1-x/r|^n + |1-y/r|^n = 1), which join the straight edges with zero curvature, i.e. a
  "continuous corner". The shape was fitted to the visible tile (rms 0.7 px over the four corners,
  which agree with each other within 0.5 px); `--verify` measures the source again and fails if
  the constants below stop matching it.
* The colour plane is kept as it is. Only pixels the source itself marks as not solid (the rim's
  anti-aliasing band) take the colour of their nearest solid neighbours, so that resampling never
  mixes stray white into the edge.
* Every output size gets its own super-sampled mask (8x8 samples per pixel) and its own Lanczos
  resample of the colour plane. Nothing is derived by shrinking an already masked image, so no size
  inherits another size's edge.

Two layouts, following what the upstream assets did:

* "padded": the tile is 824 px on a 1024 canvas, the macOS icon grid. Used for app.icns and
  app_dev.png (the dock icon of a development run; Linux development windows use it too).
* "full": the tile fills the canvas. Used for app.png (tray 16/32 px, notifications, the Linux
  package icon), app.ico, icon.png, the PWA icons and the logo the renderer imports. A margin there
  would shrink a 16 px tray icon to a 13 px tile.

Sizes of 32 px and below are drawn from a simplified variant: the artwork's diagonal as a plain
gradient, and the glyph traced, enlarged and bolder. The artwork's glyph is a third of the tile and
glows softly; at 16 px that is a pale smudge. From 48 px up the artwork itself reads well.

Deterministic: same source, same pixels (no timestamps, no randomness). Needs Python 3.9+, Pillow and
numpy. app.icns needs macOS `iconutil`; elsewhere it is skipped with a note and the iconset is kept.

    python3 scripts/kyrn/icon/build-icon.py            # write all assets into the repository
    python3 scripts/kyrn/icon/build-icon.py --verify   # only check the fit against the source
    python3 scripts/kyrn/icon/build-icon.py --out DIR  # write the same tree under DIR instead
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / "resources" / "brand" / "mu-icon-source.png"

# --- The fitted shape, in source pixels -------------------------------------------------------
# Straight edges of the tile where the source's coverage crosses 50% (pixel-edge coordinates).
TILE_LEFT, TILE_TOP, TILE_RIGHT, TILE_BOTTOM = 42.82, 43.27, 1211.39, 1212.02
# Lame corner: exponent, and how far the curve runs along each edge, as a fraction of the side.
CORNER_EXPONENT = 2.68
CORNER_EXTENT = 0.2743
# The mask sits this far inside the fitted edge, so that it only ever covers solid artwork.
MASK_INSET = 1.5
# What the source calls solid. Its body is 250-254; the rim band falls off below that.
SOLID_ALPHA = 245

CANVAS = 1024
PADDED_TILE = 824  # macOS icon grid: 824 px tile on a 1024 px canvas.
SUPERSAMPLE = 8
SIMPLIFIED_UP_TO = 32

# The glyph of the simplified variant and of the in-app mark. It is the artwork's glyph traced as
# three round-capped strokes in a 100x100 box that stands for the tile (overlap with the artwork's
# glyph: 88% intersection over union). MuMark.tsx draws the same path;
# tests/unit/kyrn/brandMark.test.ts keeps the two in step.
GLYPH_PATH = (
    "M41 32 L35 74.4 "
    "M38.9 47 C37.77 55 41 59.4 47.6 59.4 C54.8 59.4 61.05 54.5 61.9 48.5 "
    "M64.25 32 L61.35 52.5 C60.6 58 62.3 61.2 66.4 60.4"
)
GLYPH_STROKE = 9.8
GLYPH_CENTRE = (50.6, 53.2)  # centre of the glyph's bounding box, what the small sizes scale around
# 16-32 px: the glyph grows by this factor; the stroke is given in the grown glyph's own box.
SMALL_GLYPH_SCALE = 1.24
SMALL_GLYPH_STROKE = 11.2
# The simplified tile: the artwork's diagonal, pink to violet to periwinkle, a shade deeper in the
# middle than the artwork so that a 2 px white stroke still separates from it.
SMALL_GRADIENT = ((0.0, (0xF3, 0xC6, 0xFA)), (0.5, (0xB7, 0x9C, 0xF7)), (1.0, (0x86, 0x80, 0xEB)))

def load_source() -> np.ndarray:
    image = Image.open(SOURCE)
    if image.mode != "RGBA":
        raise SystemExit(f"{SOURCE} must be RGBA, found {image.mode}")
    return np.asarray(image).copy()


# --- Measuring the source (used by --verify) ---------------------------------------------------


def _crossing(values: np.ndarray) -> float:
    """Pixel-edge coordinate where coverage first reaches 50%, linearly interpolated."""
    solid = np.nonzero(values >= 0.5)[0]
    if solid.size == 0:
        return float("nan")
    i = int(solid[0])
    if i == 0:
        return 0.0
    before, after = values[i - 1], values[i]
    return (i - 1) + (0.5 - before) / (after - before) + 0.5


def measure(alpha: np.ndarray) -> dict[str, float]:
    coverage = np.clip(alpha.astype(np.float64) / 252.0, 0.0, 1.0)
    height, width = coverage.shape
    left = np.array([_crossing(coverage[y]) for y in range(height)])
    right = np.array([width - _crossing(coverage[y][::-1]) for y in range(height)])
    top = np.array([_crossing(coverage[:, x]) for x in range(width)])
    bottom = np.array([height - _crossing(coverage[:, x][::-1]) for x in range(width)])
    middle = slice(height // 2 - 220, height // 2 + 220)
    edges = {
        "left": float(np.nanmedian(left[middle])),
        "right": float(np.nanmedian(right[middle])),
        "top": float(np.nanmedian(top[middle])),
        "bottom": float(np.nanmedian(bottom[middle])),
    }
    side = ((edges["right"] - edges["left"]) + (edges["bottom"] - edges["top"])) / 2

    # Corner profile: how far the left/right boundary sits inside the straight edge, d px below the
    # top edge (or above the bottom edge), averaged over the four corners.
    depths = np.arange(6.0, 340.0)

    def profile(boundary: np.ndarray, edge: float, sign: float, from_top: bool) -> np.ndarray:
        rows = (edges["top"] + depths) if from_top else (edges["bottom"] - depths)
        lower = np.floor(rows - 0.5).astype(int)
        t = (rows - 0.5) - lower
        return sign * (boundary[lower] * (1 - t) + boundary[lower + 1] * t - edge)

    measured = np.mean(
        [
            profile(left, edges["left"], 1, True),
            profile(right, edges["right"], -1, True),
            profile(left, edges["left"], 1, False),
            profile(right, edges["right"], -1, False),
        ],
        axis=0,
    )
    extent = CORNER_EXTENT * side
    along = np.clip((extent - np.minimum(depths, extent)) / extent, 0.0, 1.0)
    model = extent * (1 - (1 - along**CORNER_EXPONENT) ** (1 / CORNER_EXPONENT))
    return {**edges, "side": side, "rms": float(np.sqrt(np.mean((model - measured) ** 2)))}


def verify(source: np.ndarray) -> None:
    found = measure(source[..., 3])
    expected = {"left": TILE_LEFT, "top": TILE_TOP, "right": TILE_RIGHT, "bottom": TILE_BOTTOM}
    print(f"tile side {found['side']:.2f} px, corner fit rms {found['rms']:.2f} px")
    for name, value in expected.items():
        if abs(found[name] - value) > 0.5:
            raise SystemExit(f"{name} edge moved: constant {value}, source {found[name]:.2f}")
    if found["rms"] > 1.5:
        raise SystemExit(f"corner fit rms {found['rms']:.2f} px is above 1.5 px")
    print("fit matches the source")


# --- Colour plane --------------------------------------------------------------------------------


def colour_plane(source: np.ndarray, pad: int) -> Image.Image:
    """The artwork's RGB, padded, with everything that is not solid filled from solid neighbours."""
    rgb = source[..., :3].astype(np.float32)
    known = source[..., 3] >= SOLID_ALPHA
    rgb[~known] = 0.0

    def box3(values: np.ndarray) -> np.ndarray:
        """Sum over each pixel's 3x3 neighbourhood (edges see zeros)."""
        padded = np.pad(values, ((1, 1), (1, 1)) + ((0, 0),) * (values.ndim - 2))
        rows = padded[:-2] + padded[1:-1] + padded[2:]
        return rows[:, :-2] + rows[:, 1:-1] + rows[:, 2:]

    # Grow the solid area outwards one pixel at a time; 14 px covers the Lanczos kernel at every
    # scale used here with room to spare. Each new pixel is the mean of its solid neighbours.
    for _ in range(14):
        count = box3(known.astype(np.float32))
        grow = ~known & (count > 0)
        total = box3(rgb)
        rgb[grow] = total[grow] / count[grow][:, None]
        known = known | grow
    plane = np.pad(np.clip(np.rint(rgb), 0, 255).astype(np.uint8), ((pad, pad), (pad, pad), (0, 0)))
    return Image.fromarray(plane, "RGB")


# --- Shape -----------------------------------------------------------------------------------------


def shape_mask(size: int, tile: float) -> np.ndarray:
    """Coverage (0..1) of the continuous-corner tile, centred on a size x size canvas."""
    n = size * SUPERSAMPLE
    # The shape is symmetric: sample one axis, measured from the tile's edge inwards.
    centres = (np.arange(n) + 0.5) / SUPERSAMPLE
    inside = tile / 2 - np.abs(centres - size / 2)  # distance inside the nearer straight edge
    extent = CORNER_EXTENT * tile
    along = np.clip((extent - inside) / extent, 0.0, None)  # 0 on the straight part, 1 at the edge
    term = np.where(inside >= 0, along**CORNER_EXPONENT, np.inf).astype(np.float32)
    coverage = np.empty((size, size), dtype=np.float32)
    rows_per_block = 32  # output rows per block keeps the sample grid small in memory
    for start in range(0, size, rows_per_block):
        stop = min(start + rows_per_block, size)
        block = term[start * SUPERSAMPLE : stop * SUPERSAMPLE, None] + term[None, :] <= 1.0
        coverage[start:stop] = block.reshape(stop - start, SUPERSAMPLE, size, SUPERSAMPLE).mean(axis=(1, 3))
    return coverage


# --- Rendering -----------------------------------------------------------------------------------


class Artwork:
    def __init__(self, source: np.ndarray):
        self.pad = 256
        self.plane = colour_plane(source, self.pad)
        self.cx = (TILE_LEFT + TILE_RIGHT) / 2 + self.pad
        self.cy = (TILE_TOP + TILE_BOTTOM) / 2 + self.pad
        side = ((TILE_RIGHT - TILE_LEFT) + (TILE_BOTTOM - TILE_TOP)) / 2
        self.side = side - 2 * MASK_INSET  # source pixels the mask's side stands for

    def render(self, size: int, tile: float) -> Image.Image:
        """The artwork at `size`, its tile `tile` px wide, with the rebuilt alpha."""
        half = (size / 2) * (self.side / tile)  # half of the canvas, in source pixels
        box = (self.cx - half, self.cy - half, self.cx + half, self.cy + half)
        colour = self.plane.resize((size, size), Image.LANCZOS, box=box)
        alpha = Image.fromarray(np.rint(shape_mask(size, tile) * 255).astype(np.uint8), "L")
        colour.putalpha(alpha)
        return colour


def parse_path(path: str) -> list[list[tuple[float, float]]]:
    """Flatten an SVG path made of absolute M, L and C commands into polylines."""
    tokens = re.findall(r"[A-Za-z]|-?\d*\.?\d+", path)
    polylines: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    command = ""
    i = 0

    def point(at: int) -> tuple[float, float]:
        return float(tokens[at]), float(tokens[at + 1])

    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
        if command == "M":
            current = [point(i)]
            polylines.append(current)
            i += 2
            command = "L"  # further pairs after a moveto are linetos
        elif command == "L":
            current.append(point(i))
            i += 2
        elif command == "C":
            p0, p1, p2, p3 = current[-1], point(i), point(i + 2), point(i + 4)
            for step in range(1, 33):
                t = step / 32
                u = 1 - t
                current.append(
                    tuple(
                        u**3 * p0[k] + 3 * u * u * t * p1[k] + 3 * u * t * t * p2[k] + t**3 * p3[k]
                        for k in (0, 1)
                    )
                )
            i += 6
        else:
            raise ValueError(f"unsupported path command {command!r}")
    return polylines


def glyph_samples(grid: int, stroke: float, scale: float = 1.0) -> np.ndarray:
    """Which of grid x grid samples over the tile the stroked glyph covers, grown around its centre."""
    unit = grid / 100.0
    ys, xs = np.mgrid[0:grid, 0:grid]
    # Sample positions in the glyph's own 100-box, undoing the growth around GLYPH_CENTRE.
    px = ((xs + 0.5) / unit - GLYPH_CENTRE[0]) / scale + GLYPH_CENTRE[0]
    py = ((ys + 0.5) / unit - GLYPH_CENTRE[1]) / scale + GLYPH_CENTRE[1]
    nearest = np.full((grid, grid), np.inf)
    for line in parse_path(GLYPH_PATH):
        for (ax, ay), (bx, by) in zip(line, line[1:]):
            dx, dy = bx - ax, by - ay
            length = dx * dx + dy * dy
            t = np.clip(((px - ax) * dx + (py - ay) * dy) / length, 0, 1) if length else 0.0
            nearest = np.minimum(nearest, (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2)
    return nearest <= (stroke / 2) ** 2


def diagonal_gradient(size: int) -> np.ndarray:
    """SMALL_GRADIENT from the top left to the bottom right corner, as float RGB."""
    ys, xs = np.mgrid[0:size, 0:size]
    t = ((xs + 0.5) + (ys + 0.5)) / (2 * size)
    stops = [stop for stop, _ in SMALL_GRADIENT]
    colours = np.array([colour for _, colour in SMALL_GRADIENT], dtype=np.float64)
    return np.stack([np.interp(t, stops, colours[:, channel]) for channel in range(3)], axis=-1)


def render_simplified(size: int, tile: float) -> Image.Image:
    """Same diagonal, bolder glyph: what 16-32 px icons are drawn from."""
    # The glyph is laid out on the tile, which on the padded layout is smaller than the canvas.
    # Sizes here are small, so the tile is drawn on its own super-sampled grid and placed by
    # resampling: 16 px padded has a 12.9 px tile, which no integer offset can hold.
    work = size * SUPERSAMPLE
    tile_px = tile * SUPERSAMPLE
    grid = int(np.ceil(tile_px))
    field = diagonal_gradient(grid)
    glyph = glyph_samples(grid, SMALL_GLYPH_STROKE, SMALL_GLYPH_SCALE)[..., None]
    field = field * (1 - glyph) + 255.0 * glyph
    canvas = np.zeros((work, work, 3))
    offset = int(round((work - grid) / 2))
    canvas[offset : offset + grid, offset : offset + grid] = field
    # Outside the tile the colour does not matter (alpha is 0), but keep the tile's own edge colour
    # there so that the box filter below never darkens the rim.
    canvas[:offset] = canvas[offset]
    canvas[offset + grid :] = canvas[offset + grid - 1]
    canvas[:, :offset] = canvas[:, offset : offset + 1]
    canvas[:, offset + grid :] = canvas[:, offset + grid - 1 : offset + grid]
    colour = canvas.reshape(size, SUPERSAMPLE, size, SUPERSAMPLE, 3).mean(axis=(1, 3))
    image = Image.fromarray(np.clip(np.rint(colour), 0, 255).astype(np.uint8), "RGB")
    image.putalpha(Image.fromarray(np.rint(shape_mask(size, tile) * 255).astype(np.uint8), "L"))
    return image


def render(artwork: Artwork, size: int, padded: bool) -> Image.Image:
    tile = size * PADDED_TILE / CANVAS if padded else float(size)
    if size <= SIMPLIFIED_UP_TO:
        return render_simplified(size, tile)
    return artwork.render(size, tile)


# --- Outputs ---------------------------------------------------------------------------------------


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG")
    print(f"  {path}")


def build(out: Path) -> None:
    source = load_source()
    verify(source)
    artwork = Artwork(source)
    resources = out / "resources"
    renderer = out / "packages" / "desktop" / "src" / "renderer"

    print("writing:")
    save_png(render(artwork, 1024, padded=False), resources / "app.png")
    save_png(render(artwork, 1024, padded=True), resources / "app_dev.png")
    save_png(render(artwork, 800, padded=False), resources / "icon.png")
    # The logo the renderer bundles (login page): 512 px is plenty for what is shown at ~72 px.
    save_png(render(artwork, 512, padded=False), renderer / "assets" / "logos" / "brand" / "app.png")
    for size in (180, 192, 512):
        save_png(render(artwork, size, padded=False), out / "public" / "pwa" / f"icon-{size}.png")

    # Windows: one file, seven sizes, each drawn for its size.
    frames = [render(artwork, size, padded=False) for size in (16, 24, 32, 48, 64, 128, 256)]
    ico = resources / "app.ico"
    frames[-1].save(ico, format="ICO", append_images=frames[:-1], sizes=[(f.width, f.height) for f in frames])
    print(f"  {ico}")

    # macOS: an iconset with every slot, turned into app.icns by iconutil.
    with tempfile.TemporaryDirectory() as temporary:
        iconset = Path(temporary) / "app.iconset"
        iconset.mkdir()
        for points in (16, 32, 128, 256, 512):
            render(artwork, points, padded=True).save(iconset / f"icon_{points}x{points}.png")
            render(artwork, points * 2, padded=True).save(iconset / f"icon_{points}x{points}@2x.png")
        icns = resources / "app.icns"
        if shutil.which("iconutil"):
            subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns)], check=True)
            print(f"  {icns}")
        else:
            kept = resources / "brand" / "app.iconset"
            shutil.copytree(iconset, kept, dirs_exist_ok=True)
            print(f"  iconutil not found (not macOS): kept {kept}, run `iconutil -c icns` on it later")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="only check the fitted shape against the source")
    parser.add_argument("--out", type=Path, default=REPO, help="write the asset tree under this directory")
    arguments = parser.parse_args()
    if arguments.verify:
        verify(load_source())
        return
    build(arguments.out.resolve())


if __name__ == "__main__":
    sys.exit(main())
