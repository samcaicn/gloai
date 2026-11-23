import tempfile
import unittest
from pathlib import Path

import size_guard


class SizeGuardTest(unittest.TestCase):
    def test_new_file_over_the_limit_fails(self):
        report = size_guard.check({"a.kt": 11}, {}, limit=10)
        self.assertEqual(1, len(report.errors))

    def test_listed_file_may_stay_or_shrink_but_not_grow(self):
        self.assertEqual([], size_guard.check({"a.kt": 20}, {"a.kt": 20}, limit=10).errors)
        shrunk = size_guard.check({"a.kt": 15}, {"a.kt": 20}, limit=10)
        self.assertEqual([], shrunk.errors)
        self.assertEqual(1, len(shrunk.notes))
        self.assertEqual(1, len(size_guard.check({"a.kt": 21}, {"a.kt": 20}, limit=10).errors))

    def test_tighten_only_lowers_and_drops_split_or_deleted_files(self):
        tightened = size_guard.tighten(
            {"grown.kt": 30, "shrunk.kt": 15, "split.kt": 9, "new.kt": 50},
            {"grown.kt": 20, "shrunk.kt": 20, "split.kt": 20, "gone.kt": 20},
            limit=10,
        )
        self.assertEqual({"grown.kt": 20, "shrunk.kt": 15}, tightened)

    def test_baseline_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "baseline.txt"
            size_guard.write_baseline(path, {"b.kt": 900, "a.kt": 1200})
            self.assertEqual({"a.kt": 1200, "b.kt": 900}, size_guard.read_baseline(path))

    def test_scan_counts_source_files_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "app/src/main/java"
            src.mkdir(parents=True)
            (src / "A.kt").write_text("1\n2\n3\n")
            (src / "notes.md").write_text("x\n")
            self.assertEqual({"app/src/main/java/A.kt": 3}, size_guard.scan(root))


if __name__ == "__main__":
    unittest.main()
