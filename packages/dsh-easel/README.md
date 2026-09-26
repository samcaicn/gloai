# dsh-easel

AiMarketing 宿主插件：把 [Easel](https://github.com/ZJU-REAL/Easel)（ZJU-REAL 开源的 AI 社交媒体智能体）嵌入桌面端。

Easel 是 **Python（FastAPI + React）进程**，不是 Cordis 插件，无法编译进 Harness runtime。本插件只做三件事：

1. 在设置区注册 "Easel" 入口（`settings.section` slot）；
2. 通过 `globalThis.dshDesktop.openEasel()` 让主进程拉起 `easel web` 并在独立窗口中加载；
3. 提供内嵌 iframe 预览（被沙箱拦截时用独立窗口兜底）。

宿主侧实现：`src/main/easel-manager.ts`；IPC：`easel:open` / `easel:status`；preload 桥：`dshDesktop.openEasel`。

## 目录关系

| 路径 | 说明 |
| --- | --- |
| `packages/easel/` | vendor 的 Easel 上游源码（Python CLI + `web/` 前端） |
| `packages/dsh-easel/` | 本插件（Cordis 客户端插件，JS，不参与 `npm run typecheck`） |
| `packages/easel/.venv/` | Python 虚拟环境，运行时生成，已 gitignore |

## 首次使用（Windows）

```bash
# 1. 建虚拟环境（Python >= 3.10）
"C:/Users/User/AppData/Local/Programs/Python/Python312/python.exe" -m venv packages/easel/.venv

# 2. 装依赖（清华镜像目前对 pip 返回 403，用阿里云或官方源）
packages/easel/.venv/Scripts/python.exe -m pip install -e packages/easel \
  -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com

# 3. 构建 Easel 前端（缺 dist 时 app.py 只服务兜底静态页，看不到完整 UI）
cd packages/easel/web/frontend && npm i && npm run build
```

然后在设置区点 "Easel"。

对话/发布能力还需：

- `packages/easel/.env` 配 API key；
- 另开网关：`packages/easel/.venv/Scripts/python.exe -m easel gateway start`（端口 18789）；
- FFmpeg + Playwright Chromium（媒体与发布功能）。

## 端口与进程

- Web UI：`127.0.0.1:7860`（uvicorn 实际绑 `0.0.0.0:7860`，读 `EASEL_PORT`）。桌面本地使用可接受；要强制 loopback 需改上游 `web/app.py`（未修改 vendored 源码）。
- 进程链：`python -m easel web` → `web/app.py` → uvicorn。停止时按进程树终止，否则 uvicorn 残留会占住 7860 导致再次打开失败。

## 排障

| 现象 | 原因 / 处理 |
| --- | --- |
| 提示 "Easel virtual environment not found" | `.venv` 未建，按上面步骤 1、2 |
| 启动失败并带 stderr 片段 | 依赖缺失或端口被占；`netstat -ano \| findstr 7860` 查残留进程 |
| 独立窗口只有静态兜底页 | `web/frontend/dist` 未构建，执行步骤 3 |
| 设置区看不到入口 | 确认 root `package.json` 已声明 `"dsh-easel": "file:packages/dsh-easel"` 且已 `npm install` |
| 内嵌预览空白 | 浏览器沙箱拦截 iframe，用 "打开 Easel" 独立窗口 |
