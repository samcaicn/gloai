"""Optional browser regression, outside the default test run (its name does not match test*.py).

Run it directly after `pip install playwright && playwright install chromium`:
    python3 tools/mika/tests/browser_desk_updates.py
It skips when Playwright or its bundled Chromium is not installed.
"""

import copy
from contextlib import contextmanager
from pathlib import Path
import tempfile
import threading
import unittest

try:
    from playwright.sync_api import Error as PlaywrightError, expect, sync_playwright
except ImportError:
    sync_playwright = None

from test_mika_cli import api, DISCOVERY, SNAPSHOT, TOKEN
from test_mika_desk import desk


@contextmanager
def browser_desk(respond, locale="zh-CN"):
    with tempfile.TemporaryDirectory() as directory, api(respond) as phone, sync_playwright() as pw:
        token = Path(directory) / "credential"
        token.write_text(TOKEN)
        with desk("--url", f"http://127.0.0.1:{phone.server_port}", "--token-file", str(token)) as url:
            try:
                browser = pw.chromium.launch()
            except PlaywrightError as exc:
                raise unittest.SkipTest(f"Playwright's Chromium is unavailable: {exc}")
            try:
                # The assertions read the Chinese strings; test_mika_desk checks both languages exist.
                page = browser.new_context(locale=locale).new_page()
                page.goto(url.geturl())
                yield page, phone
            finally:
                browser.close()


def snapshot(id, conversation, goal, status="done", **fields):
    return {**SNAPSHOT, "task_id": id, "conversation": conversation, "goal": goal, "status": status, **fields}


def mutations(phone):
    return [
        (path, body) for method, path, body in phone.calls if method == "POST" and not path.endswith("/wait")
    ]


def read_tasks(tasks, path):
    if path.startswith("/api/v1/tasks?"):
        return 200, {"tasks": copy.deepcopy(tasks)}, {}
    if path.endswith("/wait"):
        found = next((t for t in tasks if t["task_id"] == path.split("/")[-2]), None)
        return (
            (200, {**found, "changed": False}, {}) if found else (404, {"error": {"code": "not_found"}}, {})
        )


@unittest.skipIf(sync_playwright is None, "Playwright is not installed")
class DeskUpdatesTest(unittest.TestCase):
    def test_lost_submission_survives_reload_and_recovers_without_a_second_task(self):
        for location in ("recent", "older", "missing"):
            with self.subTest(location=location):
                accepted = location != "missing"
                requests, tasks = [], []
                def respond(method, path, body):
                    if path == "/api/v1":
                        return 200, {**DISCOVERY, "capabilities": ["tasks", "task_recovery"]}, {}
                    if method == "POST" and path == "/api/v1/tasks":
                        requests.append(body)
                        if accepted or len(requests) > 1:
                            tasks.append(snapshot(body["request_id"], "mcp-qa", body["goal"], answer="Recovered"))
                        return "drop" if len(requests) == 1 else (201, tasks[0], {})
                    if requests and path == "/api/v1/tasks/" + requests[0]["request_id"]:
                        return (200, tasks[0], {}) if tasks else (404, {"error": {"code": "not_found"}}, {})
                    return read_tasks([] if location == "older" else tasks, path)

                with browser_desk(respond) as (page, _):
                    expect(page.locator("#connection-line")).to_have_text("在线")
                    page.locator("#goal").fill("核对本周清单")
                    page.locator("#submit-goal").click()
                    expect(page.locator("#pending-run")).to_be_visible()
                    page.reload()
                    if location != "recent":
                        expect(page.locator("#lookup-run")).to_be_enabled()
                        expect(page.locator("#pending-label")).to_contain_text(requests[0]["request_id"])
                        page.locator("#lookup-run").click()
                    if not accepted:
                        expect(page.locator("#notice")).to_contain_text("暂未查到")
                        page.locator("#retry-run").click()
                    expect(page.locator(".answer")).to_have_text("Recovered")
                    expect(page.locator("#pending-run")).to_be_hidden()
                    self.assertEqual(1 if accepted else 2, len(requests))
                    self.assertTrue(all(r == requests[0] for r in requests))
                    self.assertEqual(1, len(tasks))

    def test_grouping_continuation_fresh_chat_and_independent_process_target(self):
        conv = "mcp-first"
        tasks = [
            snapshot("c", "mcp-other", "Same title"),
            snapshot("b", conv, "Follow up", answer="Second result"),
            snapshot(
                "a",
                conv,
                "Same title\n" + "original request " * 20,
                answer="First result",
                verified=False,
                verification_note="Missing source",
            ),
        ]
        legacy = {
            "task_id": "legacy",
            "status": "done",
            "goal": "Same title",
            "answer": "Separate old result",
        }
        tasks.append(legacy)

        def respond(method, path, body):
            if method == "POST" and path == "/api/v1/tasks":
                task = snapshot(
                    "new" + str(len(tasks)),
                    body.get("conversation") or "mcp-fresh",
                    body["goal"],
                    answer="New result",
                )
                tasks.insert(0, task)
                return 201, task, {}
            return read_tasks(tasks, path)

        with browser_desk(respond) as (page, phone):
            expect(page.locator(".conversation")).to_have_count(3)
            page.locator('[data-group="task:legacy"]').click()
            expect(page.locator(".answer")).to_have_text("Separate old result")
            expect(page.locator("#goal")).to_be_disabled()
            page.locator('[data-group="chat:mcp-first"]').click()
            expect(page.locator(".turn")).to_have_count(2)
            self.assertEqual(
                ["a", "b"], page.locator(".turn").evaluate_all("rows => rows.map(r => r.dataset.id)")
            )
            expect(page.locator("#process-panel")).to_be_hidden()
            page.locator("#goal").fill("continue the first conversation")
            page.locator("[data-action=process][data-task=a]").click()
            sidebar = page.locator("#process-panel")
            expect(sidebar).to_have_count(1)
            expect(sidebar).to_be_hidden()
            earlier = page.locator('.turn[data-id="a"]')
            inline = earlier.locator("#process-content")
            expect(inline).to_be_visible()
            expect(inline).to_be_in_viewport()
            expect(inline).to_contain_text("Missing source")
            expect(inline).to_contain_text("手机未提供这轮的历史步骤")
            expect(page.locator("#thread")).to_contain_text("Second result")
            expect(page.locator("#goal")).to_have_value("continue the first conversation")
            earlier.get_by_role("button", name="展开要求").click()
            expect(inline).to_be_visible()
            newer = page.locator('.turn[data-id="b"]')
            newer.get_by_role("button", name="查看过程", exact=True).click()
            expect(inline).to_be_hidden()
            expect(newer.locator("#process-content")).to_be_visible()
            expect(newer.locator("#process-content")).not_to_contain_text("Missing source")
            expect(earlier.locator(".turn-process")).to_have_attribute("aria-expanded", "false")
            newer.get_by_role("button", name="收起过程", exact=True).click()
            expect(newer.locator("#process-content")).to_be_hidden()
            expect(sidebar).to_be_hidden()
            page.locator("#goal").press("Enter")
            expect(page.locator(".turn")).to_have_count(3)
            page.locator("#new-chat").click()
            expect(page.locator("#goal")).to_have_value("")
            page.locator("#goal").fill("brand new")
            page.locator("#goal").press("Shift+Enter")
            expect(page.locator("#goal")).to_have_value("brand new\n")
            page.locator("#submit-goal").click()
            expect(page.locator("#thread-title")).to_have_text("brand new")
            self.assertEqual(
                [
                    ("/api/v1/tasks", {"goal": "continue the first conversation", "conversation": conv}),
                    ("/api/v1/tasks", {"goal": "brand new"}),
                ],
                mutations(phone),
            )

    def test_phone_changes_arrive_without_stealing_a_draft_or_rewriting_history(self):
        task = snapshot("one", "mcp-live", "Live", "running", step=1, progress="Read folder")
        tasks = [task]
        changed = threading.Condition()

        def update(**fields):
            with changed:
                task.update(fields)
                changed.notify_all()

        def respond(method, path, body):
            with changed:
                if path.endswith("/wait"):
                    status = task["status"]
                    changed.wait_for(lambda: task["status"] != status, timeout=body["timeout_s"])
                return read_tasks(tasks, path)

        with browser_desk(respond) as (page, phone):
            expect(page.locator(".answer")).to_have_text("Read folder")
            page.locator("#new-chat").click()
            page.locator("#goal").fill("draft for a new chat")
            page.locator(".conversation").click()
            page.locator("#toggle-process").click()
            field = page.locator("#goal")
            field.fill("draft for steering")
            update(step=2, progress="Read receipt")
            expect(page.locator(".events")).to_contain_text("Read receipt", timeout=9500)
            expect(page.locator(".events li")).to_have_count(2)
            expect(field).to_have_value("draft for steering")
            expect(field).to_be_focused()
            page.locator(".event-choice").first.click()
            expect(page.locator(".event-reading")).to_contain_text("Read folder")
            expect(page.locator(".answer")).to_have_text("Read receipt")
            expect(field).to_have_value("draft for steering")
            update(step=3, progress="Compared receipts")
            expect(page.locator(".events li")).to_have_count(3, timeout=9500)
            expect(page.locator(".event-choice").first).to_be_focused()
            expect(page.locator(".event-reading")).to_contain_text("Read folder")
            update(status="waiting_for_answer", question="Which receipt?", options=["Green"])
            expect(page.locator(".answer")).to_have_text("Which receipt?", timeout=9500)
            expect(field).to_have_value("")
            field.fill("pending answer")
            with changed:
                tasks.insert(0, snapshot("other", "mcp-other", "External task"))
            expect(page.locator("#threads")).to_contain_text("External task", timeout=12000)
            expect(field).to_have_value("pending answer")
            update(status="waiting_on_phone", message="On phone")
            expect(field).to_be_disabled(timeout=9500)
            expect(page.locator("#options button")).to_have_count(0)
            update(status="done", answer="Result")
            expect(page.locator(".answer")).to_have_text("Result", timeout=9500)
            expect(page.locator(".turn #process-content")).to_contain_text("Read folder")
            page.locator("#new-chat").click()
            expect(field).to_have_value("draft for a new chat")
            self.assertEqual([], mutations(phone))

    def test_answer_steer_and_cancel_target_the_existing_run(self):
        task = snapshot(
            "a", "mcp-one", "A note", "waiting_for_answer", question="Which note?", options=["Monday"]
        )

        def respond(method, path, body):
            if path.endswith("/answer"):
                task.update(status="running", step=2, progress="Reading")
                return 200, {"task_id": "a", "accepted": True}, {}
            if path.endswith("/steer"):
                task.update(steers=[{"message_id": "s", "state": "joined"}])
                return 202, {"task_id": "a", "message_id": "s", "accepted": True}, {}
            if path.endswith("/cancel"):
                return 202, {"task_id": "a", "accepted": True}, {}
            return read_tasks([task], path)

        with browser_desk(respond) as (page, phone):
            page.get_by_role("button", name="Monday", exact=True).click()
            expect(page.locator("#goal")).to_have_value("Monday")
            self.assertEqual([], mutations(phone))
            page.get_by_role("button", name="发送回答", exact=True).click()
            expect(page.locator("#compose-mode")).to_contain_text("追加要求")
            page.locator("#goal").fill("only the first page")
            page.locator("#goal").press("Enter")
            expect(page.locator(".extra-input")).to_have_count(2)
            page.locator("#toggle-process").click()
            expect(page.locator(".turn #process-content")).to_contain_text("已加入任务上下文")
            page.once("dialog", lambda d: d.accept())
            page.get_by_role("button", name="取消本轮").click()
            expect(page.locator("#notice")).to_contain_text("已送出取消请求")
            expect(page.locator(".state[data-tone]")).to_have_text("进行中")
            task.update(status="cancelled")
            page.locator("#connection-details summary").click()
            page.locator("#refresh").click()
            expect(page.locator(".state[data-tone]")).to_have_text("已取消")
            self.assertEqual(
                [
                    ("/api/v1/tasks/a/answer", {"text": "Monday"}),
                    ("/api/v1/tasks/a/steer", {"text": "only the first page"}),
                    ("/api/v1/tasks/a/cancel", {}),
                ],
                mutations(phone),
            )

    def test_process_expands_in_the_turn_at_every_width_and_leaves_the_side_panel_independent(self):
        task = snapshot("t", "mcp-one", "Read a receipt", answer="17:40")
        with browser_desk(lambda m, p, b: read_tasks([task], p), locale="en-US") as (page, _):
            expect(page.locator(".state")).to_have_text("Result returned")
            self.assertEqual("en", page.locator("html").get_attribute("lang"))
            sidebar = page.locator("#process-panel")
            expect(sidebar).to_have_count(1)
            expect(sidebar).to_be_hidden()
            turn = page.locator('.turn[data-id="t"]')
            process = turn.locator("#process-content")
            field = page.locator("#goal")
            field.fill("an unsent follow-up")
            colors = []
            for width, scheme in [(1200, "light"), (869, "dark"), (700, "light"), (320, "dark")]:
                page.set_viewport_size({"width": width, "height": 720})
                page.emulate_media(color_scheme=scheme)
                before = page.locator("#thread").bounding_box()
                page.locator("#toggle-process").click()
                expect(process).to_be_in_viewport()
                expect(turn.locator(".turn-process")).to_have_attribute("aria-expanded", "true")
                expect(sidebar).to_be_hidden()
                expect(field).to_be_in_viewport()
                expect(field).to_have_value("an unsent follow-up")
                thread = page.locator("#thread").bounding_box()
                self.assertAlmostEqual(before["width"], thread["width"], delta=1)
                self.assertGreaterEqual(process.bounding_box()["y"], turn.locator(".turn-process").bounding_box()["y"])
                self.assertLess(process.bounding_box()["y"], turn.locator(".answer").bounding_box()["y"])
                self.assertEqual(width, page.evaluate("document.documentElement.scrollWidth"))
                colors.append(page.locator("#main").evaluate("e=>getComputedStyle(e).backgroundColor"))
                field.focus()
                page.keyboard.press("Escape")
                expect(process).to_be_hidden()
                expect(field).to_be_focused()
            self.assertNotEqual(colors[0], colors[1])
            self.assertEqual(colors[0], colors[2])
            # A future independent sidebar surface can be visible while a turn is inspected.
            sidebar.evaluate("panel => { panel.hidden = false }")
            sidebar_position = sidebar.bounding_box()
            turn.get_by_role("button", name="View process").click()
            expect(process).to_be_visible()
            self.assertEqual(sidebar_position, sidebar.bounding_box())
            self.assertGreaterEqual(sidebar_position["x"], page.locator("#thread").bounding_box()["width"] - 1)
            turn.get_by_role("button", name="Hide process").click()
            expect(sidebar).to_be_visible()
            sidebar.evaluate("panel => { panel.hidden = true }")
            expect(field).to_have_value("an unsent follow-up")
            page.locator("#open-nav").press("Enter")
            expect(page.locator('.conversation[aria-current="true"]')).to_be_focused()
            page.keyboard.press("Escape")
            expect(page.locator("#open-nav")).to_be_focused()

    def test_connection_disclosure_offline_recovery_and_draft(self):
        available = True
        task = snapshot("t", "mcp-one", "Existing", answer="Available")

        def respond(method, path, body):
            if not available:
                return 503, {"error": {"code": "connection", "message": "disconnected"}}, {}
            return read_tasks([task], path)

        with browser_desk(respond) as (page, _):
            expect(page.locator(".answer")).to_have_text("Available")
            field = page.locator("#goal")
            field.fill("do not discard")
            details = page.locator("#connection-details summary")
            details.focus()
            details.press("Enter")
            expect(page.locator("#target")).to_be_visible()
            page.locator("#refresh").click()
            expect(page.locator("#refresh")).to_be_enabled()
            expect(page.locator("#target")).to_be_visible()
            details.press("Enter")
            expect(page.locator("#target")).to_be_hidden()
            available = False
            expect(page.locator("#error")).to_be_visible(timeout=14000)
            expect(page.locator("#target")).to_be_visible()
            expect(page.locator("#submit-goal")).to_be_disabled()
            expect(field).to_have_value("do not discard")
            available = True
            page.locator("#refresh").click()
            expect(page.locator("#target")).to_be_hidden()
            expect(page.locator("#error")).to_be_hidden()
            expect(field).to_have_value("do not discard")
            logo = page.locator(".brand img")
            self.assertGreater(logo.evaluate("async e=>{await e.decode();return e.naturalWidth}"), 0)

    def test_language_override_keeps_draft_and_survives_a_reload(self):
        task = snapshot("t", "mcp-example", "Example", answer="Result")
        with browser_desk(lambda m, p, b: read_tasks([task], p), locale="en-US") as (page, phone):
            expect(page.locator("#toggle-process")).to_have_text("View process")
            page.locator("#goal").fill("draft")
            page.locator("#connection-details summary").click()
            page.locator("#language").select_option("zh")
            expect(page.locator("#toggle-process")).to_have_text("查看过程")
            expect(page.locator("#goal")).to_have_value("draft")
            page.reload()
            expect(page.locator("#toggle-process")).to_have_text("查看过程")
            self.assertEqual([], mutations(phone))

    def test_switching_language_does_not_add_a_phone_update(self):
        task = snapshot("live", "mcp-example", "Example", "running", step=1)
        with browser_desk(lambda method, path, body: read_tasks([task], path)) as (page, _):
            page.locator("#toggle-process").click()
            entries = page.locator(".events li")
            expect(entries).to_have_count(1)
            page.locator("#connection-details summary").click()
            page.locator("#language").select_option("en")
            with page.expect_response(
                lambda r: r.url.endswith("/desk") and r.request.post_data_json["action"] == "overview"
            ):
                page.locator("#refresh").click()
            expect(page.locator("#refresh")).to_be_enabled()
            expect(entries).to_have_count(1, timeout=1500)

    def test_late_read_cannot_undo_a_write_and_a_lost_receipt_is_not_retried(self):
        tasks = [dict(SNAPSHOT)]
        watched, held, release = threading.Event(), threading.Event(), threading.Event()

        def respond(method, path, body):
            if path.endswith("/wait"):
                watched.set()
                return 200, {**SNAPSHOT, "changed": False}, {}
            if path.startswith("/api/v1/tasks?"):
                old = copy.deepcopy(tasks)
                if watched.is_set() and not held.is_set():
                    held.set()
                    release.wait(timeout=15)
                return 200, {"tasks": old}, {}
            if method == "POST" and path == "/api/v1/tasks":
                new = snapshot("t" + str(len(tasks) + 1), "mcp-" + str(len(tasks)), body["goal"], "running")
                tasks.insert(0, new)
                return "drop" if body["goal"] == "uncertain" else (201, new, {})

        with browser_desk(respond) as (page, phone):
            try:
                self.assertTrue(held.wait(timeout=8))
                page.locator("#new-chat").click()
                page.locator("#goal").fill("new task")
                page.locator("#submit-goal").click()
                expect(page.locator("#thread-title")).to_have_text("new task")
                page.evaluate("""() => {
                    const list = document.getElementById('threads');
                    new MutationObserver(() => {if (!list.innerText.includes('new task')) list.dataset.rolledBack='true';})
                      .observe(list, {childList:true});
                }""")
                with page.expect_response(
                    lambda r: r.url.endswith("/desk") and r.request.post_data_json["action"] == "watch"
                ):
                    release.set()
                expect(page.locator("#threads")).to_contain_text("new task")
                self.assertIsNone(page.locator("#threads").get_attribute("data-rolled-back"))
                page.locator("#new-chat").click()
                page.locator("#goal").fill("uncertain")
                page.locator("#submit-goal").click()
                expect(page.locator("#error")).to_contain_text("操作可能已到达手机")
                expect(page.locator("#threads")).to_contain_text("uncertain", timeout=12000)
                expect(page.locator("#error")).to_contain_text("操作可能已到达手机")
                self.assertEqual(["new task", "uncertain"], [b["goal"] for _, b in mutations(phone)])
            finally:
                release.set()


if __name__ == "__main__":
    unittest.main()
