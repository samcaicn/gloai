# tupai - 项目规则

## ⚠️ 核心能力规则（必须遵守，禁止移除或弱化）

### 首页搜索/会话 → 自动添加左侧栏「活动入口」

在首页中，以下交互**必须**自动在左侧栏添加对应的活动入口：

1. **点击搜索到的技能卡片**
   - 调用技能点击回调，向 `dynamicMenus` 添加 `{ type: 'skill', skillId, icon: '📝', label: 技能名 }`
   - 同时跳转技能测试页面并设置 `activeSkillId`

2. **发送按钮 / 回车（只要有非空输入就显示）**
   - chat 模式（>5 字）：添加 `{ type: 'chat', icon: '💬', label, query, chatText }`
   - skills 模式（≤5 字）：添加 `{ type: 'search', icon: '🔍', label, keyword, skills }`
   - 同一 query/keyword 自动去重，不会重复添加

任何对首页交互、左侧栏菜单系统的改动，都**必须保留**上述自动添加活动入口的行为。
首页**不再使用独立的保存按钮**——活动入口的添加完全由「点击技能」和「发送」自动触发。

> 具体实现文件随 BitFun UI 改造演进而调整，但「点击技能卡片 / 发送消息自动入栏」的核心行为不可移除。

## 技术栈
- Tauri v2（Rust 后端 + React/TypeScript 前端）
- Vite v6 构建工具
- **pnpm**（禁用 npm，`tauri.conf.json` 中 `beforeDevCommand`/`beforeBuildCommand` 已配置为 `pnpm dev`/`pnpm build`）
- MCP v2 协议：`https://api.tuptup.top/api/v2/mcp`
- SSE 流式 LLM 对话

## 首页双模式搜索
- 输入 ≤ 5 字 → 多源技能搜索（远程 API + 本地内置 + 已安装，并行查询后按 `skill_id` 去重）
- 输入 > 5 字 → LLM 对话（SSE 流式，`AbortController` 支持取消）
- 无输入 → 根据 token 关联的「场景标签」推荐技能（MCP `skill.scene_tags`）

## 动态菜单类型（`dynamicMenus`）
| type | 触发场景 | 图标 | 点击行为 |
|------|---------|------|---------|
| `skill` | 首页点击技能卡片 | 📝 | 跳转 skill-chat |
| `chat` | 首页发送会话 | 💬 | 跳转 home |
| `search` | （历史保留）保存的搜索 | 🔍 | 跳转 tasks |
| `web-skill` | 网页类技能(如 kuaiju-viewer) | 🎬 | 打开内嵌 webview 窗口,主窗口不切换 |
| `automation-run` | 自动化执行/录制 | 🤖 | 跳转 automation + 显示主窗口 |

菜单持久化到 `localStorage` 的 `trae_dynamic_menus` 键。

---

## 构建与测试规则（每次操作必须遵守）

### 构建配置

| 文件 | 用途 | 关键配置 |
|------|------|---------|
| `.cargo/config.toml` | Cargo 全局配置 | `git-fetch-with-cli = true`、`net.retry = 5`、`RUSTC_WRAPPER=sccache`、`linker=rust-lld` |
| `.npmrc` | pnpm 配置 | `store-dir=C:\pnpm-store`、`auto-install-peers=true`、`strict-peer-dependencies=false` |
| `src-tauri/Cargo.toml` | Rust 依赖 + profile | `[profile.dev] debug=1 + split-debuginfo=unpacked`、`[profile.release-nsis]` 快速 NSIS 构建、`[profile.release-fast]` sanity check |
| `vite.config.js` | Vite 构建 | `watch.ignored` 排除无关目录、`manualChunks` 拆分 vendor/flow、`reportCompressedSize=false` |
| `build.ps1` | 构建编排脚本 | `cargo metadata` 动态 target、`-Nsis` 默认用 `release-nsis` profile、`-NoFrontend` 跳过前端重建 |

### 磁盘优化要点

1. **Cargo dev profile**：第三方依赖关闭 debug info，target/debug 从 ~12 GB 降至 ~2 GB
2. **统一 target 目录**：由 `CARGO_TARGET_DIR` 环境变量控制，避免多 crate 重复编译
3. **pnpm store**：固定 `C:\pnpm-store`，多 workspace 共享
4. **Vite watch**：排除 front1、skills、installer、.git 等无关目录，减少无效 HMR
5. **构建后清理**：`build.ps1` 自动删除 `release/incremental`（节省 200-500 MB），**不删除** `deps/`（增量缓存）
6. **sccache**：跨 `cargo clean` 缓存编译产物（`.cargo/config.toml` 已启用），第二次构建秒级恢复

### 可用脚本

| 命令 | 说明 |
|------|------|
| `pnpm check` | `cargo check --all-targets`（完整检查） |
| `pnpm check:fast` | `cargo check --lib`（最快，仅 lib） |
| `pnpm check:frontend` | `vite build`（前端构建检查） |
| `pnpm dev:tauri` | `tauri dev`（开发模式） |
| `pnpm build:nsis` | `build.ps1 -Nsis`（release-nsis profile，~1-2 min，标准构建，推荐） |
| `pnpm build:nsis:ultra` | `build.ps1 -Nsis -UltraFast`（极速：跳过前端+清理，Rust-only 最快） |
| `pnpm build:nsis:nofe` | `build.ps1 -Nsis -NoFrontend`（Rust-only 改动，跳过前端重建） |
| `pnpm build:nsis:check` | `build.ps1 -Nsis -Check`（快速自检：cargo check + pnpm build） |
| `pnpm build:nsis:full` | `build.ps1 -Nsis -Full`（release-ci，正式发布/CI 专用，不常用） |
| `pnpm build:tauri` | `tauri build`（完整发布构建） |
| `pnpm clean` | 软清理（dist + bundle） |
| `pnpm clean:target` | 全清理 target（含增量缓存） |
| `pnpm clean:all` | 全清理 target + .vite cache |

### 提交与推送规则

**每次完成代码变更后，必须执行以下步骤：**

1. `git add -A` — 暂存所有变更（含未暂存的）
2. `git commit -m "<conventional commit message>"` — 提交（遵循 Conventional Commits 规范）
3. `git push` — 推送到腾讯源

**Commit 消息格式（Conventional Commits）：**

| 前缀 | 含义 | 示例 |
|------|------|------|
| `feat:` | 新功能 | `feat: 优化 flowchart 决策分支颜色` |
| `fix:` | Bug 修复 | `fix: 修复 IM 消息重复发送` |
| `build:` | 构建系统变更 | `build: 优化 cargo dev profile 减小 target 体积` |
| `chore:` | 日常维护 | `chore: 更新 .gitignore 忽略 .vite 缓存` |
| `refactor:` | 重构 | `refactor: 重写 build.ps1 动态解析 target 目录` |
| `docs:` | 文档更新 | `docs: 更新构建规则` |
| `perf:` | 性能优化 | `perf: vite watch.ignored 排除无关目录` |
| `ci:` | CI/CD 变更 | `ci: 更新 GitHub Actions 构建流程` |
| `test:` | 测试相关 | `test: 添加 IM 适配器生命周期测试` |

**注意：**
- 如果已有未提交的变更，不要强制覆盖，而是追加提交
- 推送失败时检查是否有远程新提交，必要时先 `git pull --rebase`

---

## ⚠️ 服务器 API 流程规则（客户端必须严格匹配，禁止自行臆造流程）

> **教训**：2026-07-11 设备注册 bug —— 客户端把 `join_code` 发到 `/api/v1/client/fingerprint`（该接口只负责指纹注册，不校验 join_code），导致任意输入都能"注册成功"。根因是客户端自行臆造了注册流程，未匹配服务器实际的三步流程。

### 设备注册三步流程

客户端实现位于 `src-tauri/src/commands/device_register.rs`，**必须**严格匹配以下服务器流程：

```
步骤1: 设备指纹 → device_token（无需 join_code）
  POST /api/v1/client/fingerprint
  body: { fingerprint, capability_tags, client_info }
  → { device_token, client_id, tenant_id, is_new_device, activation }

步骤2: client.bind → 审批状态 + request_id
  POST /api/v2/mcp
  body: { action: "client.bind", params: { join_code, device_token } }
  Authorization: Bearer <device_token>
  → { status: "pending_approval", request_id: "bind-xxx" }
  （join_code 格式：8 位数字字符串，如 "66668888"）
  （device_token 必须同时放在 params 和 Authorization header 里，服务器校验 params 里的 device_token）

步骤3: 轮询审批状态
  POST /api/v2/mcp
  body: { action: "client.bind.status", params: { request_id, device_token } }
  Authorization: Bearer <device_token>
  → { status: "approved" | "pending_approval" | "rejected" }
  （注意：approved 时响应中无 device_token 字段，沿用步骤1的 token）

步骤4: LLM 请求（审批通过后）
  POST /api/v2/mcp
  body: { action: "llm.request", params: { ... } }
  Authorization: Bearer <device_token>
  → 200 OK
```

### MCP v2 API 调用规范

- 所有 MCP v2 请求统一发往 `POST /api/v2/mcp`
- body 结构: `{ action: "<action_name>", params: { ... } }`
- 需要鉴权的请求携带 `Authorization: Bearer <device_token>` header
- 前端封装: `src/web-ui/src/web-ui/src/infrastructure/api/tupai/mcp.ts`
- 后端封装: `src-tauri/src/commands/mcp_proxy.rs` (`mcp_call_v2`)

### 规则

1. **join_code 只通过 MCP client.bind 传，禁止传给 fingerprint 接口** — fingerprint 只负责设备指纹注册，返回 device_token；join_code 是租户绑定凭证，只用于 client.bind 步骤
2. **join_code 格式：8 位数字字符串** — 服务器验证规则 `must be 8 digits`，如 "66668888"；传数字类型会触发 `internal.error`
3. **device_token 必须同时放在 MCP params 和 Authorization header 里** — 服务器校验 params 里的 device_token，缺了返回 `validation.missing.device_token`。`client.bind` params = `{ join_code, device_token }`，`client.bind.status` params = `{ request_id, device_token }`
4. **审批状态必须轮询** — pending_approval 状态下前端每 5 秒轮询 `client.bind.status`
5. **审批通过前不可调用 llm.request** — 否则服务器拒绝
6. **所有 MCP v2 action 都必须带 Bearer token** — `client.bind` / `client.bind.status` / `client.renew` / `llm.request` 全部要 token。token 由步骤1 fingerprint 签发，fingerprint 是唯一匿名入口。禁止把 `client.bind` 改回匿名调用（会破坏攻击隔离，见下）
7. **修改设备注册流程前，必须先确认服务器实际流程** — 查看 `docs/服务器需求.md` 或询问服务器开发组

### 架构设计理由：攻击隔离

fingerprint 与 MCP 故意拆成两个端点，不是"减少往返"的简化设计，而是攻击隔离：

- **fingerprint 端点**（`/api/v1/client/fingerprint`）是唯一允许匿名 + 重操作的入口。单独端点可独立限流/熔断，打爆它只影响**新设备注册**，不影响已注册设备的业务调用。
- **MCP 端点**（`/api/v2/mcp`）所有 action 都要 Bearer token，无 token 请求在 auth 层秒拒（cheap）。攻击者拿不到 token 就只能打 fingerprint 这个轻量端点，已注册设备的业务调用不受影响。
- 若把 fingerprint 合并进 `client.bind`（匿名 bind），则 MCP 端点上存在匿名重操作，单点拥塞会同时杀死注册和业务。

> **历史教训**：2026-07 曾短暂把 fingerprint 合并进 `client.bind`（匿名 bind + 内联硬件信息），理由是"单端点单往返简化"。实测发现这牺牲了攻击隔离——单端点 DDoS 即可瘫痪全部设备注册 + 业务调用。已于 2026-07 恢复两步架构。之前的 "fingerprint 端点超时" 是服务器实现慢（应 <500ms 的操作耗时 12s+），属实现 bug 非架构缺陷，应修服务端而非绕开端点。

---

## 设备指纹持久化规则（硬件级，跨重装不变）

详见 `AGENTS.md` §九。核心要点：

- 三级缓存（主缓存/备份缓存/注册表）+ 多硬件源降级链
- Windows: SMBIOS UUID → 主板序列号 → MachineGuid
- Linux: machine-id → 主板序列号 → product UUID  
- macOS: IOPlatformUUID (硬件级)
- 读取优先级：缓存 → 硬件命令 → fallback UUID
- 代码实现：`src-tauri/src/commands/hardware_id.rs`

---

## ⚠️ tauri.conf.json 插件配置规则（运行时反序列化陷阱）

> **教训**：2026-07-20 启动崩溃 —— `tauri.conf.json` 中给 `store`/`global-shortcut`/`os`/`process`/`clipboard-manager`/`opener` 等单元类型插件写了空 `{}` 配置，导致 `tauri::Builder::build()` 在初始化插件时报错 `PluginInitialization("store", "Error deserializing 'plugins.store'... invalid type: map, expected unit")`，应用启动即崩溃。CI 构建的安装包安装后无法打开。

### 根本原因

`tauri-plugin-store v2.4.3`、`tauri-plugin-global-shortcut`、`tauri-plugin-os`、`tauri-plugin-process`、`tauri-plugin-clipboard-manager`、`tauri-plugin-opener` 等插件的 `Conf` 类型是 **单元类型 `()`**，只能从 `null` 或 **缺失的 key** 反序列化。写入 `{}`（map）会触发 serde `invalid type: map, expected unit` 错误。

### 为什么 CI 抓不到

- `cargo build` / `cargo check` **不会** 校验 `tauri.conf.json` 里的 plugins 字段——配置文件在运行时由 `tauri::Builder::build()` 解析并喂给各插件的 `TauriPlugin::initialize`。
- 错误只在 `app.run()` 启动窗口那一刻才暴露，CI 没有运行时冒烟测试就直接打包发布。

### 规则

1. **单元类型插件的配置必须省略 key** —— `store`/`global-shortcut`/`os`/`process`/`clipboard-manager`/`opener` 等无配置项的插件，**不要** 在 `tauri.conf.json` 的 `plugins` 对象里写 `"store": {}`，要么完全不写，要么写 `"store": null`。
2. **只有真正有配置字段的插件才写 JSON 对象** —— 例如 `updater`（有 `endpoints`/`pubkey`）、`deep-link`（有 `desktop.schemes`）。
3. **修改 `tauri.conf.json` 的 plugins 段后，必须本地 `pnpm tauri dev` 启动验证** —— 仅 `cargo build` 通过 ≠ 配置有效。验证步骤：
   ```powershell
   pnpm tauri dev --config src-tauri/tauri.safeopc.conf.json
   ```
   看到窗口正常打开且 `tupai.log` 中 `analyze_log_for_errors: 0 errors` 才算通过。
4. **CI 打包前必须本地 `pnpm build:nsis` 验证一遍** —— 安装包安装后启动崩溃的代价远高于本地构建一次。
5. **启动失败优先看 `target/debug/tupai.log`（dev）或 `%APPDATA%/tupai/logs/`（installed）** —— `PluginInitialization("xxx", ...)` 错误明确指出是哪个插件配置出错。

### 当前 tauri.conf.json 的合法 plugins 段（参考）

```json
"plugins": {
  "updater": {
    "endpoints": ["https://ai.tuptup.top/api/update/tupai/{{target}}/{{arch}}/{{current_version}}"],
    "pubkey": "dW50cnRzdGVk..."
  },
  "deep-link": {
    "desktop": { "schemes": ["tupai"] }
  }
}
```

> 单元类型插件（store / global-shortcut / os / process / clipboard-manager / opener / autostart）**全部不在 plugins 段出现**，由 `lib.rs` 的 `tauri::Builder::default().plugin(tauri_plugin_store::Builder::default().build())` 等代码注册即可。

---

## ⚠️ 全局可见 UI 改动规则（避免放进未挂载的 Scene）

> **教训**：2026-07-20 品牌名/官网未加载 —— 上一轮把品牌加载逻辑写进了 `WelcomeScene.tsx`，但 `SCENE_TAB_REGISTRY`（`app/scenes/registry.ts`）在 tupai 阶段已移除 `welcome` scene，默认 scene 是 `skills`。`WelcomeScene` 永远不会被挂载，其 `useEffect` 永远不触发，导致品牌信息加载失败。

### 规则

1. **全局可见的 UI 元素（顶部品牌、底部状态栏、全局浮动按钮）必须挂在常驻组件里** —— 例如 `SceneBar`、`NavPanel`、`AppLayout`、`WorkspaceBody`。**不要** 挂在某个 scene 内部，除非确认该 scene 是默认且常驻的。
2. **改 UI 前先看 `app/scenes/registry.ts` 的 `SCENE_TAB_REGISTRY`** —— 确认目标 scene 是否在注册表里、是否 `defaultOpen: true`。被注释/移除的 scene（welcome/terminal/git/...）属于死代码，挂在上面的逻辑不会执行。
3. **`SceneBar`（32px 顶部条）是放全局品牌/状态指示灯的最佳位置** —— 它由 `WorkspaceBody` 直接渲染，与活跃 scene 无关，永远可见。

---

## ⚠️ 健壮性与防御性编程规则（避免闪退）

> **教训**：2026-07-23 mesh 对端渲染白屏 —— P2P 场景下远端 peer 数据可能畸形，`peer.clientId.slice()` 在 render path 无 try/catch，一旦 `clientId` 为 undefined 即整页白屏。后端 `ticket.rs` 的 `.expect("infallible")` 也是定时炸弹。

### 前端规则

1. **P2P/远端数据渲染必须做空守卫** —— 对端返回的数据（mesh peers、IM 消息、远程技能同步等）不可信任。render path 中的链式属性访问必须加 `|| ''` / `|| []` / `?? 0` 守卫：
   ```tsx
   // ❌ 危险：clientId 为 undefined 即白屏
   {peer.clientId.slice(0, 12)}
   {peer.availableSkills.length}

   // ✅ 安全
   {peer.clientId ? `${peer.clientId.slice(0, 12)}…` : '-'}
   {(peer.availableSkills || []).length}
   ```

2. **`invoke()` 封装层必须归一化返回值** —— `invoke()`（`infrastructure/api/tupai/invoke.ts`）在非 Tauri 环境（web 预览 / jsdom）静默返回 `undefined`。返回数组/Option 的命令必须在封装层 `?? []` / `?? null` 归一化，使运行时行为与 TypeScript 类型契约一致：
   ```ts
   // ❌ 裸返回：非 Tauri 时类型说 MeshPeer[] 实际是 undefined
   return invoke<MeshPeer[]>('mesh_list_peers');

   // ✅ 归一化：调用方无需重复守卫
   return (await invoke<MeshPeer[]>('mesh_list_peers')) ?? [];
   ```

3. **异步命令结果必须守卫 undefined 再解引用** —— 事件处理器中 `await apiCall(...)` 的结果可能为 undefined（非 Tauri 或后端异常），必须 `if (!result) return` 守卫后再访问属性：
   ```tsx
   const result = await meshCreate({...});
   if (!result || !result.status) { setErrorMsg(t('...failed')); return; }
   setStatus(result.status);
   ```

4. **新增 `t()` i18n 调用必须同步添加三语种 locale 键** —— `t('missing.key')` 不抛错，返回 key 字符串本身（丑但不崩溃）。新增 `t()` 调用时必须同步在 `locales/en-US/common.json`、`locales/zh-CN/common.json`、`locales/zh-TW/common.json` 中添加对应键。

5. **新增 `ConfigTab` 枚举值必须同步所有 `Record<ConfigTab, T>` 映射** —— `settingsTabSearchContent.ts` 等使用 `Record<ConfigTab, ...>` 的映射必须覆盖所有枚举值，否则 `tsc --noEmit` 报 `Property 'xxx' is missing in type` 错误。

### 后端规则

6. **生产路径禁止 `.expect()` / `.unwrap()`** —— 序列化、反序列化、锁操作等可能失败的操作必须返回 `Result` 并在调用方处理错误。`.expect("infallible")` 是定时炸弹：
   ```rust
   // ❌ 危险：postcard 序列化失败即 panic
   fn to_bytes(&self) -> Vec<u8> {
       postcard::to_stdvec(self).expect("postcard is infallible")
   }

   // ✅ 安全：返回 Result，调用方降级处理
   fn to_bytes(&self) -> Result<Vec<u8>, postcard::Error> {
       postcard::to_stdvec(self)
   }
   ```

7. **禁止 `let _ = expr` 静默吞错** —— 网络/IO/broadcast 操作的返回值必须用 `if let Err(e) = ... { log::warn!(...) }` 记录，不可静默丢弃。静默失败让 P2P/异步调试极困难：
   ```rust
   // ❌ 静默吞错
   let _ = broadcast_message(&sender, ...).await;

   // ✅ 记录失败
   if let Err(e) = broadcast_message(&sender, ...).await {
       log::warn!("[mesh] broadcast failed: {}", e);
   }
   ```

8. **`.into()` 字符串转换歧义** —— 本仓依赖树中 `exr` 镜像 crate 提供额外 `Into<String>` impl，导致 `&str -> String` 的 `.into()` 触发 E0283 歧义。**全仓字符串转换用 `.to_string()` 不用 `.into()`**。

---

## ⚠️ 提交与推送规则（增强）

### 分批提交策略

当一次会话涉及多个工作流（如 OS 兼容 + mesh + autoskill），按工作流分批提交，每批一个语义化 commit：

| 批次组织原则 | 示例 |
|-------------|------|
| 按功能域分组 | `fix(os-compat): ...` / `fix(mesh): ...` / `fix(automation): ...` |
| 后端与前端分开 | 后端纯函数提取 / 前端事件订阅 |
| 修复与功能分开 | `fix(mesh): 空守卫` / `feat(os-compat): 横幅` |
| 一批一个 `scope` | 避免一个 commit 混合 mesh + autoskill + hermes |

### PowerShell 兼容性（Windows 开发环境）

| Bash 语法 | PowerShell 替代 | 说明 |
|-----------|----------------|------|
| `cmd1 && cmd2` | `cmd1; cmd2` | PowerShell 不支持 `&&` |
| `$(cat <<'EOF' ... EOF)` | 多个 `-m` 参数 | `git commit -m "title" -m "body1" -m "body2"` |
| `head -N` | `Select-Object -First N` | Windows 无 `head` 命令 |
| `tail -N` | `Select-Object -Last N` | Windows 无 `tail` 命令 |

### 推送前验证清单

推送前必须通过以下检查（对应 `docs/ci-build-rules.md` 第 10 节）：

```powershell
# 1. Rust 编译检查（含测试代码）
cargo check --all-targets

# 2. 前端类型检查
npx tsc --noEmit

# 3. ESLint
npx eslint src/<changed-dirs>

# 4. 单元测试
npx vitest run src/<changed-dirs>
cargo test --lib <module>
```

### 推送到腾讯云

```powershell
git push tencent v2
```

远程 `tencent` 已配置凭据，直接推送即可。推送失败时检查是否有远程新提交，必要时先 `git pull --rebase tencent v2`。

### 并发修改冲突恢复

当 `git stash` 后另一进程修改了同一文件导致 `stash pop` 失败时，用选择性恢复：

```powershell
# 1. 查看 stash 内容
git stash list

# 2. 选择性恢复非冲突文件（不从 stash pop，直接 checkout 特定文件）
git checkout "stash@{0}" -- <file1> <file2> ...

# 3. 冲突文件手动合并（保留两方改动）

# 4. 确认恢复完整后删除 stash
git stash drop "stash@{0}"
```
