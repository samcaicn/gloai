'use client'

import { FormEvent, useState } from 'react'
import { MarkdownContent } from './MarkdownContent'

interface ChatPanelProps {
  quickButtons: { label: string; prompt: string }[]
  onSend: (message: string) => void
  messages: { role: 'user' | 'assistant'; content: string }[]
  isLoading: boolean
}

export function ChatPanel({ quickButtons, onSend, messages, isLoading }: ChatPanelProps) {
  const [input, setInput] = useState('')

  function submit(message: string) {
    const trimmed = message.trim()
    if (!trimmed || isLoading) return
    onSend(trimmed)
    setInput('')
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    submit(input)
  }

  return (
    <section className="sticky bottom-4 z-40 rounded-lg border border-zinc-200/70 bg-white p-3 shadow-2xl shadow-black/30">
      {messages.length > 0 ? (
        <div className="mb-3 max-h-64 space-y-2 overflow-auto rounded-md border border-zinc-200 bg-slate-50/80 p-3">
          {messages.map((message, index) => (
            <div
              key={`${message.role}-${index}`}
              className={message.role === 'user' ? 'text-sm text-blue-700' : 'text-sm leading-6 text-slate-700'}
            >
              <span className="mr-2 text-xs text-slate-500">{message.role === 'user' ? '你' : 'AI'}</span>
              {message.role === 'assistant' ? (
                <MarkdownContent content={message.content} />
              ) : (
                message.content
              )}
            </div>
          ))}
        </div>
      ) : null}

      <div className="mb-3 flex flex-wrap gap-2">
        {quickButtons.map((button) => (
          <button
            key={button.label}
            type="button"
            onClick={() => submit(button.prompt)}
            className="rounded-md border border-zinc-200 bg-white px-3 py-1.5 text-xs text-slate-700 transition-colors hover:border-blue-500/60 hover:text-blue-700"
          >
            {button.label}
          </button>
        ))}
      </div>

      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="输入问题..."
          className="h-11 flex-1 rounded-md border border-zinc-200 bg-white px-3 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-500 focus:border-blue-500"
        />
        <button
          type="submit"
          disabled={isLoading}
          className="h-11 rounded-md bg-blue-500 px-5 text-sm font-medium text-white transition-colors hover:bg-blue-400 disabled:cursor-not-allowed disabled:bg-zinc-700"
        >
          {isLoading ? '分析中' : '发送'}
        </button>
      </form>
    </section>
  )
}
