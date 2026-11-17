#!/bin/sh
# 一键还原:把 persona.conf 恢复成默认(全是注释,等于不修改提示词)。
# 原来的内容先备份到 .runtime/backups/,随时能拿回来。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

dir="$(cd "$(dirname "$0")/.." && pwd)"
cfg="$dir/persona.conf"
backupdir="$dir/.runtime/backups"
mkdir -p "$backupdir" || { echo "error: cannot create $backupdir"; exit 2; }

if [ -f "$cfg" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    bak="$backupdir/persona.conf.$stamp.bak"
    cp "$cfg" "$bak" || { echo "error: backup failed, nothing changed"; exit 3; }
    echo "backup: $bak"
fi

cat > "$cfg" <<'EOS'
# 提示词改写配置。改完下一次任务生效。
# 整段包在提示词外面的：prefix=（放最前）suffix=（放最后）
# 逐条替换的：replace=旧文字=>新文字（按字面匹配，不是正则）
# 行首加 [模型名片段] 只对那个模型生效，例如 [claude]prefix=…
#
# 例：先给结论
# prefix=先给结论，再给理由。不确定的事直接说不确定，不要绕。
#
# 例：把身份句换成中文（左边是 Mika 原文，右边是想换成的）
# replace=You are Mika, the user's personal assistant on their phone.=>你是 Mika。
#
# 例：只对某个模型追加要求
# [gpt]suffix=回答尽量控制在三句话内。
EOS
echo "restored: $cfg"
echo "rules now: $(grep -cE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null || echo 0)"
