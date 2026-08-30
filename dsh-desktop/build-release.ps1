# AiMarketing 标准构建脚本（永久方案）
# 用法: .\build-release.ps1
# 产物: D:\code\dsh\target\release\bundle\nsis\AiMarketing_1.0.0_x64-setup.exe
#
# 配置:
#   - 原生标题栏 (decorations: true) - 操作系统提供最小化/最大化/关闭按钮
#   - iframe 全屏嵌入 http://127.0.0.1:3080
#   - 后端: dsh --profile aimarketing --port 3080

$ErrorActionPreference = "Stop"

Write-Host "=== AiMarketing 标准构建流程 ===" -ForegroundColor Cyan

# 1. 清理旧进程
Write-Host "`n[1/5] 清理旧进程..." -ForegroundColor Yellow
Get-Process | Where-Object { $_.ProcessName -like '*node*' -or $_.ProcessName -like '*dsh-desktop*' } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2. 验证配置
Write-Host "`n[2/5] 验证 tauri.conf.json..." -ForegroundColor Yellow
$confPath = "src-tauri\tauri.conf.json"
$conf = Get-Content $confPath -Raw | ConvertFrom-Json

$checks = @(
    @{ Field = "productName"; Expected = "AiMarketing" },
    @{ Field = "version"; Expected = "1.0.0" },
    @{ Field = "app.windows[0].decorations"; Expected = $true },
    @{ Field = "app.windows[0].url"; Expected = "index.html" },
    @{ Field = "build.frontendDist"; Expected = "../web" }
)

foreach ($check in $checks) {
    $actual = $conf
    foreach ($part in $check.Field -split '\.') {
        if ($part -match '\[(\d+)\]') {
            $idx = [int]$Matches[1]
            $actual = $actual.$($part -replace '\[\d+\]', '')[$idx]
        } else {
            $actual = $actual.$part
        }
    }
    $status = if ($actual -eq $check.Expected) { "OK" } else { "FAIL (实际: $actual)" }
    $color = if ($actual -eq $check.Expected) { "Green" } else { "Red" }
    Write-Host "  $($check.Field) = $($check.Expected) [$status]" -ForegroundColor $color
}

# 3. 构建
Write-Host "`n[3/5] 开始构建 (需要 3-5 分钟)..." -ForegroundColor Yellow
Set-Location $PSScriptRoot
cargo tauri build 2>&1
if ($LASTEXITCODE -ne 0) { throw "构建失败" }

# 4. 验证产物
Write-Host "`n[4/5] 验证产物..." -ForegroundColor Yellow
$installer = "D:\code\dsh\target\release\bundle\nsis\AiMarketing_1.0.0_x64-setup.exe"
if (Test-Path $installer) {
    $size = [math]::Round((Get-Item $installer).Length / 1MB, 2)
    Write-Host "  安装包: $installer ($size MB)" -ForegroundColor Green
} else {
    throw "安装包未生成"
}

# 5. 测试启动
Write-Host "`n[5/5] 测试启动..." -ForegroundColor Yellow
$exe = "D:\code\dsh\target\release\dsh-desktop.exe"
$proc = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 10

# 检查后端
$port = netstat -ano | Select-String ":3080.*LISTENING"
if ($port) {
    Write-Host "  后端启动成功: 127.0.0.1:3080" -ForegroundColor Green
} else {
    Write-Host "  警告: 后端未启动" -ForegroundColor Red
}

# 检查应用
$app = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($app -and -not $app.HasExited) {
    Write-Host "  应用运行中 (原生标题栏)" -ForegroundColor Green
} else {
    Write-Host "  警告: 应用已退出" -ForegroundColor Red
}

Write-Host "`n=== 构建完成 ===" -ForegroundColor Cyan
Write-Host "安装包: $installer" -ForegroundColor White
Write-Host "运行: $exe" -ForegroundColor White
