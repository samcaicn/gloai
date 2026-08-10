'use client'

import dynamic from 'next/dynamic'
import { Suspense, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'
import {
  buildAttributionEvidence,
  AttributionEvidence,
  ModelChartData,
  PeopleChartData,
  DepthChartData,
  AttritionChartData,
  OrgChartData,
} from '@/lib/analytics'
import { AttributionResponse } from '@/lib/aiSchemas'
import { formatProductivity, formatRatio, formatWan } from '@/lib/format'
import {
  Card, SectionHeader, SeverityBadge, Severity,
  FactTag, JudgmentTag, ChapterTransition, Skeleton, CockpitTopbar, AiBriefing,
} from '@/components/ui'

const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })
const modelColors = ['#dc2626', '#0891b2', '#2563eb', '#7c3aed', '#d97706', '#64748b']

const quadrantLabel: Record<string, string> = {
  amplifier: 'AI 已让人效变好',
  underperforming: '待改善',
  high_potential: '待加码',
  low_base: '基础区',
  support: '支撑部门',
}
const quadrantTone: Record<string, string> = {
  amplifier: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700',
  underperforming: 'border-red-500/40 bg-red-500/10 text-red-700',
  high_potential: 'border-blue-500/40 bg-blue-500/10 text-blue-700',
  low_base: 'border-zinc-600/40 bg-zinc-700/20 text-slate-600',
  support: 'border-slate-300 bg-slate-100 text-slate-600',
}

function severityScore(severity: Severity) {
  if (severity === 'high') return 90
  if (severity === 'medium') return 62
  if (severity === 'low') return 35
  return 12
}

function RootCauseRadar({ evidence, ai }: { evidence: AttributionEvidence | null; ai: AttributionResponse | null }) {
  const steps = evidence?.steps || []
  const scores = steps.map(step => severityScore(ai?.steps.find(item => item.key === step.key)?.severity ?? step.severity))
  const option = {
    backgroundColor: 'transparent',
    radar: {
      indicator: [
        { name: '模型', max: 100 },
        { name: '人群', max: 100 },
        { name: '深度', max: 100 },
        { name: '流失', max: 100 },
        { name: '组织', max: 100 },
      ],
      axisName: { color: '#475569', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(203,213,225,0.8)' } },
      splitArea: { areaStyle: { color: ['rgba(241,245,249,0.9)', 'rgba(248,250,252,0.9)'] } },
    },
    series: [{ type: 'radar', data: [{ value: scores.length === 5 ? scores : [0, 0, 0, 0, 0], areaStyle: { color: 'rgba(217,119,6,0.14)' }, lineStyle: { color: '#d97706' }, itemStyle: { color: '#d97706' } }] }],
  }
  return <ReactECharts option={option} style={{ height: 220 }} />
}

function ChartShell({ title, children, subtitle }: { title: string; children: ReactNode; subtitle?: string }) {
  return (
    <div className="rounded-xl border border-cyan-100/60 bg-slate-50/40 p-3">
      <div className="flex items-center justify-between gap-3">
        <div className="text-[13px] font-semibold text-slate-700">{title}</div>
        {subtitle ? <div className="text-[11px] text-slate-500">{subtitle}</div> : null}
      </div>
      <div className="mt-2">{children}</div>
    </div>
  )
}

function ChartFallback() {
  return (
    <div className="rounded-xl border border-cyan-100/60 bg-slate-50/40 p-4 text-sm text-slate-500">
      数据维度不足，请参考左侧系统计算事实
    </div>
  )
}

function ModelCompareChart({ data }: { data: ModelChartData }) {
  if (data.current.length === 0) return <ChartFallback />
  const models = data.current.map(row => row.model)
  const option = {
    backgroundColor: 'transparent',
    color: modelColors,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#ffffff',
      borderColor: '#cbd5e1',
      textStyle: { color: '#1a2332' },
      formatter: (params: { seriesName: string; value: number }[]) => params
        .map(item => `${item.seriesName}: ${Math.round(item.value * 100)}%`)
        .join('<br/>'),
    },
    legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 10 }, itemWidth: 12, itemHeight: 8 },
    grid: { top: 34, right: 10, bottom: 12, left: 52 },
    xAxis: { type: 'value', max: 1, axisLabel: { color: '#64748b', formatter: (v: number) => `${Math.round(v * 100)}%` }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.55)' } } },
    yAxis: { type: 'category', data: ['标杆', '本项目'], axisLabel: { color: '#475569', fontSize: 11 } },
    series: models.map((model, index) => ({
      name: model,
      type: 'bar',
      stack: 'cost',
      barWidth: 22,
      data: [data.benchmark.find(row => row.model === model)?.share || 0, data.current[index]?.share || 0],
      itemStyle: { color: modelColors[index] },
    })),
  }
  return (
    <ChartShell title="本项目 vs 标杆 模型成本结构" subtitle={`最大差异：${data.topGap.model} ${Math.round(data.topGap.gap * 100)}pct`}>
      <ReactECharts option={option} style={{ height: 180 }} />
    </ChartShell>
  )
}

function PeopleDistChart({ data }: { data: PeopleChartData }) {
  if (data.buckets.length === 0) return <ChartFallback />
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#ffffff',
      borderColor: '#cbd5e1',
      textStyle: { color: '#1a2332' },
    },
    legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 14, itemHeight: 8 },
    grid: { top: 36, right: 12, bottom: 28, left: 40 },
    xAxis: { type: 'category', data: data.buckets.map(row => row.range), axisLabel: { color: '#64748b', fontSize: 10 } },
    yAxis: { type: 'value', name: '人数', nameTextStyle: { color: '#64748b', fontSize: 10 }, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.55)' } } },
    series: [
      { name: '本项目', type: 'bar', data: data.buckets.map(row => row.current), itemStyle: { color: '#2563eb' }, barMaxWidth: 18 },
      { name: '标杆', type: 'bar', data: data.buckets.map(row => row.benchmark), itemStyle: { color: '#0891b2' }, barMaxWidth: 18 },
    ],
  }
  return (
    <ChartShell title="活跃天数分布对比" subtitle={`重度使用者占比 ${formatRatio(data.powerShareCurrent)} vs 标杆 ${formatRatio(data.powerShareBenchmark)}`}>
      <ReactECharts option={option} style={{ height: 180 }} />
    </ChartShell>
  )
}

function DepthTrendChart({ data }: { data: DepthChartData }) {
  if (data.months.length === 0) return <ChartFallback />
  const option = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
    legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 16, itemHeight: 8 },
    grid: { top: 36, right: 16, bottom: 28, left: 54 },
    xAxis: { type: 'category', data: data.months, axisLabel: { color: '#64748b', fontSize: 10 } },
    yAxis: { type: 'value', name: '元/人', nameTextStyle: { color: '#64748b', fontSize: 10 }, axisLabel: { color: '#64748b', formatter: (v: number) => formatWan(v) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.55)' } } },
    series: [
      { name: '本项目', type: 'line', smooth: true, data: data.currentPerCapita, symbolSize: 6, lineStyle: { color: '#2563eb', width: 2 }, itemStyle: { color: '#2563eb' }, areaStyle: { color: 'rgba(37,99,235,0.08)' } },
      { name: '标杆', type: 'line', smooth: true, data: data.benchmarkPerCapita, symbolSize: 6, lineStyle: { color: '#0891b2', width: 2 }, itemStyle: { color: '#0891b2' } },
    ],
  }
  return (
    <ChartShell title="人均 AI 成本走势对比（近 6 个月）">
      <ReactECharts option={option} style={{ height: 180 }} />
    </ChartShell>
  )
}

function AttritionChart({ data }: { data: AttritionChartData }) {
  if (data.months.length === 0) return <ChartFallback />
  const option = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
    legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 14, itemHeight: 8 },
    grid: { top: 36, right: 12, bottom: 28, left: 40 },
    xAxis: { type: 'category', data: data.months, axisLabel: { color: '#64748b', fontSize: 10 } },
    yAxis: { type: 'value', name: '人数', nameTextStyle: { color: '#64748b', fontSize: 10 }, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.55)' } } },
    series: [
      { name: '主动流失', type: 'bar', stack: 'exits', data: data.voluntary, itemStyle: { color: '#f87171' }, barMaxWidth: 34 },
      { name: '被动流失', type: 'bar', stack: 'exits', data: data.involuntary, itemStyle: { color: '#dc2626' }, barMaxWidth: 34 },
    ],
  }
  return (
    <ChartShell title="流失人数分解" subtitle={`重度使用者流失 ${data.powerExits} / ${data.totalExits}`}>
      <ReactECharts option={option} style={{ height: 180 }} />
      {data.months.length === 1 ? (
        <p className="mt-1 text-[11px] text-slate-500">缺少逐月流失明细，当前按累计数据展示。</p>
      ) : null}
    </ChartShell>
  )
}

function OrgCompareChart({ data }: { data: OrgChartData }) {
  if (data.layers.length === 0) return <ChartFallback />
  const option = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
    legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 14, itemHeight: 8 },
    grid: { top: 36, right: 20, bottom: 12, left: 58 },
    xAxis: { type: 'value', name: '元/人', nameTextStyle: { color: '#64748b', fontSize: 10 }, axisLabel: { color: '#64748b', formatter: (v: number) => formatWan(v) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.55)' } } },
    yAxis: { type: 'category', data: data.layers.map(row => row.name), axisLabel: { color: '#64748b', fontSize: 11 } },
    series: [
      { name: '本项目', type: 'bar', data: data.layers.map(row => row.current), itemStyle: { color: '#2563eb' }, barMaxWidth: 14 },
      { name: '标杆', type: 'bar', data: data.layers.map(row => row.benchmark), itemStyle: { color: '#0891b2' }, barMaxWidth: 14 },
    ],
  }
  return (
    <ChartShell title="各主流角色人均 AI 成本 当前 vs 标杆">
      <ReactECharts option={option} style={{ height: 240 }} />
    </ChartShell>
  )
}

function StepChart({ step }: { step: AttributionEvidence['steps'][number] }) {
  if (!step.chart) return <ChartFallback />
  if (step.key === 'model' && step.chart.type === 'model_compare') return <ModelCompareChart data={step.chart} />
  if (step.key === 'people' && step.chart.type === 'active_dist') return <PeopleDistChart data={step.chart} />
  if (step.key === 'depth' && step.chart.type === 'depth_trend') return <DepthTrendChart data={step.chart} />
  if (step.key === 'attrition' && step.chart.type === 'attrition_breakdown') return <AttritionChart data={step.chart} />
  if (step.key === 'org' && step.chart.type === 'org_compare') return <OrgCompareChart data={step.chart} />
  return <ChartFallback />
}

function AttributionInner() {
  const router = useRouter()
  const params = useSearchParams()
  const { projects, monthlyTrend, talentRisk, roleMatrix, isLoading } = useAppData()

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [ai, setAi] = useState<AttributionResponse | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [revealed, setRevealed] = useState(0)
  const revealTimer = useRef<ReturnType<typeof setInterval> | null>(null)

  // URL 参数 / 默认项目（优先取待改善且 AI 投入最大的）
  useEffect(() => {
    if (projects.length === 0) return
    const fromUrl = params.get('id')
    if (fromUrl && projects.some(p => p.id === fromUrl)) {
      setSelectedId(fromUrl)
    } else if (!selectedId) {
      const target = [...projects]
        .filter(p => p.quadrant === 'underperforming')
        .sort((a, b) => a.productivity - b.productivity || b.ai_cost - a.ai_cost)[0] || projects[0]
      setSelectedId(target.id)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projects, params])

  const evidence: AttributionEvidence | null = selectedId
    ? buildAttributionEvidence(selectedId, projects, monthlyTrend, talentRisk, roleMatrix)
    : null

  // 选择项目 → 调 AI（带会话缓存）
  useEffect(() => {
    if (!selectedId || projects.length === 0) return
    setAi(null)
    setRevealed(0)

    const cacheKey = `attribution-v3-${selectedId}`
    try {
      const cached = sessionStorage.getItem(cacheKey)
      if (cached) {
        const data = JSON.parse(cached)
        setAi(data)
        startReveal()
        return
      }
    } catch { /* ignore */ }

    setAiLoading(true)
    fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'attribution', project_id: selectedId }),
    })
      .then(r => r.json())
      .then(json => {
        setAiLoading(false)
        if (json.data?.steps) {
          setAi(json.data)
          try { sessionStorage.setItem(cacheKey, JSON.stringify(json.data)) } catch { /* ignore */ }
          startReveal()
        } else {
          setAi(null)
          setRevealed(5)
        }
      })
      .catch(() => { setAiLoading(false); setRevealed(5) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, projects.length])

  function startReveal() {
    setRevealed(0)
    if (revealTimer.current) clearInterval(revealTimer.current)
    let n = 0
    revealTimer.current = setInterval(() => {
      n += 1
      setRevealed(n)
      if (n >= 6 && revealTimer.current) clearInterval(revealTimer.current)
    }, 450)
  }

  useEffect(() => () => { if (revealTimer.current) clearInterval(revealTimer.current) }, [])

  const project = evidence?.project

  return (
    <div className="w-full space-y-6 px-8 pb-24 pt-8">
      <CockpitTopbar />
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-[28px] font-semibold leading-tight text-[#1a2332]">
            为什么投了钱，人效没起来？
          </h1>
          <p className="mt-1.5 text-sm text-slate-500">系统沿五条因果线逐步排查，AI 交叉研判根因——每一步都有证据。</p>
        </div>
        <select
          value={selectedId ?? ''}
          onChange={e => router.replace(`/attribution?id=${e.target.value}`)}
          className="h-10 rounded-lg border border-zinc-200 bg-white px-3 text-sm text-slate-800 outline-none focus:border-blue-500"
        >
          {[...projects].sort((a, b) => a.productivity - b.productivity).map(p => (
            <option key={p.id} value={p.id}>
              {p.name} · {quadrantLabel[p.quadrant]} · 人效 {formatProductivity(p.productivity)}
            </option>
          ))}
        </select>
      </header>
      <AiBriefing title="诊断洞察" prompt="基于根因诊断页，给出当前项目最值得关注的一句根因洞察" />

      {isLoading || !project ? (
        <Skeleton className="h-24" />
      ) : (
        <Card className="flex items-center justify-between px-6 py-4">
          <div className="flex items-center gap-4">
            <div>
              <div className="text-xl font-semibold text-slate-900">{project.name}</div>
              <div className="mt-0.5 text-xs text-slate-500">{project.type} · {project.headcount} 人</div>
            </div>
            <span className={`rounded-full border px-3 py-1 text-xs font-medium ${quadrantTone[project.quadrant]}`}>
              {quadrantLabel[project.quadrant]}
            </span>
          </div>
          <div className="flex gap-8 text-right">
            <div>
              <div className="text-[10px] uppercase tracking-wider text-slate-500">人效</div>
              <div className="text-xl font-bold tabular-nums text-slate-900">{formatProductivity(project.productivity)}</div>
            </div>
            <div>
              <div className="text-[10px] uppercase tracking-wider text-slate-500">AI 投入强度</div>
              <div className="text-xl font-bold tabular-nums text-slate-900">{formatRatio(project.ai_intensity)}</div>
            </div>
            {evidence?.benchmark && (
              <div>
                <div className="text-[10px] uppercase tracking-wider text-slate-500">对照标杆</div>
                <div className="text-sm font-medium text-emerald-700">
                  {evidence.benchmark.name}（人效 {formatProductivity(evidence.benchmark.productivity)}）
                  {evidence.benchmarkQuality === 'weak' || evidence.benchmarkQuality === 'fallback'
                    ? <span className="ml-2 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-700">标杆样本有限</span>
                    : null}
                </div>
              </div>
            )}
          </div>
        </Card>
      )}

      {evidence ? (
        <Card className="p-5">
          <div className="grid grid-cols-5 gap-3">
            {evidence.steps.map((step, index) => {
              const aiStep = ai?.steps.find(s => s.key === step.key)
              const severity: Severity = aiStep?.severity ?? step.severity
              const visible = revealed > index || (!aiLoading && !ai)
              return (
                <div key={step.key} className={`relative rounded-xl border px-3 py-3 transition-all ${visible ? 'border-cyan-400/35 bg-cyan-400/5' : 'border-zinc-200 bg-slate-50/70 opacity-50'}`}>
                  {index < evidence.steps.length - 1 ? <span className="absolute left-[calc(100%-0.75rem)] top-6 h-px w-6 bg-zinc-700" /> : null}
                  <div className="flex items-center justify-between">
                    <span className="grid h-7 w-7 place-items-center rounded-full border border-zinc-200 text-[11px] font-bold text-slate-600">{index + 1}</span>
                    <SeverityBadge severity={severity} />
                  </div>
                  <div className="mt-3 text-sm font-medium text-slate-800">{step.title}</div>
                </div>
              )
            })}
          </div>
        </Card>
      ) : null}

      {/* 五步推理链 */}
      <div className="space-y-3">
        {evidence?.steps.map((step, i) => {
          const aiStep = ai?.steps.find(s => s.key === step.key)
          const visible = revealed > i || (!aiLoading && !ai)
          const severity: Severity = aiStep?.severity ?? step.severity
          return (
            <div
              key={step.key}
              className={`transition-all duration-500 ${visible ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0'}`}
            >
              <Card className="overflow-hidden">
                <div className="flex items-center justify-between border-b border-zinc-200/80 px-5 py-3">
                  <div className="flex items-center gap-3">
                    <span className="grid h-6 w-6 place-items-center rounded-md border border-zinc-200 text-[11px] font-bold text-slate-500">
                      {i + 1}
                    </span>
                    <span className="text-sm font-semibold text-slate-900">{step.title}</span>
                  </div>
                  <SeverityBadge severity={severity} />
                </div>
                <div className="grid grid-cols-[1fr_1.2fr] divide-x divide-zinc-200">
                  {/* 左：事实 */}
                  <div className="space-y-2.5 px-5 py-4">
                    <FactTag />
                    {step.facts.map((f, j) => (
                      <div key={j} className="flex items-baseline justify-between gap-3">
                        <span className="text-xs text-slate-500">{f.label}</span>
                        <span className="text-right">
                          <span className="text-sm font-semibold tabular-nums text-slate-800">{f.value}</span>
                          {f.benchmark && <span className="ml-2 text-[11px] text-slate-500">{f.benchmark}</span>}
                        </span>
                      </div>
                    ))}
                    <StepChart step={step} />
                    <p className="border-t border-zinc-200/60 pt-2 text-xs leading-5 text-slate-600">{step.finding}</p>
                  </div>
                  {/* 右：AI 研判 */}
                  <div className="px-5 py-4">
                    <JudgmentTag />
                    {aiLoading ? (
                      <div className="mt-2.5 space-y-2">
                        <Skeleton className="h-3.5 w-full" />
                        <Skeleton className="h-3.5 w-4/5" />
                      </div>
                    ) : (
                      <p className="mt-2.5 text-sm leading-relaxed text-slate-800">
                        {aiStep?.judgment || 'AI 研判暂不可用，参考左侧系统计算事实。'}
                      </p>
                    )}
                  </div>
                </div>
              </Card>
            </div>
          )
        })}
      </div>

      {/* 根因综合 */}
      <div className={`transition-all duration-500 ${revealed >= 6 || (!aiLoading && !ai) ? 'opacity-100' : 'opacity-0'}`}>
        <Card className="border-blue-500/30 bg-gradient-to-br from-blue-500/[0.07] to-transparent p-6">
          <div className="flex items-center justify-between">
            <SectionHeader title="根因综合" caption="AI 将五步证据交叉后的主要矛盾判断" />
            <div className="flex items-center gap-3">
              <JudgmentTag />
              {ai && (
                <span className="rounded-md border border-zinc-200 px-2 py-1 text-[11px] text-slate-500">
                  置信度 {ai.confidence === 'high' ? '高' : ai.confidence === 'medium' ? '中' : '低'}
                </span>
              )}
            </div>
          </div>
          <div className="mt-4 grid grid-cols-[320px_1fr] gap-5">
            <RootCauseRadar evidence={evidence} ai={ai} />
            {aiLoading ? (
              <div className="space-y-2"><Skeleton className="h-4 w-full" /><Skeleton className="h-4 w-3/4" /></div>
            ) : (
              <p className="text-[15px] leading-relaxed text-slate-900">
                {ai?.root_cause || 'AI 根因综合暂不可用；请基于上方五步事实自行研判。'}
              </p>
            )}
          </div>
          <div className="mt-5 flex items-center gap-3">
            <button
              type="button"
              onClick={() => router.push(`/decision?from=${selectedId}&cause=${encodeURIComponent(ai?.root_cause?.slice(0, 120) || '')}`)}
              className="rounded-lg bg-blue-500 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-400"
            >
              基于此根因生成方案 →
            </button>
          </div>
        </Card>
      </div>

      <ChapterTransition
        text="根因清楚了——接下来钱和人怎么调？"
        href={`/decision?from=${selectedId ?? ''}`}
        cta="04 决策推演"
      />
    </div>
  )
}

export default function AttributionPage() {
  return (
    <Suspense fallback={<div className="p-8"><Skeleton className="h-40" /></div>}>
      <AttributionInner />
    </Suspense>
  )
}
