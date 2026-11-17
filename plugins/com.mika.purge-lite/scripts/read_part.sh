#!/bin/sh
# 只看前 N 行,别把整份文件灌进上下文。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}
num() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$args" 2>/dev/null | head -1
}

path=$(field path)
lines=$(num lines)
[ -n "$lines" ] || lines=80
case "$lines" in *[!0-9]*) lines=80 ;; esac
[ "$lines" -ge 1 ] 2>/dev/null || lines=80
[ "$lines" -le 400 ] 2>/dev/null || lines=400

if [ -z "$path" ]; then
    echo "error: path is required"
    exit 2
fi
if [ ! -f "$path" ]; then
    echo "error: not a file: $path"
    exit 3
fi

total=$(wc -l < "$path" 2>/dev/null)
echo "file: $path"
echo "lines: $total (showing first $lines)"
echo "---"
head -n "$lines" "$path"
echo "---"
if [ "$total" -gt "$lines" ] 2>/dev/null; then
    echo "(还有 $((total - lines)) 行没显示)"
fi
