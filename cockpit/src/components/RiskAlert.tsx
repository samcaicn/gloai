interface RiskAlertProps {
  highRiskCount: number
  powerUsers: number
  projectName: string
}

export function RiskAlert({ highRiskCount, powerUsers, projectName }: RiskAlertProps) {
  if (highRiskCount <= 0) return null

  return (
    <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-4">
      <div className="flex items-start gap-3">
        <div className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-amber-500/20 text-amber-700">
          !
        </div>
        <div>
          <h3 className="text-sm font-semibold text-amber-800">人才护栏提示</h3>
          <p className="mt-1 text-sm leading-6 text-amber-800/80">
            {projectName} 有 {highRiskCount} 名高风险人才，重度使用者共 {powerUsers} 名。建议在预算调整前先确认保留、调薪或转岗方案。
          </p>
        </div>
      </div>
    </div>
  )
}
