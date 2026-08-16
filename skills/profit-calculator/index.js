const SKILL_ID = 'com.tupautochrome.skills.profit-calculator'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'profit-calculator-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '利润计算流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { analyze: 'profit_analyze', suggest: 'price_suggest', fba: 'fba_calc' } },
    { id: 'profit_analyze', type: 'process', label: '利润分析' },
    { id: 'price_suggest', type: 'process', label: '定价建议' },
    { id: 'fba_calc', type: 'process', label: 'FBA费用' },
    { id: 'report', type: 'process', label: '报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'profit_analyze', label: 'analyze' },
    { from: 'choose', to: 'price_suggest', label: 'suggest' },
    { from: 'choose', to: 'fba_calc', label: 'fba' },
    { from: 'profit_analyze', to: 'report' },
    { from: 'price_suggest', to: 'report' },
    { from: 'fba_calc', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [], selectors: {}, variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'tupAI' },
}

const PLATFORM_FEES = {
  amazon: { referral: 0.15, subscription: 39.99, closing: 0, type: 'referral+subscription' },
  amazon_eu: { referral: 0.15, subscription: 39.99, closing: 0 },
  ebay: { referral: 0.1325, subscription: 0, closing: 0.30, type: 'final value + per order' },
  shopify: { referral: 0, subscription: 29, closing: 0, type: 'subscription + payment 2.9%+$0.30' },
  tiktok: { referral: 0.08, subscription: 0, closing: 0, type: 'commission only' },
  walmart: { referral: 0.15, subscription: 0, closing: 0, type: 'referral fee' },
}

const FBA_RATES = {
  US: { smallStandard: { weightTier: '<=1lb', pickPack: 3.50, weightHandling: 0.40 }, largeStandard: { weightTier: '<=3lb', pickPack: 4.50, weightHandling: 0.45 }, largeBulky: { weightTier: '>3lb', pickPack: 7.50, weightHandling: 0.60 }, storageMonthly: { standard: 0.75, oversized: 1.50 } },
  UK: { smallStandard: { pickPack: 2.50, weightHandling: 0.35 }, largeStandard: { pickPack: 3.50, weightHandling: 0.40 } },
  DE: { smallStandard: { pickPack: 2.80, weightHandling: 0.35 }, largeStandard: { pickPack: 3.80, weightHandling: 0.40 } },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'analyze': return await profitAnalyze(params)
    case 'suggest': return await priceSuggest(params)
    case 'fba': return await fbaCalc(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function profitAnalyze(params) {
  const t0 = cap.flowchart.beginNode('profit_analyze')
  const info = params.productInfo || {}
  const market = params.market || 'US'
  const purchasePrice = info.purchasePrice || 0
  const sellingPrice = info.sellingPrice || 0

  const platform = info.platform || 'amazon'
  const feeConfig = PLATFORM_FEES[platform] || PLATFORM_FEES.amazon
  const referralFee = sellingPrice * feeConfig.referral
  const weight = info.weight || 0.3
  const fbaRate = FBA_RATES[market]?.largeStandard || FBA_RATES.US.largeStandard
  const fbaFee = fbaRate.pickPack + (weight > 1 ? fbaRate.weightHandling * weight : fbaRate.weightHandling * 0.5)
  const shippingCN = purchasePrice * 0.2
  const shippingToFBA = 0.70
  const adCost = sellingPrice * 0.15
  const totalCost = purchasePrice + shippingCN + shippingToFBA + fbaFee + referralFee + adCost
  const netProfit = sellingPrice - totalCost
  const margin = sellingPrice > 0 ? (netProfit / sellingPrice * 100) : 0

  let analysis = null
  if (cap.llm) {
    const prompt = `Analyze this product profit: purchase $${purchasePrice}, sell $${sellingPrice} on ${platform} ${market}, total cost $${totalCost.toFixed(2)}, margin ${margin.toFixed(1)}%. Provide recommendations to improve profitability. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('profit_analyze', 'ok', '利润分析完成', t0)
  return {
    ok: true, action: 'analyze', market, platform,
    costs: { purchase: purchasePrice, shippingFromChina: shippingCN, shippingToFBA: shippingToFBA, fbaFee, referralFee, adCost: adCost, totalCost: totalCost.toFixed(2) },
    revenue: { sellingPrice, platformCut: referralFee },
    profit: { netProfit: netProfit.toFixed(2), margin: margin.toFixed(1) + '%', roi: purchasePrice > 0 ? ((netProfit / purchasePrice) * 100).toFixed(0) + '%' : 'N/A' },
    breakEven: { unitsToBreakEven: purchasePrice > 0 ? Math.ceil(39.99 / netProfit) : 'N/A' },
    analysis,
  }
}

async function priceSuggest(params) {
  const t0 = cap.flowchart.beginNode('price_suggest')
  const cost = params.costInfo || {}
  const targetMargin = (params.targetMargin || 30) / 100
  const totalCost = (cost.purchasePrice || 0) + (cost.shipping || 0) + (cost.fbaFee || 0)
  const platform = cost.platform || 'amazon'
  const referralRate = PLATFORM_FEES[platform]?.referral || 0.15
  const adRate = cost.adRate || 0.15
  const basePrice = totalCost / (1 - targetMargin - referralRate - adRate)
  const lowPrice = basePrice * 0.9
  const highPrice = basePrice * 1.15

  const markets = { US: 1, UK: 0.79, DE: 0.92, JP: 0.0067, CA: 0.73, AU: 0.65 }
  const multiMarket = Object.entries(markets).map(([m, rate]) => ({ market: m, suggestedPrice: (basePrice * rate).toFixed(2), currency: m === 'JP' ? 'JPY' : m === 'UK' ? 'GBP' : m === 'DE' ? 'EUR' : m === 'CA' ? 'CAD' : m === 'AU' ? 'AUD' : 'USD' }))

  cap.flowchart.endNode('price_suggest', 'ok', '定价建议完成', t0)
  return {
    ok: true, action: 'suggest', targetMargin: (targetMargin * 100) + '%', totalCost: totalCost.toFixed(2),
    suggestedPrice: { low: lowPrice.toFixed(2), target: basePrice.toFixed(2), high: highPrice.toFixed(2), currency: 'USD' },
    multiMarket,
  }
}

async function fbaCalc(params) {
  const t0 = cap.flowchart.beginNode('fba_calc')
  const info = params.productInfo || {}
  const market = params.market || 'US'
  const weight = info.weight || 0.5
  const isStandard = weight <= 3
  const rate = FBA_RATES[market] || FBA_RATES.US
  const tier = isStandard ? rate.largeStandard : rate.largeBulky
  const pickPackFee = tier.pickPack
  const weightFee = tier.weightHandling * weight
  const monthlyStorage = (rate.storageMonthly?.standard || 0.75) * 0.5
  const totalFBA = pickPackFee + weightFee + monthlyStorage

  cap.flowchart.endNode('fba_calc', 'ok', 'FBA费用计算完成', t0)
  return {
    ok: true, action: 'fba', market, productInfo: info,
    fees: { pickAndPack: pickPackFee, weightHandling: weightFee.toFixed(2), monthlyStorage: monthlyStorage.toFixed(2), totalFBA: totalFBA.toFixed(2) },
    breakdown: `$${pickPackFee.toFixed(2)} pick+pack + $${weightFee.toFixed(2)} weight + $${monthlyStorage.toFixed(2)} storage/mo = $${totalFBA.toFixed(2)}`,
  }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('profit', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('profit', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('profit', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('profit', 'skill unloaded'),
}

export default handler
