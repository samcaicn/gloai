'use client'

import { useRouter } from 'next/navigation'
import { useRef, useState } from 'react'
import { parseUploadedFiles, saveUploadedDataset, UploadedDataset, UploadFileSummary } from '@/lib/uploadData'

const steps = [
  '识别数据结构...',
  '多源字段映射与归集',
  '人效指标计算',
  'AI 价值信号分析',
  '人才护栏扫描',
]

export default function Home() {
  const router = useRouter()
  const [activeStep, setActiveStep] = useState(-1)
  const [isProcessing, setIsProcessing] = useState(false)
  const [files, setFiles] = useState<UploadFileSummary[]>([])
  const [uploadedDataset, setUploadedDataset] = useState<UploadedDataset | null>(null)
  const [uploadErrors, setUploadErrors] = useState<string[]>([])
  const [isParsing, setIsParsing] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  async function handleFiles(fileList: FileList) {
    setIsParsing(true)
    setUploadErrors([])
    setUploadedDataset(null)
    try {
      const result = await parseUploadedFiles(Array.from(fileList))
      setFiles(result.summaries)
      setUploadErrors(result.errors)
      setUploadedDataset(result.dataset)
    } catch (error) {
      setUploadErrors([(error as Error).message])
    } finally {
      setIsParsing(false)
    }
  }

  function startAnalysis() {
    if (isProcessing) return
    if (files.length > 0 && !uploadedDataset) {
      setUploadErrors(prev => prev.length > 0 ? prev : ['上传文件尚未形成可分析数据集，请上传模板三表，或提供 projects schema 数据'])
      return
    }

    if (uploadedDataset) saveUploadedDataset(uploadedDataset)
    setIsProcessing(true)
    steps.forEach((_, index) => {
      window.setTimeout(() => setActiveStep(index), (index + 1) * 600)
    })
    window.setTimeout(() => router.push('/verdict'), (steps.length + 1) * 600)
  }

  return (
    <div className="flex min-h-[calc(100vh-5rem)] w-full items-center justify-center px-8 py-10">
      <section className="w-full max-w-[860px] text-center">
        <div className="mx-auto mb-6 grid h-12 w-12 place-items-center rounded-lg border border-blue-500/40 bg-blue-500/10 text-sm font-bold text-blue-700">
          AI
        </div>
        <h1 className="text-5xl font-semibold tracking-tight text-slate-900">AI 转型驾驶舱</h1>
        <p className="mt-4 text-lg text-slate-600">用 AI 算清 AI 转型这笔账：投入在哪里撬动了人效，在哪里需要调整</p>

        <div className="mt-10 rounded-lg border border-dashed border-zinc-200 bg-white/70 p-8">
          <div className="text-sm font-medium text-slate-800">上传三类数据，AI 自动生成决策分析</div>
          <p className="mt-2 text-xs text-slate-500">支持一次选择多个文件（业务产出 + 人力成本 + AI 使用日志），格式：.xlsx / .csv</p>
          <div className="mt-4 flex justify-center gap-2 text-xs">
            {[
              ['人力成本模板', '/templates/人力成本数据.xlsx'],
              ['业务产出模板', '/templates/业务产出数据.xlsx'],
              ['AI 使用模板', '/templates/AI使用数据.xlsx'],
            ].map(([label, href]) => (
              <a
                key={href}
                href={href}
                download
                className="rounded-md border border-zinc-200 bg-white px-3 py-1.5 text-slate-700 hover:border-blue-500/60 hover:text-blue-700"
              >
                {label}
              </a>
            ))}
          </div>

          <input
            ref={fileInputRef}
            type="file"
            accept=".xlsx,.xls,.csv,.json"
            multiple
            className="hidden"
            onChange={(e) => {
              if (e.target.files && e.target.files.length > 0) {
                handleFiles(e.target.files)
              }
            }}
          />

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault()
              if (event.dataTransfer.files.length > 0) handleFiles(event.dataTransfer.files)
            }}
            className="mt-5 w-full cursor-pointer rounded-lg border-2 border-dashed border-zinc-600 bg-slate-50/80 px-6 py-8 text-center transition-colors hover:border-blue-500/60 hover:bg-white"
          >
            <div className="mx-auto grid h-9 w-12 place-items-center rounded border border-zinc-200 bg-white text-[11px] font-semibold tracking-[0.16em] text-slate-500">DATA</div>
            <div className="mt-2 text-sm text-slate-700">{isParsing ? '正在解析文件...' : '点击选择文件（可多选）'}</div>
            <div className="mt-1 text-xs text-slate-500">支持拖拽；可上传上方模板三表，或 projects / monthly_trend / talent_risk schema 文件</div>
          </button>

          {files.length > 0 && (
            <div className="mt-4 space-y-2 text-left">
              {files.map((f, i) => (
                <div
                  key={`${f.name}-${i}`}
                  className={[
                    'flex items-center gap-3 rounded-md border px-3 py-2',
                    f.status === 'ready' ? 'border-green-500/30 bg-green-500/5' : f.status === 'warning' ? 'border-amber-500/30 bg-amber-500/5' : 'border-red-500/30 bg-red-500/5',
                  ].join(' ')}
                >
                  <span className={f.status === 'ready' ? 'text-green-400' : f.status === 'warning' ? 'text-amber-400' : 'text-red-400'}>
                    {f.status === 'ready' ? '✓' : f.status === 'warning' ? '!' : '×'}
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="truncate text-sm text-slate-800">{f.name}</div>
                    <div className="text-xs text-slate-500">{f.message} · {f.rows} 行 × {f.cols} 列 · {f.columns.join(', ') || '无字段'}</div>
                  </div>
                </div>
              ))}
              {uploadedDataset ? (
                <DataCompleteness dataset={uploadedDataset} />
              ) : null}
            </div>
          )}

          {uploadErrors.length > 0 ? (
            <div className="mt-4 rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-left text-xs leading-5 text-red-200">
              {uploadErrors.map((error) => (
                <div key={error}>{error}</div>
              ))}
            </div>
          ) : null}
        </div>

        <div className="mt-6 flex items-center justify-center gap-4">
          <button
            type="button"
            onClick={startAnalysis}
            disabled={isProcessing || isParsing}
            className="h-12 rounded-md bg-blue-500 px-7 text-sm font-semibold text-white transition-colors hover:bg-blue-400 disabled:cursor-not-allowed disabled:bg-zinc-700"
          >
            {isProcessing ? '正在生成...' : isParsing ? '正在解析...' : uploadedDataset ? '基于上传数据生成分析' : '使用示例数据体验'}
          </button>
        </div>

        {isProcessing && (
          <div className="mx-auto mt-8 max-w-xl rounded-lg border border-zinc-200/70 bg-white p-4 text-left">
            <div className="text-xs font-medium uppercase tracking-[0.2em] text-slate-500">Processing</div>
            <div className="mt-4 space-y-3">
              {steps.map((step, index) => {
                const done = activeStep >= index
                return (
                  <div
                    key={step}
                    className={[
                      'flex items-center gap-3 rounded-md border px-3 py-2 text-sm transition-opacity duration-300',
                      done
                        ? 'border-blue-500/30 bg-blue-500/10 text-slate-900 opacity-100'
                        : 'border-zinc-200 bg-white text-slate-500 opacity-60',
                    ].join(' ')}
                  >
                    <span className={done ? 'text-blue-700' : 'text-slate-400'}>{done ? '✓' : '•'}</span>
                    {step}
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </section>
    </div>
  )
}

function DataCompleteness({ dataset }: { dataset: UploadedDataset }) {
  const projectCount = dataset.projects.length
  const monthCount = new Set(dataset.monthlyTrend.map((record) => record.month)).size
  const aiCoveredCount = dataset.projects.filter((project) => project.ai_cost > 0 || project.ai_penetration > 0).length
  const hasTalent = dataset.talentRisk.length > 0
  const items = [
    { label: '业务单元', value: `${projectCount} 个`, ready: projectCount > 0 },
    { label: '月度趋势', value: monthCount > 0 ? `${monthCount} 个月` : '未提供', ready: monthCount > 0 },
    { label: 'AI 覆盖', value: `${aiCoveredCount}/${projectCount}`, ready: aiCoveredCount > 0 },
    { label: '人才护栏', value: hasTalent ? `${dataset.talentRisk.length} 条` : '降级运行', ready: hasTalent },
  ]

  return (
    <div className="rounded-md border border-blue-500/30 bg-blue-500/5 p-3">
      <div className="mb-2 text-center text-xs font-medium text-blue-700">已生成可分析数据集</div>
      <div className="grid grid-cols-4 gap-2">
        {items.map((item) => (
          <div key={item.label} className="rounded border border-zinc-200 bg-white/80 px-2 py-2 text-center">
            <div className={item.ready ? 'text-sm font-semibold tabular-nums text-slate-900' : 'text-sm font-semibold text-amber-700'}>{item.value}</div>
            <div className="mt-1 text-[10px] text-slate-500">{item.label}</div>
          </div>
        ))}
      </div>
      {!hasTalent ? (
        <div className="mt-2 text-center text-[11px] text-amber-700">未上传人才风险数据时，系统仍可做人效和 AI 投入分析，人才护栏将使用降级提示。</div>
      ) : null}
    </div>
  )
}
