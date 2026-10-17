# -*- coding: utf-8 -*-
"""单聊/群聊/转发 三条链路优化的针对性冒烟测试。

覆盖本轮修复的真 bug：
  1. inbox_reply 的 chat_history 记录 key 用原始 who（修手动回复后 bot 重复回复）
  2. inbox_sessions 微信库侧会话按显示名归并（修同一联系人两条会话）
  3. forward_page embed 模式（修 iframe 内「返回配置」整页跳转）
  4. 模板结构：用户列表抽屉 ①②③ 分区、群聊页指引、转发页来源标注
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, '.')

PASS = FAIL = 0


def check(name, cond, detail=''):
    global PASS, FAIL
    if cond:
        PASS += 1
        print('PASS', name)
    else:
        FAIL += 1
        print('FAIL', name, '|', detail)


def main():
    import config_editor as ce
    # 用临时库隔离 chat_history，避免 weauto_chat.db 跨运行累积导致会话合并断言不稳定
    import chat_history as _ch0
    _tmp = tempfile.NamedTemporaryFile(suffix='.db', delete=False)
    _tmp.close()
    _ch0.DB_PATH = _tmp.name
    _ch0._conn = None
    ce.app.config['WTF_CSRF_ENABLED'] = False
    c = ce.app.test_client()

    # ---------- 1. inbox_reply 记录 key ----------
    import chat_history
    recorded = []
    orig_record = chat_history.record_outgoing_bot

    def spy(who, content, msgtype=None):
        recorded.append((who, content))
        return orig_record(who, content, msgtype)

    chat_history.record_outgoing_bot = spy

    import wechat_compat

    class FakeWx:
        _db = None

        def _init(self):
            return self

        def close(self):
            pass

        def SendMsg(self, text, who):
            pass

    orig_wx_cls = wechat_compat.WeChat
    wechat_compat.WeChat = FakeWx
    try:
        r = c.post('/api/inbox/reply', json={'who': '凯哥', 'text': '好的收到'})
        j = json.loads(r.data)
        check('inbox_reply 发送成功', j.get('ok') is True, j)
        check('chat_history 按原始 who（凯哥）记录，不是解析后 username',
              bool(recorded) and recorded[-1][0] == '凯哥',
              'recorded=%r' % (recorded[-2:],))
        # 与 bot 侧口径一致：latest_message(昵称) 能读到这条手动回复
        last = chat_history.latest_message('凯哥')
        check('latest_message(凯哥) 能读到手动回复（补漏判定同源）',
              bool(last) and last['direction'] == 'outgoing' and last['content'] == '好的收到',
              'last=%r' % (last,))
    finally:
        wechat_compat.WeChat = orig_wx_cls
        chat_history.record_outgoing_bot = orig_record

    # ---------- 2. inbox_sessions 归并 ----------
    class FakeDB:
        def wxid(self):
            return 'wxid_self'

        def get_sessions(self, limit=200):
            return [
                {'username': 'wxid_abc123', 'nick_name': '凯哥'},
                {'username': 'wxid_zzz999', 'nick_name': '全新联系人'},
                {'username': '12345@chatroom', 'nick_name': '快剧运营'},
            ]

        def get_messages(self, who, limit=10):
            if who == 'wxid_zzz999':
                return [{'sender_id': 2, 'type': '文本', 'content': '在吗',
                         'sender_username': 'wxid_zzz999', 'create_time': 3000.0}]
            return []

    # 本地聊天记录里先造一条「凯哥」会话（模拟 bot 记录的昵称 key）
    orig_record('凯哥', '你好呀')

    orig_wxdb = ce._inbox_wxdb
    ce._inbox_wxdb = lambda: (None, FakeDB())
    try:
        r = c.get('/api/inbox/sessions')
        j = json.loads(r.data)
        check('sessions 接口 ok', j.get('ok') is True, j)
        sess = j.get('sessions') or []
        kaige = [s for s in sess if s.get('name') == '凯哥' or s.get('who') == '凯哥']
        check('「凯哥」只出现一条会话（本地昵称 key + 微信库 username key 已归并）',
              len(kaige) == 1, 'kaige=%r' % (kaige,))
        check('「凯哥」会话保留了本地 last_content',
              bool(kaige) and '你好呀' in (kaige[0].get('last_content') or ''),
              'kaige=%r' % (kaige,))
        fresh = [s for s in sess if s.get('who') == 'wxid_zzz999']
        check('本地没有的新联系人仍从微信库补充进来',
              len(fresh) == 1 and fresh[0].get('name') == '全新联系人'
              and fresh[0].get('last_content') == '在吗',
              'fresh=%r' % (fresh,))
        grp = [s for s in sess if s.get('who') == '12345@chatroom']
        check('群会话 is_group 标记正确', len(grp) == 1 and grp[0].get('is_group') is True,
              'grp=%r' % (grp,))
    finally:
        ce._inbox_wxdb = orig_wxdb

    # ---------- 2b. pending 口径：AI 接管会话不标红 ----------
    # bot 接管的会话（LISTEN_LIST 昵称 + forward 目标）由 AI 策略决定回不回复，
    # 消息中心不再标「未回复」——红点语义收敛为「无人接管，需人工处理」。
    orig_parse = ce.parse_config
    ce.parse_config = lambda: {
        'LISTEN_LIST': [
            ['凯哥', ''],
            ['接管中的好友', '', {'forward': ['销售群']}],
        ],
    }
    # 造一条「对方发来、bot 刻意未回」的接管会话消息（群聊概率/Jev hold 场景）
    orig_record_in = chat_history.record_incoming
    chat_history.record_incoming('接管中的好友', 'wxid_peer', '这条不该标红', '文本', False)
    ce._inbox_wxdb = lambda: (None, FakeDB())
    try:
        managed_set = ce._bot_managed_chats()
        check('_bot_managed_chats 含 LISTEN_LIST 昵称与 forward 目标',
              managed_set == {'凯哥', '接管中的好友', '销售群'}, managed_set)
        r = c.get('/api/inbox/sessions')
        j = json.loads(r.data)
        sess = j.get('sessions') or []
        taken = [s for s in sess if s.get('who') == '接管中的好友']
        fresh = [s for s in sess if s.get('who') == 'wxid_zzz999']
        check('AI 接管会话：末条是 incoming 也不标红（bot 有意不回不骚扰主人）',
              bool(taken) and taken[0].get('managed') is True
              and taken[0].get('pending') is False, 'taken=%r' % (taken,))
        check('非接管的新联系人（微信库 incoming）pending=True',
              bool(fresh) and fresh[0].get('pending') is True
              and fresh[0].get('managed') is False, 'fresh=%r' % (fresh,))
        check('每条会话都带 managed 字段（UI 徽章用）',
              all('managed' in s for s in sess))
    finally:
        ce.parse_config = orig_parse
        ce._inbox_wxdb = orig_wxdb
        chat_history.record_incoming = orig_record_in

    # ---------- 2c. pending 口径：靠群聊页规则回复的群不标红 ----------
    # 群没加进 LISTEN_LIST、只靠「群聊回复规则」页驱动 bot 按需回复时，
    # 消息中心不能把群消息标「待处理」——群消息 bot 未回是常态（概率/Jev/
    # 禁词/没@/没命中关键词），不是漏回。
    orig_parse2 = ce.parse_config
    ce.parse_config = lambda: {
        'ACCEPT_ALL_GROUP_CHAT_MESSAGES': False,
        'ENABLE_GROUP_AT_REPLY': True,
        'ENABLE_GROUP_KEYWORD_REPLY': False,
        'GROUP_WHITELIST': ['快剧运营'],
        'GROUP_BLACKLIST': ['广告'],
    }
    orig_record_in2 = chat_history.record_incoming
    # 白名单群：靠群聊页规则回复 -> 接管
    chat_history.record_incoming('快剧运营', 'wxid_member', '群里问个事', '文本', is_group=True)
    # 黑名单群：bot 永不回 -> 不接管，应标红
    chat_history.record_incoming('广告群', 'wxid_spam', '加我微信', '文本', is_group=True)
    ce._inbox_wxdb = lambda: (None, FakeDB())
    try:
        check('群聊白名单命中 -> _group_managed_by_bot 接管',
              ce._group_managed_by_bot('快剧运营') is True)
        check('群聊黑名单 -> _group_managed_by_bot 不接管',
              ce._group_managed_by_bot('广告群') is False)
        check('全局接收 -> 所有群接管',
              (lambda: (setattr(ce, 'parse_config', lambda: {'ACCEPT_ALL_GROUP_CHAT_MESSAGES': True}),
                        ce._group_managed_by_bot('任意群'))[1])() is True)
        ce.parse_config = lambda: {
            'ACCEPT_ALL_GROUP_CHAT_MESSAGES': False,
            'ENABLE_GROUP_AT_REPLY': True,
            'ENABLE_GROUP_KEYWORD_REPLY': False,
            'GROUP_WHITELIST': ['快剧运营'],
            'GROUP_BLACKLIST': ['广告'],
        }
        r = c.get('/api/inbox/sessions')
        j = json.loads(r.data)
        sess = j.get('sessions') or []
        grp_ok = [s for s in sess if s.get('who') == '12345@chatroom'
                  or s.get('name') == '快剧运营']
        grp_bad = [s for s in sess if s.get('name') == '广告群']
        check('白名单群：managed=True 且不标待处理',
              bool(grp_ok) and grp_ok[0].get('managed') is True
              and grp_ok[0].get('pending') is False, 'grp_ok=%r' % (grp_ok,))
        check('黑名单群：managed=False 且标待处理（bot 真不回，需人工）',
              bool(grp_bad) and grp_bad[0].get('managed') is False
              and grp_bad[0].get('pending') is True, 'grp_bad=%r' % (grp_bad,))
    finally:
        ce.parse_config = orig_parse2
        ce._inbox_wxdb = orig_wxdb
        chat_history.record_incoming = orig_record_in2

    # ---------- 3. forward_page embed ----------
    r = c.get('/forward', query_string={'embed': '1'})
    html = r.data.decode('utf-8')
    check('embed 模式 200', r.status_code == 200, r.status_code)
    check('embed 模式无「返回配置」按钮（不会整页跳转；_modern_ui 的 JS 注释里提到该词不算）',
          'class="nav-button">返回配置' not in html and '>返回配置</a>' not in html)
    check('embed 模式无独立页头 header-bar', 'class="header-bar"' not in html)
    check('embed 模式仍有规则编辑区', 'rulesBox' in html and '新增转发对' in html)
    r2 = c.get('/forward')
    html2 = r2.data.decode('utf-8')
    check('非 embed 模式保留页头与返回按钮', '返回配置' in html2 and 'header-bar' in html2)

    # ---------- 4. 模板结构 ----------
    r3 = c.get('/?skipped=true')
    h3 = r3.data.decode('utf-8')
    check('抽屉含 ①②③ 分区标题', '① 监测内容' in h3 and '② 命中后' in h3 and '③ 转发给' in h3)
    check('抽屉含 toggleMonPromptDisabled 接线', 'toggleMonPromptDisabled' in h3)
    check('用户与监听导语说明群可加', '群聊</b>都可以加' in h3 or '<b>群聊</b>都可以加' in h3)
    check('群聊页含专属人设指引', '用户与监听' in h3 and '专属人设' in h3)
    check('群白名单说明子串匹配', '子串' in h3)
    check('消息中心含搜索框', 'inbox-search' in h3)
    check('esc 含引号转义', "replace(/\"/g, '&quot;')" in h3)

    # ---------- 5. 固定转发：自身回声回环防护 ----------
    # 双向规则下，本机把消息转发进群；个别微信 4.x 版本把这次发送回显成
    # msgattr='group'（而非正常的 'self'）→ 回显又命中规则被再次转发 → 无限回环。
    # 防护：以 (会话, 正文) 精确记录本机刚发出的文本，窗口内回灌的异常回声直接拦截。
    import forward_hub as fh
    hub = fh._ForwardHub()
    hub._rules = {
        'enabled': True,
        'rules': [
            {'enabled': True, 'bidirectional': True,
             'party_a': [{'name': '群X'}], 'party_b': [{'name': '好友Y'}],
             'monitor_content': 'all', 'media_mode': 'original',
             'source_label': True, 'label_format': fh.DEFAULT_LABEL_FORMAT,
             'replace_alias': False, 'salutation_mode': 'off'},
        ],
    }
    hub._load_rules = lambda force=False: hub._rules  # 注入规则，绕过磁盘读取
    sent = []
    hub._send = lambda text, who: sent.append((who, text))
    hub.robot_name = '机器人'

    class _Msg:
        def __init__(self, content):
            self.content = content
            self.type = 'text'

    # A. 好友Y 的消息 -> 转发到 群X，并记录「刚发给群X 的文本」
    rA = hub.handle_incoming('好友Y', 'wxid_peer', 'text', 'friend', _Msg('在吗'))
    check('转发回声 A：好友Y->群X 已转发（返回 True 已消费）',
          rA is True and any(d == '群X' for d, _ in sent), 'sent=%r' % (sent,))
    # B. 刚转发到 群X 的文本以 msgattr='group' 异常回声回灌 -> 必须拦截，不再二次转发
    sent_b_before = list(sent)
    echo_text = sent_b_before[-1][1]
    rB = hub.handle_incoming('群X', '机器人', 'text', 'group', _Msg(echo_text))
    check('转发回声 B：群X 异常回声被回环防护拦截（不再二次转发）',
          rB is False and sent == sent_b_before, 'sent=%r' % (sent,))
    # C. 群X 里的真实新消息（非回声）仍正常转发到 好友Y
    sent.clear()
    rC = hub.handle_incoming('群X', 'wxid_other', 'text', 'group', _Msg('新消息'))
    check('转发回声 C：群X 真实新消息仍转发到 好友Y',
          rC is True and any(d == '好友Y' for d, _ in sent), 'sent=%r' % (sent,))
    # D. 同源但正文不同的回声不应误拦（窗口内不同文本放行）
    sent.clear()
    rD = hub.handle_incoming('群X', '机器人', 'text', 'group', _Msg('另一条不同内容'))
    check('转发回声 D：窗口内不同正文不误拦（仍转发）',
          rD is True and any(d == '好友Y' for d, _ in sent), 'sent=%r' % (sent,))

    # ---------- 6. 跨规则重复（相同甲乙方）检测 + 运行时目标去重 ----------
    # handle_incoming 按全部匹配规则逐条投递、无目标去重：两条规则的甲乙方相同
    # （无向边重复，含 A↔B 与 B↔A 反向书写）时，同一条消息会被重复投递到对端。
    # 防护：① 归一化时检测重复挂 conflicts 告警；② 运行时按目标去重兜底。
    def _mk_rule(rid, name, a, b, **kw):
        r = {'id': rid, 'name': name, 'enabled': True, 'bidirectional': True,
             'party_a': [{'name': a}], 'party_b': [{'name': b}],
             'monitor_content': 'all', 'media_mode': 'original',
             # 关掉标签/称呼处理，让「一次投递 = 一次发送」便于精确计数
             'source_label': False, 'replace_alias': False, 'salutation_mode': 'off'}
        r.update(kw)
        return r

    # 6a. 反向书写的相同甲乙方（群X↔好友Y 与 好友Y↔群X）应判为重复
    cfg_dup = hub._normalize_config({'enabled': True, 'rules': [
        _mk_rule('r1', '规则一', '群X', '好友Y'),
        _mk_rule('r2', '规则二', '好友Y', '群X'),
    ]})
    c1 = cfg_dup['rules'][0].get('conflicts') or []
    c2 = cfg_dup['rules'][1].get('conflicts') or []
    check('重复规则 6a：反向书写的相同甲乙方被判重复（规则一有告警）', len(c1) > 0, 'conflicts=%r' % (c1,))
    check('重复规则 6b：反向书写的相同甲乙方被判重复（规则二有告警）', len(c2) > 0, 'conflicts=%r' % (c2,))

    # 6c. 不同甲乙方（同一源的扇形转发）不应告警，否则会误伤合法配置
    cfg_ok = hub._normalize_config({'enabled': True, 'rules': [
        _mk_rule('r1', '规则一', '群X', '好友Y'),
        _mk_rule('r2', '规则二', '群X', '好友Z'),
    ]})
    oc = [(r.get('conflicts') or []) for r in cfg_ok['rules']]
    check('重复规则 6c：不同甲乙方（扇形转发）不告警', not any(oc), 'conflicts=%r' % (oc,))

    # 6d. 运行时去重兜底：重复规则下 好友Y->群X 只投递一次（不重复发）
    hub._rules = cfg_dup
    sent.clear()
    rDup = hub.handle_incoming('好友Y', 'wxid_peer', 'text', 'friend', _Msg('重复测试'))
    to_grp = [d for d, _ in sent if d == '群X']
    check('重复规则 6d：运行时目标去重，好友Y->群X 只投递一次',
          rDup is True and len(to_grp) == 1, 'to_群X=%r sent=%r' % (to_grp, sent))

    # 6e. 扇形转发仍正常：同一源命中两条不同规则时两个对端都要收到
    hub._rules = cfg_ok
    sent.clear()
    rFan = hub.handle_incoming('群X', 'wxid_other', 'text', 'group', _Msg('扇形测试'))
    check('重复规则 6e：扇形转发两个对端都收到（去重不误伤）',
          rFan is True and any(d == '好友Y' for d, _ in sent)
          and any(d == '好友Z' for d, _ in sent), 'sent=%r' % (sent,))

    # ---------- 7. 敏感信息拦截（中间商防飞单）----------
    # 中间商场景：需求方/供应商之间自动互转业务内容；若消息里出现手机号/微信号/
    # 金额等，说明双方可能试图绕过中间商直接交易——这类消息不自动转发，
    # 拦下来通知中间商人工处理（fail-safe：宁可多拦，人工放行很快）。
    cfg_sens = hub._normalize_config({'enabled': True, 'rules': [
        _mk_rule('s1', '业务互转', '需求方', '供应商', sensitive_hold=True),
    ]})
    # 7g. 归一化补全敏感字段默认值
    s1 = cfg_sens['rules'][0]
    check('敏感拦截 7g：归一化补全 sensitive_hold/words/notify_to 默认值',
          s1.get('sensitive_hold') is True and s1.get('sensitive_words') == []
          and s1.get('sensitive_notify_to') == '文件传输助手',
          'rule=%r' % (s1,))

    hub._rules = cfg_sens
    # 7a. 手机号命中：不转发，通知文件传输助手，返回 True（已消费，AI 不回）
    sent.clear()
    rS = hub.handle_incoming('需求方', 'wxid_a', 'text', 'friend', _Msg('这套方案不错，我微信 13812345678'))
    notified = [t for d, t in sent if d == '文件传输助手']
    check('敏感拦截 7a：手机号消息不转发给供应商、通知发文件传输助手、已消费',
          rS is True and not any(d == '供应商' for d, _ in sent) and len(notified) == 1
          and '13812345678' in notified[0] and '敏感拦截' in notified[0],
          'sent=%r' % (sent,))
    # 7b. 金额+微信号多标签命中（直接测特征）
    hits = hub._hit_sensitive(s1, '价格 ¥3500，加我微信 abc12345')
    check('敏感拦截 7b：金额与微信号同时命中',
          '金额' in hits and '微信号' in hits, 'hits=%r' % (hits,))
    # 7c. 自定义敏感词命中
    cfg_custom = hub._normalize_config({'enabled': True, 'rules': [
        _mk_rule('s2', '含自定义词', '需求方', '供应商', sensitive_hold=True,
                 sensitive_words=['报价单', '底价']),
    ]})
    hub._rules = cfg_custom
    sent.clear()
    rC2 = hub.handle_incoming('供应商', 'wxid_b', 'text', 'friend', _Msg('这是我们的底价，你看下'))
    check('敏感拦截 7c：自定义敏感词「底价」命中拦截',
          rC2 is True and not any(d == '需求方' for d, _ in sent)
          and any(d == '文件传输助手' for d, _ in sent), 'sent=%r' % (sent,))
    # 7d. 未开启拦截的规则：含手机号照常转发（不误拦）
    cfg_off = hub._normalize_config({'enabled': True, 'rules': [
        _mk_rule('s3', '不拦截', '需求方', '供应商'),
    ]})
    hub._rules = cfg_off
    sent.clear()
    rD2 = hub.handle_incoming('需求方', 'wxid_a', 'text', 'friend', _Msg('联系方式 13812345678'))
    check('敏感拦截 7d：未开启拦截时含手机号仍正常转发',
          rD2 is True and any(d == '供应商' for d, _ in sent), 'sent=%r' % (sent,))
    # 7e. 同一条消息重复触发只通知一次（TTL 内同会话同正文不重复轰炸）
    hub._rules = cfg_sens
    sent.clear()
    hub.handle_incoming('需求方', 'wxid_a', 'text', 'friend', _Msg('再发一次 13998887777'))
    n1 = len([1 for d, _ in sent if d == '文件传输助手'])
    hub.handle_incoming('需求方', 'wxid_a', 'text', 'friend', _Msg('再发一次 13998887777'))
    n2 = len([1 for d, _ in sent if d == '文件传输助手'])
    check('敏感拦截 7e：TTL 内同会话同正文只通知一次',
          n1 == 1 and n2 == 1, 'n1=%d n2=%d sent=%r' % (n1, n2, sent))
    # 7f. 开启拦截但普通消息（无敏感）仍正常转发
    sent.clear()
    rF2 = hub.handle_incoming('需求方', 'wxid_a', 'text', 'friend', _Msg('今天天气不错，方案我看完了'))
    check('敏感拦截 7f：普通消息不受拦截影响（正常转发）',
          rF2 is True and any(d == '供应商' for d, _ in sent), 'sent=%r' % (sent,))

    print('\n==== 汇总: %d PASS / %d FAIL ====' % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
