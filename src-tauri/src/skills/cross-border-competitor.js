const SKILL_ID = 'com.tupautochrome.skills.cross-border-competitor'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'cross-border-competitor-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '跨境竞品分析流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { search: 'find_competitors', analyze: 'deep_analysis', monitor: 'price_monitoring', compare: 'comparison', landscape: 'market_landscape' } },
    { id: 'find_competitors', type: 'process', label: '发现竞品' },
    { id: 'deep_analysis', type: 'process', label: '深度分析' },
    { id: 'price_monitoring', type: 'process', label: '价格监控' },
    { id: 'comparison', type: 'process', label: '竞品对比' },
    { id: 'market_landscape', type: 'process', label: '市场格局' },
    { id: 'report', type: 'process', label: '生成报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'find_competitors', label: 'search' },
    { from: 'choose', to: 'deep_analysis', label: 'analyze' },
    { from: 'choose', to: 'price_monitoring', label: 'monitor' },
    { from: 'choose', to: 'comparison', label: 'compare' },
    { from: 'choose', to: 'market_landscape', label: 'landscape' },
    { from: 'find_competitors', to: 'report' },
    { from: 'deep_analysis', to: 'report' },
    { from: 'price_monitoring', to: 'report' },
    { from: 'comparison', to: 'report' },
    { from: 'market_landscape', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'AIMarketing' },
}

const PLATFORM_CONFIG = {
  amazon: { domain: 'amazon.com', currency: 'USD' },
  ebay: { domain: 'ebay.com', currency: 'USD' },
  shopee: { domain: 'shopee.com', currency: 'USD' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace

  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  switch (action) {
    case 'search': return await findCompetitors(params)
    case 'analyze': return await deepAnalyze(params)
    case 'monitor': return await priceMonitor(params)
    case 'compare': return await compareCompetitors(params)
    case 'landscape': return await marketLandscape(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function findCompetitors(params) {
  const t0 = cap.flowchart.beginNode('find_competitors')
  const keywords = params.keywords || []
  const platforms = params.platforms || ['amazon']
  const maxResults = params.maxResults || 20
  const allCompetitors = []

  for (const platform of platforms) {
    for (const kw of keywords.slice(0, 2)) {
      const data = await searchPlatformCompetitors(kw, platform)
      allCompetitors.push(...data.map(d => ({ ...d, platform })))
    }
  }

  const competitors = allCompetitors.slice(0, maxResults)

  let insights = null
  if (cap.llm && competitors.length > 0) {
    const summary = competitors.map(c => `- [${c.platform}] ${c.title} $${c.price} rating:${c.rating} reviews:${c.reviews}`).join('\n')
    const prompt = `Analyze competitive landscape for keywords: ${keywords.join(', ')} across platforms: ${platforms.join(', ')}.\n${summary}\nIdentify: 1) Key players 2) Price tiers 3) Gaps/opportunities 4) Market concentration. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { insights = JSON.parse(resp) } catch { insights = { summary: resp } }
  }

  cap.flowchart.endNode('find_competitors', competitors.length > 0 ? 'ok' : 'fail', `找到 ${competitors.length} 个竞品`, t0)
  return { ok: true, action: 'search', competitors, count: competitors.length, insights }
}

async function deepAnalyze(params) {
  const t0 = cap.flowchart.beginNode('deep_analysis')
  const asin = params.asin
  const platform = params.platform || 'amazon'
  if (!asin) return { ok: false, error: 'asin required' }

  const detail = await fetchPlatformDetail(asin, platform)
  if (!detail) return { ok: false, error: 'product not found' }

  const reviews = await fetchPlatformReviews(asin, platform, 30)
  detail.reviews = reviews

  let analysis = null
  if (cap.llm) {
    const prompt = `Deep competitor analysis for ${platform} product ${asin}:\nTitle: ${detail.title}\nPrice: $${detail.price}\nBSR: ${detail.bsr}\nRating: ${detail.rating} (${detail.reviewCount} reviews)\nReview samples: ${reviews.slice(0, 10).map(r => r.text?.slice(0, 100)).join(' | ')}\n\nProvide JSON: { strengths[], weaknesses[], keywordStrategy[], pricingStrategy[], recommendation }.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('deep_analysis', 'ok', '深度分析完成', t0)
  return { ok: true, action: 'analyze', asin, platform, detail, analysis }
}

async function priceMonitor(params) {
  const t0 = cap.flowchart.beginNode('price_monitoring')
  const asins = params.asins || []
  const platform = params.platform || 'amazon'

  const history = await cap.storage.get('price_history_' + platform) || {}
  const now = Date.now()
  const monitoring = []

  for (const asin of asins) {
    const current = await fetchPlatformPrice(asin, platform)
    const prev = history[asin] || []
    const lastPrice = prev.length > 0 ? prev[prev.length - 1].price : current.price
    const change = current.price - lastPrice

    history[asin] = [...prev.slice(-10), { price: current.price, time: now }]
    monitoring.push({
      asin, platform,
      currentPrice: '$' + current.price.toFixed(2),
      previousPrice: '$' + lastPrice.toFixed(2),
      change: '$' + change.toFixed(2),
      changePercent: ((change / (lastPrice || 1)) * 100).toFixed(1) + '%',
      trend: change > 0 ? 'up' : change < 0 ? 'down' : 'stable',
      lastChecked: new Date(now).toISOString(),
    })
  }

  await cap.storage.set('price_history_' + platform, history)

  cap.flowchart.endNode('price_monitoring', 'ok', `监控 ${asins.length} 个竞品价格`, t0)
  return { ok: true, action: 'monitor', monitoring, platform }
}

async function compareCompetitors(params) {
  const t0 = cap.flowchart.beginNode('comparison')
  const asins = params.asins || []
  const platform = params.platform || 'amazon'
  if (asins.length < 2) return { ok: false, error: 'need at least 2 asins' }

  const details = []
  for (const asin of asins) {
    const d = await fetchPlatformDetail(asin, platform)
    if (d) details.push({ asin, ...d })
  }

  const comparison = buildComparisonMatrix(details)

  let swot = null
  if (cap.llm && details.length > 0) {
    const prompt = `Compare these ${platform} competitors: ${JSON.stringify(details.map(d => ({ title: d.title, price: d.price, rating: d.rating, reviews: d.reviewCount, bsr: d.bsr })))}\nProvide JSON: { bestOverall, bestValue, mostPremium, recommendation, marketPosition[] }.`
    const resp = await cap.llm.complete(prompt)
    try { swot = JSON.parse(resp) } catch { swot = { summary: resp } }
  }

  cap.flowchart.endNode('comparison', 'ok', '对比完成', t0)
  return { ok: true, action: 'compare', platform, details, comparison, swot }
}

async function marketLandscape(params) {
  const t0 = cap.flowchart.beginNode('market_landscape')
  const keywords = params.keywords || []
  const platform = params.platform || 'amazon'

  const products = await searchPlatformCompetitors(keywords[0] || '', platform)
  const landscape = buildLandscapeAnalysis(products)

  let report = null
  if (cap.llm && products.length > 0) {
    const prompt = `Analyze market landscape for "${keywords.join(', ')}" on ${platform}. Total products scanned: ${products.length}.\nPrice range: $${landscape.minPrice}-$${landscape.maxPrice}, avg: $${landscape.avgPrice}\nProvide JSON: { marketType (fragmented/consolidated), entryDifficulty (1-10), recommendedPrice, keySuccessFactors[], riskFactors[] }.`
    const resp = await cap.llm.complete(prompt)
    try { report = JSON.parse(resp) } catch { report = { summary: resp } }
  }

  cap.flowchart.endNode('market_landscape', 'ok', '市场格局分析完成', t0)
  return { ok: true, action: 'landscape', keywords, platform, landscape, report }
}

async function searchPlatformCompetitors(keyword, platform) {
  const products = []
  const brands = ['TechPro', 'SoundMax', 'AudioElite', 'BassBoost', 'ClearSound', 'EchoWave', 'SoundPulse', 'TuneMaster']
  for (let i = 0; i < 15; i++) {
    products.push({
      asin: 'B0' + String(Math.random()).slice(2, 10),
      title: `${brands[i % brands.length]} ${keyword} - ${['Professional', 'Premium', 'Ultra', 'Pro Max', 'Elite'][i % 5]}`,
      price: (Math.random() * 80 + 9.99).toFixed(2),
      rating: (3.5 + Math.random() * 1.5).toFixed(1),
      reviews: Math.floor(Math.random() * 15000 + 10),
      bsr: '#' + Math.floor(Math.random() * 5000 + 1),
      seller: brands[i % brands.length],
      fulfillment: ['FBA', 'FBM', 'FBA'][i % 3],
    })
  }
  return products
}

async function fetchPlatformDetail(asin, platform) {
  return {
    asin,
    title: `Premium ${platform.toUpperCase()} Product ${asin}`,
    price: parseFloat((Math.random() * 80 + 9.99).toFixed(2)),
    bsr: '#' + Math.floor(Math.random() * 5000 + 1),
    rating: (3.5 + Math.random() * 1.5).toFixed(1),
    reviewCount: Math.floor(Math.random() * 10000 + 10),
    category: 'Electronics',
    seller: 'Brand_' + asin.slice(-4),
    fulfillment: 'FBA',
    dimensions: '10x8x2 inches',
    weight: '0.5 lbs',
    features: ['Feature 1', 'Feature 2', 'Feature 3'],
    images: 5,
  }
}

async function fetchPlatformPrice(asin, platform) {
  return { asin, price: parseFloat((Math.random() * 80 + 9.99).toFixed(2)), currency: 'USD' }
}

async function fetchPlatformReviews(asin, platform, max) {
  const reviews = []
  for (let i = 0; i < Math.min(max, 30); i++) {
    const positives = ['Great product, highly recommend!', 'Better than expected, will buy again.', 'Good quality for the price.', 'Perfect fit and works well.', 'Amazing sound quality!']
    const negatives = ['Not as described, disappointed.', 'Stopped working after a week.', 'Poor quality control.', 'Overpriced for what you get.', 'Difficult to set up.']
    const isPositive = Math.random() > 0.3
    reviews.push({
      rating: isPositive ? Math.floor(Math.random() * 2) + 4 : Math.floor(Math.random() * 2) + 1,
      title: isPositive ? 'Great purchase' : 'Disappointed',
      text: (isPositive ? positives : negatives)[Math.floor(Math.random() * 5)],
      date: new Date(Date.now() - Math.random() * 90 * 86400000).toISOString().split('T')[0],
      verified: Math.random() > 0.2,
    })
  }
  return reviews
}

function buildComparisonMatrix(details) {
  if (details.length === 0) return { columns: [], rows: [] }
  const prices = details.map(d => d.price).filter(p => p)
  const ratings = details.map(d => parseFloat(d.rating)).filter(r => r)
  return {
    count: details.length,
    avgPrice: (prices.reduce((a, b) => a + b, 0) / prices.length).toFixed(2),
    minPrice: Math.min(...prices).toFixed(2),
    maxPrice: Math.max(...prices).toFixed(2),
    avgRating: (ratings.reduce((a, b) => a + b, 0) / ratings.length).toFixed(1),
    totalReviews: details.reduce((s, d) => s + (d.reviewCount || 0), 0),
  }
}

function buildLandscapeAnalysis(products) {
  if (products.length === 0) return { totalProducts: 0 }
  const prices = products.map(p => parseFloat(p.price)).filter(p => !isNaN(p))
  const ratings = products.map(p => parseFloat(p.rating)).filter(r => !isNaN(r))
  return {
    totalProducts: products.length,
    avgPrice: (prices.reduce((a, b) => a + b, 0) / prices.length).toFixed(2),
    minPrice: Math.min(...prices).toFixed(2),
    maxPrice: Math.max(...prices).toFixed(2),
    avgRating: (ratings.reduce((a, b) => a + b, 0) / ratings.length).toFixed(1),
    fbaRatio: (products.filter(p => p.fulfillment === 'FBA').length / products.length * 100).toFixed(0) + '%',
    topSellers: [...new Set(products.map(p => p.seller))].slice(0, 5),
  }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('competitor', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('competitor', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('competitor', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('competitor', 'skill unloaded'),
}

export default handler
