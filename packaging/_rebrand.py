import os

ROOT = r"C:\code\openopc"
SKIP_DIRS = {".git", "dist", "build", "node_modules", "frontend_dist",
             ".workbuddy", "__pycache__", ".venv", "venv", ".idea", ".vscode"}
SKIP_EXT = {".pyc", ".pyo", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".webp",
            ".woff", ".woff2", ".ttf", ".eot", ".otf", ".exe", ".dll", ".so",
            ".dylib", ".bin", ".zip", ".gz", ".tar", ".pdf", ".mp4", ".mov",
            ".bmp", ".tif", ".tiff"}

# order matters: URL-specific first, then brand, then leftover SafeOPC
REPL = [
    ("github.com/samcaicn/safeopc", "github.com/samcaicn/safeopc"),
    ("samcaicn/safeopc", "samcaicn/safeopc"),
    ("github.com/samcaicn", "github.com/samcaicn"),
    ("samcaicn/safeopc", "samcaicn/safeopc"),
    ("SafeOPC", "SafeOPC"),
    ("safeopc", "safeopc"),
    ("SAFEOPC", "SAFEOPC"),
    ("SafeOPC", "SafeOPC"),
]


def skip(path: str) -> bool:
    parts = path.replace(ROOT, "").split(os.sep)
    for p in parts:
        if p in SKIP_DIRS:
            return True
    ext = os.path.splitext(path)[1].lower()
    if ext in SKIP_EXT:
        return True
    return False


def main():
    changed_files = 0
    total_repl = 0
    for dirpath, dirnames, filenames in os.walk(ROOT):
        # prune skip dirs in-place
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            fp = os.path.join(dirpath, fn)
            if skip(fp):
                continue
            try:
                with open(fp, "r", encoding="utf-8") as f:
                    text = f.read()
            except (UnicodeDecodeError, PermissionError):
                continue
            original = text
            for old, new in REPL:
                if old in text:
                    text = text.replace(old, new)
            if text != original:
                with open(fp, "w", encoding="utf-8") as f:
                    f.write(text)
                n = sum(original.count(old) for old, _ in REPL)
                total_repl += n
                changed_files += 1
                rel = os.path.relpath(fp, ROOT)
                print(f"  edited {rel}")
    print(f"\nDONE: {changed_files} files changed, ~{total_repl} replacements.")


if __name__ == "__main__":
    main()
