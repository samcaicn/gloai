# do_each 采用率:同形状循环提醒 + few-shot + 守卫死代码清理

日期:2026-11-21
状态:待批准

## 意图与为什么

成本结构实测:钱在「轮数 × 历史长度 + 看屏」,轮数是最大杠杆。do_each(对列表每条目跑同一段步骤,N 轮压成 1 轮)是这根杠杆目前唯一的现成抓手,但真机采用率 0/3——该用的场景模型还是逐条 observe→tap→back。

根因分析(2026-11-21 会话,证据在代码里):

1. **中途提醒通路是死代码**:`OperationGuards.arrived()` / `cycle()` 自第一个公开提交(093965fcd)起零调用,`trail` / `visits` 从未写入。唯一会在模型逐条干活时建议 do_each 的文案(`guard.same_state`,feedback/tools.md)因此永远不会发出。三个配套测试名(如 `returningToTheSameScreenWithoutAnythingNewNudgesAtThree`)声称触发,断言却是 `isEmpty()`。
2. **决策动力学不利于自发采用**:逐轮决策下点第一条是局部最优;do_each 要求一次性交出识别规则 + 多步 body + 信任没用过的引擎;手动做 1~2 条后上下文里已立住 observe→tap→back 模式。
3. **主模型决策窗口窄**:`ModelRouter` 把 step>1 的简单步派给轻量模型,弱模型更构造不出合法 composite 调用。

## 目标

- 逐条干活(开详情 → 动作 → 返回)重复 ≥3 次时,模型收到一次明确的 do_each 建议——机制级投递,不靠模型自发想起。
- do_each 工具说明带一个有真实感的调用示例,降低首次构造门槛。
- same_state / state_cycle 死代码复活(而非删除),测试名与断言恢复一致。

## 非目标

- 不改 do_each 引擎本体(EachRunner / EachCursor / ScrollCursor)。
- 不改轻量模型路由表(DoEach 保持非 main-only——轻量的 do_each 调用不挡回)。
- 不做自动触发:建议权在模型,引擎不替它把逐条操作改成 do_each。
- 不追求 100% 采用率;提醒是 nudge,模型有权继续逐条。

## 反目标

- 提醒滥发:正常双屏来回(比较两个页面)、刷信息流(observe→scroll)不得触发;每个任务最多提醒一次。
- 提示词膨胀:刚做完提示词瘦身,few-shot 增量必须小(目标 <200 token)。
- 任何 App 特例:识别只看动词序列形状和界面状态哈希。
- 再留「名不副实」的测试。

## 约束

- 前缀缓存:提醒只作为轮间新消息追加,不改任何已发消息(与所有 notice 同)。
- 通用性:面向 AOSP 通用行为,按能力检测,不做品牌特例。
- 修类别不修个案:threeStateLoopIsCaught 等三个测试一起恢复真实语义,不是只改断言过绿。

## 方案

### A. 同形状循环识别 → do_each 建议(主修复)

新类 `EachLoopDetector`(core/agent),纯状态机,每任务一个实例:

- **输入**:AgentLoop 每轮结束喂 `(verb, ok)`(挂在现有 `track.after(...)` 附近)。
- **计数的轮**:一轮一个屏上动词。出现 do_steps 批、do_each、phone_run、复合工具、非屏动词(沙箱/记忆/追问等)时:重置序列、本轮不计——它们本来就是替代方案或打断。
- **识别**:维护最近动词序列,检测长度 2–5 的重复 motif:
  - 完整重复 ≥3 次(最近 3×L 个动词呈 L 周期,L∈[2,5])
  - motif 恰含一个 `Back` 且至少一个 `Tap`——「开详情 → 动作 → 返回」的签名
  - `ok=false` 的轮打断连续计数
  - 本任务模型已用过 do_each 则不触发
- **误报防护**:不含 `Back` 的重复(observe→scroll 刷信息流)永不触发;双屏对比(A B A B)的 motif 不含 Back 也不触发。
- **触发后**:每任务一次。产出提醒,AgentLoop 在轮末以 `ctx.notice(source, text)` 追加进历史(与 `TurnPolicy.unconfirmed` 同一投递机制,只增不改)。
- **文案**:新 feedback 键 `loop.repeating_items`(prompts/feedback/loop.md):说清检测到的模式(「最近 N 步在重复 打开→操作→返回」),建议把剩余条目放进一次 do_each,给 item/body 最小写法提示(指向工具说明,不重复全文)。
- **顺带**:注入建议时 `track.markRecovery()`——下一步强制回主模型,让强模型做这次批量决策(对应根因 3,复用现有「恢复步」语义,零新机制)。

### B. few-shot(工具说明内)

`prompts/tools/do_each.md`:把现有的一行示例换成一个 4 步真实 body(打开 → 等详情加载 → 动作 → 返回),场景用「列表逐条操作」通用写法。只在工具 description 这一处加,不动 capabilities/batch.md,不两处重复。增量控制在 200 token 内。

### C. same_state / state_cycle 复活

选择复活而非删除:两条通道分工——A 看动词形状,精准负责列表逐条场景(详情页是否同构都覆盖);C 看界面状态哈希,负责「不规则反复回访」(A→B→C→A→D→A 式迷路)与周期性兜圈。

- **`frame()` 接线**:新帧 hash 与上一帧不同(且 frameId 更换)时:
  - `trail` 追加该 hash(相邻不同才追加,封顶 TRAIL_KEEP=64)
  - hash 已在 `seenStates`(回到旧状态)→ `out += arrived(hash)`
  - 随后 `out += cycle()`
- **闸门保持原样(builder check 裁决)**:曾考虑放开 `arrived()` 的 cycle 闸门让列表场景也走 same_state,否决——`cycle()` 对单个来回(rounds=1)就非空,列表场景每次回到列表屏都被抑制;若把闸门放宽到「rounds 达提醒级才抑制」,3-周期循环会连发 3 条 same_state 才轮到 state_cycle,违背提醒滥发反目标。保持「正在兜圈时由 cycle 说」:same_state 覆盖不规则回访,state_cycle 覆盖周期循环,A 通道覆盖列表。
- **cycleLadder 首级 2→3**:A B A B(2 圈)是合法的双屏对比操作,首级 2 太敏感;提到 3(A B A B A B 三个来回才提醒),4 与 stop 级不变。sameStateLadder [3,6,stop] 不变。
- **测试恢复真实语义**:
  - `returningToTheSameScreenWithoutAnythingNewNudgesAtThree`:用不规则回访序列(每次经不同的新页面回到同一屏),断言第 3 次到达时 same_state Warn 且提醒带 do_each 建议。
  - `threeStateLoopIsCaught`:1→2→3 转满三圈,断言 state_cycle Warn(且全程无 same_state——周期循环由 cycle 通道单独说)。
  - `cycleStopFollowsTheStepBudget`:budget 30 → stop 级 max(6, 30/5)=6 圈,断言 stop 在对应帧数出现。
  - `backAndForthBetweenTwoStatesDoesNotStopProgress`:3 帧 2 圈 < 3,首级提高后断言 empty 依然成立,原样保留、名副其实。

### 不做的一个选项(记录备查)

- 「放宽 arrived() 的 cycle 闸门让列表场景走 same_state」:列表每次回屏都被 rounds=1 的半个周期抑制,放开则 3-周期循环连发 3 条 same_state,违背滥发反目标;列表场景由 A 通道负责,弃。
- 「按旧注释语义(见过新状态就重置 visits)」:会让 same_state 几乎永不触发,复活即失去意义,弃。

## 验证

- JVM:`:core:test` 全绿——守卫测试重写 + EachLoopDetector 单测:
  - 典型列表模式([Observe,Tap,Back]×3、[Tap,Back]×3)触发一次
  - observe→scroll 刷信息流不触发;双屏对比不触发
  - 失败轮打断;触发一次后不重复;模型用过 do_each 后不触发
  - do_steps/复合工具出现时不计不误触
- 全量回归:`./gradlew :app:testDebugUnitTest`(注意:裸 `:app:test` 会 OOM)。
- 真机(需用户在场):LiveRun 3 个列表类任务复测——记录里出现 `loop.repeating_items` 提醒;采用率目标 ≥1/3(提醒后模型发起 do_each 调用)。真机结果回填本文档。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 提醒时机偏晚(第 3 次重复才提醒) | 可接受:前 2~3 条本是模型理解列表的必要阶段;过早提醒反而打断正常探索 |
| 轻量模型收到提醒仍构造不出合法 do_each | 注入建议时 markRecovery,下一步强制主模型 |
| few-shot 增 token | 只动工具 description 一处,200 token 上限 |
| 复活 cycle 后对正常双屏操作误报 | 首级 2→3;warn 级只是提醒不叫停 |
| 提醒进历史后模型仍无视 | nudge 定位即如此;真机采用率复测会给出真实数据,再决定是否升级手段 |
