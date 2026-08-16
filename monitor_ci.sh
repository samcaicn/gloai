#!/bin/bash

RUN_ID="$1"

if [ -z "$RUN_ID" ]; then
    echo "Usage: $0 <run_id>"
    exit 1
fi

echo "=== 开始监测 CI (Run ID: $RUN_ID) ==="

while true; do
    echo ""
    echo "=================================="
    echo "检查时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "=================================="
    
    STATUS=$(curl -s -H "Authorization: token $(gh auth token)" "https://api.github.com/repos/samcaicn/gloai/actions/runs/$RUN_ID" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['status']}/{d['conclusion']}\")")
    echo "状态: $STATUS"
    
    if echo "$STATUS" | grep -q "completed/success"; then
        echo ""
        echo "✓✓✓ CI 全部成功！ ✓✓✓"
        break
    elif echo "$STATUS" | grep -q "completed/failure"; then
        echo ""
        echo "✗✗✗ CI 失败！查看错误日志... ✗✗✗"
        break
    fi
    
    sleep 60
done