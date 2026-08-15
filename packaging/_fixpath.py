import os

ROOT = r"C:\code\openopc"
SKIP = {".git", "dist", "build", "node_modules", "frontend_dist",
        ".workbuddy", "__pycache__", ".venv", "venv"}
SKIP_EXT = {".pyc", ".pyo", ".png", ".jpg", ".ico", ".exe", ".dll", ".so",
            ".bin", ".zip", ".pdf"}


def main():
    fixed = 0
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            fp = os.path.join(dp, f)
            if os.path.splitext(fp)[1].lower() in SKIP_EXT:
                continue
            try:
                t = open(fp, encoding="utf-8").read()
            except Exception:
                continue
            o = t
            t = t.replace(r"C:\code\openopc", r"C:\code\openopc").replace(
                "C:/code/openopc", "C:/code/openopc"
            )
            if t != o:
                open(fp, "w", encoding="utf-8").write(t)
                fixed += 1
                print("FIX", os.path.relpath(fp, ROOT))
    print("FIXED_FILES", fixed)


if __name__ == "__main__":
    main()
