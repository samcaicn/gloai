const SKILL_ID = 'com.tupautochrome.skills.global-tax-guide'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'global-tax-guide-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '全球税务合规流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择操作', branches: { check: 'tax_check', landedCost: 'landed_cost', compliance: 'product_compliance' } },
    { id: 'tax_check', type: 'process', label: '税务检查' },
    { id: 'landed_cost', type: 'process', label: '到岸成本' },
    { id: 'product_compliance', type: 'process', label: '产品合规' },
    { id: 'report', type: 'process', label: '报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'tax_check', label: 'check' },
    { from: 'choose', to: 'landed_cost', label: 'landedCost' },
    { from: 'choose', to: 'product_compliance', label: 'compliance' },
    { from: 'tax_check', to: 'report' },
    { from: 'landed_cost', to: 'report' },
    { from: 'product_compliance', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [], selectors: {}, variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-26T00:00:00Z', updatedAt: '2026-07-26T00:00:00Z', author: 'AIMarketing' },
}

const TAX_RULES = {
  UK: { type: 'VAT', rate: 0.20, threshold: 85000, currency: 'GBP', registration: 'HMRC', ioss: false, note: 'Post-Brexit separate registration' },
  DE: { type: 'VAT', rate: 0.19, threshold: 100000, currency: 'EUR', registration: 'BZSt', ioss: true, note: 'VerpackG + EPR required' },
  FR: { type: 'VAT', rate: 0.20, threshold: 100000, currency: 'EUR', registration: 'DGFiP', ioss: true, note: 'French EPR compliance' },
  IT: { type: 'VAT', rate: 0.22, threshold: 100000, currency: 'EUR', registration: 'Agenzia Entrate', ioss: true },
  ES: { type: 'VAT', rate: 0.21, threshold: 100000, currency: 'EUR', registration: 'AEAT', ioss: true },
  JP: { type: 'Consumption Tax', rate: 0.10, threshold: 10000000, currency: 'JPY', registration: 'NTA', ioss: false, note: 'JCT from 2024' },
  CA: { type: 'GST/HST', rate: 0.05, threshold: 30000, currency: 'CAD', registration: 'CRA', ioss: false, note: 'Provincial rates vary' },
  AU: { type: 'GST', rate: 0.10, threshold: 75000, currency: 'AUD', registration: 'ATO', ioss: false },
  SG: { type: 'GST', rate: 0.09, threshold: 1000000, currency: 'SGD', registration: 'IRAS', ioss: false },
}

const PRODUCT_COMPLIANCE = {
  electronics: { EU: ['CE', 'RoHS', 'WEEE', 'REACH'], US: ['FCC', 'UL', 'Energy Star'], JP: ['PSE', 'VCCI'] },
  toys: { EU: ['CE', 'EN71', 'REACH'], US: ['CPSIA', 'ASTM F963'], JP: ['ST'] },
  food: { EU: ['CE', 'FDA equivalent', 'Organic cert'], US: ['FDA', 'USDA'], JP: ['JFRL'] },
  cosmetics: { EU: ['CPNP', 'REACH'], US: ['FDA', 'MoCRA'], JP: ['PAL'] },
  medical: { EU: ['CE (MDR)', 'ISO 13485'], US: ['FDA 510(k)'], JP: ['PMDA'] },
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'check': return await taxCheck(params)
    case 'landedCost': return await landedCost(params)
    case 'compliance': return await productCompliance(params)
    default: return { ok: false, error: 'unknown action: ' + action }
  }
}

async function taxCheck(params) {
  const t0 = cap.flowchart.beginNode('tax_check')
  const markets = params.markets || []
  const revenue = params.revenue || {}
  const obligations = markets.filter(m => TAX_RULES[m]).map(m => {
    const rule = TAX_RULES[m]
    const annual = revenue[m] || 0
    const thresholdReached = annual * (m === 'JP' ? 1 : m === 'CA' ? 1.3 : 1.1) >= rule.threshold
    return { market: m, taxType: rule.type, rate: (rule.rate * 100) + '%', thresholdExceeded: thresholdReached, needsRegistration: thresholdReached, registrationBody: rule.registration, iossEligible: rule.ioss, notes: rule.note || '' }
  })
  cap.flowchart.endNode('tax_check', 'ok', `检查 ${obligations.length} 个市场税务`, t0)
  return { ok: true, action: 'check', obligations }
}

async function landedCost(params) {
  const t0 = cap.flowchart.beginNode('landed_cost')
  const price = params.productPrice || 0
  const dest = params.destination || 'US'
  const shipping = price * 0.15
  const dutyRate = { US: 0.025, UK: 0.035, DE: 0.04, JP: 0.03, CA: 0.02, AU: 0.05, SG: 0.0 }[dest] || 0.03
  const duty = price * dutyRate
  const insurance = price * 0.01
  const taxRate = TAX_RULES[dest]?.rate || 0
  const taxable = price + shipping + duty + insurance
  const tax = taxable * taxRate
  const total = taxable + tax

  cap.flowchart.endNode('landed_cost', 'ok', `到岸成本计算完成`, t0)
  return {
    ok: true, action: 'landedCost',
    origin: params.origin || 'CN', destination: dest,
    productPrice: price, shipping, duty: { rate: (dutyRate * 100) + '%', amount: duty },
    insurance, tax: { rate: (taxRate * 100) + '%', amount: tax, type: TAX_RULES[dest]?.type || 'Tax' },
    totalLandedCost: total, marginImpact: ((total - price) / price * 100).toFixed(1) + '%',
    breakdown: `Product $${price.toFixed(2)} + Shipping $${shipping.toFixed(2)} + Duty $${duty.toFixed(2)} + Insurance $${insurance.toFixed(2)} = Taxable $${taxable.toFixed(2)} + Tax $${tax.toFixed(2)} = Total $${total.toFixed(2)}`,
  }
}

async function productCompliance(params) {
  const t0 = cap.flowchart.beginNode('product_compliance')
  const productType = params.productType || 'electronics'
  const markets = params.markets || ['EU', 'US', 'JP']
  const certs = PRODUCT_COMPLIANCE[productType] || PRODUCT_COMPLIANCE.electronics
  const result = markets.filter(m => certs[m]).map(m => ({ market: m, certifications: certs[m] }))
  cap.flowchart.endNode('product_compliance', 'ok', `生成 ${result.length} 个市场认证要求`, t0)
  return { ok: true, action: 'compliance', productType, requirements: result }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('tax', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('tax', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('tax', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('tax', 'skill unloaded'),
}

export default handler
