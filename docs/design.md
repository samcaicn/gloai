# DeepSeek Harness Plugin MCP — Design

Any MCP-capable agent (Cursor, Claude Desktop, Codex, Copilot, OpenCode, and others) can discover, inspect, install, and execute [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) plugins through the Model Context Protocol.

Official ecosystem index: [github.com/topics/dsh-plugin](https://github.com/topics/dsh-plugin).

## Problem

DSH plugins are Cordis bundles (`package.json` `dsh.bundle.patch` → `cordis.patch.yml`). They register tools, UI, skills, and other capabilities on a live Harness context (`ctx.tools`, `ctx.skills`, …). Other agents speak MCP, not Cordis. DSH already ships an MCP **client** (`@deepseek-ai/dsh-mcp-client`) that pulls foreign MCP tools into DSH. This project is the inverse: it **publishes** DSH plugins to foreign agents.

## Goals

1. Catalog every public repository tagged `dsh-plugin` and let an agent search, read, and classify them without running DSH.
2. Install and remove plugins into a real DSH profile through `dsh plugin --profile <name> add|remove`.
3. Execute plugin (and composition) tools by booting a DSH profile that mounts this package as a Cordis plugin, then bridging `ctx.tools` onto MCP.
4. Run as stdio (spawned by the agent) or Streamable HTTP (shared server). The same process is also a DSH bundle so `dsh plugin add github:<owner>/deepseek-harness-plugin-mcp` turns a running Harness into an MCP server.

## Non-goals

- Reimplementing Cordis or a subset of DSH services inside this process.
- Rendering DSH Web UI / TUI plugins inside the foreign agent. Those plugins are catalogued and installable; their UI stays in DSH.
- Publishing DSH first-party packages to npm. Runtime always uses a local `dsh` binary and/or `DSH_ROOT` checkout.

## Architecture

Three planes share one MCP server.

```text
Agent  --stdio/http MCP-->  PluginMcpServer
                              ├─ Catalog plane   GitHub topic dsh-plugin + contents API
                              ├─ Profile plane   `dsh plugin` in $DSH_HOME/profiles/<name>
                              └─ Runtime plane   spawn `dsh --profile <name>`
                                                   └─ this package as Cordis plugin
                                                        └─ HTTP MCP  (ctx.tools ↔ tools/call)
                                                             └─ stdio server re-exports dsh__*
```

### Catalog plane

- Source of truth: GitHub Search `q=topic:dsh-plugin`, paginated (`per_page=100`) until exhausted.
- Cache: `$DSH_PLUGIN_MCP_CACHE_DIR/catalog.json` (default `~/.dsh-plugin-mcp/catalog.json`), TTL 30 minutes.
- Inspect: `GET /repos/{owner}/{repo}/contents/package.json`, `/readme`, `cordis.patch.yml`, root listing. Classification is derived from those files plus topics and description.
- Auth: `GITHUB_TOKEN` or `GH_TOKEN`; otherwise unauthenticated (stricter rate limit). Errors name the rate-limit case.

### Profile plane

- Requires `dsh` on `PATH`.
- `dsh plugin --profile <name> add github:<owner>/<repo>` and `remove <package>` are the only install mechanism. Relative path specs are anchored like the DSH CLI.
- Default profile name: `mcp-bridge` (configurable). Missing profiles are created by `dsh plugin` itself.

### Runtime plane

A DSH plugin is not a standalone Node module: it expects the Harness tree. Execution therefore **boots DSH**.

1. Ensure this package is installed into the target profile (`dsh plugin add <this-dir-or-git-spec>`).
2. Install requested plugin specs the same way.
3. Spawn `dsh --profile <name>` with `DSH_PLUGIN_MCP_PORT` and `DSH_PLUGIN_MCP_CATALOG=0`.
4. The Cordis plugin starts Streamable HTTP MCP on that port and mirrors `ctx.tools` (event `tools/change` → `notifications/tools/list_changed`).
5. The stdio server connects as an MCP client and re-exports those tools as `dsh__<rawName>` (DeepSeek function-name normalization: `[A-Za-z0-9_-]`, max 64, hash suffix on collision).

`bridgeMode` is `all`: every tool the composition registered, first-party and third-party. The composition is the unit a plugin actually runs in. Control-plane names (`dsh_plugin_*`, `dsh_runtime_*`) are never re-exported, so a catalog-enabled child cannot recurse.

Install and runtime are off until `--allow-install` / `--allow-runtime` (or the matching env vars). Catalog stays available with no flags.

## MCP surface

### Tools (control plane)

| Name | Purpose |
|---|---|
| `dsh_plugin_status` | GitHub auth, cache, `dsh` binary, `DSH_ROOT`, profile, runtime |
| `dsh_plugin_refresh_catalog` | Force-fetch the topic |
| `dsh_plugin_list` | Paginated catalog, optional kind/language/star filters |
| `dsh_plugin_search` | In-memory search over the cached catalog |
| `dsh_plugin_get` | Repository card |
| `dsh_plugin_inspect` | package.json, patch, README, skills, kinds, install spec |
| `dsh_plugin_readme` | Full README text |
| `dsh_plugin_list_installed` | Profile bundle list |
| `dsh_plugin_install` | `dsh plugin add` |
| `dsh_plugin_uninstall` | `dsh plugin remove` |
| `dsh_runtime_start` | Boot DSH + bridge |
| `dsh_runtime_stop` | Tear down the child |
| `dsh_runtime_load` | Add a plugin to the live profile and restart the child |
| `dsh_runtime_unload` | Remove a plugin and restart the child |
| `dsh_runtime_list_tools` | Currently bridged `dsh__*` names |

Dynamic tools: `dsh__<name>` after `dsh_runtime_start`.

### Resources

- `dsh-plugin://catalog`
- `dsh-plugin://github/{owner}/{repo}`
- `dsh-plugin://github/{owner}/{repo}/readme`
- `dsh-plugin://github/{owner}/{repo}/package.json`
- `dsh-plugin://github/{owner}/{repo}/cordis.patch.yml`
- `dsh-plugin://installed/{profile}`
- `dsh-plugin://runtime/tools`

### Prompts

- `search-dsh-plugins` (task)
- `install-dsh-plugin` (spec)
- `use-dsh-plugin` (spec, task)

## Security

- Catalog is read-only GitHub HTTP.
- Install runs `pnpm` inside the profile directory (same trust model as `dsh plugin add`).
- Runtime executes plugin code with the privileges of the spawned `dsh` process.
- Both mutating planes require an explicit allow flag. The server refuses those tools with an actionable error when the flag is off.

## Dual entry

| Entry | Role |
|---|---|
| `dsh-plugin-mcp` / `deepseek-harness-plugin-mcp` | Agent-spawned stdio or `--http` server |
| `cordis.patch.yml` + `export apply` | In-tree DSH bundle that serves Streamable HTTP from a live `ctx` |

## Testing

Unit tests cover classification, catalog pagination/caching, inspect parsing, CLI argument/env resolution, DSH CLI command construction, tool-name normalization, tool-result bridging, and an in-memory MCP session (list/call/resources/prompts). Runtime spawn and GitHub are injected so tests do not need a live Harness or network.
