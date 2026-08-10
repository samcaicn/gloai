'use client'

import dynamic from 'next/dynamic'
import { useEffect, useMemo, useState } from 'react'
import { ChatPanel } from '@/components/ChatPanel'
import { ProjectDashboard } from '@/components/ProjectDashboard'
import { useAppData } from '@/lib/DataProvider'
import { getTalentRiskSummary } from '@/lib/calculations'
import { formatPercent, formatProductivity, formatRatio, formatWan } from '@/lib/format'
import { ProjectWithMetrics, Quadrant } from '@/lib/types'
import { buildSignalContext } from '@/lib/buildContext'

const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })

const quadrantColor: Record<Quadrant, string> = {
  amplifier: '#22c55e',
  underperforming: '#ef4444',
  high_potential: '#3b82f6',
  low_base: '#71717a',
  support: '#94a3b8',
}

const quadrantLabel: Record<Quadrant, string> = {
  amplifier: 'AI 有效区',
  underperforming: '待改善',
  high_potential: '待加码',
  low_base: '基础区',
  support: '支撑部门',
}

type QuadrantPoint = [number, number, string, string, Quadrant]

function median(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid] || 0
}

function topEntries(record: Record<string, number>, limit = 2) {
  return Object.entries(record)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
}

function buildDiagnosis(project: ProjectWithMetrics, medianProductivity: number, medianAiIntensity: number, highRiskCount: number) {
  const topModel = topEntries(project.ai_model_mix, 1)[0]
  const topRole = topEntries(project.role_distribution, 1)[0]
  const productivityGap = medianProductivity > 0 ? project.productivity / medianProductivity - 1 : 0
  const aiGap = medianAiIntensity > 0 ? project.ai_intensity / medianAiIntensity : 0

  if (project.quadrant === 'underperforming') {
    return {
      tone: 'red',
      title: 'AI 投入尚未转化为人效',
      summary: `${project.name} 的 AI 投入强度为中位数 ${aiGap.toFixed(1)} 倍，但人效较中位数 ${formatRatio(productivityGap)}，需要先优化使用结构再扩大投入。`,
      evidences: [
        `AI 月成本 ${formatWan(project.ai_cost)}，AI 投入强度 ${formatRatio(project.ai_intensity)}`,
        topModel ? `${topModel[0]} 占 AI 成本 ${formatRatio(topModel[1])}` : '模型结构暂无明显集中项',
        `重度使用者 ${project.power_user_profile.count} 人，高风险核心人才 ${highRiskCount} 人`,
      ],
      action: '建议进入决策推演：缩减低效 AI 消耗，保留 重度使用者额度，并设置 3 个月人效观察点。',
    }
  }

  if (project.quadrant === 'high_potential') {
    return {
      tone: 'blue',
      title: '业务基本面好，AI 加码空间明确',
      summary: `${project.name} 人效较中位数 ${formatRatio(productivityGap)}，但 AI 投入强度仍低于中位数，适合做小步快跑的 AI 预算加码实验。`,
      evidences: [
        `当前人效 ${formatProductivity(project.productivity)}，利润 ${formatWan(project.profit)}`,
        `AI 覆盖率 ${formatPercent(project.ai_penetration)}，月均活跃 ${project.avg_active_days.toFixed(1)} 天`,
        topRole ? `核心岗位为 ${topRole[0]}，共 ${topRole[1]} 人` : '岗位结构暂无数据',
      ],
      action: '建议优先覆盖高产出岗位，扩大 重度使用者比例，并和有效区标杆做方法迁移。',
    }
  }

  if (project.quadrant === 'amplifier') {
    return {
      tone: 'green',
      title: 'AI 投入正在放大业务产出',
      summary: `${project.name} 同时高于 AI 投入强度和人效中位数，是当前组合里的 AI 转型标杆。`,
      evidences: [
        `人效 ${formatProductivity(project.productivity)}，AI 投入强度 ${formatRatio(project.ai_intensity)}`,
        `重度使用者 ${project.power_user_profile.count} 人，平均 AI 成本 ${formatWan(project.power_user_profile.avg_ai_cost)}`,
        topModel ? `主用模型为 ${topModel[0]}，成本占比 ${formatRatio(topModel[1])}` : '模型结构暂无明显集中项',
      ],
      action: '建议沉淀工作流和岗位用法，作为待改善的训练样板。',
    }
  }

  return {
    tone: 'zinc',
    title: '业务与 AI 投入均处于基础区',
    summary: `${project.name} 当前人效和 AI 投入强度均低于中位数，优先判断业务基本面，再决定是否做 AI 投入。`,
    evidences: [
      `人效 ${formatProductivity(project.productivity)}，AI 投入强度 ${formatRatio(project.ai_intensity)}`,
      `团队 ${project.headcount} 人，AI 覆盖率 ${formatPercent(project.ai_penetration)}`,
      `近期离职 ${project.recent_turnover.total_exits} 人`,
    ],
    action: '建议先锁定核心流程和关键岗位，避免平均摊派 AI 预算。',
  }
}


export default function SignalPage() {
  const { projects, monthlyTrend, talentRisk, isLoading, error, dataSource } = useAppData()
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null)
  const [messages, setMessages] = useState<{ role: 'user' | 'assistant'; content: string }[]>([])
  const [chatLoading, setChatLoading] = useState(false)

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get('id')
    if (id) setSelectedProjectId(id)
  }, [])

  useEffect(() => {
    if (!selectedProjectId && projects[0]) setSelectedProjectId(projects[0].id)
  }, [projects, selectedProjectId])

  const selectedProject = projects.find((project) => project.id === selectedProjectId) || projects[0]
  const selectedTrend = selectedProject
    ? monthlyTrend.filter((record) => record.project_id === selectedProject.id)
    : []

  async function handleSend(message: string) {
    setMessages(prev => [...prev, { role: 'user', content: message }])
    setChatLoading(true)
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message,
          page: 'signal',
          selected_project_id: selectedProjectId,
          client_context: dataSource === 'uploaded' && selectedProject ? buildSignalContext(selectedProject, monthlyTrend, talentRisk) : undefined,
        }),
      })
      const data = await res.json()
      setMessages(prev => [...prev, { role: 'assistant', content: data.answer || '暂无分析结果' }])
    } catch {
      setMessages(prev => [...prev, { role: 'assistant', content: '分析请求失败，请重试。' }])
    }
    setChatLoading(false)
  }

  const medianProductivity = useMemo(() => median(projects.map((project) => project.productivity)), [projects])
  const medianAiIntensity = useMemo(() => median(projects.map((project) => project.ai_intensity)), [projects])
  const riskSummary = selectedProject
    ? getTalentRiskSummary(selectedProject.id, talentRisk)
    : { project_id: '', total: 0, power_users: 0, high_risk_count: 0, high_risk_profiles: [] }
  const diagnosis = selectedProject
    ? buildDiagnosis(selectedProject, medianProductivity, medianAiIntensity, riskSummary.high_risk_count)
    : null

  const quadrantOption = {
    backgroundColor: 'transparent',
    grid: { top: 24, right: 24, bottom: 48, left: 54 },
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(63,63,70,0.45)',
      borderColor: '#3f3f46',
      textStyle: { color: '#fafafa' },
      formatter: (params: unknown) => {
        const data = (params as unknown as { data: QuadrantPoint }).data
        return [`<b>${data[2]}</b>`, `AI 投入强度：${formatRatio(data[0])}（AI成本/人力成本）`, `人效：${formatProductivity(data[1])}`, quadrantLabel[data[4]]].join('<br/>')
      },
    },
    xAxis: {
      name: 'AI投入强度(AI/人力)',
      type: 'log',
      min: 0.005,
      max: Math.min(5, Math.max(...projects.map(p => p.ai_intensity)) * 1.2),
      axisLine: { lineStyle: { color: '#3f3f46' } },
      splitLine: { lineStyle: { color: 'rgba(63,63,70,0.45)' } },
      axisLabel: { color: '#a1a1aa', formatter: (value: number) => formatRatio(value) },
      nameTextStyle: { color: '#a1a1aa' },
    },
    yAxis: {
      name: '人效',
      axisLine: { lineStyle: { color: '#3f3f46' } },
      splitLine: { lineStyle: { color: 'rgba(63,63,70,0.45)' } },
      axisLabel: { color: '#a1a1aa' },
      nameTextStyle: { color: '#a1a1aa' },
    },
    graphic: [
      { type: 'text', right: 36, top: 36, style: { text: 'AI 有效区', fill: 'rgba(34,197,94,0.45)', fontSize: 12, fontWeight: 600 } },
      { type: 'text', right: 36, bottom: 60, style: { text: '待改善', fill: 'rgba(239,68,68,0.45)', fontSize: 12, fontWeight: 600 } },
      { type: 'text', left: 66, top: 36, style: { text: '待加码', fill: 'rgba(59,130,246,0.45)', fontSize: 12, fontWeight: 600 } },
      { type: 'text', left: 66, bottom: 60, style: { text: '基础区', fill: 'rgba(113,113,122,0.55)', fontSize: 12, fontWeight: 600 } },
    ],
    series: [
      {
        type: 'scatter',
        data: projects.map((project) => [
          project.ai_intensity,
          project.productivity,
          project.name,
          project.id,
          project.quadrant,
        ]),
        symbolSize: 18,
        itemStyle: {
          color: (params: unknown) => {
            const data = (params as { data: QuadrantPoint }).data
            return quadrantColor[data[4]]
          },
          opacity: 0.9,
        },
        markLine: {
          symbol: ['none', 'none'],
          lineStyle: { color: '#52525b', type: 'dashed' },
          label: { color: '#a1a1aa' },
          data: [{ xAxis: medianAiIntensity }, { yAxis: medianProductivity }],
        },
        emphasis: {
          scale: 1.35,
          label: { show: true, formatter: '{@[2]}', color: '#fafafa' },
        },
      },
      selectedProject
        ? {
            type: 'scatter',
            silent: true,
            data: [[selectedProject.ai_intensity, selectedProject.productivity, selectedProject.name, selectedProject.id, selectedProject.quadrant]],
            symbolSize: 28,
            itemStyle: {
              color: 'transparent',
              borderColor: '#fafafa',
              borderWidth: 2,
              shadowBlur: 12,
              shadowColor: quadrantColor[selectedProject.quadrant],
            },
            label: { show: true, formatter: selectedProject.name, color: '#fafafa', fontSize: 11, position: 'top' },
          }
        : {},
    ],
  }

  if (error) {
    return <div className="w-full p-8 text-red-700">数据加载失败：{error}</div>
  }

  return (
    <div className="w-full space-y-4 px-8 pb-44 pt-5">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">AI 价值信号</h1>
          <p className="mt-1 text-sm text-slate-600">按 AI 投入强度、人效、岗位和人才护栏解释投入信号；AI 投入强度 = AI 成本 / 人力成本</p>
        </div>
        {selectedProject ? <div className="text-xs text-slate-500">当前：{selectedProject.name}</div> : null}
      </header>

      <section className="grid grid-cols-[40fr_60fr] gap-4">
        <div className="rounded-lg border border-zinc-200/70 bg-white p-4">
	          <div className="mb-3 flex items-center justify-between gap-3">
	            <h2 className="text-sm font-semibold text-slate-900">四象限矩阵</h2>
	            <span className="text-xs text-slate-500">横轴 AI 投入强度 = AI 成本 / 人力成本</span>
	          </div>
          {isLoading ? (
            <div className="h-[560px] animate-pulse rounded bg-slate-200/70" />
          ) : (
            <ReactECharts
              option={quadrantOption}
              style={{ height: 560 }}
              onEvents={{
                click: (params: { data?: unknown }) => {
                  const data = params.data as QuadrantPoint | undefined
                  if (data?.[3]) setSelectedProjectId(data[3])
                },
              }}
            />
          )}
        </div>

        <div className="space-y-4">
          {selectedProject ? (
            <>
	              <div className="flex items-center justify-between rounded-lg border border-zinc-200/70 bg-white px-4 py-3">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">{selectedProject.name}</h2>
                  <p className="text-sm text-slate-600">{selectedProject.type} · {selectedProject.headcount} 人</p>
                </div>
                <span
                  className="rounded-full px-3 py-1 text-xs font-medium text-zinc-950"
                  style={{ backgroundColor: quadrantColor[selectedProject.quadrant] }}
                >
                  {quadrantLabel[selectedProject.quadrant]}
	                </span>
	              </div>
              {diagnosis ? <DiagnosisCard diagnosis={diagnosis} project={selectedProject} /> : null}
	              <ProjectDashboard project={selectedProject} trend={selectedTrend} talents={talentRisk} />
	            </>
          ) : (
            <div className="flex h-96 items-center justify-center rounded-lg border border-dashed border-zinc-200 bg-white/50">
              <p className="text-sm text-slate-500">点击左侧四象限图中的项目查看详情</p>
            </div>
          )}
        </div>
      </section>

      <ChatPanel
        quickButtons={[
          { label: '为什么人效低', prompt: '为什么这个项目人效低？' },
          { label: 'AI用在哪了', prompt: '这个项目 AI 主要用在哪些模型上？' },
          { label: '核心人才情况', prompt: '这个项目核心人才风险如何？' },
        ]}
        onSend={handleSend}
        messages={messages}
        isLoading={chatLoading}
      />
    </div>
	  )
	}

function getDecisionIntent(project: ProjectWithMetrics) {
  if (project.quadrant === 'underperforming') return 'optimize'
  if (project.quadrant === 'high_potential') return 'growth'
  if (project.quadrant === 'amplifier') return 'benchmark'
  return 'baseline'
}

function DiagnosisCard({ diagnosis, project }: { diagnosis: ReturnType<typeof buildDiagnosis>; project: ProjectWithMetrics }) {
  const toneClass =
    diagnosis.tone === 'red'
      ? 'border-red-500/30 bg-red-500/5 text-red-200'
      : diagnosis.tone === 'green'
        ? 'border-green-500/30 bg-green-500/5 text-green-200'
        : diagnosis.tone === 'blue'
          ? 'border-blue-500/30 bg-blue-500/5 text-blue-700'
          : 'border-zinc-200/70 bg-white text-slate-800'

  return (
    <section className={`rounded-lg border p-4 ${toneClass}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="text-xs font-medium uppercase tracking-[0.16em] opacity-70">诊断结论</div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">{diagnosis.title}</h3>
          <p className="mt-2 text-sm leading-6 text-slate-700">{diagnosis.summary}</p>
        </div>
        <a
          href={`/decision?project=${encodeURIComponent(project.id)}&intent=${getDecisionIntent(project)}`}
          className="shrink-0 rounded-md border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-xs font-medium text-blue-700 hover:bg-blue-500/20"
        >
          生成方案
        </a>
      </div>
      <div className="mt-4 grid grid-cols-3 gap-2">
        {diagnosis.evidences.map((item) => (
          <div key={item} className="rounded-md border border-zinc-200 bg-white/70 px-3 py-2 text-xs leading-5 text-slate-600">
            {item}
          </div>
        ))}
      </div>
      <div className="mt-3 rounded-md border border-zinc-200 bg-white/70 px-3 py-2 text-xs text-slate-700">
        {diagnosis.action}
      </div>
    </section>
  )
}
