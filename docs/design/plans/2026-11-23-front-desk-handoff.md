# 前台层实施：交接说明（2026-11-23，第二次交接）

给接手的 agent。按这份说明可以接着做，不需要回看之前的对话。上一版交接（Task 3 半途）已被执行完，本版是当前状态。

## 1. 在哪、做到哪了

- **工作目录**：`/Users/bolyn/coding-projects/Mika/.claude/worktrees/front-desk`（git worktree，分支 `front-desk`，从本地 main `2e33e150b` 拉出）。所有活都在这里干。**不要动 main、其他分支和其他 worktree，不要 push、不要 merge**，除非用户亲口要求。
- **要读的三份文件**（都已提交在这个分支上）：
  - 设计（最高依据）：`docs/design/specs/2026-11-23-front-desk-design.md`
  - 实施计划（11 个任务 + 插入的 3b）：`docs/design/plans/2026-11-23-front-desk.md`
  - 聊天页原型（用户已认可 v2 + 输入框上方在办条变体）：`docs/design/prototypes/2026-11-23-front-desk-chat.html`
- **进度账本**（最重要，git 忽略、只在磁盘上）：`.superpowers/sdd/2026-11-23-front-desk/progress.md`。里面有预检表、所有裁定（Ruling A–K）、每个任务的提交区间和延后的小问题。**以账本 + `git log` 为准**，不要凭印象重做已完成的任务。
- **当前状态**（写这份时，HEAD = `c4ac312e2`）：

| 任务 | 状态 | 提交 |
|---|---|---|
| 1 对话脑子搬进 core | ✅ 完成，审查通过 | `4e44b27cf` |
| 2 任务事件归类 + 汇报规则 | ✅ 完成，审查通过 | `6fcb6813b` |
| 3 调度工具 + 执行器 | ✅ 完成，审查通过 | `ea95da0ef` |
| 3b 前台只说话+派活（用户改定，去掉查询工具） | ✅ 完成，审查通过 | `455cb0c19`…`677a7ae66` |
| 4 按会话常驻 FrontDesk | ✅ 完成，审查通过 | `3db7ef9ff` |
| 5 后台接收前台补充 | ✅ 完成，审查通过 | `1727a6f81` |
| 6 接线 app 层（1 轮修复后通过） | ✅ 完成，审查通过 | `cef4d456f`…`37fb910c3` |
| 7 前台提示词 + 模型选择 | ✅ 完成，审查通过 | `c4ac312e2` |
| 8 语音通话接前台 | ⏸ **实现者已派出，被叫停时还没动工作区** | 起点 `c4ac312e2` |
| 9 聊天页走前台（原型门已开） | 未开始 | — |
| 10 AI 电话里的前台 | 未开始 | — |
| 11 全量回归 + 真机验证 | 未开始 | — |

**Task 8 的摊子**：实现者（opus）被派出后不久用户叫停，控制器已直接终止它。**终止时它还在读代码阶段，工作区干净、没有任何半成品**（已验证）。**接手第一件事：重新派一个 Task 8 实现者**（opus，BASE = 当时的 HEAD），按第 2 节的提示词写法，带上第 3 节的裁定（Ruling B/F/G/H/I 必带）。Task 8 的 brief 已生成好：`.superpowers/sdd/2026-11-23-front-desk/task-8-brief.md`，报告写到同目录 `task-8-report.md`。

## 2. 执行方式（用户选的：subagent-driven）

用 superpowers 的 `subagent-driven-development` 技能。每个任务：派一个实现子代理 → 派一个审查子代理 → 有 Critical/Important 就回给原实现者修 → 通过后记账本 → 下一个。不在任务之间停下来问用户。

**脚本**（技能目录 `/Users/bolyn/.claude/plugins/cache/superpowers-dev/superpowers/7.0.2/skills/subagent-driven-development/scripts/`，在 worktree 根目录下跑）：
- 生成任务说明：`bash <dir>/task-brief $PWD/docs/design/plans/2026-11-23-front-desk.md N` → 写出 `task-N-brief.md`（**8/9/10/11 的 brief 已经生成好**，直接在 `.superpowers/sdd/2026-11-23-front-desk/` 里）
- 生成审查包：`bash <dir>/review-package $PWD/docs/design/plans/2026-11-23-front-desk.md <BASE> HEAD`。BASE 是派实现者之前记下的 HEAD，**不要用 HEAD~1**

**已经准备好的公共文件**（在 `.superpowers/sdd/2026-11-23-front-desk/`，派活时让子代理读，提示词就能写得很短）：
- `implementer-rules.md`：实现者守则 + 本项目规则（只在本 worktree 干活、本地化 12 份、测试命令等）
- `reviewer-rules.md`：审查方法和输出格式
- `global-constraints.md`：计划里的全局约束

**模型**：一律用 **`opus`**（Ruling D：`sonnet` 别名在这个账号上 404）。

**验证**：纯 core 的任务跑 `./gradlew :core:test`；碰 app 的任务（8/9/10/11 全部）跑 `./gradlew :app:testDebugUnitTest` 全量（约 20 分钟）。**不要跑裸 `:app:test`**（会 OOM）。已知本地负载抖动可忽略：`TerminalHubTest` 15s 超时、`McpServerStoreTest.cancelDuringConnectClosesWhatConnected`（diff 不碰对应区域时）。

## 3. 已定的裁定（派后面的任务时要带上）

账本里有原文，这里列后面任务会用的：
- **Ruling A**：`DeskBrain.seed(prior)` 只在历史为空时生效，每个 Turn 展开成 用户(userText 非空) + 助手(outcome 非空)，不看 status。
- **Ruling B**：清洗函数在 core `DeskLines.stripDetails`；`VoiceCallRoute` 调它，Task 8 删 `VoiceCallRoute` 时确认没有别的调用方被晾着。结果上限常量 `DESK_RESULT_MAX_CHARS = 1_800`。
- **Ruling C**：原型已认可（v2 + 输入框上方在办条变体，11-23 18:03），**Task 9 的门已开**，直接按原型做。
- **Ruling E**：只改 core 的任务不跑 app 全量。
- **Ruling F**（Task 3 实际接口）：`DeskLedger.add(entry: DeskTaskEntry)`；`DeskToolExecutor` 构造带 `conversationId: String` + `onDispatched: (DeskTaskEntry) -> Unit = {}`；`DISPATCH_DEDUPE_MS = 30_000L`；工具结果形态固定（`started{task_id}` / `queued{task_id, position}` / `failed{reason}` / `ok` / `not_accepted` / `rejected{reason}` / `unknown_task` / `already_dispatched{task_id}` / `unavailable` / `unsupported`）。
- **Ruling G**：`FrontDesk.track(taskId, title)` 不写账本（执行器写）；app 接线 `onDispatched = { desk.track(it.taskId, it.title) }`。
- **Ruling H**：interrupted 报告去下一个挂着的 surface，否则立刻进 sink（写进会话，不重念）——`FrontDesk.deliver` 已是这个行为，bridge 的 `deliver` 返回 false 就会触发。
- **Ruling I（用户 11-23 改定）**：前台只说话+派活。User 工具 = dispatch/add/answer/cancel；Phone 工具 = 只有 ask_owner；查询工具（check_tasks/web_search/recall_memory）已删除，任务板+记忆由代码经 DeskBackground 放进上下文。**任何任务都不得把查询工具接回来。**
- **Ruling J**：背景块是临时尾随消息（system 保持字节稳定吃缓存）；记忆每句用户话查一次；任务板带进行中/待答任务最近 ≤3 行进展。
- **Ruling K**：model 适配器有 opt-in 的不缓存消息标记，移动缓存点放在不缓存块之前；默认请求保持字节不变。
- **Task 6 的 Ruling**：desk 的 steer 走本地 `run.steer`，不是计划原文的 `steerRemote`（那是 MCP 专用）。
- 派每个任务前看一眼真实代码签名，别照抄计划原文。
- 派发顺序：**`8 → 10 → 9 → 11 → 全分支终审`**。

## 4. 剩下的任务要点

- **Task 8（语音通话接前台）**：删 `VoiceCallRoute`/`VoiceCallTaskLauncher` 及其测试；新建 `VoiceDeskBridge`；`CallRealtimeSession.sendNotice`；级联 + 实时（Volc）两套；`onBargeIn` 不再取消任务；对话流任务卡改由 `desk.active` 驱动。账本里两条 deferred 要在本任务顺手验证：desk_report 行渲染、sink 通知靠 `ReportPolicy` 节流（不要在 bridge 里造第二个节流）。
- **Task 9（聊天页走前台）**：按原型做，门已开（Ruling C）。
- **Task 10（AI 电话前台）**：`PhoneDesk` 只有 `ask_owner`；主人回复走通知 `RemoteInput`（minSdk 29 满足 API 24+）；`OwnerReplyReceiver` 在 Manifest 注册、不导出。
- **Task 11（真机）**：打 perf 包（`./gradlew :app:assemblePerf`，正式版就是这个变体、debug 签名）、装测试机（安装要在手机上点一下，**用户在手机旁才能做**）、`adb shell cmd package compile -f -m speed com.wangzan101.mika` 强制 AOT（注意：`cmd compile` 在这台机上不存在，要用 `cmd package compile`）。测试机是 HONOR ANP-AN00（A94VUT2C28001694，Android 14）。用 `LiveRunTest` 自己跑场景。用户不在就把能自动跑的跑完，真机项写进验收文档标「待真机」。
- **全分支终审**：所有任务完成后按技能的 `references/final-review.md` 做，用最强模型。账本里所有 `minor (deferred)` 交给终审分拣。
- 做完以后用 `superpowers:finishing-a-development-branch` 问用户怎么合，**不要自己合到 main**。

## 5. 其他注意

- 主仓库 `/Users/bolyn/coding-projects/Mika` 里还有这三份文档建 worktree 之前留下的未跟踪旧副本（`docs/superpowers/{specs,plans,prototypes}/2026-11-23-front-desk*`）。正式版本在分支上（已随 main 的目录分层移到 `docs/design/`），旧副本等合并时删掉，现在别动。
- main 的 perf 包（0.1.51, versionCode 52, `a85b8a7be`）已装到测试机给用户体验，与本分支无关。
- 项目规矩见仓库根目录的 `CLAUDE.md` 和用户记忆目录 `/Users/bolyn/.claude/projects/-Users-bolyn-coding-projects-Mika/memory/MEMORY.md`（通用安卓、不加小众服务商、按根因修、新界面先原型等）。
- 每做完一个任务就往账本追加一行 `Task N: complete (commits a..b, review clean)`，小问题记成 `Task N: minor (deferred): …`。
- 代码和注释用中文，跟随周围风格；提交信息按任务 brief 给的写。
