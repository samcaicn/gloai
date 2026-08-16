#!/bin/bash

REPO="samcaicn/gloai"
BRANCH="v2-branch"
TOKEN=$(gh auth token)

check_ci() {
    local run_id=$1
    echo "=== 检查 CI $run_id ==="
    
    local status=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$run_id" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['status']}/{d['conclusion']}\")")
    echo "状态: $status"
    
    if echo "$status" | grep -q "completed/success"; then
        echo "✓✓✓ CI 成功！检查产物..."
        check_artifacts $run_id
        return 0
    elif echo "$status" | grep -q "completed/failure"; then
        echo "✗✗✗ CI 失败！分析错误..."
        analyze_failure $run_id
        return 1
    else
        echo "等待中..."
        return 2
    fi
}

check_artifacts() {
    local run_id=$1
    local artifacts=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$run_id/artifacts")
    local has_exe=$(echo "$artifacts" | python3 -c "import sys,json; d=json.load(sys.stdin); print(any('.exe' in a['name'] for a in d.get('artifacts',[])))")
    local has_dmg=$(echo "$artifacts" | python3 -c "import sys,json; d=json.load(sys.stdin); print(any('.dmg' in a['name'] for a in d.get('artifacts',[])))")
    
    echo "EXE: $has_exe"
    echo "DMG: $has_dmg"
    
    if [ "$has_exe" = "True" ] && [ "$has_dmg" = "True" ]; then
        echo "✓✓✓ 获得了 EXE 和 DMG！"
        exit 0
    else
        echo "缺少产物，重新构建..."
        trigger_ci
    fi
}

analyze_failure() {
    local run_id=$1
    local jobs=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$run_id/jobs")
    
    python3 <<EOF
import sys, json

data = json.loads('''$jobs''')

for job in data.get('jobs', []):
    if job.get('conclusion') == 'failure':
        print(f"失败 Job: {job['name']}")
        print(f"Job ID: {job['id']}")
        
        for step in job.get('steps', []):
            if step.get('conclusion') == 'failure':
                print(f"  失败步骤: {step['name']}")
                
                # 尝试获取步骤日志
                if 'log_url' in step:
                    print(f"  日志URL: {step['log_url']}")
EOF
}

trigger_ci() {
    echo "=== 触发新的 CI ==="
    git commit --allow-empty -m "ci: retry build $(date '+%Y%m%d-%H%M%S')"
    git push origin $BRANCH
    
    sleep 30
    
    # 获取新的 run ID
    local run_info=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?branch=$BRANCH&per_page=1")
    local run_id=$(echo "$run_info" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['workflow_runs'][0]['id'])")
    
    echo "新的 Run ID: $run_id"
    monitor_ci $run_id
}

monitor_ci() {
    local run_id=$1
    echo "=== 开始监测 CI $run_id ==="
    
    while true; do
        local status=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$run_id" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['status']}/{d['conclusion']}\")")
        echo "[$(date '+%H:%M:%S')] $status"
        
        if echo "$status" | grep -q "completed"; then
            break
        fi
        
        sleep 60
    done
    
    check_ci $run_id
}

# 获取最新的 run ID
run_info=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?branch=$BRANCH&per_page=1")
run_id=$(echo "$run_info" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['workflow_runs'][0]['id'])")

echo "开始监测 Run ID: $run_id"
monitor_ci $run_id