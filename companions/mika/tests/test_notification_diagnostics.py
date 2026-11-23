"""Notification configuration tests without a phone, account or message access."""

import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
spec = importlib.util.spec_from_file_location("mika_notifications", SCRIPTS / "mika_notifications.py")
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)

SOURCE = "com.example.social"
DEPENDENCY = "com.example.push"
LISTENER = "com.example.agent/.Listener"


def package_dump(name, enabled=0, stopped=False, user=0, installed=True, app_id_field="userId"):
    return (f"Packages:\n  Package [{name}]:\n    {app_id_field}=10123\n"
            f"    User {user}: installed={str(installed).lower()} hidden=false suspended=false "
            f"stopped={str(stopped).lower()} enabled={enabled}\n")


NOTIFICATIONS = """
    AppSettings: com.example.social (10123)
      NotificationChannel{mId='private-channel', mName=private-name, mImportance=2, mDeleted=false}
    AppSettings: com.example.other (10124) importance=NONE
      NotificationChannel{mImportance=0, mDeleted=false}
  Live notification listeners (1):
    ComponentInfo{com.example.agent/com.example.agent.Listener} (user 0)
  Snoozed notification listeners (0):
  NotificationRecord(contains-private-message-text)
"""


class FakeAdb:
    def __init__(self, source=None, dependency=None, disabled="", dump=NOTIFICATIONS, failure=None):
        self.source = source or package_dump(SOURCE)
        self.dependency = dependency or package_dump(DEPENDENCY)
        self.disabled, self.dump, self.failure = disabled, dump, failure
        self.calls = []

    def run(self, *args):
        self.calls.append(args)
        command = args[1:]
        if self.failure == command[0]:
            raise RuntimeError("private-error-value")
        if command[:3] == ("pm", "list", "packages"):
            return self.disabled
        if command[:2] == ("dumpsys", "package"):
            return self.source if command[2] == SOURCE else self.dependency
        if command == ("dumpsys", "notification"):
            return self.dump
        if command[:2] == ("cmd", "appops"):
            return "No operations.\nDefault mode: allow"
        if command[0] == "settings":
            return LISTENER
        raise AssertionError(command)


class NotificationDiagnosticsTest(unittest.TestCase):
    def test_hidden_factory_copy_does_not_override_disabled_user_state(self):
        text = package_dump(SOURCE, 3, True) + "Hidden system packages:\n" + package_dump(SOURCE)
        state = diagnostics.package_state(text, 0, False)
        self.assertEqual(state["enabled_override"], 3)
        self.assertFalse(state["effective_enabled"])
        self.assertTrue(state["stopped"])

    def test_requested_profile_is_selected(self):
        text = package_dump(SOURCE, installed=False) + "    User 128: installed=true enabled=1 stopped=false\n"
        state = diagnostics.package_state(text, 128, False)
        self.assertTrue(state["installed"])
        self.assertEqual(state["uid"], 12810123)
        adb = FakeAdb(source=package_dump(SOURCE, user=128), dump=NOTIFICATIONS.replace("(user 0)", "(user 128)"))
        report = diagnostics.inspect_notifications(adb, SOURCE, listener=LISTENER, user=128)
        self.assertTrue(report["listener"]["bound"])

    def test_app_id_line_of_newer_android_gives_the_uid(self):
        state = diagnostics.package_state(package_dump(SOURCE, app_id_field="appId"), 0, False)
        self.assertEqual(state["uid"], 10123)
        blocked = NOTIFICATIONS.replace("(10123)", "(10123) importance=NONE")
        report = diagnostics.inspect_notifications(FakeAdb(source=package_dump(SOURCE, app_id_field="appId"), dump=blocked), SOURCE)
        self.assertTrue(report["notifications"]["found"])
        self.assertIn("notifications_blocked", {item["code"] for item in report["findings"]})

    def test_effective_disabled_list_is_honored(self):
        self.assertFalse(diagnostics.package_state(package_dump(SOURCE), 0, True)["effective_enabled"])
        self.assertIsNone(diagnostics.package_state(package_dump(SOURCE), 0, None)["effective_enabled"])

    def test_missing_fields_remain_unknown(self):
        state = diagnostics.package_state("unavailable", 0, False)
        self.assertIsNone(state["installed"])
        self.assertIsNone(state["stopped"])

    def test_silent_channel_is_enabled_and_other_app_is_excluded(self):
        state = diagnostics.notification_settings(NOTIFICATIONS, SOURCE, 10123)
        self.assertFalse(state["blocked"])
        self.assertEqual(state["channels"], 1)
        self.assertEqual(state["enabled_channels"], 1)
        self.assertNotIn("private", json.dumps(state))

    def test_deleted_channel_is_not_counted(self):
        text = NOTIFICATIONS.replace("mDeleted=false}", "mDeleted=true}")
        self.assertEqual(diagnostics.notification_settings(text, SOURCE, 10123)["channels"], 0)

    def test_blocked_package_and_wrong_profile(self):
        blocked = NOTIFICATIONS.replace("(10123)", "(10123) importance=NONE")
        self.assertTrue(diagnostics.notification_settings(blocked, SOURCE, 10123)["blocked"])
        self.assertIsNone(diagnostics.notification_settings(blocked, SOURCE, 12810123)["blocked"])

    def test_uid_appop_wins_over_package_mode(self):
        self.assertEqual(diagnostics.appop_mode("Uid mode: POST_NOTIFICATION: ignore\nPOST_NOTIFICATION: allow", "POST_NOTIFICATION"), "ignore")
        self.assertEqual(diagnostics.appop_mode("No operations.\nDefault mode: allow", "POST_NOTIFICATION"), "allow")

    def test_listener_shorthand_and_live_binding(self):
        for user in (0, 128):
            with self.subTest(user=user):
                text = NOTIFICATIONS.replace("(user 0)", f"(user {user})")
                state = diagnostics.listener_state(LISTENER, text, LISTENER, user)
                self.assertTrue(state["access_granted"])
                self.assertTrue(state["bound"])

    def test_allowed_listener_can_be_unbound(self):
        text = NOTIFICATIONS.replace("ComponentInfo{com.example.agent/com.example.agent.Listener}", "ComponentInfo{com.example.other/com.example.other.Listener}")
        self.assertFalse(diagnostics.listener_state(LISTENER, text, LISTENER)["bound"])
        report = diagnostics.inspect_notifications(FakeAdb(dump=NOTIFICATIONS.replace("(user 0)", "(user 128)")),
                                                   SOURCE, listener=LISTENER)
        self.assertFalse(report["listener"]["bound"])
        self.assertIn("listener_not_bound", {item["code"] for item in report["findings"]})

    def test_listener_without_profile_metadata_is_unknown(self):
        text = NOTIFICATIONS.replace(" (user 0)", "")
        self.assertIsNone(diagnostics.listener_state(LISTENER, text, LISTENER)["bound"])

    def test_dependency_failure_is_specific_and_content_is_not_returned(self):
        adb = FakeAdb(dependency=package_dump(DEPENDENCY, 3, True), disabled="package:" + DEPENDENCY)
        report = diagnostics.inspect_notifications(adb, SOURCE, [DEPENDENCY], LISTENER)
        self.assertEqual({f["code"] for f in report["findings"]}, {"dependency_disabled", "dependency_stopped"})
        self.assertNotIn("private", json.dumps(report))
        self.assertEqual(report["delivery_test"], "not_run")

    def test_uninstalled_dependency_has_one_primary_finding(self):
        report = diagnostics.inspect_notifications(FakeAdb(dependency=package_dump(DEPENDENCY, installed=False, stopped=True)), SOURCE, [DEPENDENCY])
        self.assertEqual([f["code"] for f in report["findings"]], ["dependency_not_installed"])

    def test_suspended_source_is_reported(self):
        source = package_dump(SOURCE).replace("suspended=false", "suspended=true")
        report = diagnostics.inspect_notifications(FakeAdb(source=source), SOURCE)
        self.assertIn("source_suspended", {item["code"] for item in report["findings"]})

    def test_probe_failure_is_reported_without_error_content(self):
        report = diagnostics.inspect_notifications(FakeAdb(failure="dumpsys"), SOURCE)
        self.assertTrue(report["probe_errors"])
        self.assertNotIn("private-error-value", json.dumps(report))

    def test_invalid_names_are_rejected(self):
        for name in ("com.example;id", "com.example\ncmd", "", "../app"):
            with self.assertRaises(ValueError):
                diagnostics.validate(name, [], None, 0)

    def test_cli_does_not_connect_to_task_api(self):
        cli_spec = importlib.util.spec_from_file_location("diagnostic_cli", SCRIPTS / "mika_cli.py")
        cli = importlib.util.module_from_spec(cli_spec)
        cli_spec.loader.exec_module(cli)
        adb = FakeAdb()
        adb.start_server = lambda: None
        adb.select = lambda: None
        args = types.SimpleNamespace(command="notification-doctor", url=None, token_file=None, adb="adb",
                                     device="test-device", package=SOURCE, dependency=[], listener=LISTENER, user=0)
        with patch.object(cli, "Adb", return_value=adb), patch.object(cli, "connection", side_effect=AssertionError("Unexpected API")), \
                patch.dict(sys.modules, {"mika_notifications": diagnostics}):
            self.assertEqual(cli.execute(args, [])["source"]["package"], SOURCE)


if __name__ == "__main__":
    unittest.main()
