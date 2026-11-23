#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Generate app/src/main/assets/mcp/mcp-logos.json — service brand logos (path + brand color).

The catalog is secret-free connection data; logos are presentation data, kept in a separate asset.
Each entry maps a catalog preset id to a monochrome brand mark (SVG path `d` + 6-digit hex brand color)
so the app can render it as a Compose ImageVector with no image-loading library.

Sources (attribution in licenses/THIRD_PARTY_NOTICES.md; brand marks remain their owners' property):
  1. a CC0 brand-icon set, pinned to several versions merged by slug — newest first, older versions only
     supplement brands later removed from the set;
  2. an MIT icon set for AI/search vendors the first set does not carry (24x24, fill-rule evenodd).
Colors are the source's brand color, darkened just enough to keep >= 3:1 contrast on the fixed light tile;
brand colors too bright for that become the tile color instead, with a near-black mark.

Usage:
  build_logos.py            write app/src/main/assets/mcp/mcp-logos.json
  build_logos.py --check    regenerate to a temp file and compare with the committed asset (exit 1 on mismatch)
"""

import argparse
import concurrent.futures
import json
import re
import sys
import tempfile
import urllib.request

ROOT = None  # set in main()
ASSET = "app/src/main/assets/mcp/mcp-logos.json"
CATALOG = "app/src/main/assets/mcp/catalog.json"

# Merged, pinned versions of the icon set (newer first; older supplements brands removed in newer).
ICON_VERSIONS = [
    ("16.38.1", "https://unpkg.com/simple-icons@16.38.1/icons.json", "https://unpkg.com/simple-icons@16.38.1/icons/%s.svg"),
    ("14.11.0", "https://unpkg.com/simple-icons@14.11.0/_data/simple-icons.json", "https://unpkg.com/simple-icons@14.11.0/icons/%s.svg"),
    ("11.15.0", "https://unpkg.com/simple-icons@11.15.0/_data/simple-icons.json", "https://unpkg.com/simple-icons@11.15.0/icons/%s.svg"),
]

# Supplement set (MIT): presetId -> (icon name, brand color taken from that icon's color variant).
LOBE_VERSION = "1.106.0"
LOBE_SVG = "https://unpkg.com/@lobehub/icons-static-svg@" + LOBE_VERSION + "/icons/%s.svg"
LOBE_ICONS = {
    "cohere": ("cohere", "39594D"),
    "groq": ("groq", "F55036"),
    "xai": ("xai", "000000"),
    "together": ("together", "EF2CC1"),
    "fireworks": ("fireworks", "5019C5"),
    "cerebras": ("cerebras", "F15A29"),
    "moonshot": ("moonshot", "000000"),
    "zhipu": ("zhipu", "3859FF"),
    "baidu-ernie": ("wenxin", "0A51C3"),
    "bedrock": ("bedrock", "6350FB"),
    "vertex-ai": ("vertexai", "4285F4"),
    "tavily": ("tavily", "468BFF"),
    "exa": ("exa", "1F40ED"),
    "bing": ("bing", "048FCE"),
    "tencentcloud": ("tencentcloud", "006EFF"),
}

# presetId -> icon-set slug where the naive rule (strip dashes, lowercase) is wrong or ambiguous.
SLUG_OVERRIDES = {
    "brave-search": "brave",
    "mistral": "mistralai",
    "mongodb-atlas": "mongodb",
    "npm-registry": "npm",
    "msteams": "microsoftteams",
    "bigquery": "googlebigquery",
    "cockroachdb": "cockroachlabs",
    "aliyun": "alibabacloud",
    "postgres": "postgresql",
    "flyio": "flydotio",
    "bunny": "bunnydotnet",
    "moonshot": "moonshotai",
    "azure": "microsoftazure",
    "onedrive": "microsoftonedrive",
    "outlook": "microsoftoutlook",
    "oraclecloud": "oracle",
    "convertkit": "kit",       # ConvertKit renamed itself Kit
    "linode": "akamai",        # Linode is now Akamai's cloud
}

TILE_BG = "F4F4F5"   # BrandTileBg in the app's IconTile
MIN_CONTRAST = 3.0   # WCAG non-text contrast for a graphic on the tile
BRIGHT_TILE_BELOW = 1.7  # brand colors this close to the tile become the tile itself, with a dark mark
DARK_MARK = "1A1A1A"


def _fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "mika-build-logos"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def _slugify(name: str) -> str:
    return "".join(c for c in name if c.isalnum()).lower()


def _load_indexes():
    """Return (slug -> hex, slug -> version) merged across ICON_VERSIONS (newer wins)."""
    hex_by_slug, ver_by_slug = {}, {}
    for ver, index_url, _svg_tpl in ICON_VERSIONS:
        raw = json.loads(_fetch(index_url).decode("utf-8"))
        entries = raw["icons"] if isinstance(raw, dict) else raw
        for e in entries:
            # v16 ships a `slug`; v14 does not, so derive it from the title (ASCII brand names only).
            slug = e.get("slug") or _slugify(e.get("title", ""))
            hexv = (e.get("hex") or "").upper()
            if not slug or not re.fullmatch(r"[0-9A-F]{6}", hexv):
                continue
            if slug not in hex_by_slug:
                hex_by_slug[slug] = hexv
                ver_by_slug[slug] = ver
    return hex_by_slug, ver_by_slug


def _svg_tpl_for(version: str) -> str:
    return next(tpl for ver, _, tpl in ICON_VERSIONS if ver == version)


_UNSUPPORTED = re.compile(r"<(circle|rect|ellipse|polygon|polyline|line|use|image)\b|transform=|opacity=")


def _svg_paths(svg: str, name: str) -> list:
    """All path `d`s of a flat, single-color 24x24 icon; refuse anything a plain path list can't reproduce."""
    if 'viewBox="0 0 24 24"' not in svg or _UNSUPPORTED.search(svg):
        raise ValueError("%s: not a flat 24x24 path icon" % name)
    paths = re.findall(r'<path[^>]*\bd="([^"]+)"', svg)
    if not paths:
        raise ValueError("no path in %s svg" % name)
    return paths


def _fetch_paths(slug: str, version: str) -> list:
    return _svg_paths(_fetch(_svg_tpl_for(version) % slug).decode("utf-8"), slug)


def _lin(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _luminance(hexv: str) -> float:
    r, g, b = (_lin(int(hexv[i:i + 2], 16) / 255.0) for i in (0, 2, 4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _contrast(a: str, b: str) -> float:
    la, lb = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def _style(hexv: str) -> dict:
    """Mark color (+ tile color for very bright brands, which would only turn muddy if darkened)."""
    if _contrast(hexv, TILE_BG) < BRIGHT_TILE_BELOW:
        return {"color": DARK_MARK, "tile": hexv}
    return {"color": _readable(hexv)}


def _readable(hexv: str) -> str:
    """Darken (same hue) until the mark reaches MIN_CONTRAST on the tile; already-readable colors pass through."""
    rgb = [int(hexv[i:i + 2], 16) for i in (0, 2, 4)]
    out = hexv
    k = 1.0
    while _contrast(out, TILE_BG) < MIN_CONTRAST and k > 0:
        k -= 0.02
        out = "".join("%02X" % round(c * k) for c in rgb)
    return out


def _slug_for(preset_id: str) -> str:
    return SLUG_OVERRIDES.get(preset_id, preset_id.replace("-", "").lower())


def generate(catalog_json: str, out_path: str) -> dict:
    hex_by_slug, ver_by_slug = _load_indexes()
    preset_ids = [p["id"] for p in json.loads(catalog_json)["presets"]]
    # The primary set wins; the supplement only fills presets it has no mark for.
    jobs = []
    for pid in preset_ids:
        slug = _slug_for(pid)
        if slug in hex_by_slug:
            jobs.append((pid, "si", slug))
        elif pid in LOBE_ICONS:
            jobs.append((pid, "lobe", LOBE_ICONS[pid][0]))

    def one(job):
        pid, src, name = job
        try:
            if src == "si":
                return pid, {"paths": _fetch_paths(name, ver_by_slug[name]), **_style(hex_by_slug[name])}
            svg = _fetch(LOBE_SVG % name).decode("utf-8")
            return pid, {"paths": _svg_paths(svg, name), **_style(LOBE_ICONS[pid][1]), "evenOdd": True}
        except Exception as e:
            print("skip %s (%s): %s" % (pid, name, e), file=sys.stderr)
            return None

    logos = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
        for result in ex.map(one, jobs):
            if result:
                logos[result[0]] = result[1]
    out = {"formatVersion": 2, "logos": dict(sorted(logos.items()))}
    with open(out_path, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    return out


def main():
    global ROOT
    ROOT = __import__("os").path.abspath(__import__("os").path.join(__import__("os").path.dirname(__file__), "..", ".."))
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    asset = __import__("os").path.join(ROOT, ASSET)
    catalog = __import__("os").path.join(ROOT, CATALOG)
    with open(catalog) as f:
        catalog_json = f.read()

    if args.check:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as tmp:
            tmp_path = tmp.name
        generate(catalog_json, tmp_path)
        with open(asset) as f, open(tmp_path) as g:
            ok = f.read() == g.read()
        print("ok" if ok else "MISMATCH")
        sys.exit(0 if ok else 1)

    out = generate(catalog_json, asset)
    n = len(out["logos"])
    n_presets = len(json.loads(catalog_json)["presets"])
    print("wrote %d logos for %d presets (%d without a brand mark fall back in the app)" % (n, n_presets, n_presets - n))


if __name__ == "__main__":
    main()
