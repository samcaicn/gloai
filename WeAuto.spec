# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_all

datas = [('config.py', '.'), ('templates', 'templates'), ('emojis', 'emojis'), ('prompts', 'prompts'), ('Demo_Image', 'Demo_Image')]
binaries = []
hiddenimports = ['bot', 'wechat_compat', 'wechatauto', 'forward_hub', 'jev_guard', 'cli', 'weauto_mcp', 'weauto_stdio', 'weauto_license', 'weauto_license.guard', 'mcp', 'uiautomation', 'comtypes', 'colorama', 'webview.platforms.edgechromium', 'webview.platforms.winforms']
tmp_ret = collect_all('mcp')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('wechatauto')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('webview')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('clr_loader')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('pythonnet')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]


a = Analysis(
    ['config_editor.py'],
    pathex=['vendor'],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='WeAuto',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    # GUI-only：noconsole 形态，启动不弹 cmd 黑框。
    # 需要真实 stdio 的子形态（--cli / --mcp / --bot）在 config_editor.__main__
    # 里用 AttachConsole(ATTACH_PARENT_PROCESS) 重新附着调用方控制台，
    # 从命令行/agent 启动时照常输出，不受影响。
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    version='version_info.txt',
    manifest='manifest.xml',
)
