"""Tests for the ADB log export tool; adb is mocked, so no phone is needed."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("pull-logs.py")
spec = importlib.util.spec_from_file_location("pull_logs", SCRIPT)
pull_logs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pull_logs)

PRESENT = b"/data/user/0/com.wangzan101.mika.logs\n"


def answered(returncode=0, stderr=b""):
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=b"", stderr=stderr)


class FakeAdb:
    """Answer as adb does: report the remote exit status and honour check=True."""

    def __init__(self, run_as=(0, b""), directories=None, missing=False, transfer=None):
        self.run_as = run_as
        self.directories = directories or {}
        self.missing = missing
        self.transfer = transfer

    def __call__(self, command, **options):
        if self.missing:
            raise FileNotFoundError(2, "No such file or directory", command[0])
        if "test" in command:
            returncode, stderr = self.directories.get(command[-1], (1, b""))
        elif "tar" in command and self.transfer is not None:
            returncode, stderr = self.transfer
        else:
            returncode, stderr = self.run_as
        if options.get("check") and returncode != 0:
            # subprocess keeps the message only when the caller asked for it.
            raise subprocess.CalledProcessError(returncode, command,
                                                stderr=stderr if options.get("stderr") else None)
        return answered(returncode, stderr)


class PullLogsTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.output = Path(self.temporary_directory.name) / "mika-logs.tar"

    def export(self, adb):
        argv = ["pull-logs.py", "--device", "SERIAL", "--output", str(self.output)]
        with patch("subprocess.run", adb), patch.object(sys, "argv", argv):
            pull_logs.main()

    def message(self, adb):
        with self.assertRaises(SystemExit) as caught:
            self.export(adb)
        return str(caught.exception)

    def test_adb_failure_is_not_reported_as_missing_records(self):
        # A phone that dropped off mid-export exits 1 like `test -d` does, and only adb's
        # message distinguishes the two.
        adb = FakeAdb(run_as=(0, PRESENT), directories={
            "files/runs": (1, b"error: device offline\n"),
            "no_backup/harness-traces": (1, b"error: device offline\n"),
        })
        self.assertEqual("error: device offline", self.message(adb))
        self.assertFalse(self.output.exists())

    def test_adb_failure_without_a_message_names_the_exit_code(self):
        adb = FakeAdb(run_as=(0, PRESENT), directories={
            "files/runs": (137, b""),
            "no_backup/harness-traces": (0, b""),
        })
        self.assertEqual("adb test -d files/runs exited 137", self.message(adb))

    def test_refused_run_as_is_a_message_rather_than_a_traceback(self):
        adb = FakeAdb(run_as=(1, b"run-as: package not debuggable\n"))
        self.assertEqual("run-as: package not debuggable", self.message(adb))

    def test_missing_adb_binary_names_the_executable(self):
        self.assertEqual("Cannot run adb: No such file or directory",
                         self.message(FakeAdb(missing=True)))

    def test_silent_exit_one_still_means_no_records_yet(self):
        # `test -d` reports an absent directory this way, so it must not become an error.
        adb = FakeAdb(run_as=(0, PRESENT))
        self.assertEqual("No records yet. Run a task in Mika Logs first.", self.message(adb))

    def test_a_failed_transfer_names_adb_instead_of_a_traceback(self):
        # The phone can drop off while tar streams the export. Every other failure in this tool
        # is reported in adb's own words, so this one must be too, not as a Python traceback.
        adb = FakeAdb(run_as=(0, PRESENT),
                      directories={"files/runs": (0, b""), "no_backup/harness-traces": (0, b"")},
                      transfer=(1, b"adb: error: failed to read from device: device offline\n"))
        self.assertEqual("adb: error: failed to read from device: device offline",
                         self.message(adb))
        self.assertFalse(self.output.exists())

    def test_a_warning_on_stderr_does_not_stop_a_successful_export(self):
        adb = FakeAdb(run_as=(0, b"adb server version doesn't match this client\n"),
                      directories={"files/runs": (0, b""),
                                   "no_backup/harness-traces": (0, b"")})
        self.export(adb)
        self.assertTrue(self.output.exists())


if __name__ == "__main__":
    unittest.main()
