# CI Monitoring Stack

## Quick Start

```bash
cd monitoring
docker-compose up -d
```

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Node Exporter**: http://localhost:9100/metrics

## GitHub Actions Integration

### Option 1: Pushgateway (current workflow)
1. Add secret `PROMETHEUS_PUSHGATEWAY_URL` in GitHub repo settings
   - Value: `http://<your-host>:9091` (run pushgateway separately)

### Option 2: GitHub Actions Exporter (recommended)
```bash
# Add to docker-compose.yml
github-actions-exporter:
  image: ghcr.io/yourorg/github-actions-exporter:latest
  ports:
    - "9494:9494"
  environment:
    - GITHUB_TOKEN=${GITHUB_TOKEN}
    - GITHUB_REPOS=ceoadmin/CEOadmin
```

Then update `prometheus.yml`:
```yaml
- job_name: 'github-actions'
  static_configs:
    - targets: ['github-actions-exporter:9494']
```

## Alerts

Add to `prometheus.yml`:
```yaml
rule_files:
  - 'alerts/*.yml'
```

Create `alerts/ci.yml`:
```yaml
groups:
- name: ci-alerts
  rules:
  - alert: CIBuildFailing
    expr: ci_build_status == 0
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "CI build failing for {{ $labels.repo }}"
      description: "Build has been failing for 5 minutes"
```

## Dashboards

Pre-provisioned: **CI Monitoring** (uid: `ci-monitoring`)

Key metrics:
- `ci_build_status` (0=fail, 1=pass)
- `ci_build_duration_seconds`
- Build frequency (builds/hour)
- Success rate over time