#!/bin/sh
# 做手术:按字面串替换。自动备份、计数校验、原子写入;对不上就不写。
# 参数在 JSON 文件里($1 或 $MIKA_ARGS_FILE),这里只用 sed 抠字段,不依赖 jq。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

# 取 JSON 字符串字段。不用 sed 的 \| 交替:toybox 的 sed 不支持,真机上会永远取不到值。
field() {
    raw=$(awk -v key="$1" '
    BEGIN { pat = "\"" key "\"[[:space:]]*:[[:space:]]*\"" }
    {
        if (match($0, pat) == 0) next
        rest = substr($0, RSTART + RLENGTH)
        out = ""
        for (i = 1; i <= length(rest); i++) {
            c = substr(rest, i, 1)
            if (c == "\\") { out = out "\\" substr(rest, i + 1, 1); i++; continue }
            if (c == "\"") break
            out = out c
        }
        printf "%s", out
        exit
    }' "$args")
    printf '%b' "$raw" | sed 's/\\"/"/g'
}
num() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$args" 2>/dev/null | head -1
}

path=$(field path)
find=$(field find)
repl=$(field replace)
expect=$(num expect)
# 没填 expect 就按 1 次算:宁可拒绝,也不要一次替换掉同名的所有片段
[ -n "$expect" ] || expect=1

command -v awk >/dev/null 2>&1 || { echo "error: awk is not available on this device, so patch cannot run"; exit 7; }

if [ -z "$path" ]; then echo "error: path is required"; exit 2; fi
if [ -z "$find" ]; then echo "error: find is required"; exit 2; fi
if [ ! -f "$path" ]; then echo "error: not a file: $path"; exit 3; fi

size=$(wc -c < "$path" 2>/dev/null)
if [ "$size" -gt 2097152 ] 2>/dev/null; then
    echo "error: file is larger than 2MB ($size bytes), refusing to patch"
    exit 6
fi

plugindir="$(cd "$(dirname "$0")/.." && pwd)"
backupdir="$plugindir/.runtime/backups"
mkdir -p "$backupdir" || { echo "error: cannot create $backupdir"; exit 7; }

tmp="$plugindir/.runtime/patch.$$"
cnt="$plugindir/.runtime/patch.count.$$"
trap 'rm -f "$tmp" "$cnt"' EXIT

# 用 ENVIRON 传串,绕开 awk -v 的反斜杠转义;index() 是字面匹配,不是正则
FIND="$find" REPL="$repl" CNTF="$cnt" awk '
BEGIN { find = ENVIRON["FIND"]; repl = ENVIRON["REPL"]; n = 0 }
{
    line = $0; out = ""; rest = line
    while ((i = index(rest, find)) > 0) {
        out = out substr(rest, 1, i - 1) repl
        rest = substr(rest, i + length(find))
        n++
    }
    print (out rest)
}
END { printf "%d\n", n > ENVIRON["CNTF"]; close(ENVIRON["CNTF"]) }
' "$path" > "$tmp" || { echo "error: awk failed"; exit 8; }

hits=$(cat "$cnt" 2>/dev/null)
[ -n "$hits" ] || hits=0

echo "file: $path"
echo "matches: $hits"

if [ "$hits" -eq 0 ]; then
    echo "refused: the text was not found, nothing was written"
    exit 4
fi
if [ "$hits" -ne "$expect" ]; then
    echo "refused: expected $expect match(es), found $hits — nothing was written"
    exit 5
fi

stamp=$(date +%Y%m%d-%H%M%S)
base=$(basename "$path")
bak="$backupdir/${base}.${stamp}.bak"
cp "$path" "$bak" || { echo "error: backup failed, nothing was written"; exit 9; }

mv "$tmp" "$path" || { echo "error: write failed; original is at $bak"; exit 10; }

newsize=$(wc -c < "$path" 2>/dev/null)
echo "backup: $bak"
echo "bytes: $size -> $newsize"

# 写回之后立刻读一遍,证明改的就是这里
echo "--- after ---"
grep -n -F "$repl" "$path" 2>/dev/null | head -5

echo "restore with: restore  {\"path\": \"$path\"}"
