const SKILL_ID = 'com.tupautochrome.skills.listing-optimizer'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'listing-optimizer-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: 'Listing优化流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { title: 'optimize_title', bullets: 'optimize_bullets', description: 'generate_desc', searchTerms: 'optimize_search_terms', fullOptimize: 'full_optimize' } },
    { id: 'optimize_title', type: 'process', label: '标题优化' },
    { id: 'optimize_bullets', type: 'process', label: '五点优化' },
    { id: 'generate_desc', type: 'process', label: '描述生成' },
    { id: 'optimize_search_terms', type: 'process', label: '搜索词优化' },
    { id: 'full_optimize', type: 'process', label: '完整优化' },
    { id: 'report', type: 'process', label: '输出结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'optimize_title', label: 'title' },
    { from: 'choose', to: 'optimize_bullets', label: 'bullets' },
    { from: 'choose', to: 'generate_desc', label: 'description' },
    { from: 'choose', to: 'optimize_search_terms', label: 'searchTerms' },
    { from: 'choose', to: 'full_optimize', label: 'fullOptimize' },
    { from: 'optimize_title', to: 'report' },
    { from: 'optimize_bullets', to: 'report' },
    { from: 'generate_desc', to: 'report' },
    { from: 'optimize_search_terms', to: 'report' },
    { from: 'full_optimize', to: 'report' },
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
    case 'title': return await optimizeTitle(params)
    case 'bullets': return await optimizeBullets(params)
    case 'description': return await generateDescription(params)
    case 'searchTerms': return await optimizeSearchTerms(params)
    case 'fullOptimize': return await fullOptimize(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function optimizeTitle(params) {
  const t0 = cap.flowchart.beginNode('optimize_title')
  const info = params.productInfo
  const marketplace = params.marketplace || 'US'
  if (!info) return { ok: false, error: 'productInfo required' }

  const titles = await generateTitleVariants(info, marketplace)

  let best = null
  if (cap.llm) {
    const prompt = `Evaluate these Amazon ${marketplace} product title variants and rank them:\n${titles.map((t, i) => `${i + 1}. ${t}`).join('\n')}\nProduct: ${info.title}\nCategory: ${info.category || 'General'}\nProvide JSON: { bestTitle, reasoning, seoScore (0-100), clickPotential (0-100) }.`
    const resp = await cap.llm.complete(prompt)
    try { best = JSON.parse(resp) } catch { best = { recommendation: titles[0] } }
  }

  cap.flowchart.endNode('optimize_title', 'ok', `生成 ${titles.length} 个标题变体`, t0)
  return { ok: true, action: 'title', marketplace, original: info.title, variants: titles, best }
}

async function optimizeBullets(params) {
  const t0 = cap.flowchart.beginNode('optimize_bullets')
  const info = params.productInfo
  const current = params.currentBullets || []
  if (!info) return { ok: false, error: 'productInfo required' }

  const bullets = await generateBulletVariants(info, current)

  let analysis = null
  if (cap.llm) {
    const prompt = `Evaluate these Amazon bullet points for conversion optimization:\n${bullets.map((b, i) => `Bullet ${i + 1}: ${b}`).join('\n')}\nProvide JSON: { bestBullets[], improvementNotes[], conversionPotential (0-100) }.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { recommended: bullets } }
  }

  cap.flowchart.endNode('optimize_bullets', 'ok', `优化 ${bullets.length} 个卖点`, t0)
  return { ok: true, action: 'bullets', original: current, optimized: bullets, analysis }
}

async function generateDescription(params) {
  const t0 = cap.flowchart.beginNode('generate_desc')
  const info = params.productInfo
  const style = params.style || 'professional'
  if (!info) return { ok: false, error: 'productInfo required' }

  let description
  if (cap.llm) {
    const prompt = `Generate an Amazon product description in ${style} style for:\nProduct: ${info.title}\nFeatures: ${(info.features || []).join(', ')}\nKeywords: ${(info.keywords || []).join(', ')}\nTarget audience: ${info.targetAudience || 'general'}\nMarketplace: ${params.marketplace || 'US'}\n\nInclude: engaging intro, feature highlights, benefits, call to action. Keep under 2000 chars.`
    const resp = await cap.llm.complete(prompt)
    description = resp
  } else {
    const features = (info.features || ['feature 1', 'feature 2', 'feature 3'])
    description = `Introducing the ${info.title || 'Premium Product'}! ${features.slice(0, 3).map((f, i) => `✓ ${f.charAt(0).toUpperCase() + f.slice(1)}`).join('. ')}. Perfect for ${info.targetAudience || 'everyone'} who demands quality. Order now!`
  }

  cap.flowchart.endNode('generate_desc', 'ok', '描述生成完成', t0)
  return { ok: true, action: 'description', style, description }
}

async function optimizeSearchTerms(params) {
  const t0 = cap.flowchart.beginNode('optimize_search_terms')
  const keywords = params.keywords || []
  const marketplace = params.marketplace || 'US'
  if (keywords.length === 0) return { ok: false, error: 'keywords required' }

  const expanded = expandKeywords(keywords)

  let analysis = null
  if (cap.llm) {
    const prompt = `Optimize Amazon ${marketplace} backend search terms for keywords: ${keywords.join(', ')}.\nRelated: ${expanded.slice(0, 20).join(', ')}.\nProvide JSON: { recommendedSearchTerms, highVolumeKeywords[], longTailKeywords[], seasonalKeywords[], strategy }.`
    const resp = await cap.llm.complete(prompt)
    try { analysis = JSON.parse(resp) } catch { analysis = { recommended: expanded.slice(0, 10) } }
  }

  cap.flowchart.endNode('optimize_search_terms', 'ok', `优化 ${expanded.length} 个搜索词`, t0)
  return { ok: true, action: 'searchTerms', marketplace, seedKeywords: keywords, expanded, analysis }
}

async function fullOptimize(params) {
  const t0 = cap.flowchart.beginNode('full_optimize')
  const listing = params.listing
  const marketplace = params.marketplace || 'US'
  if (!listing) return { ok: false, error: 'listing required' }

  const result = {
    title: await generateTitleVariants({ title: listing.title, features: listing.features, keywords: listing.keywords, category: listing.category }, marketplace),
    bullets: await generateBulletVariants({ features: listing.features, keywords: listing.keywords }, listing.bullets),
    description: await generateDescriptionLocal({ features: listing.features, keywords: listing.keywords, title: listing.title }, params.style || 'professional', marketplace),
  }

  let report = null
  if (cap.llm) {
    const prompt = `Full listing optimization report for Amazon ${marketplace}:\nOriginal Title: ${listing.title}\nOriginal Bullets: ${(listing.bullets || []).join(' | ')}\n\nProvide JSON: { overallScore (0-100), criticalIssues[], suggestedImprovements[], keywordDensity, competitiveAnalysis }.`
    const resp = await cap.llm.complete(prompt)
    try { report = JSON.parse(resp) } catch { report = { summary: resp } }
  }

  cap.flowchart.endNode('full_optimize', 'ok', '完整优化完成', t0)
  return { ok: true, action: 'fullOptimize', marketplace, result, report }
}

async function generateTitleVariants(info, mp) {
  if (cap.llm) {
    const prompt = `Generate 5 Amazon ${mp} product title variants for:\nProduct: ${info.title || 'Product'}\nKey features: ${(info.features || []).join(', ')}\nTarget keywords: ${(info.keywords || []).join(', ')}\nCategory: ${info.category || 'General'}\n\nRules: Under 200 chars each, include brand+product+key features+keywords, natural flow. Return as JSON array.`
    const resp = await cap.llm.complete(prompt)
    try { return JSON.parse(resp) } catch { }
  }
  const kw = (info.keywords || [info.title || 'product'])[0]
  const features = info.features || []
  return [
    `${info.title || 'Premium'} - ${features.slice(0, 2).join(', ')} for ${mp === 'US' ? 'Home & Gym' : 'Daily Use'} | ${kw}`,
    `${info.title || 'Premium'} ${kw.toUpperCase()} - ${features.slice(0, 3).join(' | ')}`,
    `Professional ${kw} - ${features[0] || 'Premium Quality'} ${features[1] ? '| ' + features[1] : ''} Ideal for ${mp === 'DE' ? 'Zuhause & Reise' : 'Home & Travel'}`,
    `${[mp === 'JP' ? 'プレミアム' : 'Premium', info.title || 'Product'].join(' ')} | ${features.join(' - ')}`,
    `${kw}: ${info.title || 'Premium Product'} with ${features.slice(0, 2).join(' and ')} - ${mp === 'UK' ? 'Shop Now' : 'Buy Now'}`,
  ]
}

async function generateBulletVariants(info, current) {
  if (cap.llm) {
    const prompt = `Generate 5 optimized Amazon bullet points for:\nFeatures: ${(info.features || []).join(', ')}\nKeywords: ${(info.keywords || []).join(', ')}\nCurrent bullets: ${current.join(' | ')}\n\nEach bullet: starts with emoji, benefit-driven, includes keyword naturally, max 200 chars. Return JSON array of 5 strings.`
    const resp = await cap.llm.complete(prompt)
    try { return JSON.parse(resp) } catch { }
  }
  const features = info.features || ['feature']
  return features.slice(0, 5).map((f, i) => {
    const emojis = ['✅', '✨', '💪', '🎯', '🌟']
    const benefits = ['Professional grade quality', 'Built to last', 'Perfect for daily use', 'Customer favorite', 'Top rated choice']
    return `${emojis[i]} ${benefits[i % benefits.length]}: ${f.charAt(0).toUpperCase() + f.slice(1)} - ${['Ideal for professionals', 'Perfect for home use', 'Great for travel', 'Excellent value', 'Superior performance'][i]}`
  })
}

async function generateDescriptionLocal(info, style, mp) {
  if (cap.llm) {
    const prompt = `Generate Amazon ${mp} product description in ${style} style for:\n${info.title}\nFeatures: ${(info.features || []).join(', ')}\nKeep under 1500 chars.`
    return await cap.llm.complete(prompt)
  }
  return `Experience the quality of ${info.title || 'our product'}. Designed with ${(info.features || ['care']).slice(0, 3).join(', ')}, it delivers outstanding performance. Order with confidence on Amazon ${mp}.`
}

function expandKeywords(keywords) {
  const expanded = new Set(keywords.map(k => k.toLowerCase().trim()))
  const additions = ['best', 'top', 'premium', 'quality', 'professional', 'affordable', 'cheap', 'discount', 'sale', 'new', '2026', 'for sale', 'near me', 'online', 'shop', 'buy', 'wholesale', 'bulk', 'set', 'kit', 'pack', 'with', 'for', 'and']
  keywords.forEach(kw => {
    const clean = kw.toLowerCase().trim()
    additions.forEach(add => {
      expanded.add(add + ' ' + clean)
      expanded.add(clean + ' ' + add)
    })
  })
  return Array.from(expanded).slice(0, 50)
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('listing', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('listing', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('listing', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('listing', 'skill unloaded'),
}

export default handler
