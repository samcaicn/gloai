# DeepSeek Harness Plugin MCP

Made by [BitFun](https://github.com/GCWing/BitFun/)。

让**任意 agent** 通过 MCP 发现、检视、安装并运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 插件的服务器。

目录来源：[github.com/topics/dsh-plugin](https://github.com/topics/dsh-plugin)。

[English](README.md) | 中文

## 它做什么

DSH 插件是 Cordis 组合包（`dsh.bundle.patch` → `cordis.patch.yml`）。其他 agent 说 MCP，不说 Cordis。本项目是 `@deepseek-ai/dsh-mcp-client` 的反向：把 DSH 插件发布给外部 agent。

| 平面 | 是否需要 DSH | agent 能做什么 |
|---|---|---|
| 目录 | 否 | 搜索/列出/检视所有公开 `dsh-plugin` 仓库，读取 README 与 `cordis.patch.yml` |
| Profile | PATH 上有 `dsh` | 对真实 profile 执行 `dsh plugin add` / `remove` |
| 运行时 | PATH 上有 `dsh` 且 `--allow-runtime` | 启动该 profile，把组合包工具桥成 `dsh__*` |

UI / TUI / 皮肤类插件可以检索和安装，不会变成 MCP 工具；界面仍在 DSH 里。

## 安装

```bash
npm install -g deepseek-harness-plugin-mcp
# 或
npx deepseek-harness-plugin-mcp --help
```

需要 Node `^22.19 || >=24`。

## 接到 agent

### Cursor / Claude Desktop / Claude Code / Codex（stdio）

```json
{
  "mcpServers": {
    "dsh-plugins": {
      "command": "npx",
      "args": ["-y", "deepseek-harness-plugin-mcp"],
      "env": {
        "GITHUB_TOKEN": "ghp_建议填写以免限流"
      }
    }
  }
}
```

打开安装与运行时（会执行 `dsh plugin` 以及插件代码）：

```json
{
  "mcpServers": {
    "dsh-plugins": {
      "command": "npx",
      "args": ["-y", "deepseek-harness-plugin-mcp", "--allow-install", "--allow-runtime"],
      "env": {
        "GITHUB_TOKEN": "ghp_…",
        "DSH_PLUGIN_MCP_PROFILE": "mcp-bridge"
      }
    }
  }
}
```

Profile 与运行时平面要求 `dsh` 在 `PATH` 上。

### Streamable HTTP

```bash
dsh-plugin-mcp --http --port 8765 --allow-runtime
```

把 agent 指到 `http://127.0.0.1:8765/mcp`。`GET /health` 用于探活。

### 装进 DeepSeek Harness

本仓库本身也是 DSH 组合包：

```bash
dsh plugin --profile web add github:bobleer/deepseek-harness-plugin-mcp
dsh --profile web
```

运行中的 Harness 会提供 Streamable HTTP MCP（默认 `http://127.0.0.1:8765/mcp`），并镜像 `ctx.tools`——组合包里注册的一等工具和第三方工具都会出现。

## MCP 工具

控制面：

- `dsh_plugin_status` / `dsh_plugin_refresh_catalog` / `dsh_plugin_list` / `dsh_plugin_search`
- `dsh_plugin_get` / `dsh_plugin_inspect` / `dsh_plugin_readme`
- `dsh_plugin_list_installed` / `dsh_plugin_install` / `dsh_plugin_uninstall`
- `dsh_runtime_start` / `dsh_runtime_stop` / `dsh_runtime_load` / `dsh_runtime_unload` / `dsh_runtime_list_tools`

`dsh_runtime_start` 之后，DSH 工具以 `dsh__<name>` 出现。

资源：`dsh-plugin://catalog`、`dsh-plugin://github/{owner}/{repo}`（以及 `/readme`、`/package.json`、`/cordis.patch.yml`）、`dsh-plugin://installed/{profile}`、`dsh-plugin://runtime/tools`。

提示词：`search-dsh-plugins`、`install-dsh-plugin`、`use-dsh-plugin`。

## 标志与环境变量

| 标志 / 环境变量 | 默认 | 含义 |
|---|---|---|
| `--http` | stdio | Streamable HTTP |
| `--host` / `DSH_PLUGIN_MCP_HOST` | `127.0.0.1` | 监听地址 |
| `--port` / `DSH_PLUGIN_MCP_PORT` | `8765` | 端口 |
| `--allow-install` / `DSH_PLUGIN_MCP_ALLOW_INSTALL` | 关 | `dsh plugin add/remove` |
| `--allow-runtime` / `DSH_PLUGIN_MCP_ALLOW_RUNTIME` | 关 | 拉起 DSH 并桥接工具 |
| `--profile` / `DSH_PLUGIN_MCP_PROFILE` | `mcp-bridge` | 目标 profile |
| `--dsh-root` / `DSH_ROOT` | 未设 | Harness checkout（status 中展示） |
| `--cache-dir` / `DSH_PLUGIN_MCP_CACHE_DIR` | `~/.dsh-plugin-mcp` | 目录缓存 |
| `--no-catalog` / `DSH_PLUGIN_MCP_CATALOG=0` | 开 | 关闭 GitHub 目录工具 |
| `GITHUB_TOKEN` / `GH_TOKEN` | 未设 | GitHub API 认证 |

安装与运行时默认关闭，需显式打开。目录平面始终可用。

## 典型流程

1. 用任务关键词调用 `dsh_plugin_search`。
2. 对 `owner/repo` 调用 `dsh_plugin_inspect`。优先 `isDshBundle: true`。
3. 可选：`dsh_plugin_install`，spec 为 `github:owner/repo`。
4. `dsh_runtime_start`，`plugins: ["github:owner/repo"]`。
5. 调用列出的 `dsh__*` 工具。

## 开发

```bash
npm install
npm run check
```

设计见 [docs/design.md](docs/design.md)。

## License

MIT
