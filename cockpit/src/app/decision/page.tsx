'use client'

import dynamic from 'next/dynamic'
import { Suspense, useMemo, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'
import { getCriticalTalentList } from '@/lib/analytics'
import { DecisionResponse } from '@/lib/aiSchemas'
import {
  Card, SectionHeader, FactTag, JudgmentTag, Skeleton, CockpitTopbar, AiBriefing,
} from '@/components/ui'
import { TermTooltip } from '@/components/TermTooltip'

const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })

function DecisionInner() {
  const params = useSearchParams()
  const { projects, talentRisk, companySummary, isLoading } = useAppData()
  const [ai, setAi] = useState<DecisionResponse | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [activeIntent, setActiveIntent] = useState('')
  const [freeInput, setFreeInput] = useState('')

  const fromProject = params.get('from')
  const cause = params.get('cause')
  const fromName = projects.find(p => p.id === fromProject)?.name

  const critical = useMemo(
    () => (projects.length > 0 ? getCriticalTalentList(talentRisk, projects) : []),
    [projects, talentRisk]
  )
  const groupedCritical = useMemo(() => {
    const m = new Map<string, { name: string; count: number; minCr: number; avgCr: number; powerCount: number; projectId?: string }>()
    critical.forEach(item => {
      const key = item.project_name
      const cr = Number.isFinite(item.cr) ? item.cr : 0.5
      const prev = m.get(key)
      if (!prev) {
        m.set(key, { name: key, count: 1, minCr: cr, avgCr: cr, powerCount: 1, projectId: item.project_id })
      } else {
        prev.count += 1
        prev.minCr = Math.min(prev.minCr, cr)
        prev.avgCr = (prev.avgCr * (prev.count - 1) + cr) / prev.count
        prev.powerCount += 1
      }
    })
    return [...m.values()].sort((a, b) => b.count - a.count)
  }, [critical])
  const guardOption = useMemo(() => ({
    backgroundColor: 'transparent',
    grid: { top: 16, right: 110, bottom: 24, left: 112 },
    tooltip: {
      backgroundColor: '#ffffff',
      borderColor: '#cbd5e1',
      textStyle: { color: '#1a2332' },
      formatter: (params: { dataIndex: number }) => {
        const item = groupedCritical[params.dataIndex]
        if (!item) return ''
        return `<b>${item.name}</b><br/>保人数 ${item.count}<br/>平均薪酬位档 ${item.avgCr.toFixed(2)}<br/>最低薪酬位档 ${item.minCr.toFixed(2)}<br/>其中重度使用者 ${item.powerCount}`
      },
    },
    yAxis: {
      type: 'category',
      data: groupedCritical.map(item => item.name),
      inverse: true,
      axisLabel: { color: '#475569', fontSize: 12 },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    xAxis: {
      type: 'value',
      name: '保人数',
      axisLabel: { color: '#475569', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(203,213,225,0.6)' } },
    },
    series: [{
      type: 'bar',
      barWidth: '60%',
      data: groupedCritical.map(item => ({
        value: item.count,
        itemStyle: {
          color: item.minCr < 0.65 ? '#dc2626' : item.minCr < 0.8 ? '#d97706' : '#eab308',
          borderRadius: [0, 4, 4, 0],
        },
      })),
      label: {
        show: true,
        position: 'right',
        color: '#475569',
        fontSize: 11,
        formatter: (params: { dataIndex: number }) => {
          const item = groupedCritical[params.dataIndex]
          return item ? `${item.count} 人 · 最低位档 ${item.minCr.toFixed(2)}` : ''
        },
      },
    }],
  }), [groupedCritical])
  const guardChartHeight = Math.max(280, groupedCritical.length * 40)

  const scenarios = useMemo(() => {
    const list: { label: string; intent: string }[] = []
    if (fromName) {
      list.push({
        label: `基于根因为 ${fromName} 出方案`,
        intent: `针对 ${fromName} 的诊断根因（${cause || '见根因诊断'}），制定提升 AI 使用效果的可执行方案`,
      })
    }
    list.push(
      { label: '提升待改善项目 AI 效果', intent: '针对 AI 投入高但人效未改善的项目，制定提升 AI 使用效果的方案' },
      { label: '待加码项目 AI 加码', intent: '为人效好但 AI 使用普及度低的项目制定加码 AI 的策略；如果给它们的 AI 预算翻倍，用已让人效变好的项目经验推演预期回报' },
      { label: '方法迁移：标杆 → 落后', intent: '把已让人效变好的项目使用方法迁移到同岗位差距最大的落后部门，给出迁移方案' },
    )
    return list.slice(0, 4)
  }, [fromName, cause])

  async function runDecision(intent: string) {
    if (aiLoading) return
    setActiveIntent(intent)
    setAi(null)
    setAiLoading(true)
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'decision', message: intent }),
      })
      const json = await res.json()
      if (json.data?.action_cards) setAi(json.data)
    } catch { /* ai stays null */ }
    setAiLoading(false)
  }

  return (
    <div className="w-full space-y-6 px-8 pb-24 pt-8">
      <CockpitTopbar />
      <header>
        <h1 className="text-[28px] font-semibold leading-tight text-[#1a2332]">钱和人，接下来怎么投？</h1>
        <p className="mt-1.5 text-sm text-slate-500">
          AI 基于诊断证据生成可执行方案——每张行动卡都有参考标杆项目与验证方法，触及关键人才时自动亮风险提示。
        </p>
      </header>
      <AiBriefing title="推演洞察" prompt="用一句话整体概括公司当前 AI 投入分布该如何重新调配——聚焦在整体格局上（哪类项目应该加码、哪类该护住、哪类该改善 / 关键人才保护的整体压力如何），不要只挑某 1-2 个项目说，要给经营层一句决策方向" />

      {/* 保人名单（事实层，始终在场） */}
      <Card className="border-amber-500/25 p-5">
        <SectionHeader
          title={`人才护栏 · 保人名单 ${critical.length} 人`}
          caption="重度使用者 × 薪酬位档偏低（代理口径）× 团队流失环境——任何方案动到他们都会被拦截"
          right={<FactTag />}
        />
        {isLoading ? (
          <Skeleton className="mt-3 h-64" />
        ) : critical.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">当前数据未识别保人名单，方案推演不会触发关键人才保护检查。</p>
        ) : (
          <div className="mt-4">
            <ReactECharts option={guardOption} style={{ height: guardChartHeight }} />
            <p className="pt-2 text-xs leading-5 text-slate-500"><JudgmentTag /> <span className="ml-1">任何预算动作触达这些人群时，方案必须优先保护额度与激励。</span></p>
          </div>
        )}
      </Card>

      {/* 场景入口 */}
      <div>
        <SectionHeader title="场景推演（结构化方案生成）" caption={<span>这里用于生成行动卡与<TermTooltip term="talent_guardrail_check">关键人才保护检查</TermTooltip>；自由追问请使用右侧 AI 对话坞</span>} />
        <div className="mt-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
          {scenarios.map(s => (
            <button
              key={s.label}
              type="button"
              onClick={() => runDecision(s.intent)}
              disabled={aiLoading}
              className={`rounded-xl border p-4 text-left text-sm transition-all disabled:opacity-50 ${
                activeIntent === s.intent
                  ? 'border-blue-500/60 bg-blue-500/10 text-blue-700'
                  : 'border-zinc-200 bg-white/70 text-slate-700 hover:border-blue-500/40'
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
        <div className="mt-3 flex gap-2">
          <input
            value={freeInput}
            onChange={e => setFreeInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && freeInput.trim()) runDecision(freeInput.trim()) }}
            placeholder="描述推演场景：例如「在不动核心人才的前提下，把低效 AI 投入转投待加码项目」…"
            className="h-11 flex-1 rounded-lg border border-zinc-200 bg-white px-4 text-sm text-slate-900 outline-none placeholder:text-slate-500 focus:border-blue-500"
          />
          <button
            type="button"
            onClick={() => freeInput.trim() && runDecision(freeInput.trim())}
            disabled={aiLoading || !freeInput.trim()}
            className="h-11 rounded-lg bg-blue-500 px-6 text-sm font-medium text-white transition-colors hover:bg-blue-400 disabled:opacity-40"
          >
            推演
          </button>
        </div>
      </div>

      {/* 结果区 */}
      {aiLoading ? (
        <Card className="p-6">
          <div className="flex items-center gap-2 text-sm text-blue-700">
            <span className="h-2 w-2 animate-pulse rounded-full bg-blue-400" />
            AI 正在生成方案（检索参考标杆项目 · 做关键人才保护检查 · 计算预期收益）…
          </div>
          <div className="mt-5 grid grid-cols-3 gap-4">
            {[0, 1, 2].map(i => <Skeleton key={i} className="h-56" />)}
          </div>
        </Card>
      ) : ai ? (
        <div className="space-y-5">
          {/* 概述 + 护栏命中 */}
          <Card className="p-5">
            <div className="flex items-start justify-between gap-6">
              <p className="text-[15px] leading-relaxed text-slate-900">{ai.summary}</p>
              <div className="flex shrink-0 items-center gap-2">
                <JudgmentTag />
                <button
                  type="button"
                  onClick={() => window.print()}
                  className="rounded-md border border-zinc-200 px-3 py-1.5 text-xs text-slate-600 transition-colors hover:border-blue-500/50 hover:text-blue-700"
                >
                  导出一页纸
                </button>
              </div>
            </div>
            {ai.guardrail_hits.length > 0 && (
              <div className="mt-4 rounded-lg border border-amber-500/40 bg-amber-500/[0.08] px-4 py-3">
                <div className="text-sm font-medium text-amber-700">关键人才保护检查命中</div>
                <ul className="mt-1.5 space-y-1">
                  {ai.guardrail_hits.map((h, i) => {
                    const name = projects.find(p => p.id === h.project_id)?.name || h.project_id
                    return (
                      <li key={i} className="text-xs leading-5 text-amber-700">
                        {name}：涉及 {h.count} 名保人名单成员——{h.note}
                      </li>
                    )
                  })}
                </ul>
              </div>
            )}
          </Card>

          {/* 行动卡组 */}
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            {ai.action_cards.map((c, i) => (
              <Card key={i} className={`flex flex-col p-5 ${c.guardrail ? 'border-amber-500/30' : ''}`}>
                <div className="flex items-start justify-between gap-2">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-md bg-blue-500/15 text-[11px] font-bold text-blue-700">
                    {i + 1}
                  </span>
                  {c.guardrail && (
                    <span className="rounded border border-amber-500/40 bg-amber-500/10 px-1.5 py-0.5 text-[10px] text-amber-700">护栏</span>
                  )}
                </div>
                <p className="mt-3 whitespace-normal break-words text-sm font-medium leading-snug text-slate-900">{c.action}</p>
                <dl className="mt-4 flex-1 space-y-3 border-t border-zinc-200/80 pt-3 text-xs leading-5">
                  <div><dt className="text-slate-500"><TermTooltip term="coverage_scope">覆盖范围</TermTooltip></dt><dd className="whitespace-normal break-words text-pretty text-slate-700">{c.scope}</dd></div>
                  <div><dt className="text-slate-500"><TermTooltip term="expected_value">预期收益</TermTooltip></dt><dd className="whitespace-normal break-words text-pretty font-medium tabular-nums text-slate-800">{c.amount}</dd></div>
                  <RoiMiniBar amount={c.amount} />
                  <div><dt className="text-slate-500"><TermTooltip term="benchmark_project">参考标杆项目</TermTooltip></dt><dd className="whitespace-normal break-words text-pretty text-emerald-700">{c.benchmark}</dd></div>
                  <div><dt className="text-slate-500"><TermTooltip term="validation_method">怎么验证生效</TermTooltip></dt><dd className="whitespace-normal break-words text-pretty text-slate-700">{c.validation}</dd></div>
                  <div><dt className="text-slate-500">风险</dt><dd className="whitespace-normal break-words text-pretty text-slate-600">{c.risk}</dd></div>
                  {c.guardrail && (
                    <div className="rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2"><dt className="text-amber-600">人才保护提示</dt><dd className="whitespace-normal break-words text-pretty text-amber-700">{c.guardrail}</dd></div>
                  )}
                </dl>
              </Card>
            ))}
          </div>

          {/* 推演（如有） */}
          {ai.simulation && (
            <Card className="border-blue-500/25 bg-gradient-to-br from-blue-500/[0.06] to-transparent p-5">
              <SectionHeader title="推演对比" caption="前后对比图 + AI 推演研判" right={<JudgmentTag />} />
              <div className="mt-3 grid grid-cols-[1fr_1fr] gap-5">
                <SimulationChart
                  avgProductivity={companySummary.avg_productivity}
                  aiToLaborRatio={companySummary.ai_to_labor_ratio}
                  criticalCount={critical.length}
                  actionCards={ai.action_cards}
                  guardrailHits={ai.guardrail_hits}
                />
                <div>
                  <p className="whitespace-pre-line text-sm leading-relaxed text-slate-800">{ai.simulation}</p>
                  {(ai.simulation_dimensions?.length ?? 0) > 0 ? (
                    <div className="mt-3 space-y-2">
                      {(ai.simulation_dimensions ?? []).slice(0, 4).map(item => (
                        <div key={item.key} className="rounded-lg border border-blue-100 bg-blue-50 px-3 py-2 text-sm">
                          <span className="font-medium text-blue-900">{item.label}：</span>
                          <span className="text-blue-800">{item.judgment}</span>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>
            </Card>
          )}
        </div>
      ) : (
        <Card className="flex h-56 items-center justify-center">
          <div className="text-center">
            <div className="text-base font-medium text-slate-600">选择场景开始推演</div>
            <p className="mt-2 max-w-md text-xs leading-5 text-slate-500">
              AI 将输出 2-3 张行动卡：动作 · 覆盖范围 · 预期收益 · 参考标杆项目 · 怎么验证生效 · 关键人才保护检查，全部基于真实数据实时生成
            </p>
          </div>
        </Card>
      )}
    </div>
  )
}

function parseAmount(amount: string) {
  const match = amount.match(/(\d+(\.\d+)?)/)
  return match ? Number(match[1]) : 0
}

function RoiMiniBar({ amount }: { amount: string }) {
  const value = parseAmount(amount)
  const width = Math.max(8, Math.min(100, value))
  return (
    <div>
      <dt className="text-slate-500">ROI 预期</dt>
      <dd className="mt-1 h-2 overflow-hidden rounded-full bg-slate-100 whitespace-normal break-words text-pretty">
        <span className="block h-full rounded-full bg-cyan-400" style={{ width: `${width}%` }} />
      </dd>
    </div>
  )
}

function SimulationChart({
  avgProductivity,
  aiToLaborRatio,
  criticalCount,
  actionCards,
  guardrailHits,
}: {
  avgProductivity: number
  aiToLaborRatio: number
  criticalCount: number
  actionCards: DecisionResponse['action_cards']
  guardrailHits: DecisionResponse['guardrail_hits']
}) {
  const amountSignal = actionCards
    .map(card => parseAmount(card.amount))
    .filter(value => value > 0)
  const avgSignal = amountSignal.length
    ? amountSignal.reduce((sum, value) => sum + value, 0) / amountSignal.length
    : actionCards.length
  const liftRate = Math.min(0.18, Math.max(0.03, avgSignal / 1000))
  const protectedCount = guardrailHits.reduce((sum, hit) => sum + hit.count, 0)
  const current = [
    Number(avgProductivity.toFixed(2)),
    Number((aiToLaborRatio * 100).toFixed(2)),
    criticalCount,
  ]
  const projected = [
    Number((avgProductivity * (1 + liftRate)).toFixed(2)),
    Number((aiToLaborRatio * 100 * (1 + Math.min(0.2, actionCards.length * 0.04))).toFixed(2)),
    Math.max(0, criticalCount - protectedCount),
  ]
  const option = {
    backgroundColor: 'transparent',
    grid: { top: 24, right: 16, bottom: 34, left: 42 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#ffffff',
      borderColor: '#cbd5e1',
      textStyle: { color: '#1a2332' },
    },
    legend: { top: 0, textStyle: { color: '#475569' } },
    xAxis: { type: 'category', data: ['人效(×)', 'AI投入比(%)', '人才风险(人)'], axisLabel: { color: '#475569' } },
    yAxis: { type: 'value', axisLabel: { color: '#475569' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
    series: [
      { name: '当前', type: 'bar', data: current, itemStyle: { color: '#475569', borderRadius: [4, 4, 0, 0] } },
      { name: '推演后', type: 'bar', data: projected, itemStyle: { color: '#0891b2', borderRadius: [4, 4, 0, 0] } },
    ],
  }
  return <ReactECharts option={option} style={{ height: 260 }} />
}

export default function DecisionPage() {
  return (
    <Suspense fallback={<div className="p-8"><Skeleton className="h-40" /></div>}>
      <DecisionInner />
    </Suspense>
  )
}
