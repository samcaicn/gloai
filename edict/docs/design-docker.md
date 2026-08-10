# Docker 一键体验 详细设计

> 任务ID：JJC-20260303-001 · 第一批 · 功能3

## 1. 目标

```bash
docker compose up
# 浏览器打开 http://localhost:3926 即可体验完整看板
```

## 2. Dockerfile

```dockerfile
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY edict/frontend/package*.json ./
RUN npm ci
COPY edict/frontend/ ./
RUN npm run build

FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY edict/ ./edict/
COPY docker/demo_data/ ./data/
COPY --from=frontend /app/frontend/dist ./edict/frontend/dist
EXPOSE 3926
ENV DEMO_MODE=true
CMD ["python", "edict/server.py"]
```

## 3. docker-compose.yml

```yaml
version: "3.8"
services:
  dashboard:
    build: .
    ports:
      - "3926:3926"
    environment:
      - DEMO_MODE=true
      - HOST=0.0.0.0
      - PORT=3926
    volumes:
      - dashboard-data:/app/data
    restart: unless-stopped

volumes:
  dashboard-data:
```

## 4. Demo 数据

已有 `docker/demo_data/` 目录中的数据文件：
- `tasks_source.json` — 示例旨意
- `officials_stats.json` — 官员统计
- `agent_config.json` — Agent 配置
- `live_status.json` — 实时状态
- `morning_brief.json` — 早朝简报
- `model_change_log.json` — 模型切换记录

`DEMO_MODE=true` 时 server.py 从 `/app/data/` 加载这些文件，API 可读可写但不持久化到宿主机（除非挂载 volume）。

## 5. CI/CD（GitHub Actions）

```yaml
# .github/workflows/docker.yml
name: Build & Push Docker
on:
  push:
    branches: [main]
    tags: ['v*']
jobs:
  docker:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}
      - uses: docker/build-push-action@v5
        with:
          push: true
          tags: |
            openclaw/sansheng-dashboard:latest
            openclaw/sansheng-dashboard:${{ github.sha }}
```

## 6. README 补充

在项目根 README 添加 Quick Start 区块：

```markdown
## 🐳 Quick Start (Docker)

​```bash
git clone https://github.com/xxx/openclaw-sansheng-liubu.git
cd openclaw-sansheng-liubu
docker compose up
# 打开 http://localhost:3926
​```
```
