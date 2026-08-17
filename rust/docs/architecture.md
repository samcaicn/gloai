# DeepSeek Harness Rust Architecture

English | [中文](architecture.zh.md)

Read this before changing crate boundaries. Session-log and loop semantics follow DeepSeek Harness; crate layout follows BitFun.

## Goals

1. One Agent Runtime, several delivery forms (`headless` CLI, ACP stdio). Hosts consume ports and the session log; they do not call providers or OS code directly.
2. Model-visible means logged. `derive_messages()` is the only history sent to a model. The loop asserts equality with the request before streaming.
3. Capability seams are complete: a port (definition), a provider (service/adapter), and a consumer (usually a model-facing tool).
4. Composition is compile-time delivery profiles plus runtime config. There is no JS plugin loader in this tree; a later Plugin Host would sit behind `PluginRuntimePort` and fail loud while unregistered.

## Development view

Dependencies flow downward only.

```text
1 Apps & interfaces     dsh-cli, dsh-acp
2 Assembly              dsh-core
3 Adapters              dsh-llm-deepseek, dsh-llm-mock
4 Services              dsh-fs, dsh-subprocess, dsh-shell, dsh-credentials, dsh-persist
5 Execution             dsh-session, dsh-agent-loop, dsh-agent-runtime,
                        dsh-tool-contracts, dsh-system-prompt, dsh-agent-stream
6 Contracts             dsh-core-types, dsh-events, dsh-runtime-ports
```

`scripts/check-crate-boundaries.py` rejects upward Cargo dependencies.

## Turn flow

A **step** is one model request plus the tools it calls. A **turn** is zero or more steps.

```text
turn/start
  claim next-step input plus one queued next-turn message
  assemble prompt sections + tool schemas
  -> agent/pre-step                 reject | enter(messages)
     reject, or a first enter rewritten empty -> close the turn with no step
     step/start
     append entered messages as user/message
     derive model history from the log
     agent/request -> llm/stream -> assistant/chunk* -> assistant/message
     tool/call* -> tools/pre-execute -> tools/execute -> tools/post-execute -> tool/result*
     step/end
     tools owe another request, or next-step input arrived -> claim -> next step
  -> agent/turn-stopping
turn/end
```

`turn/*`, `step/*`, `user/message`, `assistant/*`, and `tool/*` are durable session events. `agent/pre-step`, `agent/request`, `llm/stream`, and the three `tools/*` events are waterfalls; listeners must call `next()`. `agent/turn-stopping` is serial.

## Session log

The log is the source of model context. `Session::derive_messages` walks the surface (`user/message`, `assistant/message`, `tool/result`) honoring `append` and `replace` ops. Raw `assistant/chunk` events preserve replay. `SESSION_FORMAT_VERSION` is `0`; a persistence backend refuses any other header version.

Inbox mutations append `agent/inbox/spliced` before the live projection changes, so observers can recover removed messages.

## Delivery profiles

A profile is a Cargo feature closure on `dsh-core`, not a runtime object.

| Profile | Feature closure |
|---|---|
| `headless` | agent-runtime + tools-fs + tools-shell + llm-deepseek + persist-jsonl |
| `acp` | `headless` plus the ACP stdio server |
| `test` | agent-runtime + tools-fs + tools-shell + llm-mock (no network) |

Missing providers return `PortError { kind: NotAvailable }` and never silently skip.

## Where new behavior goes

| Goal | Mechanism |
|---|---|
| Add a model provider | implement `LlmPort`, register in assembly |
| Add a model-facing tool | `ToolRegistry::register`; schema joins prompt assembly |
| Add filesystem or shell behavior | implement `FsPort` / `ShellPort` / `SubprocessPort` |
| Intercept a request, tool, or turn | waterfall on `agent/*` or `tools/*` |
| Add durable state | extend `SessionEvent` and project from the log |
| Add a product entry | new app or interface crate; wire through `dsh-core` |
