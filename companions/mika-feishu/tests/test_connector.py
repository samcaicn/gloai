import json
import os
import socket
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from mika_feishu.api import ApiError, MikaApi
from mika_feishu.bridge import Bridge, normalize
from mika_feishu.store import Store


def event(mid="om_1", text="查询天气", user="ou_owner", chat_type="p2p"):
    return {"event": {"sender": {"sender_type": "user", "sender_id": {"open_id": user}},
                      "message": {"message_id": mid, "chat_id": "oc_chat", "chat_type": chat_type,
                                  "message_type": "text", "create_time": str(int(time.time() * 1000)),
                                  "content": json.dumps({"text": text})}}}


class FakeApi:
    def __init__(self):
        self.writes = []
        self.status = {"task_id": "task_1", "conversation": "mcp-test", "status": "running"}
        self.error = None

    def task(self, tid):
        return dict(self.status)

    def start(self, goal, conversation=None):
        self.writes.append(("start", goal, conversation))
        if self.error:
            raise self.error
        return dict(self.status)

    def action(self, tid, action, text=None):
        self.writes.append((action, text))
        return {"accepted": True}

    safe_id = staticmethod(MikaApi.safe_id)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "state.db")
        self.api, self.sent = FakeApi(), []
        self.bridge = Bridge(self.api, self.store, self.send, ["ou_owner"])

    def send(self, *args):
        self.sent.append(args)
        return {"bot_" + str(len(self.sent)): int(time.time() * 1000) - 1}

    def tearDown(self):
        self.store.db.close()
        self.tmp.cleanup()

    def deliver(self, text="查询天气", mid="om_1"):
        self.assertTrue(self.bridge.receive(event(mid, text)))
        self.bridge.work_once()

    def test_unauthorized_and_group_never_execute(self):
        self.assertFalse(self.bridge.receive(event(user="ou_other")))
        self.assertFalse(self.bridge.receive(event(chat_type="group")))
        self.assertFalse(self.bridge.work_once())
        self.assertEqual(self.api.writes, [])

    def test_duplicate_event_after_restart_executes_once(self):
        self.deliver()
        restarted = Bridge(self.api, self.store, lambda *a: None, ["ou_owner"])
        self.assertFalse(restarted.receive(event()))
        self.assertEqual(len(self.api.writes), 1)

    def test_restart_discards_queued_events_from_revoked_users(self):
        claimed = event("claimed")
        claimed["event"]["message"]["chat_id"] = "oc_other"
        self.assertTrue(self.bridge.receive(claimed))
        self.store.pop()
        self.assertTrue(self.bridge.receive(event("pending")))
        restarted = Bridge(self.api, self.store, lambda *args: self.sent.append(args), ["ou_new"])
        restarted.recover()
        self.assertTrue(restarted.work_once())
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.sent, [])
        self.assertEqual(self.store.execute("SELECT id FROM inbox WHERE state != 'done'"), [])

    def test_lost_post_response_blocks_future_tasks(self):
        self.api.error = ApiError("timeout", uncertain=True)
        self.deliver()
        self.deliver("再试一次", "om_2")
        self.assertEqual(len(self.api.writes), 1)
        self.assertEqual(self.store.session("oc_chat:ou_owner")["uncertain"], 1)

    def test_definite_rejection_does_not_leave_uncertainty(self):
        self.api.error = ApiError("http_400", status=400)
        self.deliver()
        self.assertEqual(self.store.session("oc_chat:ou_owner")["uncertain"], 0)

    def test_crash_after_claim_is_not_replayed(self):
        self.bridge.receive(event())
        self.store.pop()  # simulate crash between durable claim and receipt persistence
        self.bridge.recover()
        self.assertFalse(self.bridge.work_once())
        self.assertEqual(self.api.writes, [])
        self.assertEqual(self.store.session("oc_chat:ou_owner")["uncertain"], 1)

    def test_questions_answered_but_phone_approvals_cannot_be_answered(self):
        self.deliver()
        self.api.status.update(status="waiting_on_phone")
        self.deliver("/answer 同意", "om_2")
        self.assertEqual(len(self.api.writes), 1)
        self.api.status.update(status="waiting_for_answer", question="哪个城市？")
        self.bridge.poll_once()
        self.deliver("上海", "om_3")
        self.assertEqual(self.api.writes[-1], ("answer", "上海"))

    def test_poll_does_not_send_the_replaced_tasks_question(self):
        peer = "oc_chat:ou_owner"
        self.store.update(peer, task_id="task_old")

        def replace_during_poll(task_id):
            self.assertEqual(task_id, "task_old")
            self.store.update(peer, task_id="task_1")
            return {"task_id": "task_old", "status": "waiting_for_answer", "question": "Old question?"}

        with patch.object(self.api, "task", side_effect=replace_during_poll):
            self.bridge.poll_once()
        self.assertEqual(self.sent, [])
        self.api.status.update(status="waiting_for_answer", question="New question?")
        self.deliver("old answer")
        self.assertEqual(self.api.writes, [])

    def test_reply_must_match_current_question_message(self):
        peer = "oc_chat:ou_owner"
        self.store.update(peer, task_id="task_old")
        self.api.status.update(task_id="task_old", status="waiting_for_answer", question="Old question?")

        self.bridge.poll_once()
        self.store.update(peer, task_id="task_1", notice="")
        self.api.status.update(task_id="task_1", question="New question?")
        self.bridge.poll_once()

        for mid, parent, answer in (("old", "bot_1", "old answer"), ("new", "bot_2", "new answer")):
            data = event(mid, answer)
            data["event"]["message"]["parent_id"] = parent
            self.assertTrue(self.bridge.receive(data))
            self.bridge.work_once()
        self.assertEqual(self.api.writes, [("answer", "new answer")])

    def test_status_can_show_the_current_question_before_an_explicit_answer(self):
        self.deliver()
        self.api.status.update(status="waiting_for_answer", question="Which city?")
        self.deliver("/answer Shanghai", "om_2")
        self.assertEqual(len(self.api.writes), 1)
        self.deliver("/status", "om_3")
        self.deliver("/answer Shanghai", "om_4")
        self.assertEqual(self.api.writes[-1], ("answer", "Shanghai"))

    def test_queued_unquoted_answers_cannot_answer_a_later_question(self):
        self.deliver()
        now = int(time.time())
        self.api.status.update(status="waiting_for_answer", question="Departure city?")
        with patch("mika_feishu.bridge.time.time", return_value=now):
            self.bridge.poll_once()
        with patch("mika_feishu.bridge.time.time", return_value=now + 1):
            self.assertTrue(self.bridge.receive(event("plain", "Shanghai")))
            self.assertTrue(self.bridge.receive(event("command", "/answer Shanghai")))

        self.api.status.update(question="Destination city?")
        with patch("mika_feishu.bridge.time.time", return_value=now + 2):
            self.bridge.poll_once()
            self.assertTrue(self.bridge.work_once())
            self.assertTrue(self.bridge.work_once())
        self.assertEqual(self.api.writes, [("start", "查询天气", None)])
        with patch("mika_feishu.bridge.time.time", return_value=now + 3):
            self.deliver("/answer Beijing", "fresh")
        self.assertEqual(self.api.writes[-1], ("answer", "Beijing"))

    def test_terminal_notifications_sent_once_and_resume_same_conversation(self):
        self.deliver()
        self.api.status.update(status="done", answer="今天晴天")
        self.bridge.poll_once()
        count = len(self.sent)
        self.bridge.poll_once()
        self.assertEqual(len(self.sent), count)
        self.deliver("明天呢", "om_2")
        self.assertEqual(self.api.writes[-1], ("start", "明天呢", "mcp-test"))

    def test_question_shown_by_status_is_not_pushed_again(self):
        self.deliver()
        self.api.status.update(status="waiting_for_answer", question="Which city?")
        self.deliver("/status", "om_2")
        shown = len(self.sent)
        self.bridge.poll_once()
        self.assertEqual(len(self.sent), shown)
        # The question is still answerable: the record above must not cost the user their answer.
        self.deliver("/answer Shanghai", "om_3")
        self.assertEqual(self.api.writes[-1], ("answer", "Shanghai"))

    def test_final_result_shown_by_status_is_not_pushed_again(self):
        self.deliver()
        self.api.status.update(status="done", answer="今天晴天")
        self.deliver("/status", "om_2")
        shown = len(self.sent)
        self.bridge.poll_once()
        self.assertEqual(len(self.sent), shown)

    def test_inflight_poll_cannot_replace_terminal_status(self):
        for old_response in (
                {"task_id": "task_1", "status": "waiting_for_answer", "question": "Old question"},
                ApiError("http_404", status=404)):
            with self.subTest(old_response=old_response):
                peer = "oc_chat:ou_owner"
                self.store.update(peer, task_id="task_1", notice="", uncertain=0)
                final = {"task_id": "task_1", "status": "done", "answer": "Final result"}
                self.sent.clear()

                def finish_during_poll(task_id):
                    self.assertEqual("task_1", task_id)
                    with patch.object(self.api, "task", return_value=final):
                        self.deliver("/status", "status-" + type(old_response).__name__)
                    if isinstance(old_response, ApiError):
                        raise old_response
                    return old_response

                with patch.object(self.api, "task", side_effect=finish_during_poll):
                    self.bridge.poll_once()
                self.assertEqual(1, len(self.sent))
                self.assertIn("Final result", self.sent[0][1])
                session = self.store.session(peer)
                self.assertTrue(session["notice"].startswith("terminal:"))
                self.assertEqual(0, session["uncertain"])

    def test_old_poll_failure_does_not_mark_replacement_task_uncertain(self):
        peer = "oc_chat:ou_owner"
        self.store.update(peer, task_id="task_old")

        def old_task_disappears(task_id):
            self.assertEqual(task_id, "task_old")
            self.store.update(peer, task_id="task_new")
            raise ApiError("http_404", status=404)

        with patch.object(self.api, "task", side_effect=old_task_disappears):
            self.bridge.poll_once()
        self.assertEqual(self.store.session(peer)["uncertain"], 0)
        self.assertEqual(self.store.session(peer)["notice"], "")
        self.assertEqual(self.sent, [])

    def test_cancel_ack_is_not_reported_as_cancelled(self):
        self.deliver()
        self.deliver("/cancel", "om_2")
        self.assertNotIn("Task cancelled", self.sent[-1][1])
        self.api.status.update(status="cancelled")
        self.bridge.poll_once()
        self.assertIn("Task cancelled", self.sent[-1][1])

    def test_pairing_is_one_time_private_and_persistent(self):
        bridge = Bridge(self.api, self.store, lambda *a: None)
        code = bridge.enable_pairing()
        self.assertFalse(bridge.receive(event(text="/pair " + code, chat_type="group")))
        self.assertTrue(bridge.receive(event(text="/pair " + code)))
        self.assertFalse(bridge.receive(event("om_other", "/pair " + code, user="ou_other")))
        self.assertEqual(Bridge(self.api, self.store, lambda *a: None).owners(), {"ou_owner"})

    def test_non_ascii_pair_code_is_rejected_like_any_other_wrong_code(self):
        bridge = Bridge(self.api, self.store, lambda *a: None)
        code = bridge.enable_pairing()
        # A wrong code typed with non-ASCII characters must be ignored exactly like an ASCII
        # wrong code. Raising here escapes receive() and runtime.incoming() re-raises on purpose,
        # so the SDK would never acknowledge the event and the connector would crash on it.
        self.assertFalse(bridge.receive(event("om_bad", "/pair 密码")))
        self.assertEqual(bridge.owners(), set())
        # The pairing window is still open, and the real code still pairs.
        self.assertTrue(bridge.receive(event("om_ok", "/pair " + code)))
        self.assertEqual(bridge.owners(), {"ou_owner"})

    def test_unknown_status_does_not_start_another_task(self):
        self.deliver()
        self.api.status["status"] = "future_status"
        self.deliver("又一件事", "om_2")
        self.assertEqual(len(self.api.writes), 1)

    def test_stale_events_ignored(self):
        data = event()
        data["event"]["message"]["create_time"] = "1"
        self.assertIsNone(normalize(data))

    def test_persisted_old_message_is_not_executed_after_restart(self):
        accepted = normalize(event())
        accepted["created_at"] = 1
        self.store.enqueue(accepted)
        self.bridge.work_once()
        self.assertEqual(self.api.writes, [])
        self.assertIn("expired", self.sent[-1][1])

    def test_real_sdk_event_serialization(self):
        import lark_oapi as lark
        from lark_oapi.api.im.v1 import P2ImMessageReceiveV1
        data = event()
        data["event"]["message"]["parent_id"] = "om_current_question"
        payload = json.loads(lark.JSON.marshal(P2ImMessageReceiveV1(data)))
        normalized = normalize(payload)
        self.assertEqual(normalized["user"], "ou_owner")
        self.assertEqual(normalized["reply_to"], "om_current_question")

    def test_private_config_permissions_and_secret_roundtrip(self):
        from mika_feishu.config import load_config, save_config
        path = Path(self.tmp.name) / "private" / "config.json"
        config = {"feishu_app_id": "cli_fake", "feishu_app_secret": "not-real",
                  "mika_url": "http://127.0.0.1:8765", "mika_token": "not-real"}
        save_config(config, path)
        if os.name != "nt":
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(load_config(path)["mika_token"], "not-real")


class ContractTests(unittest.TestCase):
    """Wire-level contract derived from official v0.1.31 RemoteTaskApi/RemoteStatus."""
    def setUp(self):
        self.calls, self.version, self.caps, self.mode = [], 1, ["tasks", "task_steer"], "ok"
        self.hosts = []
        parent = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def handle_request(self):
                parent.hosts.append(self.headers.get("Host"))
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                parent.calls.append((self.command, self.path, body, self.headers.get("Authorization")))
                if self.path == "/api/v1":
                    response = {"api_version": parent.version, "server_version": "0.1.31",
                                "capabilities": parent.caps, "limits": {"max_goal": 4000}}
                else:
                    response = {"task_id": "task_1", "status": "running", "conversation": "mcp-test"}
                if parent.mode == "redirect":
                    self.send_response(302)
                    self.send_header("Location", "/unexpected")
                    self.end_headers()
                    return
                if self.command == "POST" and parent.mode == "lost":
                    self.connection.shutdown(2)
                    self.connection.close()
                    return
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps(response).encode())
            do_GET = handle_request
            do_POST = handle_request
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.api = MikaApi(f"http://127.0.0.1:{self.server.server_port}", "test-token")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_bearer_and_versioned_task_payload(self):
        self.api.start("总结今天的消息")
        self.assertEqual(self.calls[-1], ("POST", "/api/v1/tasks", {"goal": "总结今天的消息"}, "Bearer test-token"))

    def test_future_api_version_prevents_writes(self):
        self.version = 2
        with self.assertRaises(ApiError):
            self.api.start("test")
        self.assertEqual([c[0] for c in self.calls], ["GET"])

    def test_feature_detection_prevents_unsupported_steer(self):
        self.caps = ["tasks"]
        with self.assertRaises(ApiError):
            self.api.action("task_1", "steer", "追加")
        self.assertEqual([c[0] for c in self.calls], ["GET"])

    def test_lost_response_not_retried(self):
        self.mode = "lost"
        with self.assertRaises(ApiError) as ctx:
            self.api.start("test")
        self.assertTrue(ctx.exception.uncertain)
        self.assertEqual(sum(c[0] == "POST" for c in self.calls), 1)

    def test_redirect_never_followed(self):
        self.mode = "redirect"
        with self.assertRaises(ApiError):
            self.api.check()
        self.assertEqual(len(self.calls), 1)

    def test_request_connects_to_the_address_it_validated(self):
        host = "phone.example.test"
        port = self.server.server_port
        resolve, connect = socket.getaddrinfo, socket.socket.connect
        answers = iter(("127.0.0.1", "127.0.0.1", "203.0.113.9"))
        attempted = []

        def changing_dns(name, port, *args, **kwargs):
            return resolve(next(answers) if name == host else name, port, *args, **kwargs)

        def observe_connection(sock, address):
            attempted.append(address[0])
            # Route even an incorrect address to the local fixture; never connect to it.
            return connect(sock, ("127.0.0.1", port))

        with patch.object(socket, "getaddrinfo", changing_dns), patch.object(socket.socket, "connect", observe_connection):
            MikaApi(f"http://{host}:{port}", "test-token").check()
        self.assertEqual(attempted, ["127.0.0.1"])
        self.assertEqual(self.calls[0][3], "Bearer test-token")
        self.assertEqual(self.hosts, [f"{host}:{port}"])

    def test_public_address_after_setup_is_rejected_before_connecting(self):
        port = self.server.server_port
        public = [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("203.0.113.9", port))]
        with patch.object(socket, "getaddrinfo", return_value=public), patch.object(socket.socket, "connect") as connect:
            with self.assertRaises(ApiError) as error:
                self.api.request("POST", "/tasks", {"goal": "test"})
        connect.assert_not_called()
        self.assertFalse(error.exception.uncertain)

    def test_tls_validates_the_address_it_dials_not_the_setup_one(self):
        # HTTPSConnection.connect dials through its own super(), which here resolves to
        # PrivateHTTPConnection.connect, so the checked dial must still be the one that runs
        # after setup. Reordering the bases or overriding connect would silently skip it.
        host, port = "phone.example.test", self.server.server_port
        resolve, answers = socket.getaddrinfo, iter(("127.0.0.1", "203.0.113.9"))

        def changing_dns(name, port, *args, **kwargs):
            return resolve(next(answers, "203.0.113.9") if name == host else name, port, *args, **kwargs)

        with patch.object(socket, "getaddrinfo", side_effect=changing_dns):
            api = MikaApi(f"https://{host}:{port}", "test-token")  # setup resolves a private address
        public = [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", ("203.0.113.9", port))]
        with patch.object(socket, "getaddrinfo", return_value=public), \
                patch.object(socket.socket, "connect") as connect:
            with self.assertRaises(ApiError) as error:
                api.request("GET")
        connect.assert_not_called()
        self.assertEqual("invalid_private_address", error.exception.code)
        self.assertFalse(error.exception.uncertain)

    def test_tls_keeps_original_hostname_after_address_validation(self):
        host = "phone.example.test"
        port = self.server.server_port
        resolve = socket.getaddrinfo
        handshakes = []

        def wrap(context, sock, *, server_hostname):
            handshakes.append((context.check_hostname, context.verify_mode, server_hostname))
            return sock  # The local fixture serves HTTP; verify the TLS arguments only.

        with patch.object(socket, "getaddrinfo", side_effect=lambda name, port, **kw: resolve("127.0.0.1", port, **kw)):
            with patch.object(ssl.SSLContext, "wrap_socket", wrap):
                MikaApi(f"https://{host}:{port}", "test-token").check()
        self.assertEqual(handshakes, [(True, ssl.CERT_REQUIRED, host)])
        self.assertEqual(self.hosts, [f"{host}:{port}"])

    def test_additive_fields_tolerated(self):
        info = self.api.check()
        self.assertEqual(info["server_version"], "0.1.31")


if __name__ == "__main__":
    unittest.main()
