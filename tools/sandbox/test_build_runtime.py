"""Tests for the bundled-runtime build tool; the network and the build are faked, so no download runs."""

import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name("build-runtime.py")
spec = importlib.util.spec_from_file_location("build_runtime", SCRIPT)
build_runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_runtime)

ASSET_BYTES = b"pretend this is a 57 MB rootfs"
RESULT = {"alpine": "3.22.0", "sha256": hashlib.sha256(ASSET_BYTES).hexdigest(),
          "bytes": len(ASSET_BYTES), "fileBytes": 4096, "entries": 7,
          "packages": [{"name": "musl", "version": "1.2.5-r0", "license": "MIT"}]}


class CheckTest(unittest.TestCase):
    """`--check` compares the committed artifacts with a rebuild, so an absent one cannot pass."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.asset, self.kotlin = base / "rootfs.tar.xz", base / "BundledRuntime.kt"
        self.manifest = base / "packages.json"
        patches = [patch.object(build_runtime, "ASSET", str(self.asset)),
                   patch.object(build_runtime, "KOTLIN", str(self.kotlin)),
                   patch.object(build_runtime, "MANIFEST", str(self.manifest)),
                   patch.object(build_runtime, "build", self.fake_build)]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    @staticmethod
    def fake_build(out):
        Path(out).write_bytes(ASSET_BYTES)
        return RESULT

    def run_tool(self, *flags):
        """Run the tool; return (exit code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        argv = ["build-runtime.py", *flags]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                build_runtime.main()
            except SystemExit as stop:
                return stop.code, out.getvalue(), err.getvalue()
        return 0, out.getvalue(), err.getvalue()

    def check(self):
        return self.run_tool("--check")

    def commit(self, asset=True, kotlin=True, manifest=True):
        """Write the artifacts by running the tool's own build path, then drop the ones asked to be absent.

        The tool generates them, so these tests compare its output with its own comparison rather than
        restating a format that would then have to be kept in step by hand.
        """
        code, out, err = self.run_tool()
        self.assertEqual(0, code, out + err)
        for path, present in ((self.asset, asset), (self.kotlin, kotlin), (self.manifest, manifest)):
            if not present:
                Path(path).unlink()

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

    def test_stale_manifest_fails_instead_of_passing(self):
        # packages.json is a committed artifact of the same build: the licenses page renders it and
        # runtime-sources.py decides from it which sources the GPL source pack must ship.
        self.commit()
        self.manifest.write_text('{\n "version": "3.21.9-deadbeef",\n "alpine": "3.21.9",\n "packages": []\n}\n',
                                 encoding="utf-8")
        code, out, err = self.check()
        self.assertEqual(1, code, out)
        self.assertIn("packages.json", err)
        self.assertIn("differs", err)
        self.assertNotIn("ok ", out)

    def test_absent_manifest_fails_instead_of_passing(self):
        self.commit(manifest=False)
        code, out, err = self.check()
        self.assertEqual(1, code, out)
        self.assertIn("packages.json", err)
        self.assertIn("missing", err)
        self.assertNotIn("ok ", out)


class RuntimePythonTest(unittest.TestCase):
    """The build reads the runtime's python from the image, and refuses a wheel built for another one."""

    def bake(self, python_minor, filename):
        """Bake one wheel into a tree; return (tree, stderr, exit code). None plants no python at all."""
        tree = build_runtime.Tree()
        if python_minor is not None:
            for name in (f"usr/lib/python3.{python_minor}/os.py",
                         f"usr/lib/python3.{python_minor}/EXTERNALLY-MANAGED"):
                tree.put(build_runtime.norm(tarfile.TarInfo(name)), b"")
        entry = {"pkg": "example", "version": "1.0", "filename": filename,
                 "sha256": "0" * 64, "url": "https://example.invalid/" + filename}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as zf:
            zf.writestr("example/__init__.py", b"")
        wheel = buffer.getvalue()
        installed = "usr/lib/python3.%s/site-packages/example/__init__.py" % python_minor
        err, code = io.StringIO(), 0
        with patch.object(build_runtime, "read_pip_lock", lambda: [entry]), \
                patch.object(build_runtime, "cached", lambda url, name, want=None: wheel), \
                contextlib.redirect_stderr(err):
            try:
                build_runtime.bake_pip_wheels(tree, 0)
            except SystemExit as stop:
                code = stop.code
        return tree, err.getvalue(), code, installed

    def test_baked_wheels_land_in_the_site_packages_the_image_has(self):
        # python3 comes from WANT, so its minor version moves with Alpine: a path pinned in the
        # builder leaves every wheel in a directory the runtime does not have.
        tree, err, code, installed = self.bake(13, "example-1.0-py3-none-any.whl")
        self.assertEqual(0, code, err)
        self.assertIn(installed, tree.entries)

    def test_a_wheel_built_for_another_cpython_is_refused(self):
        # lxml ships a cp312 wheel; unpacked into a 3.13 runtime it fails at import on the phone.
        tree, err, code, _ = self.bake(13, "lxml-6.1.3-cp312-cp312-manylinux2014_aarch64.whl")
        self.assertEqual(1, code, list(tree.entries))
        self.assertIn("3.12", err)
        self.assertIn("3.13", err)
        self.assertIn("pip-requires.lock", err)

    def test_an_abi3_wheel_still_works_on_a_newer_python(self):
        tree, err, code, installed = self.bake(13, "cryptography-50.0.2-cp311-abi3-manylinux2014_aarch64.whl")
        self.assertEqual(0, code, err)
        self.assertIn(installed, tree.entries)

    def test_an_abi3_wheel_is_refused_by_an_older_python(self):
        tree, err, code, _ = self.bake(10, "cryptography-50.0.2-cp311-abi3-manylinux2014_aarch64.whl")
        self.assertEqual(1, code, list(tree.entries))
        self.assertIn("3.11", err)

    def test_a_pure_python_wheel_needs_no_particular_version(self):
        tree, err, code, installed = self.bake(14, "requests-2.34.2-py3-none-any.whl")
        self.assertEqual(0, code, err)
        self.assertIn(installed, tree.entries)

    def test_a_runtime_without_python_is_a_build_error(self):
        tree, err, code, _ = self.bake(None, "requests-2.34.2-py3-none-any.whl")
        self.assertEqual(1, code, list(tree.entries))
        self.assertIn("python3", err)


class PipLockTest(unittest.TestCase):
    """The pip lock is a required input: the wheels it lists are the runtime's site-packages."""

    def read(self, path):
        """Read a lock through the real reader; return (exit code, stderr, wheels or None)."""
        err = io.StringIO()
        with patch.object(build_runtime, "PIP_LOCK", str(path)), contextlib.redirect_stderr(err):
            try:
                wheels = build_runtime.read_pip_lock()
            except SystemExit as stop:
                return stop.code, err.getvalue(), None
        return 0, err.getvalue(), wheels

    def test_an_absent_pip_lock_is_a_build_error(self):
        # Returning [] baked zero wheels, and the build then rewrote the asset and BundledRuntime.kt
        # from that result: reportlab, lxml and requests left the shipped runtime saying nothing.
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        code, err, wheels = self.read(Path(directory.name) / "pip-requires.lock")
        self.assertEqual(1, code, err)
        self.assertIn("pip-requires.lock", err)
        self.assertIn("missing", err)
        self.assertIsNone(wheels)

    def test_the_committed_lock_is_read(self):
        code, err, wheels = self.read(build_runtime.PIP_LOCK)
        self.assertEqual(0, code, err)
        self.assertTrue(wheels)


if __name__ == "__main__":
    unittest.main()
