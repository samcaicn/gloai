'use client'

import dynamic from 'next/dynamic'
import { formatWan, formatPercent, formatRatio, formatProductivity } from '@/lib/format'
import { ProjectWithMetrics } from '@/lib/types'

const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })

export interface ReasoningStep {
  title: string
  content: string
  data_points?: { label: string; value: string; trend?: 'up' | 'down' | 'neutral' }[]
  chart_type?: 'comparison_bar' | 'risk_gauge' | 'action_list'
  chart_data?: Record<string, unknown>
}

interface ReasoningStepsProps {
  steps: ReasoningStep[]
  relatedProjects?: ProjectWithMetrics[]
}

const stepLabels = ['01', '02', '03', '04', '05']
const stepColors = ['border-blue-500/30', 'border-green-500/30', 'border-amber-500/30', 'border-purple-500/30', 'border-red-500/30']

export function ReasoningSteps({ steps, relatedProjects }: ReasoningStepsProps) {
  return (
    <div className="space-y-4">
      {steps.map((step, index) => (
        <div
          key={index}
          className={`rounded-lg border ${stepColors[index % stepColors.length]} bg-white/80 p-4`}
        >
	          {/* Step header */}
	          <div className="mb-3 flex items-center gap-2">
	            <span className="rounded border border-zinc-200 px-1.5 py-0.5 text-[10px] text-slate-500">{stepLabels[index % stepLabels.length]}</span>
	            <span className="text-sm font-semibold text-slate-900">Step {index + 1}: {step.title}</span>
	          </div>

          {/* Content */}
          <p className="text-sm leading-relaxed text-slate-700">{step.content}</p>

          {/* Data points as KPI row */}
          {step.data_points && step.data_points.length > 0 && (
            <div className="mt-3 grid grid-cols-2 gap-2 lg:grid-cols-4">
              {step.data_points.map((dp, i) => (
                <div key={i} className="rounded-md border border-zinc-200/70 bg-white px-3 py-2">
                  <div className="text-[11px] text-slate-500">{dp.label}</div>
                  <div className={`text-sm font-bold ${dp.trend === 'up' ? 'text-green-400' : dp.trend === 'down' ? 'text-red-400' : 'text-slate-900'}`}>
                    {dp.value}
                    {dp.trend === 'up' && ' ↑'}
                    {dp.trend === 'down' && ' ↓'}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Comparison chart for relevant projects */}
          {step.chart_type === 'comparison_bar' && relatedProjects && relatedProjects.length > 0 && (
            <div className="mt-3">
              <ReactECharts
                option={{
                  backgroundColor: 'transparent',
                  grid: { top: 8, right: 10, bottom: 24, left: 80 },
                  xAxis: { type: 'value', axisLabel: { color: '#a1a1aa', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(63,63,70,0.45)' } }, axisLine: { show: false } },
                  yAxis: {
                    type: 'category',
                    data: relatedProjects.slice(0, 5).map(p => p.name),
                    axisLabel: { color: '#a1a1aa', fontSize: 10 },
                    axisLine: { lineStyle: { color: '#3f3f46' } },
                  },
                  series: [{
                    type: 'bar',
                    data: relatedProjects.slice(0, 5).map(p => ({
                      value: p.productivity,
                      itemStyle: { color: p.quadrant === 'amplifier' ? '#22c55e' : p.quadrant === 'underperforming' ? '#ef4444' : p.quadrant === 'high_potential' ? '#3b82f6' : '#71717a' },
                    })),
                    barWidth: '55%',
                    label: { show: true, position: 'right', color: '#a1a1aa', fontSize: 10, formatter: (p: {value: number}) => formatProductivity(p.value) },
                  }],
                }}
                style={{ height: 140 }}
              />
            </div>
          )}

          {/* Risk gauge style */}
          {step.chart_type === 'risk_gauge' && relatedProjects && (
            <div className="mt-3 flex gap-3">
              {relatedProjects.slice(0, 4).map(p => {
                const riskLevel = p.recent_turnover.power_user_exits > 0 ? 'high' : p.recent_turnover.total_exits > 3 ? 'medium' : 'low'
                return (
                  <div key={p.id} className="flex-1 rounded-md border border-zinc-200 bg-white p-2 text-center">
                    <div className={`text-xs font-bold ${riskLevel === 'high' ? 'text-red-400' : riskLevel === 'medium' ? 'text-amber-400' : 'text-green-400'}`}>
	                      {riskLevel === 'high' ? '高' : riskLevel === 'medium' ? '中' : '低'}
                    </div>
                    <div className="mt-1 truncate text-[10px] text-slate-500">{p.name}</div>
                  </div>
                )
              })}
            </div>
          )}

          {/* Action list */}
          {step.chart_type === 'action_list' && step.data_points && (
            <div className="mt-3 space-y-2">
              {step.data_points.map((dp, i) => (
                <div key={i} className="flex items-start gap-2 rounded-md border border-zinc-200 bg-white px-3 py-2">
                  <span className="mt-0.5 text-xs text-blue-400">{i + 1}.</span>
                  <div>
                    <div className="text-sm text-slate-800">{dp.label}</div>
                    <div className="text-xs text-slate-500">{dp.value}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

// Helper: build structured reasoning from project data (for fallback when AI doesn't return structured format)
export function buildLocalReasoning(
  scenario: string,
  projects: ProjectWithMetrics[],
  preferredProjectId?: string
): { steps: ReasoningStep[]; relatedProjects: ProjectWithMetrics[] } {
  const underperforming = projects.filter(p => p.quadrant === 'underperforming').sort((a, b) => b.ai_cost - a.ai_cost)
  const highPotential = projects.filter(p => p.quadrant === 'high_potential').sort((a, b) => b.productivity - a.productivity)
  const preferredProject = preferredProjectId ? projects.find(p => p.id === preferredProjectId) : undefined

  if (scenario.includes('待改善') || scenario.includes('预算优化')) {
    const target = preferredProject || underperforming[0]
    const relatedProjects = preferredProject
      ? [preferredProject, ...underperforming.filter(p => p.id !== preferredProject.id)].slice(0, 5)
      : underperforming.slice(0, 5)
    return {
      relatedProjects,
      steps: [
        {
          title: '锁定分析对象',
          content: `当前有 ${underperforming.length} 个项目处于待改善（AI 投入高于中位数，但人效低于中位数）。优先分析 AI 投入最大的项目。`,
          data_points: relatedProjects.slice(0, 3).map(p => ({ label: p.name, value: `人效 ${formatProductivity(p.productivity)}`, trend: 'down' as const })),
          chart_type: 'comparison_bar',
        },
        {
          title: '诊断当前状态',
          content: target ? `${target.name} 的 AI 投入强度为 ${formatRatio(target.ai_intensity)}，但人效仅 ${formatProductivity(target.productivity)}。团队 ${target.headcount} 人，AI 覆盖率 ${formatPercent(target.ai_penetration)}，月均活跃 ${target.avg_active_days.toFixed(1)} 天。` : '暂无数据',
          data_points: target ? [
            { label: 'AI 月成本', value: formatWan(target.ai_cost) },
            { label: '人均 AI', value: formatWan(target.ai_cost / Math.max(target.headcount, 1)) },
            { label: '重度使用者', value: `${target.power_user_profile.count} 人` },
            { label: '活跃天数', value: `${target.avg_active_days.toFixed(0)} 天` },
          ] : [],
        },
        {
          title: '识别关键约束',
          content: `需考虑人才保护约束：重度使用者 ${target?.power_user_profile.count || 0} 人不能流失，近期已离职 ${target?.recent_turnover.total_exits || 0} 人。敬业度"职业发展"维度 ${target?.engagement_dimensions?.career_development ?? '--'} 分。`,
          chart_type: 'risk_gauge',
        },
        {
          title: '建议方案',
          content: '基于以上诊断，建议采取以下措施提升 AI 使用效果：',
          chart_type: 'action_list',
          data_points: [
            { label: '优化模型选型', value: '根据岗位需求匹配模型，避免高价模型用在低复杂度任务' },
            { label: '提升使用深度', value: '从覆盖率导向转为活跃度导向，目标：人均活跃 ≥15 天/月' },
            { label: '跨部门方法萃取', value: '从有效区标杆项目提取使用经验，组织赋能分享' },
          ],
        },
      ],
    }
  }

  // Default: high potential scenario
  const target = preferredProject || highPotential[0]
  const relatedProjects = preferredProject
    ? [preferredProject, ...highPotential.filter(p => p.id !== preferredProject.id)].slice(0, 5)
    : highPotential.slice(0, 5)
  return {
    relatedProjects,
    steps: [
      {
        title: '锁定分析对象',
        content: `${highPotential.length} 个项目位于待加码（人效高但 AI 使用普及度低），加码 AI 投入预期可获得高回报。`,
        data_points: relatedProjects.slice(0, 3).map(p => ({ label: p.name, value: `人效 ${formatProductivity(p.productivity)}`, trend: 'up' as const })),
        chart_type: 'comparison_bar',
      },
      {
        title: '诊断当前状态',
        content: target ? `${target.name} 人效 ${formatProductivity(target.productivity)}（优秀），但 AI 投入强度仅 ${formatRatio(target.ai_intensity)}。重度使用者仅 ${target.power_user_profile.count} 人，存在显著的 AI 赋能空间。` : '暂无数据',
        data_points: target ? [
          { label: '当前人效', value: formatProductivity(target.productivity), trend: 'up' },
          { label: 'AI 投入强度', value: formatRatio(target.ai_intensity), trend: 'down' },
          { label: '覆盖率', value: formatPercent(target.ai_penetration) },
          { label: '重度使用者 占比', value: formatPercent(target.power_user_ratio) },
        ] : [],
      },
      {
        title: '识别加码条件',
        content: `该项目业务基本面健康（利润 ${target ? formatWan(target.profit) : '--'}/月），团队稳定性尚可（离职 ${target?.recent_turnover.total_exits || 0} 人），具备加码 AI 的组织基础。`,
        chart_type: 'risk_gauge',
      },
      {
        title: '建议加码方案',
        content: '建议优先投入以下方向：',
        chart_type: 'action_list',
        data_points: [
          { label: '增加 AI 工具预算', value: `当前人均 ${target ? formatWan(target.ai_cost / Math.max(target.headcount, 1)) : '--'}/月，建议提升至有效区标杆水平` },
          { label: '重点岗位 AI 赋能', value: `优先覆盖 ${target?.power_user_profile.top_roles.join('、') || '核心岗位'}，提升活跃天数` },
          { label: '设定效果观测点', value: '3 个月后对比人效变化，验证 AI 投入是否有效放大产出' },
        ],
      },
    ],
  }
}
