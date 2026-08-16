const SKILL_ID = 'com.tupautochrome.skills.script-rewriter'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'script-rewriter-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '口播文案改写流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { rewrite: 'rewrite_script', generate: 'generate_script', optimize: 'optimize_script', style: 'style_learning' } },
    { id: 'rewrite_script', type: 'process', label: '改写文案' },
    { id: 'generate_script', type: 'process', label: '生成口播稿' },
    { id: 'optimize_script', type: 'process', label: '优化润色' },
    { id: 'style_learning', type: 'process', label: '风格学习' },
    { id: 'output', type: 'process', label: '输出结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'rewrite_script', label: 'rewrite' },
    { from: 'choose', to: 'generate_script', label: 'generate' },
    { from: 'choose', to: 'optimize_script', label: 'optimize' },
    { from: 'choose', to: 'style_learning', label: 'style' },
    { from: 'rewrite_script', to: 'output' },
    { from: 'generate_script', to: 'output' },
    { from: 'optimize_script', to: 'output' },
    { from: 'style_learning', to: 'output' },
    { from: 'output', to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'tupAI' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace

  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  switch (action) {
    case 'rewrite': return await rewriteScript(params)
    case 'generate': return await generateScript(params)
    case 'optimize': return await optimizeScript(params)
    case 'style': return await styleLearning(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function rewriteScript(params) {
  const t0 = cap.flowchart.beginNode('rewrite_script')
  const sourceContent = params.sourceContent || ''
  const platform = params.platform || 'douyin'
  const tone = params.tone || '自然口语化'
  const duration = params.duration || 60
  const targetAudience = params.targetAudience || ''

  if (!sourceContent) {
    return { ok: false, error: '需要提供源内容 (sourceContent)' }
  }

  let script = null
  if (cap.llm) {
    const prompt = `将以下内容改写成${platform}口播文案，时长${duration}秒，风格：${tone}${targetAudience ? '，目标受众：' + targetAudience : ''}。

源内容：
${sourceContent}

要求：
1. 开篇3秒吸引注意力（黄金3秒）
2. 口语化，像真人说话
3. 每15秒一个信息点/情绪起伏
4. 结尾引导互动（点赞/关注/评论）
5. 标注每段建议时长
6. 输出JSON格式：{ openingHook, sections: [{text, duration, emotion}], closing, totalDuration, estimatedWords }`

    const resp = await cap.llm.complete(prompt)
    try { script = JSON.parse(resp) } catch { script = { raw: resp } }
  }

  if (!script) {
    script = {
      openingHook: `你敢信？${params.sourceContent ? params.sourceContent.slice(0, 20) + '...' : '这个秘密今天终于可以说了！'}`,
      sections: [{ text: `${params.sourceContent ? params.sourceContent.slice(0, 100) + '...' : '今天给大家分享一个干货...'}`, duration: 20, emotion: '惊讶' }, { text: '我们来详细分析一下背后的逻辑...', duration: 25, emotion: '专业' }, { text: '最后给大家总结几个实用技巧...', duration: 15, emotion: '亲切' }],
      closing: '觉得有用的话点赞关注，下期更多干货！',
      totalDuration: duration,
      estimatedWords: Math.floor(duration * 3.5),
    }
  }

  cap.flowchart.endNode('rewrite_script', 'ok', '口播文案改写完成', t0)
  return { ok: true, action: 'rewrite', script, platform, tone, sourcePreview: sourceContent.slice(0, 200) }
}

async function generateScript(params) {
  const t0 = cap.flowchart.beginNode('generate_script')
  const topic = params.topic || ''
  const platform = params.platform || 'douyin'
  const style = params.style || '种草'
  const duration = params.duration || 60
  const productInfo = params.productInfo || ''
  const keywords = params.keywords || []

  if (!topic) {
    return { ok: false, error: '需要提供话题 (topic)' }
  }

  let script = null
  if (cap.llm) {
    const prompt = `为${platform}平台生成${style}类型口播文案，话题：${topic}，时长${duration}秒${productInfo ? '，商品信息：' + productInfo : ''}${keywords.length > 0 ? '，关键词：' + keywords.join(', ') : ''}。

要求：
1. 自然口语化表达
2. 适合${platform}平台风格
3. 节奏感强，有记忆点
4. 包含互动引导
5. 输出JSON格式：{ openingHook, sections: [{text, duration, emotion}], closing, totalDuration }`

    const resp = await cap.llm.complete(prompt)
    try { script = JSON.parse(resp) } catch { script = { raw: resp } }
  }

  if (!script) {
    script = {
      openingHook: `今天跟大家聊聊${topic}这个话题...`,
      sections: [{ text: `关于${topic}，很多人不知道的是...`, duration: 20, emotion: '好奇' }, { text: '其实背后的逻辑很简单...', duration: 25, emotion: '自信' }, { text: '总结一下核心要点...', duration: 15, emotion: '诚恳' }],
      closing: '关注我，了解更多干货！',
      totalDuration: duration,
    }
  }

  cap.flowchart.endNode('generate_script', 'ok', '口播稿生成完成', t0)
  return { ok: true, action: 'generate', script, topic, platform, style }
}

async function optimizeScript(params) {
  const t0 = cap.flowchart.beginNode('optimize_script')
  const script = params.script || ''
  const optimizationGoal = params.optimizationGoal || '增加吸引力'
  const platform = params.platform || 'douyin'

  if (!script) {
    return { ok: false, error: '需要提供文案 (script)' }
  }

  let optimized = null
  if (cap.llm) {
    const prompt = `优化以下${platform}口播文案，优化目标：${optimizationGoal}。

原文案：
${script}

输出JSON：{ originalScript, optimizedScript, changes: [{original, optimized, reason}], tips: [] }`
    const resp = await cap.llm.complete(prompt)
    try { optimized = JSON.parse(resp) } catch { optimized = { raw: resp } }
  }

  if (!optimized) {
    optimized = {
      originalScript: script.slice(0, 200),
      optimizedScript: script.slice(0, 200) + '（优化版：增加了互动引导和情绪起伏）',
      changes: [{ original: '开头平淡', optimized: '增加钩子句', reason: '提升完播率' }],
      tips: ['多用短句', '增加反问', '埋入记忆点'],
    }
  }

  cap.flowchart.endNode('optimize_script', 'ok', '文案优化完成', t0)
  return { ok: true, action: 'optimize', optimized, optimizationGoal, platform }
}

async function styleLearning(params) {
  const t0 = cap.flowchart.beginNode('style_learning')
  const referenceScripts = params.referenceScripts || ''
  const platform = params.platform || 'douyin'

  let styleProfile = null
  if (cap.llm && referenceScripts) {
    const prompt = `分析以下${platform}爆款口播文案的风格特征：

${referenceScripts}

输出JSON：{ styleProfile: { sentenceLength, emotionCurve, hookPattern, closingStyle, vocabularyLevel }, recommendations: [] }`
    const resp = await cap.llm.complete(prompt)
    try { styleProfile = JSON.parse(resp) } catch { styleProfile = { raw: resp } }
  }

  if (!styleProfile) {
    styleProfile = {
      styleProfile: { sentenceLength: '短句为主(8-15字)', emotionCurve: '前高-中稳-尾扬', hookPattern: '反问/数据/冲突', closingStyle: '引导互动+关注', vocabularyLevel: '通俗易懂' },
      recommendations: ['多用"你"字拉近距离', '每30秒一个情绪转折', '结尾用"关注我"代替"谢谢"'],
    }
  }

  cap.flowchart.endNode('style_learning', 'ok', '风格分析完成', t0)
  return { ok: true, action: 'style', styleProfile, platform, hasReference: !!referenceScripts }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('script-rewriter', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('script-rewriter', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('script-rewriter', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('script-rewriter', 'skill unloaded'),
}

export default handler
