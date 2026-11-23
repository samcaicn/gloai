# 前台层实施：交接说明（2026-11-23）

给接手的 agent。按这份说明可以接着做，不需要回看之前的对话。

## 1. 在哪、做到哪了

- **工作目录**：`/Users/bolyn/coding-projects/Mika/.claude/worktrees/front-desk`（git worktree，分支 `front-desk`，从本地 main `2e33e150b` 拉出）。所有活都在这里干。**不要动 main、其他分支和其他 worktree，不要 push、不要 merge**，除非用户亲口要求。
- **要读的三份文件**（都已提交在这个分支上）：
  - 设计（最高依据）：`docs/superpowers/specs/2026-11-23-front-desk-design.md`
  - 实施计划（11 个任务）：`docs/superpowers/plans/2026-11-23-front-desk.md`
  - 聊天页原型：`docs/superpowers/prototypes/2026-11-23-front-desk-chat.html`
- **进度账本**（最重要，git 忽略、只在磁盘上）：`.superpowers/sdd/2026-11-23-front-desk/progress.md`。里面有预检表、所有裁定（Ruling A–F）、每个任务的提交区间和延后的小问题。**以账本 + `git log` 为准**，不要凭印象重做已完成的任务。
- **当前状态**（写这份时）：

| 任务 | 状态 | 提交 |
|---|---|---|
| 1 对话脑子搬进 core（notice/seed） | ✅ 完成，审查通过 | `4e44b27cf` |
| 2 任务事件归类 + 汇报规则 | ✅ 完成，审查通过 | `6fcb6813b` |
| 3 调度工具 + 执行器 | ⏸ 做到一半被叫停（没提交、没报告、没跑通测试） | 起点 `6fcb6813b` |
| 4–11 | 未开始 | — |

**Task 3 留下的半成品**：工作区里有它没提交的改动，未经测试，可能编译不过。
- 改了：`core/.../frontdesk/DeskEvent.kt`、`DeskLines.kt`
- 新建：`DeskLedger.kt`、`DeskLookup.kt`、`DeskTaskPort.kt`、`DeskToolExecutor.kt`、`DeskTools.kt`、`core/src/test/.../frontdesk/DeskToolExecutorTest.kt`
- 备份补丁：`.superpowers/sdd/2026-11-23-front-desk/task-3-partial.patch`（新文件也包含在内）

**接手第一件事**：`git status` 确认上面这些还在，然后**重新派一个 Task 3 实现者**（BASE = 派之前的 HEAD，也就是这份交接说明的提交，用 `git rev-parse HEAD` 取）。提示词里告诉它：「工作区里已有上一个实现者没做完的 Task 3 改动，先读一遍，能用的接着用、不对的改掉，按 brief 补齐测试（先确认测试能失败再让它通过），跑 `./gradlew :core:test` 全绿后再提交」。如果你更想从头做，先 `git checkout -- core && git clean -fd core/src`（看清楚只删这些文件），再按 brief 正常派。
- Task 3 的说明已经生成好：`.superpowers/sdd/2026-11-23-front-desk/task-3-brief.md`；报告写到同目录的 `task-3-report.md`。
- 派 Task 3 时必须带上下面第 3 节的 **Ruling F**（brief 里没有）。

## 2. 执行方式（用户选的：subagent-driven）

用 superpowers 的 `subagent-driven-development` 技能。每个任务：派一个实现子代理 → 派一个审查子代理 → 有 Critical/Important 就回给原实现者修 → 通过后记账本 → 下一个。不在任务之间停下来问用户。

**脚本**（技能目录 `/Users/bolyn/.claude/plugins/cache/superpowers-dev/superpowers/7.0.2/skills/subagent-driven-development/scripts/`，在 worktree 根目录下跑）：
- 生成任务说明：`bash <dir>/task-brief $PWD/docs/superpowers/plans/2026-11-23-front-desk.md N` → 写出 `task-N-brief.md`
- 生成审查包：`bash <dir>/review-package $PWD/docs/superpowers/plans/2026-11-23-front-desk.md <BASE> HEAD`。BASE 是派实现者之前记下的 HEAD，**不要用 HEAD~1**

**已经准备好的公共文件**（在 `.superpowers/sdd/2026-11-23-front-desk/`，派活时让子代理读，提示词就能写得很短）：
- `implementer-rules.md`：实现者守则 + 本项目规则（只在本 worktree 干活、本地化 12 份、测试命令等）
- `reviewer-rules.md`：审查方法和输出格式
- `global-constraints.md`：计划里的全局约束

**派实现者的提示词写法**（照 Task 1–3 的样子）：一句话说明这个任务在干什么 → `task-N-brief.md` 路径（「先读，这是你的需求」）→ 让它读 `implementer-rules.md` → 前面任务定下来、brief 不知道的接口和裁定 → 报告文件路径 `task-N-report.md`。

**模型**：`model: "sonnet"` 在这个账号上会 404（被路由到一个不可用的模型），**一律用 `opus`**（账本 Ruling D）。

**验证**：纯 core 的任务跑 `./gradlew :core:test` 全量 + 相关 app 测试；Task 6/8/9/10/11 跑 `./gradlew :app:testDebugUnitTest` 全量（约 20 分钟）。**不要跑裸 `:app:test`**（会内存溢出）。`TerminalHubTest` 偶发 15 秒超时是本地负载抖动，改动不碰 terminal 时忽略。

## 3. 已定的裁定（派后面的任务时要带上）

账本里有原文，这里列后面任务会用到的：
- **Ruling A**：`DeskBrain.seed(prior)` 只在历史为空时生效，每个 Turn 展开成 用户(userText 非空) + 助手(outcome 非空)，不看 status。
- **Ruling B**：清洗函数已搬到 core `DeskLines.stripDetails`；`VoiceCallRoute` 暂时改调它，Task 8 删 `VoiceCallRoute`。结果上限常量是 `DESK_RESULT_MAX_CHARS = 1_800`（取代 `VOICE_RESULT_MAX_CHARS`）。
- **Ruling C**：用户看过聊天页原型、没提意见就选了执行方式，按「原型已认可」处理；但 **Task 9 排在 Task 10 之后做**，好让用户有机会补意见。开始 Task 9 前如果用户在场，顺口问一句原型要不要改。
- **Ruling E**：只改 core 的任务不跑 app 全量。
- **Ruling F（Task 3 的接口，和计划原文略有出入，后续任务按这个来）**：
  - `DeskLedger.add(entry: DeskTaskEntry)`（entry 含 taskId、title、words、at）
  - `DeskToolExecutor` 构造多两个参数：`conversationId: String`、`onDispatched: (DeskTaskEntry) -> Unit = {}`。**Task 4/6 要把 `onDispatched` 接到 `FrontDesk.track`。**
  - `DISPATCH_DEDUPE_MS = 30_000L`
  - 工具结果：`started{task_id}` / `queued{task_id, position}` / `failed{reason}` / `ok` / `not_accepted` / `rejected{reason}` / `unknown_task` / `already_dispatched{task_id}` / `unavailable` / `unsupported`；查询成功为 `{"status":"ok","text":…}`；`check_tasks` 的 state 取值多一个 `unknown`。
- 实际 Task 3 的接口以**提交后的代码**为准，派 Task 4 前先看一眼 `core/src/main/kotlin/com/wangzan101/mika/core/frontdesk/` 下的真实签名。
- `classify` 不产生 `Queued`：排队由派活结果直接告诉模型，排队任务开跑时 `Held(queued)→Running` 报 `Started`，任务卡上的排位来自 `port.queuePosition`。

## 4. 剩下的顺序

`3（重新实现 + 审查）→ 4 → 5 → 6 → 7 → 8 → 10 → 9 → 11 → 全分支终审`

- **Task 9（聊天页）**：按原型做，见 Ruling C。
- **Task 11（真机）**：要打 perf 包、装到测试机（安装要在手机上点一下）、用 `LiveRunTest` 自己跑场景。需要用户在手机旁边；用户不在就把能自动跑的跑完，真机项写进验收文档标「待真机」。
- **全分支终审**：所有任务完成后，按技能的 `references/final-review.md` 做，用最强的模型。账本里所有 `minor (deferred)` 交给终审分拣。Task 1 留下的「seed 可能出现连续同角色消息」到 Task 8/9 接 seed 时要留意。
- 做完以后用 `superpowers:finishing-a-development-branch` 问用户怎么合，**不要自己合到 main**。

## 5. 其他注意

- 主仓库 `/Users/bolyn/coding-projects/Mika` 里还有这三份文档的未跟踪副本（`docs/superpowers/{specs,plans,prototypes}/2026-11-23-front-desk*`），是建 worktree 之前留下的。正式版本在分支上，主仓库里的副本等合并时再处理，现在别动。
- 项目规矩见仓库根目录的 `CLAUDE.md` 和用户记忆目录 `/Users/bolyn/.claude/projects/-Users-bolyn-coding-projects-Mika/memory/MEMORY.md`（通用安卓、不加小众服务商、按根因修、新界面先原型等）。
- 每做完一个任务就往账本追加一行 `Task N: complete (commits a..b, review clean)`，小问题记成 `Task N: minor (deferred): …`。
