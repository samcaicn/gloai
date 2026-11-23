import json
import os
import sqlite3
import threading
from pathlib import Path


class Store:
    def __init__(self, path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        os.chmod(path, 0o600)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS owners(id TEXT PRIMARY KEY);
          CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY, payload TEXT NOT NULL, state TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS sessions(peer TEXT PRIMARY KEY, conversation TEXT, task_id TEXT,
            uncertain INTEGER NOT NULL DEFAULT 0, notice TEXT NOT NULL DEFAULT '');
          CREATE TABLE IF NOT EXISTS questions(peer TEXT PRIMARY KEY, task_id TEXT NOT NULL,
            notice TEXT NOT NULL, message_ids TEXT NOT NULL, shown_at_ms INTEGER);
        """)
        columns = {row["name"] for row in self.db.execute("PRAGMA table_info(questions)")}
        if "shown_at_ms" not in columns:
            self.db.execute("ALTER TABLE questions ADD COLUMN shown_at_ms INTEGER")
        self.db.commit()

    def execute(self, sql, params=()):
        with self.lock:
            cursor = self.db.execute(sql, params)
            rows = cursor.fetchall()
            self.db.commit()
            return rows

    def enqueue(self, event):
        with self.lock:
            cursor = self.db.execute("INSERT OR IGNORE INTO inbox VALUES(?,?,'pending')",
                                     (event["id"], json.dumps(event, ensure_ascii=False)))
            self.db.commit()
            return cursor.rowcount == 1

    def pop(self):
        with self.lock:
            row = self.db.execute("SELECT * FROM inbox WHERE state='pending' ORDER BY rowid LIMIT 1").fetchone()
            if not row:
                return None
            self.db.execute("UPDATE inbox SET state='processing' WHERE id=?", (row["id"],))
            self.db.commit()
            return json.loads(row["payload"])

    def session(self, peer):
        self.execute("INSERT OR IGNORE INTO sessions(peer) VALUES(?)", (peer,))
        return dict(self.execute("SELECT * FROM sessions WHERE peer=?", (peer,))[0])

    def update(self, peer, **values):
        assert set(values) <= {"conversation", "task_id", "uncertain", "notice"}
        self.session(peer)
        self.execute("UPDATE sessions SET " + ",".join(k + "=?" for k in values) + " WHERE peer=?",
                     (*values.values(), peer))

    def question(self, peer, task_id, notice):
        rows = self.execute("SELECT message_ids, shown_at_ms FROM questions WHERE peer=? AND task_id=? AND notice=?",
                            (peer, task_id, notice))
        return (json.loads(rows[0]["message_ids"]), rows[0]["shown_at_ms"]) if rows else None

    def remember_question(self, peer, task_id, notice, sent):
        prior_ids, prior_shown = self.question(peer, task_id, notice) or ([], None)
        ids = list(dict.fromkeys([*prior_ids, *sent]))[-50:]
        # Use Feishu's clock for both sides. A multipart notice is shown when its last part is sent.
        shown_at = max(sent.values()) if sent and all(sent.values()) else None
        first_shown = min((t for t in (prior_shown, shown_at) if t is not None), default=None)
        self.execute("INSERT OR REPLACE INTO questions VALUES(?,?,?,?,?)",
                     (peer, task_id, notice, json.dumps(ids), first_shown))
