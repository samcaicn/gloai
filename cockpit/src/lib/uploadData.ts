'use client'

import * as XLSX from 'xlsx'
import { MonthlyRecord, Project, TalentRecord } from './types'

export const UPLOADED_DATASET_KEY = 'roi-war-room.uploaded-dataset.v1'
export const UPLOADED_DATASET_EVENT = 'roi-war-room:uploaded-dataset-changed'

export interface UploadedDataset {
  sourceName: string
  uploadedAt: string
  projects: Project[]
  monthlyTrend: MonthlyRecord[]
  talentRisk: TalentRecord[]
}

export interface UploadFileSummary {
  name: string
  rows: number
  cols: number
  columns: string[]
  detectedType: DetectedType
  status: 'ready' | 'warning' | 'error'
  message: string
}

export interface UploadParseResult {
  dataset: UploadedDataset | null
  summaries: UploadFileSummary[]
  errors: string[]
}

type Row = Record<string, unknown>
type DetectedType = 'projects' | 'monthlyTrend' | 'talentRisk' | 'humanCost' | 'businessOutput' | 'aiUsage' | 'unknown'
type ParsedParts = Partial<UploadedDataset> & {
  humanCost?: Row[]
  businessOutput?: Row[]
  aiUsage?: Row[]
}

function toNumber(value: unknown, fallback = 0): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number(value.replace(/[,，%]/g, '').trim())
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

function toStringValue(value: unknown, fallback = ''): string {
  if (value === null || value === undefined) return fallback
  return String(value).trim() || fallback
}

function toNullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  return toNumber(value, 0)
}

function toRecord(value: unknown): Record<string, number> {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, toNumber(item)]))
  }
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return toRecord(parsed)
    } catch {
      return {}
    }
  }
  return {}
}

function toStringArray(value: unknown): string[] {
  if (Array.isArray(value)) return value.map((item) => toStringValue(item)).filter(Boolean)
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) return toStringArray(parsed)
    } catch {
      return value.split(/[,，/]/).map((item) => item.trim()).filter(Boolean)
    }
  }
  return []
}

function getValue(row: Row, aliases: string[]): unknown {
  for (const alias of aliases) {
    if (row[alias] !== undefined && row[alias] !== null && row[alias] !== '') return row[alias]
  }
  return undefined
}

function normalizeProjectType(value: unknown): Project['type'] {
  const type = toStringValue(value, '自研工作室')
  if (type === '平台' || type === '职能') return type
  return '自研工作室'
}

function detectRowsType(rows: Row[], filename: string): DetectedType {
  const lowerName = filename.toLowerCase()
  if (lowerName.includes('业务产出') || lowerName.includes('business') || lowerName.includes('收入') || lowerName.includes('利润')) return 'businessOutput'
  if (lowerName.includes('人力成本') || lowerName.includes('human') || lowerName.includes('labor') || lowerName.includes('薪酬')) return 'humanCost'
  if (lowerName.includes('ai使用') || lowerName.includes('ai 使用') || lowerName.includes('aiusage') || lowerName.includes('ai_usage')) return 'aiUsage'
  if (lowerName.includes('monthly') || lowerName.includes('trend') || lowerName.includes('月度') || lowerName.includes('趋势')) return 'monthlyTrend'
  if (lowerName.includes('talent') || lowerName.includes('risk') || lowerName.includes('人才') || lowerName.includes('员工')) return 'talentRisk'
  if (lowerName.includes('project') || lowerName.includes('项目') || lowerName.includes('部门')) return 'projects'

  const keys = new Set(rows.flatMap((row) => Object.keys(row)))
  if (keys.has('项目') && (keys.has('月份') || keys.has('month')) && (keys.has('收入') || keys.has('利润'))) return 'businessOutput'
  if (keys.has('项目') && (keys.has('月人力成本') || keys.has('人数') || keys.has('人均年薪'))) return 'humanCost'
  if (keys.has('项目') && (keys.has('月AI成本') || keys.has('AI覆盖率') || keys.has('Power用户占比'))) return 'aiUsage'
  if (keys.has('project_id') && keys.has('month')) return 'monthlyTrend'
  if (keys.has('project_id') && (keys.has('tier') || keys.has('risk_level') || keys.has('cr_value'))) return 'talentRisk'
  if (keys.has('id') && keys.has('name') && (keys.has('labor_cost') || keys.has('profit'))) return 'projects'
  return 'unknown'
}

function normalizeProject(row: Row, index: number): Project {
  const id = toStringValue(row.id, `P-${String(index + 1).padStart(2, '0')}`)
  const type = toStringValue(row.type, '自研工作室')
  const normalizedType: Project['type'] = type === '平台' || type === '职能' ? type : '自研工作室'
  const roleDistribution = toRecord(row.role_distribution)
  const aiModelMix = toRecord(row.ai_model_mix)
  const totalExits = toNumber(row.total_exits ?? (row.recent_turnover as { total_exits?: unknown } | undefined)?.total_exits)
  const powerUserCount = toNumber(row.power_user_count ?? (row.power_user_profile as { count?: unknown } | undefined)?.count)

  return {
    id,
    name: toStringValue(row.name, `上传项目 ${index + 1}`),
    type: normalizedType,
    headcount: toNumber(row.headcount),
    labor_cost: toNumber(row.labor_cost),
    ai_cost: toNumber(row.ai_cost),
    ai_penetration: toNumber(row.ai_penetration),
    power_user_ratio: toNumber(row.power_user_ratio),
    revenue: toNumber(row.revenue),
    profit: toNumber(row.profit),
    engagement_score: toNullableNumber(row.engagement_score),
    role_distribution: Object.keys(roleDistribution).length > 0 ? roleDistribution : { 其他: toNumber(row.headcount) },
    ai_model_mix: Object.keys(aiModelMix).length > 0 ? aiModelMix : { 其他: 1 },
    avg_active_days: toNumber(row.avg_active_days),
    ai_platform_count_avg: toNumber(row.ai_platform_count_avg),
    recent_turnover: {
      total_exits: totalExits,
      power_user_exits: toNumber(row.power_user_exits ?? (row.recent_turnover as { power_user_exits?: unknown } | undefined)?.power_user_exits),
      voluntary_exits: toNumber(row.voluntary_exits ?? (row.recent_turnover as { voluntary_exits?: unknown } | undefined)?.voluntary_exits),
      involuntary_exits: toNumber(row.involuntary_exits ?? (row.recent_turnover as { involuntary_exits?: unknown } | undefined)?.involuntary_exits),
    },
    avg_annual_salary: toNumber(row.avg_annual_salary),
    salary_cost_ratio: toNumber(row.salary_cost_ratio),
    engagement_dimensions: {
      overall: toNullableNumber(row.overall ?? (row.engagement_dimensions as { overall?: unknown } | undefined)?.overall),
      career_development: toNullableNumber(row.career_development ?? (row.engagement_dimensions as { career_development?: unknown } | undefined)?.career_development),
      compensation_recognition: toNullableNumber(row.compensation_recognition ?? (row.engagement_dimensions as { compensation_recognition?: unknown } | undefined)?.compensation_recognition),
      management_leadership: toNullableNumber(row.management_leadership ?? (row.engagement_dimensions as { management_leadership?: unknown } | undefined)?.management_leadership),
      work_life_balance: toNullableNumber(row.work_life_balance ?? (row.engagement_dimensions as { work_life_balance?: unknown } | undefined)?.work_life_balance),
      stay_intention: toNullableNumber(row.stay_intention ?? (row.engagement_dimensions as { stay_intention?: unknown } | undefined)?.stay_intention),
    },
    power_user_profile: {
      count: powerUserCount,
      top_roles: toStringArray(row.top_roles ?? (row.power_user_profile as { top_roles?: unknown } | undefined)?.top_roles),
      avg_ai_cost: toNumber(row.avg_ai_cost ?? (row.power_user_profile as { avg_ai_cost?: unknown } | undefined)?.avg_ai_cost),
      avg_cr: toNumber(row.avg_cr ?? (row.power_user_profile as { avg_cr?: unknown } | undefined)?.avg_cr),
    },
    revenue_is_simulated: row.revenue_is_simulated === undefined ? false : Boolean(row.revenue_is_simulated),
    profit_is_simulated: row.profit_is_simulated === undefined ? false : Boolean(row.profit_is_simulated),
  }
}

function normalizeMonthly(row: Row): MonthlyRecord {
  const laborCost = toNumber(row.labor_cost)
  const aiCost = toNumber(row.ai_cost)
  const revenue = toNumber(row.revenue)
  return {
    project_id: toStringValue(row.project_id),
    month: toStringValue(row.month),
    labor_cost: laborCost,
    ai_cost: aiCost,
    headcount: toNumber(row.headcount),
    revenue,
    exits_count: toNumber(row.exits_count),
    productivity: toNumber(row.productivity, laborCost + aiCost > 0 ? revenue / (laborCost + aiCost) : 0),
  }
}

function normalizeTalent(row: Row, index: number): TalentRecord {
  const tier = toStringValue(row.tier, 'regular')
  const riskLevel = toStringValue(row.risk_level, 'low')
  return {
    id: toStringValue(row.id, `U-${String(index + 1).padStart(3, '0')}`),
    project_id: toStringValue(row.project_id),
    tier: tier === 'power' || tier === 'light' ? tier : 'regular',
    cr_value: toNumber(row.cr_value, 1),
    risk_level: riskLevel === 'high' || riskLevel === 'medium' ? riskLevel : 'low',
    ai_cost_ratio: toNumber(row.ai_cost_ratio),
    role: row.role === null ? null : toStringValue(row.role, '') || null,
    level: row.level === null ? null : toStringValue(row.level, '') || null,
    active_days: toNumber(row.active_days),
    primary_model: row.primary_model === null ? null : toStringValue(row.primary_model, '') || null,
    tenure_years: toNumber(row.tenure_years),
  }
}

function normalizeRows(type: DetectedType, rows: Row[]): ParsedParts {
  if (type === 'projects') return { projects: rows.map(normalizeProject).filter((row) => row.labor_cost + row.ai_cost > 0 || row.profit > 0) }
  if (type === 'monthlyTrend') return { monthlyTrend: rows.map(normalizeMonthly).filter((row) => row.project_id && row.month) }
  if (type === 'talentRisk') return { talentRisk: rows.map(normalizeTalent).filter((row) => row.project_id) }
  if (type === 'humanCost') return { humanCost: rows.filter((row) => toStringValue(getValue(row, ['项目', 'name', 'project']))) }
  if (type === 'businessOutput') return { businessOutput: rows.filter((row) => toStringValue(getValue(row, ['项目', 'name', 'project']))) }
  if (type === 'aiUsage') return { aiUsage: rows.filter((row) => toStringValue(getValue(row, ['项目', 'name', 'project']))) }
  return {}
}

function rowsFromJson(value: unknown): { typeHint: DetectedType; rows: Row[] }[] {
  if (Array.isArray(value)) return [{ typeHint: 'unknown', rows: value as Row[] }]
  if (!value || typeof value !== 'object') return []
  const objectValue = value as Record<string, unknown>
  const result: { typeHint: DetectedType; rows: Row[] }[] = []
  if (Array.isArray(objectValue.projects)) result.push({ typeHint: 'projects', rows: objectValue.projects as Row[] })
  if (Array.isArray(objectValue.monthlyTrend)) result.push({ typeHint: 'monthlyTrend', rows: objectValue.monthlyTrend as Row[] })
  if (Array.isArray(objectValue.monthly_trend)) result.push({ typeHint: 'monthlyTrend', rows: objectValue.monthly_trend as Row[] })
  if (Array.isArray(objectValue.talentRisk)) result.push({ typeHint: 'talentRisk', rows: objectValue.talentRisk as Row[] })
  if (Array.isArray(objectValue.talent_risk)) result.push({ typeHint: 'talentRisk', rows: objectValue.talent_risk as Row[] })
  if (Array.isArray(objectValue.humanCost)) result.push({ typeHint: 'humanCost', rows: objectValue.humanCost as Row[] })
  if (Array.isArray(objectValue.businessOutput)) result.push({ typeHint: 'businessOutput', rows: objectValue.businessOutput as Row[] })
  if (Array.isArray(objectValue.aiUsage)) result.push({ typeHint: 'aiUsage', rows: objectValue.aiUsage as Row[] })
  return result
}

async function parseFile(file: File): Promise<{ summaries: UploadFileSummary[]; parts: ParsedParts }> {
  const extension = file.name.split('.').pop()?.toLowerCase()
  const summaries: UploadFileSummary[] = []
  const parts: ParsedParts = {}

  if (extension === 'json') {
    const parsed = JSON.parse(await file.text())
    const sections = rowsFromJson(parsed)
    for (const section of sections) {
      const type = section.typeHint === 'unknown' ? detectRowsType(section.rows, file.name) : section.typeHint
      Object.assign(parts, normalizeRows(type, section.rows))
      summaries.push(buildSummary(file.name, section.rows, type))
    }
    if (sections.length === 0) summaries.push(buildSummary(file.name, [], 'unknown', 'error', 'JSON 未包含可识别数组'))
    return { summaries, parts }
  }

  const buffer = await file.arrayBuffer()
  const workbook = XLSX.read(new Uint8Array(buffer), { type: 'array' })
  for (const sheetName of workbook.SheetNames) {
    const rows = XLSX.utils.sheet_to_json(workbook.Sheets[sheetName], { defval: null }) as Row[]
    const type = detectRowsType(rows, `${file.name} ${sheetName}`)
    if (type !== 'unknown') Object.assign(parts, normalizeRows(type, rows))
    summaries.push(buildSummary(`${file.name} / ${sheetName}`, rows, type))
  }

  return { summaries, parts }
}

function buildSummary(
  name: string,
  rows: Row[],
  detectedType: DetectedType,
  status?: UploadFileSummary['status'],
  message?: string
): UploadFileSummary {
  const columns = Object.keys(rows[0] || {})
  const typeLabel =
    detectedType === 'projects' ? '项目数据'
      : detectedType === 'monthlyTrend' ? '月度趋势'
        : detectedType === 'talentRisk' ? '人才风险'
          : detectedType === 'humanCost' ? '人力成本模板'
            : detectedType === 'businessOutput' ? '业务产出模板'
              : detectedType === 'aiUsage' ? 'AI 使用模板'
                : '未识别'
  return {
    name,
    rows: rows.length,
    cols: columns.length,
    columns: columns.slice(0, 5),
    detectedType,
    status: status || (detectedType === 'unknown' ? 'warning' : 'ready'),
    message: message || typeLabel,
  }
}

function projectNameFromRow(row: Row): string {
  return toStringValue(getValue(row, ['项目', 'name', 'project', '项目名称']))
}

function buildDatasetFromTemplates(humanCostRows: Row[], businessRows: Row[], aiRows: Row[]): Pick<UploadedDataset, 'projects' | 'monthlyTrend' | 'talentRisk'> {
  const humanByName = new Map<string, Row>()
  const aiByName = new Map<string, Row>()
  const businessByName = new Map<string, Row[]>()

  for (const row of humanCostRows) {
    const name = projectNameFromRow(row)
    if (name) humanByName.set(name, row)
  }

  for (const row of aiRows) {
    const name = projectNameFromRow(row)
    if (name) aiByName.set(name, row)
  }

  for (const row of businessRows) {
    const name = projectNameFromRow(row)
    if (!name) continue
    businessByName.set(name, [...(businessByName.get(name) || []), row])
  }

  const names = Array.from(new Set([...humanByName.keys(), ...aiByName.keys(), ...businessByName.keys()])).sort((a, b) => a.localeCompare(b, 'zh-CN'))
  const idByName = new Map(names.map((name, index) => [name, `P-${String(index + 1).padStart(2, '0')}`]))

  const projects: Project[] = names.map((name, index) => {
    const human = humanByName.get(name) || {}
    const ai = aiByName.get(name) || {}
    const businessRowsForProject = [...(businessByName.get(name) || [])].sort((a, b) =>
      toStringValue(getValue(a, ['月份', 'month'])).localeCompare(toStringValue(getValue(b, ['月份', 'month'])))
    )
    const latestBusiness = businessRowsForProject[businessRowsForProject.length - 1] || {}
    const headcount = toNumber(getValue(human, ['人数', 'headcount']))
    const laborCost = toNumber(getValue(human, ['月人力成本', 'labor_cost']))
    const aiCost = toNumber(getValue(ai, ['月AI成本', 'ai_cost']))
    const powerUserRatio = toNumber(getValue(ai, ['Power用户占比', 'power_user_ratio']))
    const powerUserCount = Math.round(headcount * powerUserRatio / 100)
    const opus = toNumber(getValue(ai, ['主要模型_Opus占比', 'Claude Opus', 'Opus']))
    const sonnet = toNumber(getValue(ai, ['主要模型_Sonnet占比', 'Claude Sonnet', 'Sonnet']))
    const gpt = toNumber(getValue(ai, ['主要模型_GPT占比', 'GPT']))
    const modelOther = Math.max(0, 1 - opus - sonnet - gpt)

    return {
      id: idByName.get(name) || `P-${String(index + 1).padStart(2, '0')}`,
      name,
      type: normalizeProjectType(getValue(human, ['类型', 'type'])),
      headcount,
      labor_cost: laborCost,
      ai_cost: aiCost,
      ai_penetration: toNumber(getValue(ai, ['AI覆盖率', 'ai_penetration'])),
      power_user_ratio: powerUserRatio,
      revenue: toNumber(getValue(latestBusiness, ['收入', 'revenue'])),
      profit: toNumber(getValue(latestBusiness, ['利润', 'profit'])),
      engagement_score: null,
      role_distribution: {
        技术研发: Math.round(headcount * 0.55),
        产品: Math.round(headcount * 0.12),
        设计: Math.round(headcount * 0.1),
        运营: Math.round(headcount * 0.12),
        其他: Math.max(0, headcount - Math.round(headcount * 0.89)),
      },
      ai_model_mix: {
        'Claude Opus': opus,
        'Claude Sonnet': sonnet,
        GPT: gpt,
        其他: modelOther,
      },
      avg_active_days: toNumber(getValue(ai, ['人均活跃天数', 'avg_active_days'])),
      ai_platform_count_avg: toNumber(getValue(ai, ['人均使用平台数', 'ai_platform_count_avg'])),
      recent_turnover: { total_exits: 0, power_user_exits: 0, voluntary_exits: 0, involuntary_exits: 0 },
      avg_annual_salary: toNumber(getValue(human, ['人均年薪', 'avg_annual_salary'])),
      salary_cost_ratio: toNumber(getValue(human, ['薪酬成本率', 'salary_cost_ratio'])),
      engagement_dimensions: null,
      power_user_profile: {
        count: powerUserCount,
        top_roles: ['技术研发', '产品'],
        avg_ai_cost: powerUserCount > 0 ? aiCost / powerUserCount : 0,
        avg_cr: 1,
      },
      revenue_is_simulated: false,
      profit_is_simulated: false,
    }
  }).filter((project) => project.name && (project.labor_cost + project.ai_cost > 0 || project.revenue + project.profit > 0))

  const monthlyTrend: MonthlyRecord[] = businessRows.map((row) => {
    const name = projectNameFromRow(row)
    const project = projects.find((item) => item.name === name)
    const human = humanByName.get(name) || {}
    const ai = aiByName.get(name) || {}
    const laborCost = toNumber(getValue(human, ['月人力成本', 'labor_cost']))
    const aiCost = toNumber(getValue(ai, ['月AI成本', 'ai_cost']))
    const revenue = toNumber(getValue(row, ['收入', 'revenue']))
    const profit = toNumber(getValue(row, ['利润', 'profit']))
    return {
      project_id: project?.id || idByName.get(name) || '',
      month: toStringValue(getValue(row, ['月份', 'month'])),
      labor_cost: laborCost,
      ai_cost: aiCost,
      headcount: toNumber(getValue(human, ['人数', 'headcount'])),
      revenue,
      exits_count: 0,
      productivity: laborCost + aiCost > 0 ? profit / (laborCost + aiCost) : 0,
    }
  }).filter((row) => row.project_id && row.month)

  return { projects, monthlyTrend, talentRisk: [] }
}

export async function parseUploadedFiles(files: File[]): Promise<UploadParseResult> {
  const summaries: UploadFileSummary[] = []
  const errors: string[] = []
  let projects: Project[] = []
  let monthlyTrend: MonthlyRecord[] = []
  let talentRisk: TalentRecord[] = []
  let humanCostRows: Row[] = []
  let businessRows: Row[] = []
  let aiRows: Row[] = []

  for (const file of files) {
    try {
      const parsed = await parseFile(file)
      summaries.push(...parsed.summaries)
      if (parsed.parts.projects) projects = parsed.parts.projects
      if (parsed.parts.monthlyTrend) monthlyTrend = parsed.parts.monthlyTrend
      if (parsed.parts.talentRisk) talentRisk = parsed.parts.talentRisk
      if (parsed.parts.humanCost) humanCostRows = [...humanCostRows, ...parsed.parts.humanCost]
      if (parsed.parts.businessOutput) businessRows = [...businessRows, ...parsed.parts.businessOutput]
      if (parsed.parts.aiUsage) aiRows = [...aiRows, ...parsed.parts.aiUsage]
    } catch (error) {
      errors.push(`${file.name}: ${(error as Error).message}`)
      summaries.push(buildSummary(file.name, [], 'unknown', 'error', '解析失败'))
    }
  }

  if (projects.length === 0) {
    const synthesized = buildDatasetFromTemplates(humanCostRows, businessRows, aiRows)
    projects = synthesized.projects
    monthlyTrend = synthesized.monthlyTrend
    talentRisk = synthesized.talentRisk
  }

  if (projects.length === 0) {
    return { dataset: null, summaries, errors: [...errors, '请上传模板三表，或上传包含 id/name/labor_cost/profit 的 projects 数据'] }
  }

  const dataset: UploadedDataset = {
    sourceName: files.map((file) => file.name).join(', '),
    uploadedAt: new Date().toISOString(),
    projects,
    monthlyTrend,
    talentRisk,
  }

  return { dataset, summaries, errors }
}

export function saveUploadedDataset(dataset: UploadedDataset) {
  window.localStorage.setItem(UPLOADED_DATASET_KEY, JSON.stringify(dataset))
  window.dispatchEvent(new Event(UPLOADED_DATASET_EVENT))
}

export function readUploadedDataset(): UploadedDataset | null {
  const raw = window.localStorage.getItem(UPLOADED_DATASET_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as UploadedDataset
    return {
      sourceName: parsed.sourceName || '上传数据',
      uploadedAt: parsed.uploadedAt || new Date().toISOString(),
      projects: ((parsed.projects || []) as unknown as Row[]).map(normalizeProject),
      monthlyTrend: ((parsed.monthlyTrend || []) as unknown as Row[]).map(normalizeMonthly),
      talentRisk: ((parsed.talentRisk || []) as unknown as Row[]).map(normalizeTalent),
    }
  } catch {
    window.localStorage.removeItem(UPLOADED_DATASET_KEY)
    return null
  }
}

export function clearUploadedDataset() {
  window.localStorage.removeItem(UPLOADED_DATASET_KEY)
  window.dispatchEvent(new Event(UPLOADED_DATASET_EVENT))
}
