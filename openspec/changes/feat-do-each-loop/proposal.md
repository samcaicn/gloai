# 跨屏循环原语 `do_each`：重复任务一次思考、多次执行

## Why

真实运行记录里，「同一动作对多个条目重复做」的任务，agent 是逐条「观察→动手→观察」地做，每轮都重发整段历史 + 一张新截图，成本按 O(N²) 累积。例证：

- 看广告领福利的真实记录（`20261114-223716-79pbvk`）：35 轮模型调用只做了「看 3 个广告、各领一次」；`wait`+`observe` 轮询 14 次、关广告 `tap` 12 次、`do_steps`/`phone_run` 0 次；输入 token 从 15K 涨到 148K。
- 投递打招呼类任务「点开→打招呼→返回 ×N」同理：跨屏循环，手机上无工具可表达。

根因两层（详见 design）：

1. **能力层**：手机侧没有「循环」原语——`do_steps` 只批单屏线性，`phone_run` 跨屏但线性，只有网页 `web_script` 能循环（但要写整个 JS 程序）。「跨屏循环」这一形状，手机无工具可填。
2. **决策层**：没有机制让模型「认出重复、commit 一个循环」；`batch.md` 只教填表单，`effect`/`guard` 词汇把「重复」读作「卡死」、把「验证」读作「美德」。

## What Changes

- 新增跨屏循环原语 **`do_each`**，一个工具同时服务手机与网页：声明式表达「对若干条目，逐个执行同一段步骤」，一次思考、引擎多次执行。
- 循环结构（找下一个 / 跳过已做 / 执行循环体 / 终止 / 结果汇总）共用；唯一按屏面分派的是「一条 item 怎么认」（手机 a11y vs 网页 DOM）与动作执行器（已由 ActionExecutor 抽象）。
- 决策层**先只落措辞、不动运行时**：`batch.md` 增加「重复 → do_each」的教导与真实例子；`guard.same_state` 的豁免从「carry on」升级为「剩下 N 个同类条目，用 do_each 一次做完」；新增 `do_each.md`。
- 运行时重复识别 / 计划阶段作为后续方向，本设计只在引擎里留汇报形态（紧凑摘要），不实现识别逻辑。

## Capabilities

### New Capabilities

- `do-each`: 跨屏循环原语——item 定位（按屏面分派）、循环体步骤（复用 do_steps 步骤语义）、跳过/终止条件、逐轮 guard、紧凑结果摘要。

### Modified Capabilities

- `batch`（`batch.md` 措辞）、`feedback`（`guard.same_state` 措辞）——仅文字。

## Impact

- core：新增循环执行器（与 `BatchRunner` 同族）——item 定位器接口 + 循环控制 + 结果摘要；`Verb.DoEach`、`AgentTool` 注册 `do_each`、`ToolSpec` 生成。
- 屏面接线：手机 item 定位走 a11y 树（role/text/resourceId），网页走 DOM（cssSelector/role）；动作走现有执行与录制路径（send/post/pay 等受控动作照常过闸）。
- prompts：新增 `tools/do_each.md`；改 `capabilities/batch.md`、`feedback/loop.md`（`guard.same_state`）。
