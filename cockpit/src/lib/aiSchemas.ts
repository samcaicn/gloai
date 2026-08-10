/**
 * aiSchemas.ts — 四种 AI 模式的响应类型 + 服务端校验归一（v2.1 §9.4）
 * 原则：结构化模式校验失败必须归一为可渲染默认对象；raw text fallback 仅限 drilldown。
 */

export type AiMode = 'verdict' | 'attribution' | 'decision' | 'drilldown'

export type Grade = 'A' | 'B' | 'C'
export type Severity = 'high' | 'medium' | 'low' | 'none'
export type DimensionInsight = { key: string; label: string; judgment: string }

// ── verdict ──
export interface VerdictFinding {
  severity: Severity
  title: string          // 一句尖锐结论
  evidence: string[]     // 2-3 个证据数字句
  target_chapter: 'divergence' | 'attribution' | 'decision' // 严格三选一：跨项目分化/单项目诊断/生成方案
  target_project_id?: string // 单项目诊断和方案承接时提供
  target_cause?: string      // target_chapter=decision 时提供，用于方案页承接根因
}
export interface VerdictResponse {
  grades: { money: Grade; efficiency: Grade; people: Grade }
  overall: string        // 总评语 1-2 句
  dimension_insights: DimensionInsight[]
  findings: VerdictFinding[]
}

// ── attribution ──
export interface AttributionStepJudgment {
  key: 'model' | 'people' | 'depth' | 'attrition' | 'org'
  judgment: string       // AI 研判 1-2 句
  severity: Severity
}
export interface AttributionResponse {
  steps: AttributionStepJudgment[]
  root_cause: string     // 交叉综合后的根因 1-2 句
  confidence: 'high' | 'medium' | 'low'
}

// ── decision ──
export interface ActionCard {
  action: string
  scope: string          // 覆盖范围
  amount: string         // 预计金额/预期收益
  benchmark: string      // 参考标杆项目
  validation: string     // 怎么验证生效
  risk: string
  guardrail: string      // 护栏提示（无则空串）
}
export interface DecisionResponse {
  summary: string
  action_cards: ActionCard[]
  guardrail_hits: { project_id: string; count: number; note: string }[]
  simulation?: string    // 预设推演文本（可选）
  simulation_dimensions: DimensionInsight[]
}

// ── drilldown ──
export interface DrilldownResponse {
  answer: string
}

const GRADES: Grade[] = ['A', 'B', 'C']
const SEVERITIES: Severity[] = ['high', 'medium', 'low', 'none']
const STEP_KEYS = ['model', 'people', 'depth', 'attrition', 'org'] as const

const str = (v: unknown, fb = ''): string => (typeof v === 'string' ? v : fb)
const arr = <T,>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : [])
const dimensionInsights = (v: unknown): DimensionInsight[] => arr<Record<string, unknown>>(v)
  .map(item => ({
    key: str(item.key),
    label: str(item.label),
    judgment: str(item.judgment),
  }))
  .filter(item => item.key && item.label && item.judgment)

export function normalizeVerdict(raw: unknown): VerdictResponse {
  const r = (raw ?? {}) as Record<string, unknown>
  const g = (r.grades ?? {}) as Record<string, unknown>
  const grade = (v: unknown): Grade => (GRADES.includes(v as Grade) ? (v as Grade) : 'B')
  const sev = (v: unknown): Severity => (SEVERITIES.includes(v as Severity) ? (v as Severity) : 'medium')
  return {
    grades: { money: grade(g.money), efficiency: grade(g.efficiency), people: grade(g.people) },
    overall: str(r.overall, 'AI 总评生成失败，请点击重新分析。'),
    dimension_insights: dimensionInsights(r.dimension_insights),
    findings: arr<Record<string, unknown>>(r.findings).slice(0, 5).map(f => ({
      severity: sev(f.severity),
      title: str(f.title, '（信号解析失败）'),
      evidence: arr<string>(f.evidence).filter(e => typeof e === 'string').slice(0, 3),
      target_chapter: (['divergence', 'attribution', 'decision'].includes(f.target_chapter as string)
        ? f.target_chapter : 'divergence') as VerdictFinding['target_chapter'],
      target_project_id: typeof f.target_project_id === 'string' ? f.target_project_id : undefined,
      target_cause: typeof f.target_cause === 'string' ? f.target_cause : undefined,
    })),
  }
}

export function normalizeAttribution(raw: unknown): AttributionResponse {
  const r = (raw ?? {}) as Record<string, unknown>
  const sev = (v: unknown): Severity => (SEVERITIES.includes(v as Severity) ? (v as Severity) : 'medium')
  const stepsIn = arr<Record<string, unknown>>(r.steps)
  const steps: AttributionStepJudgment[] = STEP_KEYS.map(key => {
    const found = stepsIn.find(s => s.key === key)
    return {
      key,
      judgment: str(found?.judgment, 'AI 研判暂不可用，参考左侧系统计算事实。'),
      severity: sev(found?.severity),
    }
  })
  return {
    steps,
    root_cause: str(r.root_cause, 'AI 根因综合暂不可用；请基于上方五步事实自行研判。'),
    confidence: (['high', 'medium', 'low'].includes(r.confidence as string)
      ? r.confidence : 'low') as AttributionResponse['confidence'],
  }
}

export function normalizeDecision(raw: unknown): DecisionResponse {
  const r = (raw ?? {}) as Record<string, unknown>
  return {
    summary: str(r.summary, '方案生成失败，请重试。'),
    action_cards: arr<Record<string, unknown>>(r.action_cards).slice(0, 4).map(c => ({
      action: str(c.action, '--'),
      scope: str(c.scope, '--'),
      amount: str(c.amount, '--'),
      benchmark: str(c.benchmark, '--'),
      validation: str(c.validation, '--'),
      risk: str(c.risk, '--'),
      guardrail: str(c.guardrail),
    })),
    guardrail_hits: arr<Record<string, unknown>>(r.guardrail_hits).map(h => ({
      project_id: str(h.project_id),
      count: typeof h.count === 'number' ? h.count : 0,
      note: str(h.note),
    })),
    simulation: typeof r.simulation === 'string' ? r.simulation : undefined,
    simulation_dimensions: dimensionInsights(r.simulation_dimensions),
  }
}

export function normalizeDrilldown(raw: unknown, fallbackText: string): DrilldownResponse {
  const r = (raw ?? {}) as Record<string, unknown>
  return { answer: str(r.answer, fallbackText) }
}

/** 从 AI 文本提取 JSON（容忍 code fence / 前后缀文字 / 字符串内裸换行 / 尾逗号） */
export function extractJson(text: string): unknown {
  let s = text.trim().replace(/^```(?:json)?\s*\n?/i, '').replace(/\n?```\s*$/g, '').trim()
  const m = s.match(/\{[\s\S]*\}/)
  if (m) s = m[0]
  try { return JSON.parse(s) } catch { /* 渐进修复 */ }
  // 修复1：字符串内裸换行（JSON 非法）→ 空格；结构性换行变空格无副作用
  let repaired = s.replace(/\r?\n/g, ' ')
  // 修复2：尾逗号
  repaired = repaired.replace(/,\s*([}\]])/g, '$1')
  try { return JSON.parse(repaired) } catch { /* 修复3 */ }
  // 修复3：AI 在字符串内部裸用 ASCII " 引号（破坏 JSON 结构）
  // 启发式：" 不在合法 JSON 边界（前: , : { [，后: , } ] : 空格）→ 视为内部引号转义
  // 第一遍：CJK + 标点 + 数字 + 字母 之间的 " 都转义
  repaired = repaired.replace(
    /(?<=[一-龥，。；：、！？％+\-\d\w%）)])"(?=[一-龥，。；：、！？％+\-\d\w%（(])/g,
    '\\"'
  )
  try { return JSON.parse(repaired) } catch { /* 修复4 */ }
  // 修复4：兜底——找出所有 " 后面紧接非 JSON 边界字符的位置全部转义
  repaired = repaired.replace(/(?<![\\,:\[\{\s])"(?![,\}\]\s:])/g, '\\"')
  try { return JSON.parse(repaired) } catch { return null }
}
