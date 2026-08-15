# SafeOPC Documentation Index

This directory is the long-form reference for SafeOPC. The project README stays focused on quick start, while documents here go deep on architecture, subsystems, and operations.

## Reading order

If you are new to SafeOPC, read in this order:

1. [Architecture](architecture.md) — 7-layer model, directory map, and core data flow.
2. [Company metadata ownership](company-metadata-ownership.md) — the contract that makes Company Mode safe to evolve.
3. [Native runtime](native-runtime.md) — how `runtime_v2` actually executes work.
4. [Approval and autonomy](approval-and-autonomy.md) — risk classification, permissions v2, and escalation paths.
5. [Work items](work-items.md) and [Company Mode](company-mode.md) — the Company Mode end-to-end story.

If you only have ten minutes, read the README and the architecture doc. The others are reference material you can come back to.

## By audience

### For users

| Doc | What it covers |
|---|---|
| [Channels](channels.md) | Channel providers, install, login, status. |
| [Channel bridge providers](channel-bridges.md) | WhatsApp and Mochat companion bridges. |
| [CLI chat slash commands](cli-chat-slash.md) | Interactive `opc chat` slash reference. |
| [CLI reference](cli-reference.md) | Full `opc` command tree. |
| [Office UI](office-ui.md) | Office UI architecture and extension. |
| [Channels setup](channels-setup.md) | Per-provider step-by-step install. |
| [Troubleshooting](troubleshooting.md) | Common failures and recovery. |
| [Desktop packaging](desktop-packaging.md) | PyInstaller + pywebview desktop build. |

### For operators

| Doc | What it covers |
|---|---|
| [Architecture](architecture.md) | Layer model and component map. |
| [Data layout](data-layout.md) | `.opc/` directory layout and `OPC_HOME` resolution. |
| [Native runtime](native-runtime.md) | Runtime v2 configuration reference. |
| [Approval and autonomy](approval-and-autonomy.md) | Autonomy policy and audit surface. |
| [Security model](security.md) | Risk levels, secret handling, sandbox posture. |
| [Troubleshooting](troubleshooting.md) | Stuck tasks, SQLite locks, channel outages. |

### For developers

| Doc | What it covers |
|---|---|
| [Architecture](architecture.md) | Where to put new code. |
| [Work items](work-items.md) | WorkItem state machine and metadata rules. |
| [Company Mode](company-mode.md) | Org runtime, seats, escalation, reorg. |
| [Agents and skills](agents-and-skills.md) | External agent adapters and the `opc-collab` skill. |
| [Memory and evolution](memory-and-evolution.md) | Session / focused / durable memory, employee evolution. |
| [Market and packages](market-and-packages.md) | Architecture presets and `.opcpkg` format. |
| [Browser and MCP](browser-and-mcp.md) | Browser tool and MCP client. |
| [Company metadata ownership](company-metadata-ownership.md) | WorkItem vs runtime Task ownership contract. |
| [Development](development.md) | Tests, code style, deprecations, contribution flow. |

## Source-of-truth conventions

These documents follow a "code is the contract" pattern. When the prose disagrees with the code, the code wins, and the doc is updated to match. Each doc points to the canonical source file near the top.

| Topic | Source file |
|---|---|
| Slash commands | `_SLASH_COMMANDS` in `opc/cli/app.py` |
| Channel providers | `opc/channels/provider_registry.py` (`login_summary` field) |
| Metadata ownership | `opc/layer2_organization/metadata_ownership.py` |
| Skills registry | `opc/layer3_agent/skill_installer.py` |
| Native runtime | `opc/layer3_agent/runtime_v2/` |
| Approval flow | `opc/layer2_organization/approval.py` |
| Browser tools | `opc/layer4_tools/browser.py` |
| MCP client | `opc/mcp_client.py` |
| Market presets | `opc/market/architecture_registry.py` |
| CLI board | `opc/plugins/cli_board/__init__.py` |

## Project root pointers

- `README.md` — quick start, screenshots, configuration tour, troubleshooting basics.
- `README.zh-CN.md` — Simplified Chinese mirror of the README.
- `CHANGELOG.md` — release notes (Keep a Changelog format).
- `pyproject.toml` — package metadata, extras, entry points.
- `packaging/DESKTOP_PACKAGING.md` — desktop build notes (Chinese; see [desktop-packaging.md](desktop-packaging.md) for the English mirror).

## Contributing

See [development.md](development.md) for the local dev loop, test command, code style, and deprecation policy. New docs in this directory should follow the same source-of-truth convention and cross-link related docs at the top.
