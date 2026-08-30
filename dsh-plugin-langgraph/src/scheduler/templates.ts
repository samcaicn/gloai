import type { SubAgentConfig } from '../types.js'

/** 预设模板定义 */
export interface AgentTemplate {
  id: string
  name: string
  description: string
  icon: string
  mode: 'supervisor' | 'handoff'
  objective: string
  agents: SubAgentConfig[]
}

/** 预设模板库 - 一键加载常用多 Agent 协作模式 */
export const AGENT_TEMPLATES: AgentTemplate[] = [
  {
    id: 'research-team',
    name: '研究团队',
    description: '搜索 → 分析 → 撰写，三步完成研究报告',
    icon: '🔬',
    mode: 'supervisor',
    objective: '对指定主题进行全面研究并输出结构化报告',
    agents: [
      {
        name: '信息搜索员',
        description: '负责搜索和收集相关信息',
        systemPrompt: '你是一名专业的信息搜索员。你的任务是搜索和收集与目标主题相关的最新、最准确的信息。请提供有来源引用的关键发现。',
        tools: ['web_search', 'web_fetch'],
        canHandoffTo: [],
      },
      {
        name: '数据分析师',
        description: '分析搜索到的信息，提炼关键洞察',
        systemPrompt: '你是一名数据分析师。基于搜索员提供的信息，提取关键数据和趋势，进行对比分析，并给出数据支撑的结论。',
        tools: [],
        canHandoffTo: [],
      },
      {
        name: '报告撰写员',
        description: '将分析结果整合为结构化报告',
        systemPrompt: '你是一名技术写作专家。基于分析师的洞察，撰写一份结构清晰、逻辑严谨的研究报告。包含：摘要、背景、分析、结论和建议。',
        tools: [],
        canHandoffTo: [],
      },
    ],
  },
  {
    id: 'code-review',
    name: '代码审查',
    description: '安全审查 → 性能优化 → 代码质量',
    icon: '💻',
    mode: 'supervisor',
    objective: '对代码进行全面审查，包括安全性、性能和代码质量',
    agents: [
      {
        name: '安全审查员',
        description: '检查代码中的安全漏洞和风险',
        systemPrompt: '你是一名安全专家。审查代码中的安全漏洞，包括注入攻击、认证缺陷、数据泄露风险等。给出风险等级和修复建议。',
        tools: ['read_file', 'grep'],
        canHandoffTo: [],
      },
      {
        name: '性能优化师',
        description: '分析代码性能瓶颈和优化建议',
        systemPrompt: '你是一名性能优化专家。分析代码的时间复杂度、内存使用和 I/O 模式，找出性能瓶颈并提供具体的优化方案。',
        tools: ['read_file', 'run_code'],
        canHandoffTo: [],
      },
      {
        name: '质量审查员',
        description: '检查代码风格、可维护性和最佳实践',
        systemPrompt: '你是一名代码质量专家。检查代码的可读性、命名规范、测试覆盖率和 SOLID 原则遵循情况。给出改进清单。',
        tools: ['read_file', 'list_files'],
        canHandoffTo: [],
      },
    ],
  },
  {
    id: 'support-triage',
    name: '客服分流',
    description: '智能分流 → 专业处理，自动路由到合适专家',
    icon: '🎧',
    mode: 'handoff',
    objective: '处理客户问题，根据问题类型自动分配给合适的专家',
    agents: [
      {
        name: '智能分流员',
        description: '分析问题类型，决定转接目标',
        systemPrompt: '你是一名客服分流专员。分析用户问题的性质（技术问题/账单问题/产品咨询），判断应该由哪个专家处理，并输出 HANDOFF: <专家名称>。',
        tools: [],
        canHandoffTo: ['技术专家', '账单专员'],
      },
      {
        name: '技术专家',
        description: '处理产品技术相关问题',
        systemPrompt: '你是一名技术客服专家。解答用户关于产品功能、使用方法、故障排查等技术问题。如果问题超出技术范围，输出 HANDOFF: 智能分流员。',
        tools: ['search_docs', 'run_diagnostics'],
        canHandoffTo: ['智能分流员'],
      },
      {
        name: '账单专员',
        description: '处理账单、退款和订阅问题',
        systemPrompt: '你是一名账单客服专家。处理退款、订阅变更、账单查询等财务问题。如果问题不属于账单范围，输出 HANDOFF: 智能分流员。',
        tools: ['lookup_order', 'process_refund'],
        canHandoffTo: ['智能分流员'],
      },
    ],
  },
  {
    id: 'content-creation',
    name: '内容创作',
    description: '策划 → 写作 → 审核，一键生成内容',
    icon: '✍️',
    mode: 'supervisor',
    objective: '从零创建高质量内容（博客、文档、营销文案等）',
    agents: [
      {
        name: '内容策划师',
        description: '规划内容结构和关键信息点',
        systemPrompt: '你是一名内容策略师。基于目标受众和主题，制定内容大纲、关键信息点和SEO关键词策略。',
        tools: ['web_search'],
        canHandoffTo: [],
      },
      {
        name: '内容写手',
        description: '根据大纲撰写正文内容',
        systemPrompt: '你是一名专业写手。基于策划师的大纲，撰写引人入胜的正文内容。注意语气一致性和逻辑连贯性。',
        tools: [],
        canHandoffTo: [],
      },
      {
        name: '内容审核员',
        description: '检查内容质量、准确性和排版',
        systemPrompt: '你是一名内容审核专家。检查内容的语法、事实准确性、排版格式和 SEO 优化程度，提出修改建议。',
        tools: [],
        canHandoffTo: [],
      },
    ],
  },
  {
    id: 'data-pipeline',
    name: '数据处理',
    description: '采集 → 清洗 → 分析，自动化数据流程',
    icon: '📊',
    mode: 'supervisor',
    objective: '从数据源采集数据，清洗后进行分析并输出可视化报告',
    agents: [
      {
        name: '数据采集员',
        description: '从各种来源采集和整合数据',
        systemPrompt: '你是一名数据采集专家。从 API、文件和数据源中提取数据，确保数据完整性并记录数据来源。',
        tools: ['fetch_data', 'read_file', 'http_request'],
        canHandoffTo: [],
      },
      {
        name: '数据清洗员',
        description: '清理和标准化数据格式',
        systemPrompt: '你是一名数据清洗专家。处理缺失值、异常值和格式不一致问题，确保数据质量满足分析要求。',
        tools: ['run_code', 'transform_data'],
        canHandoffTo: [],
      },
      {
        name: '数据分析师',
        description: '分析数据并生成洞察报告',
        systemPrompt: '你是一名数据分析师。运用统计方法和可视化技术，从数据中提炼洞察，生成数据报告和图表。',
        tools: ['run_code', 'create_chart'],
        canHandoffTo: [],
      },
    ],
  },
]

/** 根据 ID 获取模板 */
export function getTemplate(id: string): AgentTemplate | undefined {
  return AGENT_TEMPLATES.find((t) => t.id === id)
}
