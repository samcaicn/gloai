'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useAppData } from '@/lib/DataProvider'

const chapters = [
  { href: '/verdict', num: '01', label: '总体判断' },
  { href: '/divergence', num: '02', label: '分化地图' },
  { href: '/attribution', num: '03', label: '根因诊断' },
  { href: '/decision', num: '04', label: '决策推演' },
]

// 旧路由在新章节体系下的归属（高亮用）
const legacyMap: Record<string, string> = {
  '/overview': '/divergence',
  '/signal': '/attribution',
}

export function Nav() {
  const pathname = usePathname()
  const { dataSource, sourceName, resetUploadedData } = useAppData()
  const active = legacyMap[pathname] || pathname

  return (
    <nav className="sticky top-0 z-50 h-20 bg-white/90 shadow-[0_1px_0_rgba(15,23,42,0.06)] backdrop-blur">
      <div className="flex h-full w-full items-center justify-between px-8">
        <Link href="/" className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-lg border border-blue-200 bg-blue-50 text-base font-bold text-blue-700">
            AI
          </span>
          <div>
            <div className="text-[22px] font-semibold tracking-wide text-[#1a2332]">AI 转型驾驶舱</div>
            <div className="mt-0.5 text-xs leading-4 tracking-wide text-slate-500">AI Transformation Cockpit</div>
          </div>
        </Link>

        <div className="flex items-center gap-3">
          {dataSource === 'uploaded' ? (
            <div className="flex max-w-[300px] items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-sm text-emerald-700">
              <span className="truncate">上传数据：{sourceName}</span>
              <button type="button" onClick={resetUploadedData} className="shrink-0 text-emerald-700 hover:text-emerald-900">
                恢复样例
              </button>
            </div>
          ) : null}
          <div className="flex items-center gap-0.5">
            {chapters.map((c, i) => {
              const isActive = active === c.href
              return (
                <div key={c.href} className="flex items-center">
                  {i > 0 && <span className="mx-1 h-px w-3 bg-zinc-200" />}
                  <Link
                    href={c.href}
                    className={[
                      'relative flex h-12 items-center gap-2 rounded-lg px-4 text-[17px] transition-colors',
                      isActive ? 'text-blue-700' : 'text-slate-500 hover:bg-slate-50 hover:text-[#1a2332]',
                    ].join(' ')}
                  >
                    <span className={`text-[13px] font-bold tabular-nums ${isActive ? 'text-blue-600' : 'text-slate-400'}`}>{c.num}</span>
                    {c.label}
                    {isActive ? <span className="absolute inset-x-4 -bottom-4 h-[3px] rounded-full bg-blue-600" /> : null}
                  </Link>
                </div>
              )
            })}
          </div>
        </div>
      </div>
    </nav>
  )
}
