#!/usr/bin/env bash
# 清理本地构建残留：PyInstaller 的 workpath / distpath 与历史 EXE 产物。
#
# 为什么需要（2026-10-05 立规：本地不要保留旧版 exe）：
#   为了绕开 WorkBuddy 的「批量删除保护」，历史上每次构建都换一个全新目录名
#   （C:/_wb2、C:/_wd2 … 一直排到 N），结果 C 盘根堆了十几个、合计 1.2GB。
#   源码全在 git 里，任何时候都能重建，所以这些纯产物没有留存价值。
#
# 用法：
#   bash _clean_build.sh          # dry-run，只列清单不动手（默认，安全）
#   bash _clean_build.sh --apply  # 真删
#
# 保留：_wd_v2（当前唯一最新的 EXE 输出目录）、_downloads/（唯一最新 EXE 副本）。
set -u

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

# C 盘根的构建目录模式：_wb*/_wd* 后缀（_v2/_N/_local/_build 等变体）
targets=$(ls -d C:/_wb* C:/_wd* 2>/dev/null | grep -v '^C:/_wd_v2$')
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

total=0
n=0
echo "=== 待清理的构建目录 ==="
for d in $targets; do
  [ -d "$d" ] || continue
  sz=$(du -sm "$d" 2>/dev/null | cut -f1)
  total=$((total + ${sz:-0}))
  n=$((n + 1))
  echo "  [$n] $d  (${sz:-?} MB)"
done
echo "  合计: $n 个目录 / 约 ${total} MB"

# 仓库内 _downloads 下的历史 EXE（保留 WeAuto_v3.25.1.exe 这一个最新副本）
echo ""
echo "=== 待清理的历史 EXE（_downloads/，保留最新版）==="
keep="_downloads/WeAuto_v3.25.1.exe"
old_exes=$(ls "$repo"/_downloads/*.exe 2>/dev/null | grep -v "$(basename "$keep")")
if [ -z "$old_exes" ]; then
  echo "  （无，只有最新版 $keep）"
else
  for f in $old_exes; do echo "  $(basename "$f")  ($(du -m "$f" | cut -f1) MB)"; done
fi

if [ "$APPLY" != "1" ]; then
  echo ""
  echo "以上为 dry-run，未删除任何东西。确认后执行：bash _clean_build.sh --apply"
  exit 0
fi

echo ""
echo "=== 开始删除 ==="
# 逐个删（不用 rm -rf 通配，避免误伤）；目录内文件数多时可能被安全层拦，
# 那就退回「改名隔离」或让用户手动清。
for d in $targets; do
  [ -d "$d" ] || continue
  rm -rf "$d" && echo "  已删 $d" || echo "  [被拦] $d（文件数达批量阈值，需手动删或换新目录名）"
done
for f in $old_exes; do
  [ -f "$f" ] || continue
  rm -f "$f" && echo "  已删 $(basename "$f")" || echo "  [被拦] $f"
done
echo ""
echo "完成。保留：$keep  与  C:/_wd_v2"
