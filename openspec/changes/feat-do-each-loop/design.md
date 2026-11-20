# 设计：跨屏循环原语 `do_each`

## Context

现状批量能力矩阵（explore 结论）：

| 工具 | 屏面 | 形状 | 能循环？ |
|---|---|---|---|
| `do_steps` | 单屏 | 线性批（带 expect 条件） | 否（明文 same screen） |
| `phone_run` | 跨屏 | 线性序列（30 命令，逐条重找目标） | 否 |
| `web_script` | 网页 | 一整个 JS 程序 | 是（门槛高：写程序） |
| `phone_wait` / `web_wait_for` | 各屏 | 条件等待 | —（能省轮询，但被冷落） |

真实记录证据（`20261114-223716-79pbvk`「看广告领福利」）：35 轮 = 3 个广告 ×（等待轮询 ~5 + 关 ×3 + 领 ×1）；工具分布 `wait` 9、`phone_observe` 8、`phone_tap` 12、`phone_wait` 2、`do_steps` 0、`phone_run` 0；输入 token 15K→148K（历史 O(N²)，95% 缓存）。

要补的洞：手机侧没有「跨屏循环」这一形状；同时决策层没有任何「重复→批量」的教导，反而被 `effect`/`guard` 反向拉。

## Goals / Non-Goals

**Goals：**

- 一个 `do_each` 原语，表面无关，表达「打开→操作→返回 ×N」这类跨屏循环。
- 声明式、低门槛——和 `do_steps` 一样是步骤数组，不是 JS 程序。
- 循环体复用 `do_steps` 的步骤语义（expect 条件等待、本地校验、失败即停、受控动作过闸）。
- 决策层：措辞层面教会「重复→do_each」，先不动运行时。

**Non-Goals：**

- 不做运行时重复识别 / 计划阶段（后续方向；本设计只留摘要形态，不加识别逻辑）。
- 不改 `web_script`（保留为 do_each 覆盖不了的任意程序的逃逸口）。
- 不做并行子代理版「一条一代理」（那是子代理并行的问题，正交）。

## Decisions

### D1：循环结构表面无关，只有 item 定位与动作执行分派

`do_each` 是一个工具（`Verb.DoEach`），同时挂到手机与网页工具组。循环引擎（找下一个 → 跳过已做 → 执行循环体 → 判断终止 → 汇总）完全共享；两处按屏面分派：

- **item 定位**：手机在 a11y 树上匹配（role/text/resourceId + 可选容器），网页在 DOM 上匹配（cssSelector/role/text）。
- **动作执行**：走现有 `ActionExecutor`（`phone_tap`/`web_tap` 已共用执行与录制路径）。

`mixed_surface` 判定沿用 `do_steps` 的：一次 `do_each` 里的 item 与 body 必须同屏面，混屏直接拒绝。

### D2：循环头 = body 里显式 `@item`，不是隐式「打开」

循环体是**完整的单条目步骤序列**，其中第一步通常「打开当前条目」，用特殊引用 `@item` 指代本轮定位到的条目；最后一步「返回列表」由模型自己在 body 里写（通常是 `back`）。这样比「引擎隐式打开+隐式返回」更灵活：

- 「点开→打招呼→返回」：`body = [ tap @item, tap "打招呼", back ]`。
- 「就地循环」（滚动采集、批量已读，不离开列表）：`body` 里不用 `@item`、也不 `back`，同样表达。

**被否的替代**：引擎隐式 open/return——少了 `@item` 那步的模板，但把「有些循环不打开/不返回」的变体堵死，还要额外一个 `navigate` 字段去开关，反而更啰嗦。

### D3：参数形状（草案，原型再收）

```
do_each(
  item: ItemPattern,        // {role?, text?, resourceId?, cssSelector?} 按屏面取字段
  body: [Step],             // 每条的步骤；可用 @item
  until?: Until,            // {count: N} | {text: ...} | 默认「无更多条目且 reveal 到底」
  skip?: Skip,              // {text_matches: ...} 跳过命中的条目
  reveal?: "scroll"|"none", // 当前屏找不到未做的条目时，滚动找更多（列表任务默认 scroll）
  max_items?: N,            // 安全上限（缺省取自每调用资源上限）
)
```

`Step` 复用 `do_steps`/`phone_run` 的步骤形状（tap/type/scroll/swipe/key/back/wait/assert/observe），并带 `expect`/`expect_gone`/`wait_stable` 条件等待——这样「等广告播完出领取按钮」在 body 里是一步条件等待，而不是外部 14 次轮询。

### D4：逐轮 guard 与紧凑摘要

每轮循环体走正常执行与录制路径，沿用 `BatchRunner` 的停止规则：body 步失败、app 切换、需要视觉复核、expect 未满足、until 命中——都停，并记在哪一条哪一步。**关键**：结束后给模型的是一份**紧凑摘要**，不是逐步截图：

```
{ done: 3, failed: 0, skipped: 0, stopped_at: null,
  per_item: [ {i:1, ok, note:"已打招呼"}, {i:2, ok, ...}, {i:3, ok} ] }
```

模型一次调用读回 N 条的结局，N 轮压成 1 轮——这正是「思考一次、执行多次」落在 token 上的回报。

### D5：与既有工具的边界（教给模型的一句话判据）

| 场景 | 用 |
|---|---|
| 一屏内确定几步（填一个表单） | `do_steps` |
| 跨几条屏的固定路线，走一遍 | `phone_run` |
| **同一动作对多个条目做（打开→操作→返回）** | **`do_each`** |
| 任意程序 / 数据变换 / 多标签（仅网页） | `web_script`（逃逸口） |

`do_each` 不取代 `do_steps`/`phone_run`，它们是「线性」；`do_each` 是「循环」。`web_script` 长期保留，但 `do_each` 覆盖其九成的「逐条点」用法后，它退化成很少用的任意程序口。

### D6：决策层只落措辞（不动运行时）

1. `capabilities/batch.md` 追加：「同一动作要对多个条目做时（逐个点开→操作→返回，如逐条打招呼、逐条领福利），用 `do_each` 一次做完，别一条一回合。」
2. `feedback/loop.md` 的 `guard.same_state` 豁免，从「If you are deliberately going through items one by one, carry on.」改为「…going through items one by one, **put the remaining items in one do_each call instead of doing them one at a time**.」
3. 新增 `tools/do_each.md`，带一个投递类的真实例子（点开→打招呼→返回 ×N）。
4. 不动运行时：不新增「连续 3 次同动作」检测，不新增计划阶段。

## Risks / Trade-offs

- **「找下一个、跳过已做」是引擎最难点**（见 Q1）：ref 每次变，得靠稳定键（text/resourceId/位置）去重，同名条目有歧义。先原型跑通这一点再定稿。
- **失败语义**：投递里一条打招呼失败，是停还是跳过继续？默认「停并汇报」（安全），`on_error: skip` 作为可选（见 Q4）。
- **「返回列表」不总是 back**：body 最后一步由模型写，引擎只负责「返回后重新定位 item，定位不到就停」，不替模型猜返回方式。
- **把「重复」从卡死信号改成批量信号，可能放走真卡死**：guard 的豁免本来就是「逐条过」时才放行，改成「用 do_each」后，若模型误判成循环而实际是绕圈，`do_each` 的 until/max_items 兜底限住次数，不会比现在更坏。

## Migration Plan

纯新增工具 + 两处提示词文字改动，无存量数据迁移。回滚 = 撤销提交；模型没被教之前不会调 `do_each`，行为与今天一致。

## Open Questions

- **Q1 稳定键（已原型验证 ✅）**：item 去重的键是什么？同名条目怎么区分？原型 `core/.../agent/EachCursor.kt`（+ `EachCursorTest`）跑通：**`role|name`，同名冲突退 `role|name#k`（容器内序号）**。关键发现：`resourceId` 在真实 a11y 树里存在但**列表条目同构**——N 条卡片同一 widget 类型、resourceId 一模一样，不能区分第 1 条和第 2 条，故否决「优先 resourceId」。ref 每次快照重排、不能用。唯一名条目的键与位置无关（列表重排也不变）；真同名才靠位置兜底。剩余边界：列表滚动 / 条目被移除时位置键会失效——归入 Q2（reveal）。
- **Q2 reveal 语义（已原型验证 ✅）**：`ScrollCursor` 跑通——停止条件 = 「刚滚过一次、且这次读屏没出现任何新 contentKey」。contentKey（`role|name`）跨滚动稳定：滚出屏的条目键不变、不重做；`ordinal`（同名序号）位置依赖、滚后失效、不能当跨屏键。边界：同名条目收敛成「做一次」——元素信息不足以跨滚动区分两条同名条目，根治靠更细的 item 匹配让 contentKey 唯一，不是靠键。
- **Q3 就地循环（已原型验证 ✅）**：不打开条目的循环（滚动采集、批量已读/领取）**不需要新机制**——执行器只关心「这条做没做成」，就地移除时列表上移、但 contentKey 不变，游标照常去重。v1 直接支持，`body` 不用 `@item`/`back` 即可表达（`EachRunnerTest.inPlaceRemovalDedupsWhenItemsShiftUp`）。
- **Q4 on_error（已原型验证 ✅）**：`stop`（安全默认，失败那条不记 done、留给上层决定重试，后面的不再做）与 `skip`（失败也记 done、继续做后面，投递类「一条失败不挡一屏」）都要 v1 暴露；`EachRunner.OnError` 跑通两者。摘要含 `done/failed/stoppedAt/perItem`。
- **Q5 与 do_steps 的关系**：是否最终把 do_steps 收敛成 `do_each` 的 `count=1` 特例，还是长期并存？本设计先并存，观察使用后再收敛。
