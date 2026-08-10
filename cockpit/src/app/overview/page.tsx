'use client'

import dynamic from 'next/dynamic'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ChatPanel } from '@/components/ChatPanel'
import { KPICard } from '@/components/KPICard'
import { useAppData } from '@/lib/DataProvider'
import { formatNumber, formatProductivity, formatRatio, formatWan } from '@/lib/format'
import { ProjectWithMetrics, Quadrant } from '@/lib/types'
import { buildOverviewContext } from '@/lib/buildContext'

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

type ChartPoint = [number, number, number, string, string, number, string]

function buildOverviewInsights(projects: ProjectWithMetrics[]) {
  if (projects.length === 0) return null
  const byProductivity = [...projects].sort((a, b) => b.productivity - a.productivity)
  const highPotential = projects.filter((project) => project.quadrant === 'high_potential')
  const underperforming = projects.filter((project) => project.quadrant === 'underperforming')
  const amplifier = projects.filter((project) => project.quadrant === 'amplifier')
  const underAiCost = underperforming.reduce((sum, project) => sum + project.ai_cost, 0)

  return [
    `**人效冠军：** ${byProductivity[0].name}，人效 ${formatProductivity(byProductivity[0].productivity)}，以 ${byProductivity[0].headcount} 人团队创造 ${formatWan(byProductivity[0].profit)} 利润。`,
    '',
    `**待加码机会：** ${highPotential.length} 个项目人效高但 AI 使用普及度低——加码 AI 投入可能获得高回报。`,
    '',
    `**待改善预警：** ${underperforming.length} 个项目 AI 投入高但人效未达预期，累计 AI 投入 ${formatWan(underAiCost)}。`,
    '',
    `**AI 放大验证：** ${amplifier.length} 个项目处于有效区，AI 正在有效驱动人效提升。`,
  ].join('\n')
}

function productivityDelta(project: ProjectWithMetrics, average: number) {
  if (average === 0) return 0
  return project.productivity / average - 1
}

function buildSignalCards(projects: ProjectWithMetrics[], averageProductivity: number) {
  if (projects.length === 0) return []
  const sortedByProductivity = [...projects].sort((a, b) => b.productivity - a.productivity)
  const underperforming = projects.filter((project) => project.quadrant === 'underperforming')
  const highPotential = projects.filter((project) => project.quadrant === 'high_potential')
  const largestDrag = underperforming.length > 0
    ? [...underperforming].sort((a, b) => (b.ai_cost * Math.max(0, averageProductivity - b.productivity)) - (a.ai_cost * Math.max(0, averageProductivity - a.productivity)))[0]
    : sortedByProductivity[sortedByProductivity.length - 1]
  const benchmark = sortedByProductivity[0]
  const opportunity = highPotential.length > 0
    ? [...highPotential].sort((a, b) => b.profit - a.profit)[0]
    : sortedByProductivity.find((project) => project.ai_intensity < 0.08) || sortedByProductivity[0]

  return [
    {
      label: '效率偏离项',
      project: largestDrag,
      tone: 'red',
      summary: `人效 ${formatProductivity(largestDrag.productivity)}，较平均 ${formatRatio(productivityDelta(largestDrag, averageProductivity))}`,
      action: '建议诊断 AI 使用结构与岗位匹配',
    },
    {
      label: '最高人效标杆',
      project: benchmark,
      tone: 'green',
      summary: `人效 ${formatProductivity(benchmark.productivity)}，利润 ${formatWan(benchmark.profit)}`,
      action: '沉淀可复制的 AI 使用方法',
    },
    {
      label: '待加码项目',
      project: opportunity,
      tone: 'blue',
      summary: `AI 投入强度 ${formatRatio(opportunity.ai_intensity)}，人效 ${formatProductivity(opportunity.productivity)}`,
      action: '小步加码 AI 预算并设置 3 个月观察点',
    },
  ]
}

export default function OverviewPage() {
  const router = useRouter()
  const { projects, companySummary, isLoading, error, dataSource, sourceName } = useAppData()
  const [messages, setMessages] = useState<{ role: 'user' | 'assistant'; content: string }[]>([])
  const [chatLoading, setChatLoading] = useState(false)
  const [aiInsights, setAiInsights] = useState<string | null>(null)
  const [aiInsightsLoading, setAiInsightsLoading] = useState(false)

	  const maxHeadcount = Math.max(...projects.map((project) => project.headcount), 1)
	  const fallbackInsights = useMemo(() => buildOverviewInsights(projects), [projects])
  const rankedProjects = useMemo(() => [...projects].sort((a, b) => b.productivity - a.productivity), [projects])
  const signalCards = useMemo(() => buildSignalCards(projects, companySummary.avg_productivity), [projects, companySummary.avg_productivity])
	  const diagnosisRan = useRef(false)

  // AI 自动诊断（仅在数据加载后运行一次）
  useEffect(() => {
    if (projects.length > 0 && !diagnosisRan.current) {
      diagnosisRan.current = true
      setAiInsightsLoading(true)
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 8000)
      fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: '',
          page: 'overview_auto_diagnosis',
          client_context: dataSource === 'uploaded' ? buildOverviewContext(projects) : undefined,
        }),
        signal: controller.signal,
      })
        .then(res => res.json())
        .then(data => {
          clearTimeout(timeout)
          const answer = data.answer || ''
          if (answer.includes('系统配置异常') || answer.includes('分析异常') || answer.length < 20) {
            setAiInsights(null)
          } else {
            setAiInsights(answer)
          }
          setAiInsightsLoading(false)
        })
        .catch(() => {
          clearTimeout(timeout)
          setAiInsightsLoading(false)
        })
    }
  }, [projects, dataSource])

  async function handleSend(message: string) {
    setMessages(prev => [...prev, { role: 'user', content: message }])
    setChatLoading(true)
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message,
          page: 'overview',
          client_context: dataSource === 'uploaded' ? buildOverviewContext(projects) : undefined,
        }),
      })
      const data = await res.json()
      setMessages(prev => [...prev, { role: 'assistant', content: data.answer || '暂无分析结果' }])
    } catch {
      setMessages(prev => [...prev, { role: 'assistant', content: '分析请求失败，请重试。' }])
    }
    setChatLoading(false)
  }

  const option = {
    backgroundColor: 'transparent',
    grid: { top: 28, right: 36, bottom: 56, left: 72 },
    legend: {
      bottom: 8,
      textStyle: { color: '#a1a1aa' },
      data: Object.values(quadrantLabel),
    },
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(63,63,70,0.45)',
      borderColor: '#3f3f46',
      textStyle: { color: '#fafafa' },
	      formatter: (params: unknown) => {
	        const data = (params as unknown as { data: ChartPoint }).data
        const delta = productivityDelta({ productivity: Number(data[4]) } as ProjectWithMetrics, companySummary.avg_productivity)
	        return [
	          `<b>${data[3]}</b>`,
	          `人数：${data[5]}`,
	          `人效：${formatProductivity(Number(data[4]))}`,
          `较平均：${formatRatio(delta)}`,
	          `AI 投入强度：${data[6]}（AI成本/人力成本）`,
	          `总投入：${formatWan(data[0])}`,
	          `利润：${formatWan(data[1])}`,
        ].join('<br/>')
      },
    },
    xAxis: {
      name: '总投入',
      type: 'log',
      min: Math.max(1, Math.min(...projects.map(p => p.labor_cost + p.ai_cost)) * 0.7),
      axisLine: { lineStyle: { color: '#3f3f46' } },
      splitLine: { lineStyle: { color: 'rgba(63,63,70,0.45)' } },
      axisLabel: { color: '#a1a1aa', formatter: (value: number) => formatWan(value) },
      nameTextStyle: { color: '#a1a1aa' },
    },
    yAxis: {
      name: '利润',
      type: 'log',
      min: Math.max(1, Math.min(...projects.map(p => p.profit)) * 0.7),
      axisLine: { lineStyle: { color: '#3f3f46' } },
      splitLine: { lineStyle: { color: 'rgba(63,63,70,0.45)' } },
      axisLabel: { color: '#a1a1aa', formatter: (value: number) => formatWan(value) },
      nameTextStyle: { color: '#a1a1aa' },
    },
    series: Object.entries(quadrantLabel).map(([quadrant, label]) => ({
      name: label,
      type: 'scatter',
      data: projects
        .filter((project) => project.quadrant === quadrant)
        .map((project) => [
          project.labor_cost + project.ai_cost,
          project.profit,
          project.headcount,
          project.name,
          project.productivity,
          project.headcount,
          formatRatio(project.ai_intensity),
          project.id,
        ]),
	      itemStyle: { color: quadrantColor[quadrant as Quadrant], opacity: 0.85 },
      label: {
        show: true,
        formatter: (params: unknown) => {
          const data = (params as { data: ChartPoint }).data
          const project = projects.find((item) => item.name === data[3])
          if (!project) return ''
          const topOrBottom = rankedProjects.slice(0, 3).includes(project) || rankedProjects.slice(-3).includes(project)
          return topOrBottom ? project.name : ''
        },
        color: '#d4d4d8',
        fontSize: 10,
        position: 'top',
      },
	      symbolSize: (value: unknown) => {
        const point = value as ChartPoint
        return Math.max(20, Math.min(80, 20 + (point[2] / maxHeadcount) * 60))
      },
      emphasis: {
        scale: 1.1,
        label: { show: true, formatter: '{@[3]}', color: '#fafafa' },
      },
      markLine:
        quadrant === 'amplifier'
          ? {
              symbol: ['none', 'none'],
              lineStyle: { color: '#52525b', type: 'dashed' },
              label: { show: true, position: 'end', formatter: '平均人效线', color: '#71717a', fontSize: 10 },
              data: [
                [
                  { coord: [Math.max(1, Math.min(...projects.map(p => p.labor_cost + p.ai_cost)) * 0.8), Math.max(1, Math.min(...projects.map(p => p.labor_cost + p.ai_cost)) * 0.8 * companySummary.avg_productivity)] },
                  {
                    coord: [
                      Math.max(...projects.map((project) => project.labor_cost + project.ai_cost), 1),
                      Math.max(...projects.map((project) => project.labor_cost + project.ai_cost), 1) *
                        companySummary.avg_productivity,
                    ],
                  },
                ],
              ],
            }
          : undefined,
    })),
  }

  if (error) {
    return <div className="w-full p-8 text-red-700">数据加载失败：{error}</div>
  }

  return (
    <div className="w-full space-y-4 px-8 pb-44 pt-5">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">投入产出全景</h1>
          <p className="mt-1 text-sm text-slate-600">总投入、利润与人效偏离的全局分布；AI 投入强度 = AI 成本 / 人力成本</p>
        </div>
        <div className="text-xs text-slate-500">{dataSource === 'uploaded' ? sourceName : '脱敏样本'} · 2026-04</div>
      </header>

      <section className="grid grid-cols-4 gap-4">
        <KPICard label="业务单元数" value={isLoading ? '--' : formatNumber(companySummary.project_count)} sub="可点击下钻到价值信号" />
        <KPICard
          label="总人力+AI投入"
          value={isLoading ? '--' : formatWan(companySummary.total_labor_cost + companySummary.total_ai_cost)}
          sub={`整体 AI 投入强度 ${formatRatio(companySummary.ai_to_labor_ratio)}`}
        />
        <KPICard label="总利润" value={isLoading ? '--' : formatWan(companySummary.total_profit)} sub="收入/利润为脱敏模拟" />
        <KPICard label="平均人效" value={isLoading ? '--' : formatProductivity(companySummary.avg_productivity)} sub="利润 ÷ 总投入" />
      </section>

	      <section className="grid grid-cols-[1fr_380px] gap-4">
	        <div className="rounded-lg border border-zinc-200/70 bg-white p-4">
	          <div className="mb-2 flex items-center justify-between">
	            <h2 className="text-sm font-semibold text-slate-900">投入产出分布图</h2>
	            <span className="text-xs text-slate-500">斜率 = 人效，气泡大小 = 人数，颜色 = AI 投入强度与人效象限</span>
          </div>
          {isLoading ? (
            <div className="h-[520px] animate-pulse rounded bg-slate-200/70" />
          ) : (
            <ReactECharts
              option={option}
              style={{ height: 520 }}
              onEvents={{
                click: (params: { data?: unknown }) => {
                  const data = params.data as (ChartPoint & { 7?: string }) | undefined
                  const projectId = data?.[7]
                  if (projectId) router.push(`/signal?id=${projectId}`)
                },
              }}
            />
          )}
        </div>
	        <OverviewDecisionPanel
	          projects={rankedProjects}
	          signalCards={signalCards}
	          avgProductivity={companySummary.avg_productivity}
	          isLoading={isLoading || aiInsightsLoading}
	          aiInsights={aiInsights || fallbackInsights}
	          onOpenProject={(projectId) => router.push(`/signal?id=${projectId}`)}
	        />
	      </section>

      <section className="grid grid-cols-4 gap-3">
        <div className="rounded-lg border border-green-500/20 bg-green-500/5 p-3">
          <div className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full bg-green-500" />
            <span className="text-xs font-medium text-green-300">AI 有效区</span>
          </div>
          <p className="mt-1 text-[11px] leading-4 text-slate-600">AI 投入高于中位数，且人效高于中位数。说明 AI 投入正在有效放大业务产出。</p>
        </div>
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
          <div className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full bg-red-500" />
            <span className="text-xs font-medium text-red-700">待改善</span>
          </div>
          <p className="mt-1 text-[11px] leading-4 text-slate-600">AI 投入高于中位数，但人效低于中位数。AI 投入尚未转化为产出提升，需诊断原因。</p>
        </div>
        <div className="rounded-lg border border-blue-500/20 bg-blue-500/5 p-3">
          <div className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full bg-blue-500" />
            <span className="text-xs font-medium text-blue-700">待加码</span>
          </div>
          <p className="mt-1 text-[11px] leading-4 text-slate-600">人效高于中位数，但 AI 投入低于中位数。业务基础好，加码 AI 可能获得高回报。</p>
        </div>
        <div className="rounded-lg border border-zinc-600/20 bg-slate-100/30 p-3">
          <div className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full bg-zinc-500" />
            <span className="text-xs font-medium text-slate-600">基础区</span>
          </div>
          <p className="mt-1 text-[11px] leading-4 text-slate-600">AI 投入和人效均低于中位数。需先诊断业务基本面，再考虑 AI 投入策略。</p>
        </div>
      </section>

      <ChatPanel
        quickButtons={[
          { label: '人效最高的项目', prompt: '哪个项目人效最高？' },
          { label: 'AI投入最大的', prompt: 'AI 投入最大的项目是谁？' },
          { label: '待改善有哪些', prompt: '待改善有哪些项目？' },
        ]}
        onSend={handleSend}
        messages={messages}
        isLoading={chatLoading}
      />
    </div>
	  )
	}

function OverviewDecisionPanel({
  projects,
  signalCards,
  avgProductivity,
  isLoading,
  aiInsights,
  onOpenProject,
}: {
  projects: ProjectWithMetrics[]
  signalCards: ReturnType<typeof buildSignalCards>
  avgProductivity: number
  isLoading: boolean
  aiInsights: string | null
  onOpenProject: (projectId: string) => void
}) {
  const topProjects = projects.slice(0, 5)
  const bottomProjects = projects.slice(-5).reverse()
  const maxProductivity = Math.max(...projects.map((project) => project.productivity), 1)

  if (isLoading) {
    return (
      <aside className="space-y-3">
        <div className="h-40 animate-pulse rounded-lg border border-zinc-200/70 bg-white" />
        <div className="h-64 animate-pulse rounded-lg border border-zinc-200/70 bg-white" />
      </aside>
    )
  }

  return (
    <aside className="space-y-3">
      <section className="rounded-lg border border-zinc-200/70 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-900">经营关注信号</h2>
          <span className="rounded-md border border-blue-500/30 bg-blue-500/10 px-2 py-1 text-[11px] text-blue-700">AI 判读</span>
        </div>
        <div className="space-y-2">
          {signalCards.map((card) => (
            <button
              key={`${card.label}-${card.project.id}`}
              type="button"
              onClick={() => onOpenProject(card.project.id)}
              className={[
                'w-full rounded-md border p-3 text-left transition-colors hover:bg-slate-200/70',
                card.tone === 'red' ? 'border-red-500/30 bg-red-500/5' : card.tone === 'green' ? 'border-green-500/30 bg-green-500/5' : 'border-blue-500/30 bg-blue-500/5',
              ].join(' ')}
            >
              <div className="flex items-center justify-between gap-3">
                <span className={card.tone === 'red' ? 'text-xs font-medium text-red-700' : card.tone === 'green' ? 'text-xs font-medium text-green-300' : 'text-xs font-medium text-blue-700'}>
                  {card.label}
                </span>
                <span className="text-[11px] text-slate-500">{card.project.name}</span>
              </div>
              <div className="mt-2 text-sm text-slate-900">{card.summary}</div>
              <div className="mt-1 text-xs text-slate-500">{card.action}</div>
            </button>
          ))}
        </div>
      </section>

      <section className="rounded-lg border border-zinc-200/70 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-900">人效排行</h2>
          <span className="text-[11px] text-slate-500">平均 {formatProductivity(avgProductivity)}</span>
        </div>
        <RankingList title="Top 5" projects={topProjects} maxProductivity={maxProductivity} avgProductivity={avgProductivity} onOpenProject={onOpenProject} />
        <div className="my-3 h-px bg-slate-100" />
        <RankingList title="Bottom 5" projects={bottomProjects} maxProductivity={maxProductivity} avgProductivity={avgProductivity} onOpenProject={onOpenProject} />
      </section>

      {aiInsights ? (
        <section className="rounded-lg border border-zinc-200/70 bg-white p-4">
          <div className="text-xs font-medium text-slate-600">系统摘要</div>
          <p className="mt-2 line-clamp-5 whitespace-pre-line text-xs leading-5 text-slate-500">{aiInsights.replace(/\*\*/g, '')}</p>
        </section>
      ) : null}
    </aside>
  )
}

function RankingList({
  title,
  projects,
  maxProductivity,
  avgProductivity,
  onOpenProject,
}: {
  title: string
  projects: ProjectWithMetrics[]
  maxProductivity: number
  avgProductivity: number
  onOpenProject: (projectId: string) => void
}) {
  return (
    <div>
      <div className="mb-2 text-[11px] font-medium uppercase tracking-[0.16em] text-slate-500">{title}</div>
      <div className="space-y-2">
        {projects.map((project, index) => {
          const delta = productivityDelta(project, avgProductivity)
          const positive = delta >= 0
          return (
            <button
              key={project.id}
              type="button"
              onClick={() => onOpenProject(project.id)}
              className="w-full rounded-md border border-zinc-200 bg-white/70 px-3 py-2 text-left hover:border-blue-500/40"
            >
              <div className="mb-1 flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <span className="mr-2 text-xs text-slate-500">{index + 1}</span>
                  <span className="truncate text-sm text-slate-800">{project.name}</span>
                </div>
                <span className={positive ? 'text-xs tabular-nums text-green-300' : 'text-xs tabular-nums text-red-700'}>
                  {positive ? '+' : ''}{formatRatio(delta)}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <div className="h-1.5 flex-1 rounded-full bg-slate-100">
                  <div
                    className={positive ? 'h-1.5 rounded-full bg-green-500' : 'h-1.5 rounded-full bg-red-500'}
                    style={{ width: `${Math.max(4, Math.min(100, (project.productivity / maxProductivity) * 100))}%` }}
                  />
                </div>
                <span className="w-10 text-right text-xs tabular-nums text-slate-700">{formatProductivity(project.productivity)}</span>
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}
