# 调试流程（DEBUG）

本文档描述 trace-auto 技能的调试能力：断点、单步、变量监视、trace 记录与回放。所有调试能力都建在 `cap.control` / `cap.flowchart` / `debug` 导出之上，trace-auto 不自己实现控制信号。

## 1. 断点 — add_breakpoint / remove_breakpoint / clear_breakpoints

`cap.control` 维护进程内单例 `_controlState`，含 `breakpoints: Set<nodeId>`。trace-auto 的流程图节点 id（见 `flowchart.json`）可作为断点目标：

| 节点 id | 类型 | 适合设断点的场景 |
|---------|------|------------------|
| `ensure` | process | 验证 CDP 连接前停下，检查 IDE 是否启动 |
| `read` | process | 读取页面状态前停下，检查 DOM |
| `running?` | decision | 判断 AI 运行态前停下 |
| `act` | process | 执行动作前停下，检查将要点击/发送的内容 |
| `errors?` | decision | 错误判断前停下 |
| `stuck?` | decision | 卡住判断前停下 |
| `prompt` | io | 弹窗前停下，检查将要问用户的问题 |
| `loop` | process | 生成跟进指令前停下 |

前端通过 handler 暴露的标准动作：

```json
{ "action": "add_breakpoint",    "nodeId": "act" }
{ "action": "remove_breakpoint", "nodeId": "act" }
{ "action": "clear_breakpoints" }
```

**断点命中机制**：trace-auto 的 `execute` 循环在每个关键节点入口调 `cap.control.check(nodeId)`。若 `nodeId` 在断点集合里，`cap.control.check` 会自动把 `_controlState.paused = true`，进入阻塞等待循环，直到 `resume` / `stepOnce` / `stop` 唤醒。

注意：循环顶部 `cap.control.check()`（无 nodeId）只检查停止信号，不触发断点暂停，避免每轮都卡住。

## 2. 单步 — step_once / pause / resume / stop

| 动作 | handler 调用 | 行为 |
|------|-------------|------|
| 单步 | `cap.control.stepOnce()` | 执行一个节点后保持暂停 |
| 暂停 | `cap.control.pause()`    | 设置 `paused = true`，循环顶部 `check()` 阻塞 |
| 继续 | `cap.control.resume()`   | 清除 `paused` 与 `stepOnce`，唤醒等待 |
| 停止 | `cap.control.stop()`     | 设置 `stopRequested = true`，execute 返回 `status: 'stopped'` |

前端通过 handler 暴露的标准动作：

```json
{ "action": "step_once" }
{ "action": "pause" }
{ "action": "resume" }
{ "action": "stop" }
```

**`cap.control.check(nodeId)` 是 execute 循环的协作点**：

```js
if (!(await cap.control.check('read'))) return _summarize(round, 'stopped')
```

返回 `false` 表示已请求停止，trace-auto 立即退出 execute 循环并返回 `{ status: 'stopped', trace }`。

## 3. 变量监视 — debug.getVariableScope

`index.js` 导出的 `debug` 对象提供调试钩子（参考 Playwright Trace Viewer + Robot Framework Language Server）：

```js
export const debug = {
  getVariableScope: (ctx) => ({ locals: ctx?.locals || {}, flowchart: cap.flowchart.get() || FLOWCHART }),
  onBreakpoint: async (ctx, node) => { cap.runtime.log('debug', 'breakpoint hit: ' + node.id) },
}
```

调试器可以：
- 调 `debug.getVariableScope(ctx)` 拿当前 locals + flowchart 快照
- 在断点命中时收到 `onBreakpoint(ctx, node)` 回调，可在此处展示变量面板（如当前轮次、卡住次数、最后 AI 回复）

## 4. trace 记录格式 — cap.flowchart.trace

trace-auto 的 `execute` 用 `cap.flowchart.pushTrace(nodeId, status, note)` 记录每个节点。每条 trace entry 字段：

| 字段 | 类型 | trace-auto 示例 |
|------|------|------------------|
| `runId` | string | 本次执行的 uuid（由 setCurrent 生成） |
| `nodeId` | string | `start` / `ensure` / `read` / `running?` / `wait` / `act` / `errors?` / `stuck?` / `prompt` / `loop` / `end` |
| `status` | enum | `ok` / `fail` / `stopped` |
| `ts` | number | 毫秒时间戳 |
| `iso` | string | ISO 字符串时间戳 |
| `ms` | number | 节点耗时（pushTrace 模式下为 0） |
| `note` | string | 人读备注，如 `u=3 a=5 running=true` / `yes → wait` / `generate: 请帮我添加单元测试` |

典型 trace 序列（一轮循环）：

```
start    ok   (启动)
ensure   ok   (CDP 已连接)
read     ok   u=3 a=5 running=true
running? ok   yes → wait
wait     ok   等待 AI 空闲
read     ok   u=3 a=5 running=false
running? ok   no → act
act      ok   send input box
...
end      ok   共 12 轮
```

## 5. trace 序列化与导出 — cap.flowchart.serialize / exportZip

```js
// 序列化为可回放 JSON（参考 Playwright trace.json schema）
const data = cap.flowchart.serialize()
// 返回结构：
// {
//   schema: 'https://schema.tupautochrome.io/trace/v1',
//   runId, skillId: 'trace-auto-flowchart', skillVersion: '6.0.0',
//   flowchart, startedAt, endedAt,
//   events: [{ t: 0, ...traceEntry }, ...]
// }

// 导出为 zip（实际生产由 Rust 侧打包 zip + 截图 + DOM 快照）
const fname = await cap.flowchart.exportZip()
```

## 6. 读取 trace 回放 — 前端 FlowchartView 的 traceMap

前端 `src/AutomationPage.jsx` 的 `FlowchartView` 组件维护 `traceMap`：

```js
const traceMap = new Map()
;(trace || []).forEach((t, i) => {
  if (!traceMap.has(t.nodeId)) traceMap.set(t.nodeId, { ...t, idx: i })
})
```

渲染 trace-auto 的 11 个节点时查 `traceMap.get(n.id)`，命中则：
- 给节点加 `flowchart-hit` class 高亮
- 显示 `status · #idx` 徽标（如 `act · #5`、`running? · #4`）
- decision 节点显示分支选择（`yes → wait` / `no → act`）

停止后前端调 `get_flowchart` + `get_trace` 把数据喂给 `FlowchartView`，用户就能看到 Trae 自动化的完整执行轨迹。

## 调试速查

| 场景 | 动作序列 |
|------|----------|
| 设断点停在 act 节点 | `add_breakpoint act` → `execute` → 命中后自动 pause |
| 单步执行一轮循环 | `pause` → 反复 `step_once`（read → running? → act → ... → loop） |
| 继续到下一个断点 | `resume` |
| 中止本次执行 | `stop`（execute 返回 `status: 'stopped'` + trace） |
| 查看本次 trace | `get_trace` |
| 导出 trace 文件 | `cap.flowchart.exportZip()` |
| 检查 AI 是否卡住 | 看 trace 里 `stuck?` 节点的 note 是否 `yes → prompt` |
| 检查错误处理 | 看 trace 里 `errors?` 节点的 note 是否 `yes → prompt` |
