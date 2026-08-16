param(
    [switch]$Nsis,         # 只构建 NSIS 安装包（默认 tauri build 会产出 nsis+dmg+app，跳过非 Windows 目标可省时）
    [switch]$Full,         # 完整优化构建：release-ci profile + strip symbols（~10 min，正式发布/CI 专用，不常用）
    [switch]$CI,           # 使用 CI profile（codegen-units=1 最大优化，构建慢 ~3x，正式发布/CI 专用，不常用）
    [switch]$UltraFast,    # 极致极速：release-fast + 跳过前端 + 跳过清理（Rust-only 改动最快出包）
    [switch]$Clean,        # 全清：target/release/{deps,.fingerprint,build,incremental} + target/debug（强制全量重编译）
    [switch]$Aggressive,   # 激进清理：额外清 target/release-fast + target/debug（10GB+ 释放，但下次 dev:tauri 需重编 debug）
    [switch]$Check,        # 仅 cargo check + pnpm build（不入包，~30s 完成自检）
    [switch]$SkipBuild,    # 只清理不构建
    [switch]$SkipPostClean,# 构建后跳过缓存清理（保留全部中间产物以便调试）
    [switch]$NoFrontend,   # 跳过前端重建（Rust-only 改动时，要求 dist/ 已存在）
    [switch]$Force,        # 绕过运行中进程的安全检查（谨慎使用）
    [string]$Brand = "tupai"  # 品牌名: tupai / safeopc (决定 --config 覆盖与产物命名)
)

# =============================================================================
# build.ps1 — tupai 本地 NSIS 构建/清理规范脚本 (支持 -Brand tupai/safeopc 多品牌)
# =============================================================================
# 标准用法（推荐）:
#   .\build.ps1 -Nsis                 # 标准构建 NSIS（release-nsis，~1-2 min，strip symbols，日常开发默认）
#   .\build.ps1 -Nsis -UltraFast      # 极致极速：release-fast + 跳过前端 + 跳过清理（Rust-only 最快）
#   .\build.ps1 -Nsis -NoFrontend     # Rust-only 改动：跳过前端重建（需 dist/ 已存在）
#   .\build.ps1 -Nsis -SkipPostClean  # 仅构建，不清理（调试用）
#   .\build.ps1 -Check                # 快速自检（cargo check + pnpm build）
#   .\build.ps1 -Clean -SkipBuild     # 仅做全量清理（强制下次全量重编译）
#   .\build.ps1 -Aggressive -SkipBuild # 仅做激进清理（释放 ~15GB）
#
# AIMarketing 品牌（Windows NSIS，使用 tauri.safeopc.conf.json 覆盖图标/名称/标识）:
#   .\build.ps1 -Nsis -Brand safeopc            # 标准构建 AIMarketing NSIS
#   .\build.ps1 -Nsis -Full -Brand safeopc      # 完整优化构建 AIMarketing（正式发布）
#   pnpm build:nsis:safeopc                     # 等价 npm 脚本
#   pnpm build:mac:safeopc                      # macOS app+dmg 构建 AIMarketing（macOS 上运行）
#
# 不常用参数（正式发布/CI）:
#   .\build.ps1 -Nsis -Full           # 完整优化构建（release-ci + 最大优化，~10 min，正式发布用）
#   .\build.ps1 -Nsis -CI             # CI 模式：release-ci + 最大优化（正式发布用，~10 min）
#   .\build.ps1 -Nsis -Aggressive     # 同上 + 释放 target/release-fast / release-nsis / debug
#
# 构建产物:
#   $Target\release\bundle\nsis\*-setup.exe  （NSIS 安装包）
#
# 缓存策略（清理 = 释放空间 / 保留 = 下次加速）:
#   清理（再生）       : dist/、node_modules/.vite、src-tauri/gen、
#                         target/release/{incremental, build/*/output, bundle, nsis 工作目录}、
#                         target/release 根的二进制（tupai.exe/.pdb/.d/app_lib.*）、
#                         *.log（项目根）
#   清理（可选激进）   : target/release-fast（4.7GB sanity-check 产物）、
#                         target/debug（10GB dev 产物）
#   保留（加速）       : target/release/{deps, .fingerprint}（cargo 增量编译缓存）、
#                         ~/.cargo/registry（cargo crate 源码缓存）、
#                         C:\pnpm-store（pnpm 全局包缓存）、
#                         %LOCALAPPDATA%\tauri（WebView2 引导程序等 Tauri 资源缓存）
#
# 构建 Profile 选择:
#   默认 (release-nsis): lto=off, codegen-units=16, strip=symbols — ~1-2 min，标准构建（日常开发默认）
#   -UltraFast (release-fast): lto=off, codegen-units=16 — 最快链接，~30s（Rust-only 最快）
#   -Full/-CI (release-ci) : lto=thin, codegen-units=1 — 最大优化，最慢（正式发布/CI 专用）
#
# 常见问题:
#   cargo: failed to open .pdb       → tupai.exe 运行中，先关 dev 进程再清理
#   退出码 101 无错误输出            → deps/ 缓存损坏，用 -Clean -SkipBuild 全清后重试
#   链接 >10 分钟                    → 正常（lto=thin + codegen-units=1）；保留 deps/ 可跳过依赖编译
#   验证改动不破坏 release           → pnpm build:release-fast（30s 链接，不入包）
#   磁盘不足                         → .\build.ps1 -Aggressive -SkipBuild 释放 ~15GB
# =============================================================================
# 标准用法（推荐）:
#   .\build.ps1 -Nsis                 # 标准构建 NSIS（release-nsis，~1-2 min，strip symbols，日常开发默认）
#   .\build.ps1 -Nsis -Full           # 完整优化构建（release-ci + 最大优化，~10 min，正式发布用）
#   .\build.ps1 -Nsis -UltraFast      # 极致极速：release-fast + 跳过前端 + 跳过清理（Rust-only 最快）
#   .\build.ps1 -Nsis -CI             # CI 模式：release-ci + 最大优化（正式发布用，~10 min）
#   .\build.ps1 -Nsis -NoFrontend     # Rust-only 改动：跳过前端重建（需 dist/ 已存在）
#   .\build.ps1 -Nsis -Aggressive     # 同上 + 释放 target/release-fast / release-nsis / debug
#   .\build.ps1 -Nsis -SkipPostClean  # 仅构建，不清理（调试用）
#   .\build.ps1 -Check                # 快速自检（cargo check + pnpm build）
#   .\build.ps1 -Clean -SkipBuild     # 仅做全量清理（强制下次全量重编译）
#   .\build.ps1 -Aggressive -SkipBuild # 仅做激进清理（释放 ~15GB）
#
# 构建产物:
#   $Target\release\bundle\nsis\*-setup.exe  （NSIS 安装包）
#
# 缓存策略（清理 = 释放空间 / 保留 = 下次加速）:
#   清理（再生）       : dist/、node_modules/.vite、src-tauri/gen、
#                         target/release/{incremental, build/*/output, bundle, nsis 工作目录}、
#                         target/release 根的二进制（tupai.exe/.pdb/.d/app_lib.*）,
#                         *.log（项目根）
#   清理（可选激进）   : target/release-fast（4.7GB sanity-check 产物）,
#                         target/debug（10GB dev 产物）
#   保留（加速）       : target/release/{deps, .fingerprint}（cargo 增量编译缓存）,
#                         ~/.cargo/registry（cargo crate 源码缓存）,
#                         C:\pnpm-store（pnpm 全局包缓存）,
#                         %LOCALAPPDATA%\tauri（WebView2 引导程序等 Tauri 资源缓存）
#
# 构建 Profile 选择:
#   默认 (release-nsis): lto=off, codegen-units=16, strip=symbols — ~1-2 min，标准构建（日常开发默认）
#   -UltraFast (release-fast): lto=off, codegen-units=16 — 最快链接，~30s（Rust-only 最快）
#   -Full/-CI (release-ci) : lto=thin, codegen-units=1 — 最大优化，最慢（正式发布用）
#
# 常见问题:
#   cargo: failed to open .pdb       → tupai.exe 运行中，先关 dev 进程再清理
#   退出码 101 无错误输出            → deps/ 缓存损坏，用 -Clean -SkipBuild 全清后重试
#   链接 >10 分钟                    → 正常（lto=thin + codegen-units=1）；保留 deps/ 可跳过依赖编译
#   验证改动不破坏 release           → pnpm build:release-fast（30s 链接，不入包）
#   磁盘不足                         → .\build.ps1 -Aggressive -SkipBuild 释放 ~15GB
# =============================================================================

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

# -UltraFast: 组合 flag = -NoFrontend + -SkipPostClean
if ($UltraFast.IsPresent) {
    $NoFrontend = $true
    $SkipPostClean = $true
    Write-Host "[ultrafast] 组合模式: release-fast + 跳过前端 + 跳过清理" -ForegroundColor Magenta
}

# -----------------------------------------------------------------------------
# 解析实际 cargo target 目录（优先级：CARGO_TARGET_DIR > .cargo/config.toml > cargo metadata）
# -----------------------------------------------------------------------------
try {
    $cargoMeta = cargo metadata --manifest-path "$Root\src-tauri\Cargo.toml" --no-deps --format-version 1 2>$null | ConvertFrom-Json
    $Target = $cargoMeta.target_directory
} catch {
    $Target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { "$Root\src-tauri\target" }
    Write-Host "[warn] cargo metadata failed, falling back to: $Target" -ForegroundColor Yellow
}
Write-Host "[info] target dir: $Target" -ForegroundColor DarkGray

function Remove-IfExists($Path) {
    if (Test-Path $Path) {
        Remove-Item $Path -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "[clean] $Path" -ForegroundColor DarkGray
    }
}

function Get-DirSizeMB($Path) {
    if (-not (Test-Path $Path)) { return 0 }
    $bytes = (Get-ChildItem $Path -Recurse -File -ErrorAction SilentlyContinue |
              Measure-Object -Property Length -Sum).Sum
    if (-not $bytes) { return 0 }
    return [math]::Round($bytes / 1MB, 1)
}

function Format-Size($MB) {
    if ($MB -ge 1024) { return "{0:N2}GB" -f ($MB / 1024) }
    return "${MB}MB"
}

# -----------------------------------------------------------------------------
# 安全检查：检测 dev 进程（按品牌名）是否在运行，避免 EBUSY 损坏增量缓存
# -----------------------------------------------------------------------------
function Test-DevProcessRunning {
    param([string]$Brand = "tupai")
    $procs = Get-Process -Name $Brand -ErrorAction SilentlyContinue
    if ($procs) {
        Write-Host "[safety] 检测到 $Brand.exe 运行中 (PID: $($procs.Id -join ', '))。" -ForegroundColor Yellow
        Write-Host "[safety] 此时清理 target/ 会触发 EBUSY 并损坏增量缓存。" -ForegroundColor Yellow
        Write-Host "[safety] 请先停止 dev 进程，或使用 -Force 强制覆盖（有损坏风险）。" -ForegroundColor Yellow
        return $true
    }
    return $false
}

# -----------------------------------------------------------------------------
# 确定清理模式
# -----------------------------------------------------------------------------
$fullClean = $Clean.IsPresent
if (($fullClean -or $Aggressive.IsPresent) -and -not $Force.IsPresent) {
    if (Test-DevProcessRunning -Brand $Brand) { exit 1 }
}

Write-Host "`n=== 预构建清理 ===" -ForegroundColor Cyan

# dist/ 清理：-NoFrontend 时保留（需复用已有产物）
if (-not $NoFrontend.IsPresent) {
    Remove-IfExists "$Root\dist"
}
Remove-IfExists "$Target\release\bundle"
Remove-IfExists "$Target\release\nsis"
Remove-IfExists "$Target\release-nsis\bundle"
Remove-IfExists "$Target\release-nsis\nsis"

if ($fullClean) {
    Write-Host "`n=== 全量清理（target/release 增量缓存）===" -ForegroundColor Yellow
    Remove-IfExists "$Target\release\.fingerprint"
    Remove-IfExists "$Target\release\deps"
    Remove-IfExists "$Target\release\build"
    Remove-IfExists "$Target\release\incremental"
    Remove-IfExists "$Target\debug"
}

if ($SkipBuild) {
    if ($Aggressive.IsPresent) {
        Write-Host "`n=== 激进清理（额外释放 release-fast / debug）===" -ForegroundColor Yellow
        Remove-IfExists "$Target\release-fast"
        Remove-IfExists "$Target\debug"
        Remove-IfExists "$Root\node_modules\.vite"
        Remove-IfExists "$Root\src-tauri\gen"
    }
    Write-Host "`nDone (skip-build)." -ForegroundColor Green
    $releaseMB = Get-DirSizeMB "$Target\release"
    $debugMB = Get-DirSizeMB "$Target\debug"
    $rfMB = Get-DirSizeMB "$Target\release-fast"
    $rnsMB = Get-DirSizeMB "$Target\release-nsis"
    Write-Host "target = $(Format-Size ($releaseMB + $debugMB + $rfMB + $rnsMB)) (release=$(Format-Size $releaseMB), release-nsis=$(Format-Size $rnsMB), debug=$(Format-Size $debugMB), release-fast=$(Format-Size $rfMB))" -ForegroundColor DarkGray
    exit 0
}

# -----------------------------------------------------------------------------
# -Check 模式：cargo check + pnpm build 快速自检
# -----------------------------------------------------------------------------
if ($Check.IsPresent) {
    Write-Host "`n=== cargo check ===" -ForegroundColor Cyan
    cargo check --manifest-path "$Root\src-tauri\Cargo.toml" --all-targets
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[check] cargo check failed (exit $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    Write-Host "`n=== pnpm build (frontend) ===" -ForegroundColor Cyan
    pnpm build
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[check] pnpm build failed (exit $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    Write-Host "`n=== Check passed ===" -ForegroundColor Green
    exit 0
}

# -----------------------------------------------------------------------------
# 构建：tauri build（默认全 bundle；-Nsis 仅 nsis 跳过非 Windows 目标）
# 注意：tauri/cargo 通过 stderr 输出 Info 日志，PowerShell 的 Stop 策略会
# 把其当作 NativeCommandError 终止脚本，故构建期间临时切到 Continue，
# 仅通过 $LASTEXITCODE 判断真实失败。
# -----------------------------------------------------------------------------
$buildStart = Get-Date
Write-Host "`n=== 构建（tauri build）===" -ForegroundColor Cyan

# 品牌配置覆盖: src-tauri/tauri.<brand>.conf.json
$brandConf = "$Root\src-tauri\tauri.$Brand.conf.json"
$configArg = @()
if (Test-Path $brandConf) {
    $configArg = @("--config", $brandConf)
    Write-Host "[build] 品牌: $Brand (覆盖配置: tauri.$Brand.conf.json)" -ForegroundColor DarkGray
} else {
    Write-Host "[build] 品牌: $Brand (无品牌覆盖配置, 用基础 tauri.conf.json)" -ForegroundColor DarkGray
}

# -NoFrontend: 跳过前端重建（Rust-only 改动时使用）
if ($NoFrontend.IsPresent) {
    $distDir = "$Root\dist"
    if (-not (Test-Path $distDir) -or -not (Get-ChildItem $distDir -ErrorAction SilentlyContinue)) {
        Write-Host "[error] -NoFrontend 要求 dist/ 目录已存在且非空。请先运行一次完整构建。" -ForegroundColor Red
        exit 1
    }
    # NoFrontend 覆盖：写入临时 JSON 文件避免 PowerShell 转义问题
    $noFeFile = Join-Path $env:TEMP "tauri-nofe-override-$PID.json"
    '{"build":{"beforeBuildCommand":""}}' | Out-File -Encoding utf8 -NoNewline $noFeFile
    $configArg = @($configArg) + @("--config", $noFeFile)
    Write-Host "[build] -NoFrontend: 跳过前端重建（复用 dist/）" -ForegroundColor Yellow
}

$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"

# Build profile selection
# 默认 (release-nsis): lto=off + codegen-units=16 + strip=symbols → ~1-2 min，标准构建（日常开发默认）
# -UltraFast (release-fast): lto=off + codegen-units=16 → 最快链接 (~30s，Rust-only 最快)
# -Full/-CI (release-ci): lto=thin + codegen-units=1 → 最大优化，最慢 (正式发布/CI 专用，不常用)
$profileArg = @()
if ($CI.IsPresent -or $Full.IsPresent) {
    $profileArg = @("--", "--profile", "release-ci")
    Write-Host "[build] Profile: release-ci (codegen-units=1, maximum optimization)" -ForegroundColor Yellow
} elseif ($UltraFast.IsPresent) {
    $profileArg = @("--", "--profile", "release-fast")
    Write-Host "[build] Profile: release-fast (lto=off, codegen-units=16, fastest link ~30s, no strip)" -ForegroundColor DarkGray
} else {
    $profileArg = @("--", "--profile", "release-nsis")
    Write-Host "[build] Profile: release-nsis (lto=off, codegen-units=16, strip=symbols, ~1-2 min)" -ForegroundColor DarkGray
}

if ($Nsis.IsPresent) {
    Write-Host "[build] 仅构建 NSIS bundle" -ForegroundColor DarkGray
    pnpm tauri build --bundles nsis @configArg @profileArg
} else {
    Write-Host "[build] 构建全部配置 bundle（nsis+dmg+app）" -ForegroundColor DarkGray
    pnpm build:tauri @configArg @profileArg
}
$buildExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
$buildDuration = (Get-Date) - $buildStart
Write-Host "[build] 耗时: $([math]::Round($buildDuration.TotalMinutes, 1)) 分钟" -ForegroundColor DarkGray

if ($buildExit -ne 0) {
    Write-Host "[build] tauri build 失败 (exit $buildExit)" -ForegroundColor Red
    exit $buildExit
}

# -----------------------------------------------------------------------------
# 定位 NSIS 产物（按 profile 输出目录搜索）
# -----------------------------------------------------------------------------
$nsisCandidates = @(
    "$Target\release-nsis\bundle\nsis",
    "$Target\release-fast\bundle\nsis",
    "$Target\release\bundle\nsis"
)
$nsisDir = $nsisCandidates[2]
$setupExe = $null
foreach ($candidate in $nsisCandidates) {
    if (Test-Path $candidate) {
        $setupExe = Get-ChildItem $candidate -Filter "*-setup.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($setupExe) { $nsisDir = $candidate; break }
    }
}
if ($setupExe) {
    $sizeMB = [math]::Round($setupExe.Length / 1MB, 1)
    Write-Host "`n[product] NSIS 安装包: $($setupExe.FullName) ($sizeMB MB)" -ForegroundColor Green
} else {
    Write-Host "[warn] 未找到 *-setup.exe，请检查 $nsisDir" -ForegroundColor Yellow
}

if ($SkipPostClean.IsPresent) {
    Write-Host "`n[skip] 跳过构建后缓存清理（-SkipPostClean）" -ForegroundColor DarkGray
    exit 0
}

# =============================================================================
# 构建后缓存清理（规范核心）
# 目标：立即清理再生垃圾与中间产物，但保留加速文件以让下次构建更快。
# =============================================================================
Write-Host "`n=== 构建后缓存清理 ===" -ForegroundColor Cyan

# --- 1. 立即清理构建日志（构建完成后立即清理，避免占用空间）---
Remove-IfExists "$Root\nsis-build.log"
Remove-IfExists "$Root\check_err.txt"
Remove-IfExists "$Root\check_out.txt"
Remove-IfExists "$Root\test_output.txt"
Remove-IfExists "$Root\nsis-build-err.log"
Write-Host "[clean] 临时日志文件" -ForegroundColor DarkGray

# --- 2. 立即清理前端缓存（构建后立即清理，释放空间）---
Remove-IfExists "$Root\node_modules\.vite"
Remove-IfExists "$Root\node_modules\.cache"
# Tauri 代码生成（每次 tauri build 重新生成）
Remove-IfExists "$Root\src-tauri\gen"
Write-Host "[clean] node_modules/.vite, node_modules/.cache, src-tauri/gen" -ForegroundColor DarkGray

# --- 3. cargo target/release 中间产物（构建后立即清理）---
# build script 输出（build.rs 生成的头文件等，每次 cargo 调用都会重新生成）
$buildDir = "$Target\release\build"
if (Test-Path $buildDir) {
    Get-ChildItem $buildDir -Directory | ForEach-Object {
        $outputDir = Join-Path $_.FullName "output"
        if (Test-Path $outputDir) { Remove-Item $outputDir -Recurse -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "[clean] target/release/build/*/output" -ForegroundColor DarkGray
}

# incremental 缓存在成功 release 后无用（下次 release 必由代码变更触发，会失效）
Remove-IfExists "$Target\release\incremental"
Remove-IfExists "$Target\release-nsis\incremental"
# NSIS 工作目录（保留最终 .exe 已离开此目录）
Remove-IfExists "$Target\release\nsis"

# 根 release 二进制：最终链接输出，不属于增量缓存（deps/ 才是）。删除后下次仅 relink ~2s
$rootBinaries = @(
    "tupai.exe", "tupai.pdb", "tupai.d",
    "app_lib.dll", "app_lib.lib", "app_lib.d", "app_lib.pdb",
    "libapp_lib.rlib", "libapp_lib.d"
)
foreach ($b in $rootBinaries) {
    Remove-IfExists "$Target\release\$b"
}
Write-Host "[clean] target/release 根二进制" -ForegroundColor DarkGray

# --- 4. release-nsis 中间产物（NSIS setup.exe 已产出，工作目录与根二进制可删）---
if ($nsisDir -and (Test-Path $nsisDir)) {
    $rnsInc = "$Target\release-nsis\incremental"
    $rnsNsisWork = "$Target\release-nsis\nsis"
    Remove-IfExists $rnsInc
    Remove-IfExists $rnsNsisWork
    Write-Host "[clean] target/release-nsis/{incremental,nsis}" -ForegroundColor DarkGray
}
# release-nsis 根二进制：NSIS 包已打包完成，这些文件不再需要（~数百 MB）
$rnsRootBinaries = @("tupai.exe", "tupai.pdb", "tupai.d")
foreach ($b in $rnsRootBinaries) {
    Remove-IfExists "$Target\release-nsis\$b"
}
Write-Host "[clean] target/release-nsis 根二进制" -ForegroundColor DarkGray

# --- 5. Aggressive 模式：额外清理 release-fast / release-nsis / debug ---
if ($Aggressive.IsPresent) {
    Write-Host "`n--- 激进清理 ---" -ForegroundColor Yellow
    $rfBefore = Get-DirSizeMB "$Target\release-fast"
    $rnsBefore = Get-DirSizeMB "$Target\release-nsis"
    $dbgBefore = Get-DirSizeMB "$Target\debug"
    Remove-IfExists "$Target\release-fast"
    Remove-IfExists "$Target\release-nsis"
    Remove-IfExists "$Target\debug"
    Write-Host "[clean] target/release-fast (~$(Format-Size $rfBefore))" -ForegroundColor DarkGray
    Write-Host "[clean] target/release-nsis (~$(Format-Size $rnsBefore))" -ForegroundColor DarkGray
    Write-Host "[clean] target/debug (~$(Format-Size $dbgBefore))" -ForegroundColor DarkGray
}

# --- 6. 加速文件清单（说明保留项）---
Write-Host "`n--- 保留加速缓存 ---" -ForegroundColor DarkGreen
$depsMB = Get-DirSizeMB "$Target\release\deps"
$fpMB = Get-DirSizeMB "$Target\release\.fingerprint"
$cargoRegSrcMB = Get-DirSizeMB "$env:USERPROFILE\.cargo\registry\src"
$cargoRegCacheMB = Get-DirSizeMB "$env:USERPROFILE\.cargo\registry\cache"
$pnpmStoreMB = 0
$pnpmStorePath = $null
try {
    $pnpmStorePath = (pnpm store path 2>$null).Trim()
    if ($pnpmStorePath -and (Test-Path $pnpmStorePath)) {
        $pnpmStoreMB = Get-DirSizeMB $pnpmStorePath
    }
} catch {}
$tauriCacheMB = Get-DirSizeMB "$env:LOCALAPPDATA\tauri"
Write-Host "[keep] target/release/deps        = $(Format-Size $depsMB)（cargo 增量编译 .rlib/.rmeta）" -ForegroundColor DarkGreen
Write-Host "[keep] target/release/.fingerprint = $(Format-Size $fpMB)（cargo 编译指纹）" -ForegroundColor DarkGreen
Write-Host "[keep] ~/.cargo/registry/src       = $(Format-Size $cargoRegSrcMB)（crate 源码）" -ForegroundColor DarkGreen
Write-Host "[keep] ~/.cargo/registry/cache     = $(Format-Size $cargoRegCacheMB)（crate .crate 缓存）" -ForegroundColor DarkGreen
if ($pnpmStorePath) {
    Write-Host "[keep] pnpm store ($pnpmStorePath) = $(Format-Size $pnpmStoreMB)（pnpm 全局包缓存）" -ForegroundColor DarkGreen
}
Write-Host "[keep] %LOCALAPPDATA%\tauri        = $(Format-Size $tauriCacheMB)（WebView2 引导等 Tauri 资源）" -ForegroundColor DarkGreen

# =============================================================================
# 汇总
# =============================================================================
$releaseMB = Get-DirSizeMB "$Target\release"
$debugMB = Get-DirSizeMB "$Target\debug"
$rfMB = Get-DirSizeMB "$Target\release-fast"
$rnsMB = Get-DirSizeMB "$Target\release-nsis"
$totalMB = $releaseMB + $debugMB + $rfMB + $rnsMB
Write-Host "`n=== 完成 ===" -ForegroundColor Green
Write-Host "target = $(Format-Size $totalMB) (release=$(Format-Size $releaseMB), release-nsis=$(Format-Size $rnsMB), debug=$(Format-Size $debugMB), release-fast=$(Format-Size $rfMB))" -ForegroundColor DarkGray
if ($setupExe) {
    Write-Host "产物  : $($setupExe.FullName)" -ForegroundColor Green
}
