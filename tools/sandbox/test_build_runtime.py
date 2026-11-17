"""Tests for the bundled-runtime build tool; the network and the build are faked, so no download runs."""

import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("build-runtime.py")
spec = importlib.util.spec_from_file_location("build_runtime", SCRIPT)
build_runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_runtime)

ASSET_BYTES = b"pretend this is a 57 MB rootfs"
RESULT = {"alpine": "3.22.0", "sha256": hashlib.sha256(ASSET_BYTES).hexdigest(),
          "bytes": len(ASSET_BYTES), "fileBytes": 4096, "entries": 7, "packages": []}


class CheckTest(unittest.TestCase):
    """`--check` compares the committed artifacts with a rebuild, so an absent one cannot pass."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.asset, self.kotlin = base / "rootfs.tar.xz", base / "BundledRuntime.kt"
        self.patches = [patch.object(build_runtime, "ASSET", str(self.asset)),
                        patch.object(build_runtime, "KOTLIN", str(self.kotlin)),
                        patch.object(build_runtime, "build", self.fake_build)]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    @staticmethod
    def fake_build(out):
        Path(out).write_bytes(b"rebuilt")
        return RESULT

    def check(self):
        """Run --check; return (exit code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        argv = ["build-runtime.py", "--check"]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                build_runtime.main()
            except SystemExit as stop:
                return stop.code, out.getvalue(), err.getvalue()
        return 0, out.getvalue(), err.getvalue()

    def commit(self, asset=True, kotlin=True):
        if asset:
            self.asset.write_bytes(ASSET_BYTES)
        if kotlin:
            # Render after ASSET is patched: the generated comment embeds the asset path.
            self.kotlin.write_text(build_runtime.kotlin(RESULT), encoding="utf-8")

    def test_matching_artifacts_pass(self):
        self.commit()
        code, out, err = self.check()
        self.assertEqual(0, code, err)
        self.assertIn("ok 3.22.0-", out)

    def test_absent_runtime_asset_fails_instead_of_passing(self):
        # A partial or sparse checkout can lack the 57 MB asset; a check that skips its comparison
        # then prints "ok" for an artifact it never looked at.
        self.commit(asset=False)
        code, out, err = self.check()
        self.assertEqual(1, code, out)
        self.assertIn("rootfs.tar.xz", err)
        self.assertIn("missing", err)
        self.assertNotIn("ok ", out)

    def test_absent_kotlin_names_the_file_rather_than_claiming_a_difference(self):
        self.commit(kotlin=False)
        code, _, err = self.check()
        self.assertEqual(1, code)
        self.assertIn("BundledRuntime.kt", err)
        self.assertIn("missing", err)
        self.assertNotIn("differs", err)


if __name__ == "__main__":
    unittest.main()
