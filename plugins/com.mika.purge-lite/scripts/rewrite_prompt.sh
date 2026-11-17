#!/bin/sh
# 提示词过一遍:stdin 进原提示词,stdout 出改写后的。
# 规则在 persona.conf:
#   prefix=… / suffix=…            整段包在提示词外面
#   replace=旧=>新                 字面替换,一行一条
#   [模型名片段]prefix=…           只有发给这个模型时才生效(MIKA_MODEL 里含这个片段)
# 任何一步出错都原样输出,绝不把任务搞挂。
set -e

dir="$(cd "$(dirname "$0")/.." && pwd)"
cfg="$dir/persona.conf"
work="$dir/.runtime"
mkdir -p "$work" || { cat; exit 0; }

in="$work/prompt.in.$$"
out="$work/prompt.out.$$"
tmp="$work/prompt.tmp.$$"
trap 'rm -f "$in" "$out" "$tmp"' EXIT

cat > "$in"

[ -f "$cfg" ] || { cat "$in"; exit 0; }

cp "$in" "$out"

model=$(printf '%s' "${MIKA_MODEL:-}" | tr 'A-Z' 'a-z')

# 这一行对这个模型生效吗:行首 [片段] 存在时,只有模型名含这个片段才算
applies() {
    case "$1" in
        "["*"]"*) ;;
        *) return 0 ;;
    esac
    scope=$(printf '%s' "${1%%]*}" | tr -d '[' | tr 'A-Z' 'a-z')
    [ -n "$model" ] || return 1
    case "$model" in
        *"$scope"*) return 0 ;;
        *) return 1 ;;
    esac
}

# 去掉行首的 [片段]
strip_scope() {
    case "$1" in
        "["*"]"*) printf '%s' "${1#*]}" ;;
        *) printf '%s' "$1" ;;
    esac
}

# 1) 逐条字面替换
grep -E '^(\[[^]]*\])?replace=' "$cfg" 2>/dev/null | while IFS= read -r line; do
    applies "$line" || continue
    rule=$(strip_scope "$line" | sed 's/^replace=//')
    old=${rule%%=>*}
    new=${rule#*=>}
    [ -n "$old" ] || continue
    OLD="$old" NEW="$new" awk '
    BEGIN { o = ENVIRON["OLD"]; n = ENVIRON["NEW"] }
    {
        line = $0; out = ""; rest = line
        while ((i = index(rest, o)) > 0) {
            out = out substr(rest, 1, i - 1) n
            rest = substr(rest, i + length(o))
        }
        print (out rest)
    }' "$out" > "$tmp" && mv "$tmp" "$out"
done

# 2) 前缀 / 后缀:每个模型只取一条——带了 [模型] 的优先,否则用通用那条
pick() {
    kind="$1"
    awk -v kind="$kind" -v model="$model" '
    BEGIN { scoped = ""; generic = "" }
    {
        line = $0
        if (line ~ /^#/ || line == "") next
        sc = ""
        if (line ~ /^\[/) {
            i = index(line, "]")
            if (i == 0) next
            sc = substr(line, 2, i - 2)
            line = substr(line, i + 1)
        }
        if (index(line, kind "=") != 1) next
        val = substr(line, length(kind) + 2)
        if (sc != "") {
            if (model != "" && index(model, tolower(sc)) > 0) scoped = val
        } else {
            generic = val
        }
    }
    END { if (scoped != "") printf "%s", scoped; else printf "%s", generic }
    ' "$cfg"
}

prefix=$(pick prefix)
suffix=$(pick suffix)

{
    [ -n "$prefix" ] && printf '%s\n\n' "$prefix"
    cat "$out"
    [ -n "$suffix" ] && printf '\n\n%s\n' "$suffix"
} > "$tmp"

size=$(wc -c < "$tmp" 2>/dev/null || echo 0)
if [ "$size" -gt 204800 ] 2>/dev/null; then
    cat "$in"
    exit 0
fi

cat "$tmp"
