# 使用流程（USAGE）

本文档描述一个标准技能从「被发现」到「停止回放」的完整 5 步链路。5 步：**搜索 → 加载 → 执行 → 停止 → 回放**。

## 1. 搜索 — 如何被前端 AutomationPage 发现

前端 `src/AutomationPage.jsx` 是技能市场的入口。用户在搜索框输入软件中/英文名后：

1. 前端调 `searchSoftwareSkills({ softwareName, softwareNameEn })`（来自 `./mcpClient`）。
2. 该调用最终落到技能 handler 的 `search_software` 动作，本模板里走 `cap.skillMarket.searchBySoftware(name, nameEn)`。
3. `cap.skillMarket.searchBySoftware` 内部调 `cap.server.searchSkills(q, { softwareName, softwareNameEn })`，命中服务器侧技能市场。
4. 返回 `{ skills: [...], executable: boolean, query }`：
   - `executable: true` → 前端「执行」按钮可点
   - `executable: false` → 「执行」置灰，「录制」永远可点

**技能被发现的关键**：`SKILL.md` 的 `software_names` / `software_names_en` 字段必须与用户输入匹配（中英文都填）。

## 2. 加载 — 技能包加载

命中后，前端在「执行」点击前会先尝试取服务器流程图：

```js
const fc = await getSkillFlowchart(skillId)
```

对应 `cap.server.getFlowchart(skillId, version)`。如果服务器没返回，前端用内置 `BUILTIN_FLOWCHART` 兜底。

技能包本身的加载由 `cap.skillMarket.load({ type, skillId|path, version, meta, flowchart, handler })` 完成，注册到本地 `_installedSkills` Map。

## 3. 执行 — 用户点 Execute 后的链路

点击「执行」按钮（`handleExecute`）后：

1. **隐藏主窗口**：`getAllWebviewWindows().find(w => w.label === 'main').hide()`
2. **弹出迷你悬浮窗**：`invoke('fw_open', { input: { id, title, width, height, anchor: 'right', payload: { goal, maxRounds, recognition, ... } } })`
3. **调 handler execute**：前端通过 `POST /v1/skill/<skill-id>` body `{ action: 'execute', goal, recognition, maxRounds }`
4. **handler 内部**（见 `index.js` 的 `execute`）：
   - `cap.flowchart.setCurrent(FLOWCHART)` 设置当前流程图 + 清空 trace
   - `cap.control.reset()` 重置控制信号
   - 循环遍历节点：每节点 `cap.control.check(nodeId)` → `cap.flowchart.beginNode` → 执行 → `cap.flowchart.endNode`
   - 识别走 `cap.recognize.chain(task, tiers)`

## 4. 停止 — 用户点停止后的链路

迷你悬浮窗被关闭（用户点停止键）：

1. 前端轮询 `invoke('fw_get_state')`，发现悬浮窗 id 不在列表里
2. **恢复主窗口**：`main.show(); main.setFocus()`
3. **拉流程图与轨迹**：
   - `callSkill('get_flowchart')` → handler 返回 `cap.flowchart.get()`
   - `callSkill('get_trace')` → handler 返回 `cap.flowchart.trace`
4. 前端 `FlowchartView` 用 `traceMap` 把 trace 落到对应节点上高亮显示

## 5. 回放 — 流程图重放机制

`FlowchartView` 组件（`src/AutomationPage.jsx`）维护一个 `traceMap`：

```js
const traceMap = new Map()
;(trace || []).forEach((t, i) => {
  if (!traceMap.has(t.nodeId)) traceMap.set(t.nodeId, { ...t, idx: i })
})
```

每个节点渲染时查 `traceMap.get(n.id)`，命中则显示状态徽标（`ok`/`fail`/`stopped`/`breakpoint`）与序号。停止后用户能看到完整执行轨迹。

## 录制链路（Record）

「录制」按钮永远可点，不需要先搜索命中。点击后：

1. 隐藏主窗口 + 弹迷你悬浮窗（title 带「录制」前缀，payload 带 `record: true`）
2. 调 `callSkill('record', { softwareName, softwareNameEn })`
3. handler 的 `record` 动作调 `cap.cdp.startRecording(params)`（若注入）
4. 用户停止后同样拉 `get_flowchart` + `get_trace` 回放

## SKILL.md frontmatter 字段语义速查

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 反域名全局唯一 id，如 `com.tupautochrome.skills.template` |
| `name` / `name_en` | string | 中/英文技能名，前端搜索匹配用 |
| `version` | SemVer | 版本号，升级比对用 |
| `category` | enum | `web` / `desktop` / `mobile` / `data` / `misc` |
| `software_names` / `software_names_en` | string[] | 该技能支持的目标软件名，`search_software` 按此匹配 |
| `tags` / `keywords` | string[] | 搜索辅助标签 |
| `capabilities` | object[] | 能力声明：每个能力含 `id`/`name`/`inputs`/`outputs` |
| `runtime.engine` | string | 执行引擎，当前固定 `js` |
| `runtime.caps` | string[] | 依赖的能力及其版本约束，如 `cap.cdp@^1.0.0` |
| `runtime.permissions` | string[] | 权限声明，如 `http:fetch:*` / `storage:readwrite` |
| `distribution.channel` | enum | `stable` / `beta` / `nightly` |
| `distribution.minAppVersion` / `maxAppVersion` | SemVer | 兼容的 App 版本区间 |
| `distribution.rollout` | object | 灰度策略：`percentage` + `targetUsers` |
| `signing.algorithm` / `publicKey` | string | 签名算法与公钥，ed25519 |
