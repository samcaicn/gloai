"""Reproduce the real _NativeSplash._run failure by importing the actual
module and capturing any exception the daemon thread swallows.
"""
import os
import sys
import threading
import time
import traceback

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "packaging"))
import desktop_app  # noqa: E402

errors = []


def _excepthook(args):
    errors.append(
        "".join(
            traceback.format_exception(args.exc_type, args.exc_value, args.exc_traceback)
        )
    )


threading.excepthook = _excepthook

print("platform:", sys.platform)
s = desktop_app._NativeSplash()
s.start()
time.sleep(2.5)
print("after 2.5s -> hwnd=", s._hwnd, "ok=", s._ok, "closed=", s._closed)
if errors:
    print("=== THREAD EXCEPTION CAPTURED ===")
    print("\n".join(errors))
else:
    print("no thread exception captured")
s.close()
print("done")
