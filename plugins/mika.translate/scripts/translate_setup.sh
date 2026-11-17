#!/system/bin/sh
# doc —— 给插件的技能用：把插件目录下的说明文档打印出来（App 里没有 cat 之外的读物，统一走这里）。
# 本来想用 cat，留着这个占位是为了以后文档变多时能按章节取；现在只支持整篇。
set -u
ARGS="${1:-${MIKA_ARGS_FILE:-}}"
NAME=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p' "$ARGS" 2>/dev/null | head -n 1)
[ -n "$NAME" ] || NAME="translate-setup"
DIR="$(dirname "$0")/../docs"
FILE="$DIR/$NAME.md"
if [ ! -f "$FILE" ]; then
  printf '{"ok":false,"error":"not_found","message":"没有这个文档：%s（可用：translate-setup）"}\n' "$NAME"
  exit 0
fi
printf '{"ok":true,"name":"%s","text":"' "$NAME"
sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "$FILE" | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g' -e 's/\r//g' -e 's/\t/\\t/g'
printf '"}\n'
