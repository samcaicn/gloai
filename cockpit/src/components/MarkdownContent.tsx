'use client'

import ReactMarkdown from 'react-markdown'

interface MarkdownContentProps {
  content: string
}

export function MarkdownContent({ content }: MarkdownContentProps) {
  return (
    <div className="prose prose-invert prose-sm max-w-none prose-headings:text-slate-900 prose-headings:font-semibold prose-h2:text-base prose-h3:text-sm prose-p:text-slate-700 prose-p:leading-relaxed prose-li:text-slate-700 prose-strong:text-slate-900 prose-table:text-xs prose-th:text-slate-600 prose-td:text-slate-700 prose-th:border-zinc-200 prose-td:border-zinc-200 prose-hr:border-zinc-200">
      <ReactMarkdown>{content}</ReactMarkdown>
    </div>
  )
}
