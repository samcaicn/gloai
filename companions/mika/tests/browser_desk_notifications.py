"""Browser regressions for the optional task notifications. Run directly with Playwright Chromium."""
import time
import unittest

from browser_desk_updates import browser_desk, expect, mutations, read_tasks, snapshot, sync_playwright


# Only the system notification surface is substituted. The desk HTTP service,
# scheduler, permissions UI, storage events and cross-tab locks run normally.
NOTIFICATIONS = """
window.notices = [];
window.Notification = class {
  static permission = "default";
  static async requestPermission() { return this.permission = "granted"; }
  constructor(title, options) { this.title = title; this.body = options.body; notices.push(this); }
  close() { this.onclose?.(); }
};
"""


def prepare(page):
    page.context.add_init_script(NOTIFICATIONS)
    page.reload()
    expect(page.locator("#connection-line")).to_have_text("在线")
    page.locator("#connection-details summary").click()
    return page.locator("#notifications")


def hide(page):
    # Headless Chromium keeps all tabs visible. Exercise the Page Visibility
    # event deterministically on headless CI as well as on a desktop.
    page.evaluate("""() => {
        Object.defineProperty(document, "hidden", {value:true, configurable:true});
        document.dispatchEvent(new Event("visibilitychange"));
    }""")


@unittest.skipIf(sync_playwright is None, "Playwright is not installed")
class DeskNotificationsTest(unittest.TestCase):
    def test_quiet_transitions_background_click_and_draft(self):
        target = snapshot("first", "multi", "First request", "running")
        sibling = snapshot("second", "multi", "Next request", "waiting_for_answer", question="Another question")
        tasks = [snapshot("old", "elsewhere", "Other conversation"), sibling, target]
        with browser_desk(lambda _, path, __: read_tasks(tasks, path)) as (page, phone):
            toggle = prepare(page)
            expect(toggle).not_to_be_checked()
            toggle.check()
            expect(toggle).to_be_checked()
            page.locator("#connection-details summary").click()
            page.locator('[data-group="chat:elsewhere"]').click()
            page.locator("#goal").fill("keep this draft")
            hide(page)

            def change(count, **fields):
                with page.expect_response(lambda r: r.url.endswith("/desk") and r.request.post_data_json["action"] == "watch",
                                          timeout=12000):
                    target.update(fields)
                if count:
                    page.wait_for_function("count => notices.length >= count", arg=count)
                self.assertEqual(count, page.evaluate("notices.length"))

            change(0, step=4, progress="ordinary progress")
            change(1, status="waiting_for_answer", question="Which file?")
            change(1, progress="unchanged question")
            page.evaluate("notices[0].onclick()")
            row = page.locator('.turn[data-id="first"]')
            expect(row).to_be_focused()
            expect(row).to_be_in_viewport()
            expect(page.locator("#compose-mode")).to_contain_text("第 1 轮")
            expect(page.locator("#goal")).to_have_attribute("placeholder", "回答 Mika 的问题…")
            page.locator('[data-group="chat:elsewhere"]').click()
            expect(page.locator("#goal")).to_have_value("keep this draft")

            change(2, question="Which folder?")
            change(3, status="waiting_on_phone", waiting_for="confirmation", app="Example")
            change(3, step=5, progress="still waiting")
            change(4, status="done", answer="Complete")
            # A completion for an older sibling must still open that turn.
            page.evaluate("notices.at(-1).onclick()")
            expect(row).to_be_focused()
            expect(row).to_contain_text("Complete")
            new_task = snapshot("third", "third", "New task", "failed", reason="Unavailable")
            tasks.insert(0, new_task)
            page.wait_for_function("() => notices.length === 5")
            self.assertEqual(["Mika · 等你回答"] * 2 + ["Mika · 需手机接管", "Mika · 已返回结果", "Mika · 未能完成"],
                             page.evaluate("notices.map(n => n.title)"))
            sibling.update(status="cancelled")
            page.locator("#connection-details summary").click()
            page.locator("#refresh").click()
            expect(page.locator('.turn[data-id="second"]')).to_contain_text("已取消")
            self.assertEqual(5, page.evaluate("notices.length"))
            self.assertEqual([], mutations(phone))

    def test_handoff_disable_and_fresh_start_after_all_tabs_close(self):
        task = snapshot("job", "chat", "Desk task", "running")
        tasks = [task]
        with browser_desk(lambda _, path, __: read_tasks(tasks, path)) as (first, phone):
            toggle = prepare(first)
            launch = first.evaluate("location.origin + '/#' + sessionStorage.deskSession")
            toggle.check()
            second = first.context.new_page()
            second.goto(launch)
            second.evaluate("Notification.permission = 'granted'; window.dispatchEvent(new Event('focus'))")
            second.wait_for_function("async () => (await navigator.locks.query()).pending.length === 1")
            hide(first)
            hide(second)
            task.update(status="waiting_for_answer", question="A question")
            first.wait_for_function("() => notices.length === 1")
            self.assertEqual(0, second.evaluate("notices.length"))
            # A change made while the previous owner is closing belongs to its successor.
            first.close()
            task.update(status="waiting_on_phone", waiting_for="hand_to_user")
            second.wait_for_function("() => notices.some(n => n.title.endsWith('需手机接管'))")
            self.assertEqual(1, second.evaluate("notices.length"))
            second.locator("#connection-details summary").click()
            second.locator("#notifications").uncheck()
            second.wait_for_function("async () => !(await navigator.locks.query()).held.length")
            task.update(status="failed")
            second.locator("#refresh").click()
            expect(second.locator(".turn")).to_contain_text("未能完成")
            self.assertEqual(1, second.evaluate("notices.length"))
            task = snapshot("next", "chat", "Next turn", "running")
            tasks.insert(0, task)
            with second.expect_response(lambda r: r.url.endswith("/desk") and r.request.post_data_json["action"] == "watch") as watch:
                second.locator("#notifications").check()
            watch.value.body()
            context = second.context
            second.close()
            after_close = len(phone.calls)
            time.sleep(1.1)
            self.assertEqual(after_close, len(phone.calls))
            task.update(status="done")
            third = context.new_page()
            third.goto(launch)
            third.evaluate("Notification.permission = 'granted'; window.dispatchEvent(new Event('focus'))")
            expect(third.locator('.turn[data-id="next"]')).to_contain_text("已返回结果")
            third.wait_for_function("async () => (await navigator.locks.query()).held.length === 1")
            self.assertEqual(0, third.evaluate("notices.length"))
            self.assertEqual([], mutations(phone))

    def test_permission_denied_dismissed_and_unsupported(self):
        with browser_desk(lambda _, path, __: read_tasks([], path)) as (page, _):
            toggle = prepare(page)
            page.evaluate("Notification.requestPermission = async () => 'default'")
            toggle.click()
            expect(toggle).not_to_be_checked()
            expect(toggle).to_be_enabled()
            page.evaluate("Notification.requestPermission = async () => Notification.permission = 'denied'")
            toggle.click()
            expect(toggle).to_be_disabled()
            expect(page.locator("#notification-hint")).to_contain_text("网站设置")
            page.add_init_script("delete window.Notification")
            page.reload()
            expect(page.locator("#notification-hint")).to_have_text("此浏览器无法发送任务台提醒。")
            expect(toggle).to_be_disabled()
            self.assertFalse(page.evaluate("!!localStorage.getItem('mikaDeskNotifications')"))

    def test_disconnect_and_click_after_a_task_leaves_the_recent_list(self):
        task = snapshot("earlier", "target", "Original turn", "running")
        other = snapshot("other", "other", "Other task", "running")
        tasks = [other, task]
        online, exists = True, True

        def respond(_, path, __):
            if not online:
                return 503, {"error": {"code": "connection", "message": "Test phone offline"}}, {}
            if path == "/api/v1/tasks/earlier":
                return (200, task, {}) if exists else (404, {"error": {"code": "not_found"}}, {})
            return read_tasks(tasks, path)

        with browser_desk(respond) as (page, phone):
            toggle = prepare(page)
            toggle.check()
            page.locator('[data-group="chat:other"]').click()
            online = False
            page.locator("#refresh").click()
            expect(page.locator("#connection-line")).to_have_text("未连接")
            self.assertEqual(0, page.evaluate("notices.length"))
            task.update(status="done", answer="Original result")
            online = True
            page.locator("#refresh").click()
            page.wait_for_function("() => notices.length === 1")
            tasks.remove(task)
            page.locator("#refresh").click()
            expect(page.locator('[data-group="chat:target"]')).to_have_count(0)
            page.evaluate("notices[0].onclick()")
            expect(page.locator('.turn[data-id="earlier"]')).to_be_focused()
            expect(page.locator(".answer")).to_have_text("Original result")
            page.locator('[data-group="chat:other"]').click()
            exists = False
            page.locator("#refresh").click()
            expect(page.locator('[data-group="chat:target"]')).to_have_count(0)
            page.evaluate("notices[0].onclick()")
            expect(page.locator("#notice")).to_contain_text("手机已找不到")
            expect(page.locator("#goal")).to_be_disabled()
            self.assertEqual(1, page.evaluate("notices.length"))
            self.assertEqual([], mutations(phone))


if __name__ == "__main__":
    unittest.main()
