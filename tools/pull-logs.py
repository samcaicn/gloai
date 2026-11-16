#!/usr/bin/env python3
"""Export Mika's run records and detailed traces through an authorized ADB connection."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile


def adb_message(stderr, fallback):
    """Prefer adb's own words; fall back only when it said nothing."""
    return (stderr or b"").decode(errors="replace").strip() or fallback


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--device", required=True)
    parser.add_argument("--package", default="com.wangzan101.mika.logs")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("Invalid Android package name")
    # shell -T preserves binary output and reports the remote exit status;
    # exec-out can report success even when run-as failed on the device.
    adb = [args.adb, "-s", args.device, "shell", "-T", "run-as", args.package]
    # Fail before creating an output if Android does not allow run-as for this package.
    try:
        subprocess.run(adb + ["pwd"], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        raise SystemExit(adb_message(error.stderr, "Android does not allow run-as for "
                                     + args.package)) from None
    except OSError as error:
        raise SystemExit("Cannot run {}: {}".format(args.adb, error.strerror or error)) from None
    paths = []
    for path in ("files/runs", "no_backup/harness-traces"):
        exists = subprocess.run(adb + ["test", "-d", path], capture_output=True)
        if exists.returncode == 0:
            paths.append(path)
        # `test -d` reports an absent directory by exiting 1 and saying nothing, while adb exits 1
        # for its own failures too. The message is what separates them: reading a dropped
        # connection as an absent directory told a user with a disconnected phone to run a task.
        elif exists.returncode == 1 and not exists.stderr.strip():
            continue
        else:
            raise SystemExit(adb_message(exists.stderr, "adb test -d {} exited {}".format(
                path, exists.returncode)))
    if not paths:
        raise SystemExit("No records yet. Run a task in Mika Logs first.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as dest:
            temporary = Path(dest.name)
            # The phone can drop off while the export streams; report adb's words like every
            # other failure here instead of letting CalledProcessError surface as a traceback.
            subprocess.run(adb + ["tar", "-cf", "-", *paths], stdout=dest,
                           stderr=subprocess.PIPE, check=True)
        temporary.replace(args.output)
        print(args.output.resolve())
    except subprocess.CalledProcessError as error:
        raise SystemExit(adb_message(error.stderr, "adb tar exited {}".format(
            error.returncode))) from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
