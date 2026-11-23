#!/usr/bin/env python3
"""Keep source files from growing into giants.

A production source file may have at most LIMIT lines. Files that were already larger when the guard
arrived are listed in baseline.txt with the size they had then; each may shrink but never grow past
that ceiling. Splitting such a file is the way out: once it is under LIMIT, it leaves the list.

  check    fail when a file over LIMIT is not listed, or a listed file grew past its ceiling
  tighten  lower each ceiling to the file's current size and drop files that are gone or under LIMIT
           (never raises a ceiling and never adds a file)
"""

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).resolve().parent / "baseline.txt"
LIMIT = 800
SOURCE_DIRS = ("app/src/main", "core/src/main")
EXTENSIONS = {".kt", ".kts", ".java"}


def count_lines(path: Path) -> int:
    with path.open("rb") as f:
        return sum(1 for _ in f)


def scan(root: Path) -> Dict[str, int]:
    sizes: Dict[str, int] = {}
    for top in SOURCE_DIRS:
        base = root / top
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.suffix in EXTENSIONS and path.is_file():
                sizes[path.relative_to(root).as_posix()] = count_lines(path)
    return sizes


def read_baseline(path: Path) -> Dict[str, int]:
    ceilings: Dict[str, int] = {}
    if not path.exists():
        return ceilings
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        count, name = line.split(None, 1)
        ceilings[name.strip()] = int(count)
    return ceilings


def write_baseline(path: Path, ceilings: Dict[str, int]) -> None:
    header = (
        f"# Files that were over {LIMIT} lines when the size guard arrived, with the most lines each may have.\n"
        "# Ceilings only go down: split a file, then run `python3 tools/size/size_guard.py tighten`.\n"
    )
    rows = sorted(ceilings.items(), key=lambda item: (-item[1], item[0]))
    path.write_text(header + "".join(f"{count} {name}\n" for name, count in rows), encoding="utf-8")


@dataclass
class Report:
    errors: List[str]
    notes: List[str]


def check(sizes: Dict[str, int], ceilings: Dict[str, int], limit: int = LIMIT) -> Report:
    errors: List[str] = []
    notes: List[str] = []
    for name, lines in sorted(sizes.items()):
        ceiling: Optional[int] = ceilings.get(name)
        if ceiling is None:
            if lines > limit:
                errors.append(f"{name}: {lines} lines, over the {limit}-line limit. Split it by responsibility.")
        elif lines > ceiling:
            errors.append(f"{name}: {lines} lines, grew past its ceiling of {ceiling}. Move the new code out instead.")
        elif lines < ceiling:
            notes.append(f"{name}: {lines} lines, below its ceiling of {ceiling}.")
    for name in sorted(set(ceilings) - set(sizes)):
        notes.append(f"{name}: listed but no longer exists.")
    return Report(errors, notes)


def tighten(sizes: Dict[str, int], ceilings: Dict[str, int], limit: int = LIMIT) -> Dict[str, int]:
    return {
        name: min(ceiling, sizes[name])
        for name, ceiling in ceilings.items()
        if name in sizes and sizes[name] > limit
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("check", "tighten"))
    args = parser.parse_args()
    sizes = scan(ROOT)
    ceilings = read_baseline(BASELINE)
    if args.command == "tighten":
        tightened = tighten(sizes, ceilings)
        write_baseline(BASELINE, tightened)
        print(f"{len(tightened)} files listed ({len(ceilings) - len(tightened)} dropped).")
        return 0
    report = check(sizes, ceilings)
    for note in report.notes:
        print(f"note: {note}")
    if report.notes:
        print("note: run `python3 tools/size/size_guard.py tighten` to lock in the smaller sizes.")
    for error in report.errors:
        print(f"error: {error}", file=sys.stderr)
    if report.errors:
        return 1
    print(f"Size guard: {len(sizes)} files checked, {len(ceilings)} listed giants, none grew.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
