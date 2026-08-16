// 标准技能模板 v1.0
// =============================================================================
// 这是新技能的起点模板。拷贝此目录，改 SKILL.md/id、flowchart.json/nodes、handler 实现
// 三件套关系：
//   - SKILL.md        技能元数据（frontmatter）+ 人读说明，被 manifest 与市场索引
//   - flowchart.json  标准流程图配置（节点/边/判断），前端停止后用它完整重放
//   - index.js        运行时 handler，被 mid 路由调用；本文件提供标准动作入口
// =============================================================================

// ── 流程图（与 flowchart.json 一致，作为缺省回退） ─────────────────────────
// 当 cap.flowchart.get() 返回 null（未调 setCurrent）时，回退到这个常量
const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'template-flowchart',
  skillId: 'com.tupautochrome.skills.template',
  version: '1.0.0',
  name: '模板流程图',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: ['cdp', 'uia', 'ocr', 'vlm'],
  nodes: [
    { id: 'start', type: 'start',   label: '开始' },
    { id: 'act',   type: 'process', label: '执行动作', recognition: ['cdp'] },
    { id: 'end',   type: 'end',     label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'act' },
    { from: 'act',   to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-06-29T00:00:00Z', updatedAt: '2026-06-29T00:00:00Z', author: 'your-org' },
}

// ── handler 入口（与现有 trace-auto 兼容，被 mid 路由调用） ────────────────
// 前端 AutomationPage 通过 POST /v1/skill/<skill-id> 调用，body 即 params
async function handler(params, complete) {
  const { action } = params

  // 元信息查询
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_judgments') return (cap.flowchart.get() || FLOWCHART).judgments || []
  if (action === 'get_trace')     return cap.flowchart.trace

  // 软件搜索（调 cap.skillMarket）
  if (action === 'search_software') return await cap.skillMarket.searchBySoftware(params.softwareName, params.softwareNameEn)

  // 执行入口
  if (action === 'execute') return await execute(params, complete)
  if (action === 'record')  return await record(params, complete)

  // 控制流（调 cap.control）
  if (action === 'step_once') { cap.control.stepOnce(); return { ok: true } }
  if (action === 'pause')     { cap.control.pause();    return { ok: true, paused: true } }
  if (action === 'resume')    { cap.control.resume();   return { ok: true, paused: false } }
  if (action === 'stop')      { cap.control.stop();     return { ok: true, stopRequested: true } }

  // 断点管理
  if (action === 'add_breakpoint')    { cap.control.addBreakpoint(params.nodeId);    return { ok: true } }
  if (action === 'remove_breakpoint') { cap.control.removeBreakpoint(params.nodeId); return { ok: true } }
  if (action === 'clear_breakpoints') { cap.control.clearBreakpoints();              return { ok: true } }

  // 升级管理（调 cap.skillMarket）
  if (action === 'check_upgrade') return await cap.skillMarket.checkUpgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'upgrade')       return await cap.skillMarket.upgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'rollback')      return await cap.skillMarket.rollback(params.skillId || FLOWCHART.skillId)

  return { ok: false, error: 'unknown action: ' + action }
}

// ── execute ──────────────────────────────────────────────────────────────
// 标准执行循环骨架：每节点先 cap.control.check(nodeId) → cap.flowchart.beginNode
// → 执行节点逻辑 → cap.flowchart.endNode；识别走 cap.recognize.chain(task, tiers)
async function execute(params, complete) {
  // 1. 设置当前流程图 + 重置控制信号 + 清空 trace
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()

  const goal = params.goal || '默认任务目标'
  const recognition = (params.recognition && params.recognition.length) ? params.recognition : FLOWCHART.recognition
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  // 2. 走流程图节点（简化示例，实际参考 trace-auto/index.js 的 execute 实现）
  //    start
  let t0 = cap.flowchart.beginNode('start')
  if (!(await cap.control.check('start'))) { cap.flowchart.endNode('start', 'stopped', '用户停止', t0); return _summarize(0, 'stopped') }
  cap.flowchart.endNode('start', 'ok', '', t0)

  //    act
  t0 = cap.flowchart.beginNode('act')
  if (!(await cap.control.check('act'))) { cap.flowchart.endNode('act', 'stopped', '用户停止', t0); return _summarize(1, 'stopped') }
  // 识别走 cap.recognize.chain
  const r = await cap.recognize.chain({ kind: 'element_visible', selector: 'body' }, recognition)
  cap.flowchart.endNode('act', r.ok ? 'ok' : 'fail', r.note, t0)

  //    end
  t0 = cap.flowchart.beginNode('end')
  cap.flowchart.endNode('end', 'ok', '共 1 轮', t0)

  // 3. 终止：返回 trace 序列化
  return _summarize(1, 'completed')
}

function _summarize(round, status) {
  return {
    ok: true,
    status,
    rounds: round,
    flowchart: cap.flowchart.get() || FLOWCHART,
    judgments: (cap.flowchart.get() || FLOWCHART).judgments || [],
    trace: cap.flowchart.trace,
  }
}

// ── record ────────────────────────────────────────────────────────────────
async function record(params, complete) {
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  // 占位：调 cap.cdp.startRecording（若注入）
  if (cap.cdp && typeof cap.cdp.startRecording === 'function') {
    await cap.cdp.startRecording(params)
    return { ok: true, mode: 'record', message: '录制已开始，按迷你悬浮窗停止键结束' }
  }
  return { ok: true, mode: 'record', message: 'cap.cdp.startRecording 未注入，仅记录操作日志' }
}

// ── 生命周期导出（参考 Robot Framework Setup/Teardown + Robocorp @task） ──
export const lifecycle = {
  onSkillLoad:   async (ctx) => { cap.runtime.log('template', 'skill loaded') },
  onTaskStart:   async (ctx, task) => { cap.runtime.log('template', 'task start: ' + task) },
  onTaskEnd:     async (ctx, task, result) => { cap.runtime.log('template', 'task end: ' + task) },
  onSkillUnload: async (ctx) => { cap.runtime.log('template', 'skill unloaded') },
}

// ── 调试钩子（参考 Playwright Trace Viewer + RF Language Server） ──────────
export const debug = {
  // 列出可监视的变量
  getVariableScope: (ctx) => ({ locals: ctx?.locals || {}, flowchart: cap.flowchart.get() }),
  // 命中断点时调用
  onBreakpoint: async (ctx, node) => { cap.runtime.log('debug', 'breakpoint hit: ' + node.id) },
}

export default handler
