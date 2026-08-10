'use client'

import { ReactNode, useState } from 'react'
import { GLOSSARY, GlossaryKey } from '@/lib/glossary'

export function TermTooltip({ term, children }: { term: GlossaryKey; children?: ReactNode }) {
  const item = GLOSSARY[term]
  const [open, setOpen] = useState(false)

  return (
    <span
      className="group relative inline-flex items-center gap-1 align-baseline"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      <button
        type="button"
        onClick={() => setOpen(value => !value)}
        className="inline-flex items-center gap-1 rounded-md text-inherit underline decoration-slate-300 decoration-dotted underline-offset-4 outline-none hover:text-blue-700 focus:text-blue-700"
      >
        <span>{children || item.term}</span>
        <span className="grid h-3.5 w-3.5 place-items-center rounded-full border border-slate-300 bg-white text-[10px] leading-none text-slate-500">?</span>
      </button>
      <span
        className={[
          'absolute left-0 top-full z-50 mt-2 w-72 rounded-xl border border-slate-200 bg-white p-3 text-left text-xs leading-5 text-slate-600 shadow-[0_12px_40px_rgba(15,23,42,0.14)]',
          open ? 'block' : 'hidden group-hover:block group-focus-within:block',
        ].join(' ')}
      >
        <span className="block text-sm font-semibold text-[#1a2332]">{item.term}</span>
        <span className="mt-1 block font-medium text-blue-700">{item.short}</span>
        <span className="mt-2 block">{item.long}</span>
      </span>
    </span>
  )
}
