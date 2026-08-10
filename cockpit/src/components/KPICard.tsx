interface KPICardProps {
  label: string
  value: string
  sub?: string
}

export function KPICard({ label, value, sub }: KPICardProps) {
  return (
    <div className="rounded-lg border border-zinc-200/70 bg-white p-4">
      <div className="text-sm text-slate-600">{label}</div>
      <div className="mt-3 text-3xl font-bold tabular-nums text-slate-900">{value}</div>
      {sub ? <div className="mt-2 text-xs text-slate-500">{sub}</div> : null}
    </div>
  )
}
