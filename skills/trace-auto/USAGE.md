# 使用流程（USAGE）

本文档描述 trace-auto 技能从「被发现」到「停止回放」的完整 5 步链路。5 步：**搜索 → 加载 → 执行 → 停止 → 回放**。trace-auto 是 `_template/` 标准模板的参考实例，所有动作都建在标准 cap 能力之上。

## 1. 搜索 — 如何被前端 AutomationPage 发现

前端 `src/AutomationPage.jsx` 是技能市场入口。用户在搜索框输入软件中/英文名（如「Trae」「Trae IDE」）后：

1. 前端调 `searchSoftwareSkills({ softwareName, softwareNameEn })`（来自 `./mcpClient`）。
2. 该调用最终落到 trace-auto handler 的 `search_software` 动作。
3. handler 内部调 `searchSoftware(params)`，优先走 `cap.skillMarket.searchBySoftware(name, nameEn)`：
   - `cap.skillMarket.searchBySoftware` 内部调 `cap.server.searchSkills(q, { softwareName, softwareNameEn })`，命中服务器侧技能市场。
   - 服务器无结果或异常时，**降级返回内置 trace-auto 自身**（`fallback: true`），保证 demo 可跑。
4. 返回 `{ ok, query, skills, executable, fallback }`：
   - `executable: true` → 前端「执行」按钮可点
   - `executable: false` → 「执行」置灰，「录制」永远可点

**trace-auto 被发现的关键**：`SKILL.md` 的 `software_names: ["Trae", "Trae IDE"]` 与用户输入匹配。

## 2. 加载 — 技能包加载

命中后，前端在「执行」点击前会先尝试取服务器流程图：

```js
const fc = await getSkillFlowchart(skillId)
```

对应 `cap.server.getFlowchart(skillId, version)`。如果服务器没返回，前端用内置 `BUILTIN_FLOWCHART`（定义在 `AutomationPage.jsx`）兜底，trace-auto 的 handler 在 `get_flowchart` 动作里也会回退到内置 `FLOWCHART` 常量。

技能包本身的加载由 `cap.skillMarket.load({ type, skillId, version, meta, flowchart, handler })` 完成，注册到本地 `_installedSkills` Map。

## 3. 执行 — 用户点 Execute 后的链路

点击「执行」按钮（`handleExecute`）后：

1. **隐藏主窗口**：`getAllWebviewWindows().find(w => w.label === 'main').hide()`
2. **弹出迷你悬浮窗**：`invoke('fw_open', { input: { id, title: '自动化: Trae', width: 360, height: 240, anchor: 'right', payload: { goal, maxRounds, idleTimeoutSec, recognition, softwareName, softwareNameEn } } })`
3. **调 handler execute**：前端通过 `POST /v1/skill/trace-auto` body `{ action: 'execute', goal, recognition, maxRounds }`
4. **handler 内部**（见 `index.js` 的 `execute`）：
   - `cap.flowchart.setCurrent(FLOWCHART)` 设置当前流程图 + 清空 trace
   - `cap.control.reset()` 重置控制信号
   - 走流程图节点循环：
     - `ensure` — `cap.recognize.chain({ kind: 'element_visible', selector: 'body' }, ['cdp'])` 验证 CDP 连接
     - `read` — `getPageState()` 读对话轮次 / 运行态 / 错误 / 按钮
     - `running?` — J1 判断，命中走 `wait`，否则走 `act`
     - `act` — 错误处理 / 点击确认按钮 / 条件回复 / 发送输入框
     - `errors?` — J2 判断，命中走 `prompt`
     - `stuck?` — J3 判断（3 轮无变化），命中走 `prompt`
     - `loop` — LLM 生成跟进指令后回到 `read`
   - 每个关键节点入口 `cap.control.check(nodeId)` 检查停止信号与断点

## 4. 停止 — 用户点停止后的链路

迷你悬浮窗被关闭（用户点停止键）：

1. 前端轮询 `invoke('fw_get_state')`，发现悬浮窗 id 不在列表里
2. **恢复主窗口**：`main.show(); main.setFocus()`
3. **拉流程图与轨迹**：
   - `callSkill('get_flowchart')` → handler 返回 `cap.flowchart.get() || FLOWCHART`
   - `callSkill('get_trace')` → handler 返回 `cap.flowchart.trace`
4. 前端 `FlowchartView` 用 `traceMap` 把 trace 落到对应节点上高亮显示（如 `read · #3`、`act · #5`）

## 5. 回放 — 流程图重放机制

`FlowchartView` 组件（`src/AutomationPage.jsx`）维护 `traceMap`：

```js
const traceMap = new Map()
;(trace || []).forEach((t, i) => {
  if (!traceMap.has(t.nodeId)) traceMap.set(t.nodeId, { ...t, idx: i })
})
```

trace-auto 的 trace entry 由 `cap.flowchart.pushTrace` 写入，每条含 `{ runId, nodeId, status, ts, iso, ms, note }`。停止后用户能看到完整执行轨迹，包括：
- 哪些节点命中（`ok`）、哪些失败（`fail`）、何时停止（`stopped`）
- judgment 节点的分支选择（如 `running?` → `yes → wait`）
- 卡住次数与用户提问的回答

## 录制链路（Record）

「录制」按钮永远可点，不需要先搜索命中。点击后：

1. 隐藏主窗口 + 弹迷你悬浮窗（title 带「录制」前缀，payload 带 `record: true`）
2. 调 `callSkill('record', { softwareName, softwareNameEn })`
3. handler 的 `record` 动作：`cap.flowchart.setCurrent(FLOWCHART)` + `cap.control.reset()` + 调 `cap.cdp.startRecording(params)`（若注入）
4. 用户停止后同样拉 `get_flowchart` + `get_trace` 回放

## 动作清单与 frontmatter 字段

- **动作清单速查**：见 `SKILL.md` 的「动作分组」表（流程图 / 执行入口 / 控制流 / 断点 / 升级 / 旧版兼容）。
- **frontmatter 字段语义**：见 `_template/SKILL.md` 与 `_template/USAGE.md` 的字段速查表。trace-auto 关键字段：`id=com.tupautochrome.skills.trace-auto`、`version=6.0.0`、`category=desktop`、`software_names=["Trae","Trae IDE"]`、`distribution.channel=stable`。
