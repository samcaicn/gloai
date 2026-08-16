# 技能 CDP→UIA→OCR→VLM 优先级链 开发说明

> 版本: v1.0.0
> 适用范围: 所有 AIMarketing 内置技能 + 市场下载技能
> 关联文档: [`src/pc_automation/router.rs`](../src-tauri/src/pc_automation/router.rs)、[`src/pc_automation/executor/selector.rs`](../src-tauri/src/pc_automation/executor/selector.rs)、[`skills/manifest.json`](../skills/manifest.json)、[`src/tauri/src/skills_embedded.rs`]

---

## 一、架构总览

### 1.1 四层识别能力链

AIMarketing 的自动化能力按性能+效果排序，形成 **CDP → UIA → OCR → VLM** 优先级链：

| 层级 | 能力 | 速度 | 准确度 | 适用场景 | 模块 |
|------|------|------|--------|----------|------|
| **L0 CDP** | DOM 状态/元素属性/可见性/文本 | <100ms | 100% | Electron 应用 | `pc_automation/cdp/` |
| **L1 UIA** | 控件状态/窗口信息/按钮调用 | 100-500ms | 90-95% | 原生 Windows 应用 | `pc_automation/uia/` |
| **L2 OCR** | 屏幕文字/按钮文字/错误提示 | 500ms-2s | 85-95% | 任意文字识别 | `pc_automation/ocr/` |
| **L3 VLM** | 图像理解/视觉推理/多模态 | 2-5s | 90-95%+ | 复杂图像分析 | `pc_automation/vlm_rescue/` |

### 1.2 不跨域降级原则

```
Desktop 应用:  UIA (primary) → OCR (fallback) → VLM (rescue)
Web 应用:      CDP (primary) → OCR (fallback) → VLM (rescue)
```

**CDP 和 UIA 互不下调**。Web 域不会走 UIA，Desktop 域不会走 CDP。

### 1.3 快捷路径：OCR 直达

当 step 显式声明 `strategy == Ocr` 时，跳过 primary（UIA/CDP），直接走 OCR。这对 OCR 选择器优化，避免 UIA 解析浪费时间。

```
CDP 步骤 → CDP 尝试 → miss → OCR → miss → VLM rescue
UIA 步骤 → UIA 尝试 → miss → OCR → miss → VLM rescue
OCR 步骤 → 跳过 primary → OCR 直接执行 → miss → VLM rescue
```

---

## 二、技能 Manifest 与 Recognition 声明

### 2.1 manifest.json 中的 `recognition` 字段

每个技能在 `skills/manifest.json` 中声明其识别能力链。前端据此选择正确的执行器。

```json
{
  "id": "trace-auto",
  "recognition": ["cdp", "uia", "ocr", "vlm"]
}
```

**字段语义**：
- `["cdp", "uia", "ocr", "vlm"]` — 按优先级声明可用的识别层
- 实际执行时 router 根据应用类型（Web/Desktop）和 step 策略自动选择
- 技能声明的是"可用"，router 决定"用哪个"

### 2.2 `StepStrategy` 枚举

Rust 中 `StepStrategy` 驱动 domain 选择：

```rust
pub enum StepStrategy {
    Cdp,    // Web 域 → CDP primary
    Uia,    // Desktop 域 → UIA primary
    Ocr,    // 直达 OCR，跳过 primary
}
```

### 2.3 SelectorKind → StepStrategy 映射

```rust
SelectorKind::Cdp    → StepStrategy::Cdp    // Web 域主路径
SelectorKind::Uia    → StepStrategy::Uia    // Desktop 域主路径
SelectorKind::Ocr    → StepStrategy::Ocr    // 直达 OCR 快捷路径
SelectorKind::Visual / SelectorKind::Coordinate → StepStrategy::Uia // 映射到 Desktop 域
```

---

## 三、技能按需调用 CDP→UIA→OCR→VLM 的流程

### 3.1 技能执行入口

```
用户请求
  → AI 决定需要执行某个 skill
  → invoke('execute_skill', { skill_id, params })
  → commands::skill::execute_skill()
  → AutomationEngine::execute()
  → 遍历 SkillStep 列表，逐个 dispatch
```

### 3.2 单 Step 执行流程

```
Step → PcRouter::execute_step(&step)
  ├─ StepStrategy::Ocr
  │   → 跳过 primary，直接走 OCR
  │   → miss → VLM rescue
  │
  └─ StepStrategy::Cdp / StepStrategy::Uia
      → domain_for_step(step) → Web | Desktop
      → Primary: Web → CDP, Desktop → UIA
      → miss → OCR fallback（跨域通用）
      → miss → VLM rescue
```

### 3.3 关键 API 签名

```rust
// router.rs
pub async fn execute_step(&self, step: &PcStep) -> Result<StepOutcome, RouterError>

// executor 暴露给前端
pub async fn execute_step(step: PcStepView) -> Result<StepResult, String>
```

### 3.4 技能如何声明每个 step 的策略

SKILL.md 中的 step selector 可声明识别类型：

```yaml
steps:
  - id: click-submit
    selector:
      value: "button.submit"
      kind: cdp          # CDP CSS 选择器 → Web 域 → CDP primary
    action: click

  - id: type-name
    selector:
      value: "name-field"
      kind: uia          # UIA 选择器 → Desktop 域 → UIA primary
    action: type

  - id: read-error
    selector:
      value: ".errorMsg"
      kind: ocr          # 直接 OCR → 跳过 primary 走 OCR 快捷路径
    action: read

  - id: screenshot-check
    selector:
      value: "确认按钮区域"
      kind: visual       # Visual → Desktop 域 → UIA primary → OCR → VLM
    action: verify
```

---

## 四、CDP 不可用时的处理

### 4.1 不自动启动浏览器

CDP tier **不会自动 launches 浏览器**。当 `CDP attach failed` 时：
1. Router 记录 primary miss
2. 自动降级到 OCR fallback（在 Web 域下允许）
3. OCR miss → VLM rescue
4. 都不成功 → 返回错误给 LLM

### 4.2 用户通知

```
[router] step[click-submit] primary=CDP miss (no browser attached)
  → fallback OCR → miss
  → escalate to VLM
  → structured miss → { "action": "show_error", "message": "需要浏览器调试端口" }
```

**正确做法**：通知用户"需要手动启动浏览器并开启远程调试端口"或"请切换到原生应用模式"。

**不正确的做法**：自动启动浏览器（破坏用户预期、占用资源）。

### 4.3 CDP 不可用的场景

| 场景 | 行为 | 用户通知 |
|------|------|----------|
| 无浏览器运行 | CDP attach failed → OCR → VLM | "需要浏览器已运行并开启调试端口" |
| 浏览器无 CDP 端口 | CDP discover 失败 → 同上 | 同上 |
| Electron 应用的 CDP | 直接连接成功 | 正常使用 |
| 代理/CDP 隧道中断 | connect 失败 → 同上 | "CDP 连接中断，尝试 OCR/VLM 替代" |

---

## 五、技能市场 — 按需下载安装

### 5.1 7 个市场源

| 源 | 下载方式 | 用途 |
|----|----------|------|
| LinkFox | `npx linkfoxskill init <id>` | 社区技能集 |
| Skills.sh (Nexscope) | `curl` 直接下载 SKILL.md | GitHub 托管技能 |
| ClawHub | `npx clawhub@latest install <id>` | CLI 市场 |
| SkillStore | `npx skillstore add <id>` | 独立市场 |
| Noique | `curl` 下载 SKILL.md | 跨境技能集合 |
| SkillBank.app | HTTP API 下载 | 付费技能分销 |
| FindSkill.com | Catalog index only | 发现入口 |

### 5.2 技能生命周期（从发现到执行）

```
用户请求技能
  → AI 无法用内置技能满足
  → 触发 discover_skills_from_server()
    → 1. 远程 MCP 搜索 (搜索 query)
    → 2. 本地缓存镜像匹配 (skill_catalog_cache.json)
    → 3. 返回候选列表
    → 4. SkillEvaluator 4 维度评分
         (成功率/稳定性/效率/通用性)
    → 5. 高分候选 → adopt_skill_upgrade()
         → 下载 SKILL.md 到 skills_market/{id}/
         → 更新 _index.json
    → 6. 用户确认后 execute_skill(skill_id)
```

### 5.3 技能评估维度（4 维度加权评分）

| 维度 | 权重 | 说明 |
|------|------|------|
| 成功率 (success_rate) | ~40% | 历史执行成功率 |
| 稳定性 (stability) | ~30% | 失败率/熔断状态 |
| 效率 (efficiency) | ~15% | 平均执行时间 |
| 通用性 (universality) | ~15% | 适用场景广度 |

### 5.4 技能存储

下载的技能存储在 `{app_data}/skills_market/`：
- `skills_market/_index.json` — 技能索引
- `skills_market/{skill_id}/SKILL.md` — 技能定义
- `skills_market/{skill_id}/` — 可选的配套文件

### 5.5 Tauri IPC 命令

```rust
// 搜索市场
invoke('search_multi_market', { query: 'mini-program', sources: ['LinkFox', 'SkillsSh'] })

// 下载技能
invoke('download_market_skill', { source: 'SkillsSh', skillId: 'wechat-mini-program-builder', downloadCommand: '...' })

// 查看已下载技能
invoke('list_downloaded_market_skills')

// 删除已下载技能
invoke('delete_downloaded_market_skill', { skillId: '...' })
```

---

## 六、内置技能与市场技能的统一执行

### 6.1 统一入口

所有技能通过同样的 `execute_skill` 命令执行：

```rust
// 内置技能
execute_skill("builtin-mini-program-helper", { action: "create", ... })

// 市场下载技能
execute_skill("com.market.mini-program-builder", { ... })
```

### 6.2 内置技能路径

```
前端 invoke('execute_skill', { skillId: 'builtin-mini-program-helper' })
  → commands::skill::execute_skill()
    → load_manifest_from_skill_id("builtin-mini-program-helper")
      → 从 skills_embedded 中查找 ID 匹配
      → 解析 EmbeddedSkill 返回 manifest
    → parse_skill_steps_from_manifest(manifest)
      → 解析 params 定义 → 生成 Step 选择器
    → AutomationEngine::execute(request_id, manifest, params)
      → 逐 Step 通过 PcRouter::execute_step() 走 CDP→UIA→OCR→VLM 链
```

### 6.3 市场技能路径

```
前端 invoke('execute_skill', { skillId: 'com.example.mini-builder' })
  → commands::skill::execute_skill()
    → load_manifest_from_skill_id()
      → skills_market/ 目录查找
      → 读取 SKILL.md 解析 manifest
    → 同上
```

---

## 七、技能开发规范

### 7.1 标准技能文件结构

```
skill-xxx/
  SKILL.md              # 必要 — frontmatter + 人读说明
  index.js              # 必要 — 运行时 handler（内置技能）
  flowchart.json        # 推荐 — 流程图配置
  USAGE.md              # 推荐 — 使用文档
  DEBUG.md              # 推荐 — 调试文档
  UPGRADE.md            # 推荐 — 升级文档
```

### 7.2 SKILL.md Frontmatter 规范

```yaml
---
id: "com.tupautochrome.skills.xxx"   # 反域名，全局唯一
name: "技能中文名"
name_en: "Skill English Name"
version: "1.0.0"                      # SemVer
author: "AIMarketing"
license: "MIT"
category: "mobile"                     # web | desktop | mobile | data | misc
software_names: ["微信小程序", "WeChat DevTools"]
tags: ["mini-program", "wechat", "development"]
keywords: ["小程序", "微信开发", "WXML"]

recognition: ["cdp", "uia", "ocr", "vlm"]  # 声明可用识别层

capabilities:
  - id: "create"
    name: "项目搭建"
    inputs: [...]
    outputs: [...]

runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.llm@^1.0.0"
    - "cap.flowchart@^1.0.0"
  permissions:
    - "http:fetch:*"

distribution:
  channel: "stable"
  minAppVersion: "0.5.0"
  rollout:
    percentage: 100

signing:
  algorithm: "ed25519"
  publicKey: ""
---
```

### 7.3 技能 Handler 实现

前端通过 `new Function()` 在内存中执行 JS 技能代码。Skills use three exports:

```javascript
// 1. 主 handler
const SKILL_ID = 'com.tupautochrome.skills.xxx'
const FLOWCHART = { /* ... */ }

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  switch (action) {
    case 'create': return await createProject(params)
    case 'guidance': return await devGuidance(params)
    // ...
  }
}

// 2. 生命周期钩子
export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('xxx', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('xxx', 'task start'),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('xxx', 'task end'),
  onSkillUnload: async (ctx) => cap.runtime.log('xxx', 'skill unloaded'),
}

// 3. 调试钩子
export const debug = { getVariableScope, onBreakpoint }

export default handler
```

### 7.4 技能 JS 中如何声明 CDP 依赖

```javascript
// 示例：使用 CDP 控制浏览器
async function handleStep(params) {
  // 检查 CDP 可用性
  if (!cap.cdp) {
    return { ok: false, error: 'CDP backend not available', fallbackSuggestion: '请确保浏览器已开启远程调试端口' }
  }

  // 使用 CDP 执行操作
  const result = await cap.cdp.eval(`document.querySelector(...)`)
  return result
}
```

### 7.5 技能 JS 中如何实现自动降级

```javascript
// 推荐模式：CDP 失败 → OCR → 告知用户
async function robustClick(params) {
  const cdpResult = await tryCdpClick(params)
  if (cdpResult.ok) return cdpResult

  const ocrResult = await tryOcrClick(params)
  if (ocrResult.ok) return ocrResult

  // 都不成功 → 返回描述性错误，让 LLM 决定下一步
  return {
    ok: false,
    error: '无法在当前页面找到目标元素',
    suggestion: '请手动点击目标按钮，或确认目标元素是否在当前页面',
    diagnostics: { cdpError: cdpResult.error, ocrError: ocrResult.error }
  }
}
```

---

## 八、完整执行流程（技能 + CDP 链）

### 8.1 端到端流程

```
用户说："帮我写一个小程序"
  │
  ├─ AI 读取内置技能列表
  │   → 发现 builtin-mini-program-helper (id: com.tupautochrome.skills.mini-program-helper)
  │   → 调用 execute_skill(skill_id="builtin-mini-program-helper", params={action: "create", projectName: "我的小程序"})
  │
  ├─ execute_skill() 加载技能 manifest
  │   → 从 skills_embedded.get_builtin_skills() 返回 EmbeddedSkill
  │   → new Function() 执行 JS handler
  │
  ├─ handler(action="create") 被调用
  │   → cap.fillchart.setCurrent() 设置流程图
  │   → LLM 生成项目结构 + app.json + 首屏代码
  │   → 返回 project scaffold
  │
  ├─ 如果需要实际操作浏览器（如调试小程序）
  │   → 引擎逐 Step dispatch
  │   → PcRouter::execute_step()
  │       → domain_for_step() 判断 Web/Desktop
  │       → Web → CDP primary (attach to browser)
  │       → CDP miss → OCR fallback
  │       → OCR miss → VLM rescue
  │       → All miss → error → LLM 决定下一步
  │
  └─ 若 skill 需要但尚未安装
      → AI 发现技能不在内置列表
      → 触发 discover_skills_from_server("mini-program builder")
      → 市场搜索 → 评估 → adopt → 下载 SKILL.md
      → execute_skill(skill_id="new-skill-id", params)
```

### 8.2 CDP attach 失败的完整降级

```
execute_step(Click 按钮)
  │
  ├─ domain = Web (CDP primary)
  ├─ CDP attach → 失败 ("no CDP target found")
  │   → RouterError::PrimaryMiss
  │
  ├─ OCR fallback → miss (页面上没有可识别的文本)
  │   → RouterError::StructuredMiss
  │
  ├─ VLM rescue → 成功! (截图识别按钮位置)
  │   → StepOutcome { success: true, strategy_used: "vlm" }
  │
  └─ 或 VLM 也失败
      → return Err(RouterError::StructuredMiss)
      → executor 处理为 "action_failed"
      → LLM 收到 tool result "CDP+OCR+VLM 均失败"
      → LLM 回复用户 "无法自动操作，请手动完成"
```

---

## 九、技能注册规范

### 9.1 内置技能注册（编译期）

内置技能通过 `include_str!` 在编译期嵌入 Rust 二进制：

**`src-tauri/src/skills_embedded.rs`**:

```rust
// 1. 添加 include_str 引用
const MINI_PROGRAM_HELPER_JS: &str = include_str!("skills/mini-program-helper.js");

// 2. 在 get_builtin_skills() 中添加 EmbeddedSkill 条目
EmbeddedSkill {
    id: "builtin-mini-program-helper".to_string(),
    name: "微信小程序开发助手".to_string(),
    version: "1.0.0".to_string(),
    description: "全流程微信小程序开发指导...".to_string(),
    category: Some("开发工具".to_string()),
    tags: vec!["mini-program".to_string(), "development".to_string()],
    params: serde_json::json!({ ... }),
    entry_action: "guidance".to_string(),
    code: prepend_layers(MINI_PROGRAM_HELPER_JS, gateway_url),
},
```

### 9.2 市场技能注册（运行时）

市场下载的技能存储在 `{app_data}/skills_market/`：
- `_index.json` 记录所有已下载技能
- 每次 execute_skill 时从磁盘或内存读取 manifest
- 无需重启 app 即可使用新下载的技能

### 9.3 manifest.json 注册

`skills/manifest.json` 是 filesystem skills 的索引：

```json
{
  "skills": [
    // ... existing skills ...
    {
      "id": "mini-program-helper",
      "name": "微信小程序开发助手",
      "version": "1.0.0",
      "file": "mini-program-helper/index.js",
      "recognition": ["cdp", "uia", "ocr", "vlm"],
      "tags": ["mini-program", "wechat", "development", "guidance"],
      "flowchart": "mini-program-helper/flowchart.json"
    }
  ]
}
```

---

## 十、CDP 通知用户的模式

### 10.1 CDP 不可用时的用户 Notification

不自动启动浏览器，而是通知用户手动操作：

```
⚠️ CDP 连接失败
原因: 未找到浏览器调试端口 (9222-9230)

建议操作:
1. 启动浏览器并开启远程调试端口:
   - Chrome: chrome.exe --remote-debugging-port=9222
   - Edge: msedge.exe --remote-debugging-port=9222
2. 或切换到 UIA 模式（原生 Windows 应用）
3. 或使用 OCR/VLM 辅助识别
```

### 10.2 前端展示模式

前端收到 `CDP attach failed` 后展示：
1. Toast/通知 "CDP 连接失败，尝试 OCR 替代方案"
2. 自动走 OCR fallback 路径
3. OCR 也失败时，展示 Dialog 让用户选择手动操作

### 10.3 代码示例：CDP 失败通知

```javascript
// 技能 JS 中处理 CDP 失败
async function robustCdpAction(params) {
  let result

  // 尝试 CDP
  result = await tryCdp(params)
  if (result.ok) return result

  // CDP 失败 → 通知用户 + 自动降级
  cap.runtime.notify({
    type: 'cdp_fallback',
    message: 'CDP 连接失败，已自动切换到 OCR 识别',
    detail: { cdpError: result.error, fallback: 'ocr' }
  })

  // 尝试 OCR
  result = await tryOcr(params)
  if (result.ok) return result

  return {
    ok: false,
    error: 'CDP + OCR 均失败',
    suggestion: '请手动操作或启动浏览器远程调试端口',
    notification: {
      type: 'manual_intervention_required',
      message: '需要手动操作此步骤'
    }
  }
}
```

---

## 十一、关键文件索引

| 文件 | 用途 |
|------|------|
| `src/pc_automation/router.rs` | CDP→UIA→OCR→VLM 路由核心逻辑 |
| `src/pc_automation/executor/selector.rs` | Selector → StepStrategy 映射 |
| `src/commands/skill.rs` | `execute_skill` Tauri 命令 |
| `src/commands/skill_discovery.rs` | 远程技能发现 → 评估 → 采纳 |
| `src/commands/skill_multi_market.rs` | 7 源市场搜索 + 下载 |
| `skills/manifest.json` | Filesystem 技能注册表 |
| `src-tauri/src/skills_embedded.rs` | 内置技能编译期嵌入 |
| `src/tauri/src/skills/` | 内置技能 JS 文件 (`include_str!` 源文件) |
| `skills/_template/` | 标准技能模板 (SKILL.md + index.js + flowchart.json) |
| `src/hermes/tool_schemas.rs` | Agent Loop 的工具 schema（含 cdp_action） |
| `src/hermes/agent_loop.rs` | ReAct 循环 + 工具注册 |

---

## 十二、常见陷阱

| 陷阱 | 原因 | 解决方案 |
|------|------|----------|
| CDP attach failed 但没有 fallback | skill 声明了 `recognition: ["cdp"]` 但没有声明 `"ocr"` | 确保 `recognition` 包含完整的降级链 `["cdp", "uia", "ocr", "vlm"]` |
| 市场技能下载后无法执行 | manifest.json 中 `file` 路径错误 | 检查 `skills_market/{id}/` 下有对应的 SKILL.md |
| 技能执行但识别链全 miss | 应用类型判断错误 | 确认 step 的 `kind` 正确映射到 `StepStrategy` |
| OCR fallback 被跳过 | `strategy == Ocr` 但 OCR 后端未初始化 | 确保非 Windows 环境下 OCR 后端返回 `BackendUnavailable` 而非 panic |
| 技能加载失败 | JS 代码有语法错误 | 使用 `cap.runtime.log()` 调试，确保 `handler` 函数无编译错误 |
| 内置技能未出现在前端 | `include_str!` 路径错误或 `get_builtin_skills` 中未添加条目 | 确认 `skills_embedded.rs` 中有对应的 `const` 和 `EmbeddedSkill` 条目 |
