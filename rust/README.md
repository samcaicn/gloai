# DeepSeek Harness (Rust)

English | [中文](README.zh.md)

Made by [BitFun](https://github.com/GCWing/BitFun/).

Rust implementation of [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): an append-only session log, a turn/step agent loop, capability ports, and a DeepSeek streaming adapter. Crate layout follows BitFun's layered backend (contracts → execution → services → adapters → assembly → apps).

This is not a Cordis port. Composition is Cargo features plus an assembly crate. Live extension uses typed events and waterfalls. Concrete OS and provider behavior stays behind ports.

## Requirements

- Rust 1.88+
- A DeepSeek API key for live runs (`DEEPSEEK_API_KEY`)

## Quick start

```sh
cp .env.example .env   # set DEEPSEEK_API_KEY
cargo run -p dsh-cli -- --profile headless "Summarize this repository."
```

Dump the assembled runtime without calling a model:

```sh
cargo run -p dsh-cli -- --dump-config
```

ACP stdio server:

```sh
cargo run -p dsh-cli -- acp
```

Tests use an in-process mock LLM and do not need a key:

```sh
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
python3 scripts/check-crate-boundaries.py
```

## Layout

Dependencies flow downward only. See [docs/architecture.md](docs/architecture.md).

| Layer | Path | Owns |
|---|---|---|
| Apps | `src/apps/cli` | `dsh` CLI and ACP stdio entry |
| Interfaces | `src/crates/interfaces/acp` | Agent Client Protocol server |
| Assembly | `src/crates/assembly/core` | Delivery profiles and wiring |
| Adapters | `src/crates/adapters` | DeepSeek SSE + mock LLM |
| Services | `src/crates/services` | FS, subprocess, shell, credentials, JSONL persist |
| Execution | `src/crates/execution` | Session log, loop, tools, prompt, stream assembler |
| Contracts | `src/crates/contracts` | DTOs, events, ports |

## Configuration

Environment (also loaded from `.env` in cwd and the harness home):

| Variable | Meaning |
|---|---|
| `DEEPSEEK_API_KEY` | Credential reference target (never stored in config) |
| `DEEPSEEK_BASE_URL` | Chat-completions base; `/chat/completions` is appended |
| `DSH_MODEL` | Wire model id (default `deepseek-chat`) |
| `DSH_PROVIDER` | Provider route (default `deepseek`) |
| `DSH_HOME` | Harness home (default `~/.dsh-rust`) |

`dsh --profile headless` selects the headless delivery profile: agent loop, local FS/shell tools, JSONL persistence, DeepSeek adapter.

## License

MIT. Session-log and loop semantics follow DeepSeek Harness; crate boundaries follow BitFun.
