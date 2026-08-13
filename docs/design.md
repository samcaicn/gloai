# deepseek-harness-rust 需求设计

日期：2026-08-13

## 1. 问题

DeepSeek Harness（TypeScript / Cordis）是插件化 agent 运行时：仅追加会话日志、turn/step 循环、能力缝合（Service Definition / Provider / Consumer）、DeepSeek 流式适配器。需要一个独立的 Rust 仓库，使同一套产品语义可以在 BitFun 风格的分层 crate 中编译、测试和交付，而不是把 Cordis 逐 API 搬进 Rust。

参考实现：`/Users/liwenbo/ide_dev/repo/BitFun` 后端（contracts → execution → services → adapters → assembly → apps），以及 `/Users/liwenbo/ide_dev/repo/deepseek-harness` 的会话、循环、工具与 DeepSeek 适配器语义。

## 2. 非目标

- 不移植 Cordis / Loader / HMR / `cordis.yml` 插件树。Rust 组装是 Cargo feature + `dsh-core` wiring。
- 不在本里程碑实现 Web GUI、Typert RPC、E2B、LSP、subagent、compaction、workflow、MCP、远程 workspace。这些能力以端口形式存在，未注册时返回 `NotAvailable`。
- 不承诺与 TypeScript 会话文件交叉读取；`SESSION_FORMAT_VERSION` 仍为 `0`，无兼容保证。JSON 字段命名对齐 dsh，便于日后对照。

## 3. 目标行为

1. `dsh --profile headless "<task>"` 创建会话、跑完 turn、把事件写入 JSONL、把助手文本打到 stdout，退出码反映失败。
2. `dsh --dump-config` 打印已组装的 provider、模型、工具名、持久化目录，不发起模型调用。
3. `dsh acp` 在 stdio 上提供 ACP JSON-RPC：`initialize`、`session/new`、`session/prompt`、`session/cancel`，会话更新来自同一 Agent Runtime。
4. 无 `DEEPSEEK_API_KEY` 时，真实 DeepSeek 路径在解析凭据时失败并带 `MISSING_CREDENTIAL`；测试路径使用 `dsh-llm-mock`，CI 不需要 key。
5. 内置工具 `read` / `write` / `edit` / `glob` / `grep` / `bash` 走 `FsPort` / `ShellPort` / `SubprocessPort`，cwd 为会话工作区。
6. 循环在每次模型请求前断言 `session.derive_messages() == request.messages`。
7. Waterfall 监听者必须调用 `next()`；不调用即短路。该行为有单测。

## 4. 分层与 crate

| Crate | 层 | 职责 |
|---|---|---|
| `dsh-core-types` | contracts | 消息、块、用量、失败码、品牌 id |
| `dsh-events` | contracts | `SessionEvent`、EventBus、waterfall/serial/emit |
| `dsh-runtime-ports` | contracts | `LlmPort`、`FsPort`、`SubprocessPort`、`ShellPort`、`CredentialsPort`、`SessionPersistPort`、`PluginRuntimePort` |
| `dsh-agent-stream` | execution | `BlockAssembler`（与 dsh `assembler.ts` 同一算法） |
| `dsh-session` | execution | 仅追加日志、surface、`derive_messages`、fork 前缀规则 |
| `dsh-system-prompt` | execution | 分段、变量、工具 schema 组装 |
| `dsh-tool-contracts` | execution | 注册表、JSON Schema 校验、pre/execute/post |
| `dsh-agent-runtime` | execution | `Agent`、Inbox、注册表、initiator |
| `dsh-agent-loop` | execution | turn/step 驱动，含并行/独占工具调度 |
| `dsh-credentials` | services | env / `.env` 解析 `CredentialRef` |
| `dsh-persist` | services | JSONL 会话落盘 |
| `dsh-fs` | services | 本地文件系统 + read/write/edit/glob/grep |
| `dsh-subprocess` | services | 进程树 spawn / 超时 / 取消 |
| `dsh-shell` | services | bash 执行器 + `bash` 工具 |
| `dsh-llm-deepseek` | adapters | chat-completions SSE；thinking / tool_calls / 用量映射 |
| `dsh-llm-mock` | adapters | 脚本化流，供测试与 `--llm mock` |
| `dsh-core` | assembly | delivery profile 接线 |
| `dsh-acp` | interfaces | ACP stdio JSON-RPC |
| `dsh-cli` | apps | 启动器 |
| `dsh-testkit` | support | 装配测试运行时 |

依赖方向由 `scripts/check-crate-boundaries.py` 锁定。

## 5. 关键语义（从 dsh 原样保留）

- Inbox 两个队列：`next-turn`、`next-step`。`followup` 入队并唤醒；`steer` 入队下一步并唤醒；`inject` 入队下一步不唤醒。
- 先 `append('turn/start')` 再 claim。首步 enter 被改写成空消息仍关闭 turn，且不花费 step。
- `max-tokens` 在 turn 上粘滞：后续 completed step 不得降级。
- 工具参数：空字符串视为 `{}`；非法 JSON 原样保留并在 execute 前作为校验失败。
- DeepSeek：`content` 在纯 tool-call 回合为 `""` 不是 null；`reasoning_content` 只在带 tool_calls 的回合回传；`prompt_tokens` 含 cache hit，映射时从 `inputTokens` 减去。
- SSE 必须以 `[DONE]` 结束，否则 `STREAM_CLOSED`。空完成（无打开的 block）映射为 `EMPTY_RESPONSE`。

## 6. 配置

显式 resolve，不在 `run()` 里隐藏默认值。`ProductRuntime::resolve(request) -> RuntimeSpec`，再 `RuntimeSpec::boot() -> ProductRuntime`。

`CredentialRef` 只携带环境变量名（默认 `DEEPSEEK_API_KEY`）。配置文件不得写入字面 key。

## 7. 测试

- 每个 execution/service/adapter crate 带行为测试。
- 循环：纯文本 turn、工具回合、取消、空 enter、max-tokens 粘滞、model-visible 断言。
- DeepSeek：serialize、translate、SSE framing、HTTP 错误码（httpmock）。
- 工具：tempdir 上的 read/write/edit/glob/grep/bash。
- CLI：`--dump-config`；`--llm mock` 跑完一个 headless 任务。
- 边界脚本：故意向上依赖必须失败（脚本静态检查，不改仓库成员）。

## 8. 发布

GitHub 仓库 `bobleer/deepseek-harness-rust`，MIT，CI 跑 fmt / clippy / test / dump-config / 边界检查。
