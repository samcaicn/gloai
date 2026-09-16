# -*- coding: utf-8 -*-
"""聊天记录持久化（SQLite）——「模仿主人对话风格」功能的数据底座。

设计要点
--------
* 项目此前只有滚动的 chat_contexts.json（会被裁剪），没有完整历史，
  因此无法做任何风格统计。本模块补齐这一层。
* SQLite 是 Python 内置依赖，不引入任何第三方包，满足离线打包要求。
* 区分消息来源，是「模仿主人」的关键：
    - source='friend'  好友发来的（收到）
    - source='group'   群里别人发的（收到）
    - source='owner'   主人亲手敲的（发出）  ← 风格学习只用这些
    - source='bot'     bot 代发的（发出）
* bot 代发的消息会在微信侧回显一条 msgattr=='self' 的消息，若不识别会把
  bot 的话误当成主人的话去学（风格就跑偏成 AI 腔）。这里用「发送窗口回声
  去重」解决：bot 发送时登记 (who, content, ts)，回显在窗口期内且内容一致
  则判为 bot，否则判为 owner。
* 所有异常都在本模块内兜住：记录失败绝不影响 bot 收发主流程。
"""

import os
import sqlite3
import threading
import time
import logging

logger = logging.getLogger(__name__)

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'weauto_chat.db')

# RLock：record() 等会先持锁再调 _get_conn()，而 _get_conn() 内部也取同一把锁，
# 必须用可重入锁，否则初始化连接时会自死锁。
_lock = threading.RLock()
_recent_lock = threading.Lock()  # 保护内存态回声窗口 _recent_bot_sends
_conn = None

# bot 代发登记的回声窗口（秒）
BOT_ECHO_WINDOW = 25.0
_MAX_TRACKED_SENDS = 200
_recent_bot_sends = []  # [(ts, who, content)] —— 仅内存，重启后丢失不影响正确性

_SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        REAL NOT NULL,
    who       TEXT,
    sender    TEXT,
    direction TEXT NOT NULL,
    source    TEXT NOT NULL,
    msgtype   TEXT,
    content   TEXT
);
CREATE INDEX IF NOT EXISTS idx_messages_ts     ON messages(ts);
CREATE INDEX IF NOT EXISTS idx_messages_source ON messages(source);
"""


def _get_conn():
    """获取（并按需初始化）SQLite 连接。check_same_thread=False 供多线程复用。"""
    global _conn
    if _conn is not None:
        return _conn
    with _lock:
        if _conn is None:
            try:
                _conn = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=10)
                _conn.execute("PRAGMA journal_mode=WAL")  # 减少写锁竞争
                _conn.executescript(_SCHEMA)
                _conn.commit()
                logger.info(f"聊天记录库已就绪: {DB_PATH}")
            except Exception as e:
                logger.error(f"初始化聊天记录库失败（不影响收发）: {e}", exc_info=True)
                _conn = None
    return _conn


def mark_bot_send(who, content):
    """bot 代发一条消息时登记，用于稍后识别微信侧回显。"""
    try:
        with _recent_lock:
            _recent_bot_sends.append((time.time(), who or '', (content or '')[:500]))
            if len(_recent_bot_sends) > _MAX_TRACKED_SENDS:
                del _recent_bot_sends[: len(_recent_bot_sends) - _MAX_TRACKED_SENDS]
    except Exception as e:
        logger.debug(f"登记 bot 发送失败: {e}")


def _is_bot_echo(who, content, ts):
    """判断这条账号侧消息是否是剛刚 bot 代发产生的回显。"""
    try:
        who = who or ''
        content = (content or '')[:500]
        with _recent_lock:
            items = list(_recent_bot_sends)
        for s_ts, s_who, s_content in reversed(items):
            if ts - s_ts > BOT_ECHO_WINDOW:
                break
            if s_who == who and s_content == content:
                return True
    except Exception:
        pass
    return False


def record(who, sender, direction, source, content, msgtype=None, ts=None):
    """写入一条聊天记录。direction: 'in'|'out'；source: friend|group|owner|bot|self。

    注意：单条连接对象在多线程下并发 execute/commit 会触发 "database is locked"
    甚至偶发损坏，因此所有 DB 访问都用模块级 _lock 串行化。
    """
    with _lock:
        try:
            conn = _get_conn()
            if conn is None:
                return False
            conn.execute(
                "INSERT INTO messages (ts, who, sender, direction, source, msgtype, content)"
                " VALUES (?,?,?,?,?,?,?)",
                (ts or time.time(), who or '', sender or '', direction, source,
                 msgtype or '', (content or '')[:4000]),
            )
            conn.commit()
            return True
        except Exception as e:
            logger.error(f"写入聊天记录失败（不影响收发）: {e}")
            return False


def record_incoming(who, sender, content, msgtype=None, is_group=False):
    """收到别人发来的消息。"""
    return record(who, sender, 'in', 'group' if is_group else 'friend', content, msgtype)


def record_outgoing_self(who, sender, content, msgtype=None, ts=None):
    """账号侧发出的消息：自动判定是主人亲手发的还是 bot 代发的回显。"""
    ts = ts or time.time()
    source = 'bot' if _is_bot_echo(who, content, ts) else 'owner'
    return record(who, sender, 'out', source, content, msgtype, ts)


def record_outgoing_bot(who, content, msgtype=None):
    """bot 代发：登记回声窗口并入库（source=bot）。"""
    mark_bot_send(who, content)
    return record(who, '', 'out', 'bot', content, msgtype)


def stats():
    """各来源消息计数 + 时间跨度。"""
    out = {'total': 0, 'by_source': {}, 'first_ts': None, 'last_ts': None}
    with _lock:
        try:
            conn = _get_conn()
            if conn is None:
                return out
            cur = conn.execute("SELECT source, COUNT(*) FROM messages GROUP BY source")
            for src, cnt in cur.fetchall():
                out['by_source'][src] = cnt
                out['total'] += cnt
            cur = conn.execute("SELECT MIN(ts), MAX(ts) FROM messages")
            row = cur.fetchone()
            if row:
                out['first_ts'], out['last_ts'] = row[0], row[1]
        except Exception as e:
            logger.error(f"统计聊天记录失败: {e}")
    return out


def fetch_by_source(source, limit=2000, order='DESC'):
    """取指定来源的消息内容列表（用于风格分析）。"""
    with _lock:
        try:
            conn = _get_conn()
            if conn is None:
                return []
            # order 仅允许固定值，避免注入
            order = 'ASC' if order == 'ASC' else 'DESC'
            cur = conn.execute(
                f"SELECT content, ts FROM messages WHERE source=? AND content != ''"
                f" ORDER BY ts {order} LIMIT ?",
                (source, int(limit)),
            )
            return cur.fetchall()
        except Exception as e:
            logger.error(f"读取聊天记录失败: {e}")
            return []


def fetch_recent(limit=50):
    """最近若干条（全部来源），用于 UI 展示。"""
    with _lock:
        try:
            conn = _get_conn()
            if conn is None:
                return []
            cur = conn.execute(
                "SELECT ts, who, direction, source, content FROM messages"
                " ORDER BY ts DESC LIMIT ?",
                (int(limit),),
            )
            return cur.fetchall()
        except Exception as e:
            logger.error(f"读取最近聊天记录失败: {e}")
            return []


def clear_all():
    """清空全部聊天记录（UI 提供入口）。"""
    with _lock:
        try:
            conn = _get_conn()
            if conn is None:
                return False
            conn.execute("DELETE FROM messages")
            conn.commit()
            return True
        except Exception as e:
            logger.error(f"清空聊天记录失败: {e}")
            return False
