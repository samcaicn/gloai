# tupai - 自进化桌面 AI Agent

> 系统版本: v1.8.9 (Tauri v2 + Rust + React/TypeScript)
> 构建状态: ✅ CI 通过 (NSIS exe + macOS dmg)
> 监控状态: ▶ 运行中 (Hermes 自进化引擎 24h 后台扫描)

---

## 一、真实架构

本项目是 **Tauri v2 桌面应用**，Rust 后端 + React/TypeScript 前端，单进程内集成 Agent 调度 / 技能执行 / 系统自动化 / 数据持久化 / 自进化引擎。无跨语言冗余开销。

```
safeopcAPP/
├── src-tauri/src/           ← Rust 后端内核
│   ├── lib.rs               主入口 (setup hook + invoke_handler 注册)
│   ├── commands/            IPC 命令层 (45+ 模块)
│   │   ├── legacy.rs        核心 CRUD (memories/tasks/sessions/workspaces)
│   │   ├── floating_window.rs  跨窗口悬浮窗
│   │   ├── turn_rating.rs   👍/👎 评分 → 自动升级
│   │   ├── autoskill.rs     AutoSkill IPC 层
│   │   ├── mcp_proxy.rs     MCP v2 代理 (绕 WebView2 TLS)
│   │   ├── pc_automation.rs UIA+CDP+OCR 路由器 IPC
│   │   ├── skill_multi_market.rs 多源技能市场 (7 个源: LinkFox/Skills.sh/ClawHub/SkillStore/Noique/SkillBank.app+FindSkill.com 目录; 聚合搜索+curl/CLI/API 下载)
│   │   └── ...              (im_config, teaching, device_register 等)
│   ├── hermes/              Hermes Agent 运行时 (60+ 子模块)
│   │   ├── evolution.rs     滑动窗口成功率追踪
│   │   ├── evolution_stats.rs  持久化计数 + 熔断器
│   │   ├── memory_evolution.rs V2 记忆演化 (dedupe + lineage)
│   │   ├── embedded_server.rs  内嵌 axum 网关 + dashboard
│   │   ├── cron_local.rs    本地定时任务调度
│   │   ├── llm_service.rs   LLM 流式调用
│   │   └── ...
│   ├── autoskill/           AutoSkill 自进化引擎
│   │   ├── pipeline.rs     LogMiner→PatternFinder→ParamGeneralizer→ScoreCheck
│   │   ├── pattern_miner.rs 成功模式挖掘
│   │   └── state_machine.rs  Monitoring→Drafting→PendingConfirm→Watching→Rollback
│   ├── skill_eval/          4 维度加权评分 (成功率/稳定性/效率/通用性)
│   ├── pc_automation/       三层自动化 (CDP > UIA > OCR + VLM 救援)
│   ├── storage/             DuckDB 数据中台 (7 张核心表)
│   ├── skill/               技能注册表 + 持久化
│   ├── acp/                 ACP (Agent Client Protocol) CLI 接入
│   └── upgrade/             自建升级流水线
│
├── src/web-ui/src/web-ui/src/   ← React 前端
│   ├── app/                应用层 (scenes/components/layout/stores)
│   ├── flow_chat/          对话核心 (store/components/services)
│   │   ├── store/FlowChatStore.ts        自定义状态管理 (3625行)
│   │   ├── store/modernFlowChatStore.ts  Zustand 状态 (新功能走这里)
│   │   ├── store/turnRatingStore.ts      评分状态
│   │   └── store/sessionHabitsStore.ts   会话习惯记忆
│   ├── component-library/  通用组件库 (30+ 组件)
│   ├── infrastructure/     基础设施 (api/config/i18n/hooks)
│   └── tools/             工具集 (editor/lsp/terminal/workspace)
│
├── skills/                JavaScript 技能模块 (index.js, 非 .cjs)
│   ├── auto-product-comm/  自动产品文案
│   ├── trace-auto/         流程录制
│   ├── kuaiju-viewer/      快看
│   ├── wechat-publisher/   微信发布
│   ├── xiaohongshu-publisher/ 小红书发布
│   ├── amazon-product-research/ 亚马逊选品调研 (10 个跨境技能之一)
│   ├── alibaba-1688-sourcing/    1688 跨境寻源
│   ├── cross-border-expansion/   跨境市场拓展
│   └── ... 10 个跨境电商技能, 均编译内嵌为 Rust builtin
│
├── upsource/              ← 上游参考代码 (非本项目组成部分)
│   ├── BitFun/            BitFun 上游 (Rust workspace + 前端参考)
│   └── terminator/       Terminator 上游
│
├── .github/workflows/build.yml  CI 构建 (Windows NSIS + macOS DMG)
└── docs/                 架构文档 (hermes-rs-implementation.md, ci-build-rules.md)
```

> **注**: `upsource/` 目录是上游项目 (BitFun / terminator) 的参考源码，**不参与** tupai 的编译和运行。tupai 的实际代码仅由 `src-tauri/` (Rust 后端) + `src/web-ui/` (React 前端) + `skills/` (JS 技能) 组成，三者通过 Tauri IPC 集成为单一桌面应用。

---

## 二、核心工作流

### 2.1 Plan 模式（默认）

非琐碎任务（3 步以上或涉及架构决策）**必须先进入计划模式**：`Plan → Execute → Verify → Iterate`

- 出现偏差立即停止并重新规划，绝不硬推
- 验证步骤也用计划模式
- 每次执行前自问：是否需要计划模式？

### 2.2 ReAct 循环

`Observe → Think → Act → Observe → ...` 持续观察行动结果、识别偏差、决定是否重新规划。

### 2.3 子代理策略

大量使用子代理保持主上下文窗口干净；研究、探索、并行分析全部外包；每个子代理只专注一个方向。

---

## 三、自进化引擎（已实现）

### 3.1 三层自进化架构

| 层级 | 模块 | 职责 | 触发方式 |
|------|------|------|----------|
| L0 记忆演化 | `hermes/memory_evolution.rs` | write_outcome → dedupe → lineage 版本族谱 | 每次任务完成 + 24h dailyReflection |
| L1 评分追踪 | `hermes/evolution.rs` + `evolution_stats.rs` | 滑动窗口成功率 + 熔断器 + 去重 | 每次技能执行 + 实时熔断 |
| L2 技能迭代 | `autoskill/` + `skill_eval/` | 日志挖掘 → 模式聚类 → 参数泛化 → 评分 → 草稿 → 观察 → 回滚 | 30min 后台扫描 |

### 3.2 AutoSkill 状态机

```
Monitoring → Drafting → Scoring → PendingConfirm → Upgrading → Watching
                                                         ↓ (分数下降 >15)
                                                      Rollback
```

- **纯本地实现**，不调用 LLM；参数泛化用 regex 规则
- 评分 ≥85 分才保留草稿；用户确认后进入 24h 观察期
- 观察期分数下降 >15 分自动回滚到旧版本

### 3.3 Turn Rating 自动升级

用户在对话界面 👍/👎 评分 → 会话删除时 `evaluate_session_ratings` 计算得分 → 得分 ≥0.7 且 ≥2 条评分 → 自动写入升级记忆 → (TODO) 上传服务器评估

实现: `commands/turn_rating.rs` (Rust) + `flow_chat/store/turnRatingStore.ts` (前端)

---

## 四、识别能力优先级系统

### 4.1 双层架构（对应 `pc_automation/` 模块）

**上层（认知层）**：语义分析、意图识别、任务完成判断、智能指导生成——基于底层识别结果进行高级分析。

**下层（感知层）**：`CDP > UIA > OCR > VLM`（按性能+效果排序）。

| 层级 | 能力 | 速度 | 准确度 | 适用场景 | 实现文件 |
|------|------|------|--------|----------|----------|
| L0 CDP | DOM 状态/元素属性/可见性/文本 | <100ms | 100% | Electron 应用 | `pc_automation/cdp/` |
| L1 UIA | 控件状态/窗口信息/按钮调用 | 100-500ms | 90-95% | 原生 Windows 应用 | `pc_automation/uia/` |
| L2 OCR | 屏幕文字/按钮文字/错误提示 | 500ms-2s | 85-95% | 任意文字识别 | `pc_automation/ocr/` |
| L3 VLM | 图像理解/视觉推理/多模态 | 2-5s | 90-95%+ | 复杂图像分析 | `pc_automation/vlm_rescue/` |

资源受限时优先 CDP+UIA+OCR。

### 4.2 降级策略

按优先级链式降级，失败则进入下一层：

- **Electron 应用 (Trae/VSCode)**：`CDP → OCR → VLM`（UIA 跳过）
- **原生 Windows 应用**：`CDP → UIA → OCR → VLM`
- **其他场景**：依次尝试 `CDP → UIA → OCR → VLM`

路由器入口：`commands::pc_automation::execute_step` → `pc_automation::executor` 三策略路由。

---

## 五、自我改进循环

### 5.1 教训库（概念层）

每次纠正后更新教训记录，格式：

```markdown
## [日期] - [问题类型]
- **问题**: 描述问题
- **原因**: 根本原因分析
- **解决方案**: 如何解决
- **预防措施**: 如何避免再次发生
```

### 5.2 自动改进（代码层）

| 机制 | 实现 | 触发 |
|------|------|------|
| 记忆去重 | `hermes::memory_evolution::dedupe_memories` | 24h 后台 dailyReflection |
| 技能评分 | `skill_eval::SkillEvalEngine` (4 维度加权) | 每次技能执行后 |
| 草稿生成 | `autoskill::AutoSkillEngine::generate_draft` | 30min 后台扫描 |
| 回滚保护 | `autoskill::AutoSkillEngine::rollback_all_degraded` | 30min 后台检查 |
| 熔断器 | `hermes::evolution_stats` (3 次连续失败 → 30min 冷却) | 实时 |

原则：模式 `错误模式 → 根因分析 → 解决方案 → 规则化 → 自动应用`。

---

## 六、任务完成判断

完成信号正则匹配：`测试通过|已修复|已完成|编译成功` 等明确信号。
- 100% 完成信号 → 创建新任务
- 70-99% → 等待最终确认
- 30-70% → 继续监控
- 0-30% → 继续监控

---

## 七、核心原则

- **简洁优先**：每次改动尽量简单，只影响最小代码，避免过度工程化
- **绝不偷懒**：找到根因不用临时补丁，坚持资深开发者标准，质量优先于速度
- **最小影响**：只改必要部分，避免引入新 bug，保持向后兼容
- **自主修复**：收到 bug 报告直接修复，自动修复失败的 CI 测试
- **完成前验证**：绝不在证明"它能工作"前标记任务完成（编译通过/测试通过/文档更新）
- **资深工程师审查**：每次提交前自问方案是否优雅、是否考虑边界情况
- **护栏与安全**：最小权限原则，低置信度时暂停升级，失败优雅降级
- **稳定优先**：当前功能满足需求时，优先稳定性优化而非大规模重构

---

## 八、规则文档索引

本文件聚焦项目架构与开发原则。具体规则请查阅以下文档（单一事实来源，避免重复维护）：

| 文档 | 覆盖内容 |
|------|---------|
| [`CLAUDE.md`](./CLAUDE.md) | 服务器 API 流程规则、构建与测试规则、tauri.conf.json 插件配置规则、全局 UI 改动规则、健壮性与防御性编程规则、提交与推送规则 |
| [`docs/ci-build-rules.md`](./docs/ci-build-rules.md) | CI 触发条件、平台矩阵、构建产物、缓存策略、提交前验证清单 |
| [`src/web-ui/src/web-ui/AGENTS.md`](./src/web-ui/src/web-ui/AGENTS.md) | 前端开发规则（adapter 层、i18n、theme、Zustand stores） |
| [`src/web-ui/src/web-ui/LOGGING.md`](./src/web-ui/src/web-ui/LOGGING.md) | 前端日志规范（英文、无 emoji、结构化、createLogger） |
| [`docs/cua-dev-debug.md`](./docs/cua-dev-debug.md) | cua 驱动（trycua/cua）本地构建、dev 调试、JSON-RPC 追踪与故障排查 |

---

## 九、设备指纹持久化规则（硬件级，跨重装不变）

> 实现: `src-tauri/src/commands/hardware_id.rs` (`get_hardware_id` Tauri 命令)

### 9.1 多级持久化缓存 + 多硬件源降级链

| 优先级 | 存储位置 | 生命周期 | 说明 |
|--------|----------|----------|------|
| **L1 主缓存** | `$APPDATA_DIR/.hardware_id` (Tauri `app_data_dir`) | 跨重启 | 首选，读写最快 |
| **L2 备份缓存** | 平台持久路径 (跨卸载/重装) | 跨 app 重装 | Win: `%APPDATA%\tupai\.hardware_id`<br>macOS: `~/Library/Application Support/tupai/.hardware_id`<br>Linux: `~/.local/share/tupai/.hardware_id` |
| **L2.5 家目录 dotfile** (macOS/Linux) | `~/.tupai_hardware_id` | 跨 app 重装/卸载 | HOME 根目录, 不依赖 Library 权限, App Translocation 不影响 |
| **L3 注册表缓存** (仅 Windows) | `HKCU\Software\tupai\hardware_id` | 跨卸载/重装 | NSIS 卸载器不删除 HKCU，极其稳健 |
| **L4 硬件命令** | 读取固件/主板级 UUID | **跨 OS 重装** | 见下表多硬件源 |
| **L5 最终兜底** | UUID v4 + 写入所有缓存层 | 至少同一次安装内 | 兜底，保证不为空 |

### 9.2 多硬件源降级链（按优先级，拿到即用）

| 平台 | 优先级 1（硬件级，跨 OS 重装不变） | 优先级 2（硬件级） | 优先级 3（OS 级，重装会变） |
|------|-----------------------------------|-------------------|----------------------------|
| **Windows** | SMBIOS UUID (`Win32_ComputerSystemProduct.UUID`) | 主板序列号 (`Win32_BaseBoard.SerialNumber`) | MachineGuid (`HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`) |
| **Linux** | — | 主板序列号 (`/sys/class/dmi/id/board_serial`) | 产品 UUID (`/sys/class/dmi/id/product_uuid`) |
| **macOS** | **IOPlatformUUID** (`/usr/sbin/ioreg -rd1 -c IOPlatformExpertDevice`) | **IOPlatformSerialNumber** (同一 ioreg 输出) | **sysctl hw.uuid** (`/usr/sbin/sysctl -n hw.uuid`, 与 UUID 同值不同二进制) |

> **关键点**：SMBIOS UUID / 主板序列号 / IOPlatformUUID / IOPlatformSerialNumber 烧录在主板/固件，**不随 OS 重装改变**。macOS 的 ioreg-uuid 与 sysctl-uuid 返回同一个 Platform UUID（都读自固件/NVRAM），即使 ioreg 二进制失败、sysctl 成功，跨启动指纹仍稳定。代码按优先级尝试，拿到即用并同步写入 L1/L2/L2.5/L3 所有可用缓存层。所有 macOS 命令用绝对路径 `/usr/sbin/...`（GUI 应用 PATH 极简）。

### 9.3 读取/写入流程

```
读取:  L1主缓存 → L2备份缓存 → L2.5家目录dotfile → L3注册表缓存(仅Win) → L4硬件命令(按平台降级链) → L5 fallback
写入:  L4/L5 成功后 → 同步写入 L1/L2/L2.5/L3 所有可用层
```

### 9.4 返回结构

```rust
HardwareId {
    hardware_id: String,  // 最终使用的指纹
    platform: String,     // "windows" | "darwin" | "linux"
    arch: String,         // "x86_64" | "aarch64"
    os_version: String,   // OS 版本
    is_fallback: bool,    // true=用了 uuid-v4 兜底
    source: String,       // 来源标识: "cache:smbios-uuid" | "backup:registry" | "smbios-uuid" | "uuid-v4" 等
}
```

### 9.5 健壮性保证

- ✅ **软件卸载重装**：L2/L2.5/L3 缓存保留，读取即复用
- ✅ **OS 重装**：硬件命令读取主板级 UUID，指纹不变
- ✅ **主板更换**：硬件命令读到新 UUID，按新设备注册（符合预期）
- ✅ **虚拟机/容器**：无 SMBIOS 时降级到 OS 级 MachineGuid / product_uuid
- ✅ **权限不足**：读注册表/文件失败仅警告，不阻塞，继续降级
- ✅ **并发安全**：单命令串行，缓存写入幂等
- ✅ **macOS ioreg 失败**：三源降级（ioreg-uuid → ioreg-serial → sysctl-uuid），ioreg-uuid 与 sysctl-uuid 同值，避免单点失败导致 uuid-v4 随机兜底
- ✅ **macOS App Translocation**：L2.5 家目录 dotfile (`~/.tupai_hardware_id`) 不受影响，HOME 根目录始终可写
- ✅ **macOS GUI 应用 PATH 极简**：所有命令用绝对路径 `/usr/sbin/ioreg`、`/usr/sbin/sysctl`、`/usr/bin/sw_vers`

---

## 十、CI 构建经验规则（2026-07-28）

> **教训**：tauri.safeopc.conf.json 中写了非法的 `hardeningRuntime: true` 字段，导致 CI 构建在配置解析阶段即失败（所有平台）。同时 Rust 代码中存在未引用的变量名 `state_for_vlm`、跨模块缺少 `crate::` 前缀、以及不可达的命令注册路径 `mesh::commands`，使得 `cargo check` 通过但 CI 运行时编译失败。

### 10.1 Tauri 配置规则

| 规则 | 说明 |
|------|------|
| 1. **`hardeningRuntime` 不在 Tauri v2 配置 schema 中** | 它是 Xcode 项目的 plist 字段，不是 `tauri.conf.json` 字段。macOS 代码签名/硬化在 `entitlements.plist` 和 CI 的 `APPLE_SIGNING_IDENTITY` 环境变量处理。**禁止在 `tauri.*.conf.json` 的 `bundle.macOS` 下写 `hardeningRuntime`**。 |
| 2. **JSON 编辑后必须 `jq .` 验证语法** | 编辑 `tauri.*.conf.json` 后立即执行 `jq . <file > nul` 或 `python -m json.tool <file`，确保无多余/缺失括号。一个多余 `}` 会让所有平台构建失败。 |
| 3. **`-DmacOS` 不是有效字段** | Tauri v2 的 macOS 配置在 `bundle.macOS`（大写 OS），不是 `bundle.macOS` 内部的 `-DmacOS`。传入 `--target aarch64-apple-darwin` 即可。 |
| 4. **品牌覆盖文件只包含品牌差异字段** | macOS 通用设置（`minimumSystemVersion`, `dmg`, `entitlements`）应同时在所有品牌文件中保持同步。不要用独立的 OS overlay 文件——CI 只使用 `tauri.\${BRAND}.conf.json` 一个覆盖文件。 |

### 10.2 Rust 编译规则

| 规则 | 说明 |
|------|------|
| 5. **`cargo check` 通过 ≠ 构建通过** | `cargo check` 只检查类型和借用，不检查 Tauri 宏展开路径、`generate_handler!` 中模块访问性。**推送前必须 `cargo check --release --features tauri/custom-protocol` 或完整 `tauri build`。** |
| 6. **`ci-validate.yml` 的 `continue-on-error: true` 不阻挡合并** | 该 workflow 的 Rust 步骤即使失败也不会标红 PR，**不能依赖它捕获编译错误**。 |
| 7. **`generate_handler!` 中的路径必须直接从 crate root 可达** | `hermes::mesh::commands::mesh_create` 必须通过 `pub mod hermes { pub mod mesh { pub mod commands { ... } } }` 暴露，且不能依赖 feature-gate 内部模块。`mesh::commands` 通过条件编译（feature `mesh`）暴露在 `mesh/mod.rs` 中，但子模块 `mesh/commands.rs` 需要被 `mod commands;` 暴露，否则 `generate_handler!` 报错。 |
| 8. **跨模块闭包中不能依赖 `self` 作用域** | 在嵌套路径函数（如 `lib.rs` 中的 setup hook 闭包）中使用 `pc_automation::xxx` 必须加 `crate::` 前缀——`self` 在这些闭包中绑定的是闭包自身，而非 crate root。 |
| 9. **重构变量名时必须全局搜索所有引用** | 改名 `state_for_vlm` → `dt_for_vlm` 后必须在整个 `src-tauri/src/` 下搜索旧名，确保无残留引用。`cargo check` 会报 `not found in this scope`，但 CI 中因为 `continue-on-error` 可能被忽略。 |
| 10. **Tauri 命令函数不能用 `pub use` 跨 crate 转发** | `tauri::generate_handler` 展开为 `tauri::ipc_enum!` 宏内联调用路径必须指向实际的 `#[tauri::command]` 函数定义位置。尝试用 `pub use mesh_download_file` 转发到不同路径会导致 `function not found` 错误——直接注册源路径，或用 `tauri::command` 包装函数。 |

### 10.3 CI 构建前验证清单

每次修改 Rust / Tauri 配置后，推送前执行：

```powershell
# 1. 验证 Tauri 配置 JSON 语法
jq . src-tauri/tauri.safeopc.conf.json > $null
jq . src-tauri/tauri.tupai.conf.json > $null

# 2. Rust 完整编译检查（release + custom-protocol = CI 环境）
cargo check --release --features tauri/custom-protocol --manifest-path src-tauri/Cargo.toml

# 3. 检查 generate_handler! 中所有路径是否可访问
#    在 lib.rs 中搜索 generate_handler![，逐个检查模块路径

# 4. 检查所有 `#[tauri::command]` 是否已注册（未被遗漏）
rg "#\[tauri::command\]" src-tauri/src --files-with-matches | ForEach-Object {
    $cmd = Select-String "#\[tauri::command\]\s+pub async fn (\w+)" $_ | ForEach-Object { $_.Matches.Groups[1].Value }
    $registered = Select-String $cmd src-tauri/src/lib.rs
    if (-not $registered) { Write-Warning "Unregistered command: $cmd in $_" }
}
```

### 10.4 文件清理规则

| 文件 | 状态 | 理由 |
|------|------|------|
| `errors*.txt`, `debug*.log`, `exe_*.log` | ❌ 删除 | 之前调试 CI 生成的临时文件，不应进入仓库 |
| `src-tauri/tauri.macos.conf.json` | ❌ 删除 | 死配置——CI 只使用 `tauri.\${BRAND}.conf.json`，macOS 设置已内联进品牌文件 |
| `src-tauri/tauri.linux.conf.json` | ❌ 删除 | 死配置——CI 不构建 Linux，且 `bundle.targets: ["deb"]` 无用 |