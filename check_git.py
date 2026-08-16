import subprocess
import os
import sys
import traceback

cwd = r'C:\code\safeopcAPP'
out_path = os.path.join(cwd, 'git_check_result.txt')

lines = []
lines.append(f'python: {sys.executable}')
lines.append(f'cwd: {os.getcwd()}')
lines.append(f'target cwd: {cwd}')
lines.append(f'exists: {os.path.isdir(cwd)}')
lines.append('')

try:
    status = subprocess.run(['git', 'status', '--short'], cwd=cwd, capture_output=True, text=True)
    lines.append(f'=== git status stdout ===\n{status.stdout}')
    lines.append(f'=== git status stderr ===\n{status.stderr}')
    lines.append(f'=== git status code ===\n{status.returncode}')
except Exception as e:
    lines.append(f'git status error: {e}\n{traceback.format_exc()}')

try:
    log = subprocess.run(['git', 'log', '--oneline', '-5'], cwd=cwd, capture_output=True, text=True)
    lines.append(f'=== git log stdout ===\n{log.stdout}')
except Exception as e:
    lines.append(f'git log error: {e}\n{traceback.format_exc()}')

try:
    diff_names = subprocess.run(['git', 'diff', '--name-only', 'HEAD'], cwd=cwd, capture_output=True, text=True)
    lines.append(f'=== git diff --name-only HEAD stdout ===\n{diff_names.stdout}')
except Exception as e:
    lines.append(f'git diff error: {e}\n{traceback.format_exc()}')

with open(out_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines))

print('done')
