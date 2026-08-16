// capabilities.test.js — 测试 6 大 cap 能力层（cap.server / cap.recognize / cap.vlm / cap.control / cap.flowchart / cap.skillMarket）
// 所有代码注释用中文。
// 注意：不修改被测代码，若发现 bug 在测试注释里标 TODO。

import { test, describe, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { setupSandbox, loadCapabilities, sleep } from './_helper.js'

// 每个测试用例独立加载一份 capabilities.js，避免模块级私有状态（如 _installedSkills Map）串扰
function fresh() {
  const env = setupSandbox()
  loadCapabilities(env.sandbox)
  // capabilities.js 内部用 `const cap = {}` 重新赋值，sandbox.cap 已是新对象；
  // 重新从 sandbox 取，避免 env.cap 仍指向最初的空占位
  env.cap = env.sandbox.cap
  return env
}

// 跨 vm context 的对象比较：JSON.stringify 后比较
// 避免不同 vm context 创建的 Array/Object 实例在 assert.deepEqual 下被认为结构不同
function jsonEqual(actual, expected) {
  assert.equal(JSON.stringify(actual), JSON.stringify(expected))
}

// 注入完整的 cap.storage._impl，让 keys() 不带前缀过滤（默认实现只返回 'trace_' 前缀）
// 用于绕过被测代码 storage.keys() 的过滤行为，验证 skillMarket.rollback 的真实逻辑
function injectFullStorageImpl(env) {
  env.cap.storage._impl = {
    get: (k, def) => (k in env.store ? JSON.parse(env.store[k]) : def),
    set: (k, v) => { env.store[k] = JSON.stringify(v) },
    getRaw: (k) => env.store[k] || '',
    setRaw: (k, v) => { env.store[k] = String(v) },
    append: (k, item) => {
      const arr = env.store[k] ? JSON.parse(env.store[k]) : []
      arr.push(item)
      env.store[k] = JSON.stringify(arr)
    },
    delete: (k) => { delete env.store[k] },
    keys: () => Object.keys(env.store),
  }
}

// ────────────────────────────────────────────────────────────────────
// A.1 cap.server — 服务器侧 API 封装
// ────────────────────────────────────────────────────────────────────
describe('A.1 cap.server 服务器侧 API 封装', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('searchSkills: 服务器返回技能列表时返回数组', async () => {
    // mock fetch 返回 ok=true + 技能数组
    env.setFetchImpl(async (url) => ({
      ok: true,
      json: async () => [{ skill_id: 's1', name: 'Trae助手', version: '1.0' }],
    }))
    const r = await env.cap.server.searchSkills('trae')
    assert.ok(Array.isArray(r))
    assert.equal(r.length, 1)
    assert.equal(r[0].skill_id, 's1')
  })

  test('searchSkills: 服务器返回 404 (r.ok=false) 时返回 []', async () => {
    // 默认 fetch impl 返回 ok=false，模拟 404
    const r = await env.cap.server.searchSkills('trae')
    assert.equal(Array.isArray(r) && r.length, 0, true)
  })

  test('searchSkills: _impl 注入时走 _impl（不走 fetch）', async () => {
    let fetchCalled = false
    env.setFetchImpl(async () => { fetchCalled = true; return { ok: true, json: async () => [] } })
    env.cap.server._impl = {
      searchSkills: async (q, opts) => [{ skill_id: 'impl-1', query: q, opts }],
    }
    const r = await env.cap.server.searchSkills('xx', { softwareName: 'XX' })
    assert.equal(fetchCalled, false)
    assert.equal(r.length, 1)
    assert.equal(r[0].skill_id, 'impl-1')
    assert.equal(r[0].query, 'xx')
  })

  test('searchSkills: query+opts 拼接成 URL 查询参数', async () => {
    let capturedUrl = ''
    env.setFetchImpl(async (url) => {
      capturedUrl = url
      return { ok: true, json: async () => [] }
    })
    await env.cap.server.searchSkills('trae', { softwareName: 'Trae', softwareNameEn: 'Trae', category: 'ide', page: 1, pageSize: 20 })
    // 断言 URL 包含所有参数
    assert.match(capturedUrl, /q=trae/)
    assert.match(capturedUrl, /softwareName=Trae/)
    assert.match(capturedUrl, /softwareNameEn=Trae/)
    assert.match(capturedUrl, /category=ide/)
    assert.match(capturedUrl, /page=1/)
    assert.match(capturedUrl, /pageSize=20/)
  })

  test('getSkillDetail: 服务器返回详情时返回对象；失败返回 null', async () => {
    env.setFetchImpl(async () => ({ ok: true, json: async () => ({ id: 's1', name: 'N' }) }))
    const r = await env.cap.server.getSkillDetail('s1')
    assert.equal(r.id, 's1')
    // 切回失败
    env.setFetchImpl(async () => ({ ok: false }))
    const r2 = await env.cap.server.getSkillDetail('s1')
    assert.equal(r2, null)
  })

  test('getSkillDetail: _impl 注入时走 _impl', async () => {
    env.cap.server._impl = { getSkillDetail: async (id) => ({ id, from: 'impl' }) }
    const r = await env.cap.server.getSkillDetail('xyz')
    assert.equal(r.from, 'impl')
    assert.equal(r.id, 'xyz')
  })

  test('getFlowchart: 拼接 path + version 查询参数', async () => {
    let capturedUrl = ''
    env.setFetchImpl(async (url) => {
      capturedUrl = url
      return { ok: true, json: async () => ({ nodes: [] }) }
    })
    const r = await env.cap.server.getFlowchart('my-skill', '2.0')
    assert.equal(r.nodes !== undefined, true)
    assert.match(capturedUrl, /\/api\/v1\/skills\/market\/my-skill\/flowchart/)
    assert.match(capturedUrl, /version=2\.0/)
  })

  test('downloadPackage: 失败时返回 null', async () => {
    const r = await env.cap.server.downloadPackage('s1', '1.0')
    assert.equal(r, null)
  })

  test('downloadPackage: _impl 注入时走 _impl', async () => {
    env.cap.server._impl = { downloadPackage: async (id, v) => ({ id, v, data: 'blob' }) }
    const r = await env.cap.server.downloadPackage('s1', '1.0')
    assert.equal(r.data, 'blob')
  })

  test('reportRun: POST trace，失败返回 null', async () => {
    let captured = null
    env.setFetchImpl(async (url, opts) => {
      captured = { url, body: JSON.parse(opts.body) }
      return { ok: true, json: async () => ({ ok: true }) }
    })
    const r = await env.cap.server.reportRun([{ nodeId: 'x', status: 'ok' }])
    assert.equal(r.ok, true)
    assert.match(captured.url, /\/api\/v1\/runs\/trace/)
    assert.equal(captured.body.trace.length, 1)
  })

  test('reportUpgrade: POST 升级报告，含 skillId/fromVersion/toVersion/ok/error', async () => {
    let captured = null
    env.setFetchImpl(async (url, opts) => {
      captured = { url, body: JSON.parse(opts.body) }
      return { ok: true, json: async () => ({ received: true }) }
    })
    const r = await env.cap.server.reportUpgrade('s1', '1.0', '2.0', true, null)
    assert.equal(r.received, true)
    assert.equal(captured.body.skillId, 's1')
    assert.equal(captured.body.fromVersion, '1.0')
    assert.equal(captured.body.toVersion, '2.0')
    assert.equal(captured.body.ok, true)
    assert.equal(captured.body.error, null)
  })

  test('getLatestVersion: 失败返回 null；成功返回远端版本元数据', async () => {
    env.setFetchImpl(async () => ({ ok: true, json: async () => ({ version: '3.0', changelog: 'big' }) }))
    const r = await env.cap.server.getLatestVersion('s1')
    assert.equal(r.version, '3.0')
    assert.equal(r.changelog, 'big')
    env.setFetchImpl(async () => ({ ok: false }))
    const r2 = await env.cap.server.getLatestVersion('s1')
    assert.equal(r2, null)
  })
})

// ────────────────────────────────────────────────────────────────────
// A.2 cap.recognize — 多层识别降级链
// ────────────────────────────────────────────────────────────────────
describe('A.2 cap.recognize 多层识别降级链', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('chain: cdp 命中 → 不走后续 tier，trace 含 1 条', async () => {
    // mock cap.cdp.eval 返回 '1'（capabilities.js 的 _recognizeCdp 接受 '1'/'true'/1/true 视为 ok）
    let evalCalls = 0
    env.cap.cdp.eval = async () => { evalCalls++; return '1' }
    const r = await env.cap.recognize.chain({ kind: 'element_visible', selector: 'body' })
    assert.equal(r.ok, true)
    assert.equal(r.tier, 'cdp')
    assert.equal(r.trace.length, 1)
    assert.equal(r.trace[0].tier, 'cdp')
    assert.equal(r.trace[0].ok, true)
    assert.equal(evalCalls, 1)
  })

  test('chain: cdp 失败 → uia 命中，trace 含 2 条', async () => {
    env.cap.cdp.eval = async () => '0'   // cdp 不命中
    env.cap.uia._impl = {
      find: async () => ({ id: 'el-1' }),  // uia 命中
      getText: async () => 'text',
      click: async () => null,
      type: async () => null,
    }
    const r = await env.cap.recognize.chain({ kind: 'element_visible', selector: '.btn' })
    assert.equal(r.ok, true)
    assert.equal(r.tier, 'uia')
    assert.equal(r.trace.length, 2)
    assert.equal(r.trace[0].tier, 'cdp')
    assert.equal(r.trace[0].ok, false)
    assert.equal(r.trace[1].tier, 'uia')
    assert.equal(r.trace[1].ok, true)
  })

  test('chain: 全部失败 → 返回 { ok: false, tier: null }', async () => {
    env.cap.cdp.eval = async () => '0'   // cdp 不命中
    // uia / ocr / vlm 未注入，run 返回 ok:false
    const r = await env.cap.recognize.chain({ kind: 'element_visible', selector: '.btn' })
    assert.equal(r.ok, false)
    assert.equal(r.tier, null)
    assert.equal(r.value, null)
    assert.equal(r.trace.length, 4)   // 默认 ['cdp','uia','ocr','vlm']
    for (const t of r.trace) assert.equal(t.ok, false)
  })

  test('chain: tiers 参数覆盖默认顺序', async () => {
    let evalCalls = 0
    env.cap.cdp.eval = async () => { evalCalls++; return '1' }
    const r = await env.cap.recognize.chain(
      { kind: 'element_visible', selector: 'body' },
      ['vlm', 'cdp']   // 先 vlm 再 cdp
    )
    // vlm 未注入 → ok:false，然后 cdp 命中
    assert.equal(r.ok, true)
    assert.equal(r.tier, 'cdp')
    assert.equal(r.trace.length, 2)
    assert.equal(r.trace[0].tier, 'vlm')
    assert.equal(r.trace[0].ok, false)
    assert.equal(r.trace[1].tier, 'cdp')
    assert.equal(r.trace[1].ok, true)
  })

  test('run: 每个 tier 的 dispatch 正确（cdp/uia/ocr/vlm/未知）', async () => {
    // cdp
    env.cap.cdp.eval = async () => '1'
    const r1 = await env.cap.recognize.run('cdp', { kind: 'element_visible', selector: 'body' })
    assert.equal(r1.ok, true)
    // uia 未注入 → ok:false
    const r2 = await env.cap.recognize.run('uia', { kind: 'element_visible', selector: 'body' })
    assert.equal(r2.ok, false)
    assert.match(r2.note, /uia not available/)
    // ocr 未注入
    const r3 = await env.cap.recognize.run('ocr', { kind: 'text_present', text: 'x' })
    assert.equal(r3.ok, false)
    assert.match(r3.note, /ocr not available/)
    // vlm 未注入
    const r4 = await env.cap.recognize.run('vlm', { kind: 'image_understand' })
    assert.equal(r4.ok, false)
    assert.match(r4.note, /vlm not available/)
    // 未知 tier
    const r5 = await env.cap.recognize.run('unknown', {})
    assert.equal(r5.ok, false)
    assert.match(r5.note, /unknown tier/)
  })

  test('_recognizeCdp: text_present 命中/不命中', async () => {
    env.cap.cdp.eval = async () => 'true'
    const hit = await env.cap.recognize._recognizeCdp({ kind: 'text_present', text: 'hello' })
    assert.equal(hit.ok, true)
    env.cap.cdp.eval = async () => 'false'
    const miss = await env.cap.recognize._recognizeCdp({ kind: 'text_present', text: 'hello' })
    assert.equal(miss.ok, false)
  })

  test('_recognizeCdp: element_visible 命中/不命中', async () => {
    env.cap.cdp.eval = async () => 1   // capabilities.js 接受 1
    const hit = await env.cap.recognize._recognizeCdp({ kind: 'element_visible', selector: '.x' })
    assert.equal(hit.ok, true)
    env.cap.cdp.eval = async () => 0
    const miss = await env.cap.recognize._recognizeCdp({ kind: 'element_visible', selector: '.x' })
    assert.equal(miss.ok, false)
  })

  test('_recognizeCdp: element_attribute 命中/空值不命中', async () => {
    env.cap.cdp.eval = async () => 'my-class'
    const hit = await env.cap.recognize._recognizeCdp({ kind: 'element_attribute', selector: '.x', attribute: 'class' })
    assert.equal(hit.ok, true)
    assert.equal(hit.value, 'my-class')
    env.cap.cdp.eval = async () => null
    const miss = await env.cap.recognize._recognizeCdp({ kind: 'element_attribute', selector: '.x', attribute: 'class' })
    assert.equal(miss.ok, false)
  })

  test('_recognizeCdp: 不支持的 kind → ok:false', async () => {
    const r = await env.cap.recognize._recognizeCdp({ kind: 'unsupported_kind' })
    assert.equal(r.ok, false)
    assert.match(r.note, /unsupported kind/)
  })

  test('_recognizeUia: _impl 未注入时返回 ok:false', async () => {
    const r = await env.cap.recognize._recognizeUia({ kind: 'element_visible', selector: 'body' })
    assert.equal(r.ok, false)
    assert.match(r.note, /uia not available/)
  })

  test('_recognizeUia: _impl 注入时 find 命中 element_visible', async () => {
    env.cap.uia._impl = { find: async () => ({ id: 'el' }) }
    const r = await env.cap.recognize._recognizeUia({ kind: 'element_visible', selector: '.x' })
    assert.equal(r.ok, true)
  })

  test('_recognizeUia: _impl 注入时 element_attribute 走 getText', async () => {
    env.cap.uia._impl = { find: async () => ({ id: 'el' }), getText: async () => 'hello' }
    const r = await env.cap.recognize._recognizeUia({ kind: 'element_attribute', selector: '.x' })
    assert.equal(r.ok, true)
    assert.equal(r.value, 'hello')
  })

  test('_recognizeUia: text_present 走 find({text})', async () => {
    let captured = null
    env.cap.uia._impl = { find: async (cond) => { captured = cond; return { id: 'el' } }, getText: async () => '' }
    const r = await env.cap.recognize._recognizeUia({ kind: 'text_present', text: 'submit' })
    assert.equal(r.ok, true)
    assert.equal(captured.text, 'submit')
  })

  test('_recognizeOcr: _impl 未注入时返回 ok:false', async () => {
    const r = await env.cap.recognize._recognizeOcr({ kind: 'text_present', text: 'x' })
    assert.equal(r.ok, false)
    assert.match(r.note, /ocr not available/)
  })

  test('_recognizeOcr: _impl 注入时 readText 命中 text_present', async () => {
    env.cap.ocr._impl = { readText: async () => 'hello world' }
    const r = await env.cap.recognize._recognizeOcr({ kind: 'text_present', text: 'hello' })
    assert.equal(r.ok, true)
    assert.equal(r.value, 'hello world')
    const r2 = await env.cap.recognize._recognizeOcr({ kind: 'text_present', text: 'foo' })
    assert.equal(r2.ok, false)
  })

  test('_recognizeVlm: 走 cap.vlm.ask，返回非空即 ok', async () => {
    env.cap.vlm.register({ ask: async () => '是的，存在该元素' })
    const r = await env.cap.recognize._recognizeVlm({ kind: 'text_present', text: '提交' })
    assert.equal(r.ok, true)
    assert.equal(r.value, '是的，存在该元素')
  })

  test('_recognizeVlm: 未注入时返回 ok:false', async () => {
    const r = await env.cap.recognize._recognizeVlm({ kind: 'text_present', text: 'x' })
    assert.equal(r.ok, false)
    assert.match(r.note, /vlm not available/)
  })

  test('register: 注入 tier 实现，chain 优先用注入的', async () => {
    env.cap.recognize.register('cdp', {
      find: async (task) => ({ ok: true, value: 'injected', note: 'from-impl' }),
    })
    const r = await env.cap.recognize.chain({ kind: 'element_visible', selector: 'body' }, ['cdp'])
    assert.equal(r.ok, true)
    assert.equal(r.value, 'injected')
    assert.equal(r.trace[0].note, 'from-impl')
  })

  test('chain: tier 抛异常时记 trace 并继续下一个 tier', async () => {
    env.cap.recognize.register('cdp', { find: async () => { throw new Error('boom') } })
    env.cap.recognize.register('uia', { find: async () => ({ ok: true, value: 'ok-from-uia' }) })
    const r = await env.cap.recognize.chain({ kind: 'element_visible', selector: 'body' }, ['cdp', 'uia'])
    assert.equal(r.ok, true)
    assert.equal(r.tier, 'uia')
    assert.equal(r.trace.length, 2)
    assert.equal(r.trace[0].ok, false)
    assert.match(r.trace[0].note, /error: boom/)
  })
})

// ────────────────────────────────────────────────────────────────────
// A.3 cap.vlm — 视觉语言模型
// ────────────────────────────────────────────────────────────────────
describe('A.3 cap.vlm 视觉语言模型', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('ask: _impl 已注入时走 _impl', async () => {
    let captured
    env.cap.vlm.register({
      ask: async (question, image) => {
        captured = { question, image }
        return 'VLM 回复'
      },
    })
    const r = await env.cap.vlm.ask('屏幕上有什么？', 'base64-png')
    assert.equal(r, 'VLM 回复')
    assert.equal(captured.question, '屏幕上有什么？')
    assert.equal(captured.image, 'base64-png')
  })

  test('ask: _impl 未注入时后备走 cap.llm.complete', async () => {
    // mock cap.llm.complete，让它返回一段文本
    let captured
    env.cap.llm.complete = async (messages, opts) => {
      captured = { messages, opts }
      return 'LLM 兜底回复'
    }
    const r = await env.cap.vlm.ask('屏幕上有什么？')
    assert.equal(r, 'LLM 兜底回复')
    assert.ok(Array.isArray(captured.messages))
    assert.equal(captured.messages[0].content, '屏幕上有什么？')
  })

  test('ask: _impl 未注入 + 带 image 时构造多模态 messages', async () => {
    let captured
    env.cap.llm.complete = async (messages) => {
      captured = messages
      return 'desc'
    }
    await env.cap.vlm.ask('描述这张图', 'data:image/png;base64,xxx')
    assert.ok(Array.isArray(captured[0].content))
    assert.equal(captured[0].content[0].type, 'text')
    assert.equal(captured[0].content[0].text, '描述这张图')
    assert.equal(captured[0].content[1].type, 'image_url')
    assert.equal(captured[0].content[1].image_url.url, 'data:image/png;base64,xxx')
  })

  test('ask: _impl 未注入 + cap.llm 抛异常 → 返回空字符串（优雅降级）', async () => {
    env.cap.llm.complete = async () => { throw new Error('network') }
    const r = await env.cap.vlm.ask('x')
    assert.equal(r, '')
  })

  test('describeScreen: 调 screenshot + ask', async () => {
    let screenshotCalled = false
    let askCaptured
    env.cap.cdp.screenshot = async () => { screenshotCalled = true; return 'data:image/png;base64,abc' }
    env.cap.vlm.register({ ask: async (q, img) => { askCaptured = { q, img }; return '描述' } })
    const r = await env.cap.vlm.describeScreen()
    assert.equal(r, '描述')
    assert.equal(screenshotCalled, true)
    assert.match(askCaptured.q, /描述/)
    assert.equal(askCaptured.img, 'data:image/png;base64,abc')
  })

  test('findTarget: 调 ask（带描述）', async () => {
    let captured
    env.cap.vlm.register({ ask: async (q) => { captured = q; return '目标位置(100,200)' } })
    const r = await env.cap.vlm.findTarget('登录按钮')
    assert.match(captured, /登录按钮/)
    assert.equal(r, '目标位置(100,200)')
  })

  test('register: 注入后 _available=true', () => {
    assert.equal(env.cap.vlm._available, false)
    env.cap.vlm.register({ ask: async () => 'x' })
    assert.equal(env.cap.vlm._available, true)
  })
})

// ────────────────────────────────────────────────────────────────────
// A.4 cap.control — 执行控制信号
// ────────────────────────────────────────────────────────────────────
describe('A.4 cap.control 执行控制信号', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('pause + isPaused = true', () => {
    assert.equal(env.cap.control.isPaused(), false)
    env.cap.control.pause()
    assert.equal(env.cap.control.isPaused(), true)
  })

  test('resume + isPaused = false', () => {
    env.cap.control.pause()
    env.cap.control.resume()
    assert.equal(env.cap.control.isPaused(), false)
  })

  test('stepOnce + check 立即返回 true（不阻塞）', async () => {
    env.cap.control.stepOnce()
    const r = await env.cap.control.check('n1')
    assert.equal(r, true)
    // stepOnce 后会自动保持 paused 状态
    assert.equal(env.cap.control.isPaused(), true)
  })

  test('stop + check 返回 false', async () => {
    env.cap.control.stop()
    const r = await env.cap.control.check('n1')
    assert.equal(r, false)
    assert.equal(env.cap.control.isStopRequested(), true)
  })

  test('reset 清空所有信号（pause/stop/breakpoints）', () => {
    env.cap.control.pause()
    env.cap.control.stop()
    env.cap.control.addBreakpoint('n1')
    env.cap.control.reset()
    assert.equal(env.cap.control.isPaused(), false)
    assert.equal(env.cap.control.isStopRequested(), false)
    assert.equal(env.cap.control.hasBreakpoint('n1'), false)
  })

  test('断点：addBreakpoint / removeBreakpoint / hasBreakpoint / clearBreakpoints', () => {
    env.cap.control.addBreakpoint('n1')
    env.cap.control.addBreakpoint('n2')
    assert.equal(env.cap.control.hasBreakpoint('n1'), true)
    assert.equal(env.cap.control.hasBreakpoint('n2'), true)
    assert.equal(env.cap.control.hasBreakpoint('n3'), false)
    env.cap.control.removeBreakpoint('n1')
    assert.equal(env.cap.control.hasBreakpoint('n1'), false)
    env.cap.control.clearBreakpoints()
    assert.equal(env.cap.control.hasBreakpoint('n2'), false)
  })

  test('check 命中断点 → 自动暂停 → 阻塞，stepOnce 唤醒后返回 true', async () => {
    env.cap.control.addBreakpoint('bp-node')
    let resolved
    // 注意：不能链 .then(r => { resolved = r }) 因为不返回会让 await p 拿到 undefined
    const p = env.cap.control.check('bp-node')
    p.then((r) => { resolved = r })
    // 等待一会让 check 进入阻塞循环
    await sleep(50)
    assert.equal(resolved, undefined)   // 仍阻塞中（p 未 resolve）
    assert.equal(env.cap.control.isPaused(), true)   // 自动暂停
    // stepOnce 唤醒
    env.cap.control.stepOnce()
    const r = await p
    assert.equal(r, true)
    assert.equal(resolved, true)
  })

  test('check 暂停态下阻塞，resume 后唤醒返回 true', async () => {
    env.cap.control.pause()
    let resolved
    const p = env.cap.control.check('n1')
    p.then((r) => { resolved = r })
    await sleep(50)
    assert.equal(resolved, undefined)
    env.cap.control.resume()
    const r = await p
    assert.equal(r, true)
    assert.equal(resolved, true)
  })

  test('check 暂停态下阻塞，stop 后唤醒返回 false', async () => {
    env.cap.control.pause()
    let resolved
    const p = env.cap.control.check('n1')
    p.then((r) => { resolved = r })
    await sleep(50)
    env.cap.control.stop()
    const r = await p
    assert.equal(r, false)
    assert.equal(resolved, false)
  })

  test('check nodeId 为空时不检查断点', async () => {
    env.cap.control.addBreakpoint('x')
    // nodeId 为空 → 不会自动暂停 → 直接返回 true
    const r = await env.cap.control.check(null)
    assert.equal(r, true)
    assert.equal(env.cap.control.isPaused(), false)
  })
})

// ────────────────────────────────────────────────────────────────────
// A.5 cap.flowchart — 流程图访问层 + trace
// ────────────────────────────────────────────────────────────────────
describe('A.5 cap.flowchart 流程图访问层 + trace', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('setCurrent + get 返回深拷贝（修改不影响内部）', () => {
    const fc = { id: 'fc1', nodes: [{ id: 'n1' }], version: '1.0' }
    env.cap.flowchart.setCurrent(fc)
    const got = env.cap.flowchart.get()
    jsonEqual(got, fc)
    // 修改返回值不影响内部
    got.nodes.push({ id: 'n2' })
    const got2 = env.cap.flowchart.get()
    assert.equal(got2.nodes.length, 1)
  })

  test('setCurrent 之后 trace 被清空', () => {
    env.cap.flowchart.trace.push({ nodeId: 'old' })
    env.cap.flowchart.setCurrent({ nodes: [] })
    assert.equal(env.cap.flowchart.trace.length, 0)
  })

  test('setCurrent 后 getRunId 返回非空 uuid', () => {
    env.cap.flowchart.setCurrent({ id: 'x' })
    const rid = env.cap.flowchart.getRunId()
    assert.ok(typeof rid === 'string' && rid.length > 0)
  })

  test('pushTrace 添加 entry，含 runId/nodeId/status/ts/iso/ms/note', () => {
    env.cap.flowchart.setCurrent({ id: 'fc1' })
    const entry = env.cap.flowchart.pushTrace('n1', 'ok', 'all good', { variables: { x: 1 }, cap_calls: ['cdp.eval'] })
    assert.ok(entry.runId)
    assert.equal(entry.nodeId, 'n1')
    assert.equal(entry.status, 'ok')
    assert.equal(entry.note, 'all good')
    assert.equal(typeof entry.ts, 'number')
    assert.equal(typeof entry.iso, 'string')
    assert.equal(entry.ms, 0)
    jsonEqual(entry.variables, { x: 1 })
    jsonEqual(entry.cap_calls, ['cdp.eval'])
    // 也写入 storage
    const arr = JSON.parse(env.store['trace_flowchart_trace'] || '[]')
    assert.equal(arr.length, 1)
  })

  test('beginNode + endNode 算 ms（endNode 修改 last 节点的 ms）', async () => {
    env.cap.flowchart.setCurrent({ id: 'fc1' })
    const t0 = env.cap.flowchart.beginNode('n1')
    await sleep(20)
    env.cap.flowchart.endNode('n1', 'ok', 'done', t0)
    const trace = env.cap.flowchart.trace
    assert.equal(trace.length, 1)
    assert.equal(trace[0].nodeId, 'n1')
    assert.equal(trace[0].status, 'ok')
    assert.equal(trace[0].note, 'done')
    assert.ok(trace[0].ms >= 15, `ms=${trace[0].ms} 应 >= 15`)
  })

  test('endNode 在没匹配到 running 节点时 pushTrace 新增', () => {
    env.cap.flowchart.setCurrent({ id: 'fc1' })
    env.cap.flowchart.endNode('other', 'ok', 'note', Date.now())
    const trace = env.cap.flowchart.trace
    assert.equal(trace.length, 1)
    assert.equal(trace[0].nodeId, 'other')
    assert.equal(trace[0].status, 'ok')
  })

  test('clear 清空 trace', () => {
    env.cap.flowchart.setCurrent({ id: 'fc1' })
    env.cap.flowchart.pushTrace('n1', 'ok', '')
    env.cap.flowchart.pushTrace('n2', 'ok', '')
    assert.equal(env.cap.flowchart.trace.length, 2)
    env.cap.flowchart.clear()
    assert.equal(env.cap.flowchart.trace.length, 0)
  })

  test('serialize 返回标准 schema（含 schema 字段 + events 数组）', () => {
    env.cap.flowchart.setCurrent({ id: 'fc1', version: '2.0', title: '测试流程' })
    env.cap.flowchart.pushTrace('n1', 'ok', 'first')
    env.cap.flowchart.pushTrace('n2', 'fail', 'second')
    const s = env.cap.flowchart.serialize()
    assert.equal(s.schema, 'https://schema.tupautochrome.io/trace/v1')
    assert.ok(s.runId)
    assert.equal(s.skillId, 'fc1')   // 优先取 fc.id
    assert.equal(s.skillVersion, '2.0')
    jsonEqual(s.flowchart, { id: 'fc1', version: '2.0', title: '测试流程' })
    assert.equal(s.startedAt, env.cap.flowchart.trace[0].iso)
    assert.equal(s.endedAt, env.cap.flowchart.trace[1].iso)
    assert.ok(Array.isArray(s.events))
    assert.equal(s.events.length, 2)
    assert.equal(s.events[0].t, 0)
    assert.equal(s.events[0].nodeId, 'n1')
    assert.equal(s.events[1].t, 1)
    assert.equal(s.events[1].nodeId, 'n2')
  })

  test('exportZip 写到 storage（返回文件名）', async () => {
    env.cap.flowchart.setCurrent({ id: 'fc1', version: '2.0' })
    env.cap.flowchart.pushTrace('n1', 'ok', '')
    const fname = await env.cap.flowchart.exportZip()
    assert.match(fname, /^trace_.*\.json$/)
    // storage 里应有一条 trace_export_ 前缀的记录
    const keys = Object.keys(env.store).filter((k) => k.startsWith('trace_export_'))
    assert.equal(keys.length, 1)
  })

  test('serialize skillId 在 fc.id 缺失时回退到 title', () => {
    env.cap.flowchart.setCurrent({ title: '回退流程' })
    const s = env.cap.flowchart.serialize()
    assert.equal(s.skillId, '回退流程')
  })

  test('serialize 在 trace 为空时 startedAt/endedAt 为 null', () => {
    env.cap.flowchart.setCurrent({ id: 'fc1' })
    const s = env.cap.flowchart.serialize()
    assert.equal(s.startedAt, null)
    assert.equal(s.endedAt, null)
    assert.equal(s.events.length, 0)
  })
})

// ────────────────────────────────────────────────────────────────────
// A.6 cap.skillMarket — 技能市场客户端
// ────────────────────────────────────────────────────────────────────
describe('A.6 cap.skillMarket 技能市场客户端', () => {
  let env
  beforeEach(() => { env = fresh() })

  test('listInstalled 空时返回 []', () => {
    const r = env.cap.skillMarket.listInstalled()
    assert.deepEqual(r, [])
  })

  test('load 后 isInstalled=true', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { name: 'S1', version: '1.0' }, flowchart: { nodes: [] }, handler: () => null })
    assert.equal(env.cap.skillMarket.isInstalled('s1'), true)
    assert.equal(env.cap.skillMarket.isInstalled('s2'), false)
  })

  test('getInstalled 返回元数据', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { name: 'S1', version: '1.0' }, flowchart: { nodes: [] }, handler: () => null, path: '/p' })
    const inst = env.cap.skillMarket.getInstalled('s1')
    assert.equal(inst.skillId, 's1')
    assert.equal(inst.meta.name, 'S1')
    assert.equal(inst.version, '1.0')
    assert.equal(inst.path, '/p')
    assert.ok(inst.installedAt)
  })

  test('unload 后 isInstalled=false', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    const r = env.cap.skillMarket.unload('s1')
    assert.equal(r.ok, true)
    assert.equal(env.cap.skillMarket.isInstalled('s1'), false)
    // 再卸载一次返回 ok:false
    const r2 = env.cap.skillMarket.unload('s1')
    assert.equal(r2.ok, false)
  })

  test('checkUpgrade: 本地无 → { ok: false, error: "not installed" }', async () => {
    const r = await env.cap.skillMarket.checkUpgrade('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'not installed')
  })

  test('checkUpgrade: 服务器不可达 → { ok: false, error: "cannot reach server" }', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    // 默认 fetch impl 返回 ok=false，getLatestVersion 返回 null
    const r = await env.cap.skillMarket.checkUpgrade('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'cannot reach server')
    assert.equal(r.local, '1.0')
  })

  test('checkUpgrade: 服务器返回新版 → hasUpdate=true', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    env.setFetchImpl(async () => ({ ok: true, json: async () => ({ version: '2.0', changelog: 'big update' }) }))
    const r = await env.cap.skillMarket.checkUpgrade('s1')
    assert.equal(r.ok, true)
    assert.equal(r.hasUpdate, true)
    assert.equal(r.local, '1.0')
    assert.equal(r.remote, '2.0')
    assert.equal(r.changelog, 'big update')
  })

  test('checkUpgrade: 版本相同 → hasUpdate=false', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    env.setFetchImpl(async () => ({ ok: true, json: async () => ({ version: '1.0', changelog: '' }) }))
    const r = await env.cap.skillMarket.checkUpgrade('s1')
    assert.equal(r.ok, true)
    assert.equal(r.hasUpdate, false)
  })

  test('upgrade: 归档旧版 + 下载新版 + 上报', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { name: 'S1', version: '1.0' }, flowchart: { nodes: [] }, handler: () => null })
    let reportCaptured = null
    env.setFetchImpl(async (url, opts) => {
      // downloadPackage 走 _get → /download；reportUpgrade 走 _post → /upgrade-report
      if (opts && opts.method === 'POST') {
        reportCaptured = JSON.parse(opts.body)
        return { ok: true, json: async () => ({ received: true }) }
      }
      // 默认 GET
      if (url.includes('/download')) return { ok: true, json: async () => ({ data: 'new-package' }) }
      return { ok: true, json: async () => null }
    })
    const r = await env.cap.skillMarket.upgrade('s1')
    assert.equal(r.ok, true)
    assert.equal(r.fromVersion, '1.0')
    assert.equal(r.toVersion, 'NEW')
    // 归档应该写到 storage
    const archiveKeys = Object.keys(env.store).filter((k) => k.startsWith('skill_archive:s1:'))
    assert.ok(archiveKeys.length >= 1)
    // 上报应被调用
    assert.ok(reportCaptured !== null)
    assert.equal(reportCaptured.skillId, 's1')
    assert.equal(reportCaptured.fromVersion, '1.0')
    assert.equal(reportCaptured.toVersion, 'NEW')
    assert.equal(reportCaptured.ok, true)
  })

  test('upgrade: 未安装时返回 { ok: false, error: "not installed" }', async () => {
    const r = await env.cap.skillMarket.upgrade('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'not installed')
  })

  test('upgrade: 下载失败时返回 { ok: false, error: "download failed" }', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    // fetch 默认返回 ok=false，downloadPackage 返回 null
    const r = await env.cap.skillMarket.upgrade('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'download failed')
    assert.equal(r.local, '1.0')
  })

  test('rollback: 恢复归档版本', async () => {
    // 注：被测代码的 cap.storage.keys 默认实现只返回 'trace_' 前缀的 key，
    // 而 cap.skillMarket.rollback 用 'skill_archive:' 前缀查找归档，会被过滤掉。
    // TODO（被测代码 bug）：cap.storage.keys 应该返回所有 trace_ 与 skill_archive: 前缀的 key，
    // 或者 skillMarket 应该改用统一的归档前缀（如 'trace_skill_archive:'）。
    // 此处通过注入 storage._impl 覆盖默认 keys 实现，让测试可以验证 rollback 逻辑本身。
    injectFullStorageImpl(env)

    // 先加载 1.0 + 升级到 NEW，再 rollback 回 1.0
    await env.cap.skillMarket.load({ skillId: 's1', meta: { name: 'S1', version: '1.0' }, flowchart: { nodes: [{ id: 'n1' }] }, handler: () => null })
    // 直接往 storage 里塞一个归档（绕过 upgrade 流程，便于测试 rollback 逻辑）
    env.cap.storage.set('skill_archive:s1:1.0', {
      meta: { name: 'S1-old', version: '1.0' },
      flowchart: { nodes: [{ id: 'old-n1' }] },
      handler: () => 'old-handler',
      archivedAt: '2024-01-01',
    })
    // 把当前版本改为 2.0（模拟升级后的状态）
    const inst = env.cap.skillMarket.getInstalled('s1')
    inst.version = '2.0'
    inst.meta.version = '2.0'

    let reportCaptured = null
    env.setFetchImpl(async (url, opts) => {
      if (opts && opts.method === 'POST') {
        reportCaptured = JSON.parse(opts.body)
        return { ok: true, json: async () => ({ received: true }) }
      }
      return { ok: true, json: async () => null }
    })

    const r = await env.cap.skillMarket.rollback('s1')
    assert.equal(r.ok, true)
    assert.equal(r.fromVersion, '2.0')
    assert.equal(r.toVersion, '1.0')
    // 已恢复为 1.0
    const restored = env.cap.skillMarket.getInstalled('s1')
    assert.equal(restored.version, '1.0')
    assert.equal(restored.meta.name, 'S1-old')
    // 上报
    assert.ok(reportCaptured !== null)
    assert.equal(reportCaptured.toVersion, '1.0')
    assert.match(reportCaptured.error, /rollback/)
  })

  test('rollback: 恢复归档后 isInstalled 仍为 true', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '2.0' } })
    env.cap.storage.set('skill_archive:s1:1.0', { meta: { version: '1.0' }, flowchart: null, handler: null, archivedAt: 'now' })
    await env.cap.skillMarket.rollback('s1')
    assert.equal(env.cap.skillMarket.isInstalled('s1'), true)
  })

  test('rollback: 未安装 → { ok: false, error: "not installed" }', async () => {
    const r = await env.cap.skillMarket.rollback('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'not installed')
  })

  test('rollback: 无归档 → { ok: false, error: "no archive" }', async () => {
    await env.cap.skillMarket.load({ skillId: 's1', meta: { version: '1.0' } })
    const r = await env.cap.skillMarket.rollback('s1')
    assert.equal(r.ok, false)
    assert.equal(r.error, 'no archive')
  })

  test('searchBySoftware: 中文名+英文名拼接查询，返回 executable 标志', async () => {
    let capturedUrl = ''
    env.setFetchImpl(async (url) => {
      capturedUrl = url
      return { ok: true, json: async () => [{ skill_id: 's1', name: 'Trae' }] }
    })
    const r = await env.cap.skillMarket.searchBySoftware('Trae 中文', 'Trae')
    assert.equal(r.executable, true)
    assert.equal(r.skills.length, 1)
    assert.equal(r.query, 'Trae 中文 Trae')
    // 中文会被 URLSearchParams 编码为 %E4%B8%AD%E6%96%87，断言时不依赖具体编码
    const decodedUrl = decodeURIComponent(capturedUrl)
    assert.match(decodedUrl, /q=Trae\+中文\+Trae/)
    assert.match(decodedUrl, /softwareName=Trae\+中文/)
    assert.match(decodedUrl, /softwareNameEn=Trae/)
  })

  test('searchBySoftware: 中文名+英文名都为空 → 返回空 skills + executable=false', async () => {
    const r = await env.cap.skillMarket.searchBySoftware('', '')
    assert.equal(r.executable, false)
    assert.equal(Array.isArray(r.skills) && r.skills.length, 0, true)
  })

  test('searchBySoftware: 服务器返回空 → executable=false', async () => {
    env.setFetchImpl(async () => ({ ok: true, json: async () => [] }))
    const r = await env.cap.skillMarket.searchBySoftware('foo', 'Foo')
    assert.equal(r.executable, false)
    assert.equal(r.skills.length, 0)
  })
})
