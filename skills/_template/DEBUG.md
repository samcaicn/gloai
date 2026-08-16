# 调试流程（DEBUG）

本文档描述标准技能的调试能力：断点、单步、变量监视、trace 记录与回放。所有调试能力都建在 `cap.control` / `cap.flowchart` / `debug` 导出之上，不需要技能自己实现控制信号。

## 1. 断点 — add_breakpoint / remove_breakpoint / clear_breakpoints

`cap.control` 维护一个进程内单例 `_controlState`，含 `breakpoints: Set<nodeId>`。

| 动作 | 调用 | 行为 |
|------|------|------|
| 添加断点 | `cap.control.addBreakpoint(nodeId)` | 把 nodeId 加入断点集合 |
| 移除断点 | `cap.control.removeBreakpoint(nodeId)` | 从断点集合删除 |
| 清空断点 | `cap.control.clearBreakpoints()` | 清空整个集合 |
| 查询 | `cap.control.hasBreakpoint(nodeId)` | 返回 boolean |

前端通过 handler 暴露的标准动作：

```json
{ "action": "add_breakpoint",    "nodeId": "act" }
{ "action": "remove_breakpoint", "nodeId": "act" }
{ "action": "clear_breakpoints" }
```

**断点命中机制**：`cap.control.check(nodeId)` 在节点入口被调用时，若 `nodeId` 在断点集合里，会自动把 `_controlState.paused = true`，然后进入阻塞等待循环，直到 `resume` / `stepOnce` / `stop` 唤醒。

## 2. 单步 — step_once / pause / resume / stop

| 动作 | 调用 | 行为 |
|------|------|------|
| 单步 | `cap.control.stepOnce()` | 执行一个节点后保持暂停 |
| 暂停 | `cap.control.pause()`    | 设置 `paused = true` |
| 继续 | `cap.control.resume()`   | 清除 `paused` 与 `stepOnce`，唤醒等待 |
| 停止 | `cap.control.stop()`     | 设置 `stopRequested = true`，唤醒等待 |
| 重置 | `cap.control.reset()`    | 开始新一次执行前调用，清空所有信号与断点 |

前端通过 handler 暴露的标准动作：

```json
{ "action": "step_once" }
{ "action": "pause" }
{ "action": "resume" }
{ "action": "stop" }
```

**`cap.control.check(nodeId)` 是 execute 循环的协作点**：

```js
if (!(await cap.control.check(nodeId))) {
  // stopRequested = true，应当退出循环
  return _summarize(round, 'stopped')
}
```

返回 `false` 表示已请求停止，技能应立即退出 execute 循环。

## 3. 变量监视 — debug.getVariableScope

`index.js` 导出的 `debug` 对象提供调试钩子（参考 Playwright Trace Viewer + Robot Framework Language Server）：

```js
export const debug = {
  // 列出可监视的变量
  getVariableScope: (ctx) => ({ locals: ctx?.locals || {}, flowchart: cap.flowchart.get() }),
  // 命中断点时调用
  onBreakpoint: async (ctx, node) => { cap.runtime.log('debug', 'breakpoint hit: ' + node.id) },
}
```

调试器（前端或外部 IDE）可以：
- 调 `debug.getVariableScope(ctx)` 拿当前 locals + flowchart 快照
- 在断点命中时收到 `onBreakpoint(ctx, node)` 回调，可在此处展示变量面板

## 4. trace 记录格式 — cap.flowchart.trace

每条 trace entry 的字段（由 `cap.flowchart.pushTrace` 写入）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `runId` | string | 本次执行的 uuid，由 `setCurrent` 生成 |
| `nodeId` | string | 流程图节点 id |
| `status` | enum | `running` / `ok` / `fail` / `skipped` / `stopped` / `breakpoint` |
| `ts` | number | 毫秒时间戳 |
| `iso` | string | ISO 字符串时间戳 |
| `ms` | number | 节点耗时（由 `endNode` 填充） |
| `note` | string | 人读备注 |
| `variables` | object | 节点变量快照（可选） |
| `cap_calls` | array | 节点内 cap 调用记录（可选） |

`beginNode` 推一条 `status: 'running'`，`endNode` 把它更新为 `ok`/`fail`/`stopped` 并填 `ms`。所有 trace 同时被 `cap.storage.append('trace_flowchart_trace', entry)` 持久化。

## 5. trace 序列化与导出 — cap.flowchart.serialize / exportZip

```js
// 序列化为可回放 JSON（参考 Playwright trace.json schema）
const data = cap.flowchart.serialize()
// 返回结构：
// {
//   schema: 'https://schema.tupautochrome.io/trace/v1',
//   runId, skillId, skillVersion,
//   flowchart, startedAt, endedAt,
//   events: [{ t: 0, ...traceEntry }, ...]
// }

// 导出为 zip（实际生产由 Rust 侧打包 zip + 截图 + DOM 快照）
const fname = await cap.flowchart.exportZip()
// 返回保存的文件名，存到 cap.storage('trace_export_<fname>')
```

## 6. 读取 trace 回放 — 前端 FlowchartView 的 traceMap

前端 `src/AutomationPage.jsx` 的 `FlowchartView` 组件维护 `traceMap`：

```js
const traceMap = new Map()
;(trace || []).forEach((t, i) => {
  if (!traceMap.has(t.nodeId)) traceMap.set(t.nodeId, { ...t, idx: i })
})
```

渲染节点时查 `traceMap.get(n.id)`，命中则：
- 给节点加 `flowchart-hit` class 高亮
- 显示 `status · #idx` 徽标（如 `ok · #3`）

停止后前端调 `get_flowchart` + `get_trace` 把数据喂给 `FlowchartView`，用户就能看到完整执行轨迹。

## 调试速查

| 场景 | 动作序列 |
|------|----------|
| 设断点停在某节点 | `add_breakpoint(nodeId)` → `execute` → 命中后自动 pause |
| 单步执行 | `pause` → 反复 `step_once` |
| 继续到下一个断点 | `resume` |
| 中止本次执行 | `stop`（execute 返回 `status: 'stopped'`） |
| 查看本次 trace | `get_trace` |
| 导出 trace 文件 | `cap.flowchart.exportZip()` |
