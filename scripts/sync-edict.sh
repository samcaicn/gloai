#!/usr/bin/env bash
# =============================================================================
# sync-edict.sh —— 把上游 edict 的“当前 main”同步进根目录 /workspace/edict，
# 并叠加“已提出的需求分支”的真实功能增量（见 scripts/edict-integration.patch）。
#
# 设计原则：
#   - 只读读取上游：所有数据均通过 `git -C /up archive` 导出，绝不修改 /up。
#   - 可复现：每次重跑都从 /up 重新导出 main + 打补丁，结果一致。
#   - 独立组件：/workspace/edict 是根目录下的独立子目录（伴生服务），
#     不直接并入 Go Hub 工程；如需入口/路由可另行在根工程内接入。
#
# 用法：
#   ./scripts/sync-edict.sh            # 导出 main + 打补丁 + 写集成清单
#   ./scripts/sync-edict.sh --dry-run  # 只校验上游可用性，不写文件
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="/up"
TARGET="$ROOT/edict"
PATCH="$ROOT/scripts/edict-integration.patch"
MANIFEST="$TARGET/INTEGRATED.md"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

echo "==> 根目录: $ROOT"
echo "==> 上游(只读): $UPSTREAM"
echo "==> 目标组件: $TARGET"

# ---- 1. 校验上游可用性（只读检查，不改 /up）----
if [[ ! -d "$UPSTREAM/.git" ]]; then
  echo "✗ 上游不是 git 仓库: $UPSTREAM" >&2
  exit 1
fi
if ! git -C "$UPSTREAM" rev-parse --verify origin/main >/dev/null 2>&1; then
  echo "✗ 上游缺少 origin/main 引用" >&2
  exit 1
fi
UP_COMMIT="$(git -C "$UPSTREAM" rev-parse origin/main)"
echo "==> 上游 origin/main @ ${UP_COMMIT:0:12}"

# ---- 2. 校验补丁文件 ----
if [[ ! -f "$PATCH" ]]; then
  echo "✗ 补丁文件不存在: $PATCH" >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "==> [dry-run] 上游可用、补丁存在，校验通过。未写入任何文件。"
  exit 0
fi

# ---- 3. 从只读上游导出 edict main 到目标目录 ----
echo "==> 导出 edict origin/main -> $TARGET"
rm -rf "$TARGET"
mkdir -p "$TARGET"
git -C "$UPSTREAM" archive origin/main | tar -x -C "$TARGET"

# ---- 4. 叠加需求分支的真实功能增量 ----
# 说明：/workspace/edict 不是 git 仓库，而 /workspace 是；直接 `git apply`
# 会向上找到 /workspace 并把补丁路径按 /workspace 根解析，导致找不到文件。
# 故先建一个临时仓库让 `git apply -p1` 以本目录为根，打完即删，保持组件为纯目录。
echo "==> 应用集成补丁: $(basename "$PATCH")"
git -C "$TARGET" init -q
git -C "$TARGET" apply -p1 --whitespace=nowarn "$PATCH"
rm -rf "$TARGET/.git"

# ---- 5. 写集成清单（溯源）----
cat > "$MANIFEST" <<EOF
# edict 集成清单（由 scripts/sync-edict.sh 自动生成）

- 同步时间: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- 上游(只读): /up  (github.com/cft0808/edict)
- 上游基线: origin/main @ ${UP_COMMIT}
- 集成方式: git archive 导出 main + 应用 scripts/edict-integration.patch
- 已叠加的“提出的需求分支”（仅取其相对 main 的真实功能增量）:
    - copilot/add-ai-daily-digest            (HEAD a032eff)  # 技术博客 RSS 源 + 看板分类
    - copilot/fix-code-bugs-and-optimize-performance (HEAD 037bbbd)  # 回归测试
      （该分支对 kanban_update.py 的 save()->trigger_refresh() 重构，
       上游 origin/main 已包含，故此处仅补其新增测试）

## 说明
本目录是根目录下的独立组件 / 伴生服务，不直接并入 Go Hub 工程。
重跑 ./scripts/sync-edict.sh 可从只读上游重新生成本目录（含上述增量）。
EOF

echo "✓ 完成。组件位于: $TARGET"
echo "✓ 集成清单: $MANIFEST"
