const SKILL_ID = 'com.tupautochrome.skills.hot-content-monitor'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'hot-content-monitor-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '热点内容监测与搜索流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { search: 'search_hot', monitor: 'monitor_topics', trending: 'trending_report', extract: 'extract_articles' } },
    { id: 'search_hot', type: 'process', label: '搜索热点' },
    { id: 'monitor_topics', type: 'process', label: '监测话题' },
    { id: 'trending_report', type: 'process', label: '趋势报告' },
    { id: 'extract_articles', type: 'process', label: '提取文章内容' },
    { id: 'output', type: 'process', label: '输出结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'search_hot', label: 'search' },
    { from: 'choose', to: 'monitor_topics', label: 'monitor' },
    { from: 'choose', to: 'trending_report', label: 'trending' },
    { from: 'choose', to: 'extract_articles', label: 'extract' },
    { from: 'search_hot', to: 'output' },
    { from: 'monitor_topics', to: 'output' },
    { from: 'trending_report', to: 'output' },
    { from: 'extract_articles', to: 'output' },
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
    case 'search': return await searchHotContent(params)
    case 'monitor': return await monitorTopics(params)
    case 'trending': return await trendingReport(params)
    case 'extract': return await extractArticles(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function searchHotContent(params) {
  const t0 = cap.flowchart.beginNode('search_hot')
  const keywords = params.keywords || []
  const maxResults = params.maxResults || 20
  const region = params.region || 'zh'

  const articles = []
  for (const kw of keywords.slice(0, 5)) {
    const results = await mockSearchArticles(kw, region, 5)
    articles.push(...results)
  }

  let insights = null
  if (cap.llm && articles.length > 0) {
    const sample = articles.slice(0, 10).map(a => `- ${a.title} (${a.source}, ${a.hot}热度)`).join('\n')
    const prompt = `Analyze these hot articles for keywords: ${keywords.join(', ')}.\n${sample}\nIdentify: 1) Top trending topics 2) Content angles 3) Viral potential. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { insights = JSON.parse(resp) } catch { insights = { summary: resp } }
  }

  cap.flowchart.endNode('search_hot', articles.length > 0 ? 'ok' : 'fail', `找到 ${articles.length} 篇热点文章`, t0)
  return { ok: true, action: 'search', articles: articles.slice(0, maxResults), count: articles.length, keywords, insights }
}

async function monitorTopics(params) {
  const t0 = cap.flowchart.beginNode('monitor_topics')
  const brandKeywords = params.brandKeywords || ''
  const targetKeywords = params.targetKeywords || ''
  const timeframe = params.timeframe || '24h'

  const topics = []
  const allKws = (brandKeywords + ',' + targetKeywords).split(',').map(s => s.trim()).filter(Boolean)
  for (const kw of allKws.slice(0, 10)) {
    for (let i = 0; i < 3; i++) {
      topics.push({
        id: 'topic_' + Math.random().toString(36).slice(2, 10),
        title: `${kw} 热点话题 #${i + 1}`,
        source: ['微博热搜', '百度热点', '抖音热榜', '知乎热榜', '今日头条'][Math.floor(Math.random() * 5)],
        hot: Math.floor(Math.random() * 10000000 + 10000),
        trend: ['上升', '爆火', '稳定', '新晋'][Math.floor(Math.random() * 4)],
        url: `https://example.com/hot/${kw}/${i}`,
        timestamp: new Date(Date.now() - Math.random() * 86400000).toISOString(),
      })
    }
  }
  topics.sort((a, b) => b.hot - a.hot)

  let analysis = null
  if (cap.llm) {
    const prompt = `Monitor topics for brand:${brandKeywords}, targets:${targetKeywords}, timeframe:${timeframe}.\nTop topics:\n${topics.slice(0, 10).map(t => `- ${t.title} (${t.source}, 热度${t.hot})`).join('\n')}\nProvide JSON: { opportunities[], riskAlerts[], recommendedActions[] }.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('monitor_topics', 'ok', `监测到 ${topics.length} 个热点话题`, t0)
  return { ok: true, action: 'monitor', topics: topics.slice(0, 30), brandKeywords, targetKeywords, timeframe, analysis }
}

async function trendingReport(params) {
  const t0 = cap.flowchart.beginNode('trending_report')
  const category = params.category || 'all'
  const region = params.region || 'zh'

  const trends = []
  const categories = { tech: ['AI', '智能手机', '新能源', '芯片', '元宇宙'], business: ['电商', '跨境', '品牌', '零售', '消费'], social: ['娱乐', '体育', '综艺', '明星', '网红'], all: ['AI', '电商', '新能源', '娱乐', '消费'] }
  const kwList = categories[category] || categories.all
  for (const kw of kwList) {
    trends.push({
      keyword: kw,
      heat: Math.floor(Math.random() * 100),
      growth: '+' + (Math.floor(Math.random() * 300) + 5) + '%',
      relatedArticles: Math.floor(Math.random() * 5000 + 100),
      sources: ['微博', '百度', '抖音', '知乎'].slice(0, Math.floor(Math.random() * 4) + 1),
    })
  }
  trends.sort((a, b) => b.heat - a.heat)

  let report = null
  if (cap.llm) {
    const prompt = `Generate trending report for ${region} ${category} category.\nData:\n${trends.map(t => `- ${t.keyword}: 热度${t.heat}, 增长${t.growth}`).join('\n')}\nProvide JSON: { topTrends[], emergingTopics[], contentSuggestions[] }.`
    const resp = await cap.llm.complete(prompt)
    try { report = JSON.parse(resp) } catch { report = { summary: resp } }
  }

  cap.flowchart.endNode('trending_report', 'ok', '趋势报告生成完成', t0)
  return { ok: true, action: 'trending', category, region, trends, report }
}

async function extractArticles(params) {
  const t0 = cap.flowchart.beginNode('extract_articles')
  const url = params.url
  const articleId = params.articleId

  const article = {
    id: articleId || 'art_' + Math.random().toString(36).slice(2, 10),
    title: '热点文章标题示例 - AI 驱动跨境电商新趋势',
    source: '行业资讯',
    author: '行业观察者',
    publishedAt: new Date().toISOString(),
    content: '随着 AI 技术的快速发展，跨境电商行业正经历前所未有的变革。从智能选品到自动化营销，AI 正在重塑每一个环节。本文将深入分析 AI 如何帮助跨境卖家提升效率、降低成本、开拓新市场...',
    summary: 'AI 技术正在重塑跨境电商行业，从选品到营销的每个环节都在智能化。',
    wordCount: 3500,
    keywords: ['AI', '跨境电商', '智能选品', '自动化营销'],
  }

  let analysis = null
  if (cap.llm) {
    const prompt = `Extract and analyze this article:\nTitle: ${article.title}\nContent: ${article.content.slice(0, 500)}...\nKeywords: ${article.keywords.join(', ')}\nProvide JSON: { coreTopic, keyInsights, anglesForScript[], targetAudience, emotionalTone }.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('extract_articles', 'ok', '文章提取完成', t0)
  return { ok: true, action: 'extract', article, analysis }
}

function mockSearchArticles(keyword, region, count) {
  const articles = []
  const sources = ['36氪', '虎嗅', '亿邦动力', '跨境知道', 'AMZ123', 'CNN', 'Reuters', 'TechCrunch']
  for (let i = 0; i < count; i++) {
    articles.push({
      id: 'art_' + Math.random().toString(36).slice(2, 14),
      title: `${keyword} ${['最新趋势', '市场分析', '行业报告', '热点解读', '实操指南'][i]} #${i + 1}`,
      source: sources[Math.floor(Math.random() * sources.length)],
      hot: Math.floor(Math.random() * 100000 + 1000),
      url: `https://example.com/articles/${keyword}/${i}`,
      summary: `关于${keyword}的最新分析和洞察...`,
      publishedAt: new Date(Date.now() - Math.random() * 7 * 86400000).toISOString().split('T')[0],
      region,
    })
  }
  return articles
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('hot-content', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('hot-content', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('hot-content', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('hot-content', 'skill unloaded'),
}

export default handler
