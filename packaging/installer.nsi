; SafeOPC NSIS installer
; Packs the PyInstaller onedir build (dist/SafeOPC/) into a single setup exe.
; NOTE: this installer is UNSIGNED. Windows SmartScreen/Defender will still
; flag it on first run ("Windows protected your PC"). Code-signing the
; resulting SafeOPC-Setup.exe is required to remove that prompt.

!define APPNAME    "SafeOPC"
!define APPVERSION "0.1.0"
!define PUBLISHER  "SafeOPC"
!define SRC        "C:\code\openopc\dist\SafeOPC"
!define UNKEY      "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"

Name    "${APPNAME}"
OutFile "C:\code\openopc\dist\SafeOPC-Setup.exe"
InstallDir "$PROGRAMFILES\${APPNAME}"
RequestExecutionLevel admin   ; writing to Program Files needs elevation
Unicode true
CRCCheck on

!include "MUI2.nsh"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

VIProductVersion "${APPVERSION}.0"
VIAddVersionKey "ProductName"    "${APPNAME}"
VIAddVersionKey "ProductVersion" "${APPVERSION}"
VIAddVersionKey "CompanyName"    "${PUBLISHER}"
VIAddVersionKey "FileVersion"    "${APPVERSION}.0"
VIAddVersionKey "FileDescription" "SafeOPC Desktop"
VIAddVersionKey "LegalCopyright"  "© ${PUBLISHER}"

Section "Install"
  SetOutPath "$INSTDIR"
  ; Recursively bundle the whole onedir build (SafeOPC.exe + _internal/).
  File /r "${SRC}\*"

  ; Shortcuts
  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\SafeOPC.exe"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk"              "$INSTDIR\SafeOPC.exe"

  ; Uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; Registry entry for "Programs and Features"
  WriteRegStr HKLM "${UNKEY}" "DisplayName"     "${APPNAME}"
  WriteRegStr HKLM "${UNKEY}" "DisplayVersion"  "${APPVERSION}"
  WriteRegStr HKLM "${UNKEY}" "Publisher"       "${PUBLISHER}"
  WriteRegStr HKLM "${UNKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNKEY}" "UninstallString" "$INSTDIR\Uninstall.exe"
  WriteRegDWORD HKLM "${UNKEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNKEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  RMDir /r "$INSTDIR"
  Delete "$DESKTOP\${APPNAME}.lnk"
  Delete "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk"
  RMDir  "$SMPROGRAMS\${APPNAME}"
  DeleteRegKey HKLM "${UNKEY}"
SectionEnd
