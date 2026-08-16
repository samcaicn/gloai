const SKILL_ID = 'com.tupautochrome.skills.cross-border-expansion'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'cross-border-expansion-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '跨境市场扩张战略流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { score: 'market_scoring', fulfillment: 'fulfillment_compare', roadmap: 'expansion_roadmap', taxGuide: 'tax_guide', fullAnalysis: 'full_analysis' } },
    { id: 'market_scoring', type: 'process', label: '市场评分' },
    { id: 'fulfillment_compare', type: 'process', label: '物流对比' },
    { id: 'expansion_roadmap', type: 'process', label: '路线图' },
    { id: 'tax_guide', type: 'process', label: '税务指南' },
    { id: 'full_analysis', type: 'process', label: '全链路分析' },
    { id: 'report', type: 'process', label: '输出报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'market_scoring', label: 'score' },
    { from: 'choose', to: 'fulfillment_compare', label: 'fulfillment' },
    { from: 'choose', to: 'expansion_roadmap', label: 'roadmap' },
    { from: 'choose', to: 'tax_guide', label: 'taxGuide' },
    { from: 'choose', to: 'full_analysis', label: 'fullAnalysis' },
    { from: 'market_scoring', to: 'report' },
    { from: 'fulfillment_compare', to: 'report' },
    { from: 'expansion_roadmap', to: 'report' },
    { from: 'tax_guide', to: 'report' },
    { from: 'full_analysis', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'tupAI' },
}

const MARKET_DATA = {
  US: { revenue: '1.2T', penetration: 22, growth: 9, platform: 'Amazon, Shopify, Walmart', competition: 8, regulation: 4, logistics: 9, payment: 9, culture: 10, ip: 9 },
  UK: { revenue: '196B', penetration: 36, growth: 6, platform: 'Amazon UK, eBay', competition: 7, regulation: 6, logistics: 8, payment: 8, culture: 8, ip: 8 },
  DE: { revenue: '142B', penetration: 19, growth: 7, platform: 'Amazon DE, Otto', competition: 7, regulation: 7, logistics: 8, payment: 7, culture: 5, ip: 8 },
  JP: { revenue: '178B', penetration: 14, growth: 7, platform: 'Amazon JP, Rakuten', competition: 7, regulation: 6, logistics: 8, payment: 6, culture: 3, ip: 7 },
  CA: { revenue: '75B', penetration: 13, growth: 10, platform: 'Amazon CA, Shopify', competition: 5, regulation: 4, logistics: 7, payment: 8, culture: 9, ip: 8 },
  AU: { revenue: '52B', penetration: 15, growth: 8, platform: 'Amazon AU, eBay', competition: 5, regulation: 5, logistics: 6, payment: 7, culture: 8, ip: 7 },
  FR: { revenue: '96B', penetration: 15, growth: 8, platform: 'Amazon FR, Cdiscount', competition: 7, regulation: 7, logistics: 7, payment: 7, culture: 5, ip: 7 },
  IT: { revenue: '48B', penetration: 12, growth: 9, platform: 'Amazon IT, eBay', competition: 6, regulation: 7, logistics: 6, payment: 6, culture: 5, ip: 6 },
  ES: { revenue: '38B', penetration: 11, growth: 10, platform: 'Amazon ES, El Corte', competition: 5, regulation: 6, logistics: 6, payment: 6, culture: 5, ip: 6 },
  SG: { revenue: '8B', penetration: 15, growth: 10, platform: 'Shopee, Lazada', competition: 6, regulation: 4, logistics: 9, payment: 8, culture: 6, ip: 8 },
}

const WEIGHTS = { marketSize: 0.20, penetration: 0.10, competition: 0.15, regulation: 0.15, logistics: 0.15, payment: 0.10, culture: 0.10, ip: 0.05 }

const FULFILLMENT_MODELS = [
  { name: 'Direct Shipping', bestFor: 'Testing, <50 orders/mo', costPerOrder: '$15-40+', transitDays: '7-21', inventoryRisk: 'None', setupEffort: 'Low' },
  { name: 'Local 3PL', bestFor: 'Established, 100+ orders/mo', costPerOrder: '$5-15', transitDays: '1-5', inventoryRisk: 'Medium', setupEffort: 'Medium' },
  { name: 'Platform Fulfillment (FBA)', bestFor: 'Marketplace sellers', costPerOrder: '$3-12 + fees', transitDays: '1-3', inventoryRisk: 'Medium', setupEffort: 'Low-Medium' },
  { name: 'Dropshipping / POD', bestFor: 'Testing, zero inventory', costPerOrder: '$0 + lower margins', transitDays: '5-15', inventoryRisk: 'None', setupEffort: 'Low' },
  { name: 'Cross-Border Consolidation', bestFor: 'Multi-market, medium vol', costPerOrder: '$8-20', transitDays: '3-10', inventoryRisk: 'Low-Medium', setupEffort: 'Medium' },
]

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'score': return await scoreMarkets(params)
    case 'fulfillment': return await compareFulfillment(params)
    case 'roadmap': return await generateRoadmap(params)
    case 'taxGuide': return await taxComplianceGuide(params)
    case 'fullAnalysis': return await fullAnalysis(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function scoreMarkets(params) {
  const t0 = cap.flowchart.beginNode('market_scoring')
  const targets = params.targetMarkets || Object.keys(MARKET_DATA)
  const category = params.category || 'general'
  const scored = targets.filter(m => MARKET_DATA[m]).map(m => {
    const d = MARKET_DATA[m]
    const scores = {
      marketSize: Math.min(10, (parseFloat(d.revenue.replace('T', '000').replace('B', '')) / 100) || 5),
      penetration: d.penetration / 5,
      competition: (10 - d.competition) + 1,
      regulation: (10 - d.regulation) + 1,
      logistics: d.logistics,
      payment: d.payment,
      culture: d.culture,
      ip: d.ip,
    }
    const composite = Object.keys(WEIGHTS).reduce((sum, k) => sum + (scores[k] || 5) * WEIGHTS[k], 0)
    return { market: m, ...d, scores, composite: composite.toFixed(2) }
  }).sort((a, b) => parseFloat(b.composite) - parseFloat(a.composite))

  let insight = null
  if (cap.llm) {
    const top3 = scored.slice(0, 3).map(s => `${s.market} (${s.composite})`).join(', ')
    const prompt = `Market scoring results for "${category}": top 3 = ${top3}. Full rankings: ${scored.map(s => `${s.market}:${s.composite}`).join(', ')}. Analyze and recommend top 2 markets with rationale. Respond in JSON.`
    const resp = await cap.llm.complete(prompt)
    try { insight = JSON.parse(resp) } catch { insight = { summary: resp } }
  }
  cap.flowchart.endNode('market_scoring', 'ok', `评估 ${scored.length} 个市场`, t0)
  return { ok: true, action: 'score', rankings: scored, insight }
}

async function compareFulfillment(params) {
  const t0 = cap.flowchart.beginNode('fulfillment_compare')
  const monthlyOrders = params.monthlyOrders || 100
  const recommendation = monthlyOrders < 50 ? FULFILLMENT_MODELS[0] : monthlyOrders < 200 ? FULFILLMENT_MODELS[4] : FULFILLMENT_MODELS[2]
  cap.flowchart.endNode('fulfillment_compare', 'ok', '物流方案对比完成', t0)
  return { ok: true, action: 'fulfillment', models: FULFILLMENT_MODELS, monthlyOrders, recommendation: { suggested: recommendation.name, reasoning: `Based on ${monthlyOrders} orders/month` } }
}

async function generateRoadmap(params) {
  const t0 = cap.flowchart.beginNode('expansion_roadmap')
  const platform = params.currentPlatform || 'amazon'
  const home = params.homeMarket || 'US'

  const priority = home === 'US' ? [{ market: 'CA', reason: 'NARF, same Seller Central' }, { market: 'UK', reason: 'English, large market, UK VAT needed' }, { market: 'DE', reason: 'Largest EU, German VAT + translation' }, { market: 'JP', reason: 'High AOV, localization investment' }, { market: 'AU', reason: 'Growing, English-speaking, GST' }]
    : [{ market: 'US', reason: 'World\'s largest ecommerce market' }, { market: 'UK', reason: 'English-speaking, high penetration' }, { market: 'DE', reason: 'Largest EU market' }]

  const phases = [
    { phase: 1, timeline: 'Month 1-2', focus: 'Market research + compliance setup', tasks: ['Register VAT/GST', 'Product compliance certifications', 'Localize listings'] },
    { phase: 2, timeline: 'Month 2-3', focus: 'Soft launch', tasks: ['Ship initial inventory', 'Set up fulfillment', 'Run test campaigns'] },
    { phase: 3, timeline: 'Month 3-6', focus: 'Scale', tasks: ['Optimize ads', 'Expand selection', 'Build reviews'] },
  ]

  let roadmap = null
  if (cap.llm) {
    const prompt = `Build an expansion roadmap from ${home} via ${platform}. Priority markets: ${priority.map(p => p.market).join(', ')}. Include milestones, KPIs, and decision points. Respond in JSON.`
    const resp = await cap.llm.complete(prompt)
    try { roadmap = JSON.parse(resp) } catch { roadmap = { phases, priority } }
  }
  cap.flowchart.endNode('expansion_roadmap', 'ok', '路线图生成完成', t0)
  return { ok: true, action: 'roadmap', currentPlatform: platform, homeMarket: home, priority, phases, roadmap }
}

async function taxComplianceGuide(params) {
  const t0 = cap.flowchart.beginNode('tax_guide')
  const markets = params.markets || ['UK', 'DE']
  const taxData = {
    UK: { taxType: 'VAT', rate: '20%', threshold: '£85,000', registration: 'HMRC', notes: 'Post-Brexit, separate UK VAT registration required' },
    DE: { taxType: 'VAT', rate: '19%', threshold: '€100,000', registration: 'BZSt', notes: 'German VAT + VerpackG packaging law' },
    FR: { taxType: 'VAT', rate: '20%', threshold: '€100,000', registration: 'DGFiP', notes: 'French VAT + EPR compliance' },
    JP: { taxType: 'Consumption Tax', rate: '10%', threshold: '¥10M', registration: 'NTA', notes: 'JCT registration required from 2024' },
    CA: { taxType: 'GST/HST', rate: '5-15%', threshold: 'CAD$30,000', registration: 'CRA', notes: 'Varies by province' },
    AU: { taxType: 'GST', rate: '10%', threshold: 'AUD$75,000', registration: 'ATO', notes: 'Low-value import threshold AUD$1,000' },
    SG: { taxType: 'GST', rate: '9%', threshold: 'SGD$1M', registration: 'IRAS', notes: 'Overseas vendor registration required' },
    US: { taxType: 'Sales Tax', rate: '0-10%', threshold: 'State-dependent', registration: 'State DOR', notes: 'Economic nexus by state' },
  }
  const guides = markets.filter(m => taxData[m]).map(m => ({ market: m, ...taxData[m] }))
  cap.flowchart.endNode('tax_guide', 'ok', `生成 ${guides.length} 个市场税务指南`, t0)
  return { ok: true, action: 'taxGuide', guides }
}

async function fullAnalysis(params) {
  const t0 = cap.flowchart.beginNode('full_analysis')
  const info = params.productInfo || {}
  const home = params.homeMarket || 'US'
  const targets = params.targetMarkets || ['UK', 'DE', 'CA']
  const scored = await scoreMarkets({ targetMarkets: targets, category: info.category })
  const tax = await taxComplianceGuide({ markets: targets })
  const fulfillment = await compareFulfillment({ monthlyOrders: 100, markets: targets })
  const roadmap = await generateRoadmap({ currentPlatform: 'amazon', homeMarket: home })
  cap.flowchart.endNode('full_analysis', 'ok', '全链路分析完成', t0)
  return { ok: true, action: 'fullAnalysis', productInfo: info, marketScore: scored.rankings, taxGuide: tax.guides, fulfillment: fulfillment.models, roadmap: roadmap.phases }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('expansion', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('expansion', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('expansion', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('expansion', 'skill unloaded'),
}

export default handler
