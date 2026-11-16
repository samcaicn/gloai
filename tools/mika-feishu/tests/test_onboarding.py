import contextlib
import errno
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from mika_feishu.config import load_config, save_config
from mika_feishu.locking import instance_lock
from mika_feishu.onboarding import ADDONS, qr_page, register, setup
from mika_feishu.runtime import check_feishu


class OnboardingTests(unittest.TestCase):
    @unittest.skipIf(os.name == "nt", "POSIX directory permissions")
    def test_setup_does_not_chmod_an_existing_shared_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory) / "shared"
            parent.mkdir(mode=0o755)
            parent.chmod(0o755)
            with self.assertRaises(ValueError):
                save_config({"secret": "fake"}, parent / "config.json")
            self.assertEqual(parent.stat().st_mode & 0o777, 0o755)
            self.assertFalse((parent / "config.json").exists())

    def test_scan_creation_requests_only_explicit_permissions(self):
        sdk = Mock(return_value={"client_id": "cli_fake", "client_secret": "fake-secret"})
        self.assertEqual(register("create", register_app=sdk), ("cli_fake", "fake-secret"))
        kwargs = sdk.call_args.kwargs
        self.assertTrue(kwargs["create_only"])
        self.assertIs(kwargs["addons"]["preset"], False)
        self.assertEqual(kwargs["addons"]["scopes"]["tenant"],
                         ["im:message.p2p_msg:readonly", "im:message:send_as_bot"])
        self.assertEqual(kwargs["addons"]["events"]["items"]["tenant"], ["im.message.receive_v1"])
        self.assertNotIn("app_id", kwargs)

    def test_existing_app_can_be_selected_or_specified(self):
        sdk = Mock(return_value={"client_id": "cli_fake", "client_secret": "fake-secret"})
        register("existing", "cli_existing", register_app=sdk)
        self.assertEqual(sdk.call_args.kwargs["app_id"], "cli_existing")
        self.assertNotIn("create_only", sdk.call_args.kwargs)
        register("existing", register_app=sdk)
        self.assertNotIn("app_id", sdk.call_args.kwargs)
        self.assertNotIn("create_only", sdk.call_args.kwargs)

    def test_invalid_existing_app_does_not_start_registration(self):
        sdk = Mock()
        with self.assertRaises(ValueError):
            register("existing", "invalid", register_app=sdk)
        sdk.assert_not_called()

    def test_declined_or_incomplete_registration_never_creates_config(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            with patch("builtins.input", return_value="http://127.0.0.1:8765"), \
                    patch("getpass.getpass", return_value="fake-token"), \
                    patch("mika_feishu.onboarding.register", side_effect=RuntimeError("denied")):
                with self.assertRaises(RuntimeError):
                    setup(path, "create", False)
            self.assertFalse(path.exists())
        with self.assertRaises(ValueError):
            register("create", register_app=lambda **kwargs: {"client_id": "cli_no_secret"})

    def test_manual_setup_saves_secrets_without_echoing_them(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            with patch("builtins.input", side_effect=["http://127.0.0.1:8765", "cli_example"]), \
                    patch("getpass.getpass", side_effect=["fake-phone-secret", "fake-app-secret"]), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                setup(path, "manual")
            config = load_config(path)
            self.assertEqual(config["mika_token"], "fake-phone-secret")
            self.assertEqual(config["feishu_app_secret"], "fake-app-secret")
            self.assertNotIn("fake-phone-secret", output.getvalue())
            self.assertNotIn("fake-app-secret", output.getvalue())

    def test_switching_apps_does_not_reuse_fixed_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            save_config({"mika_url": "http://127.0.0.1:8765", "mika_token": "fake-token",
                         "feishu_app_id": "cli_old", "feishu_app_secret": "fake-secret",
                         "allowed_open_ids": ["ou_old"]}, path)
            with patch("builtins.input", side_effect=["", "cli_new"]), \
                    patch("getpass.getpass", side_effect=["", "fake-new-secret"]):
                setup(path, "manual")
            self.assertEqual(load_config(path)["allowed_open_ids"], [])

    def test_qr_is_local_and_browser_opening_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as directory, patch("webbrowser.open") as browser, \
                contextlib.redirect_stdout(io.StringIO()):
            page = qr_page({"url": "https://accounts.feishu.cn/oauth/fake?code=test", "expire_in": 600}, directory, False)
            content = page.read_text(encoding="utf-8")
            self.assertIn("data:image/svg+xml;base64,", content)
            self.assertIn("default-src 'none'", content)
            self.assertNotIn("client_secret", content)
            browser.assert_not_called()

    def test_untrusted_qr_url_never_opens_browser(self):
        with tempfile.TemporaryDirectory() as directory, patch("webbrowser.open") as browser:
            with self.assertRaises(ValueError):
                qr_page({"url": "https://example.com/untrusted"}, directory)
            browser.assert_not_called()

    def test_lock_is_exclusive_and_released(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "connector.lock"
            with instance_lock(path):
                with self.assertRaises(ValueError):
                    with instance_lock(path):
                        pass
            with instance_lock(path):
                pass

    def test_a_filesystem_that_cannot_lock_is_not_reported_as_a_busy_config(self):
        # Contention means another process holds the lock. A filesystem without lock support
        # (a network or container mount) reports ENOLCK instead, and there is no other connector
        # to stop: naming one sends the user hunting for a process that does not exist.
        target = "msvcrt.locking" if os.name == "nt" else "fcntl.flock"
        with tempfile.TemporaryDirectory() as directory:
            with patch(target, side_effect=OSError(errno.ENOLCK, "No locks available")):
                with self.assertRaises(ValueError) as error:
                    with instance_lock(Path(directory) / "connector.lock"):
                        pass
        message = str(error.exception)
        self.assertNotIn("Another connector", message)
        self.assertIn("No locks available", message)

    def test_authentication_probe_sends_no_message_and_omits_token(self):
        response = Mock(status_code=200)
        response.json.return_value = {"code": 0, "tenant_access_token": "private-token"}
        with patch("requests.post", return_value=response) as post:
            report = check_feishu({"feishu_app_id": "cli_fake", "feishu_app_secret": "fake-secret"})
        self.assertTrue(report["ok"])
        self.assertNotIn("private-token", json.dumps(report))
        self.assertFalse(post.call_args.kwargs["allow_redirects"])
        self.assertIn("tenant_access_token/internal", post.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
