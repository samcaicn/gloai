// AIMarketing v6 — 标准化为模板参考实例
// =============================================================================
// 关键升级（v6 标准化）：
//   1. 移除 cap.recognize / cap.flowchart / _control 临时桩 → 改用 capabilities.js 标准能力
//      - cap.recognize.chain(task, tiers)        识别降级链（CDP>UIA>OCR>VLM）
//      - cap.flowchart.setCurrent/get/pushTrace/beginNode/endNode/trace  流程图访问层
//      - cap.control.check/reset/pause/resume/stepOnce/stop/addBreakpoint  控制信号+断点
//      - cap.skillMarket.searchBySoftware/checkUpgrade/upgrade/rollback  技能市场
//   2. 保留 FLOWCHART 内嵌常量作为缺省回退（与 flowchart.json 一致）
//   3. 保留 v5 旧版 _legacyAction 兼容层不变
//   4. 新增 lifecycle / debug 三段式导出（与 _template 一致）
// =============================================================================

// ── 内置流程图（与同目录 flowchart.json 一致，缺省回退用） ─────────────────
// 当 cap.flowchart.get() 返回 null（未调 setCurrent）时，回退到这个常量
const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'trace-auto-flowchart',
  skillId: 'com.tupautochrome.skills.trace-auto',
  version: '6.0.0',
  name: 'Trae 自动化循环',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: ['cdp', 'uia', 'ocr', 'vlm'],
  nodes: [
    { id: 'start',    type: 'start',    label: '开始' },
    { id: 'ensure',   type: 'process',  label: '确保软件支持连接',  recognition: ['cdp', 'uia'] },
    { id: 'read',     type: 'process',  label: '读取页面状态',     recognition: ['cdp', 'ocr', 'vlm'] },
    { id: 'running?', type: 'decision', label: 'AI 在运行?', branches: { yes: 'wait', no: 'act' } },
    { id: 'wait',     type: 'process',  label: '等待 AI 空闲' },
    { id: 'act',      type: 'process',  label: '执行下一步(点击/输入/发送)' },
    { id: 'errors?',  type: 'decision', label: '检测到错误?', branches: { yes: 'prompt', no: 'stuck?' } },
    { id: 'stuck?',   type: 'decision', label: '卡住?',       branches: { yes: 'prompt', no: 'loop' } },
    { id: 'prompt',   type: 'io',       label: '向用户提问/发送指令' },
    { id: 'loop',     type: 'process',  label: '回到读取页面' },
    { id: 'end',      type: 'end',      label: '结束' },
  ],
  connections: [
    { from: 'start',     to: 'ensure' },
    { from: 'ensure',    to: 'read' },
    { from: 'read',      to: 'running?' },
    { from: 'running?',   to: 'wait',   label: 'yes' },
    { from: 'running?',   to: 'act',    label: 'no'  },
    { from: 'wait',      to: 'read' },
    { from: 'act',        to: 'errors?' },
    { from: 'errors?',    to: 'prompt', label: 'yes' },
    { from: 'errors?',    to: 'stuck?', label: 'no'  },
    { from: 'stuck?',     to: 'prompt', label: 'yes' },
    { from: 'stuck?',     to: 'loop',   label: 'no'  },
    { from: 'prompt',    to: 'loop' },
    { from: 'loop',      to: 'read' },
  ],
  judgments: [
    { id: 'J1', node: 'running?', rule: 'stop 按钮 enabled → running=true；CDP 直读 DOM，UIA/OCR 兜底', onMatch: 'wait',     recognition: ['cdp', 'uia', 'ocr'] },
    { id: 'J2', node: 'errors?',  rule: 'DOM 含 [class*=error],[class*=warning],[class*=danger] 且文本 5..500 字',         onMatch: 'prompt',   recognition: ['cdp', 'ocr'] },
    { id: 'J3', node: 'stuck?',   rule: '对话轮次或 AI 最后回复文本 3 轮无变化 → stuck=true',                              onMatch: 'prompt',   recognition: ['cdp'] },
  ],
  selectors: {
    input: '.chat-input-v2-input-box-editable',
    sendBtn: '.chat-input-v2-send-button',
    stopBtn: 'button[class*=stop]',
    userTurn: 'section.chat-turn[data-role=user]',
    aiTurn: 'section.chat-turn[data-role=assistant]',
  },
  variables: {
    goal: { type: 'string' },
    maxRounds: { type: 'number', default: 50 },
    recognition: { type: 'array', items: 'string', default: ['cdp', 'uia', 'ocr', 'vlm'] },
  },
  metadata: { createdAt: '2026-06-29T00:00:00Z', updatedAt: '2026-06-29T00:00:00Z', author: 'AIMarketing' },
}

// ── 默认识别降级链顺序（与 FLOWCHART.recognition 一致） ───────────────────
const DEFAULT_RECOGNITION = ['cdp', 'uia', 'ocr', 'vlm']

// ── 步骤顺序（用于「从此步骤执行」功能） ─────────────────────────────────
// 节点按流程图先后顺序排列；执行时如指定 startNodeId，
// startNodeId 之前的节点会推送 'skipped' 轨迹，循环逻辑从 startNodeId 处恢复。
const STEP_ORDER = ['start', 'ensure', 'read', 'running?', 'wait', 'act', 'errors?', 'stuck?', 'prompt', 'loop', 'end']

// ── 服务器检索包装（按软件中英文名，调 cap.skillMarket.searchBySoftware） ──
// 由前端 AutomationPage 调用，返回服务器侧技能列表 + 是否可执行
// cap.skillMarket 未注入或服务器无结果时，降级返回内置 trace-auto 自身保证 demo 可跑
async function searchSoftware(params) {
  const { softwareName, softwareNameEn } = params
  const q = [softwareName, softwareNameEn].filter(Boolean).join(' ').trim()
  if (!q) return { ok: false, error: 'softwareName 或 softwareNameEn 不能都为空', skills: [], executable: false }

  // 优先走标准能力 cap.skillMarket.searchBySoftware
  if (cap.skillMarket && typeof cap.skillMarket.searchBySoftware === 'function') {
    try {
      const r = await cap.skillMarket.searchBySoftware(softwareName, softwareNameEn)
      const list = Array.isArray(r?.skills) ? r.skills : []
      if (list.length > 0) {
        return { ok: true, query: q, skills: list, executable: true }
      }
      // 服务器无结果 → 走后备
    } catch (e) {
      // 异常 → 走后备
    }
  }

  // 后备：返回内置 trace-auto 自身，保证 demo 可跑
  return {
    ok: true,
    query: q,
    skills: [{
      skill_id: 'trace-auto',
      name: 'AIMarketing',
      version: FLOWCHART.version,
      description: '内置回退：服务器未返回结果，使用本地 trace-auto 技能',
    }],
    executable: true,
    fallback: true,
  }
}

// ── 执行入口：execute ────────────────────────────────────────────────────
// 前端调用顺序：search_software → execute → (step_once/pause/resume) → stop
// 新增 params.startNodeId：从此节点往后执行。
//   - startNodeId 之前的节点全部推 'skipped' 轨迹
//   - 主循环仍然跑完整的 read → running? → wait/act → errors? → stuck? → loop
//     （保证 act 阶段有 read 提供的 state 数据）
//   - 在 startNodeId 处推 'ok' 轨迹作为「恢复执行」标记
async function execute(params, complete) {
  const goal = params.goal || '持续推进开发任务'
  const MAX_ROUNDS = params.maxRounds || 50
  const IDLE_TIMEOUT = params.idleTimeoutSec || 60
  const recognition = (params.recognition && params.recognition.length) ? params.recognition : DEFAULT_RECOGNITION
  const startNodeId = params.startNodeId || null
  // 计算 startNodeId 在 STEP_ORDER 中的位置
  const startStepIdx = startNodeId ? STEP_ORDER.indexOf(startNodeId) : -1
  // startNodeId 合法（存在于 STEP_ORDER 且不是 start/end 骨架）才视为有效
  const hasResumeFrom = startStepIdx > 0
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  // 1. 设置当前流程图 + 重置控制信号 + 清空 trace（setCurrent 内部会清空 trace）
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  cap.flowchart.pushTrace('start', 'ok', hasResumeFrom ? `执行从 ${startNodeId} 开始` : '')

  if (hasResumeFrom) {
    // 把 startNodeId 之前的所有节点推 'skipped' 轨迹
    for (let i = 0; i < startStepIdx; i++) {
      cap.flowchart.pushTrace(STEP_ORDER[i], 'skipped', `从 ${startNodeId} 开始执行`)
    }
    // 在 startNodeId 处推 'ok' 轨迹作为「恢复执行」标记
    cap.flowchart.pushTrace(startNodeId, 'ok', `从此步骤开始执行`)
  }

  // 2. ensure — CDP 连接（走识别链）
  if (!(await cap.control.check('ensure'))) return _summarize(0, 'stopped')
  const ensureTask = { kind: 'element_visible', selector: 'body' }
  const ensureRes = await cap.recognize.chain(ensureTask, ['cdp'])
  cap.flowchart.pushTrace('ensure', ensureRes.ok ? 'ok' : 'fail', ensureRes.note)
  if (!ensureRes.ok) {
    cap.flowchart.pushTrace('end', 'fail', 'CDP 未连接，请启动 IDE')
    return { ok: false, error: 'IDE 未连接，请确认 IDE 已启动', flowchart: cap.flowchart.get() || FLOWCHART, trace: cap.flowchart.trace }
  }

  let round = 0
  let _stuckCount = 0
  let _lastUserTurns = 0
  let _lastAITurns = 0
  let _lastAiText = ''

  for (; round < MAX_ROUNDS; round++) {
    // 循环顶部检查停止信号（不传 nodeId，避免每轮触发断点暂停）
    if (!(await cap.control.check())) {
      cap.flowchart.pushTrace('end', 'stopped', '用户请求停止')
      return _summarize(round, 'stopped')
    }

    // 3. read — 读取页面状态（识别链）
    if (!(await cap.control.check('read'))) return _summarize(round, 'stopped')
    const st = await getPageState()
    cap.flowchart.pushTrace('read', 'ok', `u=${st.u} a=${st.a} running=${st.running}`)

    // 4. running? — decision 节点 J1
    if (st.running) {
      cap.flowchart.pushTrace('running?', 'ok', 'yes → wait')
      cap.flowchart.pushTrace('wait', 'ok', '等待 AI 空闲')
      await waitIdle(IDLE_TIMEOUT)
      continue
    }
    cap.flowchart.pushTrace('running?', 'ok', 'no → act')

    // 5. act — 错误检测 / 点击 / 发送
    // 5.1 errors? — J2
    if (st.errorMsgs && st.errorMsgs.length > 0) {
      const errText = st.errorMsgs.join('\n')
      cap.flowchart.pushTrace('errors?', 'ok', 'yes → prompt: ' + errText.slice(0, 80))
      if (!(await cap.control.check('prompt'))) return _summarize(round, 'stopped')
      const userAction = await promptUser(
        '检测到错误',
        `AI 报告了以下错误：\n${errText.slice(0, 300)}\n\n请告诉系统如何处理：`,
        ['跳过继续', '重试上次操作', '发送修复指令']
      )
      cap.flowchart.pushTrace('prompt', 'ok', String(userAction).slice(0, 80))
      if (userAction === '跳过继续') continue
      if (userAction === '重试上次操作') { round--; continue }
      if (userAction && userAction !== '停止') {
        await cap.cdp.type('.chat-input-v2-input-box-editable', userAction)
        await cap.runtime.sleep(500)
        await cap.cdp.click('.chat-input-v2-send-button')
        await cap.runtime.sleep(8000)
        continue
      }
      break
    }
    cap.flowchart.pushTrace('errors?', 'ok', 'no → stuck?')

    // 5.2 点击确认/运行按钮
    if (st.actionBtns && st.actionBtns.length > 0) {
      const btnText = st.actionBtns[0]
      cap.flowchart.pushTrace('act', 'ok', 'click: ' + btnText)
      await cap.cdp.eval(`(function(){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){var t=(b[i].innerText||b[i].textContent||"").trim();if(t==="${btnText}"&&!b[i].disabled){b[i].click();return "ok";}}return "no_match";})()`)
      await cap.runtime.sleep(3000)
      continue
    }

    // 5.3 条件自动回复
    const conditions = await cap.storage.get('trace_auto_conditions')
    if (conditions && conditions.length > 0 && st.txt) {
      const match = await checkConditions(st.txt, conditions)
      if (match) {
        cap.flowchart.pushTrace('act', 'ok', 'condition match: ' + match)
        const reply = await cap.llm.complete([
          { role: 'system', content: '根据条件和 AI 回复生成一句简洁的回复。只输出回复文本。' },
          { role: 'user', content: `条件: ${match}\nAI回复: ${(st.txt || '').slice(-600)}\n\n我的回复:` },
        ], { max_tokens: 150, temperature: 0.5 })
        if (reply) {
          await cap.cdp.type('.chat-input-v2-input-box-editable', reply)
          await cap.runtime.sleep(500)
          await cap.cdp.click('.chat-input-v2-send-button')
          await cap.runtime.sleep(8000)
          continue
        }
      }
    }

    // 5.4 输入框有内容 → 发送
    const inputCheck = await cap.cdp.eval(`(function(){var e=document.querySelector('.chat-input-v2-input-box-editable');return e?(e.innerText||"").trim():"";})()`)
    if (inputCheck.length > 0) {
      cap.flowchart.pushTrace('act', 'ok', 'send input box')
      await cap.cdp.click('.chat-input-v2-send-button')
      await cap.runtime.sleep(8000)
      continue
    }

    // 6. stuck? — J3
    if (!(await cap.control.check('stuck?'))) return _summarize(round, 'stopped')
    const turnChanged = st.u !== _lastUserTurns || st.a !== _lastAITurns
    const textChanged = st.txt !== _lastAiText
    if (turnChanged || textChanged) _stuckCount = 0
    else _stuckCount++
    _lastUserTurns = st.u; _lastAITurns = st.a; _lastAiText = st.txt

    if (_stuckCount >= 3) {
      cap.flowchart.pushTrace('stuck?', 'ok', 'yes → prompt')
      if (!(await cap.control.check('prompt'))) return _summarize(round, 'stopped')
      const userAction = await promptUser(
        'AI 可能卡住了',
        `AI 已经 ${_stuckCount} 轮没有进展。\n\n最后回复：\n${(st.txt || '').slice(-400) || '(空)'}\n\n请告诉系统下一步做什么：`,
        ['继续等待', '发送新指令', '结束循环']
      )
      cap.flowchart.pushTrace('prompt', 'ok', String(userAction).slice(0, 80))
      if (userAction === '继续等待') { _stuckCount = 0; await cap.runtime.sleep(5000); continue }
      if (userAction === '发送新指令') { _stuckCount = 0; continue }
      if (userAction && userAction !== '结束循环') {
        _stuckCount = 0
        await cap.cdp.type('.chat-input-v2-input-box-editable', userAction)
        await cap.runtime.sleep(500)
        await cap.cdp.click('.chat-input-v2-send-button')
        await cap.runtime.sleep(8000)
        continue
      }
      break
    }
    cap.flowchart.pushTrace('stuck?', 'ok', 'no → loop')

    // 7. loop — LLM 生成跟进并回到 read
    if (!(await cap.control.check('loop'))) return _summarize(round, 'stopped')
    const msg = await (async () => {
      if (/完成|完毕|结束|done|finished|complete/i.test(st.txt || '')) return '预览并看控制台日志'
      const msgs = [
        { role: 'system', content: '你是一个代码任务推进助手。根据 AI 上一轮的回复和任务目标，生成简洁明确的下一步指令推动任务继续执行。只输出指令文本，不要解释或提问。' },
        { role: 'user', content: '任务目标: ' + goal + '\n\nAI上一轮回复:\n' + (st.txt || '').slice(-600) + '\n\n请生成下一步指令:' },
      ]
      return await cap.llm.complete(msgs, { max_tokens: 200, temperature: 0.3 }) || '继续执行下一步。'
    })()
    cap.flowchart.pushTrace('loop', 'ok', 'generate: ' + msg.slice(0, 40))
    await cap.cdp.type('.chat-input-v2-input-box-editable', msg)
    await cap.runtime.sleep(500)
    await cap.cdp.click('.chat-input-v2-send-button')
    await cap.runtime.sleep(8000)
  }

  cap.flowchart.pushTrace('end', 'ok', `共 ${round} 轮`)
  return _summarize(round, 'completed')
}

function _summarize(round, status) {
  return {
    ok: true,
    status,
    rounds: round,
    flowchart: cap.flowchart.get() || FLOWCHART,
    judgments: FLOWCHART.judgments,
    trace: cap.flowchart.trace,
  }
}

// ── 录制入口：record ─────────────────────────────────────────────────────
// 占位实现：开 cap.cdp.startRecording（若注入），将用户操作逐条落到 storage
async function record(params) {
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  cap.flowchart.pushTrace('start', 'ok', 'record mode')
  if (cap.cdp && typeof cap.cdp.startRecording === 'function') {
    await cap.cdp.startRecording(params)
    return { ok: true, mode: 'record', message: '录制已开始，按迷你悬浮窗停止键结束' }
  }
  return { ok: true, mode: 'record', message: 'cap.cdp.startRecording 未注入，仅记录操作日志', flowchart: cap.flowchart.get() || FLOWCHART }
}

// ── 主 handler ───────────────────────────────────────────────────────────
async function handler(params, complete) {
  const { action } = params

  // ── 流程图查看 ──
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_judgments') return (cap.flowchart.get() || FLOWCHART).judgments || FLOWCHART.judgments
  if (action === 'get_trace')     return cap.flowchart.trace

  // ── 执行入口 ──
  if (action === 'search_software') return await searchSoftware(params)
  if (action === 'execute')         return await execute(params, complete)
  if (action === 'record')          return await record(params)

  // ── 控制流（调 cap.control） ──
  if (action === 'step_once') { cap.control.stepOnce(); return { ok: true, paused: false, stepOnce: true } }
  if (action === 'pause')     { cap.control.pause();    return { ok: true, paused: true } }
  if (action === 'resume')    { cap.control.resume();   return { ok: true, paused: false } }
  if (action === 'stop')      { cap.control.stop();     return { ok: true, stopRequested: true } }

  // ── 断点管理（调 cap.control） ──
  if (action === 'add_breakpoint')    { cap.control.addBreakpoint(params.nodeId);    return { ok: true } }
  if (action === 'remove_breakpoint') { cap.control.removeBreakpoint(params.nodeId); return { ok: true } }
  if (action === 'clear_breakpoints') { cap.control.clearBreakpoints();              return { ok: true } }

  // ── 升级管理（调 cap.skillMarket） ──
  if (action === 'check_upgrade') return await cap.skillMarket.checkUpgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'upgrade')       return await cap.skillMarket.upgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'rollback')      return await cap.skillMarket.rollback(params.skillId || FLOWCHART.skillId)

  // ── 旧版兼容：run_steps / status / chat / stop / start ──
  if (action === 'run_steps') {
    const skillDef = { id: params.id || 'inline', steps: params.steps || [] }
    return await skillRun.run(skillDef, params.params || {})
  }
  if (action === 'status') {
    const targets = await cap.cdp.getTargets()
    if (!Array.isArray(targets) || !targets.length) {
      return { connected: false, state: 'disconnected', rounds: 0, running: false }
    }
    const page = await getPageState()
    return {
      connected: true,
      state: page.running ? 'running' : 'idle',
      rounds: Math.max(page.u || 0, page.a || 0),
      running: page.running || false,
    }
  }
  if (action === 'chat') {
    const raw = params.conditions || []
    if (raw.length === 0) {
      const saved = await cap.storage.get('trace_auto_conditions')
      return { conditions: saved || [] }
    }
    const refined = params.skipSummarize ? raw : await summarizeConditions(raw)
    await cap.storage.set('trace_auto_conditions', refined)
    return { conditions: refined, message: '条件已保存，驱动循环将自动按条件回复' }
  }
  if (action === 'start') {
    // 旧版 start 等价于 execute（不返回流程图）
    const r = await execute(params, complete)
    return { rounds: r.rounds, logs: (cap.flowchart.trace || []).slice(-20) }
  }

  // ── 旧版 CDP / 页面操作 / 条件回复（保留 v5 行为） ──
  return await _legacyAction(action, params, complete)
}

// ── 辅助：页面状态读取 ─────────────────────────────────────────────────
async function getPageState() {
  const JS = `(function(){
    var u=document.querySelectorAll('section.chat-turn[data-role=user]').length;
    var a=document.querySelectorAll('section.chat-turn[data-role=assistant]').length;
    var running=false;
    var stopBtn=document.querySelector('button[class*=stop]');
    if(stopBtn && !stopBtn.disabled) running=true;
    var lastAI=document.querySelectorAll('section.chat-turn[data-role=assistant]');
    var last=lastAI.length?lastAI[lastAI.length-1]:null;
    var txt=last?(last.innerText||"").slice(-1200):"";
    var buttons=[];
    document.querySelectorAll('button').forEach(function(b){
      var t=(b.innerText||b.textContent||"").trim();
      if(t && !b.disabled && b.offsetParent!==null) buttons.push({text:t,cls:b.className.slice(0,80)});
    });
    var actionBtns=buttons.filter(function(b){
      return /^运行$|^仍然运行$|^添加到白名单$/.test(b.text) && !/取消|停止|关闭|Cancel|Stop|Close/i.test(b.text);
    });
    var errorMsgs=[];
    document.querySelectorAll('[class*=error],[class*=warning],[class*=danger]').forEach(function(el){
      var t=(el.innerText||"").trim();
      if(t && t.length>5 && t.length<500) errorMsgs.push(t);
    });
    return JSON.stringify({ u:u, a:a, running:running, txt:txt, actionBtns:actionBtns.map(function(b){return b.text}), errorMsgs:errorMsgs });
  })()`
  const raw = await cap.cdp.eval(JS)
  try { return typeof raw === 'string' ? JSON.parse(raw) : raw }
  catch { return { u: 0, a: 0, running: false, txt: '', actionBtns: [], errorMsgs: [] } }
}

async function waitIdle(timeoutSec) {
  for (let w = 0; w < timeoutSec * 2; w++) {
    await cap.runtime.sleep(500)
    // 等待期间检查停止信号（不传 nodeId，避免触发断点）
    if (!(await cap.control.check())) return null
    const cur = await getPageState()
    if (!cur.running) return cur
  }
  return await getPageState()
}

async function promptUser(title, context, suggestions) {
  if (!cap.ui || !cap.ui.prompt) return null
  return await cap.ui.prompt(title, { context, suggestions: suggestions || [], timeout: 120000 })
}

async function checkConditions(lastText, conditions) {
  if (!conditions || conditions.length === 0) return null
  const prompt = `判断以下 AI 回复是否匹配设定的条件之一。如果匹配，回复条件编号(从0开始)和简短说明；如果不匹配，回复"无"。\n\n条件:\n${conditions.map((c,i)=>i+'. '+c).join('\n')}\n\nAI回复:\n${(lastText||'').slice(-800)}\n\n结果:`
  const reply = await cap.llm.complete([
    { role: 'system', content: '你只判断条件是否匹配，不讨论不解释。匹配返回"条件X: 说明"，不匹配返回"无"。' },
    { role: 'user', content: prompt },
  ], { max_tokens: 100, temperature: 0.1 })
  // 防御性检查: LLM 可能返回 undefined/null/空字符串/纯空白,
  // 均视为"不匹配",与 '无' 同等处理。
  if (!reply || reply === '无' || !reply.trim()) return null
  return reply
}

async function summarizeConditions(raw) {
  const prompt = `将以下条件总结为简洁的中文规则（每条不超过20字），去重合并同类项：\n${raw.join('\n')}\n\n总结后的规则列表（每条一行，带编号）:`
  const reply = await cap.llm.complete([
    { role: 'system', content: '你只返回精简后的规则列表，不讨论不解释。' },
    { role: 'user', content: prompt },
  ], { max_tokens: 300, temperature: 0.2 })
  if (!reply) return raw
  return reply.split('\n').filter(l => l.trim()).map(l => l.replace(/^\d+[\.\)]\s*/, '').trim())
}

// ── 旧版 action 兼容层（保留 v5 行为不变） ──────────────────────────────
async function _legacyAction(action, params, complete) {
  // CDP / 软件探测
  if (action === 'ensure_cdp') {
    const t = await cap.cdp.getTargets()
    return { connected: Array.isArray(t) && t.length > 0, targets: t || [] }
  }
  if (action === 'find_exe' || action === 'scan_ports') return { ok: true, action }
  if (action === 'targets')  return await cap.cdp.getTargets()
  if (action === 'check_page') {
    const kw = params.keyword || 'trae'
    const JS = `(function(){return (document.body&&(document.body.innerText||"")).indexOf(${JSON.stringify(kw)})>=0?"1":"0";})()`
    return { matched: (await cap.cdp.eval(JS)) === '1' }
  }
  // 页面读取
  if (action === 'read_state')     return await getPageState()
  if (action === 'wait_idle')      return await waitIdle(params.timeoutSec || 60)
  if (action === 'detect_stuck')    return { ok: true, note: 'use execute loop' }
  if (action === 'reset_stuck')    { _stuckCount_legacy = 0; return { ok: true } }
  if (action === 'read_input') {
    return { text: await cap.cdp.eval(`(function(){var e=document.querySelector('.chat-input-v2-input-box-editable');return e?(e.innerText||"").trim():"";})()`) }
  }
  if (action === 'count_turns') {
    return JSON.parse(await cap.cdp.eval(`(function(){return JSON.stringify({user:document.querySelectorAll("section.chat-turn[data-role=user]").length,ai:document.querySelectorAll("section.chat-turn[data-role=assistant]").length})})()`) || '{}')
  }
  if (action === 'check_running') {
    return { running: await cap.cdp.eval(`(function(){var b=document.querySelector("button[class*=stop]");return !!(b&&!b.disabled);})()`) === true }
  }
  // 页面操作
  if (action === 'click_button' && params.buttonText) {
    const r = await cap.cdp.eval(`(function(){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){var t=(b[i].innerText||b[i].textContent||"").trim();if(t==="${params.buttonText}"&&!b[i].disabled){b[i].click();return "ok";}}return "no_match";})()`)
    return { clicked: r === 'ok' }
  }
  if (action === 'click_action_buttons') {
    const st = await getPageState()
    if (st.actionBtns && st.actionBtns.length > 0) {
      const btnText = st.actionBtns[0]
      await cap.cdp.eval(`(function(){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){var t=(b[i].innerText||b[i].textContent||"").trim();if(t==="${btnText}"&&!b[i].disabled){b[i].click();return "ok";}}return "no_match";})()`)
      return { clicked: btnText }
    }
    return { clicked: null }
  }
  if (action === 'click_send') { await cap.cdp.click('.chat-input-v2-send-button'); return { ok: true } }
  if (action === 'click_stop') {
    const r = await cap.cdp.eval(`(function(){var b=document.querySelector("button[class*=stop]");if(b&&!b.disabled){b.click();return"stopped"}return"no_stop"})()`)
    return { stopped: r === 'stopped' }
  }
  if (action === 'type_input')         { await cap.cdp.type('.chat-input-v2-input-box-editable', params.text || ''); return { ok: true } }
  if (action === 'type_and_send') {
    await cap.cdp.type('.chat-input-v2-input-box-editable', params.text || '')
    await cap.runtime.sleep(500)
    await cap.cdp.click('.chat-input-v2-send-button')
    await cap.runtime.sleep(params.waitAfterMs || 8000)
    return { ok: true, sent: params.text }
  }
  if (action === 'verify_input') {
    const text = await cap.cdp.eval(`(function(){var e=document.querySelector('.chat-input-v2-input-box-editable');return e?(e.innerText||"").trim():"";})()`)
    return { verified: text === (params.text || '') }
  }
  if (action === 'clear_input') {
    await cap.cdp.eval(`(function(){var e=document.querySelector('.chat-input-v2-input-box-editable');if(e){e.innerText='';e.dispatchEvent(new Event('input',{bubbles:true}));}return "ok";})()`)
    return { ok: true }
  }
  if (action === 'send_input') { await cap.cdp.click('.chat-input-v2-send-button'); return { ok: true } }
  // 条件回复
  if (action === 'set_conditions') {
    const raw = params.conditions || []
    const refined = params.skipSummarize ? raw : await summarizeConditions(raw)
    await cap.storage.set('trace_auto_conditions', refined)
    return { conditions: refined }
  }
  if (action === 'get_conditions')     return { conditions: (await cap.storage.get('trace_auto_conditions')) || [] }
  if (action === 'clear_conditions')  { await cap.storage.delete('trace_auto_conditions'); return { ok: true } }
  if (action === 'summarize_conditions') return { conditions: await summarizeConditions(params.conditions || []) }
  if (action === 'check_only') {
    const st = await getPageState()
    return { match: await checkConditions(params.aiText || st.txt, (await cap.storage.get('trace_auto_conditions')) || []) }
  }
  if (action === 'check_and_reply') {
    const st = await getPageState()
    const conditions = (await cap.storage.get('trace_auto_conditions')) || []
    const match = await checkConditions(params.aiText || st.txt, conditions)
    if (!match) return { match: null }
    const reply = await cap.llm.complete([
      { role: 'system', content: '根据条件和 AI 回复生成一句简洁的回复。只输出回复文本。' },
      { role: 'user', content: `条件: ${match}\nAI回复: ${(params.aiText || st.txt || '').slice(-600)}\n\n我的回复:` },
    ], { max_tokens: 150, temperature: 0.5 })
    if (reply) {
      await cap.cdp.type('.chat-input-v2-input-box-editable', reply)
      await cap.runtime.sleep(500)
      await cap.cdp.click('.chat-input-v2-send-button')
      await cap.runtime.sleep(params.waitAfterMs || 8000)
    }
    return { match, reply }
  }
  if (action === 'generate_followup') {
    const st = await getPageState()
    const msg = await cap.llm.complete([
      { role: 'system', content: '你是代码任务推进助手，只输出下一步指令文本。' },
      { role: 'user', content: `任务目标: ${params.goal || '持续推进'}\nAI回复: ${(params.aiText || st.txt || '').slice(-600)}\n\n下一步指令:` },
    ], { max_tokens: 200, temperature: 0.3 })
    return { followup: msg }
  }
  return { ok: false, error: 'unknown action: ' + action }
}

var _stuckCount_legacy = 0

// ── 生命周期导出（参考 Robot Framework Setup/Teardown + Robocorp @task） ──
export const lifecycle = {
  onSkillLoad:   async (ctx) => { cap.runtime.log('trace-auto', 'skill loaded') },
  onTaskStart:   async (ctx, task) => { cap.runtime.log('trace-auto', 'task start: ' + task) },
  onTaskEnd:     async (ctx, task, result) => { cap.runtime.log('trace-auto', 'task end: ' + task) },
  onSkillUnload: async (ctx) => { cap.runtime.log('trace-auto', 'skill unloaded') },
}

// ── 调试钩子（参考 Playwright Trace Viewer + RF Language Server） ──────────
export const debug = {
  // 列出可监视的变量
  getVariableScope: (ctx) => ({ locals: ctx?.locals || {}, flowchart: cap.flowchart.get() || FLOWCHART }),
  // 命中断点时调用
  onBreakpoint: async (ctx, node) => { cap.runtime.log('debug', 'breakpoint hit: ' + node.id) },
}

export default handler
