'use client'

import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react'

export interface AiDockMessage {
  id: string
  role: 'user' | 'ai'
  text: string
  timestamp: number
  chapter: string
  contextProjectId?: string
}

interface AiDockContextValue {
  messages: AiDockMessage[]
  isLoading: boolean
  sendMessage: (text: string, meta: { chapter: string; page: string; projectId?: string }) => Promise<void>
  clearHistory: () => void
}

const STORAGE_KEY = 'ai-dock-history-v1'

const AiDockContext = createContext<AiDockContextValue>({
  messages: [],
  isLoading: false,
  sendMessage: async () => undefined,
  clearHistory: () => undefined,
})

function makeId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function AiDockProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<AiDockMessage[]>([])
  const [isLoading, setIsLoading] = useState(false)

  useEffect(() => {
    try {
      const stored = sessionStorage.getItem(STORAGE_KEY)
      if (stored) setMessages(JSON.parse(stored))
    } catch {
      setMessages([])
    }
  }, [])

  useEffect(() => {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages))
    } catch {
      // Session storage can be unavailable in private modes.
    }
  }, [messages])

  async function sendMessage(text: string, meta: { chapter: string; page: string; projectId?: string }) {
    const trimmed = text.trim()
    if (!trimmed || isLoading) return

    const userMessage: AiDockMessage = {
      id: makeId(),
      role: 'user',
      text: trimmed,
      timestamp: Date.now(),
      chapter: meta.chapter,
      contextProjectId: meta.projectId,
    }
    setMessages(prev => [...prev, userMessage])
    setIsLoading(true)

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mode: 'drilldown',
          message: trimmed,
          page: meta.page,
          project_id: meta.projectId,
          chapter: meta.chapter,
          history: messages.slice(-6).map(message => ({
            role: message.role === 'ai' ? 'assistant' : 'user',
            content: message.text,
          })),
        }),
      })
      const json = await res.json()
      const answer = json.data?.answer || json.answer || 'AI 暂未返回可用回答。'
      setMessages(prev => [...prev, {
        id: makeId(),
        role: 'ai',
        text: answer,
        timestamp: Date.now(),
        chapter: meta.chapter,
        contextProjectId: meta.projectId,
      }])
    } catch {
      setMessages(prev => [...prev, {
        id: makeId(),
        role: 'ai',
        text: '追问失败，请稍后重试。',
        timestamp: Date.now(),
        chapter: meta.chapter,
        contextProjectId: meta.projectId,
      }])
    } finally {
      setIsLoading(false)
    }
  }

  const value = useMemo(() => ({
    messages,
    isLoading,
    sendMessage,
    clearHistory: () => setMessages([]),
  }), [messages, isLoading])

  return <AiDockContext.Provider value={value}>{children}</AiDockContext.Provider>
}

export function useAiDock() {
  return useContext(AiDockContext)
}
