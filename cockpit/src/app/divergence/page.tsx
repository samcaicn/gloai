'use client'

import dynamic from 'next/dynamic'
import { useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'
import {
  getDependencyFragility,
  getLeverageMatrix,
  getPricingMismatch,
  LeveragePoint,
} from '@/lib/analytics'
import { formatProductivity, formatRatio, formatWan } from '@/lib/format'
import { Card, SectionHeader, FactTag, JudgmentTag, ChapterTransition, Skeleton, CockpitTopbar, AiBriefing } from '@/components/ui'
import { TermTooltip } from '@/components/TermTooltip'
import { MarkdownContent } from '@/components/MarkdownContent'

const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false })

const verdictMeta: Record<LeveragePoint['verdict'], { label: string; color: string }> = {
  amplifier_confirmed: { label: 'AI 已让人效变好', color: '#0891b2' },
  amplifier_unproven: { label: '效果待验证', color: '#06b6d4' },
  underperforming: { label: '待改善', color: '#dc2626' },
  high_potential: { label: '待加码', color: '#2563eb' },
  low_base: { label: '基础区', color: '#64748b' },
  support: { label: '支撑部门', color: '#94a3b8' },
}

const models = ['Claude Opus', 'Claude Sonnet', 'GPT', 'Cursor/IDE', 'Mivo', '其他']
const modelColors = ['#dc2626', '#0891b2', '#2563eb', '#7c3aed', '#d97706', '#64748b']
// 主流业务角色名单——分析洞察以这些为主，"其他/未分类"等聚合类别仅做补充观察
const FALLBACK_ROLE_LABELS = ['其他', '未分类', '杂项', 'Other', 'other', 'Misc', 'misc']
const isFallbackRole = (role: string) => FALLBACK_ROLE_LABELS.includes(role)

function jitter(seed: string, range = 0.02) {
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  return ((hash % 1000) / 1000 - 0.5) * range
}

export default function DivergencePage() {
  const router = useRouter()
  const { projects, monthlyTrend, talentRisk, roleMatrix, isLoading } = useAppData()

  const leverage = useMemo(
    () => (projects.length > 0 ? getLeverageMatrix(projects, monthlyTrend) : null),
    [projects, monthlyTrend]
  )
  const pricing = useMemo(() => getPricingMismatch(talentRisk), [talentRisk])
  const fragility = useMemo(() => getDependencyFragility(talentRisk, projects), [talentRisk, projects])
  const projectName = useMemo(() => new Map(projects.map(project => [project.id, project.name])), [projects])
  const roleCells = useMemo(() => roleMatrix || [], [roleMatrix])
  const roleNames = useMemo(() => Array.from(new Set(roleCells.map(cell => cell.role))), [roleCells])
  const roleMix = useMemo(() => roleNames.map(role => {
    const cells = roleCells.filter(cell => cell.role === role)
    const totals = Object.fromEntries(models.map(model => [model, 0]))
    let weight = 0
    cells.forEach(cell => {
      const cellWeight = Math.max(cell.ai_cost, 1)
      weight += cellWeight
      models.forEach(model => { totals[model] += (cell.model_mix?.[model] || 0) * cellWeight })
    })
    return Object.fromEntries(models.map(model => [model, weight > 0 ? totals[model] / weight : 0]))
  }), [roleCells, roleNames])
  const leverageInsight = useMemo(() => {
    if (!leverage) return ''
    const improved = leverage.counts.amplifier_confirmed
    const highSpendLowReturn = leverage.counts.underperforming
    const highPotential = leverage.counts.high_potential
    return improved <= 1
      ? `右下高投入低人效区有 ${highSpendLowReturn} 个气泡；高投入且已变好仅 ${improved} 个，多数投入还没转成人效。`
      : `右上已变好项目 ${improved} 个，左上待加码 ${highPotential} 个；右下高投入低人效区是攻坚重点。`
  }, [leverage])
  const roleModelInsight = useMemo(() => {
    const opusByRole = roleNames
      .map((role, index) => ({
        role,
        opus: Number(roleMix[index]?.['Claude Opus'] || 0),
      }))
      .filter(item => !isFallbackRole(item.role))
    const sorted = [...opusByRole].sort((a, b) => b.opus - a.opus)
    const top = sorted[0]
    const bottom = sorted[sorted.length - 1]
    return top && bottom
      ? `${top.role} 的 Opus 占比 ${Math.round(top.opus * 100)}%，远高于 ${bottom.role} 的 ${Math.round(bottom.opus * 100)}%；高价模型在主流角色间分布不均。`
      : '模型结构暂无足够角色数据。'
  }, [roleMix, roleNames])
  const heatmapInsight = useMemo(() => {
    const mainstreamCells = roleCells.filter(cell => !isFallbackRole(cell.role))
    if (mainstreamCells.length === 0) return '热力图暂无足够岗位数据。'
    const topCells = [...mainstreamCells]
      .sort((a, b) => b.per_capita - a.per_capita)
      .slice(0, Math.max(3, Math.ceil(mainstreamCells.length * 0.1)))
    const counts = new Map<string, number>()
    topCells.forEach(cell => counts.set(cell.role, (counts.get(cell.role) || 0) + 1))
    const dominant = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]
    const sameRole = dominant ? mainstreamCells.filter(cell => cell.role === dominant[0] && cell.per_capita > 0) : []
    const max = Math.max(...sameRole.map(cell => cell.per_capita), 0)
    const min = Math.min(...sameRole.filter(cell => cell.per_capita > 0).map(cell => cell.per_capita), max || 1)
    const gap = min > 0 && max > 0 ? Math.max(1, max / min) : 1
    return dominant
      ? `主流角色中 ${dominant[0]} 占高投入热区 ${dominant[1]} 格，同岗位部门间人均 AI 投入最高相差 ${gap.toFixed(1)} 倍。`
      : '热力图暂无足够岗位数据。'
  }, [roleCells])
  const pricingInsight = useMemo(
    () => `右下红区 ${pricing.highUseLowPaid.length} 人高用低薪；左上 ${pricing.highPaidLowUse.length} 人薪酬高但 AI 用得少。`,
    [pricing]
  )
  const fragilityInsight = useMemo(
    () => `右下警戒区 ${fragility.fragileCount} 人：部门依赖高且薪酬位档低；三角形重度使用者优先保护。`,
    [fragility.fragileCount]
  )

  const leverageOption = useMemo(() => {
    if (!leverage) return {}
    const groups = new Map<LeveragePoint['verdict'], LeveragePoint[]>()
    leverage.points.forEach(point => groups.set(point.verdict, [...(groups.get(point.verdict) || []), point]))
    const maxHC = Math.max(...leverage.points.map(point => point.headcount), 1)
    const seriesItems = [...groups.entries()]
    const areaVerdict = seriesItems.find(([verdict]) => verdict !== 'support')?.[0]
    // 用 P10/P90 分位数防 outlier（如 labor=0 项目）拉伸坐标轴
    const pnlPoints = leverage.points.filter(p => p.verdict !== 'support')
    const yValues = pnlPoints.map(p => p.productivity).filter(v => Number.isFinite(v)).sort((a, b) => a - b)
    const yP10 = yValues.length > 0 ? yValues[Math.max(0, Math.floor(yValues.length * 0.1))] : -1
    const yP90 = yValues.length > 0 ? yValues[Math.min(yValues.length - 1, Math.ceil(yValues.length * 0.9))] : 5
    const yRange = Math.max(yP90 - yP10, 1)
    const yMin = Math.floor(yP10 - yRange * 0.35)
    const yMax = Math.ceil(yP90 + yRange * 0.35)
    const xValues = pnlPoints.map(p => p.ai_intensity).filter(v => Number.isFinite(v) && v > 0).sort((a, b) => a - b)
    const xP10 = xValues.length > 0 ? xValues[Math.max(0, Math.floor(xValues.length * 0.1))] : 0.01
    const xP90 = xValues.length > 0 ? xValues[Math.min(xValues.length - 1, Math.ceil(xValues.length * 0.9))] : 1
    const xMin = Math.max(xP10 * 0.4, 0.005)
    const xMax = xP90 * 2.5
    return {
      backgroundColor: 'transparent',
      grid: { top: 56, right: 40, bottom: 56, left: 64 },
      legend: {
        top: 0,
        left: 'left',
        textStyle: { color: '#475569', fontSize: 11 },
        itemWidth: 14,
        itemHeight: 8,
        data: seriesItems.map(([verdict, points]) => `${verdictMeta[verdict].label} (${points.length})`),
      },
      tooltip: {
        backgroundColor: '#ffffff',
        borderColor: '#cbd5e1',
        textStyle: { color: '#1a2332' },
        formatter: (params: { data: (number | string)[] }) => {
          const data = params.data
          return `<b>${data[3]}</b><br/>人效 ${formatProductivity(Number(data[1]))}<br/>AI投入强度 ${formatRatio(Number(data[0]))}<br/>人数 ${data[2]}`
        },
      },
      xAxis: { type: 'log', min: xMin, max: xMax, name: 'AI 投入强度', nameGap: 28, axisLabel: { color: '#475569', formatter: (v: number) => formatRatio(v) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      yAxis: { name: '人效', min: yMin, max: yMax, nameGap: 36, interval: Math.max(1, Math.ceil((yMax - yMin) / 6)), axisLabel: { color: '#475569', formatter: (v: number) => v.toFixed(0) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      series: seriesItems.map(([verdict, points]) => ({
        name: `${verdictMeta[verdict].label} (${points.length})`,
        type: 'scatter',
        data: points.map(point => [point.ai_intensity, point.productivity, point.headcount, point.name, point.project_id, point.trend]),
        symbol: verdict === 'support' ? 'triangle' : 'circle',
        symbolSize: (value: number[]) => Math.max(12, Math.min(52, 12 + (value[2] / maxHC) * 42)),
        itemStyle: { color: verdictMeta[verdict].color, opacity: verdict === 'support' ? 0.4 : 0.9 },
        label: { show: false },
        emphasis: { label: { show: true, formatter: '{@[3]}', color: '#1a2332' } },
        markLine: verdict === 'underperforming' ? {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#475569', type: 'dashed' },
          label: { show: false },
          data: [{ xAxis: leverage.medianIntensity }, { yAxis: leverage.medianProductivity }],
        } : undefined,
        markArea: verdict === areaVerdict ? {
          silent: true,
          label: { color: '#64748b', fontSize: 10, position: 'insideTopLeft' },
          data: [
            [
              { name: '低投入·高人效', xAxis: 'min', yAxis: leverage.medianProductivity, itemStyle: { color: 'rgba(37,99,235,0.05)' } },
              { xAxis: leverage.medianIntensity, yAxis: 'max' },
            ],
            [
              { name: '高投入·高人效', xAxis: leverage.medianIntensity, yAxis: leverage.medianProductivity, itemStyle: { color: 'rgba(8,145,178,0.06)' } },
              { xAxis: 'max', yAxis: 'max' },
            ],
            [
              { name: '高投入·低人效', xAxis: leverage.medianIntensity, yAxis: 'min', itemStyle: { color: 'rgba(220,38,38,0.06)' } },
              { xAxis: 'max', yAxis: leverage.medianProductivity },
            ],
            [
              { name: '低投入·低人效', xAxis: 'min', yAxis: 'min', itemStyle: { color: 'rgba(100,116,139,0.05)' } },
              { xAxis: leverage.medianIntensity, yAxis: leverage.medianProductivity },
            ],
          ],
        } : undefined,
      })),
    }
  }, [leverage])

  const heatmapOption = useMemo(() => {
    const cells = roleCells
    const roles = Array.from(new Set(cells.map(cell => cell.role)))
    const departments = Array.from(new Set(cells.map(cell => projectName.get(cell.project_id) || cell.project_id)))
    const valuesAll = cells.map(cell => cell.per_capita).filter(v => Number.isFinite(v))
    const sorted = [...valuesAll].sort((a, b) => a - b)
    const quantile = (p: number) => sorted.length === 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]
    const visualMin = quantile(0.05)
    const visualMax = Math.max(quantile(0.9), visualMin + 1)
    const globalP90 = quantile(0.9)
    const roleP90 = new Map(roles.map(role => {
      const roleValues = cells
        .filter(cell => cell.role === role)
        .map(cell => cell.per_capita)
        .sort((a, b) => a - b)
      const p90 = roleValues.length === 0 ? 0 : roleValues[Math.min(roleValues.length - 1, Math.floor(roleValues.length * 0.9))]
      return [role, p90]
    }))
    const highP90Roles = new Set([...roleP90.entries()].filter(([, p90]) => p90 >= globalP90).map(([role]) => role))
    return {
      backgroundColor: 'transparent',
      grid: { top: 28, right: 24, bottom: 90, left: 100 },
      tooltip: {
        backgroundColor: '#ffffff',
        borderColor: '#cbd5e1',
        textStyle: { color: '#1a2332' },
        formatter: (params: { value: [number, number, number, number, number, string] }) => {
          const [x, y, value, headcount, active, projectId] = params.value
          return `<b>${departments[x]} · ${roles[y]}</b><br/>人均AI ${formatWan(value)}<br/>人数 ${headcount}<br/>活跃 ${active} 天<br/>${projectId}`
        },
      },
      xAxis: {
        type: 'category',
        data: departments,
        axisLabel: {
          color: '#475569',
          rotate: 35,
          fontSize: 11,
          interval: 0,
          formatter: (value: string) => value.length > 6 ? `${value.slice(0, 6)}...` : value,
        },
      },
      yAxis: {
        type: 'category',
        data: roles,
        axisLabel: {
          color: '#475569',
          fontSize: 12,
          interval: 0,
          formatter: (value: string) => highP90Roles.has(value) ? `{hot|${value} *}` : `{normal|${value}}`,
          rich: {
            hot: { color: '#b91c1c', fontSize: 12, fontWeight: 700 },
            normal: { color: '#475569', fontSize: 12 },
          },
        },
      },
      visualMap: {
        min: visualMin,
        max: visualMax,
        dimension: 2,
        show: true,
        orient: 'horizontal',
        bottom: 4,
        left: 'center',
        itemWidth: 10,
        itemHeight: 240,
        text: ['高', '低'],
        textStyle: { color: '#475569', fontSize: 10 },
        calculable: false,
        inRange: { color: ['#e0f2fe', '#7dd3fc', '#0891b2', '#155e75', '#b91c1c'] },
      },
      series: [{
        type: 'heatmap',
        itemStyle: { borderColor: 'rgba(148,163,184,0.35)', borderWidth: 1 },
        emphasis: { itemStyle: { borderColor: '#1a2332', borderWidth: 2 } },
        data: cells.flatMap(cell => {
          const x = departments.indexOf(projectName.get(cell.project_id) || cell.project_id)
          const y = roles.indexOf(cell.role)
          const isExtreme = cell.per_capita >= visualMax * 0.95
          return x >= 0 && y >= 0 ? [{
            value: [x, y, cell.per_capita, cell.headcount, cell.avg_active_days, cell.project_id],
            itemStyle: isExtreme ? {
              borderColor: '#ffffff',
              borderWidth: 2,
              shadowBlur: 4,
              shadowColor: 'rgba(185,28,28,0.4)',
            } : undefined,
          }] : []
        }),
      }],
    }
  }, [roleCells, projectName])

  const stackedOption = useMemo(() => {
    return {
      backgroundColor: 'transparent',
      grid: { top: 50, right: 12, bottom: 60, left: 50 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' } },
      legend: { top: 0, left: 'center', type: 'scroll', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 14, itemHeight: 8, itemGap: 12 },
      xAxis: {
        type: 'category',
        data: roleNames,
        axisLabel: {
          color: '#475569',
          rotate: 0,
          interval: 0,
          fontSize: 11,
          formatter: (value: string) => value.length > 4 ? value.slice(0, 4) : value,
        },
      },
      yAxis: { type: 'value', max: 1, axisLabel: { color: '#475569', formatter: (v: number) => `${Math.round(v * 100)}%` }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      series: models.map((model, index) => ({
        name: model,
        type: 'bar',
        stack: 'model',
        data: roleMix.map(mix => Number(mix[model] || 0)),
        itemStyle: { color: modelColors[index] },
        label: model === 'Claude Opus' ? {
          show: true,
          position: 'insideTop',
          formatter: (params: { value: number }) => params.value >= 0.3 ? `${Math.round(params.value * 100)}%` : '',
          color: '#ffffff',
          fontSize: 11,
          fontWeight: 600,
        } : undefined,
      })),
    }
  }, [roleMix, roleNames])

  const pricingOption = useMemo(() => {
    const rows = [
      ...pricing.highPaidLowUse.slice(0, 80).map(talent => [talent.ai_cost_ratio, talent.cr_value + jitter(talent.id), 8, talent.id, '高薪低用']),
      ...pricing.highUseLowPaid.slice(0, 80).map(talent => [talent.ai_cost_ratio, talent.cr_value + jitter(talent.id), 14, talent.id, '高用低薪']),
    ]
    const xValues = rows.map(row => Number(row[0])).filter(value => Number.isFinite(value))
    const useLogXAxis = xValues.length > 0 && Math.min(...xValues) > 0
    const xMin = useLogXAxis ? 0.01 : 0
    return {
      backgroundColor: 'transparent',
      grid: { top: 24, right: 24, bottom: 42, left: 50 },
      tooltip: { backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' }, formatter: (params: { data: (number | string)[] }) => `<b>${params.data[3]}</b><br/>${params.data[4]}<br/>AI/薪酬 ${formatRatio(Number(params.data[0]))}<br/>薪酬位档 ${Number(params.data[1]).toFixed(2)}` },
      xAxis: { type: useLogXAxis ? 'log' : 'value', logBase: 10, min: useLogXAxis ? 0.01 : undefined, name: 'AI/薪酬', axisLabel: { color: '#475569', formatter: (v: number) => formatRatio(v) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      yAxis: { name: '薪酬位档', min: 0.5, max: 1.7, axisLabel: { color: '#475569' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      series: [{
        type: 'scatter',
        data: rows,
        symbolSize: (value: number[]) => value[2],
        itemStyle: { color: (params: { data: (number | string)[] }) => params.data[4] === '高用低薪' ? '#dc2626' : '#d97706', opacity: 0.5 },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#94a3b8', type: 'dashed' },
          label: { color: '#64748b', fontSize: 10 },
          data: [{ yAxis: 1 }, { xAxis: 0.5 }, { xAxis: 1 }],
        },
        markArea: {
          silent: true,
          label: { color: '#64748b', fontSize: 11, position: 'insideTopLeft' },
          data: [
            [
              { name: '高用低薪', xAxis: 1, yAxis: 0.5, itemStyle: { color: 'rgba(220,38,38,0.06)' } },
              { xAxis: 'max', yAxis: 0.9 },
            ],
            [
              { name: '高薪低用', xAxis: xMin, yAxis: 1.16, itemStyle: { color: 'rgba(217,119,6,0.06)' } },
              { xAxis: 0.5, yAxis: 'max' },
            ],
          ],
        },
      }],
    }
  }, [pricing])

  const fragilityOption = useMemo(() => {
    const points = fragility.points.slice(0, 360).map(point => [point.deptShare, point.cr + jitter(point.id, 0.015), point.tier === 'power' ? 10 : 6, point.id, point.project_id, point.fragile, point.tier])
    const powerPoints = points.filter(point => point[6] === 'power')
    const regularPoints = points.filter(point => point[6] !== 'power')
    return {
      backgroundColor: 'transparent',
      grid: { top: 40, right: 24, bottom: 42, left: 50 },
      legend: { top: 0, left: 'center', textStyle: { color: '#475569', fontSize: 11 }, itemWidth: 14, itemHeight: 8 },
      tooltip: { backgroundColor: '#ffffff', borderColor: '#cbd5e1', textStyle: { color: '#1a2332' }, formatter: (params: { data: (number | string | boolean)[] }) => `<b>${params.data[3]}</b><br/>部门占比 ${formatRatio(Number(params.data[0]))}<br/>薪酬位档 ${Number(params.data[1]).toFixed(2)}<br/>${projectName.get(String(params.data[4])) || params.data[4]}` },
      xAxis: { name: '个人占部门AI比', max: 1, axisLabel: { color: '#475569', formatter: (v: number) => formatRatio(v) }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      yAxis: { name: '薪酬位档', min: 0.5, max: 1.7, axisLabel: { color: '#475569' }, splitLine: { lineStyle: { color: 'rgba(203,213,225,0.65)' } } },
      series: [
        {
          name: '普通使用者',
          type: 'scatter',
          symbol: 'circle',
          data: regularPoints,
          symbolSize: (value: number[]) => value[2],
          itemStyle: { color: '#0891b2', opacity: 0.5 },
          markArea: {
            silent: true,
            label: { position: 'insideTopLeft', formatter: `警戒：${fragility.fragileCount} 人`, color: '#dc2626', fontSize: 11 },
            itemStyle: { color: 'rgba(220,38,38,0.14)', borderColor: '#dc2626', borderType: 'dashed', borderWidth: 1 },
            data: [[{ xAxis: 0.1, yAxis: 0.5 }, { xAxis: 1, yAxis: 0.9 }]],
          },
        },
        {
          name: '重度使用者',
          type: 'scatter',
          symbol: 'triangle',
          data: powerPoints,
          symbolSize: (value: number[]) => value[2],
          itemStyle: { color: '#dc2626', opacity: 0.5 },
        },
      ],
    }
  }, [fragility, projectName])

  return (
    <div className="w-full space-y-6 px-8 pb-24 pt-8">
      <CockpitTopbar />
      <header>
        <h1 className="text-[30px] font-semibold leading-tight text-[#1a2332]">钱花在哪成了，哪没成？</h1>
        <p className="mt-1.5 text-sm text-slate-500">以五张交叉图定位 AI 投入、人效、岗位、模型与人才定价之间的分化。</p>
      </header>
      <AiBriefing title="分化洞察" prompt="用一句话整体概括 27 个业务单元在 AI 投入与人效上的分化格局——聚焦在几类项目分布的整体规律上（哪类多、哪类少、整体走向如何），不要只挑某 1-2 个项目说" />

      {isLoading || !leverage ? (
        <Skeleton className="h-[620px]" />
      ) : (
        <>
          <section className="grid grid-cols-12 gap-5">
            <Card className="col-span-7 p-5">
              <SectionHeader title={<TermTooltip term="leverage_matrix">项目分布矩阵</TermTooltip>} caption="X=AI投入强度，Y=人效，气泡=人数；支撑部门以灰色三角显示，不参与象限分类" right={<FactTag />} />
              <ReactECharts option={leverageOption} style={{ height: 420 }} onEvents={{ click: (params: { data?: (number | string)[] }) => {
                const id = params.data?.[4]
                if (typeof id === 'string') router.push(`/attribution?id=${id}`)
              } }} />
              <Insight text={leverageInsight} />
            </Card>
            <Card className="col-span-5 p-5">
              <SectionHeader title="角色 × 模型成本结构" caption="X=角色，Y=各模型成本占比，颜色=模型类型" right={<FactTag />} />
              <ReactECharts option={stackedOption} style={{ height: 460 }} />
              <Insight text={roleModelInsight} />
            </Card>
          </section>

          <section>
            <Card className="p-5">
              <SectionHeader title="岗位×部门 AI 投入热力图" caption={<span>颜色越深 = 同岗位人均 AI 投入越高，红色 = 异常高值；重点看<TermTooltip term="role_gap">同岗位人效差距（倍）</TermTooltip></span>} right={<FactTag />} />
              <ReactECharts option={heatmapOption} style={{ height: 480 }} />
              <Insight text={heatmapInsight} />
            </Card>
          </section>

          <section className="grid grid-cols-2 gap-5">
            <Card className="p-5">
              <SectionHeader title="AI 投入 × 薪酬位档分布" caption="X=AI成本/薪酬，Y=薪酬位档，气泡颜色=高薪低用或高用低薪" right={<FactTag />} />
              <ReactECharts option={pricingOption} style={{ height: 380 }} />
              <Insight text={pricingInsight} />
            </Card>
            <Card className="p-5">
              <SectionHeader title="员工部门依赖 × 薪酬位档分布" caption="X=个人占部门 AI 成本比例，Y=薪酬位档，形状=使用深度" right={<FactTag />} />
              <ReactECharts option={fragilityOption} style={{ height: 380 }} />
              <Insight text={fragilityInsight} />
            </Card>
          </section>

          <DivergenceSynthesis />
        </>
      )}

      <ChapterTransition
        text="从分化地图进入根因诊断，解释高投入未转化为人效的原因。"
        href="/attribution"
        cta="03 根因诊断"
      />
    </div>
  )
}

function Insight({ text }: { text: string }) {
  return (
    <p className="mt-2 rounded-lg border border-cyan-400/15 bg-cyan-400/5 px-3 py-2 text-sm leading-6 text-slate-600">
      <JudgmentTag /> <span className="ml-2">{text.slice(0, 80)}</span>
    </p>
  )
}

function DivergenceSynthesis() {
  const storageKey = 'divergence-synthesis-v2'
  const fallback = '分化研判：先看右下高投入低人效气泡，再看高价模型集中角色；涉及部门依赖高且薪酬位档低的人才，先做关键人才保护检查。'
  const [answer, setAnswer] = useState('')
  const [isLoading, setIsLoading] = useState(true)

  const runAnalysis = async (force = false) => {
    setIsLoading(true)
    try {
      if (!force) {
        const cached = sessionStorage.getItem(storageKey)
        if (cached) {
          setAnswer(cached)
          setIsLoading(false)
          return
        }
      }
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mode: 'drilldown',
          message: '基于分化地图的 5 张图，给出 2-3 句综合研判，覆盖：① 哪个象限最集中、② 哪个角色或模型最异常、③ 优先该做什么。',
          page: '/divergence',
          chapter: 'divergence',
        }),
      })
      const json = await res.json()
      const next = json.data?.answer || json.answer || fallback
      setAnswer(next)
      sessionStorage.setItem(storageKey, next)
    } catch {
      setAnswer(fallback)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    runAnalysis()
  }, [])

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 flex-1 items-start gap-3">
          <JudgmentTag />
          <div className="min-w-0">
            <div className="text-sm font-semibold text-slate-900">AI 综合研判</div>
            {isLoading ? (
              <div className="mt-3 space-y-2">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-4/5" />
              </div>
            ) : (
              <div className="mt-2 text-[15px] leading-7 text-slate-800">
                <MarkdownContent content={answer || fallback} />
              </div>
            )}
          </div>
        </div>
        <button
          type="button"
          onClick={() => runAnalysis(true)}
          className="shrink-0 rounded-lg border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-700 transition hover:border-blue-400 hover:bg-blue-100"
        >
          重新分析
        </button>
      </div>
    </Card>
  )
}
