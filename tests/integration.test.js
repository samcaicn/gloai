// integration.test.js — 端到端集成测试
// D.1: 完整链路 search_software → execute → stop → get_trace 回放
// D.2: 控制信号 → execute 行为影响（pause 阻塞 check / stepOnce 单步 / stop 终止）
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
  return loadFullStack()
}

// 注入 cap.runtime.sleep 让它立即返回，避免测试卡在 cap.runtime.sleep(8000) 等地方
function fastSleep(env) {
  env.cap.runtime.sleep = async () => {}
}

// 注入 cap.cdp.eval 模拟页面状态返回（getPageState 调用）
function mockPageState(env, state) {
  env.cap.cdp.eval = async (expr) => {
    if (expr.includes('JSON.stringify({ u:u')) return JSON.stringify(state)
    if (expr.includes('chat-input-v2-input-box-editable') && expr.includes('e.innerText')) return ''
    return '0'
  }
  env.cap.cdp.click = async () => ({ ok: true })
  env.cap.cdp.type = async () => ({ ok: true })
}

// 在控制信号阻塞期间设置一个 watchdog，避免测试卡死
// timeoutMs 后自动 resume/stop，并断言"未在预期时间内完成"
async function withTimeout(promise, ms, msg) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(msg)), ms)
  })
  try {
    return await Promise.race([promise, timeout])
  } finally {
    clearTimeout(timer)
  }
}

// ────────────────────────────────────────────────────────────────────
// D.1 端到端：search_software → execute → stop → get_trace 回放
// ────────────────────────────────────────────────────────────────────
describe('D.1 端到端：search → execute → stop → replay', () => {
  let env

  beforeEach(() => { env = fresh(); fastSleep(env) })

  test('完整链路：search_software 命中 → execute 1 轮 → stop → get_trace 回放', async () => {
    // 1. search_software 命中服务器技能
    env.setFetchImpl(async () => ({
      ok: true,
      json: async () => [{ skill_id: 'trae-cn', name: 'Trae 中文版', version: '1.0' }],
    }))
    const searchResult = await env.handler({
      action: 'search_software',
      softwareName: 'Trae',
      softwareNameEn: 'Trae',
    })
    assert.equal(searchResult.ok, true)
    assert.equal(searchResult.executable, true)
    assert.equal(searchResult.skills.length, 1)

    // 2. execute 跑 1 轮，maxRounds=1，正常完成
    mockPageState(env, {
      u: 1, a: 1, running: false, txt: 'AI 已生成回复内容',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [{ tier: 'cdp', ok: true, ms: 0, note: '' }],
    })
    env.cap.llm.complete = async () => '跟进消息'

    const executeResult = await env.handler({
      action: 'execute',
      goal: '推进任务',
      maxRounds: 1,
    }, null)
    assert.equal(executeResult.ok, true)
    assert.equal(executeResult.status, 'completed')
    assert.equal(executeResult.rounds, 1)
    assert.ok(executeResult.flowchart)
    assert.ok(executeResult.trace.length > 0)

    // 3. get_trace 回放：trace 包含 start / ensure / read / loop / end
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
    const nodeIds = trace.map((t) => t.nodeId)
    assert.ok(nodeIds.includes('start'))
    assert.ok(nodeIds.includes('ensure'))
    assert.ok(nodeIds.includes('read'))
    assert.ok(nodeIds.includes('end'))

    // 4. get_flowchart 返回完整流程图（含 judgments）
    const fc = await env.handler({ action: 'get_flowchart' })
    assert.ok(fc.nodes.length === 11)
    assert.ok(fc.connections.length === 13)
    assert.ok(fc.judgments.length === 3)
  })

  test('完整链路：search_software 未命中 → 走 fallback → execute 仍能跑（fallback trace-auto 自身）', async () => {
    // 1. search_software 服务器返回空 → 走 fallback 返回 trace-auto 自身
    env.setFetchImpl(async () => ({ ok: true, json: async () => [] }))
    const searchResult = await env.handler({
      action: 'search_software',
      softwareName: '不存在的软件',
    })
    assert.equal(searchResult.ok, true)
    assert.equal(searchResult.fallback, true)
    assert.equal(searchResult.executable, true)
    assert.equal(searchResult.skills[0].skill_id, 'trace-auto')

    // 2. 用 fallback 的 trace-auto 自身 execute
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    const executeResult = await env.handler({
      action: 'execute',
      goal: 'g',
      maxRounds: 1,
    }, null)
    assert.equal(executeResult.status, 'completed')
  })

  test('trace 记录格式：每条 trace 含 nodeId / status / ts / ms / note 字段', async () => {
    // 跑一次 execute 让 trace 有内容
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
    // 每条 trace 必须含 nodeId / status / ts / ms / note
    for (const t of trace) {
      assert.ok(typeof t.nodeId === 'string', `trace.nodeId 必须是 string: ${JSON.stringify(t)}`)
      assert.ok(typeof t.status === 'string', `trace.status 必须是 string: ${JSON.stringify(t)}`)
      assert.ok(typeof t.ts !== 'undefined', `trace.ts 必须存在: ${JSON.stringify(t)}`)
      assert.ok(typeof t.ms !== 'undefined', `trace.ms 必须存在: ${JSON.stringify(t)}`)
      // note 可以是空字符串，但字段必须存在
      assert.ok('note' in t, `trace.note 必须存在: ${JSON.stringify(t)}`)
    }
  })

  test('trace 顺序符合流程图执行顺序：start → ensure → read → ... → end', async () => {
    mockPageState(env, {
      u: 1, a: 1, running: false, txt: 'AI 回复',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    const trace = await env.handler({ action: 'get_trace' })
    const nodeIds = trace.map((t) => t.nodeId)
    // start 必须是第一条
    assert.equal(nodeIds[0], 'start')
    // ensure 必须在 read 之前
    const ensureIdx = nodeIds.indexOf('ensure')
    const readIdx = nodeIds.indexOf('read')
    assert.ok(ensureIdx >= 0 && readIdx >= 0)
    assert.ok(ensureIdx < readIdx, `ensure(${ensureIdx}) 应在 read(${readIdx}) 之前`)
    // end 必须是最后一条
    assert.equal(nodeIds[nodeIds.length - 1], 'end')
  })

  test('execute 失败回退：CDP 未连接 → flowchart 仍可回放（含 fail 节点）', async () => {
    // mock cap.cdp.eval 返回 '0' 让 recognize.chain 失败
    env.cap.cdp.eval = async () => '0'
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
    assert.equal(r.ok, false)
    assert.match(r.error, /IDE 未连接/)
    // 仍能 get_flowchart 与 get_trace
    const fc = await env.handler({ action: 'get_flowchart' })
    assert.ok(fc.nodes)
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
    // trace 含 end 节点 status=fail
    const endEntry = trace.find((t) => t.nodeId === 'end')
    assert.ok(endEntry)
    assert.equal(endEntry.status, 'fail')
    assert.match(endEntry.note, /CDP 未连接/)
  })

  test('record → stop → get_trace 回放：录制模式 trace 含 start 节点', async () => {
    // record 是占位实现：调用 cap.cdp.startRecording（若注入）
    let recordingStarted = false
    env.cap.cdp.startRecording = async () => { recordingStarted = true; return { ok: true } }
    const r = await env.handler({ action: 'record', softwareName: 'Trae' }, null)
    assert.equal(r.ok, true)
    assert.equal(r.mode, 'record')
    assert.equal(recordingStarted, true)
    // stop 录制（trace-auto 没有显式 stop record 动作，模拟用户关闭浮窗）
    await env.handler({ action: 'stop' })
    // get_trace 回放
    const trace = await env.handler({ action: 'get_trace' })
    assert.ok(trace.length > 0)
    assert.equal(trace[0].nodeId, 'start')
    assert.equal(trace[0].status, 'ok')
    assert.match(trace[0].note, /record mode/)
  })

  test('search → execute → get_judgments：返回的 judgments 与 flowchart.json 一致', async () => {
    // 服务器返回带 flowchart 的 skill（含 judgments）
    env.setFetchImpl(async (url) => {
      if (url.includes('/search')) {
        return { ok: true, json: async () => [{ skill_id: 's1', name: 'X', version: '1.0' }] }
      }
      return { ok: false }
    })
    const searchResult = await env.handler({
      action: 'search_software',
      softwareName: 'X',
    })
    assert.equal(searchResult.executable, true)

    // execute 后 get_judgments 返回内置 FLOWCHART 的 judgments
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)

    const judgments = await env.handler({ action: 'get_judgments' })
    assert.equal(judgments.length, 3)
    const ids = judgments.map((j) => j.id).sort()
    jsonEqual(ids, ['J1', 'J2', 'J3'])
    // 每条 judgment 的 node 字段对应 flowchart 中的 decision 节点
    const decisionIds = (await env.handler({ action: 'get_flowchart' }))
      .nodes.filter((n) => n.type === 'decision').map((n) => n.id)
    for (const j of judgments) {
      assert.ok(decisionIds.includes(j.node), `judgment "${j.id}" 的 node "${j.node}" 不在 decision 节点中`)
    }
  })
})

// ────────────────────────────────────────────────────────────────────
// D.2 控制信号 → execute 行为影响
// ────────────────────────────────────────────────────────────────────
describe('D.2 控制信号 → execute 行为影响', () => {
  let env

  beforeEach(() => { env = fresh(); fastSleep(env) })

  test('stop 信号让 execute 在循环顶部 check 时退出，返回 status=stopped', async () => {
    mockPageState(env, {
      u: 1, a: 1, running: false, txt: 'AI 回复',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    // execute 入口 cap.control.reset() 会清掉 stopRequested；mock reset 为空操作保留信号
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      await env.handler({ action: 'stop' })
      const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
      assert.equal(r.rounds, 0)
    } finally {
      env.cap.control.reset = origReset
    }
  })

  test('stop 信号在 execute 进入循环后发出 → execute 在下一轮 check 退出', async () => {
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: 'AI 回复',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    // 让 cap.llm.complete 在第一次调用时发 stop（在 loop 节点 LLM 调用后）
    let llmCall = 0
    env.cap.llm.complete = async () => {
      llmCall++
      if (llmCall === 1) {
        // 第一轮 loop 节点 LLM 调用完毕后发 stop
        env.handler({ action: 'stop' })
      }
      return 'msg'
    }
    const r = await env.handler({ action: 'execute', goal: 'g', maxRounds: 50 }, null)
    assert.equal(r.ok, true)
    assert.equal(r.status, 'stopped')
    assert.ok(r.rounds >= 1, `rounds 应该 >=1（至少跑完一轮）: ${r.rounds}`)
  })

  test('pause 阻塞 check：execute 进入循环顶部时被 pause 阻塞', async () => {
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    // execute 入口 cap.control.reset() 会清掉 paused；mock reset 为空操作保留信号
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      await env.handler({ action: 'pause' })
      // 启动 execute（fastSleep 让 sleep 立即返回，但 check 会阻塞在 paused=true）
      const p = env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
      // 等一小会，验证 execute 仍未完成（被 pause 阻塞）
      const result = await Promise.race([
        p.then((r) => ({ done: true, r })),
        sleep(80).then(() => ({ done: false })),
      ])
      assert.equal(result.done, false, 'execute 应被 pause 阻塞')
      // resume 唤醒后立即 stop（避免 execute 跑到 promptUser 触发 CustomEvent 错误）
      await env.handler({ action: 'resume' })
      await env.handler({ action: 'stop' })
      const r = await withTimeout(p, 2000, 'execute 超时未完成')
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
    } finally {
      env.cap.control.reset = origReset
    }
  })

  test('stepOnce 单步：每调一次 stepOnce 让 execute 推进一个 check 节点', async () => {
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    // execute 入口 cap.control.reset() 会清掉 stepOnce 信号；mock reset 为空操作保留
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      await env.handler({ action: 'pause' })
      // 启动 execute（被 pause 阻塞）
      const p = env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
      // 验证阻塞中
      const before = await Promise.race([
        p.then((r) => ({ done: true, r })),
        sleep(50).then(() => ({ done: false })),
      ])
      assert.equal(before.done, false)
      // stepOnce 一次，让 execute 推进一个 check 节点
      await env.handler({ action: 'step_once' })
      // 应该又被卡住（stepOnce 后 paused=true）
      const mid = await Promise.race([
        p.then((r) => ({ done: true, r })),
        sleep(50).then(() => ({ done: false })),
      ])
      assert.equal(mid.done, false, 'stepOnce 后应再次阻塞')
      // stop 退出
      await env.handler({ action: 'stop' })
      const r = await withTimeout(p, 2000, 'execute 超时未完成')
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
    } finally {
      env.cap.control.reset = origReset
    }
  })

  test('断点 pause: 添加 read 节点断点 → execute 走到 read 时阻塞', async () => {
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    // 添加 read 断点
    await env.handler({ action: 'add_breakpoint', nodeId: 'read' })
    assert.equal(env.cap.control.hasBreakpoint('read'), true)
    // execute 入口 cap.control.reset() 会清掉断点；mock reset 为空操作保留断点
    // TODO（被测代码设计建议）：reset 应区分"清控制信号"与"清断点"，建议拆成 resetControl + clearBreakpoints
    //   现有设计下，断点只能在 execute 启动后通过 add_breakpoint action 添加
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      // 启动 execute（在 read 节点应被断点阻塞）
      const p = env.handler({ action: 'execute', goal: 'g', maxRounds: 5 }, null)
      // 验证阻塞在 read 节点
      const before = await Promise.race([
        p.then((r) => ({ done: true, r })),
        sleep(80).then(() => ({ done: false })),
      ])
      assert.equal(before.done, false, 'execute 应在 read 节点被断点阻塞')
      // resume 唤醒后立即 stop（避免 execute 跑到 promptUser 触发 CustomEvent 错误）
      await env.handler({ action: 'resume' })
      await env.handler({ action: 'stop' })
      const r = await withTimeout(p, 2000, 'execute 超时未完成')
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
    } finally {
      env.cap.control.reset = origReset
    }
  })

  test('stop 后再 execute（reset 清掉 stop 信号）→ 新一次 execute 正常跑', async () => {
    mockPageState(env, {
      u: 0, a: 0, running: false, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    // 第一次 execute（maxRounds=1）正常完成
    const r1 = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    assert.equal(r1.status, 'completed')
    // stop（不应该影响下一次 execute，因为 reset 会清掉）
    await env.handler({ action: 'stop' })
    assert.equal(env.cap.control.isStopRequested(), true)
    // 第二次 execute（reset 后应正常跑）
    const r2 = await env.handler({ action: 'execute', goal: 'g', maxRounds: 1 }, null)
    assert.equal(r2.status, 'completed')
    assert.equal(env.cap.control.isStopRequested(), false)
  })

  test('控制信号 singleton：多个 handler 调用共享同一个 _controlState', async () => {
    // pause / stop / stepOnce 都通过 handler 调 cap.control.*；reset 直接调 cap.control.reset()
    // 注：trace-auto handler 没暴露 reset 动作，所以 reset 只能通过 cap.control.reset() 直接调
    // TODO（被测代码缺漏）：trace-auto handler 应补 `action === 'reset'` 分支，否则前端无法通过 gateway 触发 reset
    await env.handler({ action: 'pause' })
    assert.equal(env.cap.control.isPaused(), true)
    await env.handler({ action: 'step_once' })
    // stepOnce 后 paused 应为 false（被 stepOnce 唤醒），但下次 check 后又变 true
    assert.equal(env.cap.control.isPaused(), false)
    await env.handler({ action: 'stop' })
    assert.equal(env.cap.control.isStopRequested(), true)
    assert.equal(env.cap.control.isPaused(), false)
    env.cap.control.reset()
    assert.equal(env.cap.control.isPaused(), false)
    assert.equal(env.cap.control.isStopRequested(), false)
  })

  test('断点 singleton：add/remove/clear 跨 handler 调用共享状态', async () => {
    await env.handler({ action: 'add_breakpoint', nodeId: 'a' })
    await env.handler({ action: 'add_breakpoint', nodeId: 'b' })
    assert.equal(env.cap.control.hasBreakpoint('a'), true)
    assert.equal(env.cap.control.hasBreakpoint('b'), true)
    await env.handler({ action: 'remove_breakpoint', nodeId: 'a' })
    assert.equal(env.cap.control.hasBreakpoint('a'), false)
    assert.equal(env.cap.control.hasBreakpoint('b'), true)
    await env.handler({ action: 'clear_breakpoints' })
    assert.equal(env.cap.control.hasBreakpoint('b'), false)
  })

  test('execute 中 waitIdle 循环检测 stop 信号 → check 返回 false → execute 退出 stopped', async () => {
    // running=true 让 execute 走 wait 分支，waitIdle 内每轮调 check() 检测 stop
    // 用 stop 在 waitIdle 内唤醒：先 mock reset 为空操作保留 stop 信号
    mockPageState(env, {
      u: 1, a: 1, running: true, txt: '',
      actionBtns: [], errorMsgs: [],
    })
    env.cap.recognize.chain = async () => ({
      ok: true, tier: 'cdp', value: '1', trace: [],
    })
    env.cap.llm.complete = async () => 'msg'
    const origReset = env.cap.control.reset
    env.cap.control.reset = () => {}
    try {
      // 预先 stop（让 waitIdle 内的 check 立刻返回 false）
      await env.handler({ action: 'stop' })
      const r = await env.handler({
        action: 'execute',
        goal: 'g',
        maxRounds: 5,
        idleTimeoutSec: 100,
      }, null)
      assert.equal(r.ok, true)
      assert.equal(r.status, 'stopped')
    } finally {
      env.cap.control.reset = origReset
    }
  })
})
