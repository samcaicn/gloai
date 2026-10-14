# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_all

datas = [('C:/d/weauto/_pybuild_work/config.py', '.'), ('C:/d/weauto/_pybuild_work/config.py.sig', '.'), ('C:/d/weauto/templates', 'templates'), ('C:/d/weauto/emojis', 'emojis'), ('C:/d/weauto/prompts', 'prompts'), ('C:/d/weauto/Demo_Image', 'Demo_Image'), ('C:/d/weauto/release_secrets.enc', '.')]
binaries = []
hiddenimports = ['bot', 'wechat_compat', 'wechatauto', 'forward_hub', 'jev_guard', 'weauto_stdio', 'cli', 'weauto_mcp', 'weauto_license', 'weauto_license.guard', 'mcp', 'uiautomation', 'comtypes', 'colorama', 'webview.platforms.edgechromium', 'webview.platforms.winforms']
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
    ['C:/d/weauto/config_editor.py'],
    pathex=['C:/d/weauto/vendor', 'C:/d/weauto/_pyobf_dist'],
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
splash = Splash(
    'C:/d/weauto/splash.png',
    binaries=a.binaries,
    datas=a.datas,
    text_pos=None,
    text_size=12,
    minify_script=True,
    always_on_top=True,
)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    splash,
    splash.binaries,
    [],
    name='WeAuto',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    version='C:/d/weauto/version_info.txt',
    manifest='C:/d/weauto/manifest.xml',
)
