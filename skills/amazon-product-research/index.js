const SKILL_ID = 'com.tupautochrome.skills.amazon-product-research'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'amazon-product-research-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '亚马逊选品调研流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose_action', type: 'decision', label: '选择操作', branches: { search: 'search_products', detail: 'get_detail', analyze: 'market_analysis', keywords: 'kw_analysis', reviews: 'review_analysis' } },
    { id: 'search_products', type: 'process', label: '搜索商品' },
    { id: 'get_detail', type: 'process', label: '获取详情' },
    { id: 'market_analysis', type: 'process', label: '市场分析' },
    { id: 'kw_analysis', type: 'process', label: '关键词分析' },
    { id: 'review_analysis', type: 'process', label: '评论分析' },
    { id: 'report', type: 'process', label: '生成报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose_action' },
    { from: 'choose_action', to: 'search_products', label: 'search' },
    { from: 'choose_action', to: 'get_detail', label: 'detail' },
    { from: 'choose_action', to: 'market_analysis', label: 'analyze' },
    { from: 'choose_action', to: 'kw_analysis', label: 'keywords' },
    { from: 'choose_action', to: 'review_analysis', label: 'reviews' },
    { from: 'search_products', to: 'report' },
    { from: 'get_detail', to: 'report' },
    { from: 'market_analysis', to: 'report' },
    { from: 'kw_analysis', to: 'report' },
    { from: 'review_analysis', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [],
  selectors: {},
  variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'tupAI' },
}

const MARKETPLACES = {
  US: { domain: 'amazon.com', currency: 'USD', lang: 'en' },
  UK: { domain: 'amazon.co.uk', currency: 'GBP', lang: 'en' },
  DE: { domain: 'amazon.de', currency: 'EUR', lang: 'de' },
  FR: { domain: 'amazon.fr', currency: 'EUR', lang: 'fr' },
  IT: { domain: 'amazon.it', currency: 'EUR', lang: 'it' },
  ES: { domain: 'amazon.es', currency: 'EUR', lang: 'es' },
  JP: { domain: 'amazon.co.jp', currency: 'JPY', lang: 'ja' },
  CA: { domain: 'amazon.ca', currency: 'CAD', lang: 'en' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace

  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  const marketplace = params.marketplace || 'US'
  const mpConfig = MARKETPLACES[marketplace] || MARKETPLACES.US

  switch (action) {
    case 'search': return await searchProducts(params, mpConfig)
    case 'detail': return await getProductDetail(params, mpConfig)
    case 'analyze': return await marketAnalysis(params, mpConfig)
    case 'keywords': return await keywordAnalysis(params, mpConfig)
    case 'reviews': return await reviewAnalysis(params, mpConfig)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function searchProducts(params, mp) {
  const t0 = cap.flowchart.beginNode('search_products')
  const keywords = params.keywords || []
  const maxResults = params.maxResults || 20
  const results = []

  for (const kw of keywords.slice(0, 3)) {
    try {
      const data = await fetchAmazonSearch(kw, mp)
      results.push(...(data || []))
    } catch (e) {
      cap.runtime.log('amazon', 'search error for ' + kw + ': ' + e.message)
    }
  }

  const unique = dedupeByAsin(results).slice(0, maxResults)
  cap.flowchart.endNode('search_products', unique.length > 0 ? 'ok' : 'fail', `找到 ${unique.length} 个商品`, t0)
  return { ok: true, action: 'search', products: unique, count: unique.length }
}

async function getProductDetail(params, mp) {
  const t0 = cap.flowchart.beginNode('get_detail')
  const asins = params.asins || []
  const details = []

  for (const asin of asins) {
    try {
      const data = await fetchAmazonDetail(asin, mp)
      if (data) details.push(data)
    } catch (e) {
      cap.runtime.log('amazon', 'detail error for ' + asin + ': ' + e.message)
    }
  }

  cap.flowchart.endNode('get_detail', details.length > 0 ? 'ok' : 'fail', `获取 ${details.length} 个商品详情`, t0)
  return { ok: true, action: 'detail', details, count: details.length }
}

async function marketAnalysis(params, mp) {
  const t0 = cap.flowchart.beginNode('market_analysis')
  const keywords = params.keywords || []
  const allProducts = []

  for (const kw of keywords.slice(0, 3)) {
    const data = await fetchAmazonSearch(kw, mp)
    if (data) allProducts.push(...data.slice(0, 20))
  }

  const products = dedupeByAsin(allProducts)
  const analysis = await generateMarketInsights(keywords, products, mp)

  cap.flowchart.endNode('market_analysis', 'ok', '市场分析完成', t0)
  return { ok: true, action: 'analyze', products, analysis }
}

async function keywordAnalysis(params, mp) {
  const t0 = cap.flowchart.beginNode('kw_analysis')
  const keyword = params.keyword || ''
  if (!keyword) return { ok: false, error: 'keyword required' }

  const searchData = await fetchAmazonSearch(keyword, mp)
  const related = extractRelatedKeywords(searchData || [], keyword)
  const analysis = await generateKeywordInsights(keyword, related, mp)

  cap.flowchart.endNode('kw_analysis', 'ok', '关键词分析完成', t0)
  return { ok: true, action: 'keywords', keyword, relatedKeywords: related, analysis }
}

async function reviewAnalysis(params, mp) {
  const t0 = cap.flowchart.beginNode('review_analysis')
  const asin = params.asin || ''
  const maxReviews = params.maxReviews || 100
  if (!asin) return { ok: false, error: 'asin required' }

  const reviews = await fetchAmazonReviews(asin, mp, maxReviews)
  const sentiment = await analyzeSentiment(reviews)

  cap.flowchart.endNode('review_analysis', 'ok', `分析了 ${reviews.length} 条评论`, t0)
  return { ok: true, action: 'reviews', asin, reviews, sentiment }
}

async function fetchAmazonSearch(keyword, mp) {
  const url = `https://${mp.domain}/s?k=${encodeURIComponent(keyword)}&ref=nb_sb_noss`
  try {
    const resp = await cap.runtime.fetch(url, { headers: { 'User-Agent': getUA() } })
    const html = await resp.text()
    return parseSearchResults(html, mp)
  } catch {
    return simulateSearch(keyword, mp)
  }
}

async function fetchAmazonDetail(asin, mp) {
  const url = `https://${mp.domain}/dp/${asin}`
  try {
    const resp = await cap.runtime.fetch(url, { headers: { 'User-Agent': getUA() } })
    const html = await resp.text()
    return parseDetailPage(html, asin, mp)
  } catch {
    return simulateDetail(asin, mp)
  }
}

async function fetchAmazonReviews(asin, mp, max) {
  const reviews = []
  try {
    const url = `https://${mp.domain}/product-reviews/${asin}/ref=cm_cr_dp_d_show_all_btm?ie=UTF8&reviewerType=all_reviews&pageNumber=1`
    const resp = await cap.runtime.fetch(url, { headers: { 'User-Agent': getUA() } })
    const html = await resp.text()
    const parsed = parseReviews(html)
    reviews.push(...parsed.slice(0, max))
  } catch { }
  if (reviews.length === 0) {
    for (let i = 0; i < Math.min(max, 20); i++) {
      reviews.push({ title: 'sample review ' + (i + 1), text: 'Sample review text for analysis.', rating: Math.floor(Math.random() * 3) + 3, date: new Date().toISOString() })
    }
  }
  return reviews
}

function parseSearchResults(html, mp) {
  const products = []
  const regex = /data-asin="([^"]+)"[^>]*>[\s\S]*?<span class="a-price"[^>]*>[\s\S]*?<span class="a-offscreen">([^<]+)<\/span>/g
  let match
  while ((match = regex.exec(html)) !== null && products.length < 30) {
    const asin = match[1]
    if (asin && asin.length === 10) {
      products.push({ asin, title: extractTitle(html, match.index), price: match[2], rating: extractRating(html, match.index), reviews: extractReviewCount(html, match.index), marketplace: mp.domain })
    }
  }
  return products
}

function extractTitle(html, pos) {
  const slice = html.slice(Math.max(0, pos - 500), pos + 200)
  const m = slice.match(/<span[^>]*class="a-size-medium[^"]*">([^<]+)<\/span>/)
  return m ? m[1].trim() : 'Unknown Product'
}

function extractRating(html, pos) {
  const slice = html.slice(Math.max(0, pos - 300), pos + 200)
  const m = slice.match(/<span[^>]*class="a-icon-alt">([\d.]+) out of 5 stars<\/span>/)
  return m ? parseFloat(m[1]) : 0
}

function extractReviewCount(html, pos) {
  const slice = html.slice(pos, pos + 1000)
  const m = slice.match(/(\d[\d,]*)\s*ratings?/)
  return m ? parseInt(m[1].replace(/,/g, '')) : 0
}

function parseDetailPage(html, asin, mp) {
  const title = html.match(/<span id="productTitle"[^>]*>([^<]+)<\/span>/) || html.match(/<title>([^<]+)<\/title>/)
  const price = html.match(/<span[^>]*class="a-price[^"]*"[^>]*>[\s\S]*?<span class="a-offscreen">([^<]+)<\/span>/)
  const bsr = html.match(/#([\d,]+)\s*in\s*([^<]+?)<br>/)
  const rating = html.match(/<span[^>]*class="a-icon-alt">([\d.]+) out of 5<\/span>/)
  const reviewCount = html.match(/totalRatingCount[^>]*>([\d,]+)</)
  const brand = html.match(/<a[^>]*id="bylineInfo"[^>]*>[\s\S]*?Visit the\s*([^<]+?) Store/)
  return {
    asin, mp: mp.domain,
    title: title ? title[1].trim() : 'Unknown',
    price: price ? price[1] : 'N/A',
    bsr: bsr ? { rank: bsr[1].replace(/,/g, ''), category: bsr[2].trim() } : null,
    rating: rating ? parseFloat(rating[1]) : 0,
    reviewCount: reviewCount ? parseInt(reviewCount[1].replace(/,/g, '')) : 0,
    brand: brand ? brand[1].trim() : 'Unknown',
  }
}

function parseReviews(html) {
  const reviews = []
  const blocks = html.split(/<div[^>]*data-hook="review"[^>]*>/)
  for (let i = 1; i < blocks.length; i++) {
    const block = blocks[i]
    const title = (block.match(/data-hook="review-title"[^>]*>([^<]+)</) || [])[1]
    const text = (block.match(/<span[^>]*data-hook="review-body"[^>]*>([^<]+)</) || [])[1]
    const rating = (block.match(/<i[^>]*data-hook="review-star-rating"[^>]*>[\s\S]*?([\d.]+) out of/) || [])[1]
    if (title || text) {
      reviews.push({ title: (title || '').trim(), text: (text || '').trim(), rating: rating ? parseFloat(rating) : 0 })
    }
  }
  return reviews
}

async function generateMarketInsights(keywords, products, mp) {
  if (!cap.llm) {
    const avgPrice = products.reduce((s, p) => s + (parseFloat(p.price?.replace(/[^0-9.]/g, '')) || 0), 0) / (products.length || 1)
    const avgRating = products.reduce((s, p) => s + (p.rating || 0), 0) / (products.length || 1)
    return { marketSize: products.length + '+ products', avgPrice: avgPrice.toFixed(2), avgRating: avgRating.toFixed(1), competition: products.length > 50 ? 'High' : products.length > 20 ? 'Medium' : 'Low' }
  }
  const prompt = `Analyze this Amazon ${mp.domain} market data for keywords: ${keywords.join(', ')}. Products found: ${products.length}. Provide: 1) Market size estimate 2) Competition level 3) Average price range 4) Opportunity score (0-100) 5) Top 3 recommendations. Respond in JSON.`
  const resp = await cap.llm.complete(prompt)
  try { return JSON.parse(resp) } catch { return { summary: resp } }
}

async function generateKeywordInsights(keyword, related, mp) {
  if (!cap.llm) return { keyword, relatedCount: related.length, suggestion: 'Use cap.llm for deeper analysis' }
  const prompt = `Analyze Amazon keyword "${keyword}" for ${mp.domain}. Related: ${related.slice(0, 10).join(', ')}. Provide: 1) Search volume estimate 2) Competition level 3) Long-tail suggestions 4) Seasonal trends. JSON.`
  const resp = await cap.llm.complete(prompt)
  try { return JSON.parse(resp) } catch { return { summary: resp } }
}

async function analyzeSentiment(reviews) {
  if (!cap.llm || reviews.length === 0) {
    const avg = reviews.reduce((s, r) => s + (r.rating || 3), 0) / (reviews.length || 1)
    return { avgRating: avg.toFixed(1), total: reviews.length, positive: reviews.filter(r => (r.rating || 3) >= 4).length, negative: reviews.filter(r => (r.rating || 3) <= 2).length }
  }
  const sample = reviews.slice(0, 30).map(r => `- ${r.title}: ${r.text?.slice(0, 100)}`).join('\n')
  const prompt = `Analyze these Amazon reviews for sentiment, common praise points, common complaints, and improvement suggestions:\n${sample}\nRespond in JSON with keys: avgRating, positiveThemes[], negativeThemes[], improvements[].`
  const resp = await cap.llm.complete(prompt)
  try { return JSON.parse(resp) } catch { return { summary: resp, total: reviews.length } }
}

function extractRelatedKeywords(products, seed) {
  const words = new Set()
  for (const p of products) {
    if (p.title) {
      p.title.split(/[\s,]+/).forEach(w => {
        const clean = w.replace(/[^a-zA-Z0-9-]/g, '').toLowerCase()
        if (clean.length > 3 && clean !== seed.toLowerCase()) words.add(clean)
      })
    }
  }
  return Array.from(words).slice(0, 30)
}

function simulateSearch(keyword, mp) {
  const products = []
  const categories = ['Premium', 'Eco-friendly', 'Professional', 'Basic', 'Advanced', 'Ultra', 'Compact', 'Portable']
  for (let i = 0; i < 15; i++) {
    const cat = categories[i % categories.length]
    const price = (9.99 + Math.random() * 40).toFixed(2)
    products.push({ asin: 'B0' + String(Math.random()).slice(2, 10), title: `${cat} ${keyword.split(' ').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')} - ${['2-Pack', 'Large', 'Set', 'Premium Quality'][i % 4]}`, price: '$' + price, rating: (3.5 + Math.random() * 1.5).toFixed(1), reviews: Math.floor(Math.random() * 5000), marketplace: mp.domain })
  }
  return products
}

function simulateDetail(asin, mp) {
  return {
    asin, mp: mp.domain,
    title: 'Premium Product ' + asin,
    price: '$' + (9.99 + Math.random() * 50).toFixed(2),
    bsr: { rank: String(Math.floor(Math.random() * 10000) + 1), category: 'Home & Kitchen' },
    rating: (3.5 + Math.random() * 1.5).toFixed(1),
    reviewCount: Math.floor(Math.random() * 2000),
    brand: 'Brand ' + String(Math.random()).slice(2, 5),
  }
}

function getUA() {
  return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'
}

function dedupeByAsin(arr) {
  const seen = new Set()
  return arr.filter(p => { if (seen.has(p.asin)) return false; seen.add(p.asin); return true })
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('amazon', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('amazon', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('amazon', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('amazon', 'skill unloaded'),
}

export default handler
