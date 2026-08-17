"""Tests for the BrowserSkill setup module (opc.layer4_tools.browser_skill_setup).

These run WITHOUT the real `bsk` CLI: subprocess + path probes are mocked, so
the suite verifies command construction, idempotency, install outcomes,
extension detection heuristics, guidance shape, and registry consistency.
"""
import os
import subprocess
import sys
import unittest
from unittest import mock

from opc.layer4_tools import browser_skill_setup as setup
from opc.layer4_tools.browser_skill import BrowserSkillResult
from opc.layer4_tools.registry import collect_layer4_specs, collect_layer4_tools


def _fake_proc(returncode=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(
        args=["bsk"], returncode=returncode, stdout=stdout, stderr=stderr
    )


def _fake_backend(data=None, raw="", ok=True, error=""):
    """Build a BrowserSkillBackend whose .run returns a canned result."""
    res = BrowserSkillResult(ok=ok, data=data, raw=raw, error=error)
    backend = mock.MagicMock()
    backend.run.return_value = res
    return backend


class BuildInstallCommandTests(unittest.TestCase):
    def test_windows_uses_powershell_irm(self):
        cmd = setup.build_install_command("win32")
        self.assertEqual(cmd[0], "powershell")
        self.assertIn("irm", cmd[-1])
        self.assertIn("iex", cmd[-1])
        self.assertIn("install.ps1", cmd[-1])

    def test_unix_uses_curl_pipe_sh(self):
        cmd = setup.build_install_command("linux")
        self.assertEqual(cmd[:2], ["sh", "-c"])
        self.assertIn("curl -fsSL", cmd[2])
        self.assertIn("install.sh", cmd[2])

    def test_url_override(self):
        cmd = setup.build_install_command("linux", "https://example.com/x.sh")
        self.assertIn("https://example.com/x.sh", cmd[2])


class BinaryPresentTests(unittest.TestCase):
    def test_present_via_which(self):
        with mock.patch.object(setup.shutil, "which", return_value="/usr/bin/bsk"):
            present, path = setup.bsk_binary_present()
        self.assertTrue(present)
        self.assertTrue(path)  # resolved absolute path, platform-normalized

    def test_present_via_candidate_dir(self):
        import tempfile
        tmp = tempfile.mkdtemp()
        name = "bsk.exe" if sys.platform == "win32" else "bsk"
        with open(os.path.join(tmp, name), "w") as fh:
            fh.write("")
        with mock.patch.object(setup.shutil, "which", return_value=None), \
             mock.patch.object(setup, "BSK_INSTALL_DIR", __import__("pathlib").Path(tmp)):
            present, path = setup.bsk_binary_present()
        self.assertTrue(present)
        self.assertTrue(path.endswith(name))

    def test_absent(self):
        import tempfile
        tmp = tempfile.mkdtemp()
        with mock.patch.object(setup.shutil, "which", return_value=None), \
             mock.patch.object(setup, "BSK_INSTALL_DIR", __import__("pathlib").Path(tmp)):
            present, path = setup.bsk_binary_present()
        self.assertFalse(present)
        self.assertEqual(path, "")


class EnsureInstalledTests(unittest.TestCase):
    def test_already_present_is_idempotent(self):
        with mock.patch.object(setup, "bsk_binary_present", return_value=(True, "/x/bsk")):
            rep = setup.ensure_bsk_installed(auto_run=True)
        self.assertEqual(rep["action"], "already_present")
        self.assertTrue(rep["installed"])
        self.assertTrue(rep["ok"])

    def test_needs_install_when_auto_run_false(self):
        with mock.patch.object(setup, "bsk_binary_present", return_value=(False, "")):
            rep = setup.ensure_bsk_installed(auto_run=False)
        self.assertEqual(rep["action"], "needs_install")
        self.assertFalse(rep["installed"])
        self.assertIn("install_command", rep)
        self.assertIn("manual", rep)

    def test_installed_after_running_script(self):
        with mock.patch.object(setup, "bsk_binary_present",
                               side_effect=[(False, ""), (True, "/x/bsk")]), \
             mock.patch.object(setup.subprocess, "run",
                               return_value=_fake_proc(0, "ok")) as run:
            rep = setup.ensure_bsk_installed(auto_run=True)
        self.assertEqual(rep["action"], "installed")
        self.assertTrue(rep["installed"])
        run.assert_called_once()

    def test_install_failed_nonzero(self):
        with mock.patch.object(setup, "bsk_binary_present", return_value=(False, "")), \
             mock.patch.object(setup.subprocess, "run",
                               return_value=_fake_proc(1, "", "boom")):
            rep = setup.ensure_bsk_installed(auto_run=True)
        self.assertEqual(rep["action"], "install_failed")
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["error"], "boom")

    def test_install_timeout(self):
        with mock.patch.object(setup, "bsk_binary_present", return_value=(False, "")), \
             mock.patch.object(setup.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired("x", 1)):
            rep = setup.ensure_bsk_installed(auto_run=True, timeout=1)
        self.assertEqual(rep["action"], "install_timeout")
        self.assertFalse(rep["ok"])


class DetectExtensionTests(unittest.TestCase):
    def test_connected_via_browsers_list(self):
        backend = _fake_backend(data={"browsers": [{"id": "c1"}, {"id": "c2"}]})
        connected, detail, _ = setup.detect_extension(backend)
        self.assertTrue(connected)
        self.assertIn("2", detail)

    def test_installed_but_no_connected_browser(self):
        backend = _fake_backend(data={"browsers": []})
        connected, detail, _ = setup.detect_extension(backend)
        self.assertFalse(connected)

    def test_not_detected_string(self):
        backend = _fake_backend(raw="Extension not detected")
        connected, detail, _ = setup.detect_extension(backend)
        self.assertFalse(connected)

    def test_connected_bool_flag(self):
        backend = _fake_backend(data={"extension_connected": True})
        connected, _, _ = setup.detect_extension(backend)
        self.assertTrue(connected)

    def test_uncertain_falls_to_not_detected(self):
        backend = _fake_backend(data={}, raw="{}")
        connected, detail, _ = setup.detect_extension(backend)
        self.assertFalse(connected)
        self.assertIn("无法确定", detail)

    def test_probe_failure(self):
        backend = _fake_backend(ok=False, error="bridge down", raw="")
        connected, detail, _ = setup.detect_extension(backend)
        self.assertFalse(connected)
        self.assertIn("探测失败", detail)


class GuidanceTests(unittest.TestCase):
    def test_guidance_shape_and_no_auto_install(self):
        g = setup.extension_guidance()
        self.assertFalse(g["auto_installable"])
        self.assertIn("deep_link_command", g)
        self.assertIn("store", g)
        self.assertIn("steps", g)
        self.assertTrue(len(g["steps"]) >= 3)


class ReadinessTests(unittest.TestCase):
    def test_cli_missing_attaches_guidance(self):
        with mock.patch.object(setup, "ensure_bsk_installed",
                               return_value={"installed": False, "ok": True}):
            rep = setup.readiness_report(auto_install=False)
        self.assertFalse(rep["ready"])
        self.assertIn("guidance", rep)
        self.assertFalse(rep["extension"]["installed"])

    def test_cli_present_and_extension_connected_is_ready(self):
        with mock.patch.object(setup, "ensure_bsk_installed",
                               return_value={"installed": True, "ok": True}), \
             mock.patch.object(setup, "detect_extension",
                               return_value=(True, "ok", "")):
            rep = setup.readiness_report(auto_install=False)
        self.assertTrue(rep["ready"])
        self.assertTrue(rep["extension"]["installed"])
        self.assertNotIn("guidance", rep)


class RegistryTests(unittest.TestCase):
    def test_registry_includes_setup_tools(self):
        tools = collect_layer4_tools()
        for name in ("browser_skill_ensure_installed",
                     "browser_skill_extension_guidance",
                     "browser_skill_readiness"):
            self.assertIn(name, tools, f"missing tool: {name}")

    def test_registry_specs_consistent(self):
        tools = collect_layer4_tools()
        specs = collect_layer4_specs()
        setup_specs = [s for s in specs if s["name"].startswith("browser_skill_ensure")
                       or s["name"] in ("browser_skill_extension_guidance",
                                        "browser_skill_readiness")]
        for spec in setup_specs:
            self.assertEqual(spec["category"], "browser_skill")
            self.assertIn(spec["name"], tools)


if __name__ == "__main__":
    unittest.main(verbosity=2)
