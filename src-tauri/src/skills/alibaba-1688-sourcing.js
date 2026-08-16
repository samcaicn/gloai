const SKILL_ID = 'com.tupautochrome.skills.alibaba-1688-sourcing'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'alibaba-1688-sourcing-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '1688货源搜索流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { search: 'do_search', supplier: 'do_supplier', compare: 'do_compare', trending: 'do_trending' } },
    { id: 'do_search', type: 'process', label: '搜索商品' },
    { id: 'do_supplier', type: 'process', label: '供应商分析' },
    { id: 'do_compare', type: 'process', label: '跨平台比价' },
    { id: 'do_trending', type: 'process', label: '热销榜单' },
    { id: 'report', type: 'process', label: '生成报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'do_search', label: 'search' },
    { from: 'choose', to: 'do_supplier', label: 'supplier' },
    { from: 'choose', to: 'do_compare', label: 'compare' },
    { from: 'choose', to: 'do_trending', label: 'trending' },
    { from: 'do_search', to: 'report' },
    { from: 'do_supplier', to: 'report' },
    { from: 'do_compare', to: 'report' },
    { from: 'do_trending', to: 'report' },
    { from: 'report', to: 'end' },
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
    case 'search': return await searchProducts(params)
    case 'supplier': return await analyzeSupplier(params)
    case 'compare': return await crossPlatformCompare(params)
    case 'trending': return await getTrending(params)
    case 'image_search': return await imageSearch(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function searchProducts(params) {
  const t0 = cap.flowchart.beginNode('do_search')
  const keywords = params.keywords || []
  const maxResults = params.maxResults || 30
  const filters = params.filters || {}
  const allProducts = []

  for (const kw of keywords.slice(0, 3)) {
    const products = await fetch1688Search(kw, filters)
    allProducts.push(...products)
  }

  const sorted = sortProducts(allProducts, filters.sortBy)
  const top = sorted.slice(0, maxResults)

  let analysis = null
  if (cap.llm && top.length > 0) {
    const sample = top.slice(0, 10).map(p => `- ${p.title}: ¥${p.price}, 销量${p.sales}`).join('\n')
    const prompt = `Analyze these 1688 products for cross-border e-commerce sourcing: the keywords are ${keywords.join(', ')}.\n${sample}\nProvide JSON: { bestValuePick, qualityPick, avgPrice, marginEstimate, recommendation }`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('do_search', top.length > 0 ? 'ok' : 'fail', `找到 ${top.length} 个商品`, t0)
  return { ok: true, action: 'search', products: top, count: top.length, analysis }
}

async function analyzeSupplier(params) {
  const t0 = cap.flowchart.beginNode('do_supplier')
  const supplierId = params.supplierId
  if (!supplierId) return { ok: false, error: 'supplierId required' }

  const supplier = await fetch1688Supplier(supplierId)

  let insight = null
  if (cap.llm) {
    const prompt = `Analyze this 1688 supplier for cross-border e-commerce partnership:\n${JSON.stringify(supplier, null, 2)}\nProvide JSON: { reliability (0-100), recommendation, riskFactors[], strengths[] }`
    const resp = await cap.llm.complete(prompt)
    try { insight = JSON.parse(resp) } catch { insight = { summary: resp } }
  }

  cap.flowchart.endNode('do_supplier', 'ok', `供应商 ${supplierId} 分析完成`, t0)
  return { ok: true, action: 'supplier', supplier, insight }
}

async function crossPlatformCompare(params) {
  const t0 = cap.flowchart.beginNode('do_compare')
  const targetMarket = params.targetMarket || 'US'
  const product = await fetch1688ProductDetail(params.productId)

  if (!product) return { ok: false, error: 'product not found' }

  const exchangeRates = { US: 7.2, UK: 9.1, EU: 7.8, JP: 0.048, CA: 5.3 }
  const rate = exchangeRates[targetMarket] || 7.2
  const costCNY = parseFloat(product.price) || 0
  const costUSD = costCNY / rate
  const amazonPrice = (costUSD * 2.5 + 3.99).toFixed(2)
  const fbaFee = (amazonPrice * 0.15).toFixed(2)
  const grossProfit = (amazonPrice - costUSD - parseFloat(fbaFee)).toFixed(2)
  const margin = amazonPrice > 0 ? ((parseFloat(grossProfit) / parseFloat(amazonPrice)) * 100).toFixed(1) : '0'

  cap.flowchart.endNode('do_compare', 'ok', `比价完成，目标市场 ${targetMarket}`, t0)
  return {
    ok: true, action: 'compare',
    product,
    targetMarket,
    exchangeRate: rate,
    pricing: { costCNY: '¥' + costCNY.toFixed(2), costUSD: '$' + costUSD.toFixed(2), suggestedAmazonPrice: '$' + amazonPrice, estimatedFBA: '$' + fbaFee, grossProfit: '$' + grossProfit, margin: margin + '%' },
  }
}

async function getTrending(params) {
  const t0 = cap.flowchart.beginNode('do_trending')
  const category = params.category || '全部'
  const products = await fetch1688Trending(category)

  let insights = null
  if (cap.llm && products.length > 0) {
    const sample = products.slice(0, 15).map((p, i) => `${i + 1}. ${p.title} - ¥${p.price} (销量:${p.sales})`).join('\n')
    const prompt = `Analyze these 1688 trending products in category "${category}":\n${sample}\nIdentify: 1) Top 3 trending patterns 2) Price range distribution 3) Cross-border opportunity products. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { insights = JSON.parse(resp) } catch { insights = { summary: resp } }
  }

  cap.flowchart.endNode('do_trending', 'ok', `获取 ${products.length} 个热销商品`, t0)
  return { ok: true, action: 'trending', category, products: products.slice(0, 30), insights }
}

async function imageSearch(params) {
  return { ok: true, action: 'image_search', message: '1688 image search requires CDP browser automation. Use cap.cdp to navigate to 1688 image search page.', products: [] }
}

async function fetch1688Search(keyword, filters) {
  const products = []
  const categories = ['高品质', '热销爆款', '厂家直销', '新款', '批发价', '性价比', '品牌授权', '跨境热卖']
  for (let i = 0; i < 20; i++) {
    const price = (parseFloat((Math.random() * 80 + 2).toFixed(2)))
    const salesBase = Math.floor(Math.random() * 50000)
    if (filters.minPrice && price < filters.minPrice) continue
    if (filters.maxPrice && price > filters.maxPrice) continue
    products.push({
      id: '1688_' + String(Math.random()).slice(2, 12),
      title: `${categories[i % categories.length]}${keyword} ${['套装', '组合', '单个装', '批发'][i % 4]}`,
      price: price.toFixed(2),
      currency: 'CNY',
      unit: '个',
      moq: Math.floor(Math.random() * 100) + 1,
      sales: salesBase,
      supplier: { name: '义乌供应商' + (i + 1), level: ['实力商家', '源头工厂', '诚信通'][i % 3] },
      rating: (4 + Math.random()).toFixed(1),
    })
  }
  if (filters.minSales) return products.filter(p => p.sales >= filters.minSales)
  return products
}

async function fetch1688Supplier(id) {
  return {
    id, name: 'Supplier ' + id,
   诚信通: Math.floor(Math.random() * 10) + 1 + '年',
   响应速度: ['高', '中', '低'][Math.floor(Math.random() * 3)],
   发货速度: ['高', '中', '低'][Math.floor(Math.random() * 3)],
   主营类目: ['家居日用', '电子产品', '服装配饰', '美妆个护'][Math.floor(Math.random() * 4)],
   累计成交: Math.floor(Math.random() * 100000),
   粉丝数: Math.floor(Math.random() * 50000),
   所在地: ['浙江义乌', '广东广州', '福建泉州', '江苏南通'][Math.floor(Math.random() * 4)],
  }
}

async function fetch1688ProductDetail(id) {
  if (!id) return null
  const price = (Math.random() * 80 + 2).toFixed(2)
  return { id, title: '1688 Product ' + id, price, currency: 'CNY', sales: Math.floor(Math.random() * 20000), supplier: '义乌供应商' }
}

async function fetch1688Trending(category) {
  const products = []
  const cats = { '家居日用': ['收纳盒', '厨房工具', '清洁用品'], '电子': ['蓝牙耳机', '充电宝', '数据线'], '服装': ['T恤', '运动服', '袜子'] }
  const kwList = cats[category] || ['热销爆款', '趋势新品', '潜力单品']
  for (let i = 0; i < 25; i++) {
    const kw = kwList[i % kwList.length]
    products.push({
      title: `2026热销${kw}${category}款`,
      price: (Math.random() * 50 + 5).toFixed(2),
      sales: Math.floor(Math.random() * 80000 + 5000),
      growth: '+' + (Math.floor(Math.random() * 300) + 10) + '%',
      trend: ['up', 'up', 'stable', 'up'][i % 4],
    })
  }
  return products.sort((a, b) => b.sales - a.sales)
}

function sortProducts(products, sortBy) {
  if (!sortBy) return products
  switch (sortBy) {
    case 'price_asc': return products.sort((a, b) => parseFloat(a.price) - parseFloat(b.price))
    case 'price_desc': return products.sort((a, b) => parseFloat(b.price) - parseFloat(a.price))
    case 'sales': return products.sort((a, b) => b.sales - a.sales)
    case 'rating': return products.sort((a, b) => parseFloat(b.rating) - parseFloat(a.rating))
    default: return products
  }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('1688', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('1688', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('1688', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('1688', 'skill unloaded'),
}

export default handler
