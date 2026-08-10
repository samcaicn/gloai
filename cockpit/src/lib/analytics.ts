/**
 * analytics.ts — 六交叉计算引擎（设计方案 v2.1 §2.2 + §9.2 Analytics Contract）
 *
 * 第一原则：确定性计算一律在此完成，AI 只做综合研判。
 * 所有函数为纯函数；输出包含证据数字，既供图表渲染、也作为 AI 的证据包。
 *
 * Contract 关键口径：
 * - 重度使用者 阈值：月 AI 成本 ≥ ¥7,000 且活跃 ≥ 20 天（数据层已按此打 tier，本层直接用 tier）
 * - 趋势斜率：近 6 个月人效 OLS slope；|月均变化率| < 2% 视为平稳
 * - 极值倍数：格子 headcount < 3 剔除；per_capita < ¥10 剔除；winsorize P5/P95
 * - 用户分层：重度使用者 / Regular / Light 三层（无 Zero）
 */

import {
  ProjectWithMetrics,
  MonthlyRecord,
  TalentRecord,
  RoleDeptCell,
  Quadrant,
} from './types'

// ───────────────────────── 基础统计工具 ─────────────────────────

export function median(values: number[]): number {
  if (values.length === 0) return 0
  const s = [...values].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 === 0 ? (s[m - 1] + s[m]) / 2 : s[m]
}

function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0
  const idx = (sorted.length - 1) * p
  const lo = Math.floor(idx)
  const hi = Math.ceil(idx)
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
}

export function winsorize(values: number[], pLow = 0.05, pHigh = 0.95): number[] {
  const sorted = [...values].sort((a, b) => a - b)
  const lo = percentile(sorted, pLow)
  const hi = percentile(sorted, pHigh)
  return values.map(v => Math.min(Math.max(v, lo), hi))
}

/** OLS 斜率（x = 0..n-1） */
export function olsSlope(ys: number[]): number {
  const n = ys.length
  if (n < 2) return 0
  const xMean = (n - 1) / 2
  const yMean = ys.reduce((s, y) => s + y, 0) / n
  let num = 0
  let den = 0
  for (let i = 0; i < n; i++) {
    num += (i - xMean) * (ys[i] - yMean)
    den += (i - xMean) ** 2
  }
  return den === 0 ? 0 : num / den
}

export type TrendDirection = 'up' | 'down' | 'flat'

/** 近 6 个月人效趋势：斜率换算为月均变化率，|rate| < 2% 平稳 */
export function getProductivityTrend(
  projectId: string,
  trend: MonthlyRecord[],
  window = 6
): { direction: TrendDirection; slope: number; monthlyRate: number } {
  const rows = trend
    .filter(r => r.project_id === projectId)
    .sort((a, b) => a.month.localeCompare(b.month))
    .slice(-window)
  const ys = rows.map(r => r.productivity)
  const slope = olsSlope(ys)
  const base = ys.length > 0 ? Math.abs(ys[0]) || 1 : 1
  const monthlyRate = slope / base
  const direction: TrendDirection =
    monthlyRate > 0.02 ? 'up' : monthlyRate < -0.02 ? 'down' : 'flat'
  return { direction, slope, monthlyRate }
}

// ───────────────────────── 交叉① AI 杠杆有效性矩阵 ─────────────────────────

export interface LeveragePoint {
  project_id: string
  name: string
  ai_intensity: number
  productivity: number
  headcount: number
  quadrant: Quadrant
  trend: TrendDirection
  monthlyRate: number
  /** 真放大器 = 高AI高人效 且 趋势↑；运气好 = 高人效但趋势平/降 */
  verdict: 'amplifier_confirmed' | 'amplifier_unproven' | 'underperforming' | 'high_potential' | 'low_base' | 'support'
}

export interface LeverageMatrix {
  points: LeveragePoint[]
  medianIntensity: number
  medianProductivity: number
  counts: Record<LeveragePoint['verdict'], number>
}

export function getLeverageMatrix(
  projects: ProjectWithMetrics[],
  trend: MonthlyRecord[]
): LeverageMatrix {
  const valid = projects.filter(p => p.labor_cost > 0 && !p.is_cost_center)
  const medianIntensity = median(valid.map(p => p.ai_intensity))
  const medianProductivity = median(valid.map(p => p.productivity))

  const points: LeveragePoint[] = projects.filter(p => p.labor_cost > 0).map(p => {
    const t = getProductivityTrend(p.id, trend)
    let verdict: LeveragePoint['verdict']
    if (p.is_cost_center || p.quadrant === 'support') {
      verdict = 'support'
    } else if (p.quadrant === 'amplifier') {
      verdict = t.direction === 'up' ? 'amplifier_confirmed' : 'amplifier_unproven'
    } else {
      verdict = p.quadrant
    }
    return {
      project_id: p.id,
      name: p.name,
      ai_intensity: p.ai_intensity,
      productivity: p.productivity,
      headcount: p.headcount,
      quadrant: p.quadrant,
      trend: t.direction,
      monthlyRate: t.monthlyRate,
      verdict,
    }
  })

  const counts = {
    amplifier_confirmed: 0,
    amplifier_unproven: 0,
    underperforming: 0,
    high_potential: 0,
    low_base: 0,
    support: 0,
  }
  points.forEach(p => { counts[p.verdict]++ })

  return { points, medianIntensity, medianProductivity, counts }
}

// ───────────────────────── 交叉② 同岗位跨部门差距 ─────────────────────────

export interface RoleDivergence {
  role: string
  cells: RoleDeptCell[]          // 已过滤小样本，按 per_capita 降序
  maxCell: RoleDeptCell
  minCell: RoleDeptCell
  gapMultiple: number            // winsorize 后的极值倍数
}

export function getRoleDeptDivergence(
  matrix: RoleDeptCell[] | null
): RoleDivergence[] {
  if (!matrix || matrix.length === 0) return []
  const byRole = new Map<string, RoleDeptCell[]>()
  for (const cell of matrix) {
    // Contract: headcount < 3 与 per_capita < ¥10 剔除
    if (cell.headcount < 3 || cell.per_capita < 10) continue
    const list = byRole.get(cell.role) || []
    list.push(cell)
    byRole.set(cell.role, list)
  }

  const result: RoleDivergence[] = []
  for (const [role, cells] of byRole) {
    if (cells.length < 2) continue
    const ws = winsorize(cells.map(c => c.per_capita))
    const pairs = cells.map((c, i) => ({ cell: c, w: ws[i] }))
      .sort((a, b) => b.w - a.w)
    const maxP = pairs[0]
    const minP = pairs[pairs.length - 1]
    const gap = minP.w > 0 ? maxP.w / minP.w : 0
    result.push({
      role,
      cells: pairs.map(p => p.cell),
      maxCell: maxP.cell,
      minCell: minP.cell,
      gapMultiple: Math.round(gap * 10) / 10,
    })
  }
  return result.sort((a, b) => b.gapMultiple - a.gapMultiple)
}

// ───────────────────────── 交叉③ 模型×岗位错配 ─────────────────────────

const TECH_ROLES = new Set(['技术研发', '管理'])
const EXPENSIVE_MODELS = ['Claude Opus']

export interface ModelMismatch {
  project_id: string
  name: string
  dominantRole: string
  dominantRoleShare: number      // 主导序列人数占比
  expensiveShare: number         // 高价模型成本占比
  fastShare: number              // GPT Fast 等速度税占比（按可得数据）
  flag: 'mismatch_suspect' | 'reasonable' | 'unknown'
  evidence: string               // 一句事实陈述（系统计算，非 AI）
}

export function getModelMismatch(projects: ProjectWithMetrics[]): ModelMismatch[] {
  return projects.map(p => {
    const roles = Object.entries(p.role_distribution).filter(([, v]) => v > 0)
    const totalHC = roles.reduce((s, [, v]) => s + v, 0)
    const [domRole, domCount] = roles.sort((a, b) => b[1] - a[1])[0] || ['未知', 0]
    const domShare = totalHC > 0 ? domCount / totalHC : 0

    const expensiveShare = EXPENSIVE_MODELS
      .reduce((s, m) => s + (p.ai_model_mix[m] || 0), 0)
    const fastShare = p.ai_model_mix['GPT'] || 0

    let flag: ModelMismatch['flag'] = 'unknown'
    if (totalHC > 0) {
      flag = !TECH_ROLES.has(domRole) && expensiveShare > 0.4
        ? 'mismatch_suspect'
        : 'reasonable'
    }

    const evidence = `主导岗位 ${domRole}（占 ${(domShare * 100).toFixed(0)}%），高价模型成本占比 ${(expensiveShare * 100).toFixed(0)}%`

    return {
      project_id: p.id,
      name: p.name,
      dominantRole: domRole,
      dominantRoleShare: domShare,
      expensiveShare,
      fastShare,
      flag,
      evidence,
    }
  }).sort((a, b) => b.expensiveShare - a.expensiveShare)
}

// ───────────────────────── 交叉④ 人才定价错配 ─────────────────────────

export interface PricingMismatch {
  highPaidLowUse: TalentRecord[]   // CR > 1.16 ∩ Light
  highUseLowPaid: TalentRecord[]   // 重度使用者 ∩ CR < 0.9（留不住的核心战力）
  crSource: 'real' | 'proxy'
  note: string
}

export function getPricingMismatch(talents: TalentRecord[]): PricingMismatch {
  const highPaidLowUse = talents
    .filter(t => t.cr_value > 1.16 && t.tier === 'light')
    .sort((a, b) => b.cr_value - a.cr_value)
  const highUseLowPaid = talents
    .filter(t => t.tier === 'power' && t.cr_value < 0.9)
    .sort((a, b) => a.cr_value - b.cr_value)
  const crSource = talents.some(t => t.cr_source === 'real') ? 'real' : 'proxy'
  return {
    highPaidLowUse,
    highUseLowPaid,
    crSource,
    note: '以 AI 使用强度作为产能代理指标；CR 为' + (crSource === 'proxy' ? '同职级薪酬中位数代理值' : '真实值'),
  }
}

// ───────────────────────── 交叉⑤ 个人依赖-脆弱扫描 ─────────────────────────

export interface FragilityPoint {
  id: string
  project_id: string
  role: string | null
  deptShare: number              // 个人 AI 成本占部门比
  cr: number
  tier: TalentRecord['tier']
  同期入职队列StayIntention: number | null  // 所在项目 同期入职队列 留任意愿（护栏背景，不做个人结论）
  fragile: boolean               // 高依赖(占部门>10%) ∩ CR<0.9
}

export interface FragilityScan {
  points: FragilityPoint[]
  fragileCount: number
  degraded: boolean              // 数据缺 ai_cost_dept_share 时为 true
}

export function getDependencyFragility(
  talents: TalentRecord[],
  projects: ProjectWithMetrics[]
): FragilityScan {
  const stayByProject = new Map(
    projects.map(p => [p.id, p.engagement_dimensions?.stay_intention ?? null])
  )
  const hasShare = talents.some(t => typeof t.ai_cost_dept_share === 'number')

  const points: FragilityPoint[] = talents
    .filter(t => t.tier !== 'light')
    .map(t => {
      const share = t.ai_cost_dept_share ?? 0
      return {
        id: t.id,
        project_id: t.project_id,
        role: t.role,
        deptShare: share,
        cr: t.cr_value,
        tier: t.tier,
        同期入职队列StayIntention: stayByProject.get(t.project_id) ?? null,
        fragile: hasShare ? share > 0.1 && t.cr_value < 0.9 : t.tier === 'power' && t.cr_value < 0.9,
      }
    })

  return {
    points,
    fragileCount: points.filter(p => p.fragile).length,
    degraded: !hasShare,
  }
}

// ───────────────────────── 交叉⑥ 关键人才风险（保人名单） ─────────────────────────

export interface CriticalTalent {
  id: string
  project_id: string
  project_name: string
  role: string | null
  cr: number
  active_days: number
  signals: string[]              // ['重度使用者 核心', '薪酬位档偏低', '团队流失环境']
}

export function getCriticalTalentList(
  talents: TalentRecord[],
  projects: ProjectWithMetrics[]
): CriticalTalent[] {
  const projById = new Map(projects.map(p => [p.id, p]))
  // 团队流失环境：近期离职 > 3 或被动离职 > 0 或 同期入职队列 留任意愿 < 60
  const riskyEnv = new Set(
    projects
      .filter(p =>
        p.recent_turnover.total_exits > 3 ||
        p.recent_turnover.involuntary_exits > 0 ||
        (p.engagement_dimensions?.stay_intention != null && p.engagement_dimensions.stay_intention < 60)
      )
      .map(p => p.id)
  )

  return talents
    .filter(t => t.tier === 'power' && t.cr_value < 0.9 && riskyEnv.has(t.project_id))
    .map(t => {
      const p = projById.get(t.project_id)
      const signals = ['重度使用者 核心（月AI≥¥7,000 且活跃≥20天）', `CR ${t.cr_value.toFixed(2)} 薪酬倒挂`]
      if (p) {
        if (p.recent_turnover.total_exits > 3) signals.push(`所在团队近期离职 ${p.recent_turnover.total_exits} 人`)
        if (p.engagement_dimensions?.stay_intention != null && p.engagement_dimensions.stay_intention < 60) {
          signals.push(`同期入职队列 留任意愿 ${p.engagement_dimensions.stay_intention.toFixed(0)}（偏低）`)
        }
      }
      return {
        id: t.id,
        project_id: t.project_id,
        project_name: p?.name || t.project_id,
        role: t.role,
        cr: t.cr_value,
        active_days: t.active_days,
        signals,
      }
    })
    .sort((a, b) => a.cr - b.cr)
}

// ───────────────────────── 归因五步证据包（③章主战场） ─────────────────────────

export interface EvidenceStep {
  key: 'model' | 'people' | 'depth' | 'attrition' | 'org'
  title: string
  facts: { label: string; value: string; benchmark?: string }[]
  chart?: AttributionChartData | null
  /** 系统计算的事实陈述（UI 标"系统计算"） */
  finding: string
  /** 预判严重度（AI 可覆写） */
  severity: 'high' | 'medium' | 'low' | 'none'
}

export type AttributionChartData =
  | ModelChartData
  | PeopleChartData
  | DepthChartData
  | AttritionChartData
  | OrgChartData

export interface ModelChartData {
  type: 'model_compare'
  current: { model: string; share: number }[]
  benchmark: { model: string; share: number }[]
  topGap: { model: string; gap: number }
}

export interface PeopleChartData {
  type: 'active_dist'
  buckets: { range: string; current: number; benchmark: number }[]
  powerShareCurrent: number
  powerShareBenchmark: number
}

export interface DepthChartData {
  type: 'depth_trend'
  months: string[]
  currentPerCapita: number[]
  benchmarkPerCapita: number[]
}

export interface AttritionChartData {
  type: 'attrition_breakdown'
  months: string[]
  voluntary: number[]
  involuntary: number[]
  powerExits: number
  totalExits: number
}

export interface OrgChartData {
  type: 'org_compare'
  layers: { name: string; current: number; benchmark: number }[]
}

export interface AttributionEvidence {
  project: ProjectWithMetrics
  benchmark: ProjectWithMetrics | null   // 同象限外的标杆（amplifier 中人效最高且主导序列接近）
  benchmarkQuality?: 'strong' | 'medium' | 'weak' | 'fallback'
  steps: EvidenceStep[]
}

function fmtWan(v: number): string {
  return (v / 10000).toFixed(1) + '万'
}

function pct(v: number): string {
  return (v * 100).toFixed(0) + '%'
}

const ATTRIBUTION_MODELS = ['Claude Opus', 'Claude Sonnet', 'GPT', 'Cursor/IDE', 'Mivo', '其他']
const ACTIVE_BUCKETS = [
  { range: '0-5天', min: 0, max: 5 },
  { range: '5-10天', min: 5, max: 10 },
  { range: '10-15天', min: 10, max: 15 },
  { range: '15-20天', min: 15, max: 20 },
  { range: '20+天', min: 20, max: Infinity },
]
const ORG_MAIN_ROLES = ['管理', '技术研发', '产品', '设计', '运营', '美术']
const ROLE_FALLBACK_LABELS = new Set(['其他', '未分类', '杂项', 'Other'])

function selectAttributionBenchmark(
  projects: ProjectWithMetrics[],
  current: ProjectWithMetrics,
  trend: MonthlyRecord[],
  roleMatrix: RoleDeptCell[] | null
): { benchmark: ProjectWithMetrics | null; quality?: AttributionEvidence['benchmarkQuality'] } {
  const roleDiversity = (projectId: string) => {
    const cells = roleMatrix?.filter(cell =>
      cell.project_id === projectId && !ROLE_FALLBACK_LABELS.has(cell.role)
    ) || []
    return new Set(cells.map(cell => cell.role)).size
  }
  const candidates = projects.filter(project =>
    project.id !== current.id
    && !project.is_cost_center
    && project.quadrant === 'amplifier'
    && getProductivityTrend(project.id, trend).direction === 'up'
  )
  if (candidates.length === 0) return { benchmark: null }

  const sortByPriority = (list: ProjectWithMetrics[]) => [...list].sort((a, b) => {
    const aSameType = a.type === current.type ? 1 : 0
    const bSameType = b.type === current.type ? 1 : 0
    if (aSameType !== bSameType) return bSameType - aSameType
    return b.productivity - a.productivity
  })

  const strong = candidates.filter(project => project.headcount >= 15 && roleDiversity(project.id) >= 4)
  if (strong.length > 0) return { benchmark: sortByPriority(strong)[0], quality: 'strong' }

  const medium = candidates.filter(project => project.headcount >= 10 && roleDiversity(project.id) >= 3)
  if (medium.length > 0) return { benchmark: sortByPriority(medium)[0], quality: 'medium' }

  const weak = candidates.filter(project => project.headcount >= 5 && roleDiversity(project.id) >= 2)
  if (weak.length > 0) return { benchmark: sortByPriority(weak)[0], quality: 'weak' }

  return { benchmark: sortByPriority(candidates)[0], quality: 'fallback' }
}

function buildModelChart(project: ProjectWithMetrics, benchmark: ProjectWithMetrics | null): ModelChartData {
  const current = ATTRIBUTION_MODELS.map(model => ({ model, share: project.ai_model_mix?.[model] || 0 }))
  const benchmarkRows = benchmark
    ? ATTRIBUTION_MODELS.map(model => ({ model, share: benchmark.ai_model_mix?.[model] || 0 }))
    : []
  const topGap = ATTRIBUTION_MODELS
    .map(model => ({
      model,
      gap: Math.abs((project.ai_model_mix?.[model] || 0) - (benchmark?.ai_model_mix?.[model] || 0)),
    }))
    .sort((a, b) => b.gap - a.gap)[0] || { model: '无', gap: 0 }
  return { type: 'model_compare', current, benchmark: benchmarkRows, topGap }
}

function bucketActiveDays(rows: TalentRecord[]): { range: string; count: number }[] {
  return ACTIVE_BUCKETS.map(bucket => ({
    range: bucket.range,
    count: rows.filter(row => row.active_days >= bucket.min && row.active_days < bucket.max).length,
  }))
}

function buildPeopleChart(current: TalentRecord[], benchmark: TalentRecord[]): PeopleChartData {
  const currentBuckets = bucketActiveDays(current)
  const benchmarkBuckets = bucketActiveDays(benchmark)
  return {
    type: 'active_dist',
    buckets: ACTIVE_BUCKETS.map((bucket, index) => ({
      range: bucket.range,
      current: currentBuckets[index]?.count || 0,
      benchmark: benchmarkBuckets[index]?.count || 0,
    })),
    powerShareCurrent: current.length > 0 ? current.filter(row => row.tier === 'power').length / current.length : 0,
    powerShareBenchmark: benchmark.length > 0 ? benchmark.filter(row => row.tier === 'power').length / benchmark.length : 0,
  }
}

function buildDepthChart(
  projectId: string,
  benchmarkId: string | null,
  trend: MonthlyRecord[]
): DepthChartData | null {
  const currentRows = trend
    .filter(row => row.project_id === projectId)
    .sort((a, b) => a.month.localeCompare(b.month))
    .slice(-6)
  if (currentRows.length === 0) return null
  const months = currentRows.map(row => row.month)
  const benchmarkRows = benchmarkId
    ? trend.filter(row => row.project_id === benchmarkId).sort((a, b) => a.month.localeCompare(b.month))
    : []
  const benchmarkByMonth = new Map(benchmarkRows.map(row => [row.month, row]))
  return {
    type: 'depth_trend',
    months,
    currentPerCapita: currentRows.map(row => row.ai_cost / Math.max(row.headcount, 1)),
    benchmarkPerCapita: months.map(month => {
      const row = benchmarkByMonth.get(month)
      return row ? row.ai_cost / Math.max(row.headcount, 1) : 0
    }),
  }
}

function buildAttritionChart(project: ProjectWithMetrics): AttritionChartData {
  const turnover = project.recent_turnover
  const voluntary = Math.max(0, turnover.voluntary_exits || 0)
  const involuntary = Math.max(0, turnover.involuntary_exits || 0)
  const known = voluntary + involuntary
  const total = Math.max(turnover.total_exits || 0, known)
  return {
    type: 'attrition_breakdown',
    months: ['累计'],
    voluntary: [known > 0 ? voluntary : 0],
    involuntary: [known > 0 ? involuntary : total],
    powerExits: turnover.power_user_exits || 0,
    totalExits: total,
  }
}

function buildOrgChart(
  projectId: string,
  benchmarkId: string | null,
  roleMatrix: RoleDeptCell[] | null
): OrgChartData | null {
  if (!roleMatrix || roleMatrix.length === 0) return null
  const byProjectRole = new Map<string, RoleDeptCell>()
  roleMatrix.forEach(cell => {
    if (ORG_MAIN_ROLES.includes(cell.role)) byProjectRole.set(`${cell.project_id}:${cell.role}`, cell)
  })
  const layers = ORG_MAIN_ROLES
    .map(role => ({
      name: role,
      current: byProjectRole.get(`${projectId}:${role}`)?.per_capita || 0,
      benchmark: benchmarkId ? byProjectRole.get(`${benchmarkId}:${role}`)?.per_capita || 0 : 0,
    }))
    .filter(layer => layer.current > 0 || layer.benchmark > 0)
  return layers.length > 0 ? { type: 'org_compare', layers } : null
}

export function buildAttributionEvidence(
  projectId: string,
  projects: ProjectWithMetrics[],
  trend: MonthlyRecord[],
  talents: TalentRecord[],
  roleMatrix: RoleDeptCell[] | null
): AttributionEvidence | null {
  const project = projects.find(p => p.id === projectId)
  if (!project) return null

  // 标杆：优先已验证有效、同类型、人数与角色结构更典型的项目。
  const { benchmark, quality: benchmarkQuality } = selectAttributionBenchmark(projects, project, trend, roleMatrix)

  const pTalents = talents.filter(t => t.project_id === projectId)
  const benchmarkTalents = benchmark ? talents.filter(t => t.project_id === benchmark.id) : []
  const powerUsers = pTalents.filter(t => t.tier === 'power')
  const t = getProductivityTrend(projectId, trend)

  // Step 1 用对模型了吗
  const expShare = (project.ai_model_mix['Claude Opus'] || 0)
  const bmExpShare = benchmark ? (benchmark.ai_model_mix['Claude Opus'] || 0) : null
  const domRole = Object.entries(project.role_distribution).sort((a, b) => b[1] - a[1])[0]
  const modelSeverity: EvidenceStep['severity'] =
    domRole && !TECH_ROLES.has(domRole[0]) && expShare > 0.4 ? 'high'
    : expShare > 0.6 ? 'medium' : 'none'

  // Step 2 用对人了吗（重度使用者的岗位覆盖 vs 团队岗位结构）
  const powerRoles = new Map<string, number>()
  powerUsers.forEach(u => {
    if (u.role) powerRoles.set(u.role, (powerRoles.get(u.role) || 0) + 1)
  })
  const teamRoles = Object.entries(project.role_distribution).filter(([, v]) => v > 0)
  const uncoveredRoles = teamRoles
    .filter(([r, v]) => v >= 5 && !powerRoles.has(r))
    .map(([r]) => r)
  const peopleSeverity: EvidenceStep['severity'] =
    uncoveredRoles.length >= 2 ? 'high' : uncoveredRoles.length === 1 ? 'medium' : 'none'

  // Step 3 用得够深吗（活跃天数 / 人均 vs 标杆；role matrix 可用时用同序列标杆）
  const perCapita = project.ai_cost / Math.max(project.headcount, 1)
  const bmPerCapita = benchmark ? benchmark.ai_cost / Math.max(benchmark.headcount, 1) : null
  let depthBenchNote = benchmark ? `标杆 ${benchmark.name}：人均 ${fmtWan(bmPerCapita!)}，活跃 ${benchmark.avg_active_days.toFixed(1)} 天` : undefined
  if (roleMatrix && domRole) {
    const sameRole = roleMatrix
      .filter(c => c.role === domRole[0] && c.project_id !== projectId && c.headcount >= 3)
      .sort((a, b) => b.per_capita - a.per_capita)[0]
    if (sameRole) {
      depthBenchNote = `同序列(${domRole[0]})标杆：人均 ${fmtWan(sameRole.per_capita)}，活跃 ${sameRole.avg_active_days.toFixed(1)} 天`
    }
  }
  const depthSeverity: EvidenceStep['severity'] =
    project.avg_active_days < 12 ? 'high' : project.avg_active_days < 18 ? 'medium' : 'none'

  // Step 4 人在流失吗
  const to = project.recent_turnover
  const attritionSeverity: EvidenceStep['severity'] =
    to.power_user_exits > 0 ? 'high' : to.total_exits > 3 ? 'medium' : 'none'

  // Step 5 组织扛得住吗（薪酬位档偏低 + 同期入职队列 留任意愿，仅护栏口径）
  const invertedCR = pTalents.filter(x => x.tier === 'power' && x.cr_value < 0.9)
  const stay = project.engagement_dimensions?.stay_intention ?? null
  const orgSeverity: EvidenceStep['severity'] =
    invertedCR.length > 0 && stay != null && stay < 60 ? 'high'
    : invertedCR.length > 0 || (stay != null && stay < 60) ? 'medium' : 'none'
  const modelChart = buildModelChart(project, benchmark)
  const peopleChart = buildPeopleChart(pTalents, benchmarkTalents)
  const depthChart = buildDepthChart(projectId, benchmark?.id || null, trend)
  const attritionChart = buildAttritionChart(project)
  const orgChart = buildOrgChart(projectId, benchmark?.id || null, roleMatrix)

  const steps: EvidenceStep[] = [
    {
      key: 'model',
      title: '用对模型了吗',
      chart: modelChart,
      facts: [
        { label: '高价模型(Opus)成本占比', value: pct(expShare), benchmark: bmExpShare != null ? `标杆 ${pct(bmExpShare)}` : undefined },
        { label: '主导岗位', value: domRole ? `${domRole[0]} ${domRole[1]}人` : '--' },
      ],
      finding: domRole && !TECH_ROLES.has(domRole[0]) && expShare > 0.4
        ? `团队以${domRole[0]}为主，但 ${pct(expShare)} 的 AI 成本花在高价模型上`
        : `模型结构与岗位构成基本匹配`,
      severity: modelSeverity,
    },
    {
      key: 'people',
      title: '用对人了吗',
      chart: peopleChart,
      facts: [
        { label: '重度使用者', value: `${powerUsers.length} 人` },
        { label: '重度使用者 覆盖的岗位', value: [...powerRoles.keys()].join('、') || '无' },
        { label: '未覆盖的主要岗位(≥5人)', value: uncoveredRoles.join('、') || '无' },
      ],
      finding: uncoveredRoles.length > 0
        ? `${uncoveredRoles.join('、')} 等主要岗位没有任何深度使用者`
        : `深度使用者覆盖了主要岗位`,
      severity: peopleSeverity,
    },
    {
      key: 'depth',
      title: '用得够深吗',
      chart: depthChart,
      facts: [
        { label: '人均 AI 成本', value: fmtWan(perCapita), benchmark: depthBenchNote },
        { label: '月均活跃天数', value: project.avg_active_days.toFixed(1) + ' 天' },
        { label: '覆盖率', value: project.ai_penetration.toFixed(1) + '%' },
      ],
      finding: project.avg_active_days < 12
        ? `活跃天数 ${project.avg_active_days.toFixed(1)} 天，远低于深度使用水位（≥20天）——覆盖了但没用进日常`
        : `使用深度${project.avg_active_days >= 18 ? '健康' : '中等'}`,
      severity: depthSeverity,
    },
    {
      key: 'attrition',
      title: '人在流失吗',
      chart: attritionChart,
      facts: [
        { label: '近期离职', value: `${to.total_exits} 人（被动 ${to.involuntary_exits}）` },
        { label: '重度使用者流失', value: `${to.power_user_exits} 人` },
      ],
      finding: to.power_user_exits > 0
        ? `已有 ${to.power_user_exits} 名深度使用者离开，AI 经验在外流`
        : to.total_exits > 3 ? `近期流失 ${to.total_exits} 人，需关注` : `人员相对稳定`,
      severity: attritionSeverity,
    },
    {
      key: 'org',
      title: '组织扛得住吗',
      chart: orgChart,
      facts: [
        { label: '重度使用者中 CR<0.9（薪酬倒挂）', value: `${invertedCR.length} 人` },
        { label: '同期入职队列 留任意愿（团队级背景，非个人结论）', value: stay != null ? stay.toFixed(0) : '无调研数据' },
      ],
      finding: invertedCR.length > 0
        ? `${invertedCR.length} 名核心使用者薪酬低于市场水位${stay != null && stay < 60 ? '，且所在群体留任意愿偏低' : ''}`
        : `未发现明显的薪酬倒挂信号`,
      severity: orgSeverity,
    },
  ]

  return { project, benchmark, benchmarkQuality, steps }
}

// ───────────────────────── ①章 判断页输入（核心指标 + 健康度原料） ─────────────────────────

export interface VerdictInputs {
  northStar: {
    productivity: number
    aiToLaborRatio: number
    monthlyProfit: number
    criticalTalentCount: number
  }
  moneyDim: {   // 💰 钱花得值吗
    amplifierConfirmed: number
    amplifierUnproven: number
    underperforming: number
    underperformingAiCost: number
    trendUpCount: number
    costCenterCount: number
  }
  efficiencyDim: {  // 🚀 效率撬动了吗
    powerCount: number
    powerCostShare: number      // 重度使用者 占个人 AI 成本比
    deepDeptCount: number       // amplifier + high_potential 数
    lowActiveProjects: number   // 活跃 < 12 天的项目数
  }
  peopleDim: {  // 🛡️ 人扛得住吗
    criticalTalent: CriticalTalent[]
    totalPowerExits: number
    lowStayProjects: number
  }
}

export function buildVerdictInputs(
  projects: ProjectWithMetrics[],
  trend: MonthlyRecord[],
  talents: TalentRecord[]
): VerdictInputs {
  const pnlProjects = projects.filter(project => !project.is_cost_center)
  const matrix = getLeverageMatrix(projects, trend)
  const critical = getCriticalTalentList(talents, projects)

  const totalLabor = pnlProjects.reduce((s, p) => s + p.labor_cost, 0)
  const totalAi = pnlProjects.reduce((s, p) => s + p.ai_cost, 0)
  const totalProfit = pnlProjects.reduce((s, p) => s + p.profit, 0)

  const powerUsers = talents.filter(t => t.tier === 'power')
  const totalTalentCost = talents.reduce((s, t) => s + (t.ai_cost_cny ?? 0), 0)
  const powerCost = powerUsers.reduce((s, t) => s + (t.ai_cost_cny ?? 0), 0)

  return {
    northStar: {
      productivity: totalProfit / Math.max(totalLabor + totalAi, 1),
      aiToLaborRatio: totalAi / Math.max(totalLabor, 1),
      monthlyProfit: totalProfit,
      criticalTalentCount: critical.length,
    },
    moneyDim: {
      amplifierConfirmed: matrix.counts.amplifier_confirmed,
      amplifierUnproven: matrix.counts.amplifier_unproven,
      underperforming: matrix.counts.underperforming,
      underperformingAiCost: projects
        .filter(p => !p.is_cost_center && p.quadrant === 'underperforming')
        .reduce((s, p) => s + p.ai_cost, 0),
      trendUpCount: matrix.points.filter(p => p.verdict !== 'support' && p.trend === 'up').length,
      costCenterCount: projects.filter(project => project.is_cost_center).length,
    },
    efficiencyDim: {
      powerCount: powerUsers.length,
      powerCostShare: totalTalentCost > 0 ? powerCost / totalTalentCost : 0,
      deepDeptCount: matrix.counts.amplifier_confirmed + matrix.counts.amplifier_unproven,
      lowActiveProjects: pnlProjects.filter(p => p.avg_active_days < 12).length,
    },
    peopleDim: {
      criticalTalent: critical,
      totalPowerExits: projects.reduce((s, p) => s + p.recent_turnover.power_user_exits, 0),
      lowStayProjects: projects.filter(p =>
        p.engagement_dimensions?.stay_intention != null && p.engagement_dimensions.stay_intention < 60
      ).length,
    },
  }
}
