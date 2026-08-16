#!/usr/bin/env bash
# ============================================================================
# Cua Driver 上游升级脚本
# ============================================================================
#
# 用途：一键从 https://github.com/trycua/cua 拉取最新代码到 up/cua 目录，
#       并验证 cua-driver 二进制能否正常构建。
#
# 用法：
#   ./scripts/upgrade-cua-upstream.sh          # 拉取最新 main 分支
#   ./scripts/upgrade-cua-upstream.sh v0.12.6  # 拉取指定 tag/commit
#   ./scripts/upgrade-cua-upstream.sh main --build  # 拉取并构建
#
# 此脚本会：
#   1. 检查 up/cua 是否存在（不存在则 clone，存在则 fetch + reset）
#   2. 切换到指定 ref（默认 main）
#   3. 显示版本信息
#   4. 可选：构建 cua-driver 二进制（--build 标志）
#   5. 显示集成代码中可能需要更新的 API 变更
#
# 注意：up/ 目录已在 .gitignore 中排除，不会提交到仓库。
# ============================================================================

set -euo pipefail

# ── 颜色输出 ──────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }
step()  { echo -e "${BLUE}[STEP]${NC}  $*"; }

# ── 变量 ──────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CUA_DIR="$PROJECT_ROOT/up/cua"
CUA_REPO="https://github.com/trycua/cua.git"
REF="${1:-main}"
DO_BUILD=false

# 检查 --build 标志
for arg in "$@"; do
    if [ "$arg" = "--build" ]; then
        DO_BUILD=true
    fi
done

# 重新解析第一个非 --build 参数作为 ref
for arg in "$@"; do
    if [ "$arg" != "--build" ]; then
        REF="$arg"
        break
    fi
done

echo ""
echo "=============================================="
echo "  Cua Driver Upstream Upgrade"
echo "=============================================="
echo "  Project Root: $PROJECT_ROOT"
echo "  Cua Directory: $CUA_DIR"
echo "  Ref: $REF"
echo "  Build: $DO_BUILD"
echo "=============================================="
echo ""

# ── Step 1: Clone or fetch ────────────────────────────────────
step "1/5: Clone or fetch upstream repository"

if [ -d "$CUA_DIR/.git" ]; then
    info "up/cua exists — fetching latest..."
    cd "$CUA_DIR"

    # 确保远程配置正确
    if ! git remote get-url origin 2>/dev/null | grep -q "trycua/cua"; then
        warn "Remote origin is not trycua/cua, updating..."
        git remote set-url origin "$CUA_REPO"
    fi

    git fetch origin --tags --force
else
    info "up/cua does not exist — cloning..."
    mkdir -p "$PROJECT_ROOT/up"
    git clone "$CUA_REPO" "$CUA_DIR"
    cd "$CUA_DIR"
fi

# ── Step 2: Checkout ref ──────────────────────────────────────
step "2/5: Checkout ref: $REF"

# 尝试 checkout 指定的 ref
if git rev-parse --verify "$REF" >/dev/null 2>&1; then
    git checkout "$REF"
elif git rev-parse --verify "origin/$REF" >/dev/null 2>&1; then
    git checkout "origin/$REF"
else
    error "Ref '$REF' not found in repository"
    error "Available tags:"
    git tag -l | head -20
    exit 1
fi

# 如果是分支，pull 最新
if git symbolic-ref -q HEAD >/dev/null 2>&1; then
    git pull origin "$REF" --ff-only || warn "Pull failed (may be detached HEAD)"
fi

info "Checked out: $(git rev-parse --short HEAD)"

# ── Step 3: Display version info ──────────────────────────────
step "3/5: Display version information"

CUA_VERSION=$(grep '^version' "$CUA_DIR/libs/cua-driver/rust/Cargo.toml" 2>/dev/null | head -1 | sed 's/.*= *"\(.*\)"/\1/' || echo "unknown")
info "Cua Driver version: $CUA_VERSION"
info "Commit: $(git log -1 --format='%H %s')"
info "Date: $(git log -1 --format='%ci')"

# ── Step 4: Check for API changes ─────────────────────────────
step "4/5: Check for integration-relevant changes"

ABI_HEADER="$CUA_DIR/libs/cua-driver/rust/include/cua_driver_abi.h"
ABI_MAJOR=$(grep 'CUA_DRIVER_ABI_MAJOR' "$ABI_HEADER" 2>/dev/null | grep -o '[0-9]*' || echo "?")
ABI_MINOR=$(grep 'CUA_DRIVER_ABI_MINOR' "$ABI_HEADER" 2>/dev/null | grep -o '[0-9]*' || echo "?")

info "C ABI version: $ABI_MAJOR.$ABI_MINOR"

# 检查工具列表是否有变化
TOOLS_FILE="$CUA_DIR/libs/cua-driver/rust/crates/cua-driver-core/src/tool.rs"
if [ -f "$TOOLS_FILE" ]; then
    TOOLS_COUNT=$(grep -c '^\s*"' "$TOOLS_FILE" 2>/dev/null || echo "?")
    info "Registered tools (approximate): $TOOLS_COUNT"
fi

# 检查我们的集成代码是否引用了可能已变更的 API
INTEGRATION_DIR="$PROJECT_ROOT/src-tauri/src/pc_automation/cua_driver"
if [ -d "$INTEGRATION_DIR" ]; then
    info "Integration code: $INTEGRATION_DIR"

    # 检查我们使用的工具名是否仍存在于上游
    warn "Verifying tool names used in integration code..."
    for tool in click double_click right_click type_text press_key hotkey scroll move_cursor get_screen_size get_accessibility_tree get_window_state; do
        if grep -q "\"$tool\"" "$TOOLS_FILE" 2>/dev/null; then
            echo -e "  ${GREEN}✓${NC} $tool"
        else
            echo -e "  ${RED}✗${NC} $tool — NOT FOUND in upstream (may have been renamed/removed)"
        fi
    done
fi

# ── Step 5: Optional build ────────────────────────────────────
if [ "$DO_BUILD" = true ]; then
    step "5/5: Build cua-driver binary"

    cd "$CUA_DIR/libs/cua-driver/rust"

    info "Building cua-driver (release)..."
    if cargo build --release -p cua-driver 2>&1; then
        BINARY_PATH="target/release/cua-driver"
        if [ -f "$BINARY_PATH" ]; then
            info "Build successful!"
            info "Binary: $(pwd)/$BINARY_PATH"
            info "Size: $(du -h "$BINARY_PATH" | cut -f1)"

            # 复制到项目的 sidecar 目录（如果存在）
            SIDECAR_DIR="$PROJECT_ROOT/src-tauri/binaries"
            if [ -d "$SIDECAR_DIR" ]; then
                cp "$BINARY_PATH" "$SIDECAR_DIR/"
                info "Copied to $SIDECAR_DIR/"
            fi
        else
            warn "Build reported success but binary not found at expected path"
        fi
    else
        error "Build failed!"
        exit 1
    fi
else
    step "5/5: Build skipped (use --build flag to build)"
fi

# ── 完成 ──────────────────────────────────────────────────────
echo ""
echo "=============================================="
echo "  Upgrade Complete!"
echo "=============================================="
echo "  Version: $CUA_VERSION"
echo "  Commit:  $(git rev-parse --short HEAD)"
echo "  ABI:     $ABI_MAJOR.$ABI_MINOR"
echo ""
if [ "$DO_BUILD" = false ]; then
    echo "  To build the binary, run:"
    echo "    $0 $REF --build"
    echo ""
fi
echo "  Next steps:"
echo "    1. Review any API changes above"
echo "    2. Run 'cargo check' in src-tauri/ to verify integration"
echo "    3. Run 'npx tsc --noEmit' in web-ui to verify frontend"
echo "=============================================="
echo ""
