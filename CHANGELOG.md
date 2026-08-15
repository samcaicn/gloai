# Changelog

All notable changes to SafeOPC will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- (none yet)

### Changed
- (none yet)

### Deprecated
- (none yet)

### Removed
- (none yet)

### Fixed
- (none yet)

### Security
- (none yet)

## [0.1.0] — 2026-08-14

### Added
- **Self-Built organisation**: Company Mode auto-recruits AI employees into roles derived from the task, with a talent market, role inspector, and org editor.
- **Self-Run runtime**: Work-item state machine (planning → execution → review → done + blockers), dependency DAG, approval/risk classification, escalation to human, and visual kanban + office UI.
- **Self-Grown evolution**: History compaction, per-employee evaluation, private experience profiles, and shared playbooks (skills) that new hires inherit.
- **External agent adapters**: Native runtime plus Codex, Claude Code, Cursor, and OpenCode; configurable per role, with subagent profiles and tool-first-use approval.
- **14 channel providers**: Feishu, Telegram, Slack, Discord, DingTalk, Email, Matrix, QQ, WhatsApp, Mochat, plus native browser tools, MCP servers, and CLI/Office UI as entrypoints.
- **Office UI**: Workspace (sessions, kanban, chat, agents, comms, team), Office (visual agent map), Org (architecture, roles, hiring, presets), plus built-in templates (hku_research_lab, corporate, etc.).
- **CLI**: Typer-based commands for project/session/runtime/kanban/agent/org/talent/market/channels, plus interactive slash commands in chat.
- **Market & sharing**: Built-in architecture presets, talent templates from agency-agents, import/export org packages (.opcpkg), and a package manager.
- **Safety model**: Tool risk classification (low/medium/high/critical), shell safety allowlist, permission v2, secret handling via env var, denial memory.
- **Plugins**: office_ui (React + Phaser desktop app) and cli_board (TUI kanban).

### Changed
- Company-mode sessions now recover and resume more seamlessly, preserving agent identity, shared role context, delegation, and review progress.
- Office UI live updates and chat scrolling improved for long-running projects.
- Session grants persist; low-risk actions flow automatically, deferred decisions stay available.

### Fixed
- (none yet)

### Security
- (none yet)
