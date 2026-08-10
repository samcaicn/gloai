import type { Metadata } from 'next'
import { Suspense } from 'react'
import './globals.css'
import { DataProvider } from '@/lib/DataProvider'
import { AiDockProvider } from '@/lib/AiDockProvider'
import { Nav } from '@/components/Nav'
import { AiDock } from '@/components/AiDock'

export const metadata: Metadata = {
  title: 'AI 转型驾驶舱',
  description: '面向经营层的 AI 转型投入、效率、人才与决策驾驶舱',
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="zh-CN" className="h-full bg-[#fafbfc] antialiased" suppressHydrationWarning>
      <body className="min-h-full text-[#1a2332]" suppressHydrationWarning>
        <DataProvider>
          <AiDockProvider>
          <div className="hidden min-h-screen items-center justify-center bg-[#fafbfc] p-8 text-center text-slate-700 max-xl:flex">
            <div className="max-w-md rounded-lg border border-zinc-200 bg-white p-6 shadow-[var(--shadow-card)]">
              <div className="text-lg font-semibold">请使用桌面端查看</div>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                AI 转型驾驶舱为 1280px 以上经营分析大屏优化，请切换到更宽的窗口。
              </p>
            </div>
          </div>
          <div className="min-h-screen min-w-[1280px] max-xl:hidden">
            <Nav />
            <div className="flex w-full items-start">
              <main className="min-w-0 flex-1">{children}</main>
              <Suspense fallback={null}>
                <AiDock />
              </Suspense>
            </div>
          </div>
          </AiDockProvider>
        </DataProvider>
      </body>
    </html>
  )
}
