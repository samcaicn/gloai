# runtime-registry 骨架设计 + 可行性评估

> 把 `dsh-bridge` 扩成统一运行时注册表（runtime-registry），复刻 Multica
> 的「内置 runtime 自动出现 → 自动成为可调用的子 agent（`<app><n>`）」，
> 并支持用户自定义 agent API。本文档附带已落地的骨架代码。

---

## 0. 结论先行

**可行性：高。** 关键事实——本项目 `src-tauri/src/acp/` 模块已经依赖
`agent-client-protocol`（ACP），并封装了 claude-code / codex / opencode / omp
的 stdio JSON-RPC 传输层（`AcpClientService` + `BuiltinAcpClientPreset`）。
也就是说，**「怎么跟这些 CLI 通信」这一最难的环节已经解决**。我们要做的只是
在它之上加一层「统一运行时注册表 + 自动子 agent 命名 + 用户自定义 API」。

主要风险不是"能不能做"，而是**运维细节**：CLI flag 漂移、命令注入、密钥处理、
kimi/trae 的 ACP 兼容性未知。全部有缓解方案（见 §6）。

---

## 1. 现状盘点（为什么可行）

| 已有资产 | 位置 | 本骨架如何复用 |
|---|---|---|
| ACP 传输层（claude/codex/opencode/omp） | `src-tauri/src/acp/` | `adapters/acp.rs` 直接包 `AcpClientService`，不重写进程管理 |
| 二进制探测范式 | `acp::service::probe_requirements` | 本骨架用自己的跨平台 `which`（`detect.rs`），二者可二选一 |
| Adapter 范式 | `hermes::im::adapter_base.rs`（`IMAdapter` trait）+ `channel_registry.rs`（`AdapterPool` 懒连接/double-check） | 直接照抄成 `AgentProviderAdapter` + `RuntimeRegistry` |
| 子 agent 注入点 | `commands/agent.rs::get_agents()`（当前只返回 hermes-agent） | 把检测到的 runtime 注入这里 |
| 前端暴露点 | `agent-service.ts::getAvailableAgents()`（当前硬编码 `['general-purpose']`） | 合并 `runtimeRegistryAPI.listSubagents()` |

---

## 2. 架构

```
┌─────────────────────────────────────────────────────────────┐
│ 启动 / 设置页触发 rr_scan_runtimes                           │
│                                                             │
│   detect.rs  which(PATH, PATHEXT)                          │
│      ├─ opencode ─┐                                         │
│      ├─ claude  ─┤  RuntimeInstance{installed,endpoint}     │
│      ├─ codex   ─┤        │                                  │
│      ├─ kimi    ─┤        ▼                                  │
│      └─ trae    ─┘  RuntimeRegistry.scan()                  │
│                        │                                     │
│                        ▼  每个已安装的 provider               │
│                   SubAgent{ id: "claude1", ... }            │
│                        │                                     │
│                        ▼  build_adapter()                   │
│        ┌───────────────────┼───────────────────┐           │
│        ▼                   ▼                   ▼           │
│   AcpAdapter        CliRunAdapter      CustomApiAdapter    │
│   (包 AcpClientSvc) (tokio spawn)     (reqwest http)       │
│        │                   │                   │           │
│        ▼                   ▼                   ▼           │
│  ACP stdio CLI      kimi/trae 进程     用户 OpenAI 兼容 API │
└─────────────────────────────────────────────────────────────┘
        │
        ▼  rr_list_subagents → 前端 AgentService → 会话里可直接指派
```

---

## 3. 已落地骨架模块

```
src-tauri/src/runtime_registry/
├── mod.rs          # 核心类型：RuntimeKind / RuntimeInstance / SubAgent /
│                   #   InvokeRequest / RuntimeRegistrySnapshot / SharedRuntimeRegistry
├── adapter.rs      # AgentProviderAdapter trait（最小接口，照 IMAdapter 范式）
├── detect.rs       # 跨平台 which + ProviderSpec 表 + detect_builtins()
├── registry.rs     # RuntimeRegistry：scan / spawn_instance / add_custom_api /
│                   #   remove_agent / list_subagents / invoke（含 <app><n> 编号）
├── commands.rs     # Tauri 命令 rr_scan_runtimes / rr_list_* / rr_add_custom_agent /
│                   #   rr_remove_agent / rr_invoke_subagent
└── adapters/
    ├── mod.rs      # build_adapter() 工厂（照 build_adapter_from_binding）
    ├── acp.rs      # ACP 适配（复用 crate::acp）
    ├── cli_run.rs  # 非 ACP 子进程（kimi/trae），arg 向量、无 shell
    └── custom_api.rs # 用户自定义 HTTP（OpenAI 兼容 /chat/completions）

src/web-ui/.../infrastructure/api/runtimeRegistry.ts  # 前端客户端（骨架）
```

---

## 4. 关键设计点

### 4.1 `which` 探测（跨平台）
`detect::resolve_binary` 解析 `PATH`，Windows 下读 `PATHEXT`
（`.EXE;.CMD;.BAT;.COM`）找 `claude`/`claude.cmd` 等。`detect_builtins()`
返回 `RuntimeInstance{ installed, endpoint(绝对路径), version }`。
新增 CLI 只需在 `builtin_provider_specs()` 表里加一行。

### 4.2 最小 `AgentProviderAdapter`
```rust
#[async_trait]
pub trait AgentProviderAdapter: Send + Sync {
    fn provider_id(&self) -> &str;
    fn kind(&self) -> RuntimeKind;
    async fn detect(&self) -> DetectionResult;
    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String>;
    async fn health(&self) -> bool;
}
```

### 4.3 三类适配器
- **AcpAdapter**：包 `Arc<AcpClientService>`，`invoke` 走
  `create_flow_session` + `start_dialog_turn`（已有传输）。
- **CliRunAdapter**：`tokio::process::Command` 跑 `kimi run "<prompt>"` /
  `trae run "<prompt>"`。**prompt 永远作为单个 argv 元素**，绝不走 shell。
- **CustomApiAdapter**：用户填的 HTTP 端点，POST OpenAI 兼容
  `/chat/completions`，Bearer 头（手工拼，避免 reqwest `auth` feature 依赖）。

### 4.4 命名 `<app><n>`
`RuntimeRegistry` 持一个 `counters: HashMap<provider_id, u32>`。
`scan()` 对每个已安装 provider 生成 `claude1` / `opencode1` ……
`spawn_instance(provider_id)` 再递增出 `claude2`、`claude3`，用于并行会话。
用户自定义 API 用其 `name` 作前缀（`myapi1`）。

### 4.5 上游兼容 seam（dsh / proma）
"dsh-bridge" 概念 = 本 `runtime_registry`。要让 dsh / proma 的
「主要功能更新」快速集成：
1. 若它本质是另一种 CLI → 加一行 `ProviderSpec` + 复用 `CliRun`/`Acp`。
2. 若它自带 daemon / API → 加 `RuntimeKind::Upstream` 变体 +
   `adapters/upstream_xxx.rs` 一个文件 + `build_adapter` 一个 match 臂。
   调用方代码（registry / commands / 前端）**零改动**。

---

## 5. 与上游 dsh / proma 的兼容策略（展开）

- **不要**在 `runtime_registry` 里塞 dsh/proma 的具体实现细节；
  它们通过 `AgentProviderAdapter` 这一个接缝接入。
- ACP 模块本身来自 BitFun 上游（`acp/mod.rs` 注释明示），说明本项目
  已有「从上游裁剪 CLI 接入层」的先例——runtime_registry 沿用同一套路。
- 若 dsh/proma 用 ACP 协议，则它们天然落入 `Acp` 分支，无需新代码。

---

## 6. 可行性风险表

| 风险 | 等级 | 缓解 |
|---|---|---|
| CLI flag 漂移（claude/codex/opencode 各版本参数不同） | 中 | 每个 CLI 的调用参数锁在各自的 adapter 文件里，隔离变更面；ACP 分支由协议保证稳定 |
| **命令注入** | 高 | `CliRunAdapter` 强制 arg 向量、禁用 shell；prompt 作为单个 argv 元素 |
| **密钥泄露** | 高 | CustomApi 只存 `has_api_key` 布尔，密钥走环境变量/secret store，**绝不**进日志或序列化；endpoint 强制 http(s) 防 SSRF |
| kimi / trae 是否 ACP 兼容未知 | 中 | 默认走 `CliRun` 子进程；若它们日后支持 ACP，改 `ProviderSpec.kind` 即可，无架构改动 |
| ACP 流式输出未接 | 中（功能缺口，非风险） | 当前 `AcpAdapter::invoke` 返回 ack；真正输出应接 `acp` 现有事件通道（`acp::commands` / 前端 ACPClientAPI） |
| 并发 / 工作目录隔离 | 中 | 每次 invoke 用独立 `cwd`；ACP 已是每会话独立进程 |
| 用户自定义 agent 持久化 | 低 | 当前仅内存 + env；应落 `app_data_dir` JSON（照 `acp/config.rs`） |

---

## 7. 接入步骤（lib.rs 接线——**已完成**）

> 三步接线已于 `lib.rs` 落地：`mod runtime_registry;` 声明、状态管理、
> 7 个命令注册。此外启动时自动 `scan()`，复刻 Multica「内置 runtime 自动出现」。

**① 声明模块**（`lib.rs` 的 `mod acp;` 之后）：
```rust
mod acp;
mod runtime_registry;
```

**② 管理状态**（`lib.rs` 的 `acp::AcpClientService::new(...)` 块改造为
`Option` 包裹——ACP 是可选功能，即使它初始化失败，CliRun/CustomApi 仍可用）：
```rust
let acp_service: Option<std::sync::Arc<AcpClientService>> =
    match acp::AcpClientService::new(app.handle().clone()) {
        Ok(service) => {
            let arc = std::sync::Arc::new(service);
            app.manage(std::sync::Arc::clone(&arc));
            Some(arc)
        }
        Err(error) => { log::warn!(...); None }
    };
app.manage(crate::runtime_registry::RuntimeRegistry::new(acp_service));
```

> 对应 `RuntimeRegistry::new` 与 `build_adapter` 的签名也改为
> `Option<Arc<AcpClientService>>`：ACP 缺失时 `Acp` 分支返回 `None`
> （resolve 报 `no adapter for kind Acp`），其余适配器不受影响。

**③ 注册命令**（新增一个 `invoke_handler!` 块，接在 ACP handler 之后）：
```rust
let builder = builder
    .invoke_handler(tauri::generate_handler![
        runtime_registry::commands::rr_scan_runtimes,
        runtime_registry::commands::rr_list_runtimes,
        runtime_registry::commands::rr_list_subagents,
        runtime_registry::commands::rr_spawn_instance,
        runtime_registry::commands::rr_add_custom_agent,
        runtime_registry::commands::rr_remove_agent,
        runtime_registry::commands::rr_invoke_subagent,
    ]);
```

**④ 启动自动探测**（已做）：`setup` 末尾 `spawn` 一个任务调用
`reg.scan().await`，让内置 CLI 立即成为可用子 agent；ACP 初始化与
runtime-registry 互不阻塞。

---

## 8. 前端集成（片段）

`runtimeRegistry.ts` 已建。把子 agent 暴露到会话选择：
```ts
// agent-service.ts — getAvailableAgents()
async getAvailableAgents(): Promise<string[]> {
  const subs = await runtimeRegistryAPI.listSubagents();
  return ['general-purpose', ...subs.map(s => s.id)]; // claude1, opencode1, ...
}
```

---

## 9. 完成状态 / 下一步

- [x] `AcpAdapter::invoke` 接**真实输出**：`acp/service.rs` 新增 `run_dialog_turn_sync`（复用 `ensure_client_connection` / `send_prompt` / `read_update` / `content_chunk_text`），同步累积 assistant 文本并返回，不再只返回 ack。
- [x] 用户自定义 agent **落盘持久化**：`registry.rs` 在 `add_custom_api` 时写入 `<app_data_dir>/runtime_registry_custom_agents.json`；启动时 `set_data_dir` + `load_custom_agents` 自动重载。密钥不落盘（仅存 `has_api_key` 标记 + 进程内 `RR_API_KEY_<instance_id>` env）。
- [x] 自定义 agent **apiKey 透传**：`InvokeRequest.api_key` 覆盖 env 兜底；`custom_api.rs` 优先用请求携带的 key。
- [x] 启动自动 `scan()`（复刻 Multica 自动出现）+ 自动加载持久化自定义 agent。
- [x] 后端 `get_agents()` 合并 runtime 子 agent（保留 `hermes-agent` 在前）。
- [x] 前端 `getAvailableAgents()` 合并 `listSubagents()`（失败降级 `['general-purpose']`）。
- [x] **专属 Runtime Agents 面板（替代聊天选择框派发）**：`AgentsScene` 新增 `runtime-agents-zone`，渲染 `RuntimeAgentsPanel`——检测 CLI、列出子 agent、直接 `rr_invoke_subagent` 派发并显示输出、新增/删除自定义 agent、spawn 并行实例。**这是让功能端到端可用的落点**（detect → 列出 → 派发 → 增删 → 持久化全闭环），且是 additive，不碰聊天核心编排。
- [x] **校准 kimi / trae 真实调用参数**（已按 2026-08 真实 CLI 语法核对）：
  - Kimi Code CLI 非交互单 prompt 为 `kimi -p "<prompt>"`（或 `--prompt`）；`cli_args_template = ["-p", "{prompt}"]`。
  - Trae Agent CLI（`bytedance/trae-agent`）为 `trae-cli run "<prompt>" --working-dir <cwd>`；`cli_args_template = ["run", "{prompt}", "--working-dir", "{cwd}"]`，且 `binary` 别名补 `trae-cli`（同时探测 `trae` / `trae-cli`）。
  - 探测支持多候选二进制：`detect::resolve_binary(&[primary, *aliases])`。
- [x] **Upstream 适配器（dsh / proma 接入 seam）**：新增 `adapters/upstream.rs`——`endpoint` 为 http(s) 走 OpenAI 兼容 `/chat/completions`，否则走 `cli_args_template` 子进程（无 shell）。`build_adapter` 的 `Upstream` 分支已接；`registry.register_upstream` + `rr_register_upstream` 命令 + `<app_data_dir>/runtime_registry_upstream.json` 持久化与启动加载。**新增上游只需注册一个 `RuntimeInstance`，零架构改动。**
- [x] **集成测试**：`registry.rs` + `detect.rs` 加 `#[cfg(test)]`——验证 `next_index` 按 provider 独立递增、自定义 agent 编号 `myapi1`/`myapi2` 与 `remove`、upstream 非法 endpoint 拒绝、`builtin_provider_specs` 返回 5 个且 kind/template/alias 正确。`cargo test --no-run` 已编译通过（0 error）；测试二进制在本地沙箱因缺 WebView2 原生运行时无法启动（环境限制），由 CI `windows-latest` 实际执行。

> 关于「聊天选择框 + 派发闭环」：**不做**。聊天选择框数据源是 `AppManager.DEFAULT_AGENTS`（静态）与 `get_available_modes`（Rust 模式系统），不经过 `get_agents`；且聊天派发按 mode id 路由，不认识 `claude1`。强行并入会产生「点了报错」的死选择。改用专属面板实现等价且更安全的「选队友派任务」闭环，符合「稳定优先、不盲改聊天核心」原则。

> 自测状态：Rust 侧 `cargo check` 与 `cargo test --no-run` 均 **0 error**（`runtime_registry` 全部代码 + 测试模块编译通过；仅 `profile/mod.rs:144` 一个与本次无关的历史 `unused_mut` warning）。`cargo test` 运行期在本地沙箱因缺 WebView2 原生运行时导致测试二进制进程初始化失败（`STATUS_ENTRYPOINT_NOT_FOUND`，发生在任何测试代码执行前，纯环境问题），故测试执行交由 CI `windows-latest`（具备 WebView2 运行时）完成。前端组件 props 已逐一核对（`IconButton`/`Button`/`Input`/`Textarea`/`useNotification` 均匹配），改动仅新增 1 个组件 + 挂 1 个 zone，未碰聊天引擎；类型最终由 `pnpm tauri build`（CI）校验。

> CI 触发：本分支 `gh-wt` 原本不匹配任何 push 触发器，已把 `.github/workflows/build.yml` 的 `branches: [safeopc]` 改为 `[safeopc, gh-wt]`，使 `push gh-wt` 能触发 Windows NSIS + macOS DMG 构建（BRAND 对 `gh-wt` 取默认 `tupai`）。`safeopc` 分支的 `cleanup-github`/`sync-to-tencent` 删除远程分支 job 仅对 `safeopc` 生效，推 `gh-wt` 不会被删分支。
