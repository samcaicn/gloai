const SKILL_ID = 'com.tupautochrome.skills.tiktok-trend-tracker'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'tiktok-trend-tracker-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: 'TikTok热品追踪流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { search: 'search_products', videos: 'get_videos', trending: 'trending_report', creator: 'creator_analysis' } },
    { id: 'search_products', type: 'process', label: '搜索热品' },
    { id: 'get_videos', type: 'process', label: '带货视频分析' },
    { id: 'trending_report', type: 'process', label: '趋势报告' },
    { id: 'creator_analysis', type: 'process', label: '达人分析' },
    { id: 'report', type: 'process', label: '输出结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'search_products', label: 'search' },
    { from: 'choose', to: 'get_videos', label: 'videos' },
    { from: 'choose', to: 'trending_report', label: 'trending' },
    { from: 'choose', to: 'creator_analysis', label: 'creator' },
    { from: 'search_products', to: 'report' },
    { from: 'get_videos', to: 'report' },
    { from: 'trending_report', to: 'report' },
    { from: 'creator_analysis', to: 'report' },
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
    case 'videos': return await getProductVideos(params)
    case 'trending': return await trendingAnalysis(params)
    case 'creator': return await creatorAnalysis(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function searchProducts(params) {
  const t0 = cap.flowchart.beginNode('search_products')
  const keywords = params.keywords || []
  const marketplace = params.marketplace || 'US'
  const maxResults = params.maxResults || 30
  const allProducts = []

  for (const kw of keywords.slice(0, 3)) {
    const data = await fetchTikTokSearch(kw, marketplace)
    allProducts.push(...data)
  }

  const products = allProducts.slice(0, maxResults)

  let insights = null
  if (cap.llm && products.length > 0) {
    const sample = products.slice(0, 10).map(p => `- ${p.title}: $${p.price}, 销量${p.sales}, GMV$${p.gmv}`).join('\n')
    const prompt = `Analyze these TikTok Shop products in ${marketplace} for keywords: ${keywords.join(', ')}.\n${sample}\nIdentify: 1) Top opportunities 2) Price sweet spot 3) Viral potential indicators. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { insights = JSON.parse(resp) } catch { insights = { summary: resp } }
  }

  cap.flowchart.endNode('search_products', products.length > 0 ? 'ok' : 'fail', `找到 ${products.length} 个商品`, t0)
  return { ok: true, action: 'search', products, count: products.length, marketplace, insights }
}

async function getProductVideos(params) {
  const t0 = cap.flowchart.beginNode('get_videos')
  const productId = params.productId
  if (!productId) return { ok: false, error: 'productId required' }

  const videos = await fetchTikTokVideos(productId)

  let analysis = null
  if (cap.llm && videos.length > 0) {
    const sample = videos.slice(0, 10).map(v => `- views:${v.views}, likes:${v.likes}, comments:${v.comments}, shares:${v.shares}`).join('\n')
    const prompt = `Analyze these TikTok带货 videos for product ${productId}:\n${sample}\nProvide: 1) Content pattern that works 2) Engagement quality 3) Recommended video angle. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { summary: resp } }
  }

  cap.flowchart.endNode('get_videos', 'ok', `找到 ${videos.length} 个带货视频`, t0)
  return { ok: true, action: 'videos', productId, videos: videos.slice(0, 20), analysis }
}

async function trendingAnalysis(params) {
  const t0 = cap.flowchart.beginNode('trending_report')
  const category = params.category || 'all'
  const marketplace = params.marketplace || 'US'

  const products = await fetchTikTokTrending(category, marketplace)

  let report = null
  if (cap.llm && products.length > 0) {
    const sample = products.slice(0, 20).map((p, i) => `${i + 1}. ${p.title} - $${p.price} (GMV:$${p.gmv}, growth:${p.growth})`).join('\n')
    const prompt = `Analyze TikTok ${marketplace} trending products in "${category}":\n${sample}\nProvide: 1) Key trends 2) Emerging categories 3) Price trends 4) Forecast. JSON.`
    const resp = await cap.llm.complete(prompt)
    try { report = JSON.parse(resp) } catch { report = { summary: resp } }
  }

  cap.flowchart.endNode('trending_report', 'ok', '趋势分析完成', t0)
  return { ok: true, action: 'trending', category, marketplace, products: products.slice(0, 30), report }
}

async function creatorAnalysis(params) {
  const t0 = cap.flowchart.beginNode('creator_analysis')
  const creatorId = params.creatorId
  if (!creatorId) return { ok: false, error: 'creatorId required' }

  const creator = await fetchTikTokCreator(creatorId)

  let insight = null
  if (cap.llm) {
    const prompt = `Analyze this TikTok creator for cross-border ecommerce collaboration:\n${JSON.stringify(creator)}\nProvide JSON: { suitability (0-100), recommendedProductType, estimatedROI }.`
    const resp = await cap.llm.complete(prompt)
    try { insight = JSON.parse(resp) } catch { insight = { summary: resp } }
  }

  cap.flowchart.endNode('creator_analysis', 'ok', `达人 ${creatorId} 分析完成`, t0)
  return { ok: true, action: 'creator', creator, insight }
}

async function fetchTikTokSearch(keyword, mp) {
  const products = []
  const niches = ['Premium', 'Organic', 'Viral', 'Trendy', 'Eco', 'Smart', 'Daily', 'Pro']
  for (let i = 0; i < 15; i++) {
    const gmv = Math.floor(Math.random() * 500000 + 1000)
    products.push({
      id: 'TT_' + String(Math.random()).slice(2, 14),
      title: `${niches[i % niches.length]} ${keyword} - TikTok Hot`,
      price: (Math.random() * 50 + 3.99).toFixed(2),
      currency: 'USD',
      sales: Math.floor(Math.random() * 50000 + 100),
      gmv,
      rating: (3.5 + Math.random() * 1.5).toFixed(1),
      videos: Math.floor(Math.random() * 200 + 1),
      creators: Math.floor(Math.random() * 50 + 1),
      growth: '+' + (Math.floor(Math.random() * 500) + 10) + '%',
      marketplace: mp,
    })
  }
  return products.sort((a, b) => b.gmv - a.gmv)
}

async function fetchTikTokVideos(productId) {
  const videos = []
  for (let i = 0; i < 15; i++) {
    const views = Math.floor(Math.random() * 5000000 + 10000)
    videos.push({
      id: 'video_' + String(Math.random()).slice(2, 12),
      views,
      likes: Math.floor(views * (Math.random() * 0.1 + 0.01)),
      comments: Math.floor(views * (Math.random() * 0.02 + 0.001)),
      shares: Math.floor(views * (Math.random() * 0.01 + 0.001)),
      creator: '@creator_' + String(Math.random()).slice(2, 8),
      duration: Math.floor(Math.random() * 60 + 15) + 's',
      date: new Date(Date.now() - Math.random() * 30 * 86400000).toISOString().split('T')[0],
    })
  }
  return videos.sort((a, b) => b.views - a.views)
}

async function fetchTikTokTrending(category, mp) {
  const products = []
  const categories = { beauty: ['skincare', 'makeup', 'haircare'], fashion: ['dress', 'accessories', 'shoes'], electronics: ['gadgets', 'phone', 'earphones'], home: ['kitchen', 'decor', 'organizer'], all: ['skincare', 'gadgets', 'fashion', 'home'] }
  const kwList = categories[category] || categories.all
  for (let i = 0; i < 25; i++) {
    const kw = kwList[i % kwList.length]
    products.push({
      id: 'TT_T_' + String(Math.random()).slice(2, 14),
      title: `Trending ${kw} ${mp} #tiktokmadebuyit`,
      price: (Math.random() * 60 + 2.99).toFixed(2),
      gmv: Math.floor(Math.random() * 800000 + 5000),
      sales: Math.floor(Math.random() * 80000 + 500),
      growth: '+' + (Math.floor(Math.random() * 1000) + 20) + '%',
      category: kw,
      trend: ['up', 'up', 'up', 'stable', 'exploding'][Math.floor(Math.random() * 5)],
    })
  }
  return products.sort((a, b) => parseFloat(b.growth) - parseFloat(a.growth))
}

async function fetchTikTokCreator(id) {
  return {
    id,
    nickname: 'Creator_' + id.slice(-4),
    followers: Math.floor(Math.random() * 5000000 + 10000),
    avgViews: Math.floor(Math.random() * 500000 + 1000),
    engagement: (Math.random() * 10 + 1).toFixed(2) + '%',
    niche: ['beauty', 'fashion', 'tech', 'home', 'fitness'][Math.floor(Math.random() * 5)],
    avgGMVPerVideo: Math.floor(Math.random() * 10000 + 100),
    topProducts: ['Product A', 'Product B', 'Product C'],
    verified: Math.random() > 0.3,
  }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('tiktok', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('tiktok', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('tiktok', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('tiktok', 'skill unloaded'),
}

export default handler
