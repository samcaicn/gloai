#!/bin/sh
# 一键应用:检查 persona.conf 能不能用,并当场试跑一次(只看,不改任何文件)。
# 参数:没有必填项;可选 {"model":"模型名"} 指定按哪个模型试跑。
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}

dir="$(cd "$(dirname "$0")/.." && pwd)"
cfg="$dir/persona.conf"
hook="$dir/scripts/rewrite_prompt.sh"

if [ ! -f "$cfg" ]; then
    echo "error: persona.conf 不见了:$cfg"
    exit 2
fi
if [ ! -f "$hook" ]; then
    echo "error: rewrite_prompt.sh 不见了:$hook"
    exit 3
fi

model=$(field model)
[ -n "$model" ] || model="${MIKA_MODEL:-}"

rules=$(grep -cE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null)
[ -n "$rules" ] || rules=0

echo "配置:$cfg"
echo "规则:$rules 条"
if [ "$rules" -eq 0 ]; then
    echo "提示:现在一条有效规则都没有,提示词会原样发出去。"
fi

echo "--- 规则 ---"
grep -nE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null | sed 's/^/  /' || true

sample="You are Mika, the user's personal assistant on their phone.
Rule: check the current state before acting."

echo "--- 试跑(模型:${model:-未指定}) ---"
before=$(printf '%s' "$sample" | wc -c)
after_json=$(printf '%s' "$sample" | MIKA_MODEL="$model" sh "$hook" | wc -c)
echo "输入 $before 字节 → 输出 $after_json 字节"

printf '%s' "$sample" | MIKA_MODEL="$model" sh "$hook" | head -6 | sed 's/^/  /'

echo "--- 结论 ---"
if [ "$rules" -gt 0 ]; then
    echo "配置可用。它已经生效:下一个任务里提示词就会先过这段脚本。"
else
    echo "配置可用,但没有规则,等于什么都没改。"
fi
