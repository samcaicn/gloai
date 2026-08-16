# Understudy 录制模式借鉴与集成方案

> 参考项目：[understudy-ai/understudy](https://github.com/understudy-ai/understudy) (MIT)
> 克隆位置：`up/` 文件夹

## 1. Understudy 录制模式核心架构

### 1.1 双轨录制 (Dual-Track Recording)

Understudy 的录制模式（Teach）采用 **双轨录制** 策略，同时捕获两类数据：

| 轨道 | 实现方式 | 产出 | 用途 |
|------|---------|------|------|
| **视频轨** | `screencapture -x -v -D<display> -k` (macOS) | `.mov` 视频文件（带点击标记） | 场景检测、关键帧提取、AI 视觉分析 |
| **事件轨** | Swift 全局事件监听 (`NSEvent.addGlobalMonitorForEvents`) | `.events.json` 结构化事件日志 | 语义上下文提取、事件聚类、意图推断 |

**事件轨捕获内容：**
- 所有鼠标事件 (down/up/drag/move/scroll)
- 所有键盘事件 (keyDown/keyUp/flagsChanged)
- 应用切换通知 (`NSWorkspace.didActivateApplicationNotification`)
- 每个事件附带语义上下文：app 名称、窗口标题、目标元素 (role/title/description/identifier/value)
- 智能节流：鼠标移动 250ms/28px，拖拽 140ms/18px

### 1.2 证据包构建 (Evidence Pack)

录制结束后，Understudy 不是简单地回放事件，而是构建一个 **证据包**：

```
录制视频 + 事件日志
    ↓
1. 场景检测 (ffmpeg scene detection, threshold=0.12, min_gap=900ms)
2. 事件聚类 (按时间间隔 <1100ms 分组，按类型评分: drag=60/pointer=42/keyboard=34/scroll=24)
3. 三源合并 (事件引导窗口 + 场景引导窗口 + 上下文窗口 [10%/50%/90%])
4. 自适应预算 (最多 18 episodes, 64 keyframes)
5. 语义关键帧 (每 episode 最多 6 帧: before_action/action/settled/after_action/context×2)
6. AI 分析 (关键帧 + 代表性事件 + 能力快照 → LLM → 结构化 JSON)
```

### 1.3 三层抽象模型

生成的 SKILL.md 包含三层抽象，而非坐标录制：

1. **意图流程** (`## Staged Workflow`) — 自然语言步骤描述
2. **路由选项** (`## Tool Route Options`) — 每步标注 preferred/fallback/observed 路由
   - 路由优先级：skill → browser → shell → gui
3. **GUI 回放提示** (`## Detailed GUI Replay Hints`) — 最后手段，每次从当前截图重新定位

### 1.4 多轮澄清对话

录制后进入澄清模式：
- AI 分析录制内容 → 生成任务草稿 (teach draft)
- 草稿包含：标题、目标、参数槽位、步骤、成功标准
- 用户通过自然语言对话逐步精炼
- `/teach confirm` 锁定任务卡
- `/teach validate` 可选回放验证
- `/teach publish` 发布为工作区技能

### 1.5 路由优化

每个步骤标注三种路由偏好：
- `preferred` — 首选执行路径
- `fallback` — 降级方案
- `observed` — 录制时观察到的路径

执行策略：
- `toolBinding: "adaptive"` — 工具绑定自适应
- `stepInterpretation: "fallback_replay"` — 步骤解释为降级回放
- 优先复用已有工作区技能 > 浏览器自动化 > Shell 命令 > GUI 操作

## 2. 当前项目 (safeopcAPP) 现状分析

### 2.1 已有录制能力

| 能力 | 实现位置 | 状态 |
|------|---------|------|
| 事件录制 (rdev) | `src-tauri/src/automation/recorder.rs` | ✅ 已实现 |
| UIA 元素查询 | `recorder.rs` (Windows UIA) | ✅ 已实现 |
  | 截图捕获 | `recorder.rs` (GDI) | ✅ 已实现 |
| 事件→流程图转换 | `automation/flowchart.rs` | ✅ 已实现 |
| skill.md 生成 | `recorder.rs: generate_skill_md()` | ✅ 已实现 |
| MCP 编译 | `skill/compiler.rs` | ✅ 已实现 |
| 技能提案存储 | `skill/proposal_store.rs` | ✅ 已实现 |
| 暂停/恢复 | `teaching.rs` | ✅ 已实现 |
| 小循环检测 | `flowchart.rs: detect_small_loops()` | ✅ 已实现 |
| DuckDB 教学记录 | `storage/teach_record.rs` | ✅ 已实现 |

### 2.2 与 Understudy 的差距

| 维度 | 当前项目 | Understudy | 差距 |
|------|---------|------------|------|
| **录制轨道** | 仅事件轨 (rdev) | 双轨 (视频 + 事件) | 缺少视频录制 |
| **证据包** | 无 | 完整证据包构建 | 缺少场景检测/关键帧 |
| **AI 分析** | 无 (直接事件→YAML) | LLM 分析录制内容 | 缺少意图提取 |
| **任务抽象** | 单层 (步骤列表) | 三层 (意图/路由/GUI提示) | 缺少路由选项 |
| **澄清对话** | 无 | 多轮自然语言精炼 | 缺少交互式精炼 |
| **路由优化** | 无 | preferred/fallback/observed | 缺少路由标注 |
| **参数提取** | 无 | 自动识别可变参数 | 缺少参数槽位 |
| **成功标准** | 无 | AI 生成验证条件 | 缺少验证逻辑 |
| **回放验证** | 无 | 可选回放验证 | 缺少验证机制 |

## 3. 集成方案

### 3.1 分阶段实施

#### Phase 1: 增强录制类型定义 (前端)

新增录制模式类型，支持双轨录制概念：

```typescript
// 录制模式
type RecordingMode = 'standard' | 'enhanced';

// 增强录制结果
interface EnhancedRecordingResult {
  // 标准录制结果
  skillMd: string;
  mcpBlobBase64: string;
  stepCount: number;
  flowchart: Flowchart;
  // 增强录制新增
  videoPath?: string;           // 视频轨产出
  eventLogPath?: string;        // 事件日志路径
  evidenceFrames?: EvidenceFrame[];  // 证据包关键帧
  analysisResult?: RecordingAnalysis;  // AI 分析结果
}

// 证据包关键帧
interface EvidenceFrame {
  path: string;
  timestampMs: number;
  label?: string;
  kind: 'before_action' | 'action' | 'after_action' | 'settled' | 'context';
  episodeId?: string;
}

// AI 分析结果
interface RecordingAnalysis {
  title: string;
  objective: string;
  parameterSlots: ParameterSlot[];
  successCriteria: string[];
  openQuestions: string[];
  steps: AnalyzedStep[];
  routeOptions: RouteOption[];
}

// 参数槽位
interface ParameterSlot {
  name: string;
  label: string;
  sampleValue?: string;
  required: boolean;
  notes?: string;
}

// 路由选项
interface RouteOption {
  stepIndex: number;
  route: 'skill' | 'browser' | 'shell' | 'gui';
  preference: 'preferred' | 'fallback' | 'observed';
  instruction: string;
  when?: string;
}
```

#### Phase 2: 增强录制浮窗 (前端)

在 `FloatingWindow` 中新增增强录制模式 UI：
- 显示双轨录制状态（视频 + 事件）
- 实时显示事件计数和录制时长
- 录制结束后显示 AI 分析进度
- 展示分析结果（任务标题、参数槽位、路由选项）
- 支持多轮澄清对话

#### Phase 3: 增强后端录制 (Rust)

- 新增视频录制命令（Windows: DXGI Desktop Duplication / macOS: screencapture）
- 新增证据包构建模块（场景检测 + 事件聚类 + 关键帧提取）
- 新增 AI 分析接口（调用配置的 LLM 分析录制内容）
- 新增三层抽象生成器（意图流程 + 路由选项 + GUI 回放提示）

#### Phase 4: CI Push 流程增强

借鉴 Understudy 的 CI 模式，增加 push 时的代码质量验证：

```yaml
# 新增 validate job：push 时运行 lint + typecheck + test
validate:
  runs-on: ubuntu-latest
  steps:
    - checkout
    - setup pnpm + node
    - install deps
    - lint (oxlint/eslint)
    - typecheck (tsc --noEmit)
    - unit tests (vitest)
    - cargo check --all-targets (Rust 全量检查)
```

### 3.2 架构映射

```
Understudy 架构                    →    safeopcAPP 集成方案
─────────────────────────────────────────────────────────────
demonstration-recorder.ts          →    recorder.rs (增强)
  ├─ screencapture (视频轨)         →    新增 video_recorder 模块
  └─ Swift event monitor (事件轨)  →    rdev listen (已有)

video-teach-analyzer.ts            →    新增 teach_analyzer 模块
  ├─ Evidence Pack 构建             →    新增 evidence_pack 模块
  ├─ AI 分析 (LLM)                  →    新增 ai_analysis 接口
  └─ 三层抽象生成                   →    新增 skill_generator 模块

task-drafts.ts (草稿管理)          →    增强 proposal_store + 新增 draft 管理

chat-interactive-teach.ts          →    增强 FloatingWindow recorder 模式
  ├─ /teach start/stop              →    recordingStart/Stop (已有)
  ├─ 澄清对话                       →    新增 clarification 流程
  └─ confirm/validate/publish      →    新增 draft lifecycle
```

## 4. 文件清单

### 4.1 新增文件

| 文件 | 用途 |
|------|------|
| `src/web-ui/.../types/recording.ts` | 增强录制类型定义 |
| `src/web-ui/.../components/EnhancedRecorder/` | 增强录制浮窗组件 |
| `src-tauri/src/automation/video_recorder.rs` | 视频录制模块 |
| `src-tauri/src/automation/evidence_pack.rs` | 证据包构建模块 |
| `src-tauri/src/automation/teach_analyzer.rs` | AI 分析接口 |
| `.github/workflows/ci-validate.yml` | Push 代码质量验证 CI |

### 4.2 修改文件

| 文件 | 改动 |
|------|------|
| `src-tauri/src/commands/teaching.rs` | 增加增强录制命令 |
| `src/web-ui/.../infrastructure/api/tupai/recording.ts` | 增加增强录制 API |
| `src/web-ui/.../scenes/automation/AutomationScene.tsx` | 增加增强录制入口 |
| `.github/workflows/build.yml` | 集成 validate job |

## 5. Understudy 关键代码参考索引

| 功能 | 文件路径 |
|------|---------|
| 录制器实现 | `up/packages/gui/src/demonstration-recorder.ts` |
| 录制器类型 | `up/packages/gui/src/types.ts` (L303-L338) |
| 视频教学分析器 | `up/packages/tools/src/video-teach-analyzer.ts` |
| 任务草稿管理 | `up/packages/core/src/task-drafts.ts` |
| 网关草稿处理器 | `up/packages/gateway/src/task-drafts.ts` |
| CLI Teach 交互 | `up/apps/cli/src/commands/chat-interactive-teach.ts` |
| 教学能力快照 | `up/packages/tools/src/teach-capability-snapshot.ts` |
| 产品设计文档 | `up/docs/Product_Design.md` (Layer 2: L91-L180) |
| 发布技能示例 | `up/examples/published-skills/.../SKILL.md` |
