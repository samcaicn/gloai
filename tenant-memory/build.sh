#!/usr/bin/env bash
# 带排错的编译脚本：自动定位 go、拉依赖、跑 go vet，任何环节失败都给出
# 可读的诊断信息与修复建议，而不是只抛一句 "go: 未找到命令"。
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# 1) 定位 go：默认 PATH 找不到时，扫描常见安装位置并注入 PATH。
if ! command -v go >/dev/null 2>&1; then
  for cand in /usr/local/go/bin/go /usr/lib/go*/bin/go "$HOME/go/bin/go" /usr/local/bin/go; do
    if [ -x "$cand" ]; then
      export PATH="$(dirname "$cand"):$PATH"
      break
    fi
  done
fi

if ! command -v go >/dev/null 2>&1; then
  echo "✗ 编译失败：未找到 go 可执行文件。" >&2
  echo "  诊断：PATH 中无 go，且未在以下位置发现安装：" >&2
  echo "        /usr/local/go/bin/go  /usr/lib/go*/bin/go  \$HOME/go/bin/go" >&2
  echo "  修复：安装 Go 1.25+，或 export PATH=\$PATH:/usr/local/go/bin 后重试。" >&2
  exit 1
fi

echo "✓ go 版本: $(go version)"
echo "✓ 工作目录: $DIR"

# 2) 拉取依赖。
echo "--> go mod download"
if ! go mod download; then
  echo "✗ 依赖拉取失败：检查网络或 go.mod/go.sum 是否完整。" >&2
  exit 1
fi

# 3) 静态检查。
echo "--> go vet ./..."
if ! go vet ./...; then
  echo "✗ go vet 未通过：存在静态问题，请按上方输出修复后再编译。" >&2
  exit 1
fi

# 4) 编译。
echo "--> go build -o tms ."
if go build -o tms .; then
  echo "✓ 编译成功 -> $DIR/tms ($(du -h tms | cut -f1))"
else
  echo "✗ 编译失败：请查看上方 go build 报错，通常是依赖缺失或语法错误。" >&2
  exit 1
fi
