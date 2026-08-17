# AGENTS.md

This repository is an MCP server (and optional DSH bundle) that publishes DeepSeek Harness plugins to foreign agents.

- Catalog source is GitHub Search `topic:dsh-plugin`. Do not hardcode a plugin list.
- Install goes through `dsh plugin --profile <name> add|remove`. Do not invent a second installer.
- Runtime boots a real DSH profile and bridges `ctx.tools`. Do not reimplement Cordis.
- `--allow-install` and `--allow-runtime` stay off by default.
- Keep README.md and README.zh.md in sync.
