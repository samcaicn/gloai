#!/bin/sh
# 参数在 $MIKA_ARGS_FILE 里(JSON)。这里只用 sed 抠字段,不依赖 jq。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}
num() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$args" 2>/dev/null | head -1
}

dir=$(field dir)
limit=$(num limit)
[ -n "$limit" ] || limit=40
case "$limit" in *[!0-9]*) limit=40 ;; esac
[ "$limit" -ge 1 ] 2>/dev/null || limit=40
[ "$limit" -le 200 ] 2>/dev/null || limit=200

if [ -z "$dir" ]; then
    echo "error: dir is required"
    exit 2
fi
if [ ! -d "$dir" ]; then
    echo "error: not a directory: $dir"
    exit 3
fi

echo "scan: $dir"
echo "pattern: system prompt / persona / instructions / 你是 / You are"
echo

# 只看文本类文件,跳过 .git、node_modules 之类的噪音
work="$dir/.mika-scan"
found=0
list=$(mktemp 2>/dev/null || echo "/tmp/mika-scan.$$")
find "$dir" -type f \( -name '*.md' -o -name '*.txt' -o -name '*.json' -o -name '*.yml' -o -name '*.yaml' -o -name '*.js' -o -name '*.ts' -o -name '*.py' \) 2>/dev/null | head -2000 > "$list"
while IFS= read -r f; do
    hits=$(grep -c -i -E 'system prompt|systemPrompt|persona|instructions|you are an? |你是|提示词' "$f" 2>/dev/null)
    [ -n "$hits" ] || hits=0
    [ "$hits" -gt 0 ] || continue
    size=$(wc -l < "$f" 2>/dev/null)
    echo "$hits hits  ${size} lines  ${f}"
    found=$((found + 1))
    [ "$found" -ge "$limit" ] && break
done < "$list"
rm -f "$list"

echo
echo "found: $found file(s)"
