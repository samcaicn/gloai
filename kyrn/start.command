#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "$(node -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null || echo 0)" -lt 22 ]]; then
  for dir in "$HOME"/.nvm/versions/node/v24*/bin; do
    [[ -x "$dir/node" ]] && export PATH="$dir:$PATH" && break
  done
fi
exec node scripts/dev.mjs
