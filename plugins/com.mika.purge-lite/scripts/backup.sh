#!/bin/sh
# 备份只落在插件自己的 .runtime/backups 里,不动原文件、不碰别的目录。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}

path=$(field path)
if [ -z "$path" ]; then
    echo "error: path is required"
    exit 2
fi
if [ ! -f "$path" ]; then
    echo "error: not a file: $path"
    exit 3
fi

dir="$(cd "$(dirname "$0")/.." && pwd)/.runtime/backups"
mkdir -p "$dir" || { echo "error: cannot create $dir"; exit 4; }

stamp=$(date +%Y%m%d-%H%M%S)
base=$(basename "$path")
out="$dir/${base}.${stamp}.bak"
cp "$path" "$out" || { echo "error: copy failed"; exit 5; }

echo "backed up: $path"
echo "to: $out"
echo "bytes: $(wc -c < "$out" 2>/dev/null)"
