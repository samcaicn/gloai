# -*- coding: utf-8 -*-
"""精简 + 安全修复的回归测试（配合「清理垃圾功能」那次改造）。

覆盖三组：
  1. 安全：BYPASS_LOGIN 出厂必须为 False；出厂不预置弱口令；LISTEN_LIST 出厂为空
  2. 死 UI：授权面板 / 4 个隐藏 provider / 一键检测 等已删干净，且无残留引用
  3. 可达性：曾经进不去的 4 个页面现在都有导航入口，且真的能打开

跑法： ./.venv_bot/Scripts/python.exe _cleanup_test.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.join(ROOT, 'templates', 'config_editor.html')
CFG = os.path.join(ROOT, 'config.py')
CE = os.path.join(ROOT, 'config_editor.py')

_results = []


def check(desc, cond, extra=''):
    _results.append((desc, bool(cond), extra))
    return bool(cond)


def section(t):
    print('\n' + '=' * 68)
    print('  ' + t)
    print('=' * 68)


def read(p):
    return io.open(p, encoding='utf-8').read()


def strip_notes(text):
    """剔除注释（单行 // # <!-- 与跨行 /* */ <!-- -->），只留真实代码。

    删除处我都留了「原 xxx 已删」的说明注释，注释里提到名字是合理的，
    检查「是否删净」时必须先把注释剔掉，否则全是假阳性。
    """
    # 跨行块注释
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
    out = []
    for line in text.splitlines():
        st = line.strip()
        if st.startswith('//') or st.startswith('#'):
            continue
        out.append(line)
    return '\n'.join(out)


# ---------------------------------------------------------------- 1. 安全
section('1. 安全：出厂不得绕过登录 / 不得预置口令 / 不得泄露微信号')

cfg = read(CFG)

# 1.1 config.py 里没有把免密开关写成真
m = re.search(r'^\s*LICENSE_DEBUG_BYPASS_LOGIN\s*=\s*(\S+)', cfg, re.M)
check('config.py 未开启 LICENSE_DEBUG_BYPASS_LOGIN（出厂免密=关）',
      m is None or m.group(1) in ('False', 'false', '0'),
      '-> found %r' % (m.group(1) if m else None))

# 1.2 出厂不预置弱口令
mp = re.search(r"^\s*LOGIN_PASSWORD\s*=\s*(?:'([^']*)'|\"([^\"]*)\"|([^#\n]*))", cfg, re.M)
pwd = ''
if mp:
    pwd = (mp.group(1) or mp.group(2) or mp.group(3) or '').strip()
check('出厂 LOGIN_PASSWORD 为空（强制首启自设）', pwd == '',
      '-> got %r' % pwd)
check('出厂口令不是 123456', pwd != '123456')

# 1.3 PASSWORD_IS_VALID=False -> login_required 强制跳设置页
mv = re.search(r'^\s*PASSWORD_IS_VALID\s*=\s*(\S+)', cfg, re.M)
check('出厂 PASSWORD_IS_VALID=False（未设密码 -> 强制设置）',
      mv is not None and mv.group(1) in ('False', 'false', '0'),
      '-> got %r' % (mv.group(1) if mv else None))

# 1.4 LISTEN_LIST 出厂必须为空 —— 打包分发时不能把你的真实微信号送出去
ml = re.search(r'^\s*LISTEN_LIST\s*=\s*\[(.*?)\]', cfg, re.M | re.S)
lst = (ml.group(1).strip() if ml else '')
check('出厂 LISTEN_LIST 为空（不泄露真实微信号/群号）', lst == '',
      '-> got %r' % lst[:80])

# 1.5 全仓不得再有真实微信号残留
LEAK = ['SamCai_', '45158227848', '58550599600', '18712200430',
        '45882134652', '9ivay0f1tt9322', '44222621566']
leaked = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames
                   if d not in ('.venv_bot', 'build', 'dist', '__pycache__', '.git', 'vendor')]
    for fn in filenames:
        if not fn.endswith(('.py', '.html', '.md', '.txt')):
            continue
        p = os.path.join(dirpath, fn)
        if os.path.abspath(p) == os.path.abspath(__file__):
            continue          # 本文件字面量里就有这些 token，跳过自己
        try:
            body = read(p)
        except Exception:
            continue
        for token in LEAK:
            if token in body:
                leaked.append('%s <- %s' % (token, os.path.relpath(p, ROOT)))
check('全仓无真实微信号/群号残留', not leaked, '-> %s' % (leaked[:5] if leaked else 'clean'))

# 1.6 静态确认 login_required 不再被无条件跳过
ce = read(CE)
ce_code = strip_notes(ce)
check('config_editor 不再硬编码 BYPASS_LOGIN = True',
      not re.search(r'^\s*BYPASS_LOGIN\s*=\s*True', ce_code, re.M))
check('BYPASS_LOGIN 改为读 config 的调试开关（默认 False）',
      'LICENSE_DEBUG_BYPASS_LOGIN' in ce_code and '_debug_flag_from_config' in ce_code)


# ---------------------------------------------------------------- 2. 死 UI
section('2. 死 UI：已删元素不得有残留引用')

tpl = read(TPL)
tpl_code = strip_notes(tpl)

DEAD = [
    'licenseDeactivateBtn',    # 释放本机（发行版下把用户锁在门外）
    'licenseRestartBtn',       # 重启机器人（自动流程已代办）
    'licenseBuyBtn',           # 购买许可证（已被 3 个档位卡片取代）
    'payMethodsToggle',        # 支付方式折叠（与购买按钮完全重叠）
    'payMethodsPanel',         # 同上，整块面板
    'licenseBuyRow',           # 购买链接兜底行（发行版恒 display:none）
    'licenseCopyBuyBtn',
    'licenseOpenBuyBtn',
    'licenseGuardToggle',      # 门禁开关（发行版固杀，拨不动）
    'licenseWorkerHint',
    'licenseMachineHint',
    'licenseInstr',            # 操作指引（发行版已隐藏）
    'licenseKeyGroup',
    'licenseAdvanced',
    'oneKeyDetectLink',        # 一键检测（只 console.log + bat 没打进 EXE）
    'forumApiProvider',        # 4 个永久隐藏的服务商下拉
    'assistantApiProvider',
    'imageApiProvider',
    'onlineApiProvider',
    'updateNpcSelection',      # 空壳函数
    'toggleNpcCard',           # 无调用者
    'addNpcInput',             # 空函数体
    'removeNpcInput',
    'isWeAPIsUrl(',            # async 版本，无调用者
    'npc-individual-settings', # 50 行死 CSS
    'npc-card-body',
    'CSRF_EXEMPT_ENDPOINTS',   # 假安全配置
]
tpl_code = strip_notes(tpl)
ce_code = strip_notes(ce)

for name in DEAD:
    hay = tpl_code if name != 'CSRF_EXEMPT_ENDPOINTS' else ce_code
    check('已删净：%s' % name, name not in hay)

# 2.1 死路由也不该在后端
for route in ['/api/license/deactivate', '/api/license/set_guard', '/test_forum_ai']:
    check('后端已删路由 %s' % route, route not in ce_code)
for fn in ['safe_type_convert', 'kill_process_using_port']:
    check('后端已删死函数 %s' % fn, ('def %s' % fn) not in ce_code)

# 2.2 授权面板控件数量收敛
i = tpl.index('id="section-license"')
sec = tpl[i:i + 12000]
ids = re.findall(r'<(?:button|input|details|summary)[^>]*?id="([a-zA-Z]+)"', sec)
check('授权面板控件 <= 6 个（原来 11 个）', len(ids) <= 6, '-> %s' % ids)
check('授权面板有 3 个档位卡片', sec.count('class="lic-tier"') == 3)
check('3 个档位分别对应 初中高级',
      all(('data-tier="%s"' % tr) in sec for tr in ('normal', 'premium', 'lifetime')))

# 2.3 保留的 4 个 hidden 字段必须还在（它们承载提交值，删了会丢配置）
for hid in ['finalForumApiUrl', 'finalAssistantApiUrl',
            'finalImageApiUrl', 'finalOnlineApiUrl']:
    check('保留提交字段 %s' % hid, ('id="%s"' % hid) in tpl)

# 2.4 模型下拉初始化改造后仍把已存值写回 hidden
check('识图模型初始化改为 IIFE 且保留已存值',
      'initImageModelOptions' in tpl and 'finalImageModel.value = savedModel' in tpl)
check('联网模型初始化改为 IIFE 且保留已存值',
      'initOnlineModelOptions' in tpl and 'finalOnlineModel.value = savedModel' in tpl)


# ---------------------------------------------------------------- 3. 可达性
section('3. 可达性：曾经进不去的 4 个页面现在有入口且能打开')

for label, endpoint in [('固定转发', 'forward_page'), ('命令行', 'cli_page'),
                        ('MCP 接入', 'mcp_page'), ('Jev 状态', 'jev_page')]:
    check('顶栏有「%s」入口 -> url_for(\'%s\')' % (label, endpoint),
          ("url_for('%s')" % endpoint) in tpl)

# 3.1 真起 app 验证 7 个页面都能 200
try:
    import config as _c
    _c.LICENSE_DEBUG_BYPASS_LOGIN = True     # 仅测试期绕登录
    import config_editor as _ce
    _ce.BYPASS_LOGIN = True
    _ce.app.config['TESTING'] = True
    cl = _ce.app.test_client()
    for url in ['/', '/quick_start', '/style_lab', '/forward',
                '/cli', '/mcp', '/jev', '/help']:
        r = cl.get(url)
        check('GET %s -> 200' % url, r.status_code == 200,
              '-> got %s' % r.status_code)
    # 授权相关 API 仍可用
    r = cl.get('/api/license/status')
    check('GET /api/license/status -> 200 且 available', 
          r.status_code == 200 and (r.get_json() or {}).get('available') is True)
    r = cl.get('/api/license/buy_url?tier=normal')
    check('GET /api/license/buy_url?tier=normal -> 有 url',
          r.status_code == 200 and bool((r.get_json() or {}).get('url')))
    # 已删路由应 404
    for dead_url in ['/api/license/deactivate', '/api/license/set_guard']:
        r = cl.post(dead_url, json={})
        check('已删路由 %s -> 404' % dead_url, r.status_code == 404,
              '-> got %s' % r.status_code)
except Exception as e:  # noqa: BLE001
    check('Flask test_client 启动', False, '-> %s: %s' % (type(e).__name__, e))


# ---------------------------------------------------------------- 汇总
print('\n' + '=' * 68)
npass = sum(1 for _, ok, _ in _results if ok)
nfail = len(_results) - npass
for d, ok, x in _results:
    if not ok:
        print('  [FAIL] %s   %s' % (d, x))
print('  通过 %d 项，失败 %d 项' % (npass, nfail))
print('=' * 68)
sys.exit(1 if nfail else 0)
