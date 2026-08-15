# Build the SafeOPC desktop client (Windows) with PyInstaller.
#
# Prereqs (safeopc venv):
#   pip install pyinstaller pywebview
#   npm install + npm run build in opc/plugins/office_ui/frontend_src
#     (frontend_dist must already exist)
#
# Usage (from repo root C:\code\openopc):
#   powershell -ExecutionPolicy Bypass -File packaging/build.ps1
#
# Output: dist/SafeOPC/SafeOPC.exe

$ErrorActionPreference = "Stop"

$RepoRoot  = Resolve-Path (Join-Path $PSScriptRoot "..")
$SpecFile  = Join-Path $PSScriptRoot "safeopc.spec"
$DistDir   = Join-Path $RepoRoot "dist"
$ExePath   = Join-Path $DistDir "SafeOPC\SafeOPC.exe"

Write-Host "==> Repo root : $RepoRoot"
Write-Host "==> Spec      : $SpecFile"

# Sanity: frontend must be built.
$FrontendDist = Join-Path $RepoRoot "opc\plugins\office_ui\frontend_dist"
if (-not (Test-Path (Join-Path $FrontendDist "index.html"))) {
    Write-Error "frontend_dist/index.html missing. Build the frontend first:" +
                " cd opc/plugins/office_ui/frontend_src; npm install; npm run build"
    exit 1
}

# Pick the python that owns pyinstaller (prefer the safeopc venv).
$Py = "python"
if (Test-Path (Join-Path $RepoRoot "..")) { }  # noop; keep lint happy
$venvPy = Join-Path $env:USERPROFILE ".workbuddy\binaries\python\envs\safeopc\Scripts\python.exe"
if (Test-Path $venvPy) { $Py = $venvPy }

Write-Host "==> Python    : $Py"

Push-Location $RepoRoot
try {
    Write-Host "==> Running PyInstaller (this can take several minutes) ..."
    & $Py -m PyInstaller $SpecFile --noconfirm --clean
    if ($LASTEXITCODE -ne 0) {
        Write-Error "PyInstaller failed with exit code $LASTEXITCODE"
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}

if (Test-Path $ExePath) {
    $size = (Get-Item $ExePath).Length / 1MB
    Write-Host "==> BUILD OK : $ExePath  ($([math]::Round($size,1)) MB exe; full dir larger)"
    Write-Host "==> Smoke test:  `$env:SAFEOPC_HEADLESS=1; $ExePath`"
    Write-Host "==> Then:        curl http://127.0.0.1:8765/"
} else {
    Write-Error "Build finished but $ExePath was not produced."
    exit 1
}
