"""Saved phone history and trace regressions. Run with a Playwright-enabled Python."""
import threading
import unittest
from urllib.parse import parse_qs, urlsplit

from browser_desk_updates import browser_desk, mutations, snapshot, sync_playwright
from test_mika_cli import DISCOVERY
from playwright.sync_api import expect


class HistoryPhone:
    def __init__(self):
        self.enabled = True
        self.recording = False
        self.row_name = "saved request"
        self.block_run = None
        self.entered = threading.Event()
        self.release = threading.Event()
        self.tasks = [snapshot("current", "mcp-session", "Continue", "waiting_for_answer", question="Which date?", run_id="current-run"),
                      snapshot("previous", "mcp-session", "Read sources", answer="Saved result", run_id="previous-run")]

    def respond(self, method, url, body):
        parsed = urlsplit(url)
        path = parsed.path
        offset = int(parse_qs(parsed.query).get("offset", ["0"])[0])
        if path == "/api/v1":
            return 200, {**DISCOVERY, "capabilities": [*DISCOVERY["capabilities"], "history"], "history_enabled": self.enabled}, {}
        if path == "/api/v1/tasks":
            return 200, {"tasks": self.tasks}, {}
        if path.startswith("/api/v1/tasks/"):
            task = next((t for t in self.tasks if t["task_id"] == path.split("/")[4]), None)
            if task is None:
                return 404, {"error": {"code": "not_found", "message": "unknown task"}}, {}
            return 200, {**task, "changed": False}, {}
        if not self.enabled:
            return 403, {"error": {"code": "history_disabled", "message": "disabled"}}, {}
        if path == "/api/v1/history/conversations":
            return 200, {"conversations": [{"id": "phone-only", "title": "手机采集", "updated_at_ms": 20},
                                           {"id": "mcp-session", "title": "远程对话", "updated_at_ms": 30}], "next_offset": None}, {}
        parts = path.split("/")
        conversation = parts[5]
        if len(parts) == 6:
            source = self.tasks[::-1] if conversation == "mcp-session" else [
                snapshot("phone-old", conversation, "Previous phone turn", run_id="phone-old-run"),
                snapshot("phone-new", conversation, "Phone-only result", run_id="phone-new-run")]
            turns = [{**task, "task_id": "run-" + task["run_id"] if task.get("run_id") else "live-" + task["task_id"],
                      "turn_index": i, "read_only": True, "source_task_id": task["task_id"],
                      "live": task["status"] not in {"done", "failed", "cancelled"}}
                     for i, task in enumerate(source)]
            return 200, {"turns": turns, "total": len(turns), "next_offset": None}, {}
        run = parts[7]
        if len(parts) == 8:
            if run == self.block_run:
                self.entered.set()
                self.release.wait(timeout=10)
            row = {"id": "0123456789abcdef", "name": self.row_name + " " + run, "step": 1, "attempt": 1,
                   "status": "ok", "duration_ms": 120, "input": 1000, "cached": None, "uncached": None,
                   "output": 0, "difference": "input[3]", "context_change": None, "captured": True}
            return 200, {"run_id": run, "trace_available": run != "phone-old-run", "record_available": True,
                         "recording": self.recording, "trace_status": "ok", "rows": [row] if run != "phone-old-run" else [],
                         "events": [{"type": "model_reply", "step": 1, "at_ms": 1, "detail": "saved event", "truncated": False}],
                         "next_offset": 20 if offset == 0 else None}, {}
        text = "request\n<script>window.badHistory = true</script>" if offset == 0 else "second body page"
        return 200, {"text": text, "next_offset": 3000 if offset == 0 else None}, {}


@unittest.skipIf(sync_playwright is None, "Playwright is not installed")
class DeskHistoryTest(unittest.TestCase):
    def test_phone_only_history_body_paging_missing_trace_and_draft(self):
        fixture = HistoryPhone()
        with browser_desk(fixture.respond) as (page, phone):
            expect(page.locator("#thread-title")).to_have_text("远程对话")
            expect(page.locator("#compose-mode")).to_contain_text("回答追问")
            page.locator("#goal").fill("Keep this draft")
            page.get_by_role("button", name="手机采集", exact=True).click()
            expect(page.locator(".turn")).to_have_count(2)
            expect(page.locator("#goal")).to_be_disabled()
            page.locator(".turn-process").nth(1).click()
            expect(page.locator(".trace-entry")).to_contain_text("phone-new-run")
            expect(page.locator(".trace-tokens")).to_contain_text("缓存 未知")
            expect(page.locator(".trace-tokens")).to_contain_text("输出 0")
            page.get_by_role("button", name="查看请求与回复", exact=True).click()
            expect(page.locator(".trace-entry pre")).to_contain_text("<script>")
            self.assertIsNone(page.evaluate("window.badHistory"))
            page.locator(".trace-entry [data-body-page]").last.click()
            expect(page.locator(".trace-entry pre")).to_have_text("second body page")
            for width in (1200, 390, 320):
                page.set_viewport_size({"width": width, "height": 900})
                self.assertLessEqual(page.evaluate("document.documentElement.scrollWidth"), width)
            page.set_viewport_size({"width": 1200, "height": 900})
            page.locator(".turn-process").first.click()
            expect(page.locator("#process-content")).to_contain_text("可能未开启录制")
            page.locator('button[data-group="chat:mcp-session"]').click()
            expect(page.locator("#goal")).to_have_value("Keep this draft")
            self.assertEqual([], mutations(phone))

    def test_late_trace_response_cannot_replace_the_selected_turn(self):
        fixture = HistoryPhone()
        fixture.block_run = "previous-run"
        try:
            with browser_desk(fixture.respond) as (page, phone):
                expect(page.locator(".turn")).to_have_count(2)
                page.locator("#goal").fill("Still answering current turn")
                page.locator(".turn-process").first.click()
                self.assertTrue(fixture.entered.wait(timeout=3))
                page.locator(".turn-process").nth(1).click()
                expect(page.locator(".trace-entry")).to_contain_text("current-run")
                fixture.release.set()
                page.wait_for_timeout(200)
                expect(page.locator(".trace-entry")).to_contain_text("current-run")
                expect(page.locator("#goal")).to_have_value("Still answering current turn")
                self.assertEqual([], mutations(phone))
        finally:
            fixture.release.set()

    def test_recording_updates_and_revocation_clears_saved_content(self):
        fixture = HistoryPhone()
        fixture.recording = True
        with browser_desk(fixture.respond) as (page, phone):
            page.get_by_role("button", name="手机采集", exact=True).click()
            page.locator(".turn-process").nth(1).click()
            expect(page.locator("#process-content")).to_contain_text("录制中")
            fixture.row_name = "later request"
            expect(page.locator(".trace-entry")).to_contain_text("later request", timeout=12000)
            fixture.enabled = False
            expect(page.locator("#threads")).to_contain_text("允许电脑查看历史记录", timeout=12000)
            expect(page.locator(".trace-entry")).to_have_count(0)
            expect(page.get_by_role("button", name="手机采集", exact=True)).to_have_count(0)
            self.assertEqual([], mutations(phone))

    def test_queued_remote_task_is_not_duplicated_by_phone_history(self):
        fixture = HistoryPhone()
        fixture.tasks[0] = snapshot("current", "mcp-session", "Queued turn", "starting")
        with browser_desk(fixture.respond) as (page, phone):
            page.wait_for_function('() => savedTurns.has("mcp-session")')
            expect(page.locator(".turn")).to_have_count(2)
            expect(page.locator(".turn").last).to_contain_text("Queued turn")
            fixture.tasks[0] = snapshot("current", "mcp-session", "Queued turn", "running", run_id="current-run", step=1)
            expect(page.locator(".turn").last).to_contain_text("第 1 步", timeout=12000)
            expect(page.locator(".turn")).to_have_count(2)
            self.assertEqual([], mutations(phone))

    def test_cancelled_before_start_merges_by_remote_id_even_with_identical_goals(self):
        fixture = HistoryPhone()
        fixture.tasks = [snapshot(id, "mcp-session", "Same goal", status="cancelled") for id in ("new", "old")]
        def respond(method, url, body):
            if urlsplit(url).path == "/api/v1/history/conversations/mcp-session":
                turns = [{"task_id": f"turn-mcp-session-{i}", "source_task_id": task["task_id"], "turn_index": i,
                          "conversation": "mcp-session", "read_only": True, "goal": task["goal"], "status": "cancelled"}
                         for i, task in enumerate(reversed(fixture.tasks))]
                return 200, {"turns": turns, "total": 2, "next_offset": None}, {}
            return fixture.respond(method, url, body)
        with browser_desk(respond) as (page, phone):
            page.wait_for_function('() => savedTurns.has("mcp-session") && !observing')
            turns = page.locator(".turn")
            self.assertEqual(["old", "new"], turns.evaluate_all("items => items.map(item => item.dataset.id)"))
            self.assertEqual([], mutations(phone))

    def test_history_pages_keep_earlier_turns_after_refresh(self):
        fixture = HistoryPhone()
        total = 24
        def respond(method, url, body):
            parsed = urlsplit(url)
            if parsed.path == "/api/v1/history/conversations/phone-only":
                offset = int(parse_qs(parsed.query).get("offset", ["0"])[0])
                end = max(0, total - offset)
                turns = [snapshot("run-archived-" + str(i), "phone-only", "Saved request " + str(i), run_id="archived-" + str(i),
                                  turn_index=i, read_only=True) for i in range(max(0, end - 20), end)]
                return 200, {"turns": turns, "total": total, "next_offset": offset + 20 if end > 20 else None}, {}
            return fixture.respond(method, url, body)
        with browser_desk(respond) as (page, phone):
            page.get_by_role("button", name="手机采集", exact=True).click()
            expect(page.locator(".turn")).to_have_count(20)
            page.get_by_role("button", name="加载更早的轮次").click()
            expect(page.locator(".turn")).to_have_count(24)
            expect(page.locator(".turn").first).to_contain_text("Saved request 0")
            page.locator('.turn-process').first.click()
            expect(page.locator(".trace-entry")).to_contain_text("archived-0")
            page.wait_for_function('() => !observing')
            expect(page.locator(".turn")).to_have_count(24)
            # More than a full page arrives while the desk is away. Keep the open
            # turn, and fetch the gap between it and the newly visible turns.
            total = 49
            page.locator("#refresh").dispatch_event("click")
            expect(page.locator(".turn")).to_have_count(44)
            page.get_by_role("button", name="加载更早的轮次").click()
            expect(page.locator(".turn")).to_have_count(49)
            self.assertEqual([f"Saved request {i}" for i in range(49)], page.locator(".turn .request p").all_text_contents())
            expect(page.locator(".trace-entry")).to_contain_text("archived-0")
            self.assertEqual([], mutations(phone))

    def test_archived_conversation_from_later_page_is_marked_and_readable(self):
        fixture = HistoryPhone()
        fixture.tasks = []
        archived = True
        def respond(method, url, body):
            parsed = urlsplit(url)
            if parsed.path == "/api/v1/history/conversations":
                offset = int(parse_qs(parsed.query).get("offset", ["0"])[0])
                if offset == 0:
                    return 200, {"conversations": [{"id": "active", "title": "Active", "updated_at_ms": 20, "archived": False}], "next_offset": 1}, {}
                return 200, {"conversations": [{"id": "phone-only", "title": "Old chat", "updated_at_ms": 10, "archived": archived}], "next_offset": None}, {}
            return fixture.respond(method, url, body)
        with browser_desk(respond) as (page, phone):
            expect(page.locator('button[data-group="chat:active"] .archive-mark')).to_have_count(0)
            page.get_by_role("button", name="加载更多对话").click()
            old = page.locator('button[data-group="chat:phone-only"]')
            expect(old.locator(".archive-mark")).to_have_text("已归档")
            old.click()
            expect(page.locator(".turn")).to_have_count(2)
            expect(page.locator(".turn").last).to_contain_text("Phone-only result")
            self.assertEqual([], mutations(phone))

    def test_archive_mark_updates_and_coexists_with_task_status(self):
        fixture = HistoryPhone()
        archived = True
        def respond(method, url, body):
            if urlsplit(url).path == "/api/v1/history/conversations":
                return 200, {"conversations": [{"id": "mcp-session", "title": "远程对话", "updated_at_ms": 30, "archived": archived}], "next_offset": None}, {}
            return fixture.respond(method, url, body)
        with browser_desk(respond) as (page, phone):
            row = page.locator('button[data-group="chat:mcp-session"]')
            expect(row.locator(".archive-mark")).to_have_text("已归档")
            expect(row.locator("small")).to_have_count(2)
            archived = False
            page.locator("#refresh").dispatch_event("click")
            expect(row.locator(".archive-mark")).to_have_count(0)
            expect(row.locator("small")).to_have_count(1)
            self.assertEqual([], mutations(phone))

    def test_new_conversations_after_the_initially_complete_list_are_reachable(self):
        fixture = HistoryPhone()
        fixture.tasks = []
        total = 1
        def respond(method, url, body):
            parsed = urlsplit(url)
            if parsed.path == "/api/v1/history/conversations":
                offset = int(parse_qs(parsed.query).get("offset", ["0"])[0])
                ids = list(reversed(range(total)))[offset:offset + 20]
                return 200, {"conversations": [{"id": f"phone-{i}", "title": f"Chat {i}", "updated_at_ms": i} for i in ids],
                             "next_offset": offset + 20 if offset + 20 < total else None}, {}
            return fixture.respond(method, url, body)
        with browser_desk(respond) as (page, phone):
            expect(page.locator("#threads .conversation")).to_have_count(1)
            total = 26
            page.locator("#refresh").dispatch_event("click")
            more = page.get_by_role("button", name="加载更多对话")
            more.click(timeout=5000)
            names = page.locator("#threads .conversation .name")
            expect(names).to_have_count(26)
            self.assertEqual({f"Chat {i}" for i in range(total)}, set(names.all_text_contents()))
            page.locator("#refresh").dispatch_event("click")
            page.wait_for_function("() => !busy && !observing")
            expect(more).to_have_count(0)
            self.assertEqual([], mutations(phone))


if __name__ == "__main__":
    unittest.main()
