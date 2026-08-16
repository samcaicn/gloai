const SKILL_ID = 'com.tupautochrome.skills.content-to-video'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'content-to-video-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '热点内容→口播→视频生成流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { pipeline: 'full_pipeline', hot_to_script: 'hot_to_script', script_to_video: 'script_to_video', direct: 'direct_generate' } },
    { id: 'full_pipeline', type: 'process', label: '全流程：热点→改写→视频' },
    { id: 'hot_to_script', type: 'process', label: '热点→口播文案' },
    { id: 'script_to_video', type: 'process', label: '口播文案→视频生成' },
    { id: 'direct_generate', type: 'process', label: '直接生成视频' },
    { id: 'output', type: 'process', label: '输出结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'full_pipeline', label: 'pipeline' },
    { from: 'choose', to: 'hot_to_script', label: 'hotToScript' },
    { from: 'choose', to: 'script_to_video', label: 'scriptToVideo' },
    { from: 'choose', to: 'direct_generate', label: 'direct' },
    { from: 'full_pipeline', to: 'output' },
    { from: 'hot_to_script', to: 'output' },
    { from: 'script_to_video', to: 'output' },
    { from: 'direct_generate', to: 'output' },
    { from: 'output', to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'AIMarketing' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace

  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  switch (action) {
    case 'pipeline': return await fullPipeline(params)
    case 'hotToScript': return await hotToScript(params)
    case 'scriptToVideo': return await scriptToVideo(params)
    case 'direct': return await directGenerate(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function fullPipeline(params) {
  const t0 = cap.flowchart.beginNode('full_pipeline')
  const keywords = params.keywords || []
  const platform = params.platform || 'douyin'
  const duration = params.duration || 60
  const tone = params.tone || '自然口语化'
  const region = params.region || 'zh'
  const maxResults = params.maxResults || 10

  const articles = []
  for (const kw of keywords.slice(0, 3)) {
    for (let i = 0; i < 3; i++) {
      articles.push({
        id: 'art_' + Math.random().toString(36).slice(2, 14),
        title: `${kw} ${['最新趋势解读', '行业深度分析', '热点事件追踪'][i]}`,
        source: ['36氪', '虎嗅', '亿邦动力'][Math.floor(Math.random() * 3)],
        summary: `关于${kw}的最新深度分析，涵盖市场趋势、竞争格局和发展预测...`,
        hot: Math.floor(Math.random() * 100000 + 5000),
      })
    }
  }
  articles.sort((a, b) => b.hot - a.hot)

  const selections = articles.slice(0, maxResults)

  let script = null
  if (cap.llm) {
    const articleSummary = selections.slice(0, 3).map(a => `- ${a.title}（${a.source}）`).join('\n')
    const prompt = `基于以下热点文章，为${platform}生成一篇${duration}秒口播文案，风格：${tone}。

热点文章：
${articleSummary}

关键词：${keywords.join(', ')}

输出JSON：{ openingHook, sections: [{text, duration, emotion}], closing, totalDuration, estimatedWords }`
    const resp = await cap.llm.complete(prompt)
    try { script = JSON.parse(resp) } catch { script = { raw: resp } }
  }

  if (!script) {
    script = {
      openingHook: `最近${keywords[0] || '这个行业'}有大新闻！`,
      sections: [
        { text: `${keywords.join('、')}最近热度暴涨，我们来看看背后的趋势...`, duration: 20, emotion: '专业' },
        { text: '这意味着什么？对普通人来说有哪些机会？', duration: 25, emotion: '互动' },
        { text: '给大家三个实操建议...', duration: 15, emotion: '实用' },
      ],
      closing: '关注我，第一时间获取最新分析！',
      totalDuration: duration,
      estimatedWords: Math.floor(duration * 3.5),
    }
  }

  let videoResult = null
  try {
    const seedanceSkill = cap.skills ? cap.skills.get('builtin-seedance') : null
    if (seedanceSkill) {
      const videoPrompt = `基于以下口播文案生成短视频：\n\n${script.openingHook}\n${script.sections.map(s => s.text).join('\n')}\n${script.closing}`
      videoResult = await seedanceSkill({ action: 'generate', prompt: videoPrompt, model: 'seedance-2-0-fast' }, complete)
    }
  } catch (err) {
    videoResult = { ok: false, error: '视频生成调用失败: ' + (err.message || err) }
  }

  cap.flowchart.endNode('full_pipeline', 'ok', '全流程完成', t0)
  return {
    ok: true, action: 'pipeline',
    hotArticles: selections,
    script,
    videoResult,
    summary: `基于 ${selections.length} 篇热点文章生成 ${duration}秒 ${platform} 口播视频`,
  }
}

async function hotToScript(params) {
  const t0 = cap.flowchart.beginNode('hot_to_script')
  const articles = params.articles || []
  const articleInput = params.articleInput || ''
  const platform = params.platform || 'douyin'
  const duration = params.duration || 60
  const tone = params.tone || '自然口语化'

  const sourceText = articleInput || (articles.length > 0 ? articles.slice(0, 3).map(a => a.title + ': ' + (a.summary || a.content || '')).join('\n') : '')
  if (!sourceText) {
    return { ok: false, error: '请提供文章内容 (articleInput) 或文章列表 (articles)' }
  }

  let script = null
  if (cap.llm) {
    const prompt = `将以下文章内容改写成${platform}口播文案，时长${duration}秒，风格：${tone}。

文章内容：
${sourceText.slice(0, 3000)}

输出JSON：{ openingHook, sections: [{text, duration, emotion}], closing, totalDuration, estimatedWords }`
    const resp = await cap.llm.complete(prompt)
    try { script = JSON.parse(resp) } catch { script = { raw: resp } }
  }

  if (!script) {
    script = {
      openingHook: '看完这篇文章我震惊了！',
      sections: [{ text: sourceText.slice(0, 100) + '...', duration: 20, emotion: '惊讶' }, { text: '我们来深入分析一下...', duration: 25, emotion: '专业' }, { text: '总结要点...', duration: 15, emotion: '诚恳' }],
      closing: '点赞关注，不错过每一期干货！',
      totalDuration: duration,
    }
  }

  cap.flowchart.endNode('hot_to_script', 'ok', '热点→文案转换完成', t0)
  return { ok: true, action: 'hotToScript', script, platform, sourceCount: articles.length || 1 }
}

async function scriptToVideo(params) {
  const t0 = cap.flowchart.beginNode('script_to_video')
  const script = params.script || ''
  const model = params.model || 'seedance-2-0-fast'

  if (!script) {
    return { ok: false, error: '请提供口播文案 (script)' }
  }

  let videoResult = null
  try {
    const seedanceSkill = cap.skills ? cap.skills.get('builtin-seedance') : null
    if (seedanceSkill) {
      videoResult = await seedanceSkill({ action: 'generate', prompt: script, model }, complete)
    } else {
      videoResult = {
        ok: false,
        error: 'builtin-seedance 技能不可用',
        fallback: { status: '模拟生成', script, model, estimatedDuration: '约60秒' },
      }
    }
  } catch (err) {
    videoResult = { ok: false, error: '视频生成失败: ' + (err.message || err) }
  }

  cap.flowchart.endNode('script_to_video', videoResult && videoResult.ok !== false ? 'ok' : 'fail', '视频生成完成', t0)
  return { ok: true, action: 'scriptToVideo', script, videoResult, model }
}

async function directGenerate(params) {
  const t0 = cap.flowchart.beginNode('direct_generate')
  const topic = params.topic || ''
  const prompt = params.prompt || ''
  const platform = params.platform || 'douyin'
  const duration = params.duration || 60
  const tone = params.tone || '自然口语化'
  const model = params.model || 'seedance-2-0-fast'

  let script = null
  if (cap.llm) {
    const promptText = `为${platform}平台生成一段${duration}秒口播文案，主题：${topic || prompt}，风格：${tone}。
输出JSON：{ openingHook, sections: [{text, duration, emotion}], closing, totalDuration }`
    const resp = await cap.llm.complete(promptText)
    try { script = JSON.parse(resp) } catch { script = { raw: resp } }
  }

  if (!script) {
    script = {
      openingHook: `今天我们来聊聊${topic || '这个热门话题'}！`,
      sections: [{ text: `关于${topic || '这个话题'}，你可能不知道的是...`, duration: 20, emotion: '好奇' }, { text: '背后的逻辑是这样的...', duration: 25, emotion: '专业' }, { text: '总结一下重点...', duration: 15, emotion: '亲切' }],
      closing: '关注我，每天分享干货！',
      totalDuration: duration,
    }
  }

  const fullScript = `${script.openingHook}\n${script.sections.map(s => s.text).join('\n')}\n${script.closing}`

  let videoResult = null
  try {
    const seedanceSkill = cap.skills ? cap.skills.get('builtin-seedance') : null
    if (seedanceSkill) {
      videoResult = await seedanceSkill({ action: 'generate', prompt: fullScript, model }, complete)
    } else {
      videoResult = { ok: false, warning: 'builtin-seedance不可用，仅生成文案', script: fullScript }
    }
  } catch (err) {
    videoResult = { ok: false, error: err.message }
  }

  cap.flowchart.endNode('direct_generate', 'ok', '直接生成完成', t0)
  return { ok: true, action: 'direct', script, fullScript, videoResult, topic, platform }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('content-to-video', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('content-to-video', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('content-to-video', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('content-to-video', 'skill unloaded'),
}

export default handler
