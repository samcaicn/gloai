'use client'

/**
 * ui.tsx — design tokens 基础组件（设计方案 v2.1 §6 视觉语言）
 * 规则：界面 chrome 只用 zinc + 蓝 accent；语义色仅用于严重度/评级；
 * 大数字 tabular-nums；统一卡片样式；无 emoji 图标。
 */

import { ReactNode, useEffect, useState } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'

// ── 卡片 ──
export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-xl border border-zinc-200/70 bg-white shadow-[var(--shadow-card)] backdrop-blur-sm ${className}`}>
      {children}
    </div>
  )
}

export function SectionHeader({ title, caption, right }: { title: ReactNode; caption?: ReactNode; right?: ReactNode }) {
  return (
    <div className="flex items-end justify-between">
      <div>
        <h2 className="text-lg font-semibold text-[#1a2332]">{title}</h2>
        {caption ? <p className="mt-0.5 text-sm text-slate-500">{caption}</p> : null}
      </div>
      {right}
    </div>
  )
}

// ── 大数字 ──
export function MetricValue({ value, units, tone = 'default' }: {
  value: string
  units?: string
  tone?: 'default' | 'good' | 'bad' | 'warn'
}) {
  const toneCls = tone === 'good' ? 'text-emerald-700' : tone === 'bad' ? 'text-red-700' : tone === 'warn' ? 'text-amber-700' : 'text-[#1a2332]'
  const len = value.replace(/[,.]/g, '').length
  const sizeCls = len >= 10 ? 'text-[22px]' : len >= 8 ? 'text-[28px]' : len >= 6 ? 'text-[34px]' : 'text-[40px]'
  const unitSizeCls = len >= 8 ? 'text-[14px]' : len >= 6 ? 'text-[18px]' : 'text-[20px]'
  return (
    <span className={`inline-flex max-w-full items-baseline gap-1 whitespace-nowrap font-bold leading-none tabular-nums ${toneCls}`}>
      <span className={sizeCls}>{value}</span>
      {units ? <span className={`${unitSizeCls} font-semibold text-slate-500`}>{units}</span> : null}
    </span>
  )
}

export function BigNumber({ label, value, units, sub, tone = 'default' }: {
  label: ReactNode; value: string; units?: string; sub?: ReactNode; tone?: 'default' | 'good' | 'bad' | 'warn'
}) {
  return (
    <Card className="min-w-[200px] px-6 py-5">
      <div className="text-xs uppercase tracking-[0.14em] text-slate-500">{label}</div>
      <div className="mt-2">
        <MetricValue value={value} units={units} tone={tone} />
      </div>
      {sub ? <div className="mt-2 text-sm text-slate-500">{sub}</div> : null}
    </Card>
  )
}

// ── 严重度 ──
export type Severity = 'high' | 'medium' | 'low' | 'none'

const SEV_META: Record<Severity, { dot: string; text: string; label: string }> = {
  high: { dot: 'bg-red-500', text: 'border-red-200 bg-red-50 text-red-700', label: '高' },
  medium: { dot: 'bg-amber-500', text: 'border-amber-200 bg-amber-50 text-amber-700', label: '中' },
  low: { dot: 'bg-zinc-400', text: 'border-zinc-200 bg-zinc-50 text-slate-500', label: '低' },
  none: { dot: 'bg-emerald-500', text: 'border-emerald-200 bg-emerald-50 text-emerald-700', label: '正常' },
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  const m = SEV_META[severity]
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium ${m.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${m.dot}`} />
      {m.label}
    </span>
  )
}

// ── 评级 ──
export function GradeBadge({ grade, label }: { grade: 'A' | 'B' | 'C'; label: string }) {
  const cls = grade === 'A'
    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
    : grade === 'B'
      ? 'border-amber-200 bg-amber-50 text-amber-700'
      : 'border-red-200 bg-red-50 text-red-700'
  return (
    <div className={`flex flex-col items-center rounded-xl border px-5 py-3 ${cls}`}>
      <span className="text-3xl font-bold leading-none">{grade}</span>
      <span className="mt-1.5 text-xs text-slate-500">{label}</span>
    </div>
  )
}

// ── 事实 vs 判断（双层视觉，v2.1 §9.1） ──
export function FactTag() {
  return <span className="inline-flex rounded border border-blue-200 bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-700 transition-all hover:-translate-y-px hover:shadow-[0_0_14px_rgba(37,99,235,0.14)]">系统计算</span>
}
export function JudgmentTag() {
  return <span className="inline-flex rounded border border-violet-200 bg-violet-50 px-1.5 py-0.5 text-[10px] text-violet-700 shadow-[0_0_14px_rgba(124,58,237,0.08)] transition-all hover:-translate-y-px hover:shadow-[0_0_18px_rgba(124,58,237,0.16)]">AI 研判</span>
}

// ── 章节转场条 ──
export function ChapterTransition({ text, href, cta }: { text: string; href: string; cta: string }) {
  return (
    <Link
      href={href}
      className="group flex items-center justify-between rounded-xl border border-zinc-200/80 bg-white px-6 py-4 shadow-[var(--shadow-card)] transition-colors hover:border-blue-300"
    >
      <span className="text-sm text-slate-600">{text}</span>
      <span className="flex items-center gap-2 text-sm font-medium text-blue-700 group-hover:text-blue-600">
        {cta}
        <span className="transition-transform group-hover:translate-x-0.5">→</span>
      </span>
    </Link>
  )
}

// ── 加载骨架 ──
export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-lg bg-slate-200/70 ${className}`} />
}

// ── 免责标签（模拟数据） ──
export function SimulatedTag() {
  return (
    <span className="rounded border border-zinc-200 bg-zinc-50 px-1.5 py-0.5 text-[10px] text-slate-500" title="业务产出为脱敏模拟数据，用于验证产品链路；架构支持真实数据接入">
      产出为脱敏模拟
    </span>
  )
}

export function CockpitTopbar({ onRefresh }: { onRefresh?: () => void }) {
  const { projects, monthlyTrend, talentRisk, dataSource, sourceName } = useAppData()
  const months = Array.from(new Set(monthlyTrend.map(record => record.month))).sort()
  const period = months.length > 0 ? `${months[0]}–${months[months.length - 1]}` : '暂无趋势数据'
  const businessCount = projects.filter(project => !project.is_cost_center).length
  const supportCount = projects.filter(project => project.is_cost_center).length

  return (
    <div className="flex items-center justify-between rounded-xl border border-zinc-200/80 bg-white/85 px-4 py-3 text-xs text-slate-500 shadow-[var(--shadow-card)] backdrop-blur-sm">
      <div className="flex items-center gap-4">
        <span>数据快照：{period}</span>
        <span>{projects.length} 业务单元（{businessCount} 业务 / {supportCount} 支撑）· {talentRisk.length} 人</span>
        <span className="rounded-full border border-zinc-200 bg-zinc-50 px-2.5 py-1 text-slate-600">{dataSource === 'uploaded' ? sourceName : '示例数据集'}</span>
      </div>
      {onRefresh ? (
        <button type="button" onClick={onRefresh} className="rounded-md border border-cyan-200 bg-cyan-50 px-3 py-1.5 text-cyan-700 transition-colors hover:bg-cyan-100">
          重新分析
        </button>
      ) : null}
    </div>
  )
}

export function AiBriefing({ title = '本期洞察', prompt }: { title?: string; prompt: string }) {
  const pathname = usePathname()
  const [briefing, setBriefing] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'drilldown', message: `${prompt}。请只用一句话，不超过30字；必须读图不读概念，描述具体数字、比例或对比；必须用经营层大白话，禁止黑话和英文术语。`, chapter: pathname.replace('/', '') || 'home' }),
    })
      .then(res => res.json())
      .then(json => {
        if (!cancelled) {
          const raw = (json.data?.answer || json.answer || '').replace(/\n/g, ' ').replace(/\s+/g, ' ').trim()
          // 允许 ≤200 字完整呈现；prompt 已要求 ≤30 字，超长是 AI 越界，截断到 160 留缓冲
          setBriefing(raw.length > 160 ? raw.slice(0, 160) + '…' : raw)
        }
      })
      .catch(() => {
        if (!cancelled) setBriefing('')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [pathname, prompt])

  return (
    <Card className="border-l-4 border-l-blue-600 bg-gradient-to-r from-blue-50/70 via-white to-white px-7 py-6 shadow-[0_10px_32px_rgba(37,99,235,0.08)] transition-transform hover:-translate-y-px">
      <div className="flex items-start gap-4">
        <span className="relative grid h-8 w-8 shrink-0 place-items-center rounded-full bg-blue-600 text-xs font-bold text-white shadow-[0_0_18px_rgba(37,99,235,0.22)]">
          <span className="absolute inset-0 animate-ping rounded-full bg-blue-400/20" />
          <span className="relative">AI</span>
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-4">
            <span className="text-lg font-semibold leading-none text-blue-900">{title}</span>
            <span className="inline-flex shrink-0 items-center gap-2 rounded-full border border-blue-200 bg-blue-100/60 px-2.5 py-1 text-xs font-medium text-blue-700">
              <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-blue-600" />
              AI 实时生成
            </span>
          </div>
          {loading ? (
            <Skeleton className="mt-4 h-8 w-4/5" />
          ) : (
            <p className="mt-3 text-xl font-medium leading-[1.5] text-zinc-900">
              {briefing || 'AI 洞察暂不可用，系统计算图表不受影响。'}
            </p>
          )}
        </div>
      </div>
    </Card>
  )
}
