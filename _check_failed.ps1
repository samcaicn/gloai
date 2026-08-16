$runId = "29622401170"
$url = "https://api.github.com/repos/samcaicn/gloai/actions/runs/$runId/jobs"
try {
    $jobs = Invoke-RestMethod -Uri $url -Method Get -ErrorAction Stop
    foreach ($job in $jobs.jobs) {
        Write-Host "========================================"
        Write-Host "Job: $($job.name)"
        Write-Host "Status: $($job.status) / Conclusion: $($job.conclusion)"
        Write-Host ""
        if ($job.steps) {
            foreach ($step in $job.steps) {
                $mark = if ($step.conclusion -eq "success") { "OK" } elseif ($step.conclusion -eq "skipped") { "--" } else { "FAIL" }
                Write-Host "  [$mark] $($step.name) => $($step.conclusion)"
            }
        }
        Write-Host ""
    }
} catch {
    Write-Host "Error: $_"
}
