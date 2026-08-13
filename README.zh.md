# DeepSeek Harness GUI

[English](README.md) | 中文

Made by [BitFun](https://github.com/GCWing/BitFun/).

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 Tauri 2 桌面壳。窗口铬、适配器分层、欢迎页与工作区布局对齐 [BitFun](https://github.com/GCWing/BitFun) 的 desktop + web-ui 设计。会话主界面复用官方 `dsh web` GUI：本应用在工作区目录启动运行时，并把回环 URL 嵌进 iframe。

设计说明：[docs/design.md](docs/design.md)。

## 环境

- Node.js `^22.19 || >=24`
- pnpm 10+
- Rust（`pnpm desktop:dev` / `pnpm desktop:build`）
- DeepSeek API Key（`DEEPSEEK_API_KEY`）
- PATH 上的 `dsh`，或 `npx`（自动回退 `npx --yes @deepseek-ai/dsh@^0.1.0-rc.6`）

## 命令

```sh
pnpm install
pnpm test
pnpm typecheck
pnpm desktop:dev
pnpm desktop:build
```

`pnpm desktop:dev` 会在 1420 端口启动 Vite 并打开 Tauri 窗口。在欢迎页打开工作区后，宿主在该目录启动 `dsh web --host 127.0.0.1 --port 0`，并嵌入 stdout 中的回环 URL。

## 设置

- API Key：优先系统钥匙串，不可用时写入配置文件
- 可选覆盖 harness 可执行文件
- 主题（深色 / 浅色 / 跟随系统）与语言（中 / 英）
- 关闭窗口时最小化到托盘

## 布局（对齐 BitFun）

```
NavBar（38px，macOS 交通灯留白）+ NavPanel
SceneBar + 欢迎 | 会话（dsh web iframe）| 设置
```

UI 组件不直接调用 Tauri。原生能力全部走 `src/infrastructure/adapters`。

## License

MIT
