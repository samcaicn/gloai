// bugfix-12-13.test.js — 针对 Bug #12（WhatsApp 扫码轮询）和 #13（sceneTags 依赖重复触发）的验证测试
// 运行方式: node --experimental-vm-modules tests/bugfix-12-13.test.js

import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'

// ── 辅助：从源码中提取关键片段验证修复 ──
function readFile(rel) {
  return readFileSync(new URL(`../${rel}`, import.meta.url), 'utf8')
}

let passed = 0
let failed = 0

function test(name, fn) {
  try {
    fn()
    console.log(`  \x1b[32m✓\x1b[0m ${name}`)
    passed++
  } catch (e) {
    console.log(`  \x1b[31m✗\x1b[0m ${name}`)
    console.log(`    ${e.message}`)
    failed++
  }
}

// ══════════════════════════════════════════════════════════════
// Bug #13: sceneTags 不应出现在 useEffect 依赖数组中
// ══════════════════════════════════════════════════════════════
console.log('\n\x1b[1mBug #13 — sceneTags 依赖修复\x1b[0m')

test('sceneTags 不在搜索 useEffect 的依赖数组中', () => {
  const src = readFile('src/HomePage.jsx')
  // 找到搜索 useEffect 的依赖数组
  // 应该匹配 }, [query, token, sceneTagsStatus, mode]) 而不是含 sceneTags
  const effectBlock = src.match(/\/\/ 搜索 \/ 推荐数据获取[\s\S]*?\},\s*\[([^\]]+)\]/)
  assert.ok(effectBlock, '应找到搜索 useEffect 的依赖数组')
  const deps = effectBlock[1]
  // 用词边界检查：sceneTags 不应作为独立依赖出现（sceneTagsStatus 是允许的）
  const depList = deps.split(',').map(d => d.trim())
  const hasSceneTags = depList.includes('sceneTags')
  assert.ok(!hasSceneTags, `依赖数组不应包含 sceneTags，实际: [${deps}]`)
  assert.ok(depList.includes('sceneTagsStatus'), `依赖数组应包含 sceneTagsStatus，实际: [${deps}]`)
  assert.ok(depList.includes('query'), `依赖数组应包含 query`)
  assert.ok(depList.includes('token'), `依赖数组应包含 token`)
  assert.ok(depList.includes('mode'), `依赖数组应包含 mode`)
})

test('sceneTagsRef 被声明并用于读取最新值', () => {
  const src = readFile('src/HomePage.jsx')
  assert.ok(src.includes('const sceneTagsRef = useRef(sceneTags)'), '应声明 sceneTagsRef')
  assert.ok(src.includes('sceneTagsRef.current = sceneTags'), '应同步 sceneTags 到 ref')
  assert.ok(src.includes('sceneTagsRef.current.map(t => t.tag)'), '搜索逻辑应通过 sceneTagsRef.current 读取标签')
})

test('场景标签推荐搜索不再直接引用 sceneTags 数组', () => {
  const src = readFile('src/HomePage.jsx')
  // 在搜索 effect 内部，不应有 sceneTags.map 调用（应为 sceneTagsRef.current.map）
  const searchEffectMatch = src.match(/if \(mode === 'chat'\)[\s\S]*?else \{[\s\S]*?\/\/ 无关键词[\s\S]*?\}/)
  assert.ok(searchEffectMatch, '应找到搜索 effect 中的 else 分支')
  assert.ok(!searchEffectMatch[0].includes('sceneTags.map('), 'else 分支不应直接使用 sceneTags.map')
  assert.ok(searchEffectMatch[0].includes('sceneTagsRef.current.map('), 'else 分支应使用 sceneTagsRef.current.map')
})

// ══════════════════════════════════════════════════════════════
// Bug #12: WhatsApp QR 扫码轮询
// ══════════════════════════════════════════════════════════════
console.log('\n\x1b[1mBug #12 — WhatsApp 扫码轮询修复\x1b[0m')

test('mcpClient.js 导出 checkWhatsappQrcodeStatus 函数', () => {
  const src = readFile('src/mcpClient.js')
  assert.ok(src.includes('export async function checkWhatsappQrcodeStatus'), '应导出 checkWhatsappQrcodeStatus')
  assert.ok(src.includes("invoke('check_whatsapp_qrcode_status'"), '应调用 check_whatsapp_qrcode_status Tauri 命令')
})

test('SettingsModal.jsx 导入 checkWhatsappQrcodeStatus', () => {
  const src = readFile('src/SettingsModal.jsx')
  assert.ok(src.includes('checkWhatsappQrcodeStatus'), '应导入 checkWhatsappQrcodeStatus')
})

test('WhatsApp 分支包含轮询逻辑', () => {
  const src = readFile('src/SettingsModal.jsx')
  // 找到 whatsapp 分支
  const whatsappBlock = src.match(/platform === 'whatsapp'\)[\s\S]*?(?=}\s*(?:catch|setQrLoading))/)
  assert.ok(whatsappBlock, '应找到 whatsapp 分支代码块')
  const block = whatsappBlock[0]
  assert.ok(block.includes('checkWhatsappQrcodeStatus'), 'whatsapp 分支应调用 checkWhatsappQrcodeStatus')
  assert.ok(block.includes('setInterval'), 'whatsapp 分支应启动轮询 (setInterval)')
  assert.ok(block.includes('stopQrPolling'), 'whatsapp 分支应支持停止轮询')
  assert.ok(block.includes("'confirmed'") || block.includes("'success'"), 'whatsapp 分支应检测扫码成功状态')
  assert.ok(block.includes("'expired'"), 'whatsapp 分支应检测二维码过期状态')
})

test('WhatsApp 轮询包含 taskId 提取', () => {
  const src = readFile('src/SettingsModal.jsx')
  const whatsappBlock = src.match(/platform === 'whatsapp'\)[\s\S]*?(?=}\s*(?:catch|setQrLoading))/)
  assert.ok(whatsappBlock, '应找到 whatsapp 分支')
  const block = whatsappBlock[0]
  assert.ok(block.includes('taskId'), 'whatsapp 分支应提取 taskId 用于轮询')
})

// ══════════════════════════════════════════════════════════════
// 交叉验证：修复不引入新问题
// ══════════════════════════════════════════════════════════════
console.log('\n\x1b[1m交叉验证\x1b[0m')

test('sceneTags useEffect (获取标签) 仍保留 sceneTags 依赖', () => {
  const src = readFile('src/HomePage.jsx')
  // 第一个 useEffect（获取场景标签）应依赖 [token]
  const tagEffectMatch = src.match(/进入首页即根据 token[\s\S]*?\},\s*\[([^\]]+)\]/)
  assert.ok(tagEffectMatch, '应找到场景标签获取 useEffect')
  // 这个 effect 的依赖是 [token]，不需要 sceneTags
  assert.ok(tagEffectMatch[1].includes('token'), '场景标签获取 effect 应依赖 token')
})

test('WhatsApp 与微信/QQ 轮询模式一致', () => {
  const src = readFile('src/SettingsModal.jsx')
  // 检查三个平台都有轮询
  const hasWeixinPoll = src.includes("platform === 'weixin'") && src.includes('checkWeixinQrcodeStatus')
  const hasQqbotPoll = src.includes("platform === 'qqbot'") && src.includes('checkQqbotQrcodeStatus')
  const hasWhatsappPoll = src.includes("platform === 'whatsapp'") && src.includes('checkWhatsappQrcodeStatus')
  assert.ok(hasWeixinPoll, '微信应有轮询')
  assert.ok(hasQqbotPoll, 'QQ Bot 应有轮询')
  assert.ok(hasWhatsappPoll, 'WhatsApp 应有轮询')
})

// ── 结果汇总 ──
console.log(`\n\x1b[1m结果: ${passed} 通过, ${failed} 失败\x1b[0m\n`)
process.exit(failed > 0 ? 1 : 0)
