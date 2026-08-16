import json
import sys
import subprocess
import time

def get_latest_run():
    result = subprocess.run(
        ["gh", "run", "list", "--limit", "1", "--json", "databaseId,status,conclusion,name,headBranch"],
        capture_output=True, text=True, cwd="/Volumes/d/ai/safeopcAPP"
    )
    data = json.loads(result.stdout)
    if not data:
        return None
    return data[0]

def get_run_details(run_id):
    result = subprocess.run(
        ["gh", "run", "view", str(run_id), "--json", "jobs"],
        capture_output=True, text=True, cwd="/Volumes/d/ai/safeopcAPP"
    )
    data = json.loads(result.stdout)
    return data.get("jobs", [])

def main():
    print("=== 开始监测 CI 构建 ===")
    print("每 60 秒检查一次，最多等待 60 次")
    print()

    for i in range(1, 61):
        run = get_latest_run()
        if run is None:
            print(f"[{i}] 未找到 CI run，等待中...")
            time.sleep(60)
            continue

        run_id = run.get("databaseId")
        status = run.get("status", "?")
        conclusion = run.get("conclusion", "")
        name = run.get("name", "?")
        print(f"=== 第 {i} 次检查 ({time.strftime('%Y-%m-%d %H:%M:%S')}) ===")
        print(f"Run ID: {run_id} | 工作流: {name} | 状态: {status}/{conclusion}")
        print()

        jobs = get_run_details(run_id)
        if jobs:
            for j in jobs:
                job_name = j.get("name", "?")
                job_status = j.get("status", "?")
                job_conclusion = j.get("conclusion", "")
                steps = j.get("steps", [])
                total = len(steps)
                done = len([s for s in steps if s.get("status") == "completed"])
                failed = len([s for s in steps if s.get("conclusion") == "failure"])

                if failed > 0:
                    print(f"  ✗ [{job_conclusion}] {job_name} ({done}/{total}, {failed}失败)")
                elif job_status == "in_progress":
                    print(f"  ▶ [{job_status}] {job_name} ({done}/{total})")
                elif job_status == "completed":
                    icon = "✓" if job_conclusion == "success" else "✗"
                    print(f"  {icon} [{job_conclusion}] {job_name} ({done}/{total})")
                else:
                    print(f"  ? [{job_status}] {job_name}")
        else:
            print("  (jobs 尚未开始)")
        print()

        if status == "completed":
            if conclusion == "success":
                print(">>> 🎉 CI 全部成功！ >>>")
                return 0
            else:
                print(f">>> ✗ CI 完成但结论: {conclusion} <<<")
                print()
                print("=== 失败日志 ===")
                result = subprocess.run(
                    ["gh", "run", "view", str(run_id), "--log-failed"],
                    capture_output=True, text=True, cwd="/Volumes/d/ai/safeopcAPP"
                )
                print(result.stdout[-3000:])
                return 1

        time.sleep(60)

    print("超时未完成")
    return 2

if __name__ == "__main__":
    sys.exit(main())
