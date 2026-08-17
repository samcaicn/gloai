# AGENTS.md

DeepSeek Harness Rust is a layered Cargo workspace. Read [docs/architecture.md](docs/architecture.md) before changing crate boundaries. Product semantics (session log, turn/step loop, model-visible ⟺ logged) follow DeepSeek Harness; physical crate layout follows BitFun.

## Layers

Dependencies flow top to bottom. A crate may skip a layer downward; it must not depend upward.

| # | Layer | Path | Owns |
|---|---|---|---|
| 1 | Apps & interfaces | `src/apps/*`, `src/crates/interfaces` | CLI, ACP stdio |
| 2 | Assembly | `src/crates/assembly` | Delivery profiles and wiring |
| 3 | Adapters | `src/crates/adapters` | Provider protocol translation |
| 4 | Services | `src/crates/services` | OS, process, persist, credentials |
| 5 | Execution | `src/crates/execution` | Loop, session log, tools, prompt, stream |
| 6 | Contracts | `src/crates/contracts` | DTOs, events, ports |

## Rules

- Registrations are effects: `register()` returns a disposer; dropping it removes the contribution.
- Waterfall listeners must call `next()`. Returning without it short-circuits the chain.
- Anything that reaches a model request must be reconstructable from the session log. The loop asserts this before `LlmPort::stream`.
- `SESSION_FORMAT_VERSION` stays `0` with no compatibility promise; backends reject any other version.
- Capability seams are complete: Service Definition (port) / Provider / Consumer. One role alone is not a seam.
- Deployment-varying choices are `Config` fields, not hardcoded tunables. Protocol constants and security invariants stay fixed.
- Misconfiguration fails at load or at the earliest resolvable point; never skip a missing referent.
- Opaque ids are newtypes (`SessionId`, `CallId`, `MessageId`), never bare `String` at crate boundaries.
- Trust Rust types at same-process typed boundaries. Validate at config, model/tool JSON, durable/file, process, and wire edges.
- `tokio/full` is forbidden. Workspace `reqwest` keeps default features off; the DeepSeek adapter selects rustls + json + stream.
- Logs are English-only, with no emojis.
- Every crate owns tests for the behavior it introduces. Mock LLM covers loop and tools; httpmock covers the DeepSeek adapter.

## Commands

```sh
cargo fmt --all
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
python3 scripts/check-crate-boundaries.py
cargo run -p dsh-cli -- --dump-config
```
