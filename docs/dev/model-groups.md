# 模型组

模型组是「用途 → 一个或多个具体模型」之上的一层可选配置。一个组里的成员按策略选一个来回答；一次调用失败时，可以换组里的下一个成员继续，不用让整个任务失败。

单模型配置保持原样。组只用在你想给主模型、看图模型或轻量模型配备用模型的时候。

## 配置

- 设置 › 模型 › 模型组：新建、编辑、删除。
- 每个组有名称、策略、成员列表；成员可以启停、上下调顺序。
- 组可以被三个用途中的任意一个选中，和具体模型并列显示在用途选择页。
- 主模型组要求所有启用成员都支持工具调用；看图组要求至少一个成员能看图。轻量组不强制，不支持工具的成员在需要工具时会跳过。

存储仍在 `files/settings/providers.json`：

```json
{
  "groups": [
    {
      "id": "g-...",
      "name": "主力兜底",
      "strategy": "failover",
      "sticky": true,
      "members": [
        {"config": "c-...", "priority": 0, "weight": 1},
        {"config": "c-...", "priority": 1, "weight": 1}
      ]
    }
  ],
  "uses": {
    "Main": {"use": "group", "group": "g-..."}
  }
}
```

组成员引用 `providers` 里的配置 id。配置因此多了一种「被组引用」的生命周期，`ProviderStore.prune` 会把组成员算进「有人用」。

## 策略

- `failover`（默认）：按 `priority` 升序，前一个失败才用下一个。
- `weighted`：新对话按权重选一个成员，之后粘住；不是按每个请求轮询。
- `least-latency`：按近期成功率和延迟排序，样本少的成员保留探索。
- `cost-first`：按成员的费用档位；目前还没有填费用档位的界面，先按优先级。

`sticky` 默认开：同一对话固定在一个成员上，保护前缀缓存。只有失败才会换。

## 失败转移

| 错误 | 同成员重试 | 换成员 |
| --- | --- | --- |
| 限流、5xx、网络、流被断、代理连不上 | 组里多个候选时只试 1 次 | 是 |
| 鉴权、额度、模型不存在/不可用、不支持工具、登录失效 | 否 | 是 |
| 内容审核拒绝 | 否 | 否（不找更宽松的服务商） |
| 上下文超长 | 交给压缩流程 | 否 |
| 请求本身不对、解析失败、取消 | 否 | 否 |

换成员发生在请求开始前。流式已经吐字后要换人，会先清掉界面上的预览；半截回答不会进历史。已经执行过的工具不会因为换模型重做。

## 每成员的上下文与私有数据

`ModelCaller` 已经按模型分别保存压缩状态和用量锚点；组只是让它在一次调用里按成员选。选定成员后，压缩窗口、压缩触发点、图片上限都用这个成员自己的能力。主模型组的 `vision` 能力取排在最前的成员：主成员看不了图就不开截图，避免第一张图进入历史后把主成员永久排除；真正带图的请求仍只发给能看图的成员。

不同服务商的思考内容和工具调用私有字段不能发给别家。`ModelCaller` 记下每条 assistant 消息是谁给的，换成员时去掉别家的 `Part.Thinking` 和 `Part.ToolCall.opaque`。不经过 `ModelCaller` 的一次性调用（起标题、分类、核查、看图）走 `GroupPool.complete`，只在发生转移时统一剥掉这些字段。

## 健康与冷却

`ModelHealthRegistry` 按「服务商 + 模型」记连续失败、冷却到什么时候、近期延迟；进程内共享，重启清空。限流按 `Retry-After`（没有则 30 秒），鉴权/额度类冷却 5 分钟，其余连续失败翻倍、最多 10 分钟。冷却结束后先放一个请求进去，成功了就恢复。

冷却中的成员不参与选择。全部成员都不可用时抛 `ModelError.GroupUnavailable`，任务按模型失败结束并说明原因。

## 观测

- 用量账本不变：每个成员调用各自记在「服务商 × 模型 × 用途」下。
- 运行记录里 `ModelReply` 增加 `group`；换成员时写一条 `RunEvent.State("model_route", "组 成员A -> 成员B 原因")`。不记录消息内容。
- 组编辑页显示成员启停和策略；运行详情显示实际回答的模型。

## 不做

- 不做答案合并或投票。
- 不做跨服务商的工具 schema 转换。
- 不按请求轮询（会破坏前缀缓存）；加权是按对话分配。
- 不做并行 racing；留了 `ModelGroup.raceReadOnly` 字段，v3 再实现。

## 测试

- `core`：`ModelGroupTest`、`ModelCallerGroupTest`、`FailoverPolicyTest`、`StripForeignPrivateTest`。
- `app`：`ProviderStoreGroupTest`（存储往返、prune、退组、服务商删除、逐成员上下文）。
- 现有 `ModelRouterTest`、`AgentLoopLightTest`、`PrefixStabilityTest` 保持通过；单模型行为不变。

## 相关文件

- `core/src/main/kotlin/com/wangzan101/mika/core/model/ModelGroup.kt`：组、成员、策略、`ModelNeed`、`MemberKey`。
- `core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelPool.kt`：`ModelPool`、`SingleMemberPool`、`GroupPool`、健康/粘性、`FailoverPolicy`。
- `core/src/main/kotlin/com/wangzan101/mika/core/agent/ModelCaller.kt`：按成员路由、逐个尝试、换成员时准备历史。
- `app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt`：组存储、`RoleChoice.Group`、`modelFor` 建池、prune。
- `app/src/main/java/com/wangzan101/mika/ui/ModelGroupScreen.kt`：组列表和编辑。

## 后续（v2/v3）

本 PR 只做 v1（故障转移、粘性、健康冷却、加权/延迟的运行时基础）。下面这些**没有实现**，按 v2/v3 另开 PR：

**v2**

- 组编辑页的成员权重输入，让“加权”从现在的按对话轮换变成真正按权重分配。
- 成员级思考档位/费用档位编辑入口（字段已在，只是没有 UI）。
- 组级限额执行：`GroupLimits` 的 RPM、并发、每日 token/费用目前只存不执行。
- 用量账本增加组维度：现在只按「服务商 × 模型 × 用途」记，同一模型在组内外无法区分。
- 延迟策略细化：首字延迟、近期窗口、探索率可配、半开探测。
- 可选：健康/冷却状态落盘，重启后仍避开已知坏成员。

**v3**

- 用户手填费用档位/单价，露出 cost-first；不抓取服务商价目表。
- 并行 racing / best-of：只允许无工具、只读请求，每组显式开启，默认关；必须提示同一内容会发给多家。
- 聚合/投票：涉及工具调用时没有良定义，不建议做；若做只限无工具纯文本且默认关。
- 组导入/导出/模板：只导组结构和成员名，不含 Key；导入前展示成员和隐私提示。
- 组级费用预算：依赖费用数据。

**明确不做**：流式中途换模型、跨服务商工具 schema 自动转换、把内容审核拒绝当故障转移理由、在模型组 PR 里做对话页模型切换/思考档位/Skill/MCP 挂载（这些见后续 PR1/PR2 设计）。

恢复时先完成真机验收、上游 review 和 CI；v2 在 #288 合入并稳定后做，v3 另开设计。
