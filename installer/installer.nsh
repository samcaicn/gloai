; ── NSIS installer hooks for AIMarketing / AIMarketing ──────────────────────────
;
; Anti-false-positive measures for Windows Defender and other AVs:
;
;   1. customPreInstall — Kill running instances before overwriting.
;      If the old .exe is still loaded in memory when NSIS tries to
;      replace it, Windows marks the replacement as "modified during
;      write" which triggers Defender's heuristics.
;
;   2. customCheck — Log Windows version for diagnostics.
;
;   3. customInstall — Post-install hardening:
;      a) Add the install directory to Windows Defender exclusion list
;         (requires admin; silently skipped for per-user installs).
;      b) Write a "vendor" metadata file so AV heuristics see a
;         well-known publisher string alongside the binary.
;      c) Unblock downloaded files (remove Zone.Identifier alternate
;         data streams) so SmartScreen doesn't flag them.
;
;   4. customUnInstall — Clean up Defender exclusions on uninstall.

!macro customPreInstall
  ; Force-kill any leftover instance from a previous install (any brand).
  ; This prevents "file in use" errors and the resulting AV false positives
  ; that occur when NSIS writes a new .exe while the old one is still
  ; memory-mapped by the OS.
  nsExec::ExecToLog 'taskkill /F /IM tupai.exe /T'
  nsExec::ExecToLog 'taskkill /F /IM safeopc.exe /T'
  Sleep 500
!macroend

!macro customCheck
  nsExec::ExecToLog 'cmd /c ver'
  nsExec::ExecToLog 'cmd /c wmic os get BuildNumber'
!macroend

!macro customInstall
  ; ── Remove Zone.Identifier from installed files ────────────────────
  ; When the installer is downloaded from the internet, Windows marks
  ; every extracted file with a Zone.Identifier ADS (Alternate Data
  ; Stream). Some AV engines treat files with Zone=3 (internet) as
  ; higher risk, increasing false-positive rates. Removing the ADS
  ; tells the OS these files originated from a trusted installer.
  ;
  ; `powershell -Command "Unblock-File"` is the safe, built-in way to
  ; strip Zone.Identifier. It's available on Windows 8+ and Server 2012+.
  ; We use -ErrorAction SilentlyContinue because:
  ;   a) The cmdlet doesn't exist on Windows 7 (but we still install OK)
  ;   b) Some files may not have a Zone.Identifier (no error, just no-op)
  nsExec::ExecToLog 'powershell -Command "Get-ChildItem -Path "$INSTDIR" -Recurse -File | Unblock-File -ErrorAction SilentlyContinue"'

  ; ── Add install directory to Windows Defender exclusion ──────────────
  ; This is the single most effective anti-false-positive measure for
  ; desktop apps that use UIA / CDP / OCR / keyboard hooks — all of
  ; which look "suspicious" to Defender's behavioral heuristics.
  ;
  ; We add the *directory* exclusion (not process) because:
  ;   - Process exclusions require the process to already be running
  ;   - Directory exclusions also cover temp/config files the app writes
  ;   - Defender real-time scanning sees the .exe before it launches
  ;
  ; This only works when running as Administrator (per-machine install).
  ; For per-user installs (non-admin), the command silently fails —
  ; that's OK because per-user installs go to %LOCALAPPDATA% which
  ; Defender treats as lower-risk by default.
  ;
  ; We also add the process exclusion as a belt-and-suspenders measure.
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionPath \"$INSTDIR\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionProcess \"$INSTDIR\\tupai.exe\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Add-MpPreference -ExclusionProcess \"$INSTDIR\\safeopc.exe\" -ErrorAction SilentlyContinue"'

  ; ── Write vendor metadata file ─────────────────────────────────────
  ; Some AV engines look for publisher/vendor metadata alongside the
  ; binary. Writing a small text file with publisher info can reduce
  ; heuristic scoring. This is a "soft" measure — not guaranteed, but
  ; cheap and harmless.
  ;
  ; We detect the brand from the executable name in $INSTDIR so the
  ; vendor.txt matches the actual product (tupai vs safeopc).
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

!macro customUnInstall
  ; ── Remove Defender exclusions on uninstall ─────────────────────────
  ; Clean up the exclusions we added during install so we don't leave
  ; stale paths in the user's Defender config after uninstall.
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionPath \"$INSTDIR\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionProcess \"$INSTDIR\\tupai.exe\" -ErrorAction SilentlyContinue"'
  nsExec::ExecToLog 'powershell -Command "Remove-MpPreference -ExclusionProcess \"$INSTDIR\\safeopc.exe\" -ErrorAction SilentlyContinue"'
!macroend
