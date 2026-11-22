#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Generate app/src/main/assets/mcp/mcp-logos.json — service brand logos (path + brand color).

The catalog is secret-free connection data; logos are presentation data, kept in a separate asset.
Each entry maps a catalog preset id to a monochrome brand mark (SVG path `d` + 6-digit hex brand color)
so the app can render it as a Compose ImageVector with no image-loading library.

Source: a permissively-licensed (CC0) brand-icon set, pinned to two versions merged by slug —
the newer version for current brands, the older one for brands later removed at their owners' request.
Brand marks remain the property of their owners; attribution lives in licenses/THIRD_PARTY_NOTICES.md.

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
]

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
}

_MIN_LUMA = 0.18  # keep near-white brand colors readable on the light tile


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


def _fetch_path(slug: str, version: str) -> str:
    svg = _fetch(_svg_tpl_for(version) % slug).decode("utf-8")
    m = re.search(r'<path[^>]*\bd="([^"]+)"', svg)
    if not m:
        raise ValueError("no path in %s svg" % slug)
    return m.group(1)


def _clamp(hexv: str) -> str:
    r, g, b = (int(hexv[i:i + 2], 16) / 255.0 for i in (0, 2, 4))
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    if luma >= _MIN_LUMA:
        return hexv
    scale = _MIN_LUMA / max(luma, 1e-6)
    return "".join("%02X" % max(0, min(255, round(int(hexv[i:i + 2], 16) * scale))) for i in (0, 2, 4))


def _slug_for(preset_id: str) -> str:
    return SLUG_OVERRIDES.get(preset_id, preset_id.replace("-", "").lower())


def generate(catalog_json: str, out_path: str) -> dict:
    hex_by_slug, ver_by_slug = _load_indexes()
    preset_ids = [p["id"] for p in json.loads(catalog_json)["presets"]]
    resolved = [(pid, _slug_for(pid)) for pid in preset_ids if _slug_for(pid) in hex_by_slug]

    def one(item):
        pid, slug = item
        try:
            path = _fetch_path(slug, ver_by_slug[slug])
            return pid, {"d": path, "color": _clamp(hex_by_slug[slug])}
        except Exception:
            return None

    logos = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
        for result in ex.map(one, resolved):
            if result:
                logos[result[0]] = result[1]
    out = {"formatVersion": 1, "logos": dict(sorted(logos.items()))}
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
