#!/usr/bin/env bash
# build-safeopc.sh — 一键触发 GitHub CI 的 safeopc 分支构建
#
# 设计原则(对应"以后只用 safeopc 分支构建"):
#   1. 本地不再做重量级构建(不产生 target/ 等垃圾)。
#   2. 把当前已提交的 HEAD 推送到 origin/safeopc(临时分支)。
#   3. CI 用 safeopc 品牌构建 Windows NSIS + macOS DMG,产物留在
#      workflow run artifacts(保留 30 天)。
#   4. CI 的 cleanup-github job 在构建成功后自动删除 safeopc 分支与所有 release。
#   5. 源码始终保留在本地当前分支(默认 gh-wt),safeopc 只是一次性触发分支。
#
# 用法:
#   ./scripts/build-safeopc.sh            # 推送并触发构建,打印进度链接
#   ./scripts/build-safeopc.sh --watch     # 额外阻塞等待本次运行结束
set -euo pipefail

REPO_REMOTE="${REPO_REMOTE:-origin}"
BUILD_BRANCH="safeopc"

echo "==> 检查 gh 登录"
if ! gh auth status >/dev/null 2>&1; then
  echo "错误: 未登录 gh,请先运行 'gh auth login'" >&2
  exit 1
fi

echo "==> 检查工作区是否干净(未提交改动不会被构建进去)"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "错误: 工作区有未提交改动。请先 git commit,或 git stash。" >&2
  git status --short
  exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "==> 当前分支: $CURRENT_BRANCH"

# 解析 owner/repo(GitHub)
REPO_URL="$(git remote get-url "$REPO_REMOTE")"
REPO="$(printf '%s' "$REPO_URL" | sed -E 's#.*github\.com[:/]##; s#\.git$##')"
if [ -z "$REPO" ]; then
  echo "错误: 无法从远程 '$REPO_REMOTE' 解析 GitHub owner/repo" >&2
  exit 1
fi

echo "==> 推送 $CURRENT_BRANCH -> $REPO_REMOTE/$BUILD_BRANCH (--force, 临时分支)"
# safeopc 是一次性分支,可能残留上次未清理的引用,用 --force 覆盖
git push --force "$REPO_REMOTE" "HEAD:refs/heads/$BUILD_BRANCH"

echo ""
echo "==> 已触发 CI 构建。查看进度:"
echo "    https://github.com/$REPO/actions"
echo ""
echo "==> 构建完成(Windows NSIS + macOS DMG)后:"
echo "    - 产物(artifact)在 workflow run 中保留 30 天"
echo "    - 下载 Windows: gh run download -R $REPO --name safeopc-windows-x64"
echo "    - 下载 macOS aarch64: gh run download -R $REPO --name safeopc-macos-aarch64"
echo "    - 下载 macOS x64: gh run download -R $REPO --name safeopc-macos-x64"
echo "    - CI 会自动删除 safeopc 分支与所有 release"
echo ""

if [ "${1:-}" = "--watch" ]; then
  RUN_ID="$(gh run list -R "$REPO" --branch "$BUILD_BRANCH" --limit 1 --json databaseId --jq '.[0].databaseId')"
  if [ -n "$RUN_ID" ]; then
    echo "==> 等待运行 #$RUN_ID 结束..."
    gh run watch -R "$REPO" "$RUN_ID"
  fi
fi
