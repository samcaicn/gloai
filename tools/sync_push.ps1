# -*- coding: utf-8 -*-
# WeAuto 双远端同步脚本：一次提交推送 工蜂(origin) + GitHub(github)。
# 用法（在仓库根目录，PowerShell）：
#   powershell -ExecutionPolicy Bypass -File tools\sync_push.ps1 [commit_message]
# 说明：
#   - 默认 commit message："chore: sync to github"；可传参覆盖。
#   - 只推送当前分支（weauto）。
#   - 若本机未配置任一远端 SSH 免密，会交互提示输入。
#   - GitHub 侧 Actions 已收紧为只读；本脚本只做 git push，不涉及 API 写权限。
param([string]$Msg = "chore: sync to github")
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

git status -s | Out-String | Write-Host
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
Write-Host ("=== 推送分支: " + $branch + " ===")

git add -A
git commit -m $Msg --no-verify

Write-Host "=== push origin (工蜂) ==="
git push origin $branch
Write-Host "=== push github (samcaicn/gloai) ==="
git push github $branch
Write-Host "=== 双远端同步完成 ==="
