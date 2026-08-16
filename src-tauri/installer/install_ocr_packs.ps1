# Copyright (c) 2026 AIMarketing
#
# install_ocr_packs.ps1 — silently installs the Windows OCR
# language pack(s) that the Windows.Media.Ocr runtime needs.
# Tauri invokes this from `installer/installer.nsh`
# `customInstall` macro right after the binaries are
# deployed. The script is best-effort: if a particular
# language pack is unavailable for the host's edition /
# SKU, we just log and move on. The OCR tier is designed
# to fall through to the VLM rescue path in
# `pc_automation/vlm_rescue` if no pack is present, so a
# failure here is non-fatal for the app's automation
# pipeline.
#
# Languages installed:
#   * the host's system locale (most users get their
#     preferred language)
#   * en-US (universal fallback)
#   * zh-CN (the app's primary UI locale)
#
# All three cover the >95% case for the v5 trading /
# browser automation workloads. The pack itself is a
# 30-50 MB on-demand feature that ships with the Windows
# ISO but is *not* enabled by default.
#
# Exit code: 0 always (NSIS treats non-zero as fatal
# install failure, which would be wrong here — see the
# `try/catch` in the .nsh hook for the actual logic).

$ErrorActionPreference = 'SilentlyContinue'

# Resolve the locales we want OCR for. The system
# locale is the one the user picked in Windows
# Settings; en-US and zh-CN are hardcoded because
# those are the two languages the v5 UI ships in
# (the app falls back to en-US when the user's
# display language is unsupported).
$sys = (Get-WinSystemLocale).Name
$locales = @($sys, 'en-US', 'zh-CN') |
    Where-Object { $_ } |
    Sort-Object -Unique

foreach ($loc in $locales) {
    $cap = "Language.OCR~~~$loc~0.0.1.0"
    try {
        $state = (Get-WindowsCapability -Online -Name $cap -ErrorAction SilentlyContinue).State
        if ($state -ne 'Installed') {
            # `Add-WindowsCapability` pulls the pack from
            # Windows Update by default. On a metered
            # link or locked-down SKU this can fail; we
            # swallow the error so the installer doesn't
            # surface a scary red box for a missing OCR
            # pack.
            Add-WindowsCapability -Online -Name $cap -ErrorAction SilentlyContinue | Out-Null
        }
    } catch {
        # Defensive: `Add-WindowsCapability` itself
        # can throw on locked-down SKUs (e.g. Windows
        # 10 S). Stay silent; the VLM rescue path will
        # pick up the slack.
    }
}

# Always succeed. See header comment for why.
exit 0
