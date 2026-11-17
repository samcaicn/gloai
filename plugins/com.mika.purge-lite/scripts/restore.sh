#!/bin/sh
# 回滚:把这个文件最近一次备份放回去(先备份当前内容,回滚也能再回滚)。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}

path=$(field path)
if [ -z "$path" ]; then echo "error: path is required"; exit 2; fi

plugindir="$(cd "$(dirname "$0")/.." && pwd)"
backupdir="$plugindir/.runtime/backups"
base=$(basename "$path")

latest=$(ls -1t "$backupdir/$base".*.bak 2>/dev/null | head -1)
if [ -z "$latest" ]; then
    echo "error: no backup for $base in $backupdir"
    exit 3
fi

# 当前的先留一份,回滚本身也可逆
if [ -f "$path" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    cp "$path" "$backupdir/${base}.${stamp}.before-restore.bak"
fi

cp "$latest" "$path" || { echo "error: restore failed"; exit 4; }

echo "restored: $path"
echo "from: $latest"
echo "bytes: $(wc -c < "$path" 2>/dev/null)"
