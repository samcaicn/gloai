export type GlossaryKey =
  | 'power'
  | 'critical_talent'
  | 'productivity_trend_up'
  | 'dist_aggregation'
  | 'amplifier_confirmed'
  | 'underperforming'
  | 'high_potential'
  | 'observe'
  | 'talent_guardrail_check'
  | 'leverage_matrix'
  | 'cr_proxy'
  | 'compa_ratio'
  | 'model_mismatch'
  | 'ai_coverage'
  | 'roi'
  | 'core_metric'
  | 'money_dim'
  | 'efficiency_dim'
  | 'people_dim'
  | 'dept_share'
  | 'fragility'
  | 'ai_intensity'
  | 'expected_value'
  | 'coverage_scope'
  | 'validation_method'
  | 'benchmark_project'
  | 'cohort_engagement'
  | 'role_gap'

export const GLOSSARY: Record<GlossaryKey, { term: string; short: string; long: string }> = {
  power: {
    term: '重度使用者',
    short: '月 AI 成本 >= 7000 元且活跃 >= 20 天的人',
    long: '同时满足两个条件的员工：月 AI 工具成本至少 7000 元，且月活跃天数不少于 20 天。代表真正在用 AI 干活的人，是判断 AI 转型是否落地的核心样本。',
  },
  critical_talent: {
    term: '高流失风险人才',
    short: '重度使用者 + 薪酬偏低 + 团队流失环境',
    long: '这类人既在高强度使用 AI，又处在薪酬位档偏低或团队流失压力较高的环境中。预算调整时要先保护，否则可能影响已形成的 AI 使用能力。',
  },
  productivity_trend_up: {
    term: '人效在改善',
    short: '近 6 个月人效趋势为正向',
    long: '用近 6 个月的人效走势做回归判断，只有持续向上才算改善，避免把单月波动误判成真实提升。',
  },
  dist_aggregation: {
    term: '项目分类汇总',
    short: '把项目分成已变好、待改善、暂观察三类',
    long: '系统先把每个项目放到项目分布矩阵里，再按状态汇总数量。已变好代表 AI 投入和人效走势同时较好；待改善代表投入高但人效没跟上；暂观察代表样本或趋势还不足以下结论。',
  },
  amplifier_confirmed: {
    term: 'AI 已让人效变好',
    short: 'AI 投入强度高，近 6 个月人效仍在改善',
    long: '判定标准：AI 投入强度不低于全公司中位数的 1.2 倍，并且近 6 个月人效环比斜率不低于 +2%。这类项目适合作为方法复用和预算加码的参考样本。',
  },
  underperforming: {
    term: '待改善',
    short: 'AI 投入高，但人效没有同步改善',
    long: '判定标准：AI 投入强度不低于全公司中位数的 1.2 倍，但人效环比小于 0，或趋势持平/下行。说明钱已经花出去，但还没有稳定转化为经营产出。',
  },
  high_potential: {
    term: '待加码',
    short: '人效好，但 AI 使用普及度还低',
    long: '这类项目业务产出基础好，AI 还没有充分覆盖，适合用小规模预算实验验证加码空间。',
  },
  observe: {
    term: '暂不下结论',
    short: '样本不足或趋势不显著',
    long: '判定标准：活跃使用者少于 3 人，或 AI 投入强度低于全公司中位数，当前数据还不足以支持明确判断，建议继续跟踪趋势，不急着做预算动作。',
  },
  talent_guardrail_check: {
    term: '关键人才保护检查',
    short: '方案动到保人名单时自动提示风险',
    long: 'AI 生成行动方案时，会检查是否触及高流失风险人才所在项目。如果触及，系统会提示风险，并要求先给出保护或替代建议。',
  },
  leverage_matrix: {
    term: '项目分布矩阵',
    short: '横轴 AI 投入强度，纵轴人效，气泡代表项目',
    long: 'X 轴是 AI 投入强度，Y 轴是人效，气泡大小代表人数，颜色代表项目分类，趋势标记代表近 6 个月人效方向。它用于判断哪些项目已变好、待改善或暂观察。',
  },
  cr_proxy: {
    term: '薪酬位档（代理口径）',
    short: '用公开行业薪酬带替代真实薪酬竞争力',
    long: '本系统未接入公司完整薪酬库，采用公开行业薪酬带作为代理标尺。代理口径不等于真实 Compa-Ratio，仅用于相对比较。',
  },
  compa_ratio: {
    term: '薪酬位档（Compa-Ratio）',
    short: '员工薪资 / 同岗位市场中位数',
    long: 'Compa-Ratio 表示员工薪资相对同岗位市场中位数的位置。本系统未接入完整薪酬库，使用公开行业薪酬带 P50/P75 作为代理标尺，因此页面展示为“薪酬位档（代理口径）”。',
  },
  model_mismatch: {
    term: '模型用错地方了',
    short: '非技术岗位大量使用高价模型',
    long: '如果非技术岗位大量使用高成本模型，但产出没有同步改善，就可能存在模型选择过重、使用方法不匹配或培训不到位。',
  },
  ai_coverage: {
    term: 'AI 使用普及度',
    short: '团队中至少活跃使用过 AI 的人数占比',
    long: '它衡量 AI 是否覆盖到足够多的人，而不是只集中在少数深度使用者身上。',
  },
  roi: {
    term: '每投 1 元拿回多少利润',
    short: '利润 / 人力与 AI 总投入',
    long: '把利润除以人力成本和 AI 成本之和，用来判断投入是否真正转化成经营结果。',
  },
  core_metric: {
    term: '核心指标',
    short: '经营层最需要先看的几个数字',
    long: '包括投入回报、AI 投入比、月度产出、重度使用者占比、高流失风险人才和人效改善项目数，用来快速判断 AI 转型是否值得继续加码。',
  },
  money_dim: {
    term: '钱花得值吗',
    short: '看 AI 投入是否转化为人效和利润',
    long: '这个维度关注预算是否投到了有效项目上，以及高投入项目有没有产生对应回报。',
  },
  efficiency_dim: {
    term: '效率撬动了吗',
    short: '看使用深度、覆盖度和方法复用',
    long: '这个维度关注员工是否真的把 AI 用进工作流，而不是只产生零散工具费用。',
  },
  people_dim: {
    term: '人扛得住吗',
    short: '看关键使用者是否稳定',
    long: '这个维度关注 AI 转型依赖的人才是否有流失或激励风险。',
  },
  dept_share: {
    term: '个人占部门 AI 成本比例',
    short: '某个人的 AI 成本 / 所在部门 AI 总成本',
    long: '比例越高，说明团队越依赖少数人使用 AI；如果这个人薪酬位档又偏低，团队风险会上升。它是判断团队依赖脆弱点的参考，不代表个人绩效。',
  },
  fragility: {
    term: '团队依赖脆弱点',
    short: '少数人承担过多 AI 使用能力',
    long: '当一个团队的 AI 使用高度集中在少数人身上，这些人一旦流失，团队的 AI 转型能力可能明显回退。',
  },
  ai_intensity: {
    term: 'AI 投入强度',
    short: '员工人均每月 AI 工具成本',
    long: '表示团队每名员工平均每月花在 AI 工具上的成本，反映团队对 AI 的真实投入密度。部分图表也会用 AI 成本 / 人力成本做相对比较。',
  },
  expected_value: {
    term: '预期收益',
    short: '行动后可能带来的人效或财务影响',
    long: '这是 AI 基于已有项目数据、参考标杆项目和当前方案推算出的影响，使用脱敏模拟值，只用于比较不同方案的优先级。',
  },
  coverage_scope: {
    term: '覆盖范围',
    short: '本行动覆盖的项目、部门或人头',
    long: '它说明这张行动卡到底会影响哪些业务单元、岗位或人员范围，避免方案停留在抽象建议。',
  },
  validation_method: {
    term: '怎么验证生效',
    short: '执行后看哪些指标判断是否真的起效',
    long: '通常会观察人效、AI 使用普及度、活跃天数、关键人才稳定性等指标，确保方案不是只停留在投入动作上。',
  },
  benchmark_project: {
    term: '参考标杆项目',
    short: '已识别的 AI 已让人效变好的项目',
    long: '系统会从项目分布矩阵中找到已经呈现人效改善的项目，把它们作为方法迁移或预算加码的参考样本。',
  },
  cohort_engagement: {
    term: '同期入职队列敬业度',
    short: '按入职年份分组观察团队敬业度趋势',
    long: '这是团队级背景数据，只用于人才保护检查，不用于判断某个个人的敬业度或留任意愿。',
  },
  role_gap: {
    term: '同岗位人效差距（倍）',
    short: '同一岗位在不同项目之间的人效差距',
    long: '用于发现同样岗位在不同业务单元里的使用方法差异。差距越大，越说明内部可能存在可迁移的经验。',
  },
}
