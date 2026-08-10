'use client'

import { ChatResponse } from '@/lib/types'

type DecisionCardData = NonNullable<ChatResponse['decision_card']>

interface DecisionCardProps extends DecisionCardData {
  onAdjust?: () => void
}

export function DecisionCard({
  title,
  expected_saving,
  productivity_delta,
  actions,
  talent_guards,
  evidence,
  onAdjust,
}: DecisionCardProps) {
  return (
    <article className="rounded-lg border border-blue-500/30 bg-white p-5 shadow-2xl shadow-black/20">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="text-xs font-medium uppercase tracking-[0.16em] text-blue-700">经营方案</div>
          <h3 className="mt-2 text-xl font-semibold text-slate-900">{title}</h3>
          <p className="mt-1 text-sm text-slate-600">基于当前项目组合、人才护栏和 AI 投入结构生成</p>
        </div>
        <span className="rounded-full border border-green-500/30 bg-green-500/10 px-3 py-1 text-xs text-green-200">
          已通过护栏
        </span>
      </div>

      <div className="mt-5 grid grid-cols-2 gap-3">
        <div className="rounded-md border border-zinc-200 bg-white p-4">
          <div className="text-xs text-slate-500">预计节省</div>
          <div className="mt-2 text-3xl font-bold tabular-nums text-green-300">{expected_saving}</div>
        </div>
        <div className="rounded-md border border-zinc-200 bg-white p-4">
          <div className="text-xs text-slate-500">预计人效变化</div>
          <div className="mt-2 text-3xl font-bold tabular-nums text-blue-700">{productivity_delta}</div>
        </div>
      </div>

      <div className="mt-5 grid grid-cols-3 gap-4">
        <section className="col-span-2">
          <h4 className="text-sm font-semibold text-slate-900">建议动作</h4>
          <div className="mt-3 space-y-3">
            {actions.map((item, index) => (
              <div key={`${item.target}-${index}`} className="rounded-md border border-zinc-200 bg-white p-3">
                <div className="text-sm font-medium text-slate-900">
                  {index + 1}. {item.target}
                </div>
                <div className="mt-1 text-sm text-slate-600">{item.action}</div>
                <div className="mt-2 text-xs text-blue-700">{item.impact}</div>
              </div>
            ))}
          </div>
        </section>

	        <section>
	          <h4 className="text-sm font-semibold text-slate-900">人才护栏</h4>
          <div className="mt-3 space-y-3">
            {talent_guards.map((item, index) => (
              <div key={`${item.target}-${index}`} className="rounded-md border border-amber-500/30 bg-amber-500/10 p-3">
                <div className="text-sm font-medium text-amber-800">{item.target}</div>
                <div className="mt-1 text-xs text-amber-800/80">{item.role}</div>
                <div className="mt-2 text-xs leading-5 text-amber-800/70">{item.reason}</div>
              </div>
            ))}
          </div>
        </section>
      </div>

      <section className="mt-5">
        <h4 className="text-sm font-semibold text-slate-900">依据</h4>
        <ul className="mt-2 space-y-1 text-sm leading-6 text-slate-600">
          {evidence.map((item) => (
            <li key={item}>• {item}</li>
          ))}
        </ul>
      </section>

      <div className="mt-5 flex gap-3">
        <button
          type="button"
          onClick={() => window.print()}
          className="rounded-md bg-blue-500 px-4 py-2 text-sm font-medium text-white hover:bg-blue-400"
        >
          导出报告
        </button>
        <button
          type="button"
          onClick={onAdjust}
          className="rounded-md border border-zinc-200 px-4 py-2 text-sm text-slate-700 hover:border-zinc-500 hover:text-slate-900"
        >
          调整条件
        </button>
      </div>
    </article>
  )
}
