// 测试公共辅助工具：在 Node 环境中模拟浏览器运行时（window / localStorage / fetch / crypto），
// 并通过 node:vm + IIFE 包裹的方式加载 capabilities.js 和 trace-auto/index.js 这两个无 export 的脚本。
// 用 IIFE 包裹是为了避免两个文件顶层同名 const（如 DEFAULT_RECOGNITION）在同一个 vm context 里冲突，
// 同时把内部 `const cap = {}` 这种顶层声明通过 return 暴露给外部使用。
// 所有代码注释用中文。

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import vm from 'node:vm'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const ROOT = path.resolve(__dirname, '..')

// 拼接项目内文件的绝对路径（统一用 path.join 保证 Windows 兼容）
export function projectPath(rel) {
  return path.join(ROOT, rel)
}

// 读取项目内文件文本
function readProjectFile(rel) {
  return readFileSync(projectPath(rel), 'utf8')
}

// 创建一个浏览器 mock 沙箱，返回 { sandbox, store, window, fetch, setFetchImpl, eventListeners, navigator, cap }
// store 用于断言 localStorage 内容；setFetchImpl 用于切换 fetch 实现；eventListeners 用来手动触发 window 事件
export function setupSandbox() {
  // ── mock localStorage（简单对象存储） ──
  const store = {}
  const localStorage = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: (k) => { delete store[k] },
    clear: () => { for (const k of Object.keys(store)) delete store[k] },
    get length() { return Object.keys(store).length },
    key: (i) => Object.keys(store)[i] || null,
  }

  // ── mock window + 事件分发 ──
  const eventListeners = {}
  const window = {
    addEventListener: (name, fn) => {
      (eventListeners[name] = eventListeners[name] || []).push(fn)
    },
    removeEventListener: (name, fn) => {
      const arr = eventListeners[name]
      if (arr) eventListeners[name] = arr.filter((f) => f !== fn)
    },
    dispatchEvent: (ev) => {
      const type = ev && (ev.type || (ev.detail && ev.detail.type))
      ;(eventListeners[type] || []).forEach((fn) => fn(ev))
    },
  }

  // ── mock fetch，默认所有请求都失败（r.ok=false），测试可通过 setFetchImpl 切换 ──
  let fetchImpl = async () => ({ ok: false, status: 0, json: async () => null, text: async () => '' })
  const fetch = (url, opts) => fetchImpl(url, opts)

  const crypto = {
    randomUUID: () => 'mock-' + Math.random().toString(36).slice(2, 10),
  }
  const navigator = { platform: 'test' }

  // 空的 cap 占位（capabilities.js 加载后会替换）
  const cap = {}

  // vm context 显式注入常用内建；context 创建后 lexical 声明不会挂到 sandbox 上
  const sandbox = {
    cap,
    window,
    localStorage,
    fetch,
    crypto,
    navigator,
    console,
    setTimeout, clearTimeout, setInterval, clearInterval,
    Date, Math, JSON, Object, Array, Promise, Error, String, Number, Boolean,
    Map, Set, RegExp, URLSearchParams, encodeURIComponent, decodeURIComponent,
    Symbol, Reflect, WeakMap, WeakSet, ArrayBuffer, Uint8Array,
  }
  vm.createContext(sandbox)

  return {
    sandbox,
    cap,
    store,
    window,
    fetch,
    setFetchImpl: (fn) => { fetchImpl = fn },
    eventListeners,
    navigator,
  }
}

// 通用内建符号列表，避免重复书写
const BUILTINS = [
  'Date', 'Math', 'JSON', 'Object', 'Array', 'Promise', 'Error', 'String', 'Number', 'Boolean',
  'Map', 'Set', 'RegExp', 'URLSearchParams', 'encodeURIComponent', 'decodeURIComponent',
  'Symbol', 'Reflect', 'WeakMap', 'WeakSet', 'ArrayBuffer', 'Uint8Array',
]

// 加载 capabilities.js：替换 __GW_URL__ 占位符为测试 URL，避免 Rust 编译期替换
// 用 IIFE 包裹让顶层 const（含 `const cap = {}`、`const DEFAULT_RECOGNITION`）成为函数局部变量，
// 然后通过 return 把 cap / mid 暴露出来，写回 sandbox.cap / sandbox.mid
export function loadCapabilities(sandbox) {
  let src = readProjectFile('src-tauri/src/skills/capabilities.js')
  src = src.replace(/__GW_URL__/g, 'http://test.invalid')
  const wrapped = `(function(window, localStorage, fetch, crypto, navigator, console, setTimeout, clearTimeout, setInterval, clearInterval, ${BUILTINS.join(', ')}) {
${src}
return { cap, mid }
})`
  const factory = vm.runInContext(wrapped, sandbox, { filename: 'capabilities.js' })
  const result = factory(
    sandbox.window, sandbox.localStorage, sandbox.fetch, sandbox.crypto, sandbox.navigator,
    console, setTimeout, clearTimeout, setInterval, clearInterval,
    ...BUILTINS.map((name) => sandbox[name] || globalThis[name]),
  )
  sandbox.cap = result.cap
  sandbox.mid = result.mid
  return result
}

// 加载 trace-auto/index.js：依赖 sandbox.cap 已被 capabilities.js 填充好
// 同样用 IIFE 包裹避免顶层 const 冲突；通过 return 暴露 handler / execute / record / searchSoftware 等
// 注：trace-auto/index.js 末尾有 `export const lifecycle/debug` 和 `export default handler`，
// 这些是 ESM 语法在 vm.runInContext（CJS 模式）下无法解析，会报 "Unexpected token 'export'"。
// 此处通过预处理把 `export ` 关键字剥离，把 `export default X` 转成 `__defaultExport = X`，
// 不影响被测代码本身的运行逻辑，仅是为了在测试沙箱里能加载。
export function loadAIMarketing(sandbox) {
  let src = readProjectFile('skills/trace-auto/index.js')
  // 剥离 ESM export 语法：
  //   `export const X = ...`  → `const X = ...`（X 仍可在 IIFE 内部访问，必要时 return 出来）
  //   `export default X`      → `__defaultExport = X`（通过 return 暴露）
  src = src
    .replace(/^export\s+default\s+/gm, '__defaultExport = ')
    .replace(/^export\s+(const|let|var)\s+/gm, '$1 ')
  const wrapped = `(function(cap, console, setTimeout, clearTimeout, setInterval, clearInterval, ${BUILTINS.join(', ')}) {
${src}
return {
  handler: typeof handler !== 'undefined' ? handler : null,
  execute: typeof execute !== 'undefined' ? execute : null,
  record: typeof record !== 'undefined' ? record : null,
  searchSoftware: typeof searchSoftware !== 'undefined' ? searchSoftware : null,
  getPageState: typeof getPageState !== 'undefined' ? getPageState : null,
  waitIdle: typeof waitIdle !== 'undefined' ? waitIdle : null,
  promptUser: typeof promptUser !== 'undefined' ? promptUser : null,
  checkConditions: typeof checkConditions !== 'undefined' ? checkConditions : null,
  summarizeConditions: typeof summarizeConditions !== 'undefined' ? summarizeConditions : null,
  _legacyAction: typeof _legacyAction !== 'undefined' ? _legacyAction : null,
  _resetControl: typeof _resetControl !== 'undefined' ? _resetControl : null,
  _checkControl: typeof _checkControl !== 'undefined' ? _checkControl : null,
  _summarize: typeof _summarize !== 'undefined' ? _summarize : null,
  FLOWCHART: typeof FLOWCHART !== 'undefined' ? FLOWCHART : null,
  lifecycle: typeof lifecycle !== 'undefined' ? lifecycle : null,
  debug: typeof debug !== 'undefined' ? debug : null,
  defaultExport: typeof __defaultExport !== 'undefined' ? __defaultExport : null,
}
})`
  const factory = vm.runInContext(wrapped, sandbox, { filename: 'trace-auto.js' })
  const result = factory(
    sandbox.cap,
    console, setTimeout, clearTimeout, setInterval, clearInterval,
    ...BUILTINS.map((name) => sandbox[name] || globalThis[name]),
  )
  // 把导出的符号挂到 sandbox，方便测试用 sandbox.handler 直接访问
  sandbox.handler = result.handler
  sandbox.execute = result.execute
  sandbox.record = result.record
  sandbox.searchSoftware = result.searchSoftware
  return result
}

// 一站式：setupSandbox + 加载两个文件，返回合并对象
// 内含：sandbox / cap / store / window / fetch / setFetchImpl / eventListeners / navigator
//       以及 handler / execute / record / searchSoftware / getPageState / FLOWCHART 等
export function loadFullStack() {
  const env = setupSandbox()
  loadCapabilities(env.sandbox)
  const traceExports = loadAIMarketing(env.sandbox)
  // capabilities.js 内部用 `const cap = {}` 重新赋值，sandbox.cap 已是新对象；
  // 重新从 sandbox 取，避免 env.cap 仍指向最初的空占位
  env.cap = env.sandbox.cap
  return { ...env, ...traceExports }
}

// 等待 ms 毫秒（Promise）
export function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}
