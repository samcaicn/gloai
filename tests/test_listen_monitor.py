# -*- coding: utf-8 -*-
"""用户列表扩展测试：监测内容（LISTEN_LIST 第三列）+ 固定转发同步。

运行：
    python tests/test_listen_monitor.py
"""
import os
import sys
import json
import shutil
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.dirname(HERE)
sys.path.insert(0, PKG)

from werkzeug.datastructures import ImmutableMultiDict

# 先隔离 forward_hub 的存储目录，避免污染真实 forward_rules.json
import forward_hub
_TMP = tempfile.mkdtemp()
forward_hub._HUB._base = _TMP
forward_hub._HUB._rules_path = os.path.join(_TMP, 'forward_rules.json')
forward_hub._HUB._log_path = os.path.join(_TMP, 'forward_log.json')
forward_hub._HUB._ensure_files()
forward_hub._HUB._load_rules(force=True)

import config_editor
from config_editor import (_build_listen_list_from_form,
                           _listen_settings_is_empty,
                           _sync_listen_forward_rules)

# ---- 从 bot.py 源码中截取监测内容逻辑块（模块导入太重，按标记切片执行）----
_bot_src = open(os.path.join(PKG, 'bot.py'), encoding='utf-8').read()
_start = _bot_src.index('# 监测内容设置（LISTEN_LIST 第三列，可选）')
_end = _bot_src.index("    return 'prompt'", _start) + len("    return 'prompt'")
_block = _bot_src[_start:_end]
_ns = {'LISTEN_LIST': [], 'logger': None}
exec(compile(_block, '<monitor-block>', 'exec'), _ns)
check_monitor_content = _ns['check_monitor_content']


def _set_bot_listen(entries):
    _ns['LISTEN_LIST'] = entries
    _ns['monitor_settings'] = _ns['_build_monitor_settings']()
    _ns['_monitor_prompt_override'] = {
        who: cfg['prompt_file'] for who, cfg in _ns['monitor_settings'].items()
        if cfg['action'] == 'prompt' and cfg['prompt_file']
    }


def test_settings_empty_check():
    assert _listen_settings_is_empty({})
    assert _listen_settings_is_empty(None)
    assert _listen_settings_is_empty({'monitor': {}, 'forward': []})
    assert not _listen_settings_is_empty({'forward': ['李四']})
    assert not _listen_settings_is_empty({'monitor': {'keywords': ['报价']}})
    print('PASS test_settings_empty_check')


def test_build_listen_list_with_settings():
    st = {'monitor': {'types': ['text'], 'keywords': [], 'action': 'prompt', 'prompt_file': '客服'},
          'forward': ['李四', '销售群']}
    form = ImmutableMultiDict([
        ('nickname', '张三'), ('prompt_file', '角色1'),
        ('listen_settings', json.dumps(st)),
        ('nickname', '王五'), ('prompt_file', '角色2'),
        ('listen_settings', '{}'),
    ])
    out = _build_listen_list_from_form(form, {'LISTEN_LIST': []})
    assert out[0] == ['张三', '角色1', st], out[0]
    assert out[1] == ['王五', '角色2'], out[1]        # 空设置退化为两元素
    print('PASS test_build_listen_list_with_settings')


def test_build_listen_list_preserves_old_without_field():
    """表单不含 listen_settings（快速上手/旧表单）时，按昵称保留旧设置。"""
    old = {'LISTEN_LIST': [['张三', '角色1', {'forward': ['李四']}]]}
    form = ImmutableMultiDict([('nickname', '张三'), ('prompt_file', '角色1')])
    out = _build_listen_list_from_form(form, old)
    assert out == [['张三', '角色1', {'forward': ['李四']}]], out
    print('PASS test_build_listen_list_preserves_old_without_field')


def test_build_listen_list_drops_incomplete_rows():
    form = ImmutableMultiDict([('nickname', '张三'), ('prompt_file', ''),
                               ('nickname', ''), ('prompt_file', '角色2')])
    out = _build_listen_list_from_form(form, {'LISTEN_LIST': []})
    assert out == [], out
    print('PASS test_build_listen_list_drops_incomplete_rows')


def test_sync_forward_rules():
    entries = [
        ['张三', '角色1', {'monitor': {'types': ['text'], 'keywords': [], 'action': 'prompt', 'prompt_file': ''},
                           'forward': ['李四', '李四', '张三', '销售群']}],
        ['王五', '角色2'],                                        # 无设置 → 不生成
        ['赵六', '角色3', {'forward': []}],                       # 空转发 → 清除其生成规则
    ]
    # 预置一条旧的生成规则（应被清除）和一条手动规则（应保留）
    forward_hub.save_rules({'enabled': True, 'rate_limit': {'window_sec': 10, 'max_msgs': 15},
                            'rules': [{'id': 'listen_deadbeefdead', 'name': '旧生成',
                                       'party_a': ['赵六'], 'party_b': ['某人']},
                                      {'id': 'manual1', 'name': '手动规则',
                                       'party_a': ['甲'], 'party_b': ['乙']}]})
    _sync_listen_forward_rules(entries)
    cfg = forward_hub.get_rules()
    ids = [r['id'] for r in cfg['rules']]
    assert 'listen_deadbeefdead' not in ids, ids            # 旧生成规则已清除
    assert 'manual1' in ids, ids                            # 手动规则保留
    import hashlib
    expect = 'listen_' + hashlib.md5('张三'.encode('utf-8')).hexdigest()[:12]
    rule = next(r for r in cfg['rules'] if r['id'] == expect)
    # 规则落库后成员被归一化为 {name, alias} 结构，取 name 比较
    names_a = [m['name'] if isinstance(m, dict) else m for m in rule['party_a']]
    names_b = [m['name'] if isinstance(m, dict) else m for m in rule['party_b']]
    assert names_a == ['张三'], names_a
    assert set(names_b) == {'李四', '销售群'}, names_b  # 去重且剔除自己
    assert rule['bidirectional'] is True and rule['enabled'] is True
    # 赵六/王五不再有生成规则
    expect6 = 'listen_' + hashlib.md5('赵六'.encode('utf-8')).hexdigest()[:12]
    assert expect6 not in ids
    print('PASS test_sync_forward_rules')


def test_monitor_no_config_normal():
    _set_bot_listen([['张三', '角色1']])
    assert check_monitor_content('张三', 'text', '你好') == 'normal'
    assert check_monitor_content('陌生人', 'text', '你好') == 'normal'
    print('PASS test_monitor_no_config_normal')


def test_monitor_keywords_match_and_miss():
    st = {'monitor': {'types': [], 'keywords': ['订单', '退款'], 'action': 'prompt', 'prompt_file': ''}}
    _set_bot_listen([['张三', '角色1', st]])
    assert check_monitor_content('张三', 'text', '我要退货，订单号123') == 'prompt'
    assert check_monitor_content('张三', 'text', '今天天气不错') == 'ignore'   # 未命中 → 不回应
    print('PASS test_monitor_keywords_match_and_miss')


def test_monitor_types_filter():
    st = {'monitor': {'types': ['text'], 'keywords': [], 'action': 'prompt', 'prompt_file': ''}}
    _set_bot_listen([['张三', '角色1', st]])
    assert check_monitor_content('张三', 'text', '你好') == 'prompt'
    assert check_monitor_content('张三', 'voice', '') == 'ignore'             # 类型不匹配 → 不回应
    print('PASS test_monitor_types_filter')


def test_monitor_action_ignore_mutes():
    # 指定类型 + 不回应：命中该类型即静默，其余类型同样不回应（只监测所选内容）
    st = {'monitor': {'types': ['voice'], 'keywords': [], 'action': 'ignore', 'prompt_file': ''}}
    _set_bot_listen([['张三', '角色1', st]])
    assert check_monitor_content('张三', 'voice', '') == 'ignore'
    assert check_monitor_content('张三', 'text', '你好') == 'ignore'
    # 全部内容 + 不回应 = 整体屏蔽
    st2 = {'monitor': {'types': [], 'keywords': [], 'action': 'ignore', 'prompt_file': ''}}
    _set_bot_listen([['李四', '角色2', st2]])
    assert check_monitor_content('李四', 'text', '任意') == 'ignore'
    print('PASS test_monitor_action_ignore_mutes')


def test_monitor_prompt_override():
    st = {'monitor': {'types': [], 'keywords': ['报价'], 'action': 'prompt', 'prompt_file': '客服'}}
    _set_bot_listen([['张三', '角色1', st]])
    assert check_monitor_content('张三', 'text', '发个报价') == 'prompt'
    assert _ns['_monitor_prompt_override'].get('张三') == '客服'
    # 未配置专用 prompt → 无覆盖，用行 Prompt
    st2 = {'monitor': {'types': [], 'keywords': ['报价'], 'action': 'prompt', 'prompt_file': ''}}
    _set_bot_listen([['王五', '角色2', st2]])
    assert check_monitor_content('王五', 'text', '发个报价') == 'prompt'
    assert '王五' not in _ns['_monitor_prompt_override']
    print('PASS test_monitor_prompt_override')


def test_monitor_group_and_type_and_keyword():
    """群：类型+关键字同时配置时需同时满足。"""
    st = {'monitor': {'types': ['text'], 'keywords': ['日报'], 'action': 'prompt', 'prompt_file': ''}}
    _set_bot_listen([['工作群', '', st]])
    assert check_monitor_content('工作群', 'text', '今天的日报来了') == 'prompt'
    assert check_monitor_content('工作群', 'image', '') == 'ignore'
    assert check_monitor_content('工作群', 'text', ' unrelated ') == 'ignore'
    print('PASS test_monitor_group_and_type_and_keyword')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_') and callable(v)]
    passed = 0
    for t in tests:
        try:
            t()
            passed += 1
        except AssertionError as e:
            print('FAIL %s: %s' % (t.__name__, e))
        except Exception as e:  # noqa: BLE001
            import traceback
            print('ERR  %s: %s' % (t.__name__, e))
            traceback.print_exc()
    print('\n%d/%d passed' % (passed, len(tests)))
    shutil.rmtree(_TMP, ignore_errors=True)
    sys.exit(0 if passed == len(tests) else 1)
