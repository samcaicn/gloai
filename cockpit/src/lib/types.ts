export interface Project {
  id: string
  name: string
  type: '自研工作室' | '平台' | '职能'
  headcount: number
  labor_cost: number
  ai_cost: number
  ai_penetration: number
  power_user_ratio: number
  revenue: number
  profit: number
  engagement_score: number | null
  role_distribution: Record<string, number>
  ai_model_mix: Record<string, number>
  avg_active_days: number
  ai_platform_count_avg: number
  recent_turnover: {
    total_exits: number
    power_user_exits: number
    voluntary_exits: number
    involuntary_exits: number
  }
  avg_annual_salary: number
  salary_cost_ratio: number
  engagement_dimensions: {
    overall: number | null
    career_development: number | null
    compensation_recognition: number | null
    management_leadership: number | null
    work_life_balance: number | null
    stay_intention: number | null
  } | null
  power_user_profile: {
    count: number
    top_roles: string[]
    avg_ai_cost: number
    avg_cr: number
  }
  is_cost_center?: boolean
  revenue_is_simulated: boolean
  profit_is_simulated: boolean
}

export interface MonthlyRecord {
  project_id: string
  month: string
  labor_cost: number
  ai_cost: number
  headcount: number
  revenue: number
  profit?: number
  exits_count: number
  productivity: number
}

export interface TalentRecord {
  id: string
  project_id: string
  tier: 'power' | 'regular' | 'light'
  cr_value: number
  risk_level: 'high' | 'medium' | 'low'
  ai_cost_ratio: number
  role: string | null
  level: string | null
  active_days: number
  primary_model: string | null
  tenure_years: number
  // 第三轮预处理新增（可选，缺失时相关分析降级）
  ai_cost_cny?: number          // 个人月 AI 成本（已缩放）
  ai_cost_dept_share?: number   // 占所属部门 AI 成本比例 0-1
  cr_source?: 'real' | 'proxy'  // CR 来源：真实 / 同职级中位数代理
}

// 序列×部门矩阵（第三轮预处理新增文件 role_dept_matrix.json）
export interface RoleDeptCell {
  role: string                  // 通用序列名（已脱敏）
  project_id: string
  headcount: number
  ai_cost: number               // 已缩放
  per_capita: number
  coverage_rate: number         // 0-1，同部门同序列口径
  avg_active_days: number
  model_mix?: Record<string, number>  // 该格的模型成本占比
  sample_size: number
}

export type Quadrant = 'amplifier' | 'underperforming' | 'high_potential' | 'low_base' | 'support'

export interface ProjectWithMetrics extends Project {
  productivity: number
  ai_intensity: number
  quadrant: Quadrant
}

export interface CompanySummary {
  total_headcount: number
  total_labor_cost: number
  total_ai_cost: number
  total_revenue: number
  total_profit: number
  ai_to_labor_ratio: number
  avg_productivity: number
  project_count: number
  quadrant_distribution: Record<Quadrant, number>
}

export interface TalentRiskSummary {
  project_id: string
  total: number
  power_users: number
  high_risk_count: number
  high_risk_profiles: TalentRecord[]
}

export interface ChatRequest {
  message: string
  page: 'overview' | 'signal' | 'decision' | 'overview_auto_diagnosis'
  selected_project_id?: string
  client_context?: string
}

export interface ChatResponse {
  answer: string
  highlights?: string[]
  decision_card?: {
    title: string
    expected_saving: string
    productivity_delta: string
    actions: { target: string; action: string; impact: string }[]
    talent_guards: { target: string; role: string; reason: string }[]
    evidence: string[]
    visual_changes?: { project_id: string; change_type: 'shrink' | 'grow' | 'highlight_risk' }[]
  }
  warning?: {
    severity: 'high' | 'medium'
    title: string
    message: string
    affected_projects: string[]
    affected_talent_count: number
    recommendation: string
  }
}
