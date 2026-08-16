// Trace Auto — Trae 自动化全部功能
async function handler(params, complete) {
  const { action, goal } = params
  const MAX_ROUNDS = params.maxRounds || 50
  const IDLE_TIMEOUT = params.idleTimeoutSec || 60

  cap.llm.setComplete(complete)

  const CONDITIONS_KEY = 'trace_auto_conditions'

  let _stuckCount = 0
  let _lastUserTurns = 0
  let _lastAITurns = 0
  let _lastAiText = ''

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
        if(t && !b.disabled && b.offsetParent!==null){
          buttons.push({text:t,cls:b.className.slice(0,80)});
        }
      });
      var actionBtns=buttons.filter(function(b){
        return /^运行$|^仍然运行$|^添加到白名单$/.test(b.text)
          && !/取消|停止|关闭|Cancel|Stop|Close/i.test(b.text);
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
      const cur = await getPageState()
      if (!cur.running) return cur
    }
    return await getPageState()
  }

  async function detectStuck(state) {
    const turnChanged = state.u !== _lastUserTurns || state.a !== _lastAITurns
    const textChanged = state.txt !== _lastAiText
    if (turnChanged || textChanged) {
      _stuckCount = 0
    } else if (!state.running) {
      _stuckCount++
    }
    _lastUserTurns = state.u
    _lastAITurns = state.a
    _lastAiText = state.txt
    return _stuckCount
  }

  async function promptUser(title, context, suggestions) {
    if (!cap.ui || !cap.ui.prompt) return null
    return await cap.ui.prompt(title, {
      context: context,
      suggestions: suggestions || [],
      timeout: 120000,
    })
  }

  // 检查是否有条件匹配当前回复
  async function checkConditions(lastText, conditions) {
    if (!conditions || conditions.length === 0) return null
    const prompt = `判断以下 AI 回复是否匹配设定的条件之一。如果匹配，回复条件编号(从0开始)和简短说明；如果不匹配，回复"无"。\n\n条件:\n${conditions.map((c,i)=>i+'. '+c).join('\n')}\n\nAI回复:\n${(lastText||'').slice(-800)}\n\n结果:`
    const reply = await cap.llm.complete([
      { role: 'system', content: '你只判断条件是否匹配，不讨论不解释。匹配返回"条件X: 说明"，不匹配返回"无"。' },
      { role: 'user', content: prompt }
    ], { max_tokens: 100, temperature: 0.1 })
    if (!reply || reply === '无') return null
    return reply
  }

  // 自动总结精炼条件
  async function summarizeConditions(raw) {
    const prompt = `将以下条件总结为简洁的中文规则（每条不超过20字），去重合并同类项：\n${raw.join('\n')}\n\n总结后的规则列表（每条一行，带编号）:`
    const reply = await cap.llm.complete([
      { role: 'system', content: '你只返回精简后的规则列表，不讨论不解释。' },
      { role: 'user', content: prompt }
    ], { max_tokens: 300, temperature: 0.2 })
    if (!reply) return raw
    return reply.split('\n').filter(l => l.trim()).map(l => l.replace(/^\d+[\.\)]\s*/, '').trim())
  }

  function addLog(dir, text, extra) {
    const logs = _logs || []
    const entry = { id: Date.now() + Math.random(), dir, text: (text || '').slice(0, 500), ts: Date.now(), ...(extra || {}) }
    logs.push(entry)
    cap.storage.append('trace_interact_log', entry)
  }

  // ── run_steps ──
  if (action === 'run_steps') {
    const skillDef = { id: params.id || 'inline', steps: params.steps || [] }
    return await skillRun.run(skillDef, params.params || {})
  }

  // ── status: 简洁状态 ──
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
      running: page.running || false
    }
  }

  // ── chat: 设置自动回复条件 ──
  if (action === 'chat') {
    const raw = params.conditions || []
    if (raw.length === 0) {
      // 读取当前条件
      const saved = await cap.storage.get(CONDITIONS_KEY)
      return { conditions: saved || [] }
    }
    const refined = await summarizeConditions(raw)
    await cap.storage.set(CONDITIONS_KEY, refined)
    return { conditions: refined, message: '条件已保存，驱动循环将自动按条件回复' }
  }

  // ── stop ──
  if (action === 'stop') {
    return { stopped: true }
  }

  // ── start: 驱动循环 ──
  const targets = await cap.cdp.getTargets()
  if (!Array.isArray(targets) || !targets.length) {
    return { error: 'IDE 未连接，请确认 IDE 已启动' }
  }

  const _logs = []
  addLog('sys', '驱动循环启动')

  let round = 0
  for (; round < MAX_ROUNDS; round++) {
    const st = await getPageState()
    addLog('sys', `第${round + 1}轮: 对话=${st.u}/${st.a} 按钮=[${(st.actionBtns || []).join(',')}]`)

    if (st.running) {
      addLog('sys', '等待 AI 回复...')
      await waitIdle(IDLE_TIMEOUT)
    }

    const fresh = await getPageState()

    // 检测错误信息
    if (fresh.errorMsgs && fresh.errorMsgs.length > 0) {
      const errText = fresh.errorMsgs.join('\n')
      addLog('sys', `检测到错误: ${errText.slice(0, 100)}`)
      const userAction = await promptUser(
        '检测到错误',
        `AI 报告了以下错误：\n${errText.slice(0, 300)}\n\n请告诉系统如何处理：`,
        ['跳过继续', '重试上次操作', '发送修复指令']
      )
      if (userAction === '跳过继续') {
        continue
      } else if (userAction === '重试上次操作') {
        round--
        continue
      } else if (userAction && userAction !== '停止') {
        await cap.cdp.type('.chat-input-v2-input-box-editable', userAction)
        await cap.runtime.sleep(500)
        await cap.cdp.click('.chat-input-v2-send-button')
        addLog('send', userAction)
        await cap.runtime.sleep(8000)
        continue
      } else {
        break
      }
    }

    // 点击确认/运行按钮
    if (fresh.actionBtns.length > 0) {
      const btnText = fresh.actionBtns[0]
      addLog('sys', `点击按钮: "${btnText}"`)
      const clicked = await cap.cdp.eval(`(function(){
        var btns=document.querySelectorAll('button');
        for(var i=0;i<btns.length;i++){
          var t=(btns[i].innerText||btns[i].textContent||"").trim();
          if(t==="${btnText}" && !btns[i].disabled){
            btns[i].click(); return "ok";
          }
        }
        return "no_match";
      })()`)
      addLog('sys', `点击结果: ${clicked}`)
      await cap.runtime.sleep(3000)
      continue
    }

    // 条件自动回复
    const conditions = await cap.storage.get(CONDITIONS_KEY)
    if (conditions && conditions.length > 0 && fresh.txt) {
      const match = await checkConditions(fresh.txt, conditions)
      if (match) {
        addLog('sys', `条件触发: ${match}`)
        const reply = await cap.llm.complete([
          { role: 'system', content: '根据条件和 AI 回复生成一句简洁的回复。只输出回复文本。' },
          { role: 'user', content: `条件: ${match}\nAI回复: ${(fresh.txt || '').slice(-600)}\n\n我的回复:` }
        ], { max_tokens: 150, temperature: 0.5 })
        if (reply) {
          await cap.cdp.type('.chat-input-v2-input-box-editable', reply)
          await cap.runtime.sleep(500)
          const verified = await cap.cdp.eval(`(function(){
            var el=document.querySelector('.chat-input-v2-input-box-editable');
            return el?(el.innerText||"").trim().length>0:false;
          })()`)
          if (verified) {
            await cap.cdp.click('.chat-input-v2-send-button')
            addLog('send', reply)
            await cap.runtime.sleep(8000)
            addLog('recv', (await getPageState()).txt?.slice(-300) || '(空)')
            continue
          }
        }
      }
    }

    // 输入框有内容 → 发送
    const inputCheck = await cap.cdp.eval(`(function(){
      var el=document.querySelector('.chat-input-v2-input-box-editable');
      return el?(el.innerText||"").trim():"";
    })()`)
    if (inputCheck.length > 0) {
      addLog('sys', '发送输入框内容')
      await cap.cdp.click('.chat-input-v2-send-button')
      addLog('send', inputCheck)
      await cap.runtime.sleep(8000)
      addLog('recv', (await getPageState()).txt?.slice(-300) || '(空)')
      continue
    }

    // 卡住检测
    const stuck = await detectStuck(fresh)
    if (stuck >= 3) {
      addLog('sys', `检测到卡住 (${stuck} 轮无变化)`)
      const lastAi = (fresh.txt || '').slice(-400)
      const userAction = await promptUser(
        'AI 可能卡住了',
        `AI 已经 ${stuck} 轮没有进展。\n\n最后回复：\n${lastAi || '(空)'}\n\n请告诉系统下一步做什么：`,
        ['继续等待', '发送新指令', '结束循环']
      )
      if (userAction === '继续等待') {
        _stuckCount = 0
        await cap.runtime.sleep(5000)
        continue
      } else if (userAction === '发送新指令') {
        _stuckCount = 0
        continue
      } else if (userAction && userAction !== '结束循环') {
        _stuckCount = 0
        await cap.cdp.type('.chat-input-v2-input-box-editable', userAction)
        await cap.runtime.sleep(500)
        await cap.cdp.click('.chat-input-v2-send-button')
        addLog('send', userAction)
        await cap.runtime.sleep(8000)
        continue
      } else {
        break
      }
    }

    // LLM 生成跟进
    const msg = await (async () => {
      if (/完成|完毕|结束|done|finished|complete/i.test(fresh.txt || '')) return '预览并看控制台日志'
      const msgs = [
        { role: 'system', content: '你是一个代码任务推进助手。根据 AI 上一轮的回复和任务目标，生成简洁明确的下一步指令推动任务继续执行。只输出指令文本，不要解释或提问。' },
        { role: 'user', content: '任务目标: ' + (goal || '持续推进开发任务') + '\n\nAI上一轮回复:\n' + (fresh.txt || '').slice(-600) + '\n\n请生成下一步指令:' }
      ]
      return await cap.llm.complete(msgs, { max_tokens: 200, temperature: 0.3 }) || '继续执行下一步。'
    })()
    await cap.cdp.type('.chat-input-v2-input-box-editable', msg)
    await cap.runtime.sleep(500)

    const verified = await cap.cdp.eval(`(function(){
      var el=document.querySelector('.chat-input-v2-input-box-editable');
      return el?(el.innerText||"").trim():"";
    })()`)
    if (!verified) { addLog('sys', '输入失败，跳过'); await cap.runtime.sleep(3000); continue }

    await cap.cdp.click('.chat-input-v2-send-button')
    addLog('send', msg)
    await cap.runtime.sleep(8000)
    addLog('recv', (await getPageState()).txt?.slice(-300) || '(空)')
  }

  addLog('sys', `驱动循环结束，共${round}轮`)
  return { rounds: round, logs: _logs.slice(-20) }
}