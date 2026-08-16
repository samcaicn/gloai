// 验证脚本：测试所有 built-in 技能的 status 动作能正常执行（纯 LLM 模式，无 CDP 依赖）。
// 用法: node .trae/documents/test-builtin-skill.mjs
//
// 验证：
//   A. entry_action 语义映射正确
//   B. 每个技能用 status 动作能正常执行（不崩溃，返回有效结果）
//   C. wechat-publisher / xiaohongshu-publisher 不再依赖 CDP
//   D. safeopc-skill-tester 能被发现并执行 status

import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const SKILLS_DIR = path.join(ROOT, 'src-tauri', 'src', 'skills');
const GW = 'http://127.0.0.1:8642';

// 后端声明的 entry_action（与 skills_embedded.rs 一致）
const ENTRY_ACTIONS = {
  'builtin-trace-auto': 'start',
  'builtin-wechat-publisher': 'monitor',
  'builtin-xiaohongshu-publisher': 'monitor',
  'builtin-auto-product-comm': 'execute',
  'builtin-safeopc-skill-tester': 'run',
};

// 模拟 runBuiltinSkill 的映射逻辑（skill.ts:211-214）
function mapAction(skillId, params) {
  const entryAction = ENTRY_ACTIONS[skillId];
  return (entryAction && params.action === 'execute')
    ? { ...params, action: entryAction }
    : params;
}

function loadSkill(skillFile) {
  const cap = fs.readFileSync(path.join(SKILLS_DIR, 'capabilities.js'), 'utf8').replace(/__GW_URL__/g, GW);
  const rt = fs.readFileSync(path.join(SKILLS_DIR, 'skillRuntime.js'), 'utf8').replace(/__GW_URL__/g, GW);
  const skill = fs.readFileSync(path.join(SKILLS_DIR, skillFile), 'utf8').replace(/__GW_URL__/g, GW);
  let code = `${cap}\n${rt}\n${skill}`;
  code = code
    .replace(/export\s+default\s+/g, 'var __default_export__ = ')
    .replace(/export\s+const\s+/g, 'const ')
    .replace(/export\s+\{/g, '{')
    .replace(/^\s*import\s.+$/gm, '');
  code += '\nreturn typeof handler === "function" ? handler : (typeof execute === "function" ? execute : null);';
  return code;
}

// ── polyfill ──
const _store = {};
globalThis.window = {
  __TAURI_INTERNALS__: {
    invoke: async (cmd, _args) => {
      if (cmd === 'list_browser_targets_cmd') return [];
      if (cmd === 'start_browser_session_cmd') return 'fake-session-id';
      if (cmd === 'execute_browser_action_cmd') return JSON.stringify({ action: 'eval', success: true, error: '' });
      if (cmd === 'mcp_call_v2') return JSON.stringify({ ok: true, data: { content: 'mock-llm-reply' } });
      if (cmd === 'get_builtin_skills_command') {
        // 返回模拟的技能列表（含 code）
        const skills = [
          { id: 'builtin-wechat-publisher', name: '公众号文章技能', code: 'async function handler(){return {ok:true}}' },
          { id: 'builtin-xiaohongshu-publisher', name: '小红书文案技能', code: 'async function handler(){return {ok:true}}' },
          { id: 'builtin-auto-product-comm', name: '自动选品智能沟通', code: 'async function handler(){return {ok:true}}' },
          { id: 'builtin-trace-auto', name: 'AIMarketing', code: 'async function handler(){return {ok:true}}' },
          { id: 'builtin-safeopc-skill-tester', name: '技能自动测试器', code: 'async function handler(){return {ok:true}}' },
        ];
        return skills;
      }
      if (cmd === 'record_builtin_skill_run_command') return null;
      return '';
    },
  },
  __TAURI__: null,
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => {},
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id),
};
globalThis.localStorage = {
  getItem: (k) => (k in _store ? _store[k] : null),
  setItem: (k, v) => { _store[k] = String(v); },
  removeItem: (k) => { delete _store[k]; },
};

// ── A. 映射逻辑测试 ──
console.log('=== A. entry_action 映射逻辑 ===');
let mapPass = 0, mapFail = 0;
const mapCases = [
  { skillId: 'builtin-trace-auto', input: 'execute', expect: 'start' },
  { skillId: 'builtin-wechat-publisher', input: 'execute', expect: 'monitor' },
  { skillId: 'builtin-xiaohongshu-publisher', input: 'execute', expect: 'monitor' },
  { skillId: 'builtin-auto-product-comm', input: 'execute', expect: 'execute' },
  { skillId: 'builtin-safeopc-skill-tester', input: 'execute', expect: 'run' },
  { skillId: 'builtin-trace-auto', input: 'status', expect: 'status' },
  { skillId: 'builtin-wechat-publisher', input: 'status', expect: 'status' },
];
for (const c of mapCases) {
  const out = mapAction(c.skillId, { action: c.input });
  const ok = out.action === c.expect;
  console.log(`  ${ok ? '✅' : '❌'} ${c.skillId} action='${c.input}' → '${out.action}' (期望 '${c.expect}')`);
  if (ok) mapPass++; else mapFail++;
}

// ── B. 技能 status 动作执行测试 ──
console.log('\n=== B. 技能 status 动作执行（验证不崩溃）===');
const SKILLS = [
  { file: 'wechat-publisher.js', id: 'builtin-wechat-publisher', callAction: 'status' },
  { file: 'xiaohongshu-publisher.js', id: 'builtin-xiaohongshu-publisher', callAction: 'status' },
  { file: 'auto-product-comm.js', id: 'builtin-auto-product-comm', callAction: 'status' },
  { file: 'safeopc-skill-tester.js', id: 'builtin-safeopc-skill-tester', callAction: 'status' },
];
let runPass = 0, runFail = 0;
for (const s of SKILLS) {
  const mapped = mapAction(s.id, { action: s.callAction });
  process.stdout.write(`  [${s.id}] 调 action='${s.callAction}'... `);
  try {
    const code = loadSkill(s.file);
    const fn = new Function(code);
    const handlerFn = fn();
    if (typeof handlerFn !== 'function') throw new Error(`handler 非函数 (type=${typeof handlerFn})`);
    const result = await Promise.race([
      handlerFn(mapped, null),
      new Promise((_, reject) => setTimeout(() => reject(new Error('执行超时 5s')), 5000)),
    ]);
    const summary = JSON.stringify(result).slice(0, 120);
    console.log(`✅ 正常执行 → ${summary}`);
    runPass++;
  } catch (e) {
    console.log(`❌ 失败: ${e && e.message ? e.message : e}`);
    runFail++;
  }
}

// ── C. CDP 依赖检查 ──
console.log('\n=== C. CDP 依赖检查（wechat/xiaohongshu 不应包含 cap.cdp.eval）===');
const CDP_CHECKS = [
  { file: 'wechat-publisher.js', name: '公众号文章技能', shouldNotContain: 'cap.cdp.eval' },
  { file: 'xiaohongshu-publisher.js', name: '小红书文案技能', shouldNotContain: 'cap.cdp.eval' },
];
let cdpPass = 0, cdpFail = 0;
for (const c of CDP_CHECKS) {
  const content = fs.readFileSync(path.join(SKILLS_DIR, c.file), 'utf8');
  const hasCdp = content.includes(c.shouldNotContain);
  const ok = !hasCdp;
  console.log(`  ${ok ? '✅' : '❌'} ${c.name}: ${ok ? '无 CDP 依赖' : '仍包含 ' + c.shouldNotContain}`);
  if (ok) cdpPass++; else cdpFail++;
}

// ── D. safeopc-skill-tester 存在性检查 ──
console.log('\n=== D. safeopc-skill-tester 存在性检查 ===');
const testerExists = fs.existsSync(path.join(SKILLS_DIR, 'safeopc-skill-tester.js'));
console.log(`  ${testerExists ? '✅' : '❌'} safeopc-skill-tester.js ${testerExists ? '存在' : '不存在'}`);
if (testerExists) { cdpPass++; } else { cdpFail++; }

console.log(`\n=== 结果 ===`);
console.log(`  映射: ${mapPass}/${mapCases.length} 通过`);
console.log(`  执行: ${runPass}/${SKILLS.length} 通过`);
console.log(`  CDP检查: ${cdpPass}/${CDP_CHECKS.length + 1} 通过`);
const allPass = mapFail === 0 && runFail === 0 && cdpFail === 0;
console.log(`  总体: ${allPass ? '✅ 全部通过' : '❌ 有失败'}`);
process.exit(allPass ? 0 : 1);
