#!/system/bin/sh
# translate —— 调用用户自己配的 OpenAI 兼容接口，把文本翻译成目标语言。
#
# 跑在 Mika 的 App 进程里（没有 python3），所以只用 POSIX sh + 一个极小的 awk-JSON 解析器。
# 参数从 $MIKA_ARGS_FILE（JSON）读，脚本本身也接受 $1 当参数文件路径。
# 凭据走环境变量：MIKA_TRANSLATE_BASE / MIKA_TRANSLATE_KEY / MIKA_TRANSLATE_MODEL
# （Mika 的插件脚本继承 App 进程环境，所以在系统里配好这三个变量，文本就不会经过对话）。

set -u

ARGS="${1:-${MIKA_ARGS_FILE:-}}"
if [ -z "$ARGS" ] || [ ! -f "$ARGS" ]; then
  printf '{"ok":false,"error":"bad_args","message":"no argument file; expected MIKA_ARGS_FILE"}\n'
  exit 0
fi

# ---------- 极简 JSON：取顶层字符串值 ----------
# json_str <file> <key>：返回已解码的字符串（\\n \\t \\" \\uXXXX 基本覆盖），不存在返回空。
json_str() {
  awk -v key="$2" '
    { buf = buf $0 "\n" }
    END {
      pat = "\"" key "\"[ \t\r\n]*:"
      if (!match(buf, pat)) exit 0
      i = RSTART + RLENGTH
      n = length(buf)
      # 跳过空白
      while (i <= n) { c = substr(buf, i, 1); if (c != " " && c != "\t" && c != "\n" && c != "\r") break; i++ }
      if (substr(buf, i, 1) != "\"") exit 0
      i++
      out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") {
          d = substr(buf, i + 1, 1)
          if (d == "n") out = out "\n"
          else if (d == "t") out = out "\t"
          else if (d == "r") out = out ""
          else if (d == "\"") out = out "\""
          else if (d == "\\") out = out "\\"
          else if (d == "/") out = out "/"
          else if (d == "u") {
            hex = substr(buf, i + 2, 4)
            # 只解 BMP 常用区（中文）；代理对不管，原文里也几乎不会出现
            code = 0
            for (k = 1; k <= 4; k++) {
              ch = substr(hex, k, 1)
              v = index("0123456789abcdef", tolower(ch))
              if (v == 0) { code = 63; break }
              code = code * 16 + (v - 1)
            }
            if (code < 128) out = out sprintf("%c", code)
            else if (code < 2048) out = out sprintf("%c%c", 192 + int(code / 64), 128 + code % 64)
            else out = out sprintf("%c%c%c", 224 + int(code / 4096), 128 + int(code / 64) % 64, 128 + code % 64)
            i = i + 4
          } else out = out d
          i = i + 2
          continue
        }
        if (c == "\"") break
        out = out c
        i++
      }
      # 这里解出来的是真正的文本（换行就是换行），不要再转义；转义统一在输出 JSON 的地方做
      printf "%s", out
    }
  ' "$1"
}

json_num() {
  # 参数可能是数字也可能是字符串（模型的 JSON 两种都会给）
  sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([0-9][0-9]*\)".*/\1/p' "$1" | head -n 1 > /tmp/.jn1
  sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$1" | head -n 1 > /tmp/.jn2
  A=$(cat /tmp/.jn1); B=$(cat /tmp/.jn2); rm -f /tmp/.jn1 /tmp/.jn2
  [ -n "$A" ] && printf '%s' "$A" || printf '%s' "$B"
}

# ---------- 输入 ----------
TEXT=$(json_str "$ARGS" text)
TARGET=$(json_str "$ARGS" target)
SOURCE=$(json_str "$ARGS" source)
STYLE=$(json_str "$ARGS" style)
CHUNK=$(json_num "$ARGS" chunk)
# 目标语言：参数 > 环境变量 MIKA_TRANSLATE_TARGET > 简体中文。
# 不做白名单——用户说德语、越南语、斯瓦希里语都行，模型自己认。
[ -n "$TARGET" ] || TARGET="${MIKA_TRANSLATE_TARGET:-}"
[ -n "$TARGET" ] || TARGET="简体中文"
TARGET_NOTE=""
# 别名匹配时去掉空格和连字符（"Traditional Chinese"、"zh-tw" 都能认），但保留原始值用于透传
NORM=$(printf '%s' "$TARGET" | tr 'A-Z' 'a-z' | tr -d ' _-')
case "$NORM" in
  zh|zhcn|cn|chinese|中文|简体|简体中文|zhhans)
    TARGET="简体中文"; TARGET_NOTE="用中国大陆的习惯用语与标点。" ;;
  zhtw|zhhk|hk|tw|har|hant|繁体|繁體|繁體中文|繁体中文|traditionalchinese)
    TARGET="繁體中文"; TARGET_NOTE="用台灣的習慣用語與標點（軟體、網路、影片這類詞按台灣說法）。" ;;
  ja|jp|japanese|日语|日文|日本語)
    TARGET="日本語"; TARGET_NOTE="自然な日本語で。" ;;
  ko|kr|korean|韩语|韓語|한국어)
    TARGET="한국어"; TARGET_NOTE="자연스러운 한국어로。" ;;
  en|english|英语|英文)
    TARGET="English"; TARGET_NOTE="Natural English." ;;
  *) TARGET_NOTE="" ;;
esac
[ -n "$CHUNK" ] || CHUNK=1800
# 默认 1800；允许调小到 200，上限 6000（太大的段模型容易漏译）。
# 小于 200 的不夹到 200，直接回落默认——一段太小只会让切分变碎。
if [ "$CHUNK" -lt 200 ] 2>/dev/null || [ "$CHUNK" -gt 6000 ] 2>/dev/null; then
  CHUNK=1800
fi

if [ -z "$(printf '%s' "$TEXT" | tr -d ' \t\n\r')" ]; then
  printf '{"ok":false,"error":"empty_text","message":"nothing to translate"}\n'
  exit 0
fi

BASE="${MIKA_TRANSLATE_BASE:-}"
KEY="${MIKA_TRANSLATE_KEY:-}"
MODEL="${MIKA_TRANSLATE_MODEL:-}"
NOTE=""
case "$BASE" in
  http://*|https://*) ;;
  *) BASE=""; NOTE="未配置 MIKA_TRANSLATE_BASE，用自己的模型在对话里翻译（不调用接口）。" ;;
esac

# ---------- 切段 ----------
# 按空行 → 换行 → 句末标点 → 硬切，尽量在自然边界断开，减少上下文丢失。
# 工作目录解析成绝对路径：相对路径（pt/scripts/../work）在 awk 里写文件会失败——awk 的 > 不做父目录解析。
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SELF=""
[ -n "$SELF" ] || SELF="$(dirname "$0")"
WORK="$SELF/../work"
mkdir -p "$WORK" 2>/dev/null
WORK="$(cd "$WORK" 2>/dev/null && pwd)" || WORK="/tmp"
PART="$WORK/rt-$$.txt"
rm -f "$WORK"/rt-"$$".*.txt 2>/dev/null
LIST="$WORK/rt-$$.list"
# awk 的 > 写文件是按行拼的：正文里真正的换行会被当成「新的一行」截断。
# 所以先编码（换行 → 字面 \n，反斜杠 → \001），awk 只处理单行，落盘后再解码。
enc() { printf '%s' "$1" | awk '{ if (NR > 1) printf "%sn", "\\"; printf "%s", $0 }'; }
printf '%s' "$(enc "$TEXT")" | awk -v max="$CHUNK" -v dir="$WORK" -v pid="$$" -v list="$LIST" '
  # awk 的 length/substr 在 Android 的 awk 上按字节算，一个汉字 3 字节；
  # 不按字符切会把半个汉字切成乱码。这里按 UTF-8 前导字节（用字符串比较，避开 ord 的坑）建映射，
  # 长度、截断、取第 k 个字符一律按字符。不是有效 UTF-8 的行退回按字节。
  function buildmap(s,   i, n, c, o, w) {
    split("", CB); split("", CE)
    o = 0; n = length(s)
    for (i = 1; i <= n; i++) {
      c = substr(s, i, 1)
      if (c < "\200") w = 1
      else if (c < "\340") w = 2
      else if (c < "\360") w = 3
      else w = 4
      if (i + w - 1 > n) return 0
      o++; CB[o] = i; CE[o] = i + w - 1
      i += w - 1
    }
    CLEN = o
    return 1
  }
  function charlen(s) { return (buildmap(s)) ? CLEN : length(s) }
  function charsub(s, a, b,   en) {
    if (!buildmap(s)) return substr(s, a, b)
    if (a < 1) a = 1
    if (a > CLEN || b <= 0) return ""
    en = a + b - 1
    if (en > CLEN) en = CLEN
    return substr(s, CB[a], CE[en] - CB[a] + 1)
  }
  function charat(s, k) {
    if (!buildmap(s)) return substr(s, k, 1)
    if (k < 1 || k > CLEN) return ""
    return substr(s, CB[k], CE[k] - CB[k] + 1)
  }
  # 注意：$(...) 会吃掉末尾换行，所以最后一行不追加 \n
  { lines[NR] = $0 }
  END {
    buf = ""; n = 0; pending_blank = ""
    for (r = 1; r <= NR; r++) {
      line = lines[(r)]
      # 空行先记着，等下一行真产出块时再补（否则段落在段尾的空行会丢掉）
      if (line ~ /^[ \t]*$/) { pending_blank = pending_blank "\n"; continue }
      # 先按句末标点切（中英文都认）；长度一律按字符。
      # 循环结束时 rest 为空 = 这一行处理完了；在块里另算 eol 标记，
      # 不另用 cut==0 判行尾（最后一块 cut 也可能 >0，那样就永远不结束）。
      rest = line
      while (1) {
        # 注意：不能写 charlen(rest) + charlen(buf)；buildmap 会改全局 CLEN，先后调用会互相盖掉
        if (!buildmap(rest)) { LEN_REST = length(rest) } else { LEN_REST = CLEN }
        if (!buildmap(buf)) { LEN_BUF = length(buf) } else { LEN_BUF = CLEN }
        eol = 0
        if (LEN_REST + LEN_BUF <= max) {
          piece = rest
          rest = ""
          eol = 1
        } else {
          head = charsub(rest, 1, max)
          buildmap(rest); RLEN = CLEN
          cut = 0
          for (k = charlen(head); k > max * 0.5; k--) {
            ch = charat(head, k)
            if (ch == "." || ch == "!" || ch == "?" || ch == "。" || ch == "！" || ch == "？" || ch == ";" || ch == "；") { cut = k; break }
          }
          if (cut > 0) {
            piece = charsub(rest, 1, cut)
            if (cut >= RLEN) { rest = ""; eol = 1 }
            else { rest = charsub(rest, cut + 1, RLEN - cut) }
          } else {
            piece = head
            if (max >= RLEN) { rest = ""; eol = 1 }
            else { rest = charsub(rest, max + 1, RLEN - max) }
          }
        }
        buf = buf pending_blank piece
        pending_blank = ""
        buildmap(buf); BUF_NOW = CLEN
        if (BUF_NOW >= max) { n++; f = sprintf("%s/rt-%s.%04d.txt", dir, pid, n); printf "%s", buf > f; close(f); printf "%s\n", f >> list; buf = "" }
        if (eol) break
      }
    }
    if (pending_blank != "" && charlen(buf) > 0) buf = buf pending_blank
    if (charlen(buf) > 0) { n++; f = sprintf("%s/rt-%s.%04d.txt", dir, pid, n); printf "%s", buf > f; close(f); printf "%s\n", f >> list }
  }
'
# 逐段解码回真文本（字面 \n → 换行，\001 → 反斜杠）
if [ -s "$LIST" ]; then
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    sed -e 's/\001/\\/g' -e 's/\\n/\n/g' -e '$ s/\n$//' "$f" > "$f.dec" && mv "$f.dec" "$f"
  done < "$LIST"
fi
PARTS=$(wc -l < "$LIST" 2>/dev/null | tr -d ' ')
[ -n "$PARTS" ] || PARTS=0
[ "$PARTS" -gt 0 ] || { printf '{"ok":false,"error":"split_failed","message":"could not split the text"}\n'; exit 0; }
if [ "$PARTS" -gt 40 ]; then
  rm -f "$WORK"/rt-"$$".*
  printf '{"ok":false,"error":"too_long","message":"%s 段超过 40 段上限，请分次翻译（改用 web_read_page 的 offset 分页）"}\n' "$PARTS"
  exit 0
fi

# ---------- JSON 转义 ----------
# JSON 转义：反斜杠 → 引号 → 换行/制表。换行用 awk 转（多条 sed 的 -e 是按顺序作用的，
# 用 sed 转完再删 \r 会把刚写进去的 \n 又去掉，实测踩过）。
# 制表符也一并转掉（裸 \t 在 JSON 字符串里同样非法）
jesc() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{first=1} { if (!first) printf "\\n"; first=0; gsub(/\t/, "\\t"); printf "%s", $0 }' ; }

# ---------- 没有接口：把切好的段交回去，让模型自己翻 ----------
if [ -z "$BASE" ]; then
  OUT="$WORK/rt-$$.out.json"
  {
    printf '{"ok":true,"mode":"segments","note":"%s","target":"%s","targetNote":"%s","parts":%s,"segments":[' "$NOTE" "$TARGET" "$TARGET_NOTE" "$PARTS"
    i=0
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      i=$((i + 1))
      [ "$i" -gt 1 ] && printf ','
      printf '{"n":%s,"text":"' "$i"
      jesc < "$f"
      printf '"}'
      rm -f "$f"
    done < "$LIST"
    printf ']}\n'
  } > "$OUT"
  cat "$OUT"
  rm -f "$LIST" "$OUT"
  exit 0
fi

# ---------- 调接口 ----------
STYLE_RULE=""
case "$STYLE" in
  literal) STYLE_RULE="贴近原文结构，直译为主，术语保持前后一致。" ;;
  polish) STYLE_RULE="可读性优先，允许调整语序，术语保持前后一致。" ;;
  *) STYLE_RULE="准确、通顺，术语保持前后一致；保留原文的段落与列表结构。" ;;
esac
SRC_RULE=""
[ -n "$SOURCE" ] && SRC_RULE="源语言是 $SOURCE。"
SYS="你是专业翻译。把用户给出的文本翻译成${TARGET}。${SRC_RULE}${STYLE_RULE}${TARGET_NOTE}只输出译文，不要解释、不要加引号、不要输出原文。保留原文中的网址、代码、专有名词不译。"

OUT="$WORK/rt-$$.out.json"
: > "$OUT"
AI=0
ERR=""
while IFS= read -r f; do
  [ -f "$f" ] || continue
  AI=$((AI + 1))
  BODY="$WORK/rt-$$.req.json"
  {
    printf '{"model":"%s","temperature":0.2,"messages":[{"role":"system","content":"' "$MODEL"
    printf '%s' "$SYS" | jesc
    printf '"},{"role":"user","content":"'
    jesc < "$f"
    printf '"}]}'
  } > "$BODY"

  CODE=$(curl -s -o "$WORK/rt-$$.resp.json" -w '%{http_code}' \
    --max-time 90 -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $KEY" \
    --data-binary "@$BODY" "$BASE" 2>/dev/null)
  CODE=${CODE:-000}
  case "$CODE" in
    2??) ;;
    *)
      ERR="HTTP $CODE $(head -c 300 "$WORK/rt-$$.resp.json" 2>/dev/null | tr -d '\n')"
      break ;;
  esac

  # choices[0].message.content（字符串）。reasoning_content 有时排在前面，先切到 message 之后更稳。
  TR=$(awk 'BEGIN { RSTART = 0 }
    {
      buf = buf $0 "\n"
    }
    END {
      pos = index(buf, "\"message\"")
      if (pos > 0) buf = substr(buf, pos)
      if (!match(buf, "\"content\"[ \t\r\n]*:[ \t\r\n]*\"")) exit 0
      i = RSTART + RLENGTH
      n = length(buf)
      out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") {
          d = substr(buf, i + 1, 1)
          if (d == "n") out = out "\n"
          else if (d == "t") out = out "\t"
          else if (d == "r") out = out ""
          else if (d == "\"") out = out "\""
          else if (d == "\\") out = out "\\"
          else if (d == "/") out = out "/"
          else if (d == "u") {
            hex = substr(buf, i + 2, 4)
            code = 0
            for (k = 1; k <= 4; k++) {
              ch = substr(hex, k, 1)
              v = index("0123456789abcdef", tolower(ch))
              if (v == 0) { code = 63; break }
              code = code * 16 + (v - 1)
            }
            if (code < 128) out = out sprintf("%c", code)
            else if (code < 2048) out = out sprintf("%c%c", 192 + int(code / 64), 128 + code % 64)
            else out = out sprintf("%c%c%c", 224 + int(code / 4096), 128 + int(code / 64) % 64, 128 + code % 64)
            i = i + 4
          } else out = out d
          i = i + 2
          continue
        }
        if (c == "\"") break
        out = out c
        i++
      }
      # 转义：解出来的串里换行是真的换行，这里的 out 只用来拼 JSON 文件（下面再 cat/塞进 JSON）
      gsub(/\r/, "", out)
      gsub(/\\n/, "\001", out)   # 已经是字面反斜杠+n 的先占位，别被下面再转一次
      gsub(/\n/, "\\\\n", out)
      gsub(/\t/, "\\\\t", out)
      gsub(/\001/, "\\\\n", out)
      printf "%s", out
    }
  ' "$WORK/rt-$$.resp.json")

  if [ -z "$(printf '%s' "$TR" | tr -d ' \t\n\r')" ]; then
    # 有的兼容接口拒绝 system 消息：直接把指令放进 user，再试一次
    BODY2="$WORK/rt-$$.req2.json"
    {
      printf '{"model":"%s","temperature":0.2,"messages":[{"role":"user","content":"'
      printf '%s\n\n---\n' "$SYS" | jesc
      jesc < "$f"
      printf '"}]}'
    } > "$BODY2"
    CODE=$(curl -s -o "$WORK/rt-$$.resp.json" -w '%{http_code}' \
      --max-time 90 -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $KEY" \
      --data-binary "@$BODY2" "$BASE" 2>/dev/null)
    CODE=${CODE:-000}
    case "$CODE" in
      2??) ;;
      *) ERR="HTTP $CODE $(head -c 300 "$WORK/rt-$$.resp.json" 2>/dev/null | tr -d '\n')"; break ;;
    esac
    TR=$(awk '{
      buf = buf $0 "\n"
    }
    END {
      pos = index(buf, "\"message\"")
      if (pos > 0) buf = substr(buf, pos)
      if (!match(buf, "\"content\"[ \t\r\n]*:[ \t\r\n]*\"")) exit 0
      i = RSTART + RLENGTH
      n = length(buf); out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") { d = substr(buf, i + 1, 1); if (d == "n") out = out "\n"; else if (d == "t") out = out "\t"; else if (d == "\"") out = out "\""; else if (d == "\\") out = out "\\"; else out = out d; i = i + 2; continue }
        if (c == "\"") break
        out = out c; i++
      }
      printf "%s", out
    }' "$WORK/rt-$$.resp.json")
  fi

  [ -n "$TR" ] || { ERR="empty_translation（接口返回里没有 content）"; break; }
  # 段与段之间只补一个换行；段内换行保持模型给的样子
  [ "$AI" -gt 1 ] && printf '\n' >> "$OUT"
  # 模型有时把换行写成字面的反斜杠+n：拼成大段时统一还原成真换行
  printf '%s' "$TR" | tr -d '\r' | sed 's/\\n/\n/g' >> "$OUT"
done < "$LIST"

if [ -n "$ERR" ]; then
  rm -f "$WORK"/rt-"$$".*
  printf '{"ok":false,"error":"api_failed","message":"%s"}\n' "$(printf '%s' "$ERR" | jesc)"
  exit 0
fi

{
  printf '{"ok":true,"mode":"translated","target":"%s","calls":%s,"translation":"' "$TARGET" "$AI"
  jesc < "$OUT"
  printf '"}\n'
} > "$OUT.json"
cat "$OUT.json"
rm -f "$WORK"/rt-"$$".*

if [ "$AI" -gt 0 ] && [ "$AI" -gt 3 ]; then
  # 多段全成功才算完整；一段失败上面就 break 了
  :
fi
exit 0
