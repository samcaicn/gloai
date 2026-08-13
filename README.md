# DeepSeek Harness Plugin MCP

MCP server that lets **any agent** discover, inspect, install, and run [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) plugins.

Catalog source: [github.com/topics/dsh-plugin](https://github.com/topics/dsh-plugin).

English | [中文](README.zh.md)

## What it does

DSH plugins are Cordis bundles (`dsh.bundle.patch` → `cordis.patch.yml`). Foreign agents speak MCP, not Cordis. This server is the inverse of `@deepseek-ai/dsh-mcp-client`:

| Plane | Needs DSH? | What the agent can do |
|---|---|---|
| Catalog | No | Search/list/inspect every public `dsh-plugin` repo, read README and `cordis.patch.yml` |
| Profile | `dsh` on PATH | `dsh plugin add` / `remove` into a real profile |
| Runtime | `dsh` on PATH + `--allow-runtime` | Boot that profile and call composition tools as `dsh__*` |

UI/TUI/skin plugins are catalogued and installable. They do not become MCP tools; their UI stays in DSH.

## Install

```bash
npm install -g deepseek-harness-plugin-mcp
# or
npx deepseek-harness-plugin-mcp --help
```

Node `^22.19 || >=24`.

## Wire it into an agent

### Cursor / Claude Desktop / Claude Code / Codex (stdio)

```json
{
  "mcpServers": {
    "dsh-plugins": {
      "command": "npx",
      "args": ["-y", "deepseek-harness-plugin-mcp"],
      "env": {
        "GITHUB_TOKEN": "ghp_optional_but_recommended"
      }
    }
  }
}
```

Enable install and runtime (this runs `dsh plugin` and plugin code):

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

`dsh` must be on `PATH` for the profile and runtime planes.

### Streamable HTTP

```bash
dsh-plugin-mcp --http --port 8765 --allow-runtime
```

Point the agent at `http://127.0.0.1:8765/mcp`. `GET /health` is a process liveness check.

### Inside DeepSeek Harness

This package is itself a DSH bundle:

```bash
dsh plugin --profile web add github:bobleer/deepseek-harness-plugin-mcp
dsh --profile web
```

The live Harness then serves Streamable HTTP MCP (default `http://127.0.0.1:8765/mcp`) and mirrors `ctx.tools` — every first-party and third-party tool the composition registered.

## MCP tools

Control plane:

- `dsh_plugin_status` / `dsh_plugin_refresh_catalog` / `dsh_plugin_list` / `dsh_plugin_search`
- `dsh_plugin_get` / `dsh_plugin_inspect` / `dsh_plugin_readme`
- `dsh_plugin_list_installed` / `dsh_plugin_install` / `dsh_plugin_uninstall`
- `dsh_runtime_start` / `dsh_runtime_stop` / `dsh_runtime_load` / `dsh_runtime_unload` / `dsh_runtime_list_tools`

After `dsh_runtime_start`, DSH tools appear as `dsh__<name>`.

Resources: `dsh-plugin://catalog`, `dsh-plugin://github/{owner}/{repo}` (+ `/readme`, `/package.json`, `/cordis.patch.yml`), `dsh-plugin://installed/{profile}`, `dsh-plugin://runtime/tools`.

Prompts: `search-dsh-plugins`, `install-dsh-plugin`, `use-dsh-plugin`.

## Flags and env

| Flag / env | Default | Meaning |
|---|---|---|
| `--http` | stdio | Streamable HTTP |
| `--host` / `DSH_PLUGIN_MCP_HOST` | `127.0.0.1` | Bind address |
| `--port` / `DSH_PLUGIN_MCP_PORT` | `8765` | Bind port |
| `--allow-install` / `DSH_PLUGIN_MCP_ALLOW_INSTALL` | off | `dsh plugin add/remove` |
| `--allow-runtime` / `DSH_PLUGIN_MCP_ALLOW_RUNTIME` | off | Spawn DSH and bridge tools |
| `--profile` / `DSH_PLUGIN_MCP_PROFILE` | `mcp-bridge` | Target profile |
| `--dsh-root` / `DSH_ROOT` | unset | Harness checkout (informational in status) |
| `--cache-dir` / `DSH_PLUGIN_MCP_CACHE_DIR` | `~/.dsh-plugin-mcp` | Catalog cache |
| `--no-catalog` / `DSH_PLUGIN_MCP_CATALOG=0` | on | Disable GitHub catalog tools |
| `GITHUB_TOKEN` / `GH_TOKEN` | unset | GitHub API auth |

Install and runtime are off until you opt in. Catalog is always available.

## Typical agent flow

1. `dsh_plugin_search` with the task keywords.
2. `dsh_plugin_inspect` on `owner/repo`. Prefer `isDshBundle: true`.
3. Optional: `dsh_plugin_install` with `github:owner/repo`.
4. `dsh_runtime_start` with `plugins: ["github:owner/repo"]`.
5. Call the listed `dsh__*` tools.

## Development

```bash
npm install
npm run check
```

Design: [docs/design.md](docs/design.md).

## License

MIT
