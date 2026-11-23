import hashlib
import hmac
import json
import secrets
import threading
import time

from .api import ApiError

TERMINAL = {"done", "failed", "cancelled"}
KNOWN = TERMINAL | {"starting", "running", "paused", "waiting_for_answer", "waiting_on_phone"}
HELP = """Mika phone assistant (paired users, private messages only)
Send text to start a task or answer the current question shown in Feishu.
/status Show status and results
/answer TEXT Answer a question
/steer TEXT Add instructions to a running task
/cancel Request cancellation (wait for the final phone status)
/new Start a new conversation after the current task ends
/reset checked Clear uncertain local tracking after checking the phone
/help Show commands
Handle payment confirmations, verification codes and takeovers on the phone."""


def normalize(data):
    """Accept Feishu v2 message events; ignore bots, groups, attachments and old messages."""
    event = data.get("event") or {}
    sender, msg = event.get("sender") or {}, event.get("message") or {}
    user = (sender.get("sender_id") or {}).get("open_id")
    if (sender.get("sender_type") != "user" or msg.get("chat_type") != "p2p" or
            msg.get("message_type") != "text" or not user or not msg.get("message_id") or
            not msg.get("chat_id")):
        return None
    try:
        age = time.time() - int(msg["create_time"]) / 1000
        if age > 300 or age < -60:
            return None
        text = json.loads(msg["content"])["text"].strip()
        if not isinstance(text, str) or not text or len(text) > 5000:
            return None
    except (ValueError, TypeError, KeyError, AttributeError):
        return None
    return {"id": msg["message_id"], "user": user, "chat": msg["chat_id"], "text": text,
            "created_at": int(msg["create_time"]) / 1000, "reply_to": msg.get("parent_id")}


def describe(task):
    status = task.get("status", "unknown")
    title = {"done": "Task ended", "failed": "Task failed", "cancelled": "Task cancelled",
             "waiting_for_answer": "Mika needs an answer", "waiting_on_phone": "Please handle this on the phone",
             "paused": "Task paused", "running": "Running", "starting": "Starting"}.get(status, "Unknown task status")
    detail = (task.get("answer") or task.get("question") or task.get("message") or
              task.get("progress") or task.get("reason") or "")
    result = f"{title}\nTask: {task.get('task_id', '?')}\n{detail}"
    if task.get("options"):
        result += "\nOptions: " + " / ".join(str(x) for x in task["options"])
    if status == "waiting_for_answer":
        result += "\nSend /answer TEXT or reply with text."
    elif status == "waiting_on_phone":
        result += "\nThis step cannot be approved or handled in Feishu. Use the phone."
    elif status == "done":
        result += "\n" + ("Result verified." if task.get("verified") is True else "Result not marked as verified; check important outcomes.")
        if task.get("unconfirmed"):
            result += "\nUnconfirmed: " + json.dumps(task["unconfirmed"], ensure_ascii=False)
    return result


class Bridge:
    def __init__(self, api, store, send, allowed_users=()):
        self.api, self.store, self.send = api, store, send
        self.allowed = set(allowed_users)
        self.pair_code, self.pair_deadline = None, 0
        # Do not publish a polled status while the message worker is replacing the tracked task.
        self.session_lock = threading.Lock()

    def owners(self):
        return self.allowed | {r["id"] for r in self.store.execute("SELECT id FROM owners")}

    def enable_pairing(self):
        if self.owners():
            return None
        self.pair_code, self.pair_deadline = secrets.token_urlsafe(18), time.monotonic() + 600
        return self.pair_code

    def receive(self, data):
        event = normalize(data)
        if not event:
            return False
        # Pairing is only available for ten minutes, only via private messages.
        pairing = (self.pair_code and time.monotonic() < self.pair_deadline and
                   event["text"].startswith("/pair ") and
                   # Compare bytes: compare_digest rejects non-ASCII str, and a wrong code typed in
                   # another script must be ignored like any other wrong code, not raise.
                   hmac.compare_digest(event["text"][6:].strip().encode(), self.pair_code.encode()))
        if pairing:
            with self.store.lock:
                if not self.owners():
                    self.store.execute("INSERT INTO owners VALUES(?)", (event["user"],))
                    self.pair_code = None
            event["text"] = "/help"
        if event["user"] not in self.owners():
            return False
        return self.store.enqueue(event)

    def reply(self, event, text, suffix="reply"):
        key = hashlib.sha256((event["id"] + suffix).encode()).hexdigest()
        return self.send(event["chat"], text, key)

    def can_answer(self, peer, task, event):
        if not task or task["status"] != "waiting_for_answer":
            return False
        notice = hashlib.sha256(describe(task).encode()).hexdigest()
        question = self.store.question(peer, task["task_id"], notice)
        if question is None:
            return False
        message_ids, shown_at_ms = question
        parent = event.get("reply_to")
        if parent:
            return parent in message_ids
        return shown_at_ms is not None and event["created_at"] > shown_at_ms / 1000

    def recover(self):
        # Claimed events are never replayed after a crash: execution may already have happened.
        for row in self.store.execute("SELECT * FROM inbox WHERE state='processing'"):
            event = json.loads(row["payload"])
            if event["user"] in self.owners():
                self.store.update(event["chat"] + ":" + event["user"], uncertain=1)
                self.reply(event, "The connector stopped while processing a message. Execution is uncertain; it was not replayed. Check the phone, "
                           "then send /reset checked.", "recovery")
            self.store.execute("UPDATE inbox SET state='done' WHERE id=?", (row["id"],))

    def work_once(self):
        event = self.store.pop()
        if not event:
            return False
        try:
            if event["user"] not in self.owners():
                return True
            # Pending events may have been persisted before a long disconnect.
            if time.time() - event.get("created_at", 0) > 300:
                self.reply(event, "This queued message expired; no phone action was submitted.", "expired")
            else:
                with self.session_lock:
                    self.handle(event)
        except ApiError as exc:
            if exc.uncertain:
                self.store.update(event["chat"] + ":" + event["user"], uncertain=1)
                text = "The phone may have accepted the request. It was not retried. Check the phone or task desk before proceeding."
            else:
                text = f"Mika request did not complete ({exc.code}). Check connectivity, version and phone task status."
            self.reply(event, text, "error")
        except Exception:
            # Unknown failures also fail closed. Never include SDK secrets/payload in logs.
            self.store.update(event["chat"] + ":" + event["user"], uncertain=1)
            raise
        finally:
            self.store.execute("UPDATE inbox SET state='done' WHERE id=?", (event["id"],))
        return True

    def handle(self, e):
        peer, text = e["chat"] + ":" + e["user"], e["text"]
        session = self.store.session(peer)
        command, _, arg = text.partition(" ")
        if command == "/help":
            return self.reply(e, HELP)
        if command == "/reset":
            if arg != "checked":
                return self.reply(e, "This clears local tracking, not phone tasks. Check the phone first, then send /reset checked.")
            self.store.update(peer, task_id=None, conversation=None, uncertain=0, notice="")
            return self.reply(e, "Local tracking cleared. Phone tasks were not cancelled; do not duplicate a task that is still running.")
        if command == "/status":
            if not session["task_id"]:
                return self.reply(e, "No known task. " + ("The last write is uncertain; check the phone." if session["uncertain"] else ""))
            task = self.api.task(session["task_id"])
            text = describe(task)
            notice = hashlib.sha256(text.encode()).hexdigest()
            sent = self.reply(e, text)
            if task["status"] == "waiting_for_answer":
                self.store.remember_question(peer, task["task_id"], notice, sent)
            # The poller sends each state once and records it here. This reply shows the same text,
            # so record it the same way; otherwise the user who just read the state is pushed the
            # identical message again on the next poll.
            self.store.update(peer, notice="terminal:" + notice if task["status"] in TERMINAL else notice)
            return
        if session["uncertain"]:
            return self.reply(e, "An operation is uncertain. New writes are blocked. Check the phone or task desk, then send /reset checked.")
        task = self.api.task(session["task_id"]) if session["task_id"] else None
        if task and task.get("status") not in KNOWN:
            raise ApiError("unknown_task_status")
        active = task and task["status"] not in TERMINAL
        if command == "/new":
            if active:
                return self.reply(e, "A task is still active. Wait or use /cancel; no new conversation was created.")
            self.store.update(peer, conversation=None, task_id=None, notice="")
            return self.reply(e, "Your next message will create a new conversation.")
        if command in {"/answer", "/steer", "/cancel"}:
            if not active:
                return self.reply(e, "There is no active task.")
            action = command[1:]
            if action == "answer" and task["status"] != "waiting_for_answer":
                return self.reply(e, "There is no remotely answerable question. Use the phone for confirmations and takeovers.")
            if action == "answer" and not self.can_answer(peer, task, e):
                return self.reply(e, "This reply does not match the current question. Use /status to see it before answering.")
            if action == "steer" and task["status"] != "running":
                return self.reply(e, "Only a running task accepts additional instructions.")
            result = self.api.action(task["task_id"], action, None if action == "cancel" else arg)
            if not result.get("accepted"):
                return self.reply(e, "The request was not accepted. Check /status.")
            return self.reply(e, "Request accepted. Check /status or the final notification for the actual outcome.")
        if command.startswith("/"):
            return self.reply(e, "Unknown command. Send /help.")
        if (e.get("reply_to") or active and task["status"] == "waiting_for_answer") and not self.can_answer(peer, task, e):
            return self.reply(e, "This reply does not match the current question. Use /status to see it before answering.")
        if active:
            if task["status"] == "waiting_for_answer":
                self.api.action(task["task_id"], "answer", text)
                return self.reply(e, "Answer submitted.")
            return self.reply(e, "A task is already active. Use /steer TEXT or /status; no duplicate task was created.")
        # Persist intent before POST. A crash or malformed success response leaves the barrier set.
        self.store.update(peer, uncertain=1)
        try:
            result = self.api.start(text, session["conversation"])
        except ApiError as exc:
            if not exc.uncertain:
                self.store.update(peer, uncertain=0)
            raise
        if not result.get("task_id") or not result.get("conversation") or result.get("status") not in KNOWN:
            raise ApiError("incompatible_task_response", uncertain=True)
        self.api.safe_id(result["task_id"])
        self.store.update(peer, task_id=result["task_id"], conversation=result["conversation"], uncertain=0, notice="")
        self.reply(e, "Task submitted to the phone.\nTask: " + result["task_id"] + "\nUse /status to check or /cancel to request cancellation.")

    def poll_once(self):
        for row in self.store.execute("SELECT * FROM sessions WHERE task_id IS NOT NULL"):
            s = dict(row)
            if s["notice"].startswith("terminal:"):
                continue
            chat, user = s["peer"].split(":", 1)
            if user not in self.owners():
                continue
            try:
                task = self.api.task(s["task_id"])
                status = task.get("status")
                if status not in KNOWN:
                    raise ApiError("unknown_task_status")
                if status in {"starting", "running"}:
                    continue  # Progress is available on demand; avoid flooding Feishu.
                with self.session_lock:
                    current = self.store.session(s["peer"])
                    if current["task_id"] != s["task_id"] or current["notice"].startswith("terminal:"):
                        continue
                    text = describe(task)
                    notice = hashlib.sha256(text.encode()).hexdigest()
                    saved_notice = "terminal:" + notice if status in TERMINAL else notice
                    if saved_notice == current["notice"] and (status != "waiting_for_answer" or
                            self.store.question(s["peer"], s["task_id"], notice) is not None):
                        continue
                    sent = self.send(chat, text, hashlib.sha256((s["task_id"] + notice).encode()).hexdigest())
                    if status == "waiting_for_answer":
                        self.store.remember_question(s["peer"], s["task_id"], notice, sent)
                    self.store.execute("UPDATE sessions SET notice=? WHERE peer=? AND task_id=?",
                                       (saved_notice, s["peer"], s["task_id"]))
            except ApiError as exc:
                if exc.status == 404 or exc.code.startswith("incompatible") or exc.code == "unknown_task_status":
                    with self.session_lock:
                        current = self.store.session(s["peer"])
                        if current["task_id"] != s["task_id"] or current["notice"].startswith("terminal:"):
                            continue
                        notice = "error:" + exc.code
                        if notice != current["notice"]:
                            self.send(chat, "Cannot track this task (" + exc.code + "). The phone may have restarted or its API changed. No task was resubmitted. Check the phone.",
                                      hashlib.sha256((s["task_id"] + notice).encode()).hexdigest())
                        self.store.execute("UPDATE sessions SET uncertain=1, notice=? WHERE peer=? AND task_id=?",
                                           (notice, s["peer"], s["task_id"]))
                # Transient read failures are retried on the next poll; never repeat writes.
