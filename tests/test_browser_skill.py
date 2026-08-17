"""Tests for the BrowserSkill layer-4 backend (opc.layer4_tools.browser_skill).

These run WITHOUT the real `bsk` CLI installed: subprocess calls are mocked, so
the suite verifies argument construction, JSON parsing, session-id extraction,
graceful failure modes, spec/callable consistency, and registry aggregation.
"""
import subprocess
import unittest
from unittest import mock

from opc.layer4_tools import browser_skill
from opc.layer4_tools.browser_skill import (
    BrowserSkillBackend,
    BrowserSkillConfig,
    BrowserSkillError,
    BrowserSkillResult,
)
from opc.layer4_tools.registry import collect_layer4_specs, collect_layer4_tools


def _fake_proc(returncode=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(
        args=["bsk"], returncode=returncode, stdout=stdout, stderr=stderr
    )


class BackendRunnerTests(unittest.TestCase):
    def test_build_argv_adds_json_and_quiet(self):
        backend = BrowserSkillBackend()
        self.assertEqual(backend._build_argv(["status"]), ["bsk", "status", "--json", "--quiet"])

    def test_run_parses_json_stdout(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(backend, "_run_raw", return_value=_fake_proc(0, '{"ok":true,"x":1}')):
            res = backend.run(["status"])
        self.assertTrue(res.ok)
        self.assertEqual(res.data, {"ok": True, "x": 1})
        self.assertEqual(res.code, "OK")

    def test_run_nonzero_maps_to_error(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(backend, "_run_raw", return_value=_fake_proc(1, "", "boom")):
            res = backend.run(["status"])
        self.assertFalse(res.ok)
        self.assertEqual(res.code, "BSK_ERROR")
        self.assertEqual(res.error, "boom")
        self.assertEqual(res.exit_code, 1)

    def test_run_returns_raw_even_on_error(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(backend, "_run_raw", return_value=_fake_proc(2, "raw-out", "err")):
            res = backend.run(["status"])
        self.assertEqual(res.raw, "raw-out")

    def test_navigate_builds_session_and_url(self):
        backend = BrowserSkillBackend()
        captured = {}

        def fake_run(args, timeout=None):
            captured["args"] = args
            return BrowserSkillResult(ok=True, data=None)

        with mock.patch.object(backend, "run", fake_run):
            backend.navigate("https://example.com", session="abcd", wait_until="load")
        self.assertEqual(
            captured["args"],
            ["navigate", "--session", "abcd", "https://example.com", "--wait-until", "load"],
        )

    def test_missing_session_raises_no_session(self):
        backend = BrowserSkillBackend()
        with self.assertRaises(BrowserSkillError) as ctx:
            backend.snapshot(session=None)
        self.assertEqual(ctx.exception.code, "NO_SESSION")

    def test_resolve_session_uses_default(self):
        backend = BrowserSkillBackend()
        backend._default_session = "efgh"
        captured = {}

        def fake_run(args, timeout=None):
            captured["args"] = args
            return BrowserSkillResult(ok=True)

        with mock.patch.object(backend, "run", fake_run):
            backend.click("@e3")
        self.assertEqual(captured["args"][captured["args"].index("--session") + 1], "efgh")

    def test_start_session_extracts_id_from_json(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(
            backend, "run",
            return_value=BrowserSkillResult(ok=True, data={"session": "wxyz"}, raw='{"session":"wxyz"}'),
        ):
            res = backend.start_session()
        self.assertTrue(res.ok)
        self.assertEqual(res.session, "wxyz")
        self.assertEqual(backend._default_session, "wxyz")

    def test_start_session_extracts_id_from_raw_fallback(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(
            backend, "run",
            return_value=BrowserSkillResult(ok=True, data="Session started: q1r2", raw="Session started: q1r2"),
        ):
            res = backend.start_session()
        self.assertEqual(res.session, "q1r2")

    def test_start_session_no_id_is_error(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(backend, "run", return_value=BrowserSkillResult(ok=True, data="", raw="")):
            res = backend.start_session()
        self.assertFalse(res.ok)
        self.assertEqual(res.code, "NO_SESSION_ID")
        self.assertIsNone(backend._default_session)

    def test_session_stop_clears_default(self):
        backend = BrowserSkillBackend()
        backend._default_session = "abcd"
        with mock.patch.object(backend, "run", return_value=BrowserSkillResult(ok=True)):
            backend.session_stop(session="abcd")
        self.assertIsNone(backend._default_session)

    def test_is_available_false_when_command_missing(self):
        backend = BrowserSkillBackend(config=BrowserSkillConfig(command="no_such_bsk_xyz"))
        ok, detail = backend.is_available()
        self.assertFalse(ok)
        self.assertIn("PATH", detail)

    def test_is_available_true_when_bridge_healthy(self):
        backend = BrowserSkillBackend()
        with mock.patch.object(browser_skill.shutil, "which", return_value="/usr/bin/bsk"), \
             mock.patch.object(backend, "_run_raw", return_value=_fake_proc(0, "{}")):
            ok, detail = backend.is_available()
        self.assertTrue(ok)

    def test_exec_raw_accepts_list(self):
        backend = BrowserSkillBackend()
        captured = {}
        with mock.patch.object(backend, "run", lambda args, timeout=None: captured.update(args=args) or BrowserSkillResult(ok=True)):
            backend.exec_raw(["tab", "borrow", "--session", "abcd"])
        self.assertEqual(captured["args"], ["tab", "borrow", "--session", "abcd"])


class ToolFunctionTests(unittest.TestCase):
    def test_all_expected_tool_functions_present(self):
        tools = browser_skill.get_browser_skill_tools()
        for name in (
            "browser_skill_status", "browser_skill_browsers", "browser_skill_session_start",
            "browser_skill_session_stop", "browser_skill_session_list", "browser_skill_navigate",
            "browser_skill_navigate_back", "browser_skill_navigate_forward", "browser_skill_reload",
            "browser_skill_snapshot", "browser_skill_click", "browser_skill_input",
            "browser_skill_select", "browser_skill_press", "browser_skill_extract",
            "browser_skill_screenshot", "browser_skill_exec",
        ):
            self.assertIn(name, tools, f"missing tool: {name}")

    def test_tool_functions_return_dict(self):
        with mock.patch.object(browser_skill._default_backend, "exec_raw",
                                return_value=BrowserSkillResult(ok=True, data={"x": 1})):
            out = browser_skill.browser_skill_exec(["status"])
        self.assertIsInstance(out, dict)
        self.assertTrue(out["ok"])

    def test_input_maps_to_fill(self):
        captured = {}
        with mock.patch.object(browser_skill._default_backend, "fill",
                               lambda ref, value, session=None: captured.update(ref=ref, value=value) or BrowserSkillResult(ok=True)):
            browser_skill.browser_skill_input("@e5", "hello")
        self.assertEqual(captured, {"ref": "@e5", "value": "hello"})

    def test_specs_match_function_names(self):
        tools = browser_skill.get_browser_skill_tools()
        specs = browser_skill.BROWSER_SKILL_TOOL_SPECS
        names = {s["name"] for s in specs}
        self.assertEqual(set(tools.keys()), names)
        for spec in specs:
            self.assertEqual(spec["category"], "browser_skill")

    def test_exec_accepts_shell_string(self):
        captured = {}
        with mock.patch.object(browser_skill._default_backend, "exec_raw",
                               lambda args: captured.update(args=list(args)) or BrowserSkillResult(ok=True)):
            browser_skill.browser_skill_exec("tab list --session abcd")
        self.assertEqual(captured["args"], ["tab", "list", "--session", "abcd"])


class RegistryTests(unittest.TestCase):
    def test_collect_layer4_tools_includes_browser_skill(self):
        tools = collect_layer4_tools()
        self.assertIn("browser_skill_navigate", tools)
        self.assertIn("browser_skill_exec", tools)
        self.assertIn("browser_skill_snapshot", tools)

    def test_collect_specs_includes_browser_skill(self):
        specs = collect_layer4_specs()
        names = {s["name"] for s in specs}
        self.assertIn("browser_skill_navigate", names)
        self.assertIn("browser_skill_session_start", names)


if __name__ == "__main__":
    unittest.main(verbosity=2)
