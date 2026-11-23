#!/usr/bin/env python3
"""把 plugins/ 下的每个插件打成可安装的 zip(manifest.json 在 zip 根,skills/scripts/docs 原样带入)。"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "dist")


def build() -> int:
    os.makedirs(DIST, exist_ok=True)
    plugins = [d for d in os.listdir(ROOT)
               if os.path.isdir(os.path.join(ROOT, d)) and not d.startswith(".") and d not in ("dist", "examples")]
    for p in sorted(plugins):
        src = os.path.join(ROOT, p)
        manifest_path = os.path.join(src, "manifest.json")
        if not os.path.isfile(manifest_path):
            print(f"skip {p}: no manifest.json", file=sys.stderr)
            continue
        with open(manifest_path, encoding="utf-8") as f:
            m = json.load(f)
        out = os.path.join(DIST, f"{m['id']}-{m['version']}.zip")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
            for dirpath, _dirnames, filenames in os.walk(src):
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, src)
                    if rel == "manifest.json":
                        zf.write(full, "manifest.json")
                    else:
                        zf.write(full, rel)
        print(f"built {os.path.relpath(out, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(build())
