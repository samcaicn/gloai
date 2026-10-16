# -*- coding: utf-8 -*-
"""单聊/群聊/转发 三条链路优化的针对性冒烟测试。

覆盖本轮修复的真 bug：
  1. inbox_reply 的 chat_history 记录 key 用原始 who（修手动回复后 bot 重复回复）
  2. inbox_sessions 微信库侧会话按显示名归并（修同一联系人两条会话）
  3. forward_page embed 模式（修 iframe 内「返回配置」整页跳转）
  4. 模板结构：用户列表抽屉 ①②③ 分区、群聊页指引、转发页来源标注
"""
import json
import sys

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

    print('\n==== 汇总: %d PASS / %d FAIL ====' % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
