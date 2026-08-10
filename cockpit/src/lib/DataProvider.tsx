'use client'

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import { Project, MonthlyRecord, TalentRecord, ProjectWithMetrics, CompanySummary, RoleDeptCell } from './types'
import { enrichProjects, getCompanySummary } from './calculations'
import { clearUploadedDataset, readUploadedDataset, UPLOADED_DATASET_EVENT } from './uploadData'

interface AppData {
  projects: ProjectWithMetrics[]
  monthlyTrend: MonthlyRecord[]
  talentRisk: TalentRecord[]
  roleMatrix: RoleDeptCell[] | null
  companySummary: CompanySummary
  isLoading: boolean
  error: string | null
  dataSource: 'demo' | 'uploaded'
  sourceName: string
  resetUploadedData: () => void
}

const DataContext = createContext<AppData>({
  projects: [],
  monthlyTrend: [],
  talentRisk: [],
  roleMatrix: null,
  companySummary: {
    total_headcount: 0, total_labor_cost: 0, total_ai_cost: 0,
    total_revenue: 0, total_profit: 0, ai_to_labor_ratio: 0,
    avg_productivity: 0, project_count: 0,
    quadrant_distribution: { amplifier: 0, underperforming: 0, high_potential: 0, low_base: 0, support: 0 },
  },
  isLoading: true,
  error: null,
  dataSource: 'demo',
  sourceName: '示例数据',
  resetUploadedData: () => undefined,
})

const emptyCompanySummary: CompanySummary = {
  total_headcount: 0, total_labor_cost: 0, total_ai_cost: 0,
  total_revenue: 0, total_profit: 0, ai_to_labor_ratio: 0,
  avg_productivity: 0, project_count: 0,
  quadrant_distribution: { amplifier: 0, underperforming: 0, high_potential: 0, low_base: 0, support: 0 },
}

export function DataProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<AppData>({
    projects: [],
    monthlyTrend: [],
    talentRisk: [],
    roleMatrix: null,
    companySummary: emptyCompanySummary,
    isLoading: true,
    error: null,
    dataSource: 'demo',
    sourceName: '示例数据',
    resetUploadedData: () => undefined,
  })

  useEffect(() => {
    async function loadData() {
      try {
        const uploadedDataset = readUploadedDataset()
        if (uploadedDataset) {
          const projects = enrichProjects(uploadedDataset.projects)
          const companySummary = getCompanySummary(projects)
          setData(prev => ({
            ...prev,
            projects,
            monthlyTrend: uploadedDataset.monthlyTrend,
            talentRisk: uploadedDataset.talentRisk,
            roleMatrix: null,
            companySummary,
            isLoading: false,
            error: null,
            dataSource: 'uploaded',
            sourceName: uploadedDataset.sourceName,
          }))
          return
        }

        const [projRes, trendRes, riskRes, matrixRes] = await Promise.all([
          fetch('/data/demo/projects.json'),
          fetch('/data/demo/monthly_trend.json'),
          fetch('/data/demo/talent_risk.json'),
          fetch('/data/demo/role_dept_matrix.json'),
        ])

        if (!projRes.ok || !trendRes.ok || !riskRes.ok) {
          throw new Error('数据加载失败')
        }

        const rawProjects: Project[] = await projRes.json()
        const monthlyTrend: MonthlyRecord[] = await trendRes.json()
        const talentRisk: TalentRecord[] = await riskRes.json()
        const roleMatrix: RoleDeptCell[] | null = matrixRes.ok ? await matrixRes.json() : null

        const projects = enrichProjects(rawProjects)
        const companySummary = getCompanySummary(projects)

        setData(prev => ({
          ...prev,
          projects,
          monthlyTrend,
          talentRisk,
          roleMatrix,
          companySummary,
          isLoading: false,
          error: null,
          dataSource: 'demo',
          sourceName: '示例数据',
        }))
      } catch (err) {
        setData(prev => ({ ...prev, isLoading: false, error: (err as Error).message }))
      }
    }
    loadData()

    window.addEventListener(UPLOADED_DATASET_EVENT, loadData)
    return () => window.removeEventListener(UPLOADED_DATASET_EVENT, loadData)
  }, [])

  function resetUploadedData() {
    clearUploadedDataset()
  }

  return <DataContext.Provider value={{ ...data, resetUploadedData }}>{children}</DataContext.Provider>
}

export function useAppData() {
  return useContext(DataContext)
}
