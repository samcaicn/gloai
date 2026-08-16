// ═══════════════════════════════════════════════════════════════════
// Layer 1 — 能力层 (Capability Layer)
// 参考: ORCA Agent-Skills (能力即契约), CUA-Adapter (平台适配),
//       Microsoft Agent Framework (skill-source 组合), Playwright (传输抽象)
//
// 设计原则:
//   - 每个能力是一个独立模块, 通过 cap.<group>.<method>() 调用
//   - 适配器模式: OS 差异通过 cap.os.adapter() 注入, 调用的地方无差别
//   - 扩展点: mid.register(action, handler) 允许运行时注册新动作
//   - 事件钩子: mid.on('before|after|error', callback) 用于日志/监控
// ═══════════════════════════════════════════════════════════════════

// __GW_URL__ 编译期由 Rust 替换

const cap = {}
const __VERSION__ = '3.0'

// ── 传输层 ──────────────────────────────────────────────────────
// 所有与 sidecar 的 HTTP 通信统一经过这里, 方便未来替换为 WebSocket / IPC
const _GW = '__GW_URL__'
const _transport = {
  get:   async (path) => { try { const r = await fetch(_GW + path); return r.ok ? await r.json() : null } catch { return null } },
  post:  async (path, body) => { try { const r = await fetch(_GW + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) }); return r.ok ? await r.json() : null } catch { return null } },
  raw:   async (path, opts) => { try { return await fetch(_GW + path, opts) } catch { return null } },
}
const _get  = _transport.get
const _post = _transport.post

// ═══════════════════════════════════════════════════════════════════
// CDP 能力 — Chrome DevTools Protocol
// 通过 Tauri invoke 调用后端 execute_browser_action_cmd，
// 不依赖 HTTP 网关路由（网关无 /cdp/* 路由）。
// ═══════════════════════════════════════════════════════════════════

// 全局浏览器会话 ID（首次 CDP 调用时自动创建）
let _cdpSessionId = null

async function _ensureCdpSession() {
  if (_cdpSessionId) return _cdpSessionId
  const invoke = await _getTauriInvoke()
  if (!invoke) throw new Error('Tauri invoke not available for CDP')
  // v1.9.6 重打：fallback 链补 msedge（Win11 默认浏览器），顺序按
  // "用户最可能装"排：chrome > msedge > brave > firefox。每类型重试 2 次
  // （间隔 1s），覆盖 AV 扫描 / 端口冲突 / profile 锁瞬时失败。
  // 旧版只试 brave→chrome，缺 Edge；Brave-first 失败时抛出的错误污染状态。
  const types = ['chrome', 'msedge', 'brave', 'firefox']
  const errs = []
  for (const bt of types) {
    for (var attempt = 1; attempt <= 2; attempt++) {
      try {
        _cdpSessionId = await invoke('start_browser_session_cmd', { browserType: bt })
        cap.runtime.log('cdp', '会话启动成功: ' + bt + ' sid=' + _cdpSessionId)
        return _cdpSessionId
      } catch (e) {
        const msg = (e && (e.message || String(e))) || 'unknown'
        errs.push(bt + '#' + attempt + ': ' + msg)
        cap.runtime.log('cdp', '启动 ' + bt + ' 失败(尝试 ' + attempt + '/2): ' + msg)
        if (attempt < 2) await cap.runtime.sleep(1000)
      }
    }
  }
  throw new Error('所有浏览器启动均失败:\n' + errs.join('\n'))
}

async function _cdpAction(action) {
  const invoke = await _getTauriInvoke()
  if (!invoke) throw new Error('Tauri invoke not available')
  let sid = await _ensureCdpSession()
  try {
    const result = await invoke('execute_browser_action_cmd', {
      sessionId: sid,
      action: action,
    })
    // 后端返回 JSON 字符串，解析后返回
    try { return JSON.parse(result) } catch { return result }
  } catch (e) {
    // 会话活性重试（v1.9.6）：浏览器崩溃/被关闭后 _cdpSessionId 仍持失效 ID，
    // 后端返回"浏览器会话不存在"。检测到该错误 → 重置 sessionId + 重新拉起
    // 浏览器 + 重试一次 invoke。二次仍失败则 throw（带两条错误信息）。
    const errMsg = (e && (e.message || String(e))) || ''
    if (errMsg.indexOf('会话不存在') >= 0 || /session.*(not.*exist|missing|invalid)/i.test(errMsg)) {
      cap.runtime.log('cdp', '会话失效，重置 _cdpSessionId 并重试: ' + errMsg)
      _cdpSessionId = null
      try {
        sid = await _ensureCdpSession()
        const result = await invoke('execute_browser_action_cmd', {
          sessionId: sid,
          action: action,
        })
        try { return JSON.parse(result) } catch { return result }
      } catch (e2) {
        throw new Error('CDP action 重试仍失败 (sid=' + sid + '): ' + (e2?.message || String(e2)) + ' | 首次错误: ' + errMsg)
      }
    }
    throw e
  }
}

cap.cdp = {
  eval: async (expression) => {
    try {
      const r = await _cdpAction({ type: 'evaluate', expression })
      // ActionResult 仅 {action,success,error,screenshotB64} 四字段,
      // 后端把 evaluate 的 JS 返回值塞进 error 字段(见 browser_steps.rs
      // BrowserAction::Evaluate 分支)。`(r && r.result)` 永远 undefined
      // —— 旧代码留着是无意义的死分支,删除。
      return (r && r.error) || ''
    } catch (e) { cap.runtime.log('cdp', 'eval failed: ' + e.message); return '' }
  },
  click: async (selector) => {
    try { return await _cdpAction({ type: 'click', selector }) } catch { return null }
  },
  type: async (selector, text) => {
    try { return await _cdpAction({ type: 'type_in', selector, text }) } catch { return null }
  },
  wait: async (selector, timeout) => {
    try { await _cdpAction({ type: 'wait_for', selector, timeout_ms: timeout || 30000 }) } catch {}
  },
  read: async (selector) => {
    // extract_text 把文本塞进 ActionResult.error 字段 (见 browser_steps.rs),
    // 直接返回 _cdpAction 会得到整个对象 {action,success,error,screenshotB64},
    // 调用方做 String 操作会得到 "[object Object]"。这里剥出文本字符串。
    try {
      const r = await _cdpAction({ type: 'extract_text', selector })
      return (r && r.error) || ''
    } catch { return null }
  },
  getTargets: async () => {
    // v1.9.6：改用独立命令 list_browser_targets_cmd（返回 Vec<TargetInfoDto>，
    // Tauri serde 直接序列化为 JS 数组），不再走 _cdpAction({type:'get_targets'})。
    // 旧路径的 BrowserAction::GetTargets 是空壳 stub，从不返回真实 targets，
    // 且 getTargets 用 `catch { return [] }` 吞掉所有错误，导致 ensureCdp
    // 永远拿到空数组、最终返回 status:failed。这里不再吞错误——让真实错误
    // 冒泡到 safeGetTargets/ensureCdp，写入 trace。
    //
    // 会话失效重试（v1.9.7）：getTargets 走的是独立命令 list_browser_targets_cmd,
    // 不经过 _cdpAction,因此拿不到 _cdpAction 内置的"会话不存在 → 重置 + 重试"
    // 兜底。浏览器崩溃/被用户手动关闭后,_cdpSessionId 仍持失效 ID,该命令会
    // 持续抛"浏览器会话不存在" → ensureCdp 重试 3 次全失败 → 技能 status:failed。
    // 这里镜像 _cdpAction 的重试逻辑:检测到会话失效错误 → 重置 _cdpSessionId
    // + 重新拉起浏览器 + 重试一次。二次仍失败则 throw 让上层写 trace。
    const invoke = await _getTauriInvoke()
    if (!invoke) throw new Error('Tauri invoke not available for getTargets')
    let sid = await _ensureCdpSession()
    try {
      return await invoke('list_browser_targets_cmd', { sessionId: sid })
    } catch (e) {
      const errMsg = (e && (e.message || String(e))) || ''
      if (errMsg.indexOf('会话不存在') >= 0 || /session.*(not.*exist|missing|invalid)/i.test(errMsg)) {
        cap.runtime.log('cdp', 'getTargets 会话失效，重置 _cdpSessionId 并重试: ' + errMsg)
        _cdpSessionId = null
        try {
          sid = await _ensureCdpSession()
          return await invoke('list_browser_targets_cmd', { sessionId: sid })
        } catch (e2) {
          throw new Error('getTargets 重试仍失败 (sid=' + sid + '): ' + (e2?.message || String(e2)) + ' | 首次错误: ' + errMsg)
        }
      }
      throw e
    }
  },
  // v1.9.6 重打：显式启动浏览器会话（让技能能主动启动 + 看到错误，
  // 而非依赖 _ensureCdpSession 的懒触发）。若已有 session 先关掉避免泄漏。
  startSession: async (browserType) => {
    const invoke = await _getTauriInvoke()
    if (!invoke) throw new Error('Tauri invoke not available for startSession')
    if (_cdpSessionId) {
      try { await invoke('close_browser_session_cmd', { sessionId: _cdpSessionId }) } catch {}
      _cdpSessionId = null
    }
    _cdpSessionId = await invoke('start_browser_session_cmd', { browserType: browserType || '' })
    return _cdpSessionId
  },
  closeSession: async () => {
    if (!_cdpSessionId) return
    const invoke = await _getTauriInvoke()
    if (invoke) {
      try { await invoke('close_browser_session_cmd', { sessionId: _cdpSessionId }) } catch {}
    }
    _cdpSessionId = null
  },
  // models 方法已删除(v1.9.7):原实现 `await _get('/cdp/models') || []`
  // 调用的 /cdp/models 路由在 embedded_server 未注册,_get 对 404 返回 null,
  // 方法永远返回 [],且 `cap.cdp.models` 在全仓无任何调用方(仅 mid.register
  // 一行自我引用)。保留只会让误调用方拿到空数组并误以为"无可用模型"。
  // 如未来需要模型枚举,应改走 MCP `llm.stream_request` 或新加 Tauri 命令。
  navigate: async (url) => {
    try { return await _cdpAction({ type: 'navigate', url }) } catch { return null }
  },
  screenshot: async () => {
    try { return await _cdpAction({ type: 'screenshot' }) } catch { return null }
  },
}

// ═══════════════════════════════════════════════════════════════════
// UIA 能力 — Windows UI Automation (适配器注入点)
// ═══════════════════════════════════════════════════════════════════
// 未来实现: cap.uia._impl = realUiaImpl (通过 cap.os.registerAdapter 注入)
cap.uia = {
  _impl: null,
  _available: false,
  find: async (condition) => { if (cap.uia._impl) return cap.uia._impl.find(condition); return null },
  click: async (element) => { if (cap.uia._impl) return cap.uia._impl.click(element) },
  type: async (element, text) => { if (cap.uia._impl) return cap.uia._impl.type(element, text) },
  getText: async (element) => { if (cap.uia._impl) return cap.uia._impl.getText(element); return '' },
  listWindows: async () => { if (cap.uia._impl) return cap.uia._impl.listWindows(); return [] },
}

// ═══════════════════════════════════════════════════════════════════
// OCR 能力 — 文字识别 (适配器注入点)
// ═══════════════════════════════════════════════════════════════════
cap.ocr = {
  _impl: null,
  _available: false,
  readText: async (region) => { if (cap.ocr._impl) return cap.ocr._impl.readText(region); return '' },
  findText: async (text, region) => { if (cap.ocr._impl) return cap.ocr._impl.findText(text, region); return null },
  readAll: async () => { if (cap.ocr._impl) return cap.ocr._impl.readAll(); return '' },
}

// ═══════════════════════════════════════════════════════════════════
// LLM 能力 — 大语言模型 (多提供商后备链)
// ═══════════════════════════════════════════════════════════════════
let _llmFallbacks = [] // [{ name, fn }] 后备链
let _llmInjected = null
// complete 回调：技能 handler(params, complete) 的第二参数，用于流式/增量
// 上报执行进度。runBuiltinSkill 传 null（一次性 await 返回结果），技能内部
// 以 `if (complete) complete(...)` 守卫调用。setComplete 把它存到 cap.llm
// 上下文，供 LLM 流式响应等场景复用。之前 capabilities.js 未实现该方法，
// 而 trace-auto / wechat-publisher / xiaohongshu-publisher 三个技能在
// handler 首行无条件 `cap.llm.setComplete(complete)` → 启动即抛
// TypeError: cap.llm.setComplete is not a function。
let _completeCb = null

// 获取 Tauri invoke（生产路径）。技能运行时若不在 Tauri WebView 上下文则返回 null。
async function _getTauriInvoke() {
  try {
    // Tauri 2: __TAURI_INTERNALS__ 由运行时始终注入（无需 withGlobalTauri）。
    // 前端 environment.ts 的 isTauriRuntime() 也用这个全局变量判断。
    // 技能 JS 经 new Function() 求值，无法访问前端模块作用域的 invoke 闭包，
    // 必须走全局变量。旧版只查 window.__TAURI__（需 withGlobalTauri:true），
    // 本项目未设该选项 → 返回 null → 所有 CDP/LLM/storage invoke 失败。
    if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__ && typeof window.__TAURI_INTERNALS__.invoke === 'function') {
      return window.__TAURI_INTERNALS__.invoke
    }
    // 降级：withGlobalTauri=true 的应用暴露 __TAURI__.core.invoke
    if (typeof window !== 'undefined' && window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke) {
      return window.__TAURI__.core.invoke
    }
    // 最后降级：ESM 动态 import（在 new Function 求值的字符串中通常无法解析 bare specifier）
    const mod = await import('@tauri-apps/api/core')
    return mod.invoke
  } catch { return null }
}

cap.llm = {
  // 设置 complete 回调（技能 handler 第二参数）。null 安全（runBuiltinSkill
  // 传 null），非函数值统一归一为 null，避免后续误调。
  setComplete: (cb) => { _completeCb = typeof cb === 'function' ? cb : null },
  // 获取当前 complete 回调（供流式 LLM 响应等场景调用，调用方需自行判空）
  getComplete: () => _completeCb,
  // 注册主提供商 (覆盖 localStorage 路径)
  setProvider: (name, fn) => { _llmInjected = { name, fn } },
  // 添加后备 (按顺序试)
  addFallback: (name, fn) => { _llmFallbacks.push({ name, fn }) },
  // 主入口: 按优先级尝试
  // messages 归一化：技能常直接传字符串（如 cap.llm.complete('prompt')），
  // 此处自动包装为 [{ role: 'user', content: string }]，避免 MCP/injected
  // provider 收到字符串导致请求格式错误。
  complete: async (messages, opts) => {
    opts = opts || {}
    // 字符串归一化为标准 messages 数组
    if (typeof messages === 'string') {
      messages = [{ role: 'user', content: messages }]
    }
    // 单个 message 对象也包装为数组
    if (messages && !Array.isArray(messages) && typeof messages === 'object') {
      messages = [messages]
    }
    const errs = []
    // 1. 通过 Tauri 后端调用（生产路径：避免前端直连 ai.tuptup.top 绕过代理控制/设备鉴权）
    try {
      const invoke = await _getTauriInvoke()
      if (invoke) {
        const token = typeof localStorage !== 'undefined' ? localStorage.getItem('trae_device_token') : null
        if (token) {
          const params = {
            model: opts.model || 'gpt-4o',
            messages,
            max_tokens: opts.max_tokens || 200,
            temperature: opts.temperature || 0.3,
            stream: true,
          }
          const r = await invoke('mcp_call_v2', { action: 'llm.stream_request', params, token, timeoutSecs: 60 })
          // 兼容多种返回结构：先解包 MCP 外层 { ok, data, error }
          if (r && r.ok === false) { errs.push('mcp:' + (r.error?.message || 'unknown')); /* fall through to fallback */ }
          else if (r && r.error) { errs.push('mcp:' + (r.error.message || 'unknown')); /* fall through */ }
          else {
            const inner = r?.data
            if (typeof inner === 'string') return inner
            if (inner && inner.content) return inner.content
            if (inner && inner.choices && inner.choices[0] && inner.choices[0].message && inner.choices[0].message.content) return inner.choices[0].message.content
            if (inner && inner.result && inner.result.content) return inner.result.content
          }
        }
      }
    } catch (e) { errs.push('invoke:' + e.message) }
    // 注：不再保留直连云端 `https://ai.tuptup.top/v1/chat/completions` 的
    // fallback——该端点已下线（404），LLM 会话统一经 MCP `llm.stream_request`
    // 发起（见上方 mcp_call_v2 主路径）。无 Tauri 上下文（浏览器预览/测试）
    // 若需 LLM，请注入 provider（见下方 _llmInjected / _llmFallbacks）。
    // 2. Injected provider
    if (_llmInjected) { try { const r = await _llmInjected.fn(messages, opts); if (r?.content) return r.content } catch (e) { errs.push('provider:' + e.message) } }
    // 3. 后备链
    for (const fb of _llmFallbacks) { try { const r = await fb.fn(messages, opts); if (r?.content) return r.content } catch (e) { errs.push(fb.name + ':' + e.message) } }
    cap.runtime.log('llm', '所有 LLM 路径失败: ' + errs.join('; '))
    return ''
  },
}

// ═══════════════════════════════════════════════════════════════════
// 存储能力 — 持久化 (适配器注入点)
// ═══════════════════════════════════════════════════════════════════
cap.storage = {
  _impl: null, // 可替换为 Tauri fs / IndexedDB
  get:   (key, def) => { if (cap.storage._impl) return cap.storage._impl.get(key, def); try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : def } catch { return def } },
  set:   (key, val) => { if (cap.storage._impl) return cap.storage._impl.set(key, val); try { localStorage.setItem(key, JSON.stringify(val)) } catch {} },
  getRaw: (key) => { if (cap.storage._impl) return cap.storage._impl.getRaw(key); try { return localStorage.getItem(key) || '' } catch { return '' } },
  setRaw: (key, val) => { if (cap.storage._impl) return cap.storage._impl.setRaw(key, val); try { localStorage.setItem(key, val) } catch {} },
  append: (key, item) => { if (cap.storage._impl) return cap.storage._impl.append(key, item); try { const arr = JSON.parse(localStorage.getItem(key) || '[]'); arr.push(item); localStorage.setItem(key, JSON.stringify(arr)) } catch {} },
  delete: (key) => { if (cap.storage._impl) return cap.storage._impl.delete(key); try { localStorage.removeItem(key) } catch {} },
  keys:   () => { if (cap.storage._impl) return cap.storage._impl.keys(); try { return Object.keys(localStorage).filter(k => k.startsWith('trace_')) } catch { return [] } },
}

// ═══════════════════════════════════════════════════════════════════
// UI 能力 — 用户交互 (弹窗输入)
// ═══════════════════════════════════════════════════════════════════
const _pendingPrompts = new Map()
let _promptIdCounter = 0

cap.ui = {
  prompt: async (message, options) => {
    const id = ++_promptIdCounter
    const opts = options || {}
    const timeoutMs = opts.timeout || 30000
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (_pendingPrompts.has(id)) {
          _pendingPrompts.delete(id)
          cap.runtime.log('ui', 'prompt 超时 (30s): ' + message)
          resolve(null)
        }
      }, timeoutMs)
      _pendingPrompts.set(id, { resolve, message, options: opts, timer })
      window.dispatchEvent(new CustomEvent('skill-prompt', {
        detail: { id, message, options: opts }
      }))
    })
  },
  respond: (id, response) => {
    const pending = _pendingPrompts.get(id)
    if (pending) {
      if (pending.timer) clearTimeout(pending.timer)
      _pendingPrompts.delete(id)
      pending.resolve(response)
    }
  },
  cancel: (id) => {
    const pending = _pendingPrompts.get(id)
    if (pending) {
      if (pending.timer) clearTimeout(pending.timer)
      _pendingPrompts.delete(id)
      pending.resolve(null)
    }
  }
}

window.addEventListener('skill-prompt-response', (e) => {
  if (e.detail && e.detail.id != null) {
    cap.ui.respond(e.detail.id, e.detail.response)
  }
})

window.addEventListener('skill-prompt-cancel', (e) => {
  if (e.detail && e.detail.id != null) {
    cap.ui.cancel(e.detail.id)
  }
})

// ═══════════════════════════════════════════════════════════════════
// 运行时能力 — 工具函数
// ═══════════════════════════════════════════════════════════════════
cap.runtime = {
  sleep: (ms) => new Promise(r => setTimeout(r, ms)),
  now: () => Date.now(),
  iso: () => new Date().toISOString(),
  log: (tag, msg) => {
    try {
      const entry = { id: Date.now() + Math.random(), dir: tag, text: (msg || '').slice(0, 500), ts: Date.now() }
      cap.storage.append('trace_interact_log', entry)
    } catch {}
  },
  uuid: () => { try { return crypto.randomUUID() } catch { return 'u_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6) } },
}

// ═══════════════════════════════════════════════════════════════════
// 中间件 — 动作派发器 (可扩展的 action → handler 路由)
// ═══════════════════════════════════════════════════════════════════
const _actionHandlers = {}
const _eventHooks = { before: [], after: [], error: [] }

const mid = {
  // 注册动作处理器
  register: (action, handler) => { _actionHandlers[action] = handler },
  // 注册事件钩子
  on: (event, fn) => { if (_eventHooks[event]) _eventHooks[event].push(fn) },
  // 执行一步
  exec: async (step) => {
    const action = step.action
    let handler = _actionHandlers[action]
    // 内置默认处理器
    if (!handler) {
      const group = action.split('.')[0]
      if (group === 'cdp')    handler = (p) => { const m = p.action.replace('cdp.', ''); return cap.cdp[m] ? cap.cdp[m](p.expression || p.selector || p.text || p.timeout || undefined) : (() => { throw new Error('未知 cdp 方法: ' + m) })() }
      else if (action === 'llm.complete')   handler = (p) => cap.llm.complete(p.messages || [{ role: 'user', content: p.prompt || '' }], p.options)
      else if (action === 'storage.get')    handler = (p) => cap.storage.get(p.key, p.def)
      else if (action === 'storage.set')    handler = (p) => cap.storage.set(p.key, p.val)
      else if (action === 'runtime.sleep')  handler = (p) => cap.runtime.sleep(p.ms || 1000)
      else if (action === 'monitor.status') handler = () => _get('/monitor/status')
      else throw new Error('未知动作: ' + action)
      _actionHandlers[action] = handler
    }
    // 事件钩子: before
    for (const h of _eventHooks.before) { try { h(step) } catch {} }
    try {
      const result = await handler(step)
      for (const h of _eventHooks.after) { try { h(step, result) } catch {} }
      return result
    } catch (e) {
      for (const h of _eventHooks.error) { try { h(step, e) } catch {} }
      throw e
    }
  },
}

// ── 注册内置处理器 ──
mid.register('cdp.eval',   (p) => cap.cdp.eval(p.expression))
mid.register('cdp.click',  (p) => cap.cdp.click(p.selector))
mid.register('cdp.type',   (p) => cap.cdp.type(p.selector, p.text))
mid.register('cdp.wait',   (p) => cap.cdp.wait(p.selector, p.timeout))
mid.register('cdp.read',   (p) => cap.cdp.read(p.selector))
mid.register('cdp.targets',() => cap.cdp.getTargets())
// 'cdp.models' 注册已删除:对应方法 cap.cdp.models 已移除(见上方 cap.cdp 注释)。
// 任何 mid.exec({action:'cdp.models'}) 调用会落入默认 handler 的
// "未知动作: cdp.models" 分支并 throw,比静默返回 [] 更明确。
mid.register('llm.complete',(p) => cap.llm.complete(p.messages || [{ role: 'user', content: p.prompt || '' }], p.options))
mid.register('storage.get', (p) => cap.storage.get(p.key, p.def))
mid.register('storage.set', (p) => cap.storage.set(p.key, p.val))
mid.register('runtime.sleep',(p) => cap.runtime.sleep(p.ms || p.timeout || 1000))
mid.register('monitor.status',() => _get('/monitor/status'))

// ═══════════════════════════════════════════════════════════════════
// 平台适配器 (OS-specific Adapters)
// 参考: ComputerUse (cua_agent/core + macos/windows adapters)
// ═══════════════════════════════════════════════════════════════════
cap.os = {
  _adapters: {},
  register: (name, impl) => { cap.os._adapters[name] = impl },
  get: (name) => cap.os._adapters[name] || null,
  // 自动检测当前 OS
  detect: () => { try { return navigator.platform || 'unknown' } catch { return 'unknown' } },
}

// ═══════════════════════════════════════════════════════════════════
// 应用简写 (Software Profiles)
// ── 软���配置: 将 "trae.click_send" 映射为具体 step
// 参考: CUA-Skill (技能参数实例化), WKAppBot (每应用配置文件)
// ═══════════════════════════════════════════════════════════════════
const _profiles = {}

cap.app = {
  register: (id, profile) => { _profiles[id] = profile },
  get: (id) => _profiles[id] || null,
  list: () => Object.keys(_profiles),
  resolve: async (appId, actionName, stepParams) => {
    const profile = _profiles[appId]
    if (!profile) throw new Error('未知软件: ' + appId)
    const actionDef = profile.actions?.[actionName]
    if (!actionDef) throw new Error('软件 ' + appId + ' 无动作: ' + actionName)
    if (typeof actionDef === 'string') return { action: actionDef, ...stepParams }
    if (Array.isArray(actionDef.steps)) return actionDef.steps.map(s => ({ ...s, ...stepParams }))
    return { ...actionDef, ...stepParams }
  },
}

// ── Trae CN 默认配置 ──
cap.app.register('trae', {
  name: 'Trae CN',
  selectors: {
    input: '.chat-input-v2-input-box-editable',
    sendBtn: '.chat-input-v2-send-button',
    chatTurn: '.chat-turn:last-child',
    stopBtn: 'button[class*=stop]',
    userTurn: 'section.chat-turn[data-role=user]',
    aiTurn: 'section.chat-turn[data-role=assistant]',
    aiStatus: '.chat-input-v2-ai-status',
  },
  actions: {
    type_input:   { action: 'cdp.type', selector: '.chat-input-v2-input-box-editable' },
    click_send:   { action: 'cdp.click', selector: '.chat-input-v2-send-button' },
    wait_reply:   { action: 'cdp.wait', selector: '.chat-turn:last-child', timeout: 120000 },
    read_reply:   { action: 'cdp.read', selector: '.chat-turn:last-child' },
    check_running:{ action: 'cdp.eval', expression: '!!document.querySelector("button[class*=stop]:not([disabled])")' },
    last_ai_text: { action: 'cdp.eval', expression: '(function(){var e=document.querySelectorAll("section.chat-turn[data-role=assistant]");return e.length?e[e.length-1].innerText.slice(-1200):""})()' },
    click_stop:   { action: 'cdp.eval', expression: '(function(){var b=document.querySelector("button[class*=stop]");if(b&&!b.disabled){b.click();return"stopped"}return"no_stop"})()' },
    count_turns:  { action: 'cdp.eval', expression: '(function(){return JSON.stringify({user:document.querySelectorAll("section.chat-turn[data-role=user]").length,ai:document.querySelectorAll("section.chat-turn[data-role=assistant]").length})})()' },
  },
})

// ═══════════════════════════════════════════════════════════════════
// cap.server — 服务器侧技能市场 API（客户端封装）
// 所有方法走 _transport，服务器没实现时优雅降级返回 null/[]
// ═══════════════════════════════════════════════════════════════════
cap.server = {
  _impl: null,  // 可注入完整实现覆盖 HTTP 路径

  // 按软件中英文名搜索技能市场
  // 返回: [{ skill_id, name, version, description, icon, category, tags, downloads, rating, verified }]
  searchSkills: async (query, opts) => {
    if (cap.server._impl && cap.server._impl.searchSkills) return cap.server._impl.searchSkills(query, opts)
    opts = opts || {}
    const params = new URLSearchParams()
    if (query) params.set('q', query)
    if (opts.softwareName) params.set('softwareName', opts.softwareName)
    if (opts.softwareNameEn) params.set('softwareNameEn', opts.softwareNameEn)
    if (opts.category) params.set('category', opts.category)
    if (opts.page) params.set('page', opts.page)
    if (opts.pageSize) params.set('pageSize', opts.pageSize)
    const qs = params.toString()
    const r = await _get('/api/v1/skills/market/search' + (qs ? '?' + qs : ''))
    return r || []
  },

  // 获取单个技能的详情（含完整元数据）
  getSkillDetail: async (skillId) => {
    if (cap.server._impl && cap.server._impl.getSkillDetail) return cap.server._impl.getSkillDetail(skillId)
    const r = await _get('/api/v1/skills/market/' + encodeURIComponent(skillId))
    return r || null
  },

  // 获取技能的流程图配置
  getFlowchart: async (skillId, version) => {
    if (cap.server._impl && cap.server._impl.getFlowchart) return cap.server._impl.getFlowchart(skillId, version)
    let path = '/api/v1/skills/market/' + encodeURIComponent(skillId) + '/flowchart'
    if (version) path += '?version=' + encodeURIComponent(version)
    const r = await _get(path)
    return r || null
  },

  // 下载技能包 zip（返回 ArrayBuffer 或 base64 data）
  downloadPackage: async (skillId, version) => {
    if (cap.server._impl && cap.server._impl.downloadPackage) return cap.server._impl.downloadPackage(skillId, version)
    let path = '/api/v1/skills/market/' + encodeURIComponent(skillId) + '/download'
    if (version) path += '?version=' + encodeURIComponent(version)
    const r = await _get(path)
    return r || null
  },

  // 上报运行 trace（用于云端调试回看）
  reportRun: async (trace) => {
    if (cap.server._impl && cap.server._impl.reportRun) return cap.server._impl.reportRun(trace)
    const r = await _post('/api/v1/runs/trace', { trace })
    return r || null
  },

  // 上报技能升级结果（成功/失败）
  reportUpgrade: async (skillId, fromVersion, toVersion, ok, error) => {
    if (cap.server._impl && cap.server._impl.reportUpgrade) return cap.server._impl.reportUpgrade(skillId, fromVersion, toVersion, ok, error)
    const r = await _post('/api/v1/skills/' + encodeURIComponent(skillId) + '/upgrade-report', { skillId, fromVersion, toVersion, ok, error })
    return r || null
  },

  // 拉取技能的最新版本元数据（用于检查升级）
  getLatestVersion: async (skillId) => {
    if (cap.server._impl && cap.server._impl.getLatestVersion) return cap.server._impl.getLatestVersion(skillId)
    const r = await _get('/api/v1/skills/market/' + encodeURIComponent(skillId) + '/latest')
    return r || null
  },
}
mid.register('server.search_skills',    (p) => cap.server.searchSkills(p.query, p))
mid.register('server.skill_detail',     (p) => cap.server.getSkillDetail(p.skillId))
mid.register('server.get_flowchart',    (p) => cap.server.getFlowchart(p.skillId, p.version))
mid.register('server.download_package', (p) => cap.server.downloadPackage(p.skillId, p.version))
mid.register('server.report_run',       (p) => cap.server.reportRun(p.trace))
mid.register('server.report_upgrade',   (p) => cap.server.reportUpgrade(p.skillId, p.fromVersion, p.toVersion, p.ok, p.error))
mid.register('server.latest_version',   (p) => cap.server.getLatestVersion(p.skillId))

// ═══════════════════════════════════════════════════════════════════
// cap.recognize — 多层识别降级链（See 机制）
// 把 trace-auto/index.js 里的临时桩抽出来作为标准能力。CDP>UIA>OCR>VLM 链式降级
// ═══════════════════════════════════════════════════════════════════
cap.recognize = cap.recognize || {
  _impls: {},  // tier → { find, click, ... } 可注入

  // 链式降级：依次尝试 tiers 中每个 tier，第一个返回 ok 即停
  // task: { kind: 'element_visible'|'element_attribute'|'text_present'|'screen_text'|'image_understand', selector, text, attribute, value, region, question }
  // tiers: ['cdp','uia','ocr','vlm']，缺省用 DEFAULT_RECOGNITION
  // 返回: { ok, tier, value, trace: [{ tier, ok, ms, note }] }
  chain: async (task, tiers) => {
    tiers = tiers || DEFAULT_RECOGNITION
    const trace = []
    for (const tier of tiers) {
      const t0 = Date.now()
      try {
        const r = await cap.recognize.run(tier, task)
        const ms = Date.now() - t0
        const ok = !!(r && r.ok)
        const note = (r && r.note) || ''
        trace.push({ tier, ok, ms, note })
        if (ok) return { ok: true, tier, value: r.value, trace }
      } catch (e) {
        const ms = Date.now() - t0
        trace.push({ tier, ok: false, ms, note: 'error: ' + (e && e.message || e) })
      }
    }
    return { ok: false, tier: null, value: null, trace }
  },

  // 单 tier 调用
  run: async (tier, task) => {
    // 优先使用注入的实现
    const impl = cap.recognize._impls[tier]
    if (impl) {
      if (typeof impl.find === 'function') return impl.find(task)
      if (typeof impl === 'function') return impl(task)
      return { ok: false, note: 'invalid impl for tier: ' + tier }
    }
    // 内置实现
    if (tier === 'cdp') return cap.recognize._recognizeCdp(task)
    if (tier === 'uia') return cap.recognize._recognizeUia(task)
    if (tier === 'ocr') return cap.recognize._recognizeOcr(task)
    if (tier === 'vlm') return cap.recognize._recognizeVlm(task)
    return { ok: false, note: 'unknown tier: ' + tier }
  },

  // 注册某 tier 的实现（运行时由 Rust 侧或测试代码注入）
  register: (tier, impl) => { cap.recognize._impls[tier] = impl },

  // CDP 识别：用 cap.cdp.eval 跑 JS 检查
  _recognizeCdp: async (task) => {
    try {
      if (task.kind === 'text_present') {
        const expr = 'document.body.innerText.indexOf(' + JSON.stringify(task.text || '') + ')>=0'
        const r = await cap.cdp.eval(expr)
        const ok = r === true || r === 'true' || r === 1 || r === '1'
        return { ok, value: r, note: ok ? 'found' : 'not found' }
      }
      if (task.kind === 'element_visible') {
        const expr = '(function(){var e=document.querySelector(' + JSON.stringify(task.selector || '') + ');return e&&e.offsetParent!==null?1:0})()'
        const r = await cap.cdp.eval(expr)
        const ok = r === 1 || r === '1' || r === true || r === 'true'
        return { ok, value: r, note: ok ? 'visible' : 'not visible' }
      }
      if (task.kind === 'element_attribute') {
        const expr = '(function(){var e=document.querySelector(' + JSON.stringify(task.selector || '') + ');return e?e.getAttribute(' + JSON.stringify(task.attribute || '') + '):null})()'
        const r = await cap.cdp.eval(expr)
        const ok = r != null && r !== '' && r !== 'null' && r !== 'undefined'
        return { ok, value: r, note: ok ? 'attribute: ' + r : 'no attribute' }
      }
      return { ok: false, note: 'unsupported kind for cdp: ' + task.kind }
    } catch (e) {
      return { ok: false, note: 'cdp error: ' + (e && e.message || e) }
    }
  },

  // UIA 识别：走 cap.uia._impl，元素查找
  _recognizeUia: async (task) => {
    try {
      if (!cap.uia._impl) return { ok: false, note: 'uia not available' }
      if (task.kind === 'text_present' || task.kind === 'screen_text') {
        const el = await cap.uia.find({ text: task.text })
        return { ok: !!el, value: el, note: el ? 'found' : 'not found' }
      }
      if (task.kind === 'element_visible' || task.kind === 'element_attribute') {
        const el = await cap.uia.find({ selector: task.selector })
        if (!el) return { ok: false, value: null, note: 'not found' }
        if (task.kind === 'element_attribute') {
          const text = await cap.uia.getText(el)
          return { ok: text !== '', value: text, note: text ? 'text: ' + text : 'no text' }
        }
        return { ok: true, value: el, note: 'found' }
      }
      return { ok: false, note: 'unsupported kind for uia: ' + task.kind }
    } catch (e) {
      return { ok: false, note: 'uia error: ' + (e && e.message || e) }
    }
  },

  // OCR 识别：走 cap.ocr._impl.readText(region)，匹配 text
  _recognizeOcr: async (task) => {
    try {
      if (!cap.ocr._impl) return { ok: false, note: 'ocr not available' }
      const text = await cap.ocr.readText(task.region)
      if (task.kind === 'text_present' || task.kind === 'screen_text') {
        const found = !!(text && text.indexOf(task.text) >= 0)
        return { ok: found, value: text, note: found ? 'found' : 'not found' }
      }
      return { ok: false, note: 'unsupported kind for ocr: ' + task.kind }
    } catch (e) {
      return { ok: false, note: 'ocr error: ' + (e && e.message || e) }
    }
  },

  // VLM 识别：走 cap.vlm.ask(question)，返回非空即 ok
  _recognizeVlm: async (task) => {
    try {
      if (!cap.vlm || !cap.vlm._available) return { ok: false, note: 'vlm not available' }
      let question = task.question
      if (task.kind === 'text_present') question = '屏幕上是否有文字「' + (task.text || '') + '」？请回答是或否。'
      else if (task.kind === 'element_visible') question = '屏幕上是否有元素「' + (task.selector || '') + '」？请回答是或否。'
      else if (task.kind === 'image_understand') question = task.question || '请描述这个屏幕'
      else if (!question) question = '请描述当前屏幕内容'
      const r = await cap.vlm.ask(question, task.image)
      const ok = !!(r && r.trim() !== '')
      return { ok, value: r, note: ok ? 'answered' : 'no answer' }
    } catch (e) {
      return { ok: false, note: 'vlm error: ' + (e && e.message || e) }
    }
  },
}
mid.register('recognize.chain',    (p) => cap.recognize.chain(p.task, p.tiers))
mid.register('recognize.run',      (p) => cap.recognize.run(p.tier, p.task))
mid.register('recognize.register', (p) => cap.recognize.register(p.tier, p.impl))

// ═══════════════════════════════════════════════════════════════════
// cap.vlm — 视觉语言模型（VLM）
// 适配器注入点风格，参考 cap.uia
// ═══════════════════════════════════════════════════════════════════
cap.vlm = {
  _impl: null,        // 注入: { ask: async (question, image) => string }
  _available: false,  // 是否可用

  // 问 VLM 一个问题，可选附带图像（base64 或 path）
  ask: async (question, image) => {
    if (cap.vlm._impl && cap.vlm._impl.ask) return cap.vlm._impl.ask(question, image)
    // 后备：用 LLM 走文本描述（截图转 base64 喂给多模态模型）
    if (cap.llm && typeof cap.llm.complete === 'function') {
      const messages = image
        ? [{ role: 'user', content: [{ type: 'text', text: question }, { type: 'image_url', image_url: { url: image } }] }]
        : [{ role: 'user', content: question }]
      try { return await cap.llm.complete(messages, { model: 'gpt-4o', max_tokens: 300, temperature: 0.2 }) || '' }
      catch { return '' }
    }
    return ''
  },

  // 描述当前屏幕 / 截图（便捷方法）
  describeScreen: async (region) => {
    const shot = cap.cdp && cap.cdp.screenshot ? await cap.cdp.screenshot() : null
    return cap.vlm.ask('请描述这个屏幕截图中可见的 UI 元素和文字', shot)
  },

  // 在屏幕上找一个目标（返回坐标或元素描述）
  findTarget: async (description) => {
    return cap.vlm.ask('在当前屏幕中查找: ' + description + '。返回该目标的精确位置或描述。')
  },

  // 注入实现
  register: (impl) => { cap.vlm._impl = impl; cap.vlm._available = true },
}
mid.register('vlm.ask',             (p) => cap.vlm.ask(p.question, p.image))
mid.register('vlm.describe_screen', (p) => cap.vlm.describeScreen(p.region))
mid.register('vlm.find_target',     (p) => cap.vlm.findTarget(p.description))
mid.register('vlm.register',        (p) => cap.vlm.register(p.impl))

// ═══════════════════════════════════════════════════════════════════
// cap.control — 执行控制信号（暂停/单步/停止）
// 进程内单例，被技能的 execute 循环轮询，由迷你悬浮窗 / 调试器修改
// ═══════════════════════════════════════════════════════════════════
const _controlState = {
  paused: false,
  stepOnce: false,
  stopRequested: false,
  // 断点（节点 id 集合）
  breakpoints: new Set(),
  // 当前正在等待的节点 promise resolver（用于调试器唤醒）
  _waitResolver: null,
}

cap.control = {
  // 暂停
  pause: () => { _controlState.paused = true; _controlState.stepOnce = false },

  // 继续
  resume: () => { _controlState.paused = false; _controlState.stepOnce = false; if (_controlState._waitResolver) { const r = _controlState._waitResolver; _controlState._waitResolver = null; r() } },

  // 单步执行一个节点（执行后保持暂停）
  stepOnce: () => { _controlState.stepOnce = true; _controlState.paused = false; if (_controlState._waitResolver) { const r = _controlState._waitResolver; _controlState._waitResolver = null; r() } },

  // 停止
  stop: () => { _controlState.stopRequested = true; _controlState.paused = false; _controlState.stepOnce = false; if (_controlState._waitResolver) { const r = _controlState._waitResolver; _controlState._waitResolver = null; r() } },

  // 重置（开始新一次执行前调用）
  reset: () => { _controlState.paused = false; _controlState.stepOnce = false; _controlState.stopRequested = false; _controlState.breakpoints.clear(); if (_controlState._waitResolver) { const r = _controlState._waitResolver; _controlState._waitResolver = null; r() } },

  // 查询状态
  isPaused: () => _controlState.paused,
  isStopRequested: () => _controlState.stopRequested,

  // 断点管理
  addBreakpoint: (nodeId) => _controlState.breakpoints.add(nodeId),
  removeBreakpoint: (nodeId) => _controlState.breakpoints.delete(nodeId),
  clearBreakpoints: () => _controlState.breakpoints.clear(),
  hasBreakpoint: (nodeId) => _controlState.breakpoints.has(nodeId),

  // 在节点入口处调用（技能 execute 循环用）
  // 返回 true 表示应当继续；false 表示已请求停止
  // 如果当前节点命中断点或处于暂停态，会阻塞直到 resume/stepOnce/stop
  check: async (nodeId) => {
    if (_controlState.stopRequested) return false
    // 命中断点 → 自动进入暂停
    if (nodeId && _controlState.breakpoints.has(nodeId)) _controlState.paused = true
    // 暂停且非单步 → 阻塞等待
    while (_controlState.paused && !_controlState.stepOnce && !_controlState.stopRequested) {
      await new Promise(resolve => { _controlState._waitResolver = resolve; setTimeout(() => { if (_controlState._waitResolver === resolve) { _controlState._waitResolver = null; resolve() } }, 200) })
    }
    if (_controlState.stepOnce) { _controlState.stepOnce = false; _controlState.paused = true }
    return !_controlState.stopRequested
  },
}
mid.register('control.pause',             () => cap.control.pause())
mid.register('control.resume',            () => cap.control.resume())
mid.register('control.step_once',         () => cap.control.stepOnce())
mid.register('control.stop',              () => cap.control.stop())
mid.register('control.reset',             () => cap.control.reset())
mid.register('control.status',            () => ({ paused: _controlState.paused, stepOnce: _controlState.stepOnce, stopRequested: _controlState.stopRequested, breakpoints: Array.from(_controlState.breakpoints) }))
mid.register('control.add_breakpoint',    (p) => cap.control.addBreakpoint(p.nodeId))
mid.register('control.remove_breakpoint', (p) => cap.control.removeBreakpoint(p.nodeId))
mid.register('control.clear_breakpoints', () => cap.control.clearBreakpoints())

// ═══════════════════════════════════════════════════════════════════
// cap.flowchart — 流程图访问层 + 执行 trace（参考 Playwright trace.zip）
// 让技能可以把当前 flowchart 注册进来，并提供 trace 记录 / 序列化 / 导出
// ═══════════════════════════════════════════════════════════════════
let _currentFlowchart = null  // 当前正在执行的 flowchart
let _currentRunId = null

cap.flowchart = {
  // 当前 flowchart 的 trace 数组（[{ nodeId, status, ts, ms, note, variables, cap_calls }]）
  trace: [],

  // 设置当前流程图（技能 execute 入口处调用）
  setCurrent: (fc) => { _currentFlowchart = fc; _currentRunId = cap.runtime.uuid(); cap.flowchart.trace = [] },

  // 获取当前流程图
  get: () => _currentFlowchart ? JSON.parse(JSON.stringify(_currentFlowchart)) : null,

  // 获取当前 run id
  getRunId: () => _currentRunId,

  // 推一条 trace
  pushTrace: (nodeId, status, note, extra) => {
    const entry = {
      runId: _currentRunId,
      nodeId, status,                    // ok | fail | skipped | stopped | breakpoint
      ts: Date.now(),
      iso: new Date().toISOString(),
      ms: 0,                             // 由 endNode 填充
      note: note || '',
      variables: extra?.variables || null,
      cap_calls: extra?.cap_calls || [],
    }
    cap.flowchart.trace.push(entry)
    cap.storage.append('trace_flowchart_trace', entry)
    return entry
  },

  // 标记节点开始（返回开始时间用于算 ms）
  beginNode: (nodeId) => {
    const t0 = Date.now()
    cap.flowchart.pushTrace(nodeId, 'running', '')
    return t0
  },

  // 标记节点结束
  endNode: (nodeId, status, note, t0) => {
    const last = cap.flowchart.trace[cap.flowchart.trace.length - 1]
    if (last && last.nodeId === nodeId && last.status === 'running') {
      last.status = status
      last.ms = Date.now() - t0
      last.note = note || ''
    } else {
      cap.flowchart.pushTrace(nodeId, status, note, { ms: Date.now() - t0 })
    }
  },

  // 清空 trace（开始新 run 前）
  clear: () => { cap.flowchart.trace = [] },

  // 序列化 trace 为可回放 JSON（参考 Playwright trace.json schema）
  serialize: () => {
    return {
      schema: 'https://schema.tupautochrome.io/trace/v1',
      runId: _currentRunId,
      skillId: _currentFlowchart?.id || _currentFlowchart?.title || 'unknown',
      skillVersion: _currentFlowchart?.version || '0',
      flowchart: _currentFlowchart,
      startedAt: cap.flowchart.trace[0]?.iso || null,
      endedAt: cap.flowchart.trace[cap.flowchart.trace.length - 1]?.iso || null,
      events: cap.flowchart.trace.map((e, i) => ({ t: i, ...e })),
    }
  },

  // 导出为 zip 文件（用 cap.storage 写盘）
  // 返回保存的文件路径
  exportZip: async () => {
    const data = cap.flowchart.serialize()
    const fname = 'trace_' + (_currentRunId || cap.runtime.uuid()) + '.json'
    // 用 storage 保存（实际生产可由 Rust 侧打包 zip + 截图 + DOM 快照）
    cap.storage.set('trace_export_' + fname, data)
    return fname
  },
}
mid.register('flowchart.set_current', (p) => cap.flowchart.setCurrent(p.flowchart))
mid.register('flowchart.get',         () => cap.flowchart.get())
mid.register('flowchart.get_trace',   () => cap.flowchart.trace)
mid.register('flowchart.push_trace', (p) => cap.flowchart.pushTrace(p.nodeId, p.status, p.note, p))
mid.register('flowchart.begin_node', (p) => cap.flowchart.beginNode(p.nodeId))
mid.register('flowchart.end_node',   (p) => cap.flowchart.endNode(p.nodeId, p.status, p.note, p.t0))
mid.register('flowchart.clear',      () => cap.flowchart.clear())
mid.register('flowchart.serialize',  () => cap.flowchart.serialize())
mid.register('flowchart.export_zip', () => cap.flowchart.exportZip())

// ═══════════════════════════════════════════════════════════════════
// cap.skillMarket — 技能市场客户端（加载/列表/升级/回滚）
// 封装本地技能加载 + 远端市场交互
// ═══════════════════════════════════════════════════════════════════
const _installedSkills = new Map()  // skillId → { meta, flowchart, handler, path, version, installedAt }
const _archiveDir = 'skill_archive'  // 旧版本归档目录 key

cap.skillMarket = {
  // 列出本地已安装的技能
  listInstalled: () => Array.from(_installedSkills.values()).map(s => ({
    skillId: s.skillId, name: s.meta?.name, version: s.version,
    installedAt: s.installedAt, path: s.path,
  })),

  // 检查某技能是否已安装
  isInstalled: (skillId) => _installedSkills.has(skillId),

  // 获取已安装技能的元数据
  getInstalled: (skillId) => _installedSkills.get(skillId) || null,

  // 加载技能包（从本地路径或远端下载）
  // source: { type: 'local', path } 或 { type: 'remote', skillId, version }
  // 返回: { ok, skillId, version, meta, flowchart, handler }
  load: async (source) => {
    // 1. 拿到技能包内容（SKILL.md + index.js + flowchart.json）
    //    - local: 直接从 storage / 文件系统读
    //    - remote: 调 cap.server.downloadPackage 拿 zip，解包
    // 2. 解析 frontmatter 拿 meta
    // 3. 解析 flowchart.json
    // 4. 加载 index.js（实际生产由 Rust 侧沙箱执行；这里假设已注入 handler）
    // 5. 写入 _installedSkills
    // 简化实现：
    const skillId = source.skillId || source.path
    const meta = source.meta || { name: skillId, version: source.version || '0.0.0' }
    const flowchart = source.flowchart || null
    const handler = source.handler || null
    _installedSkills.set(skillId, {
      skillId, meta, flowchart, handler,
      version: meta.version,
      path: source.path || null,
      installedAt: new Date().toISOString(),
    })
    return { ok: true, skillId, version: meta.version, meta, flowchart, handler }
  },

  // 卸载技能
  unload: (skillId) => {
    const existed = _installedSkills.delete(skillId)
    return { ok: existed }
  },

  // 检查升级（与远端比对版本）
  checkUpgrade: async (skillId) => {
    const local = _installedSkills.get(skillId)
    if (!local) return { ok: false, error: 'not installed' }
    const remote = await cap.server.getLatestVersion(skillId)
    if (!remote) return { ok: false, error: 'cannot reach server', local: local.version }
    const hasUpdate = remote.version !== local.version
    return { ok: true, hasUpdate, local: local.version, remote: remote.version, changelog: remote.changelog }
  },

  // 升级（下载新版 → 归档旧版 → 加载新版）
  upgrade: async (skillId) => {
    const local = _installedSkills.get(skillId)
    if (!local) return { ok: false, error: 'not installed' }
    // 1. 归档旧版
    cap.storage.set(_archiveDir + ':' + skillId + ':' + local.version, {
      meta: local.meta, flowchart: local.flowchart, handler: local.handler,
      archivedAt: new Date().toISOString(),
    })
    // 2. 下载新版
    const pkg = await cap.server.downloadPackage(skillId)
    if (!pkg) return { ok: false, error: 'download failed', local: local.version }
    // 3. 加载新版（简化：实际由 Rust 侧沙箱注入）
    // ...
    // 4. 上报
    cap.server.reportUpgrade(skillId, local.version, 'NEW', true, null)
    return { ok: true, fromVersion: local.version, toVersion: 'NEW' }
  },

  // 回滚到上一版本
  rollback: async (skillId) => {
    const local = _installedSkills.get(skillId)
    if (!local) return { ok: false, error: 'not installed' }
    // 查归档
    const archiveKeys = cap.storage.keys().filter(k => k.startsWith(_archiveDir + ':' + skillId + ':'))
    if (!archiveKeys.length) return { ok: false, error: 'no archive' }
    // 简化：直接用最新归档
    const archivedVersion = archiveKeys[0].split(':').pop()
    const archived = cap.storage.get(_archiveDir + ':' + skillId + ':' + archivedVersion)
    if (!archived) return { ok: false, error: 'archive missing' }
    _installedSkills.set(skillId, {
      skillId, meta: archived.meta, flowchart: archived.flowchart, handler: archived.handler,
      version: archivedVersion,
      path: null, installedAt: new Date().toISOString(),
    })
    cap.server.reportUpgrade(skillId, local.version, archivedVersion, true, 'rollback')
    return { ok: true, fromVersion: local.version, toVersion: archivedVersion }
  },

  // 按软件名搜索市场
  searchBySoftware: async (softwareName, softwareNameEn) => {
    const q = [softwareName, softwareNameEn].filter(Boolean).join(' ').trim()
    if (!q) return { skills: [], executable: false }
    const skills = await cap.server.searchSkills(q, { softwareName, softwareNameEn })
    return {
      skills: skills || [],
      executable: Array.isArray(skills) && skills.length > 0,
      query: q,
    }
  },
}
mid.register('skill_market.list_installed',      () => cap.skillMarket.listInstalled())
mid.register('skill_market.is_installed',        (p) => cap.skillMarket.isInstalled(p.skillId))
mid.register('skill_market.get_installed',       (p) => cap.skillMarket.getInstalled(p.skillId))
mid.register('skill_market.load',                (p) => cap.skillMarket.load(p.source))
mid.register('skill_market.unload',              (p) => cap.skillMarket.unload(p.skillId))
mid.register('skill_market.check_upgrade',       (p) => cap.skillMarket.checkUpgrade(p.skillId))
mid.register('skill_market.upgrade',             (p) => cap.skillMarket.upgrade(p.skillId))
mid.register('skill_market.rollback',            (p) => cap.skillMarket.rollback(p.skillId))
mid.register('skill_market.search_by_software', (p) => cap.skillMarket.searchBySoftware(p.softwareName, p.softwareNameEn))

// ═══════════════════════════════════════════════════════════════════
// 默认识别降级链顺序
// ═══════════════════════════════════════════════════════════════════
const DEFAULT_RECOGNITION = ['cdp', 'uia', 'ocr', 'vlm']