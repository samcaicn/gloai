// trace-auto.test.js — 测试 trace-auto 技能 handler 的所有 action
// 覆盖：get_flowchart / get_judgments / get_trace / search_software / execute / record /
//       step_once / pause / resume / stop / _legacyAction 等
// 所有代码注释用中文。
// 注意：不修改被测代码，若发现 bug 在测试注释里标 TODO。

import { test, describe, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { loadFullStack, sleep } from './_helper.js'

// 跨 vm context 的对象比较
function jsonEqual(actual, expected) {
  assert.equal(JSON.stringify(actual), JSON.stringify(expected))
}

// 每个测试用例加载一份完整的 cap + trace-auto
function fresh() {
  const env = loadFullStack()
  return env
}

// 注入 cap.runtime.sleep 让它立即返回，避免测试卡在 cap.runtime.sleep(8000) 等地方
function fastSleep(env) {
  env.cap.runtime.sleep = async () => {}
}

// 注入 cap.cdp.eval 模拟页面状态返回（getPageState 调用）
// state: { u, a, running, txt, actionBtns, errorMsgs }
function mockPageState(env, state) {
  env.cap.cdp.eval = async (expr) => {
    // getPageState 返回完整 JSON
    if (expr.includes('JSON.stringify({ u:u')) return JSON.stringify(state)
    // 输入框检查（act 4.4 分支）
    if (expr.includes("chat-input-v2-input-box-editable") && expr.includes("e.innerText")) return ''
    // check_running 等返回 '0'
    return '0'
  }
  env.cap.cdp.click = async () => ({ ok: true })
  env.cap.cdp.type = async () => ({ ok: true })
}

// ────────────────────────────────────────────────────────────────────
// B.1 元信息查询
// ────────────────────────────────────────────────────────────────────
describe('B.1 元信息查询', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('get_flowchart 返回完整流程图（含 nodes / connections / judgments）', async () => {
    const fc = await env.handler({ action: 'get_flowchart' })
    assert.ok(fc)
    assert.ok(Array.isArray(fc.nodes))
    assert.ok(Array.isArray(fc.connections))
    assert.ok(Array.isArray(fc.judgments))
    assert.ok(fc.nodes.length > 0)
    // FLOWCHART 常量字段是 name（不是 title），与 BUILTIN_FLOWCHART 不同
    assert.equal(fc.name, 'Trae 自动化循环')
    assert.equal(fc.version, '6.0.0')
    assert.equal(fc.skillId, 'com.tupautochrome.skills.trace-auto')
    assert.equal(fc.entry, 'start')
  })

  test('get_flowchart 返回的是深拷贝（setCurrent 后修改不影响后续调用）', async () => {
    // 未调用 setCurrent 时 cap.flowchart.get() 返回 null，handler 回退到 FLOWCHART 常量引用
    // 此时修改会污染常量；调用 record 让 trace-auto 调用 cap.flowchart.setCurrent(FLOWCHART)
    // 之后 cap.flowchart.get() 返回 JSON 深拷贝，修改不影响后续
    await env.handler({ action: 'record' }, null)
    const fc1 = await env.handler({ action: 'get_flowchart' })
    const beforeLen = fc1.nodes.length
    fc1.nodes.push({ id: 'hacked', type: 'process', label: 'hack' })
    fc1.name = 'hacked'
    const fc2 = await env.handler({ action: 'get_flowchart' })
    assert.notEqual(fc2.nodes.length, fc1.nodes.length)
    assert.equal(fc2.nodes.length, beforeLen)
    assert.equal(fc2.name, 'Trae 自动化循环')
  })

  test('get_judgments 返回 judgments 数组（J1/J2/J3）', async () => {
    const j = await env.handler({ action: 'get_judgments' })
    assert.ok(Array.isArray(j))
    assert.equal(j.length, 3)
    const ids = j.map((x) => x.id).sort()
    // 跨 vm context 用 JSON.stringify 比较，避免 deepEqual 的 reference-equal 失败
    jsonEqual(ids, ['J1', 'J2', 'J3'])
    // 每个含 node / rule / onMatch / recognition 字段
    for (const x of j) {
      assert.ok(x.node, 'judgment 必须含 node')
      assert.ok(x.rule, 'judgment 必须含 rule')
      assert.ok(x.onMatch, 'judgment 必须含 onMatch')
    }
  })

  test('get_trace 初始返回空数组', async () => {
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(Array.isArray(trace))
    // 初始 trace 是空（trace-auto 加载时 cap.flowchart.trace = []）
    // 但若 execute 跑过后会有内容；这里初始为空
    assert.equal(trace.length, 0)
  })
})

// ────────────────────────────────────────────────────────────────────
// B.2 软件搜索 search_software
// ────────────────────────────────────────────────────────────────────
describe('B.2 软件搜索 search_software', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('search_software: 中文名+英文名 → 返回 skills 列表', async () => {
    env.setFetchImpl(async () => ({
      ok: true,
      json: async () => [{ skill_id: 'trae-cn', name: 'Trae 中文', version: '1.0' }],
    }))
    const r = await env.handler({ action: 'search_software', softwareName: 'Trae', softwareNameEn: 'Trae' })
    assert.equal(r.ok, true)
    assert.ok(Array.isArray(r.skills))
    assert.equal(r.skills.length, 1)
    assert.equal(r.skills[0].skill_id, 'trae-cn')
    assert.equal(r.executable, true)
    assert.match(r.query, /Trae/)
  })

  test('search_software: 中文名+英文名都为空 → 返回 ok:false', async () => {
    const r = await env.handler({ action: 'search_software', softwareName: '', softwareNameEn: '' })
    assert.equal(r.ok, false)
    assert.match(r.error, /softwareName 或 softwareNameEn 不能都为空/)
    assert.equal(r.executable, false)
    assert.equal(r.skills.length, 0)
  })

  test('search_software: 服务器不可达（fetch 失败） → 返回内置 fallback（trace-auto 自身）', async () => {
    // 默认 fetch impl 返回 ok=false，模拟服务器不可达
    // 但 cap.server.searchSkills 在 fetch 失败时返回 []（不是抛异常）
    // 所以 search_software 会得到 skills=[]，executable=false，ok=true（不是 fallback）
    // 实际上"内置 fallback"分支只有在 cap.server.searchSkills 不存在时才走
    // 这里通过删除 cap.server.searchSkills 来触发 fallback 分支
    delete env.cap.server.searchSkills
    const r = await env.handler({ action: 'search_software', softwareName: 'Trae' })
    assert.equal(r.ok, true)
    assert.equal(r.fallback, true)
    assert.equal(r.executable, true)
    assert.equal(r.skills.length, 1)
    assert.equal(r.skills[0].skill_id, 'trace-auto')
  })

  test('search_software: 服务器返回空数组 → 走 fallback 返回内置 trace-auto', async () => {
    // 实际行为：cap.skillMarket.searchBySoftware 返回 {skills:[], executable:false}，
    // trace-auto 检测 list.length===0 后落到 fallback 分支，返回内置 trace-auto 自身
    env.setFetchImpl(async () => ({ ok: true, json: async () => [] }))
    const r = await env.handler({ action: 'search_software', softwareName: '不存在的软件' })
    assert.equal(r.ok, true)
    assert.equal(r.fallback, true)
    assert.equal(r.executable, true)
    assert.equal(r.skills.length, 1)
    assert.equal(r.skills[0].skill_id, 'trace-auto')
  })

  test('search_software: cap.server.searchSkills 抛异常 → 走 fallback（异常被吞）', async () => {
    // 实际行为：searchSoftware 内 try/catch 吞掉异常落到 fallback
    // TODO（被测代码设计）：异常被静默吞掉，建议日志输出便于排查
    env.cap.server.searchSkills = async () => { throw new Error('network down') }
    const r = await env.handler({ action: 'search_software', softwareName: 'Trae' })
    assert.equal(r.ok, true)
    assert.equal(r.fallback, true)
    assert.equal(r.executable, true)
    assert.equal(r.skills.length, 1)
    assert.equal(r.skills[0].skill_id, 'trace-auto')
  })

  test('search_software: 服务器返回多个 skill 时 list 含多个', async () => {
    env.setFetchImpl(async () => ({
      ok: true,
      json: async () => [{ skill_id: 's1' }, { skill_id: 's2' }],
    }))
    const r = await env.handler({ action: 'search_software', softwareName: 'Trae' })
    assert.equal(r.ok, true)
    assert.equal(r.skills.length, 2)
    assert.equal(r.executable, true)
    assert.equal(r.fallback, undefined)
  })
})

// ────────────────────────────────────────────────────────────────────
// B.3 控制流动作（step_once / pause / resume / stop）
// 注：trace-auto handler 通过 cap.control.* 操作控制信号（capabilities.js 的单例）
// 这些动作修改 _controlState 单例；这里同时验证 cap.control.check 的阻塞行为
// ────────────────────────────────────────────────────────────────────
describe('B.3 控制流动作', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('step_once: 返回 ok: true, paused:false, stepOnce:true', async () => {
    const r = await env.handler({ action: 'step_once' })
    assert.equal(r.ok, true)
    assert.equal(r.paused, false)
    assert.equal(r.stepOnce, true)
  })

  test('pause: 返回 ok: true, paused:true', async () => {
    const r = await env.handler({ action: 'pause' })
    assert.equal(r.ok, true)
    assert.equal(r.paused, true)
  })

  test('resume: 返回 ok: true, paused:false', async () => {
    const r = await env.handler({ action: 'resume' })
    assert.equal(r.ok, true)
    assert.equal(r.paused, false)
  })

  test('stop: 返回 ok: true, stopRequested:true', async () => {
    const r = await env.handler({ action: 'stop' })
    assert.equal(r.ok, true)
    assert.equal(r.stopRequested, true)
  })

  test('stop 之后再 stop 仍返回 stopRequested:true', async () => {
    await env.handler({ action: 'stop' })
    const r2 = await env.handler({ action: 'stop' })
    assert.equal(r2.stopRequested, true)
  })

  test('cap.control.check: stop 后返回 false（控制信号单例行为）', async () => {
    await env.handler({ action: 'stop' })
    const r = await env.cap.control.check()
    assert.equal(r, false)
  })

  test('cap.control.check: 默认状态返回 true', async () => {
    const r = await env.cap.control.check()
    assert.equal(r, true)
  })

  test('cap.control.check: stepOnce 单步后保持 paused=true，下一次 check 阻塞', async () => {
    // 先 stepOnce 单步（stepOnce=true, paused=false）
    await env.handler({ action: 'step_once' })
    // 第一次 cap.control.check 消费 stepOnce 信号，返回 true
    const r1 = await env.cap.control.check()
    assert.equal(r1, true)
    // 之后 _controlState.paused=true，下次 cap.control.check 阻塞（用 stop 唤醒避免卡死）
    let resolved = false
    const p = env.cap.control.check()
    p.then((r) => { resolved = r })
    await sleep(50)
    assert.equal(resolved, false)   // 仍阻塞
    // stop 唤醒
    await env.handler({ action: 'stop' })
    const r2 = await p
    assert.equal(r2, false)
  })

  test('cap.control.check: 命中断点 → 自动进入暂停态', async () => {
    // 添加 read 节点断点
    await env.handler({ action: 'add_breakpoint', nodeId: 'read' })
    assert.equal(env.cap.control.hasBreakpoint('read'), true)
    // check('read') 会因命中断点而阻塞（_controlState.paused=true）
    let resolved = false
    const p = env.cap.control.check('read')
    p.then((r) => { resolved = r })
    await sleep(50)
    assert.equal(resolved, false)   // 阻塞中
    // resume 唤醒
    await env.handler({ action: 'resume' })
    const r = await p
    assert.equal(r, true)
    // 清除断点
    await env.handler({ action: 'clear_breakpoints' })
    assert.equal(env.cap.control.hasBreakpoint('read'), false)
  })

  test('cap.control.check: removeBreakpoint 后不再命中', async () => {
    await env.handler({ action: 'add_breakpoint', nodeId: 'act' })
    await env.handler({ action: 'remove_breakpoint', nodeId: 'act' })
    assert.equal(env.cap.control.hasBreakpoint('act'), false)
    // 直接 check 应不阻塞
    const r = await env.cap.control.check('act')
    assert.equal(r, true)
  })
})

// ────────────────────────────────────────────────────────────────────
// B.4 execute 端到端（mock 全部 cap 依赖）
// execute 内部通过 cap.control.check / cap.flowchart.pushTrace 驱动循环
// ────────────────────────────────────────────────────────────────────
describe('B.4 execute 端到端', () => {
  let env
  beforeEach(() => { env = fresh(); fastSleep(env) })

  test('execute: ensureRes 失败（CDP 未连接）→ 返回 ok:false, error: IDE 未连接', async () => {
    // mock cap.cdp.eval 返回 '0'（element_visible 不命中）
    env.cap.cdp.eval = async () => '0'
    // 注意：trace-auto 的 _recognizeCdp 期望 r === '1' 视为命中
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
    assert.equal(r.ok, false)
    assert.match(r.error, /IDE 未连接/)
    // trace 应包含 start / ensure / end 节点
    const trace = await env.handler({ action: 'get_trace' })
    const nodeIds = trace.map((t) => t.nodeId)
    assert.ok(nodeIds.includes('start'))
    assert.ok(nodeIds.includes('ensure'))
    assert.ok(nodeIds.includes('end'))
    // end 节点 status=fail
    const endEntry = trace.find((t) => t.nodeId === 'end')
    assert.equal(endEntry.status, 'fail')
  })

  test('execute: maxRounds=1 跑完后正常结束，返回 status=completed', async () => {
    // mock 页面状态：not running，无错误，无按钮，输入框空，loop 分支会调 LLM 生成消息
    mockPageState(env, { u: 1, a: 1, running: false, txt: 'AI 回复内容', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [{ tier: 'cdp', ok: true, ms: 0, note: '' }] })
    env.cap.llm.complete = async () => '跟进消息'
    const r = await env.handler({ action: 'execute', goal: '推进任务', maxRounds: 1 }, null)
    assert.equal(r.ok, true)
    assert.equal(r.status, 'completed')
    assert.equal(r.rounds, 1)
    // 返回值含 flowchart / judgments / trace
    assert.ok(r.flowchart)
    assert.ok(r.flowchart.nodes)
    assert.ok(Array.isArray(r.judgments))
    assert.ok(Array.isArray(r.trace))
  })

  test('execute: 用户请求停止 → 返回 status=stopped', async () => {
    mockPageState(env, { u: 1, a: 1, running: false, txt: 'AI 回复', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [{ tier: 'cdp', ok: true, ms: 0, note: '' }] })
    env.cap.llm.complete = async () => 'msg'
    // execute 入口会调 cap.control.reset() 清掉 stopRequested，先 mock 为空操作让 stop 信号保留
    // 这样 execute 第一次 cap.control.check('ensure') 就会因 stopRequested=true 返回 false
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      await env.handler({ action: 'stop' })
      const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
    } finally {
      env.cap.control.reset = origReset
    }
  })

  test('execute: 返回值含 flowchart 含 start 节点', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    env.cap.llm.complete = async () => 'msg'
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    assert.ok(r.flowchart.nodes.find((n) => n.id === 'start'))
  })

  test('execute: trace 含 start / ensure / read 节点', async () => {
    mockPageState(env, { u: 1, a: 1, running: false, txt: 'x', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    env.cap.llm.complete = async () => 'msg'
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const nodeIds = r.trace.map((t) => t.nodeId)
    assert.ok(nodeIds.includes('start'))
    assert.ok(nodeIds.includes('ensure'))
    assert.ok(nodeIds.includes('read'))
    assert.ok(nodeIds.includes('end'))
  })

  test('execute: running=true 走 wait 分支，trace 含 wait 节点', async () => {
    mockPageState(env, { u: 1, a: 1, running: true, txt: '', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    // 让 waitIdle 不真的等 60 秒（fastSleep 已经把 cap.runtime.sleep 改为立即返回）
    // 但 waitIdle 会循环 timeoutSec*2 次，每次 sleep(500)，因为 fastSleep 立即返回所以很快
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1, idleTimeoutSec: 2 }, null)
    const nodeIds = r.trace.map((t) => t.nodeId)
    assert.ok(nodeIds.includes('wait'), 'trace 应含 wait 节点，实际: ' + JSON.stringify(nodeIds))
    assert.ok(nodeIds.includes('running?'))
  })

  test('execute: errorMsgs 非空走 prompt 分支', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: ['编译错误: syntax'] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    env.cap.ui.prompt = async () => '停止'
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const nodeIds = r.trace.map((t) => t.nodeId)
    assert.ok(nodeIds.includes('errors?'))
    assert.ok(nodeIds.includes('prompt'))
  })

  test('execute: actionBtns 非空走 act 点击分支', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: ['运行'], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const actEntries = r.trace.filter((t) => t.nodeId === 'act')
    assert.ok(actEntries.length > 0)
    assert.match(actEntries[0].note, /click: 运行/)
  })

  test('execute: setComplete 注入（cap.llm.setComplete 被调用）', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    let captured = null
    env.cap.llm.setComplete = (fn) => { captured = fn }
    const myComplete = () => 'response'
    await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, myComplete)
    assert.equal(captured, myComplete)
  })

  test('execute: get_trace 返回非空数组（execute 后）', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    env.cap.llm.complete = async () => 'msg'
    await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
  })
})

// ────────────────────────────────────────────────────────────────────
// B.5 record
// ────────────────────────────────────────────────────────────────────
describe('B.5 record', () => {
  let env
  beforeEach(() => { env = fresh(); fastSleep(env) })

  test('record: cap.cdp.startRecording 已注入时被调用', async () => {
    let called = null
    env.cap.cdp.startRecording = async (params) => { called = params; return { ok: true } }
    const r = await env.handler({ action: 'record', softwareName: 'Trae' }, null)
    assert.equal(r.ok, true)
    assert.equal(r.mode, 'record')
    assert.ok(called !== null)
    assert.equal(called.softwareName, 'Trae')
  })

  test('record: cap.cdp.startRecording 未注入时返回 ok:true, mode:record', async () => {
    // 不注入 startRecording
    const r = await env.handler({ action: 'record' }, null)
    assert.equal(r.ok, true)
    assert.equal(r.mode, 'record')
    assert.match(r.message, /未注入/)
  })

  test('record: trace 含 start 节点（_tracePush("start","ok","record mode")）', async () => {
    await env.handler({ action: 'record' }, null)
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
    assert.equal(trace[0].nodeId, 'start')
    assert.equal(trace[0].status, 'ok')
    assert.match(trace[0].note, /record mode/)
  })

  test('record: 重置控制信号（cap.control.reset 被调用）', async () => {
    // 先 pause 让 paused=true，然后 record 入口调 cap.control.reset() 应清掉
    await env.handler({ action: 'pause' })
    assert.equal(env.cap.control.isPaused(), true)
    await env.handler({ action: 'record' }, null)
    // record 后 paused=false（reset 已清掉）
    assert.equal(env.cap.control.isPaused(), false)
    assert.equal(env.cap.control.isStopRequested(), false)
    // cap.control.check 立即返回 true
    const r = await env.cap.control.check()
    assert.equal(r, true)
  })
})

// ────────────────────────────────────────────────────────────────────
// B.6 旧版兼容（_legacyAction）
// ────────────────────────────────────────────────────────────────────
describe('B.6 旧版兼容 _legacyAction', () => {
  let env
  beforeEach(() => { env = fresh(); fastSleep(env) })

  test('ensure_cdp: targets 非空时返回 connected:true', async () => {
    env.cap.cdp.getTargets = async () => [{ id: 't1', title: 'Trae' }]
    const r = await env.handler({ action: 'ensure_cdp' })
    assert.equal(r.connected, true)
    assert.equal(r.targets.length, 1)
  })

  test('ensure_cdp: targets 为空时返回 connected:false', async () => {
    env.cap.cdp.getTargets = async () => []
    const r = await env.handler({ action: 'ensure_cdp' })
    assert.equal(r.connected, false)
  })

  test('check_page: 关键词命中 → matched:true', async () => {
    env.cap.cdp.eval = async () => '1'
    const r = await env.handler({ action: 'check_page', keyword: 'trae' })
    assert.equal(r.matched, true)
  })

  test('check_page: 关键词未命中 → matched:false', async () => {
    env.cap.cdp.eval = async () => '0'
    const r = await env.handler({ action: 'check_page', keyword: 'trae' })
    assert.equal(r.matched, false)
  })

  test('read_state: 返回页面状态对象', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ u: 2, a: 3, running: true, txt: 'hello', actionBtns: [], errorMsgs: [] })
    const r = await env.handler({ action: 'read_state' })
    assert.equal(r.u, 2)
    assert.equal(r.a, 3)
    assert.equal(r.running, true)
    assert.equal(r.txt, 'hello')
  })

  test('click_button: buttonText 命中 → clicked:true（触发 cdp.eval）', async () => {
    let evalCalls = 0
    let lastExpr = ''
    env.cap.cdp.eval = async (expr) => { evalCalls++; lastExpr = expr; return 'ok' }
    const r = await env.handler({ action: 'click_button', buttonText: '运行' })
    assert.equal(r.clicked, true)
    assert.equal(evalCalls, 1)
    assert.match(lastExpr, /运行/)
  })

  test('click_button: cdp.eval 返回 no_match → clicked:false', async () => {
    env.cap.cdp.eval = async () => 'no_match'
    const r = await env.handler({ action: 'click_button', buttonText: '不存在' })
    assert.equal(r.clicked, false)
  })

  test('click_send: 触发 cap.cdp.click', async () => {
    let clickedSelector = ''
    env.cap.cdp.click = async (selector) => { clickedSelector = selector; return { ok: true } }
    const r = await env.handler({ action: 'click_send' })
    assert.equal(r.ok, true)
    assert.match(clickedSelector, /chat-input-v2-send-button/)
  })

  test('click_stop: 调 cdp.eval 并解析结果', async () => {
    env.cap.cdp.eval = async () => 'stopped'
    const r = await env.handler({ action: 'click_stop' })
    assert.equal(r.stopped, true)
    env.cap.cdp.eval = async () => 'no_stop'
    const r2 = await env.handler({ action: 'click_stop' })
    assert.equal(r2.stopped, false)
  })

  test('type_input: 触发 cap.cdp.type', async () => {
    let typedSelector = ''
    let typedText = ''
    env.cap.cdp.type = async (selector, text) => { typedSelector = selector; typedText = text; return { ok: true } }
    const r = await env.handler({ action: 'type_input', text: 'hello' })
    assert.equal(r.ok, true)
    assert.match(typedSelector, /chat-input-v2-input-box-editable/)
    assert.equal(typedText, 'hello')
  })

  test('type_and_send: 依次 type + sleep + click', async () => {
    let order = []
    env.cap.cdp.type = async () => { order.push('type'); return { ok: true } }
    env.cap.cdp.click = async () => { order.push('click'); return { ok: true } }
    const r = await env.handler({ action: 'type_and_send', text: 'hi', waitAfterMs: 0 })
    assert.equal(r.ok, true)
    assert.equal(r.sent, 'hi')
    assert.deepEqual(order, ['type', 'click'])
  })

  test('verify_input: 文本匹配 → verified:true', async () => {
    env.cap.cdp.eval = async () => 'hello'
    const r = await env.handler({ action: 'verify_input', text: 'hello' })
    assert.equal(r.verified, true)
  })

  test('clear_input: 触发 cdp.eval 重置输入框', async () => {
    let lastExpr = ''
    env.cap.cdp.eval = async (expr) => { lastExpr = expr; return 'ok' }
    const r = await env.handler({ action: 'clear_input' })
    assert.equal(r.ok, true)
    assert.match(lastExpr, /e\.innerText='/)
  })

  test('set_conditions / get_conditions / clear_conditions: 读写 storage', async () => {
    // set
    env.cap.llm.complete = async () => '1. 简洁规则一\n2. 简洁规则二'
    const r1 = await env.handler({ action: 'set_conditions', conditions: ['原始条件1', '原始条件2'] })
    assert.ok(r1.conditions.length > 0)
    // get
    const r2 = await env.handler({ action: 'get_conditions' })
    assert.ok(Array.isArray(r2.conditions))
    assert.ok(r2.conditions.length > 0)
    // clear
    const r3 = await env.handler({ action: 'clear_conditions' })
    assert.equal(r3.ok, true)
    const r4 = await env.handler({ action: 'get_conditions' })
    assert.equal(r4.conditions.length, 0)
  })

  test('set_conditions: skipSummarize=true 时跳过 LLM 总结', async () => {
    let llmCalled = false
    env.cap.llm.complete = async () => { llmCalled = true; return '总结后' }
    const r = await env.handler({ action: 'set_conditions', conditions: ['c1', 'c2'], skipSummarize: true })
    assert.equal(llmCalled, false)
    assert.deepEqual(r.conditions, ['c1', 'c2'])
  })

  test('unknown action: 返回 ok:false, error: unknown action', async () => {
    const r = await env.handler({ action: 'totally_unknown' })
    assert.equal(r.ok, false)
    assert.match(r.error, /unknown action/)
  })

  test('start action: 等价于 execute（返回 rounds + logs）', async () => {
    mockPageState(env, { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] })
    env.cap.recognize.chain = async () => ({ ok: true, tier: 'cdp', value: '1', trace: [] })
    env.cap.llm.complete = async () => 'msg'
    const r = await env.handler({ action: 'start', goal: 'g', maxRounds: 1 }, null)
    assert.ok(r.rounds !== undefined)
    assert.ok(Array.isArray(r.logs))
  })

  test('status action: targets 为空 → connected:false', async () => {
    env.cap.cdp.getTargets = async () => []
    const r = await env.handler({ action: 'status' })
    assert.equal(r.connected, false)
    assert.equal(r.state, 'disconnected')
  })

  test('status action: targets 非空 + getPageState running=true → state:running', async () => {
    env.cap.cdp.getTargets = async () => [{ id: 't1' }]
    env.cap.cdp.eval = async () => JSON.stringify({ u: 1, a: 1, running: true, txt: '', actionBtns: [], errorMsgs: [] })
    const r = await env.handler({ action: 'status' })
    assert.equal(r.connected, true)
    assert.equal(r.state, 'running')
    assert.equal(r.running, true)
  })

  test('chat action: 无 conditions 时返回 storage 中保存的', async () => {
    env.cap.storage.set('trace_auto_conditions', ['saved-cond-1'])
    const r = await env.handler({ action: 'chat', conditions: [] })
    assert.deepEqual(r.conditions, ['saved-cond-1'])
  })

  test('chat action: 有 conditions 时保存到 storage', async () => {
    env.cap.llm.complete = async () => '总结后'
    const r = await env.handler({ action: 'chat', conditions: ['c1', 'c2'] })
    assert.ok(Array.isArray(r.conditions))
    // 验证 storage 已写入
    const stored = env.cap.storage.get('trace_auto_conditions')
    assert.ok(Array.isArray(stored))
  })

  test('find_exe / scan_ports: 返回 ok:true', async () => {
    const r1 = await env.handler({ action: 'find_exe' })
    assert.equal(r1.ok, true)
    const r2 = await env.handler({ action: 'scan_ports' })
    assert.equal(r2.ok, true)
  })

  test('targets action: 直接调 cap.cdp.getTargets', async () => {
    env.cap.cdp.getTargets = async () => [{ id: 't1' }, { id: 't2' }]
    const r = await env.handler({ action: 'targets' })
    assert.equal(r.length, 2)
  })

  test('check_running: cdp.eval 返回 true → running:true', async () => {
    // trace-auto 的 check_running 实现：`running: await cap.cdp.eval(...) === true`
    // 注意比较的是 true（严格相等）
    env.cap.cdp.eval = async () => true
    const r = await env.handler({ action: 'check_running' })
    assert.equal(r.running, true)
    env.cap.cdp.eval = async () => false
    const r2 = await env.handler({ action: 'check_running' })
    assert.equal(r2.running, false)
  })

  test('count_turns: 解析 cdp.eval 返回的 JSON', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ user: 3, ai: 2 })
    const r = await env.handler({ action: 'count_turns' })
    assert.equal(r.user, 3)
    assert.equal(r.ai, 2)
  })

  test('read_input: 返回输入框文本', async () => {
    env.cap.cdp.eval = async () => 'hello input'
    const r = await env.handler({ action: 'read_input' })
    assert.equal(r.text, 'hello input')
  })

  test('wait_idle: 调用 getPageState 直到 running=false（fastSleep 加速）', async () => {
    let calls = 0
    env.cap.cdp.eval = async () => {
      calls++
      // 第 1 次 running=true，之后 running=false
      return JSON.stringify({ u: 1, a: 1, running: calls === 1, txt: '', actionBtns: [], errorMsgs: [] })
    }
    const r = await env.handler({ action: 'wait_idle', timeoutSec: 2 })
    assert.equal(r.running, false)
    assert.ok(calls >= 2)
  })

  test('detect_stuck: 返回 ok:true', async () => {
    const r = await env.handler({ action: 'detect_stuck' })
    assert.equal(r.ok, true)
  })

  test('reset_stuck: 返回 ok:true', async () => {
    const r = await env.handler({ action: 'reset_stuck' })
    assert.equal(r.ok, true)
  })

  test('click_action_buttons: actionBtns 非空 → clicked 非空', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ u: 0, a: 0, running: false, txt: '', actionBtns: ['运行'], errorMsgs: [] })
    const r = await env.handler({ action: 'click_action_buttons' })
    assert.equal(r.clicked, '运行')
  })

  test('click_action_buttons: actionBtns 为空 → clicked:null', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] })
    const r = await env.handler({ action: 'click_action_buttons' })
    assert.equal(r.clicked, null)
  })

  test('summarize_conditions: 调 LLM 总结', async () => {
    env.cap.llm.complete = async () => '1. 规则一\n2. 规则二'
    const r = await env.handler({ action: 'summarize_conditions', conditions: ['c1', 'c2'] })
    assert.equal(r.conditions.length, 2)
  })

  test('check_only: 检查匹配 → 返回 match', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ u: 0, a: 0, running: false, txt: 'AI 回复', actionBtns: [], errorMsgs: [] })
    env.cap.storage.set('trace_auto_conditions', ['条件A'])
    env.cap.llm.complete = async () => '条件0: 匹配'
    const r = await env.handler({ action: 'check_only' })
    assert.ok(r.match !== null)
  })

  test('generate_followup: 调 LLM 生成跟进消息', async () => {
    env.cap.cdp.eval = async () => JSON.stringify({ u: 0, a: 0, running: false, txt: 'AI 回复', actionBtns: [], errorMsgs: [] })
    env.cap.llm.complete = async () => '下一步指令'
    const r = await env.handler({ action: 'generate_followup', goal: '推进任务' })
    assert.equal(r.followup, '下一步指令')
  })
})
