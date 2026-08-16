// ═══════════════════════════════════════════════════════════════════
// 技能运行时 (Skill Runtime) v3.1 — DAG 执行引擎
// ═══════════════════════════════════════════════════════════════════

// ── 变量解析 (带缓存) ──────────────────────────────────────────
const _resolveCache = new Map()
const _RE = /\$\{([^}]+)\}/g

const _resolve = (val, ctx) => {
  if (typeof val !== 'string') return val
  if (!val.includes('${')) return val
  return val.replace(_RE, (_, path) => {
    const expr = path.trim()
    // 1. 先尝试简单路径解析 (vars.x, params.items[0], ...)
    const parts = expr.split('.')
    let obj = ctx
    let pathOk = true
    for (const p of parts) {
      if (obj == null) { pathOk = false; break }
      const m = p.match(/^(\w+)\[(\d+)\]$/)
      if (m) { obj = obj[m[1]]; if (obj == null) { pathOk = false; break }; obj = obj[parseInt(m[2])] }
      else obj = obj[p]
    }
    if (pathOk && obj != null) return String(obj)
    // 2. 路径解析失败 → 尝试 JS 表达式求值 (vars.x * 2, vars.count + 1, ...)
    //    把 ctx.vars / ctx.params 注入求值作用域,防止访问全局变量。
    try {
      const fn = new Function('vars', 'params', 'return (' + expr + ')')
      const result = fn(ctx.vars || {}, ctx.params || {})
      return result != null ? String(result) : ''
    } catch (e) {
      return ''
    }
  })
}

const _deepResolve = (obj, ctx) => {
  if (typeof obj === 'string') return _resolve(obj, ctx)
  if (Array.isArray(obj)) { const r = []; for (let i = 0; i < obj.length; i++) r.push(_deepResolve(obj[i], ctx)); return r }
  if (obj && typeof obj === 'object' && !(obj instanceof Promise)) {
    const k = Object.keys(obj); const r = {}
    for (let i = 0; i < k.length; i++) r[k[i]] = _deepResolve(obj[k[i]], ctx)
    return r
  }
  return obj
}

const _truthy = (v) => {
  if (typeof v === 'boolean') return v
  if (typeof v === 'number') return v !== 0
  const s = String(v).trim().toLowerCase()
  return !(s === '' || s === 'false' || s === '0' || s === 'null' || s === 'undefined')
}

const _evalCondition = (expr, ctx) => _truthy(_resolve(expr, ctx))

// ── DAG 拓扑排序 ────────────────────────────────────────────────
const _topologicalSort = (steps) => {
  const hasDep = steps.some(s => s.depends)
  if (!hasDep) return steps

  const stepMap = {}, inDegree = {}, adj = {}, ids = []
  for (let i = 0; i < steps.length; i++) {
    const id = steps[i].id || 's' + i
    steps[i]._idx = id; stepMap[id] = steps[i]; ids.push(id)
    inDegree[id] = 0; adj[id] = []
  }
  for (let i = 0; i < steps.length; i++) {
    const s = steps[i], deps = s.depends
    if (!deps) continue
    const list = Array.isArray(deps) ? deps : [deps]
    for (const d of list) {
      if (stepMap[d]) { adj[d] = adj[d] || []; adj[d].push(s._idx); inDegree[s._idx]++ }
    }
  }

  const q = ids.filter(id => inDegree[id] === 0), sorted = []
  while (q.length) {
    const id = q.shift(); sorted.push(stepMap[id])
    for (const next of (adj[id] || [])) { if (--inDegree[next] === 0) q.push(next) }
  }
  return sorted.length === steps.length ? sorted : steps
}

// ── 错误策略 ────────────────────────────────────────────────────
const _applyErrorStrategy = async (step, error, ctx, index) => {
  const strategy = step.onError || 'abort'
  const entry = { index, id: step._idx || step.action || step.id || '?', error: error.message, strategy }
  switch (strategy) {
    case 'skip':
      cap.runtime.log('runtime', '[skip] ' + entry.id + ': ' + error.message)
      ctx._errors.push(entry)
      return { skipped: true, error: entry }
    case 'retry': {
      const max = step.retries || 3
      for (let r = 1; r <= max; r++) {
        cap.runtime.log('runtime', '[retry ' + r + '/' + max + '] ' + entry.id)
        await cap.runtime.sleep((step.retryDelay || 1000) * r)
        try { return await _executeStepInner(step, ctx) }
        catch (e) { if (r === max) { ctx._errors.push({ ...entry, error: e.message, retries: r }); throw e } }
      }
      break
    }
    case 'fallback':
      if (step.fallback) {
        cap.runtime.log('runtime', '[fallback] ' + entry.id)
        const fs = Array.isArray(step.fallback) ? step.fallback : [step.fallback]
        const results = []
        for (const f of fs) results.push(await _executeStepInner(f, ctx))
        return results
      }
      throw error
    case 'delegate':
      cap.runtime.log('runtime', '[delegate] ' + entry.id + ': ' + error.message)
      ctx._errors.push(entry)
      return { delegated: true, error: entry }
    default: throw error
  }
}

// ── 执行单步 (内部) ─────────────────────────────────────────────
const _executeStepInner = async (step, ctx) => {
  // 步骤超时
  const timeout = step.timeout || 0
  const exec = async () => {
    const resolved = _deepResolve(step, ctx)

    // Control: script
    if (resolved.action === 'script') {
      const fn = new Function('ctx', 'cap', 'mid', resolved.code || '')
      const r = fn(ctx, cap, mid)
      if (resolved.var) ctx.vars[resolved.var] = r
      return r
    }
    // Control: set
    if (resolved.action === 'set') { ctx.vars[resolved.var] = _resolve(resolved.value, ctx); return ctx.vars[resolved.var] }
    // Control: log
    if (resolved.action === 'log') { cap.runtime.log(resolved.tag || 'skill', _resolve(resolved.text, ctx)); return { logged: true } }
    // Control: sleep
    if (resolved.action === 'sleep') { await cap.runtime.sleep(resolved.ms || resolved.timeout || 1000); return }
    // Control: throw
    if (resolved.action === 'throw') { throw new Error(_resolve(resolved.message || 'manual throw', ctx)) }
    // Control: noop
    if (resolved.action === 'noop' || resolved.action === 'nop') return

    // Software shorthand: { app:'trae', do:'click_send' }
    if (resolved.app && resolved.do) {
      const rSteps = await cap.app.resolve(resolved.app, resolved.do, resolved)
      if (Array.isArray(rSteps)) {
        let last
        for (const rs of rSteps) last = await mid.exec(rs)
        if (resolved.var) ctx.vars[resolved.var] = last
        return last
      }
      const result = await mid.exec(rSteps)
      if (resolved.var) ctx.vars[resolved.var] = result
      return result
    }

    // Standard mid action
    // 把当前 ctx 暂存到 mid._lastCtx,供 `runtime.set` 之类的内置
    // handler 写入 vars (修复前 `_lastCtx` 永远为 null, `runtime.set`
    // 静默失效)。先备份旧值, finally 中恢复,以支持嵌套调用
    // (e.g. fallback 内的 mid.exec 不会污染外层 ctx)。
    const prevCtx = mid._lastCtx
    mid._lastCtx = ctx
    let result
    try {
      result = await mid.exec(resolved)
    } finally {
      mid._lastCtx = prevCtx
    }
    if (resolved.var) ctx.vars[resolved.var] = result
    return result
  }

  if (timeout > 0) {
    return await Promise.race([
      exec(),
      new Promise((_, rej) => setTimeout(() => rej(new Error('step timeout ' + timeout + 'ms')), timeout)),
    ])
  }
  return await exec()
}

// ── 执行单步 (含控制流 + 错误策略) ───────────────────────────────
const _executeStep = async (step, ctx, index) => {
  // if/else
  if (step.if) {
    const met = _evalCondition(step.if, ctx)
    const branch = met ? step.then : step.else
    if (branch) {
      const list = Array.isArray(branch) ? branch : [branch]
      for (const bs of list) await _executeStep(bs, ctx, index + '.b')
    }
    return
  }
  // foreach
  if (step.foreach) {
    const items = _resolve(step.foreach, ctx)
    const list = Array.isArray(items) ? items : []
    if (!Array.isArray(items)) {
      cap.runtime.log('runtime', '[warn] foreach: resolved value is not an array (got ' + typeof items + '), loop body skipped')
    }
    for (let li = 0; li < list.length; li++) {
      ctx.vars._item = list[li]; ctx.vars._index = li
      for (const ls of (step.do || [])) await _executeStep(ls, ctx, index + '.' + li)
    }
    return
  }
  // while
  if (step.while) {
    const max = step.maxIter || 1000; let iter = 0
    while (_evalCondition(step.while, ctx) && iter < max) {
      ctx.vars._iter = iter
      for (const ls of (step.do || [])) await _executeStep(ls, ctx, index + '.w' + iter)
      iter++
    }
    return
  }

  try {
    return await _executeStepInner(step, ctx)
  } catch (e) {
    return await _applyErrorStrategy(step, e, ctx, index)
  }
}

// ═══════════════════════════════════════════════════════════════════
// Public API
// ═══════════════════════════════════════════════════════════════════
const skillRun = {
  version: '3.1',

  async run(skillDef, params) {
    const ctx = {
      params: params || {},
      vars: { ...(skillDef.vars || {}) },
      steps: [], _errors: [], _startTime: Date.now(),
      _maxDepth: 0,
    }

    let steps = skillDef.steps || []
    if (steps.some(s => s.depends)) steps = _topologicalSort(steps)

    for (let i = 0; i < steps.length; i++) {
      const stepId = steps[i]._idx || steps[i].action || steps[i].id || 's' + i
      try {
        const r = await _executeStep(steps[i], ctx, i)
        const status = (r && r.skipped) ? 'skipped' : (r && r.delegated) ? 'delegated' : 'ok'
        ctx.steps.push({ index: i, id: stepId, status })
      } catch (e) {
        ctx.steps.push({ index: i, id: stepId, status: 'error', error: e.message })
        if ((steps[i].onError || 'abort') === 'abort') break
      }
    }

    return {
      vars: ctx.vars, steps: ctx.steps, errors: ctx._errors,
      duration: Date.now() - ctx._startTime,
    }
  },

  async execStep(step, params) {
    const ctx = { params: params || {}, vars: {}, steps: [], _errors: [] }
    return await _executeStep(step, ctx, 0)
  },

  resolve: _resolve,
}

// ── Mid handler registrations ──
// `runtime.set` 修复:
//  1. 取消 `p.value` 真值检查 —— 旧实现用 `if (p.value && ...)` 会
//     静默丢弃 `false`/`0`/`""`/`null`,违反用户意图。
//  2. 仅校验 `p.var` 必须存在 + `mid._lastCtx` 已被 executor 注入。
//     `_lastCtx` 由 `_executeStepInner` 在调 `mid.exec` 前 set,
//     finally 中恢复,处理嵌套调用。
mid.register('runtime.set', (p) => {
  if (!p.var) throw new Error('runtime.set 需要 var 字段')
  if (!mid._lastCtx) throw new Error('runtime.set 在 mid._lastCtx 未初始化时被调用 (executor 路径外?)')
  mid._lastCtx.vars[p.var] = p.value
  return p.value
})
mid.register('runtime.log', (p) => cap.runtime.log(p.tag || 'mid', p.text || ''))
// runtime.script 的 ctx 改用 mid._lastCtx (executor 注入),与
// runtime.set 行为一致;旧实现传空 ctx 导致脚本拿不到 vars/params。
mid.register('runtime.script', (p) => {
  const fn = new Function('ctx', p.code || '')
  const ctx = mid._lastCtx || { vars: {}, params: p }
  return fn({ vars: ctx.vars, params: { ...p, _ctxVars: ctx.vars } })
})
mid.register('runtime.noop', () => {})