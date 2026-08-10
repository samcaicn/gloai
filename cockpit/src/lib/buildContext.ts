import { ProjectWithMetrics, MonthlyRecord, TalentRecord } from './types'
import { getTalentRiskSummary } from './calculations'
import { formatWan, formatPercent, formatRatio, formatProductivity } from './format'

export function buildOverviewContext(projects: ProjectWithMetrics[]): string {
  const lines = projects.map(p => {
    return `${p.id} ${p.name} | 类型:${p.type} | 人数:${p.headcount} | 投入:${formatWan(p.labor_cost + p.ai_cost)} | 利润:${formatWan(p.profit)} | 人效:${formatProductivity(p.productivity)} | AI投入强度:${formatRatio(p.ai_intensity)} | 象限:${p.quadrant}`
  })

  return `公司共 ${projects.length} 个业务单元。

各项目摘要：
${lines.join('\n')}

象限分布：放大区${projects.filter(p => p.quadrant === 'amplifier').length}个, 待优化区${projects.filter(p => p.quadrant === 'underperforming').length}个, 高潜力区${projects.filter(p => p.quadrant === 'high_potential').length}个, 基础区${projects.filter(p => p.quadrant === 'low_base').length}个`
}

export function buildSignalContext(
  project: ProjectWithMetrics,
  trend: MonthlyRecord[],
  talents: TalentRecord[]
): string {
  const projectTrend = trend.filter(t => t.project_id === project.id)
  const riskSummary = getTalentRiskSummary(project.id, talents)

  const trendStr = projectTrend.map(t =>
    `${t.month}: AI投入${formatWan(t.ai_cost)}, 人效${formatProductivity(t.productivity)}, 人数${t.headcount}, 离职${t.exits_count}`
  ).join('\n')

  const roleStr = Object.entries(project.role_distribution)
    .filter(([, v]) => v > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k}:${v}人`)
    .join(', ')

  const modelStr = Object.entries(project.ai_model_mix)
    .sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k}:${formatRatio(v)}`)
    .join(', ')

  const engStr = project.engagement_dimensions
    ? Object.entries(project.engagement_dimensions)
        .filter(([, v]) => v !== null)
        .map(([k, v]) => `${k}:${v}`)
        .join(', ')
    : '无数据'

  let riskStr = `高风险人才: ${riskSummary.high_risk_count}人`
  if (riskSummary.high_risk_count > 0) {
    riskStr += '\n' + riskSummary.high_risk_profiles.map(t =>
      `  ${t.id} | ${t.role || '未知'} | ${t.level || '未知'} | CR=${t.cr_value} | 活跃${t.active_days}天 | 主用${t.primary_model || '未知'}`
    ).join('\n')
  }

  return `当前项目：${project.name} (${project.id})
类型：${project.type}
象限：${project.quadrant}
人数：${project.headcount}
月人力成本：${formatWan(project.labor_cost)}
月AI成本：${formatWan(project.ai_cost)}
AI投入强度：${formatRatio(project.ai_intensity)}
人效：${formatProductivity(project.productivity)}
AI覆盖率：${formatPercent(project.ai_penetration)}
重度使用者：${project.power_user_profile.count}人 (${project.power_user_profile.top_roles.join('/')})
平均活跃天数：${project.avg_active_days}

岗位分布：${roleStr}
AI模型结构：${modelStr}

近期离职：总${project.recent_turnover.total_exits}人(被动${project.recent_turnover.involuntary_exits}, 重度使用者流失${project.recent_turnover.power_user_exits})
敬业度维度：${engStr}

${riskStr}

12个月趋势：
${trendStr}`
}

export function buildDecisionContext(
  projects: ProjectWithMetrics[],
  talents: TalentRecord[]
): string {
  const projectSummaries = projects.map(p => {
    const risk = getTalentRiskSummary(p.id, talents)
    return `${p.id} ${p.name} | 象限:${p.quadrant} | 人数:${p.headcount} | 投入:${formatWan(p.labor_cost + p.ai_cost)} | 利润:${formatWan(p.profit)} | 人效:${formatProductivity(p.productivity)} | AI投入强度:${formatRatio(p.ai_intensity)} | 重度使用者:${p.power_user_profile.count} | 高风险:${risk.high_risk_count} | 近期离职:${p.recent_turnover.total_exits}`
  })

  const totalCost = projects.reduce((s, p) => s + p.labor_cost + p.ai_cost, 0)

  return `公司共 ${projects.length} 个业务单元，月总投入 ${formatWan(totalCost)}。

各项目概况：
${projectSummaries.join('\n')}

决策约束提醒：
- 高风险人才（tier=power 且 CR<0.9）不应被裁减
- 放大区(amplifier)项目是已验证的 AI 投入有效方向
- 高潜力区(high_potential)项目是最值得加码 AI 的方向
- 待优化区(underperforming)项目 AI 投入未产生效果，可考虑调整`
}
