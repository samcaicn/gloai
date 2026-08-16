$sha = "36567c343"
Write-Host "Waiting for CI run for commit $sha..."
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 30
    $r = Invoke-RestMethod -Uri 'https://api.github.com/repos/samcaicn/gloai/actions/runs?per_page=5'
    $found = $false
    foreach ($run in $r.workflow_runs) {
        if ($run.head_sha.StartsWith($sha) -and $run.head_branch -eq 'v2') {
            Write-Host "[$i] Status: $($run.status), Conclusion: $($run.conclusion)"
            $found = $true
            if ($run.status -eq 'completed') {
                Write-Host "CI COMPLETED! Conclusion: $($run.conclusion)"
                Write-Host "RunID: $($run.id)"
                exit 0
            }
            break
        }
    }
    if (-not $found) {
        Write-Host "[$i] No CI run found yet for $sha on v2 branch..."
    }
}
Write-Host "Timeout after 10 minutes"
