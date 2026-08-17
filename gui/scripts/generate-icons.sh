#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PNG="$(python3 "$ROOT/scripts/rasterize-app-icon.py")"
cd "$ROOT"
pnpm exec tauri icon "$PNG"
