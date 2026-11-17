#!/bin/sh
# 公开信息台账:只做两件事——记一条(必须带来源)、列出来。
# 参数:{"action":"add","text":"...","source":"https://...","tag":"可选"}
#       {"action":"list","limit":20}
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

dir="$(cd "$(dirname "$0")/.." && pwd)"
ledger="$dir/.runtime/ledger.md"
mkdir -p "$dir/.runtime" || { echo "error: cannot create runtime dir"; exit 2; }

action=$(field action)
[ -n "$action" ] || action=add
limit=$(num limit)
[ -n "$limit" ] || limit=20
case "$limit" in *[!0-9]*) limit=20 ;; esac
[ "$limit" -ge 1 ] 2>/dev/null || limit=20
[ "$limit" -le 200 ] 2>/dev/null || limit=200

if [ "$action" = "list" ]; then
    if [ ! -f "$ledger" ]; then
        echo "台账还是空的:$ledger"
        exit 0
    fi
    total=$(grep -c '^- ' "$ledger" 2>/dev/null || echo 0)
    echo "台账:$ledger"
    echo "条目:$total"
    echo "---"
    grep '^- ' "$ledger" 2>/dev/null | tail -n "$limit"
    exit 0
fi

text=$(field text)
source=$(field source)
tag=$(field tag)

if [ -z "$text" ]; then
    echo "error: text is required"
    exit 3
fi
# 没有来源的条目一律不收:这是这套流程唯一不能让步的规矩
case "$source" in
    http://*|https://*) ;;
    *) echo "error: source is required and must be an http(s) URL — 没有来源的线索不收"; exit 4 ;;
esac

stamp=$(date '+%Y-%m-%d %H:%M')
{
    printf -- '- %s | %s | 来源: %s' "$stamp" "$text" "$source"
    [ -n "$tag" ] && printf ' | 标签: %s' "$tag"
    printf '\n'
} >> "$ledger"

echo "recorded: $ledger"
echo "entry: $text"
echo "source: $source"
echo "entries now: $(grep -c '^- ' "$ledger" 2>/dev/null || echo 0)"
