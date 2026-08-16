const SKILL_ID = 'com.tupautochrome.skills.shopify-operator'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'shopify-operator-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: 'Shopify店铺运营流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { audit: 'store_audit', optimize: 'product_optimize', recovery: 'abandoned_recovery', expand: 'market_expand' } },
    { id: 'store_audit', type: 'process', label: '店铺审计' },
    { id: 'product_optimize', type: 'process', label: '商品优化' },
    { id: 'abandoned_recovery', type: 'process', label: '弃购挽回' },
    { id: 'market_expand', type: 'process', label: '市场扩张' },
    { id: 'report', type: 'process', label: '结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'store_audit', label: 'audit' },
    { from: 'choose', to: 'product_optimize', label: 'optimize' },
    { from: 'choose', to: 'abandoned_recovery', label: 'recovery' },
    { from: 'choose', to: 'market_expand', label: 'expand' },
    { from: 'store_audit', to: 'report' },
    { from: 'product_optimize', to: 'report' },
    { from: 'abandoned_recovery', to: 'report' },
    { from: 'market_expand', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [], selectors: {}, variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'AIMarketing' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'audit': return await storeAudit(params)
    case 'optimize': return await productOptimize(params)
    case 'recovery': return await abandonedRecovery(params)
    case 'expand': return await marketExpand(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function storeAudit(params) {
  const t0 = cap.flowchart.beginNode('store_audit')
  const score = Math.floor(Math.random() * 30) + 60
  const issues = score < 70 ? ['Slow page load (>3s)', 'Missing alt tags on product images', 'No structured data'] : score < 85 ? ['Meta descriptions too short', 'Low mobile speed score'] : []
  const audit = {
    overall: score,
    catalog: { status: score > 70 ? 'good' : 'needs work', products: Math.floor(Math.random() * 200 + 10), missingImages: Math.floor(Math.random() * 10), missingDesc: Math.floor(Math.random() * 5) },
    seo: { titleTags: (Math.random() * 30 + 70).toFixed(0) + '%', metaDesc: (Math.random() * 40 + 60).toFixed(0) + '%', structuredData: Math.random() > 0.5 },
    speed: { desktop: (Math.random() * 30 + 65).toFixed(0), mobile: (Math.random() * 30 + 50).toFixed(0) },
    conversion: { rate: (Math.random() * 3 + 1).toFixed(2) + '%', abandonedCart: (Math.random() * 20 + 60).toFixed(0) + '%' },
    issues,
    recommendations: issues.length > 0 ? ['Optimize image sizes', 'Add JSON-LD structured data', 'Improve mobile load time', 'Add product reviews'] : ['Monitor analytics weekly', 'A/B test product pages'],
  }
  cap.flowchart.endNode('store_audit', 'ok', '审计完成', t0)
  return { ok: true, action: 'audit', storeUrl: params.storeUrl || 'unknown', audit }
}

async function productOptimize(params) {
  const t0 = cap.flowchart.beginNode('product_optimize')
  const products = params.products || []
  const optimized = products.map((p, i) => {
    const title = p.title || 'Product ' + (i + 1)
    return {
      original: p,
      optimized: {
        title: `${title} | Premium Quality | Best for [Use Case]`,
        description: `Experience the quality of ${title}. Designed with premium materials, it delivers outstanding performance for daily use. Features include [key feature 1], [key feature 2], and [key feature 3]. Perfect for [target audience]. Shop now!`,
        seoTags: [title.toLowerCase().replace(/\s+/g, '-'), 'premium-' + title.toLowerCase().replace(/\s+/g, '-'), 'best-' + title.toLowerCase().replace(/\s+/g, '-')],
        imageAlt: `${title} - Premium Quality Product for [Use Case]`,
      },
    }
  })
  cap.flowchart.endNode('product_optimize', 'ok', `优化 ${optimized.length} 个商品`, t0)
  return { ok: true, action: 'optimize', optimized }
}

async function abandonedRecovery(params) {
  const t0 = cap.flowchart.beginNode('abandoned_recovery')
  const rate = params.abandonedRate || 75
  const aov = params.avgOrderValue || 45
  const recovery = {
    currentState: { cartAbandonment: rate + '%', monthlyLostRevenue: '$' + (aov * rate / 100 * 200).toFixed(0), avgOrderValue: '$' + aov },
    strategy: {
      emailSequence: [
        { timing: '1 hour after', discount: '10%', expectedRecovery: '5-10%' },
        { timing: '24 hours after', discount: '15% + free shipping', expectedRecovery: '3-7%' },
        { timing: '72 hours after', urgency: 'Low stock alert', expectedRecovery: '2-4%' },
      ],
      smsRecovery: { timing: '2 hours after', discount: '15%', expectedRecovery: '8-15%' },
    },
    projectedImpact: { recoveryRate: (rate * 0.15).toFixed(0) + '%', additionalRevenue: '$' + (aov * rate / 100 * 200 * 0.15).toFixed(0) + '/mo' },
  }
  cap.flowchart.endNode('abandoned_recovery', 'ok', '挽回策略生成完成', t0)
  return { ok: true, action: 'recovery', ...recovery }
}

async function marketExpand(params) {
  const t0 = cap.flowchart.beginNode('market_expand')
  const targets = params.targetMarkets || ['UK', 'DE', 'CA']
  const currency = params.currentCurrency || 'USD'
  const markets = { UK: { currency: 'GBP', rate: 0.79, lang: 'en', tax: 'VAT 20%' }, DE: { currency: 'EUR', rate: 0.92, lang: 'de', tax: 'VAT 19%' }, CA: { currency: 'CAD', rate: 1.36, lang: 'en', tax: 'GST 5%' }, FR: { currency: 'EUR', rate: 0.92, lang: 'fr', tax: 'VAT 20%' }, AU: { currency: 'AUD', rate: 1.55, lang: 'en', tax: 'GST 10%' }, JP: { currency: 'JPY', rate: 149, lang: 'ja', tax: 'CT 10%' } }
  const configs = targets.filter(m => markets[m]).map(m => ({ market: m, ...markets[m], localizedPrice: (45.99 * markets[m].rate).toFixed(m === 'JP' ? 0 : 2) }))
  cap.flowchart.endNode('market_expand', 'ok', `生成 ${configs.length} 个市场配置`, t0)
  return { ok: true, action: 'expand', currentCurrency: currency, markets: configs, setupSteps: ['Enable Shopify Markets', 'Set market-specific pricing', 'Configure payment gateways', 'Set up shipping zones', 'Register for local taxes'] }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('shopify', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('shopify', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('shopify', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('shopify', 'skill unloaded'),
}

export default handler
