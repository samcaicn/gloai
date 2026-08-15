# sign-certum.ps1 — Sign the NSIS installer with a Certum (or any) code-signing cert.
# Method A (signtool): cert imported into Windows cert store, or a PFX file.
# Method B (Certum cloud HSM): use Certum's official tool; this script only does A.
# Run from repo root, or pass -InstallerPath explicitly.

param(
    [string]$InstallerPath = "dist/SafeOPC-Setup.exe",
    [string]$PfxPath      = "",
    [string]$PfxPassword  = "",
    [string]$CertName     = "Open Source Developer",   # subject match in Windows store
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [string]$SigntoolPath = ""
)

$ErrorActionPreference = "Stop"

# ── Resolve signtool.exe ────────────────────────────────────────────────
if (-not $SigntoolPath) {
    $candidates = @(
        "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe",
        "C:\Program Files (x86)\Windows Kits\10\bin\x64\signtool.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { $SigntoolPath = $c; break } }
}
if (-not (Test-Path $SigntoolPath)) {
    Write-Error "signtool.exe not found. Install Windows SDK or pass -SigntoolPath."
    exit 1
}

# ── Resolve installer ────────────────────────────────────────────────────
$absInstaller = Resolve-Path $InstallerPath -ErrorAction SilentlyContinue
if (-not $absInstaller) {
    Write-Error "Installer not found: $InstallerPath"
    exit 1
}
$absInstaller = $absInstaller.Path
Write-Output "Signing: $absInstaller"

# ── Build sign args ──────────────────────────────────────────────────────
$signArgs = @("sign", "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256")
if ($PfxPath -and (Test-Path $PfxPath)) {
    $signArgs += "/f"; $signArgs += $PfxPath
    if ($PfxPassword) { $signArgs += "/p"; $signArgs += $PfxPassword }
} else {
    # certificate in Windows store (current user)
    $signArgs += "/s"; $signArgs += "My"; $signArgs += "/n"; $signArgs += $CertName
}
$signArgs += $absInstaller

# ── Sign ──────────────────────────────────────────────────────────────────
Write-Output "Running: signtool $($signArgs -join ' ')"
& $SigntoolPath @signArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Signing failed (exit $LASTEXITCODE)."
    exit $LASTEXITCODE
}

# ── Verify ───────────────────────────────────────────────────────────────
Write-Output "Verifying..."
& $SigntoolPath verify /pa $absInstaller
if ($LASTEXITCODE -ne 0) {
    Write-Error "Verification failed."
    exit $LASTEXITCODE
}
Write-Output "OK: $absInstaller signed and verified."
