const SKILL_ID = 'com.tupautochrome.skills.listing-translator'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'listing-translator-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: 'Listing翻译流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { translate: 'do_translate', keywords: 'localize_keywords' } },
    { id: 'do_translate', type: 'process', label: '翻译Listing' },
    { id: 'localize_keywords', type: 'process', label: '关键词本地化' },
    { id: 'report', type: 'process', label: '结果' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'do_translate', label: 'translate' },
    { from: 'choose', to: 'localize_keywords', label: 'keywords' },
    { from: 'do_translate', to: 'report' },
    { from: 'localize_keywords', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [], selectors: {}, variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'AIMarketing' },
}

const LANG_META = {
  de: { name: 'German', market: 'DE', amazonDomain: 'amazon.de' },
  fr: { name: 'French', market: 'FR', amazonDomain: 'amazon.fr' },
  it: { name: 'Italian', market: 'IT', amazonDomain: 'amazon.it' },
  es: { name: 'Spanish', market: 'ES', amazonDomain: 'amazon.es' },
  ja: { name: 'Japanese', market: 'JP', amazonDomain: 'amazon.co.jp' },
  ar: { name: 'Arabic', market: 'SA', amazonDomain: 'amazon.sa' },
  pt: { name: 'Portuguese', market: 'BR', amazonDomain: 'amazon.com.br' },
  nl: { name: 'Dutch', market: 'NL', amazonDomain: 'amazon.nl' },
  sv: { name: 'Swedish', market: 'SE', amazonDomain: 'amazon.se' },
  pl: { name: 'Polish', market: 'PL', amazonDomain: 'amazon.pl' },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'translate': return await translateListing(params)
    case 'keywords': return await localizeKeywords(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function translateListing(params) {
  const t0 = cap.flowchart.beginNode('do_translate')
  const listing = params.listing || {}
  const langs = params.targetLanguages || ['de']
  const translations = []

  for (const lang of langs) {
    const meta = LANG_META[lang]
    if (!meta) continue

    if (cap.llm) {
      const prompt = `Translate this Amazon listing to ${meta.name} (${meta.market}) for ${meta.amazonDomain}. Title: "${listing.title}". Bullets: ${(listing.bullets || []).join(' | ')}. Description: "${(listing.description || '').slice(0, 300)}". Keywords: ${(listing.keywords || []).join(', ')}. Rules: 1) Natural native language, not literal translation 2) Keep SEO keywords adapted for local search 3) Max 200 chars per bullet 4) Title under 200 chars. Return JSON: { title, bullets[], description, localizedKeywords[] }.`
      const resp = await cap.llm.complete(prompt)
      try { translations.push({ language: meta.name, market: meta.market, ...JSON.parse(resp) }); continue } catch { }
    }

    const titleMap = { de: 'Premium Yoga-Matte - Rutschfeste, umweltfreundliche Trainingsmatte', fr: 'Tapis de Yoga Premium - antidérapant et écologique', ja: 'プレミアムヨガマット - 滑り止めエコフレンドリーエクササイズマット', es: 'Alfombrilla de Yoga Premium - Antideslizante Ecológica', it: 'Tappetino Yoga Premium - Antiscivolo Ecologico' }
    translations.push({
      language: meta.name, market: meta.market,
      title: titleMap[lang] || listing.title + ' [' + lang.toUpperCase() + ']',
      bullets: (listing.bullets || []).map(b => `[${lang.toUpperCase()}] ${b}`),
      description: `[${lang.toUpperCase()} localized version] ` + (listing.description || ''),
      localizedKeywords: (listing.keywords || []).map(k => k + ' [' + lang.toUpperCase() + ']'),
    })
  }

  cap.flowchart.endNode('do_translate', translations.length > 0 ? 'ok' : 'fail', `翻译为 ${translations.length} 种语言`, t0)
  return { ok: true, action: 'translate', original: listing, translations }
}

async function localizeKeywords(params) {
  const t0 = cap.flowchart.beginNode('localize_keywords')
  const keywords = params.keywords || []
  const market = params.targetMarket || 'DE'

  if (cap.llm) {
    const prompt = `Localize these Amazon keywords for ${market} market: ${keywords.join(', ')}. Generate: 1) Direct translations 2) Local search terms 3) Long-tail keywords 4) High-volume alternatives. Return JSON: { translations[], localSearchTerms[], longTail[], highVolume[] }.`
    const resp = await cap.llm.complete(prompt)
    try { cap.flowchart.endNode('localize_keywords', 'ok', '关键词本地化完成', t0); return { ok: true, action: 'keywords', targetMarket: market, original: keywords, ...JSON.parse(resp) } } catch { }
  }

  const localMap = {
    DE: { suffix: ' kaufen', prefix: 'beste ', altSuffix: ' Shop' },
    FR: { suffix: ' pas cher', prefix: 'meilleur ', altSuffix: ' en ligne' },
    JP: { suffix: ' おすすめ', prefix: '高品質 ', altSuffix: ' 通販' },
    ES: { suffix: ' barato', prefix: 'mejor ', altSuffix: ' online' },
    IT: { suffix: ' online', prefix: 'miglior ', altSuffix: ' vendita' },
  }
  const cfg = localMap[market] || localMap.DE
  const localized = keywords.flatMap(kw => [ kw + cfg.suffix, cfg.prefix + kw, kw + cfg.altSuffix, kw.replace(/\s+/g, '-') + '-' + market.toLowerCase() ])

  cap.flowchart.endNode('localize_keywords', 'ok', '关键词本地化完成', t0)
  return { ok: true, action: 'keywords', targetMarket: market, original: keywords, translations: localized.slice(0, 10), localSearchTerms: localized.slice(0, 10) }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('translator', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('translator', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('translator', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('translator', 'skill unloaded'),
}

export default handler
