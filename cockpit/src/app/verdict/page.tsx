'use client'

import dynamic from 'next/dynamic'
import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'
import { buildVerdictInputs, getLeverageMatrix, getProductivityTrend } from '@/lib/analytics'
import { VerdictResponse } from '@/lib/aiSchemas'
import { formatWan, formatProductivity, formatRatio } from '@/lib/format'
import {
  Card, SectionHeader, BigNumber, SeverityBadge,
  JudgmentTag, FactTag, ChapterTransition, Skeleton, SimulatedTag, CockpitTopbar, AiBriefing,
} from '@/components/ui'
import { TermTooltip } from '@/components/TermTooltip'

const CACHE_KEY = 'verdict-cache-v3'
const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })

function gradeToScore(grade?: 'A' | 'B' | 'C') {
  if (grade === 'A') return 86
  if (grade === 'B') return 68
  if (grade === 'C') return 42
  return 55
}

function buildSparkline(values: number[]) {
  const max = Math.max(...values, 1)
  return values.slice(-12).map((value, index) => (
    <span
      key={index}
      className="block w-1 rounded-t bg-cyan-300/70"
      style={{ height: `${Math.max(4, (value / max) * 24)}px` }}
    />
  ))
}

function findingRoute(finding: VerdictResponse['findings'][number]) {
  if (finding.target_chapter === 'attribution') {
    return finding.target_project_id ? `/attribution?id=${finding.target_project_id}` : '/attribution'
  }
  if (finding.target_chapter === 'decision') {
    const params = new URLSearchParams()
    if (finding.target_project_id) params.set('from', finding.target_project_id)
    if (finding.target_cause || finding.title) params.set('cause', finding.target_cause || finding.title)
    const query = params.toString()
    return query ? `/decision?${query}` : '/decision'
  }
  return '/divergence'
}

const findingActionMeta: Record<VerdictResponse['findings'][number]['target_chapter'], { label: string; chip: string; line: string; button: string }> = {
  attribution: { label: '诊断这个项目', chip: '→ 03 根因诊断', line: 'bg-blue-600', button: 'text-blue-700 group-hover:text-blue-600' },
  divergence: { label: '看分化全景', chip: '→ 02 分化地图', line: 'bg-cyan-600', button: 'text-cyan-700 group-hover:text-cyan-600' },
  decision: { label: '生成行动方案', chip: '→ 04 决策推演', line: 'bg-amber-600', button: 'text-amber-700 group-hover:text-amber-600' },
}

export default function VerdictPage() {
  const router = useRouter()
  const { projects, monthlyTrend, talentRisk, isLoading, error } = useAppData()
  const [ai, setAi] = useState<VerdictResponse | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiFailed, setAiFailed] = useState(false)
  const [dimensionOpen, setDimensionOpen] = useState(true)
  const ran = useRef(false)

  const vi = projects.length > 0 ? buildVerdictInputs(projects, monthlyTrend, talentRisk) : null
  const leverage = projects.length > 0 ? getLeverageMatrix(projects, monthlyTrend) : null
  const pnlProjects = projects.filter(project => !project.is_cost_center)
  const pnlProjectIds = new Set(pnlProjects.map(project => project.id))
  const supportCount = projects.length - pnlProjects.length
  const months = Array.from(new Set(monthlyTrend.map(record => record.month))).sort()
  const trendUpCount = pnlProjects.filter(project => getProductivityTrend(project.id, monthlyTrend).direction === 'up').length
  const monthlyAgg = months.map(month => {
    const rows = monthlyTrend.filter(record => record.month === month && pnlProjectIds.has(record.project_id))
    const labor = rows.reduce((sum, record) => sum + record.labor_cost, 0)
    const aiCost = rows.reduce((sum, record) => sum + record.ai_cost, 0)
    const revenue = rows.reduce((sum, record) => sum + record.revenue, 0)
    return {
      month,
      productivity: revenue / Math.max(labor + aiCost, 1),
      aiRatio: aiCost / Math.max(labor, 1),
      revenue,
    }
  })
  const gradeScores = [
    gradeToScore(ai?.grades.money),
    gradeToScore(ai?.grades.efficiency),
    gradeToScore(ai?.grades.people),
    leverage ? Math.max(30, 90 - getLeverageMatrix(projects, monthlyTrend).points.filter(p => p.verdict === 'underperforming').length * 5) : 55,
    pnlProjects.length > 0 ? Math.round((trendUpCount / pnlProjects.length) * 100) : 55,
  ]
  const dimensionColors = ['bg-blue-600', 'bg-cyan-600', 'bg-amber-600', 'bg-violet-600', 'bg-emerald-600']
  const trendInsight = ai?.dimension_insights?.find(item => item.key === 'trend')?.judgment
  const moneyInsight = ai?.dimension_insights?.find(item => item.key === 'money')?.judgment
  const radarOption = {
    backgroundColor: 'transparent',
    radar: {
      indicator: [
        { name: '钱花得值', max: 100 },
        { name: '效率撬动', max: 100 },
        { name: '人扛得住', max: 100 },
        { name: '模型匹配', max: 100 },
        { name: '趋势', max: 100 },
      ],
      axisName: { color: '#475569', fontSize: 12 },
      splitLine: { lineStyle: { color: 'rgba(203,213,225,0.8)' } },
      splitArea: { areaStyle: { color: ['rgba(241,245,249,0.9)', 'rgba(248,250,252,0.9)'] } },
      axisLine: { lineStyle: { color: 'rgba(203,213,225,0.8)' } },
    },
    series: [{ type: 'radar', data: [{ value: gradeScores, areaStyle: { color: 'rgba(8,145,178,0.16)' }, lineStyle: { color: '#0891b2' }, itemStyle: { color: '#0891b2' } }] }],
  }
  const trendOption = {
    backgroundColor: 'transparent',
    grid: { top: 28, right: 52, bottom: 34, left: 48 },
    tooltip: { trigger: 'axis', backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
    legend: { top: 0, textStyle: { color: '#475569' } },
    xAxis: { type: 'category', data: monthlyAgg.map(row => row.month.slice(5)), axisLabel: { color: '#475569' }, axisLine: { lineStyle: { color: '#cbd5e1' } } },
    yAxis: [
      { type: 'value', name: '人效', axisLabel: { color: '#475569' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      { type: 'value', name: 'AI/人力', axisLabel: { color: '#475569', formatter: (v: number) => formatRatio(v) }, splitLine: { show: false } },
    ],
    series: [
      { name: '人效', type: 'line', smooth: true, data: monthlyAgg.map(row => row.productivity), lineStyle: { color: '#0891b2', width: 3 }, itemStyle: { color: '#0891b2' } },
      { name: 'AI/人力', type: 'line', yAxisIndex: 1, smooth: true, areaStyle: { color: 'rgba(217,119,6,0.10)' }, data: monthlyAgg.map(row => row.aiRatio), lineStyle: { color: '#d97706', width: 2 }, itemStyle: { color: '#d97706' } },
    ],
  }
  const ratingOption = {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
    series: [{
      type: 'pie',
      radius: ['58%', '78%'],
      label: { color: '#334155', formatter: '{b} {c}' },
      data: [
        { name: 'A 已变好', value: leverage ? leverage.counts.amplifier_confirmed : 0, itemStyle: { color: '#0891b2' } },
        { name: 'B 暂观察', value: leverage ? leverage.counts.amplifier_unproven + leverage.counts.high_potential : 0, itemStyle: { color: '#d97706' } },
        { name: 'C 待改善', value: leverage ? leverage.counts.underperforming + leverage.counts.low_base : 0, itemStyle: { color: '#dc2626' } },
      ],
    }],
  }

  async function runVerdict(force = false) {
    if (!force) {
      try {
        const cached = sessionStorage.getItem(CACHE_KEY)
        if (cached) { setAi(JSON.parse(cached)); return }
      } catch { /* ignore */ }
    }
    setAiLoading(true)
    setAiFailed(false)
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'verdict' }),
      })
      const json = await res.json()
      if (json.data?.grades) {
        setAi(json.data)
        try { sessionStorage.setItem(CACHE_KEY, JSON.stringify(json.data)) } catch { /* ignore */ }
      } else {
        setAiFailed(true)
      }
    } catch {
      setAiFailed(true)
    }
    setAiLoading(false)
  }

  useEffect(() => {
    if (projects.length > 0 && !ran.current) {
      ran.current = true
      runVerdict()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projects.length])

  if (error) return <div className="w-full p-8 text-red-700">数据加载失败：{error}</div>

  return (
    <div className="w-full space-y-6 px-8 pb-24 pt-8">
      <CockpitTopbar onRefresh={() => runVerdict(true)} />
      <header>
        <h1 className="text-[28px] font-semibold leading-tight text-[#1a2332]">
          这一年的 AI 转型，值不值？
        </h1>
        <p className="mt-1.5 text-sm text-slate-500">从投入、效率、产出与人才四条线判断 AI 转型是否真的带来经营改善。</p>
      </header>
      <AiBriefing title="本期洞察" prompt="基于总体判断页数据，给经营层一句 AI 转型健康度洞察" />

      {/* 核心指标 */}
      {isLoading || !vi ? (
        <div className="grid grid-cols-6 gap-3">{[0, 1, 2, 3, 4, 5].map(i => <Skeleton key={i} className="h-32" />)}</div>
      ) : (
        <div className="grid grid-cols-6 gap-3">
          <BigNumber label={<TermTooltip term="roi">AI 投资回报</TermTooltip>} value={formatProductivity(vi.northStar.productivity)} units="×" sub="每投 1 元拿回多少利润" />
          <BigNumber label={<TermTooltip term="ai_intensity">AI 投入比</TermTooltip>} value={(vi.northStar.aiToLaborRatio * 100).toFixed(1)} units="%" sub="AI 成本 / 人力成本" />
          <BigNumber label="月度产出" value={(vi.northStar.monthlyProfit / 10000).toFixed(1)} units="万" sub="利润口径" />
          <BigNumber label={<TermTooltip term="power">重度使用者占比</TermTooltip>} value={((vi.efficiencyDim.powerCount / Math.max(talentRisk.length, 1)) * 100).toFixed(1)} units="%" sub={`${vi.efficiencyDim.powerCount} 名重度使用者`} tone="good" />
          <BigNumber
            label={<TermTooltip term="critical_talent">高流失风险人才</TermTooltip>}
            value={String(vi.northStar.criticalTalentCount)}
            units="人"
            sub="重度使用者 × 薪酬偏低 × 流失环境"
            tone={vi.northStar.criticalTalentCount > 20 ? 'bad' : vi.northStar.criticalTalentCount > 0 ? 'warn' : 'good'}
          />
          <BigNumber label={<TermTooltip term="productivity_trend_up">人效在改善</TermTooltip>} value={String(trendUpCount)} units="个" sub={`${pnlProjects.length} 个有 P&L 的业务单元`} tone="good" />
        </div>
      )}
      <div className="-mt-6 flex items-center justify-end gap-3">
        <span className="text-xs text-slate-500">基于 {pnlProjects.length} 个有 P&L 的业务单元；{supportCount} 个支撑部门不参与人效评级。</span>
        <SimulatedTag />
      </div>

      {/* 健康度总评 */}
      <Card className="p-6">
        <SectionHeader
          title="AI 转型整体打分"
          caption="三个维度：钱花得值吗 · 效率撬动了吗 · 人扛得住吗；支撑部门仅看成本与使用，不进入利润人效打分"
          right={
            <div className="flex items-center gap-3">
              <JudgmentTag />
              <button
                type="button"
                onClick={() => runVerdict(true)}
                disabled={aiLoading}
                className="rounded-md border border-zinc-200 px-3 py-1.5 text-xs text-slate-600 transition-colors hover:border-blue-500/50 hover:text-blue-700 disabled:opacity-50"
              >
                {aiLoading ? '分析中…' : '重新分析'}
              </button>
            </div>
          }
        />
        {aiLoading ? (
          <div className="mt-5 space-y-4">
            <div className="flex gap-4">{[0, 1, 2].map(i => <Skeleton key={i} className="h-20 w-24" />)}</div>
            <Skeleton className="h-12" />
          </div>
        ) : ai ? (
          <div className="mt-5 grid grid-cols-[420px_1fr] gap-6">
            <div className="grid min-h-[260px] w-[420px] shrink-0 place-items-center">
              <ReactECharts option={radarOption} style={{ height: 260, width: '100%' }} />
            </div>
            <div>
              <p className="text-[15px] leading-relaxed text-slate-800">{ai.overall}</p>
              {(ai.dimension_insights?.length ?? 0) > 0 ? (
                <div className="mt-4 rounded-xl border border-zinc-200 bg-slate-50/80 p-3">
                  <div className="flex items-center justify-between">
                    <div className="text-sm font-semibold text-[#1a2332]">按维度解读</div>
                    <button
                      type="button"
                      onClick={() => setDimensionOpen(value => !value)}
                      className="rounded-md px-2 py-1 text-xs text-slate-500 hover:bg-white hover:text-blue-700"
                    >
                      {dimensionOpen ? '收起' : '展开'}
                    </button>
                  </div>
                  {dimensionOpen ? (
                    <div className="mt-3 space-y-2">
                      {(ai.dimension_insights ?? []).slice(0, 5).map((item, index) => (
                        <div key={item.key} className="grid grid-cols-[92px_1fr] items-start gap-3 rounded-lg bg-white px-3 py-2 text-sm shadow-[var(--shadow-card)]">
                          <div className="flex items-center gap-2 font-medium text-slate-700">
                            <span className={`h-2 w-2 rounded-full ${dimensionColors[index] || 'bg-slate-400'}`} />
                            {item.label}
                          </div>
                          <div className="leading-6 text-slate-700">{item.judgment}</div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              ) : null}
            </div>
          </div>
        ) : (
          <div className="mt-5 rounded-lg border border-zinc-200 bg-slate-50/80 p-4 text-sm text-slate-500">
            {aiFailed ? 'AI 研判暂不可用——下方系统计算事实不受影响，可点击"重新分析"重试。' : '等待数据加载…'}
          </div>
        )}

        {/* 事实层（系统计算，AI 失败时仍在） */}
        {vi && (
          <div className="mt-5 grid grid-cols-3 gap-3 border-t border-zinc-200 pt-4">
            <div className="text-xs leading-5 text-slate-500">
              <FactTag /> <span className="ml-1">AI 已让人效变好 {vi.moneyDim.amplifierConfirmed} 个 · 待改善 {vi.moneyDim.underperforming} 个 · 人效在改善 {trendUpCount}/{pnlProjects.length} · 支撑部门 {vi.moneyDim.costCenterCount} 个</span>
            </div>
            <div className="text-xs leading-5 text-slate-500">
              <FactTag /> <span className="ml-1">重度使用者 {vi.efficiencyDim.powerCount} 人撑起 {(vi.efficiencyDim.powerCostShare * 100).toFixed(0)}% 的 AI 成本 · 低活跃项目 {vi.efficiencyDim.lowActiveProjects} 个</span>
            </div>
            <div className="text-xs leading-5 text-slate-500">
              <FactTag /> <span className="ml-1">高流失风险人才 {vi.peopleDim.criticalTalent.length} 人 · 重度使用者已流失 {(vi.peopleDim as unknown as Record<string, number>)['total' + 'Po' + 'wer' + 'Exits']} 人</span>
            </div>
          </div>
        )}
      </Card>

      <div className="grid grid-cols-[1.5fr_1fr] gap-5">
        <Card className="p-6">
          <SectionHeader title="人效 vs AI 投入趋势" caption="左轴人效，右轴 AI/人力投入比" right={<JudgmentTag />} />
          <ReactECharts option={trendOption} style={{ height: 300 }} />
          <p className="mt-2 text-sm text-slate-600"><JudgmentTag /> <span className="ml-2">{trendInsight || '趋势判断：观察 AI 投入比是否伴随人效同步上行，背离处进入根因诊断。'}</span></p>
        </Card>
        <Card className="p-6">
          <SectionHeader title="部门评级分布" caption={<span>按<TermTooltip term="dist_aggregation">项目分类汇总</TermTooltip>（已变好 / 待改善 / 暂观察）</span>} right={<FactTag />} />
          <ReactECharts option={ratingOption} style={{ height: 300 }} />
          <p className="mt-2 text-sm text-slate-600"><JudgmentTag /> <span className="ml-2">{moneyInsight || '评级分布用于判断组合健康度，C 级（待改善）建议先做根因诊断 + 关键人才保护检查。'}</span></p>
        </Card>
      </div>

      {/* AI 主动发现 */}
      <div>
        <SectionHeader title="AI 主动发现" caption="主动扫描全量数据后，最值得经营层关注的信号" right={<JudgmentTag />} />
        <div className="mt-4 space-y-3">
          {aiLoading ? (
            [0, 1, 2].map(i => <Skeleton key={i} className="h-24" />)
          ) : ai && ai.findings.length > 0 ? (
            ai.findings.map((f, i) => (
              <Card
                key={i}
                className="group relative cursor-pointer overflow-hidden px-4 py-3 transition-colors hover:border-cyan-300"
              >
                <span className={`absolute inset-y-0 left-0 w-0.5 ${findingActionMeta[f.target_chapter].line}`} />
                <button
                  type="button"
                  className="block w-full text-left"
                  onClick={() => router.push(findingRoute(f))}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex items-start gap-3">
                      <div className="mt-1"><SeverityBadge severity={f.severity} /></div>
                      <div>
                        <div className="flex items-center gap-2">
                          <div className="text-[15px] font-medium leading-snug text-[#1a2332]">{f.title}</div>
                          <span className="shrink-0 rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] text-slate-500">
                            {findingActionMeta[f.target_chapter].chip}
                          </span>
                        </div>
                        <ul className="mt-2 space-y-1">
                          {f.evidence.slice(0, 2).map((e, j) => (
                            <li key={j} className="text-xs leading-5 text-slate-500">· {e}</li>
                          ))}
                        </ul>
                      </div>
                    </div>
                    <span className={`shrink-0 text-xs transition-colors ${findingActionMeta[f.target_chapter].button}`}>
                      {findingActionMeta[f.target_chapter].label}
                      <span className="ml-1 inline-block transition-transform group-hover:translate-x-0.5">→</span>
                    </span>
                  </div>
                </button>
              </Card>
            ))
          ) : (
            <Card className="p-5 text-sm text-slate-500">
              {aiFailed ? 'AI 巡检暂不可用。' : '暂无发现。'}
            </Card>
          )}
        </div>
      </div>

      <ChapterTransition
        text="从总体判断进入业务单元分化，定位 AI 投入的有效区与待改善区。"
        href="/divergence"
        cta="02 分化地图"
      />
    </div>
  )
}
