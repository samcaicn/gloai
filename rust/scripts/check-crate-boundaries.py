#!/usr/bin/env python3
"""Reject Cargo dependencies that point to a higher architecture layer."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

LAYERS: dict[str, int] = {
    "contracts": 6,
    "execution": 5,
    "services": 4,
    "adapters": 3,
    "assembly": 2,
    "interfaces": 1,
    "apps": 1,
    "support": 2,
}

CRATE_LAYER: dict[str, int] = {}


def crate_name(manifest: Path) -> str:
    for line in manifest.read_text().splitlines():
        if line.startswith("name = "):
            return line.split("=", 1)[1].strip().strip('"')
    raise SystemExit(f"no package name in {manifest}")


def layer_of(manifest: Path) -> int:
    rel = manifest.relative_to(ROOT).as_posix()
    for name, rank in LAYERS.items():
        needle = f"src/crates/{name}/" if name != "apps" else "src/apps/"
        if name == "apps":
            needle = "src/apps/"
        if rel.startswith(needle):
            return rank
    raise SystemExit(f"unlayered manifest: {rel}")


def parse_dsh_deps(manifest: Path) -> list[str]:
    names: list[str] = []
    for line in manifest.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or not stripped.startswith("dsh-"):
            continue
        if "workspace = true" not in stripped and "path =" not in stripped:
            continue
        names.append(stripped.split("=", 1)[0].strip())
    return names


def main() -> int:
    manifests = list(ROOT.glob("src/crates/**/Cargo.toml")) + list(
        ROOT.glob("src/apps/**/Cargo.toml")
    )
    for manifest in manifests:
        CRATE_LAYER[crate_name(manifest)] = layer_of(manifest)

    errors: list[str] = []
    for manifest in manifests:
        here = crate_name(manifest)
        here_rank = CRATE_LAYER[here]
        for dep in parse_dsh_deps(manifest):
            there = CRATE_LAYER.get(dep)
            if there is None:
                errors.append(f"{here} depends on unknown workspace crate {dep}")
                continue
            if there < here_rank:
                errors.append(
                    f"{here} (layer {here_rank}) depends upward on {dep} (layer {there})"
                )
    if errors:
        print("crate boundary violations:")
        for item in errors:
            print(f"  {item}")
        return 1
    print(f"ok: {len(manifests)} manifests, downward-only workspace deps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
