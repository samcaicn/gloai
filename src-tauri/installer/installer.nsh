; Installer hook for NSIS bundler.
;
; Kept lean — tauri-build sometimes panics on long macros during Windows
; child-process wait. All platform-specific install logic lives in Rust
; (commands/system.rs) so we can use proper error handling and emit events
; to the frontend, instead of NSIS's terse `nsExec::ExecToLog`.

; ----------------------------------------------------------------------------
; customPreInstall — kill any running instance before we replace the binary.
; perUser install can only kill the current user's processes, which is
; exactly what we want (different users' tupai.exe are isolated).
; ----------------------------------------------------------------------------
!macro customPreInstall
  ; Kill any running instance regardless of brand (tupai/safeopc/other).
  ; We cannot rely on $INSTDIR exe name at this point, so fire broad.
  nsExec::ExecToLog 'taskkill /F /IM tupai.exe /T'
  nsExec::ExecToLog 'taskkill /F /IM safeopc.exe /T'
  ; Wait a beat for the processes to fully exit (avoid "file in use" errors)
  Sleep 500
!macroend

; ----------------------------------------------------------------------------
; customCheck — verify Windows version + WebView2 before installation.
;
; Previously this macro only `nsExec::ExecToLog 'cmd /c ver'` without
; parsing the result. Now it actually:
;   1. Reads HKLM\...\CurrentBuild and aborts if < 19041 (Win10 2004).
;      Win10 < 2004 lacks the modern WebView2 runtime + WinRT OCR APIs
;      the app depends on — installing there would crash on first launch.
;   2. Reads HKLM\...\EdgeUpdate\Clients\{F3017226-...} to detect
;      pre-installed WebView2. If missing, the embedBootstrapper will
;      try to download it; if that fails (offline), the user gets a
;      clear message instead of a silent install-then-crash.
;
; Both checks are best-effort: if registry reads fail (rare), we proceed
; and let the embedBootstrapper / first-run handle it. We never Abort on
; "couldn't read registry" — only on "definitely too old".
; ----------------------------------------------------------------------------
!macro customCheck
  ; --- Windows version check (BuildNumber ≥ 19041 = Win10 2004) ---
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\Windows NT\CurrentVersion" "CurrentBuild"
  ${If} $0 != ""
    ; CurrentBuild is always numeric (e.g. "19045", "22631"). IntCmp does
    ; signed 32-bit comparison — fine for Windows build numbers.
    ; Syntax: IntCmp val1 val2 jump_if_equal jump_if_val1_less jump_if_val1_greater
    IntCmp $0 19041 build_ok build_too_old build_ok
    build_too_old:
      MessageBox MB_ICONSTOP|MB_OK "AIMarketing 需要 Windows 10 2004 (build 19041) 或更高版本。$\r$\n$\r$\n当前 build: $0$\r$\n$\r$\n请先升级 Windows 后再安装。"
      Abort
    build_ok:
      DetailPrint "AIMarketing install: Windows build $0 OK (≥ 19041)"
  ${EndIf}

  ; --- WebView2 runtime detection (informational) ---
  ReadRegStr $0 HKLM "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${EndIf}
  ${If} $0 == ""
    DetailPrint "AIMarketing install: WebView2 runtime not detected — embedBootstrapper will download it (requires internet)."
  ${Else}
    DetailPrint "AIMarketing install: WebView2 runtime detected (version $0)."
  ${EndIf}
!macroend

; ----------------------------------------------------------------------------
; customInstall — Anti-false-positive measures for Windows Defender and AVs.
;
; Previously this macro was removed in P0-4 (perUser install switch) because
; the old version installed OCR language packs requiring admin. Now it only
; contains best-effort anti-AV measures that silently fail on non-admin:
;
;   a) Unblock-File — Remove Zone.Identifier ADS from all installed files.
;      AV engines treat files with Zone=3 (internet) as higher risk.
;   b) Add-MpPreference — Add install dir + process to Defender exclusion.
;      This is the single most effective anti-false-positive measure for
;      apps that use UIA / CDP / OCR / keyboard hooks.
;   c) vendor.txt — Write publisher metadata so AV heuristics see a
;      well-known publisher string alongside the binary.
;
; All commands use -ErrorAction SilentlyContinue so they no-op on:
;   - per-user installs (non-admin, can't modify Defender)
;   - Windows 7 (no Add-MpPreference cmdlet)
;   - Disconnected / broken PowerShell
; ----------------------------------------------------------------------------
!macro customInstall
  ; ── Remove Zone.Identifier from installed files ────────────────────
  nsExec::ExecToLog 'powershell -Command "Get-ChildItem -Path \"$INSTDIR\" -Recurse -File | Unblock-File -ErrorAction SilentlyContinue"'

  ; ── Add install directory to Windows Defender exclusion ─────────────
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionPath \"$INSTDIR\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionProcess \"$INSTDIR\\tupai.exe\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionProcess \"$INSTDIR\\safeopc.exe\" -ErrorAction SilentlyContinue"'

  ; ── Write vendor metadata file ─────────────────────────────────────
  FileOpen $0 "$INSTDIR\vendor.txt" w
  IfFileExists "$INSTDIR\safeopc.exe" 0 +3
  FileWrite $0 "ProductName=AIMarketing$\r$\n"
  FileWrite $0 "Publisher=AIMarketing$\r$\n"
  FileWrite $0 "Support=https://safeopc.example.com$\r$\n"
  Goto +4
  FileWrite $0 "ProductName=AIMarketing$\r$\n"
  FileWrite $0 "Publisher=AIMarketing$\r$\n"
  FileWrite $0 "Support=https://trace-auto.example.com$\r$\n"
  FileClose $0
!macroend

; ----------------------------------------------------------------------------
; customPostInstall — relaunch the app after a silent update install.
;
; When the installer runs with /S /UPDATE=1 (silent update), the finish
; page (which normally offers a "Launch app" checkbox) is skipped, so
; the app would NOT be running after the upgrade. The Rust side calls
; app.exit(0) before the installer replaces the binary, which means
; the process is gone by the time installation finishes — without this
; hook the user would be left with no app window open after an upgrade.
;
; `IfSilent` is true for /S installs (the only path the Rust updater
; uses). For non-silent installs the MUI finish page handles the
; launch checkbox, so we skip here to avoid double-launching.
; ----------------------------------------------------------------------------
!macro customPostInstall
  IfSilent +2
  Goto skip_relaunch_after_update
  ; Give the OS a moment to release file locks on the newly-written
  ; binary before we spawn it (avoids "file in use" on slow disks).
  Sleep 300
  Exec '"$INSTDIR\${MAINBINARYNAME}.exe"'
  skip_relaunch_after_update:
!macroend

; ----------------------------------------------------------------------------
; customUnInstall — Clean up Defender exclusions added during install.
; ----------------------------------------------------------------------------
!macro customUnInstall
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionPath \"$INSTDIR\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionProcess \"$INSTDIR\\tupai.exe\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionProcess \"$INSTDIR\\safeopc.exe\" -ErrorAction SilentlyContinue"'
!macroend
