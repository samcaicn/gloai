// ═══════════════════════════════════════════════════════════════════════
// 自动选品沟通 v3.0 — LLM 智能沟通引擎
// ═══════════════════════════════════════════════════════════════════════
// 核心理念: 用 LLM 把人从重复沟通中解放出来，实现高质量、自适应、
//           可干预的多商家自动沟通，并通过 Hermes 持续自我进化。
//
// 目标页面: https://store.weixin.qq.com/talent/pool/home?from=platform&keyword=KEYWORD
// 页面架构: micro-app 微前端 (shadowDOM=open)
//
// v3.0 新增:
//   1. 产品介绍资料文件夹管理 — 用户选择文件夹，系统读取并索引资料
//   2. LLM 多轮对话引擎 — 上下文感知、自适应语气、智能跟进、适时发送资料
//   3. 多商家循环沟通 — 自动遍历多个商家，完整对话流程
//   4. 人工干预 — 随时暂停/修改/接管/恢复
//   5. 沟通日志 — 记录所有对话过程和商家反馈效果
//   6. Hermes 自进化 — 分析历史沟通效果，迭代优化沟通策略
// ═══════════════════════════════════════════════════════════════════════

// ── 内置流程图 v3.0 ──────────────────────────────────────────────────────
const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'auto-product-comm-flowchart',
  skillId: 'com.tupautochrome.skills.auto-product-comm',
  version: '3.0.0',
  name: '自动选品智能沟通流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: ['cdp', 'uia', 'ocr', 'vlm'],
  nodes: [
    { id: 'start',            type: 'start',    label: '开始' },
    { id: 'ensure',           type: 'process',  label: '确保 CDP 连接' },
    { id: 'config?',          type: 'decision', label: '有预配置?', branches: { yes: 'load_materials', no: 'get_config' } },
    { id: 'get_config',       type: 'io',       label: '交互式获取配置' },
    { id: 'load_materials',   type: 'process',  label: '加载产品介绍资料' },
    { id: 'analyze_cat',      type: 'process',  label: 'LLM 分析分类' },
    { id: 'navigate',         type: 'process',  label: '打开选品页面' },
    { id: 'wait_page',        type: 'process',  label: '等待页面加载' },
    { id: 'apply_cat',        type: 'process',  label: '选择分类标签' },
    { id: 'apply_filters',    type: 'process',  label: '批量应用筛选条件' },
    { id: 'wait_results',     type: 'process',  label: '等待筛选结果' },
    { id: 'extract_merchants',type: 'process',  label: '提取商家列表' },
    { id: 'merchant_loop',    type: 'process',  label: '商家沟通循环' },
    { id: 'extract_info',     type: 'process',  label: '提取商家/商品信息' },
    { id: 'gen_opening',      type: 'process',  label: 'LLM 生成开场白' },
    { id: 'send_msg',         type: 'process',  label: '发送消息' },
    { id: 'wait_reply',       type: 'process',  label: '等待商家回复' },
    { id: 'reply?',           type: 'decision', label: '商家回复了?', branches: { yes: 'analyze_reply', no: 'follow_up?' } },
    { id: 'follow_up?',       type: 'decision', label: '跟进提醒?', branches: { yes: 'send_msg', no: 'conv_done?' } },
    { id: 'analyze_reply',    type: 'process',  label: 'LLM 分析回复' },
    { id: 'gen_followup',     type: 'process',  label: 'LLM 生成跟进' },
    { id: 'send_material?',   type: 'decision', label: '需要发资料?', branches: { yes: 'send_material', no: 'check_human' } },
    { id: 'send_material',    type: 'process',  label: '发送产品资料' },
    { id: 'check_human',      type: 'decision', label: '人工干预?', branches: { yes: 'human_intervene', no: 'conv_done?' } },
    { id: 'human_intervene',  type: 'io',       label: '人工干预/接管' },
    { id: 'conv_done?',       type: 'decision', label: '对话结束?', branches: { yes: 'log_conv', no: 'send_msg' } },
    { id: 'log_conv',         type: 'process',  label: '记录沟通日志' },
    { id: 'next_merchant?',   type: 'decision', label: '继续下一个?', branches: { yes: 'merchant_loop', no: 'batch_report' } },
    { id: 'batch_report',     type: 'process',  label: '生成批次报告' },
    { id: 'self_evolve',      type: 'process',  label: 'Hermes 自进化分析' },
    { id: 'end',              type: 'end',      label: '结束' },
  ],
  connections: [
    { from: 'start',             to: 'ensure' },
    { from: 'ensure',            to: 'config?' },
    { from: 'config?',           to: 'load_materials',  label: 'yes' },
    { from: 'config?',           to: 'get_config',      label: 'no' },
    { from: 'get_config',        to: 'load_materials' },
    { from: 'load_materials',    to: 'analyze_cat' },
    { from: 'analyze_cat',       to: 'navigate' },
    { from: 'navigate',          to: 'wait_page' },
    { from: 'wait_page',         to: 'apply_cat' },
    { from: 'apply_cat',         to: 'apply_filters' },
    { from: 'apply_filters',     to: 'wait_results' },
    { from: 'wait_results',      to: 'extract_merchants' },
    { from: 'extract_merchants', to: 'merchant_loop' },
    { from: 'merchant_loop',     to: 'extract_info' },
    { from: 'extract_info',      to: 'gen_opening' },
    { from: 'gen_opening',       to: 'send_msg' },
    { from: 'send_msg',          to: 'wait_reply' },
    { from: 'wait_reply',        to: 'reply?' },
    { from: 'reply?',            to: 'analyze_reply',   label: 'yes' },
    { from: 'reply?',            to: 'follow_up?',      label: 'no' },
    { from: 'follow_up?',        to: 'send_msg',        label: '跟进' },
    { from: 'follow_up?',        to: 'conv_done?',      label: '放弃' },
    { from: 'analyze_reply',     to: 'gen_followup' },
    { from: 'gen_followup',      to: 'send_material?' },
    { from: 'send_material?',    to: 'send_material',   label: 'yes' },
    { from: 'send_material?',    to: 'check_human',     label: 'no' },
    { from: 'send_material',     to: 'check_human' },
    { from: 'check_human',       to: 'human_intervene', label: 'yes' },
    { from: 'check_human',       to: 'conv_done?',      label: 'no' },
    { from: 'human_intervene',   to: 'conv_done?' },
    { from: 'conv_done?',        to: 'log_conv',        label: 'yes' },
    { from: 'conv_done?',        to: 'send_msg',        label: 'no' },
    { from: 'log_conv',          to: 'next_merchant?' },
    { from: 'next_merchant?',    to: 'merchant_loop',   label: 'yes' },
    { from: 'next_merchant?',    to: 'batch_report',    label: 'no' },
    { from: 'batch_report',      to: 'self_evolve' },
    { from: 'self_evolve',       to: 'end' },
  ],
  judgments: [
    { id: 'J1', node: 'config?',        rule: '检查 params.filters 或 storage 中预存筛选配置',     onMatch: 'load_materials' },
    { id: 'J2', node: 'reply?',         rule: '检测聊天窗口是否有商家新消息',                       onMatch: 'analyze_reply' },
    { id: 'J3', node: 'send_material?', rule: 'LLM 判断当前对话阶段是否适合发送产品资料',           onMatch: 'send_material' },
    { id: 'J4', node: 'check_human',    rule: '检查人工干预标志/断点/暂停请求',                     onMatch: 'human_intervene' },
    { id: 'J5', node: 'conv_done?',     rule: 'LLM 判断对话是否自然结束/达成目的/超过最大轮次',     onMatch: 'log_conv' },
    { id: 'J6', node: 'next_merchant?', rule: '已联系数 < maxMerchants 且有更多商家',               onMatch: 'merchant_loop' },
  ],
  selectors: {
    filtersRow: '.filters-row',
    dropdown: '.weui-desktop-form__dropdown',
    dropdownDt: '.weui-desktop-form__dropdown__dt',
    dropdownValue: '.weui-desktop-form__dropdown__value',
    prependIn: '.prepend-in',
    dropdownList: '.weui-desktop-dropdown-menu',
    dropdownItem: '.weui-desktop-dropdown__list-ele',
    dropdownItemText: '.weui-desktop-dropdown__list-ele__text',
    tag: '.tag',
    tagActive: '.tag.actived',
    priceInput: '.composition-input input.t-input__inner',
    compositionDropdown: '.composition-input .weui-desktop-form__dropdown',
    contactBtn: '[class*="contact"], button, a',
    chatInput: 'textarea, [contenteditable="true"], .chat-input, [role="textbox"]',
    sendBtn: 'button[class*="send"], .send-btn, [class*="send"] button',
  },
  variables: {
    keywords:        { type: 'array',  items: 'string', default: [] },
    filters:         { type: 'object' },
    materialFolder:  { type: 'string', default: '' },
    maxMerchants:    { type: 'number', default: 5 },
    maxConvRounds:   { type: 'number', default: 8 },
    replyWaitSecs:   { type: 'number', default: 120 },
    commStyle:       { type: 'string', default: '专业友好' },
    autoEvolve:      { type: 'boolean',default: true },
    recognition:     { type: 'array',  items: 'string', default: ['cdp', 'uia', 'ocr', 'vlm'] },
  },
  metadata: { createdAt: '2026-07-17T00:00:00Z', updatedAt: '2026-07-17T18:00:00Z', author: 'tupAI', version: '3.0.0' },
}

// ── 常量 ─────────────────────────────────────────────────────────────────
// NOTE: DEFAULT_RECOGNITION 已由 capabilities.js 声明为 const，此处不再重复声明
const PAGE_BASE_URL = 'https://store.weixin.qq.com/talent/pool/home?from=platform&keyword='
const STORAGE_KEY = 'auto_product_comm_config_v3'
const LOG_KEY = 'auto_product_comm_logs_v3'
const EVOLVE_KEY = 'auto_product_comm_evolve_v3'

// ── Shadow DOM 访问前缀（micro-app 微前端） ────────────────────────────────
// 页面使用 <micro-app name="pool" shadowdom="true"> 渲染内容 [[memory:17842896083387111025]]
// 所有 DOM 查询必须通过 shadowRoot 进行
const SR_JS = 'var ma=document.querySelector(\'micro-app[name="pool"]\');var sr=ma?ma.shadowRoot:null;if(!sr)return JSON.stringify({ok:false,note:\'shadowRoot未找到\'});'

// ── 筛选选项定义 ──────────────────────────────────────────────────────────
const FILTER_OPTIONS = {
  sort: {
    label: '商品排序',
    options: ['按推荐顺序', '高佣金优先', '热销优先', '价格由低到高', '价格由高到低'],
    default: '按推荐顺序',
    multiple: false,
  },
  service: {
    label: '服务保障',
    options: ['7天无理由', '品牌', '损坏包退', '假一赔三', '先用后付'],
    default: [],
    multiple: true,
  },
  priceRange: {
    label: '价格范围',
    type: 'composition',
    compositionOptions: ['价格', '佣金比例'],
    defaultComposition: '价格',
    defaultMin: '',
    defaultMax: '',
  },
  monthlySales: {
    label: '月销量',
    options: ['5万以上', '1万以上', '5千以上', '1千以上'],
    default: null,
    multiple: false,
  },
  positiveRate: {
    label: '好评率',
    options: ['95%以上', '90%以上', '85%以上', '80%以上'],
    default: null,
    multiple: false,
  },
  shopRating: {
    label: '店铺评分',
    options: ['4.8以上', '4.5以上', '4.0以上'],
    default: null,
    multiple: false,
  },
}

const DEFAULT_CATEGORIES = ['全部', '订单商品', '食品饮料', '茶酒生鲜', '男装女装']

// ── 沟通风格 ──────────────────────────────────────────────────────────────
const COMM_STYLES = {
  '专业友好': '专业但不失友好，像一位有经验的采购经理。用语得体，逻辑清晰，让对方感到被尊重。',
  '热情主动': '热情开朗，主动推进话题。善于用积极的语气拉近距离，适合快消品/日用百货类商家。',
  '稳重务实': '沉稳务实，注重数据和专业性。适合高客单价/B端产品，给对方可靠感。',
  '轻松亲切': '轻松亲切，像朋友聊天一样自然。适合小商家/个体户，降低对方防备心。',
}

// ═══════════════════════════════════════════════════════════════════════
// 模块 1: MaterialManager — 产品介绍资料管理
// ═══════════════════════════════════════════════════════════════════════
const MaterialManager = {
  _materials: [],
  _folder: '',

  setFolder: function(path) {
    this._folder = path || ''
    this._materials = []
    return this._folder
  },

  getFolder: function() { return this._folder },

  loadFromFolder: async function(folderPath) {
    this._folder = folderPath || this._folder
    if (!this._folder) return { ok: false, note: '未设置资料文件夹' }

    // 尝试通过 Tauri 读取文件夹
    try {
      var invoke = null
      if (typeof window !== 'undefined' && window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke) {
        invoke = window.__TAURI__.core.invoke
      } else {
        try { var mod = await import('@tauri-apps/api/core'); invoke = mod.invoke } catch (e) {}
      }
      if (invoke) {
        var files = await invoke('read_dir_recursive', { path: this._folder })
        if (files && Array.isArray(files) && files.length > 0) {
          this._materials = []
          for (var i = 0; i < files.length; i++) {
            var f = files[i]
            var content = f.content || f.text || ''
            var name = f.name || f.path || ('file_' + i)
            if (typeof content === 'string' && content.length > 0) {
              this._materials.push({
                name: name,
                type: this._detectType(name),
                content: content.slice(0, 5000),
                summary: content.slice(0, 200),
                keywords: this._extractKeywords(content),
              })
            }
          }
          await this._generateSummaries()
          this.cache()
          return { ok: true, count: this._materials.length, note: '从文件夹加载 ' + this._materials.length + ' 份资料' }
        }
      }
    } catch (e) {
      cap.runtime.log('material', 'Tauri 读取失败: ' + (e.message || e))
    }

    // 后备：从 storage 加载缓存
    var cached = cap.storage.get('material_cache_' + this._folder, null)
    if (cached && Array.isArray(cached) && cached.length > 0) {
      this._materials = cached
      return { ok: true, count: cached.length, note: '从缓存加载 ' + cached.length + ' 份资料' }
    }

    return { ok: false, note: '无法读取文件夹（需要 Tauri 环境或预先缓存）' }
  },

  addMaterial: function(name, content) {
    if (!content || content.length === 0) return
    this._materials.push({
      name: name || ('资料_' + (this._materials.length + 1)),
      type: this._detectType(name),
      content: content.slice(0, 5000),
      summary: content.slice(0, 200),
      keywords: this._extractKeywords(content),
    })
  },

  addMaterials: function(items) {
    if (!Array.isArray(items)) return
    for (var i = 0; i < items.length; i++) {
      if (items[i].name && items[i].content) {
        this.addMaterial(items[i].name, items[i].content)
      }
    }
  },

  getAll: function() { return this._materials },

  findRelevant: function(productInfo, keywords) {
    if (this._materials.length === 0) return []
    var scored = this._materials.map(function(m) {
      var score = 0
      var info = (productInfo || '').toLowerCase()
      var kws = keywords || []
      for (var i = 0; i < kws.length; i++) {
        if (m.content.toLowerCase().indexOf(kws[i].toLowerCase()) >= 0) score += 10
      }
      if (info && info.length > 10) {
        var words = info.split(/[\s,，、;；]+/).filter(function(w) { return w.length > 1 })
        for (var j = 0; j < words.length; j++) {
          if (m.content.toLowerCase().indexOf(words[j].toLowerCase()) >= 0) score += 1
        }
      }
      return { material: m, score: score }
    })
    scored.sort(function(a, b) { return b.score - a.score })
    return scored.filter(function(s) { return s.score > 0 }).slice(0, 3).map(function(s) { return s.material })
  },

  getSummaryText: function() {
    if (this._materials.length === 0) return ''
    var lines = ['可用产品资料：']
    for (var i = 0; i < this._materials.length; i++) {
      lines.push((i + 1) + '. [' + this._materials[i].name + '] ' + this._materials[i].summary)
    }
    return lines.join('\n')
  },

  getMaterialContent: function(name) {
    for (var i = 0; i < this._materials.length; i++) {
      if (this._materials[i].name === name) return this._materials[i].content
    }
    return ''
  },

  cache: function() {
    if (this._folder) cap.storage.set('material_cache_' + this._folder, this._materials)
  },

  _detectType: function(name) {
    if (!name) return 'text'
    var ext = name.split('.').pop().toLowerCase()
    if (['txt', 'md', 'markdown'].indexOf(ext) >= 0) return 'text'
    if (['json', 'csv'].indexOf(ext) >= 0) return 'data'
    if (['jpg', 'jpeg', 'png', 'gif', 'webp'].indexOf(ext) >= 0) return 'image'
    if (ext === 'pdf') return 'pdf'
    if (['doc', 'docx'].indexOf(ext) >= 0) return 'doc'
    return 'text'
  },

  _extractKeywords: function(content) {
    if (!content) return []
    var words = content.split(/[\s,，。、；;：:！!？?\n\r\t（）()【】\[\]{}""'''"'<>]+/)
    var freq = {}
    for (var i = 0; i < words.length; i++) {
      var w = words[i].trim()
      if (w.length < 2 || w.length > 10) continue
      freq[w] = (freq[w] || 0) + 1
    }
    return Object.keys(freq).filter(function(k) { return freq[k] >= 2 }).sort(function(a, b) { return freq[b] - freq[a] }).slice(0, 20)
  },

  _generateSummaries: async function() {
    if (this._materials.length === 0) return
    for (var i = 0; i < this._materials.length; i += 3) {
      var batch = this._materials.slice(i, i + 3)
      var batchText = batch.map(function(m, idx) {
        return '资料' + (idx + 1) + ' [' + m.name + ']:\n' + m.content.slice(0, 1000)
      }).join('\n\n')
      try {
        var reply = await cap.llm.complete([
          { role: 'system', content: '你是资料摘要助手。为每份资料生成一句话摘要(50字内)，格式: 资料N: 摘要' },
          { role: 'user', content: batchText + '\n\n请为以上每份资料生成摘要：' }
        ], { max_tokens: 300, temperature: 0.2 })
        if (reply) {
          var lines = reply.split('\n')
          for (var j = 0; j < lines.length && j < batch.length; j++) {
            var line = lines[j].trim()
            var colonIdx = line.indexOf('：')
            if (colonIdx < 0) colonIdx = line.indexOf(':')
            if (colonIdx >= 0) line = line.slice(colonIdx + 1).trim()
            if (line.length > 5) batch[j].summary = line
          }
        }
      } catch (e) {
        cap.runtime.log('material', '摘要生成失败: ' + (e.message || e))
      }
    }
  },
}

// ═══════════════════════════════════════════════════════════════════════
// 模块 2: ConversationEngine — LLM 多轮对话引擎
// ═══════════════════════════════════════════════════════════════════════
const ConversationEngine = {
  // 创建对话上下文
  createContext: function(merchantInfo, keywords, style, materials) {
    return {
      merchantInfo: merchantInfo || '',
      keywords: keywords || [],
      style: style || '专业友好',
      styleDesc: COMM_STYLES[style] || COMM_STYLES['专业友好'],
      materials: materials || [],
      history: [],
      stage: 'opening',
      merchantMood: 'unknown',
      sentMaterials: [],
      round: 0,
      startTime: Date.now(),
    }
  },

  // 生成开场白 — 个性化、有温度的第一条消息
  generateOpening: async function(ctx) {
    var materialSummary = MaterialManager.getSummaryText()
    var systemPrompt = '你是一位经验丰富的选品采购经理，正在通过微信小店与商家沟通。\n'
      + '沟通风格：' + ctx.styleDesc + '\n'
      + '你的目标是：了解产品信息、询价、建立合作关系。\n'
      + '要求：\n'
      + '1. 开场白要个性化，结合商品信息，不要千篇一律\n'
      + '2. 语气自然友好，像真人一样，不要机器人感\n'
      + '3. 简短（80-120字），一句话说清来意\n'
      + '4. 以提问结尾，引导对方回复\n'
      + '5. 只输出消息内容，不要解释\n'

    var userPrompt = '选品关键词：' + ctx.keywords.join('、') + '\n\n'
      + '商家商品信息：\n' + (ctx.merchantInfo || '未知').slice(0, 500) + '\n\n'
      + (materialSummary ? materialSummary + '\n\n' : '')
      + '请生成开场白：'

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt }
      ], { max_tokens: 200, temperature: 0.6 })
      if (reply && reply.trim().length > 10) {
        ctx.history.push({ role: 'user', content: reply.trim(), ts: Date.now() })
        ctx.round = 1
        ctx.stage = 'inquiring'
        return { ok: true, message: reply.trim() }
      }
    } catch (e) {
      cap.runtime.log('conv', '开场白生成失败: ' + (e.message || e))
    }
    // 后备
    var fallback = '您好！我是选品负责人，对贵店的' + ctx.keywords.join('、') + '产品很感兴趣。请问可以发一份产品目录和最新报价吗？期待合作！'
    ctx.history.push({ role: 'user', content: fallback, ts: Date.now() })
    ctx.round = 1
    return { ok: true, message: fallback }
  },

  // 分析商家回复 — 理解意图、情绪、关键信息
  analyzeReply: async function(ctx, merchantReply) {
    ctx.history.push({ role: 'merchant', content: merchantReply, ts: Date.now() })

    var systemPrompt = '你是沟通分析助手。分析商家的回复，输出JSON格式的分析结果。\n'
      + '字段说明：\n'
      + '- intent: 商家意图 (interested/hesitant/resistant/asking_info/neutral)\n'
      + '- mood: 商家情绪 (positive/neutral/negative)\n'
      + '- keyPoints: 商家提到的关键信息数组\n'
      + '- needsMaterial: 是否需要发送产品资料 (true/false)\n'
      + '- suggestedAction: 建议下一步动作 (send_material/answer_question/negotiate/close/follow_up)\n'
      + '只输出JSON，不要解释。\n'

    var historyText = ctx.history.map(function(h) {
      return (h.role === 'user' ? '我' : '商家') + ': ' + h.content
    }).join('\n')

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: '对话历史：\n' + historyText + '\n\n请分析商家最后一条回复：' }
      ], { max_tokens: 300, temperature: 0.2 })

      if (reply) {
        // 尝试提取 JSON
        var jsonStr = reply.trim()
        if (jsonStr.indexOf('{') >= 0) {
          jsonStr = jsonStr.substring(jsonStr.indexOf('{'), jsonStr.lastIndexOf('}') + 1)
          var analysis = JSON.parse(jsonStr)
          ctx.merchantMood = analysis.intent || 'neutral'
          if (analysis.needsMaterial) ctx.stage = 'material_sharing'
          return { ok: true, analysis: analysis }
        }
      }
    } catch (e) {
      cap.runtime.log('conv', '回复分析失败: ' + (e.message || e))
    }

    return {
      ok: true,
      analysis: {
        intent: 'neutral',
        mood: 'neutral',
        keyPoints: [],
        needsMaterial: false,
        suggestedAction: 'follow_up',
      }
    }
  },

  // 生成跟进消息 — 根据分析结果和对话历史生成下一步回复
  generateFollowUp: async function(ctx, analysis) {
    var systemPrompt = '你是一位经验丰富的选品采购经理，正在与商家多轮沟通。\n'
      + '沟通风格：' + ctx.styleDesc + '\n'
      + '当前对话阶段：' + ctx.stage + '\n'
      + '商家状态：' + (analysis.intent || 'neutral') + ' / ' + (analysis.mood || 'neutral') + '\n'
      + '要求：\n'
      + '1. 回复要自然、有温度，像真人聊天\n'
      + '2. 针对商家的回复内容做针对性回应\n'
      + '3. 推进对话目标（了解产品、询价、建合作）\n'
      + '4. 如果商家犹豫，要温和引导，不要施压\n'
      + '5. 如果商家提出问题，先回答问题再推进\n'
      + '6. 简短（50-100字），不要长篇大论\n'
      + '7. 只输出消息内容\n'

    var historyText = ctx.history.map(function(h) {
      return (h.role === 'user' ? '我' : '商家') + ': ' + h.content
    }).join('\n')

    var userPrompt = '对话历史：\n' + historyText + '\n\n'
      + '商家关键信息：' + JSON.stringify(analysis.keyPoints || []) + '\n'
      + '建议动作：' + (analysis.suggestedAction || 'follow_up') + '\n\n'
      + '请生成下一条回复：'

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt }
      ], { max_tokens: 200, temperature: 0.6 })
      if (reply && reply.trim().length > 5) {
        ctx.history.push({ role: 'user', content: reply.trim(), ts: Date.now() })
        ctx.round++
        return { ok: true, message: reply.trim() }
      }
    } catch (e) {
      cap.runtime.log('conv', '跟进生成失败: ' + (e.message || e))
    }

    // 后备
    var fb = '好的，了解了。请问具体的价格区间和起订量是多少呢？'
    ctx.history.push({ role: 'user', content: fb, ts: Date.now() })
    ctx.round++
    return { ok: true, message: fb }
  },

  // 生成跟进提醒（商家未回复时的温和提醒）
  generateFollowUpReminder: async function(ctx) {
    var systemPrompt = '商家一段时间没有回复了。请生成一条温和的跟进提醒。\n'
      + '要求：\n1. 不要催促感，要关心式\n2. 可以换个角度提个问题\n3. 30-50字\n4. 只输出消息\n'

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: '选品关键词：' + ctx.keywords.join('、') + '\n已发送消息数：' + ctx.round }
      ], { max_tokens: 80, temperature: 0.5 })
      if (reply && reply.trim().length > 5) {
        ctx.history.push({ role: 'user', content: reply.trim(), ts: Date.now() })
        ctx.round++
        return { ok: true, message: reply.trim() }
      }
    } catch (e) {}

    var fb = '您好，不知道是否方便回复？如果有任何疑问我可以解答～'
    ctx.history.push({ role: 'user', content: fb, ts: Date.now() })
    ctx.round++
    return { ok: true, message: fb }
  },

  // 判断对话是否应该结束
  isConversationDone: async function(ctx, maxRounds) {
    // 超过最大轮次
    if (ctx.round >= maxRounds) return { done: true, reason: 'max_rounds' }

    // 对话历史为空
    if (ctx.history.length === 0) return { done: false, reason: '' }

    // 最后一条是商家回复且表明结束
    var lastEntry = ctx.history[ctx.history.length - 1]
    if (lastEntry.role !== 'merchant') return { done: false, reason: '' }

    // 用 LLM 判断
    try {
      var historyText = ctx.history.map(function(h) {
        return (h.role === 'user' ? '我' : '商家') + ': ' + h.content
      }).join('\n')

      var reply = await cap.llm.complete([
        { role: 'system', content: '判断这段选品沟通对话是否已经自然结束。只回答 true 或 false，然后简述原因。格式: true/false|原因' },
        { role: 'user', content: historyText }
      ], { max_tokens: 50, temperature: 0.1 })

      if (reply) {
        var parts = reply.trim().split('|')
        var done = parts[0].trim().toLowerCase().indexOf('true') >= 0
        return { done: done, reason: parts[1] ? parts[1].trim() : (done ? '自然结束' : '继续') }
      }
    } catch (e) {}

    return { done: false, reason: 'unknown' }
  },

  // 生成对话总结
  summarizeConversation: async function(ctx) {
    var historyText = ctx.history.map(function(h) {
      return (h.role === 'user' ? '我' : '商家') + ': ' + h.content
    }).join('\n')

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: '你是沟通总结助手。总结这段选品沟通的成果。输出JSON: { outcome: "positive/neutral/negative", summary: "一句话总结", keyInfo: "获得的关键信息", followUpNeeded: true/false }' },
        { role: 'user', content: historyText }
      ], { max_tokens: 200, temperature: 0.2 })

      if (reply) {
        var jsonStr = reply.trim()
        if (jsonStr.indexOf('{') >= 0) {
          jsonStr = jsonStr.substring(jsonStr.indexOf('{'), jsonStr.lastIndexOf('}') + 1)
          return JSON.parse(jsonStr)
        }
      }
    } catch (e) {}

    return {
      outcome: 'neutral',
      summary: '沟通' + ctx.round + '轮',
      keyInfo: '',
      followUpNeeded: false,
    }
  },
}

// ═══════════════════════════════════════════════════════════════════════
// 模块 3: CommunicationLogger — 沟通日志记录
// ═══════════════════════════════════════════════════════════════════════
const CommunicationLogger = {
  _logs: [],

  // 记录一次完整的商家沟通
  logConversation: function(merchantInfo, convCtx, summary) {
    var entry = {
      id: cap.runtime.uuid(),
      ts: Date.now(),
      iso: cap.runtime.iso(),
      merchantInfo: merchantInfo || '',
      keywords: convCtx.keywords,
      style: convCtx.style,
      rounds: convCtx.round,
      duration: Date.now() - convCtx.startTime,
      history: convCtx.history,
      stage: convCtx.stage,
      merchantMood: convCtx.merchantMood,
      sentMaterials: convCtx.sentMaterials,
      summary: summary || {},
    }
    this._logs.push(entry)
    cap.storage.append(LOG_KEY, entry)
    return entry
  },

  // 获取所有日志
  getAll: function() {
    if (this._logs.length === 0) {
      this._logs = cap.storage.get(LOG_KEY, [])
    }
    return this._logs
  },

  // 获取统计
  getStats: function() {
    var logs = this.getAll()
    if (logs.length === 0) return { total: 0, positive: 0, neutral: 0, negative: 0, avgRounds: 0 }

    var positive = 0, neutral = 0, negative = 0
    var totalRounds = 0
    for (var i = 0; i < logs.length; i++) {
      var outcome = logs[i].summary && logs[i].summary.outcome
      if (outcome === 'positive') positive++
      else if (outcome === 'negative') negative++
      else neutral++
      totalRounds += logs[i].rounds || 0
    }
    return {
      total: logs.length,
      positive: positive,
      neutral: neutral,
      negative: negative,
      avgRounds: Math.round(totalRounds / logs.length * 10) / 10,
    }
  },

  // 清空日志
  clear: function() {
    this._logs = []
    cap.storage.set(LOG_KEY, [])
  },
}

// ═══════════════════════════════════════════════════════════════════════
// 模块 4: SelfEvolution — Hermes 自进化
// ═══════════════════════════════════════════════════════════════════════
const SelfEvolution = {
  // 分析历史沟通数据，提取优化建议
  analyzeAndEvolve: async function() {
    var logs = CommunicationLogger.getAll()
    if (logs.length < 2) return { ok: false, note: '沟通数据不足（需至少2条）' }

    var stats = CommunicationLogger.getStats()

    // 准备分析数据
    var analysisData = {
      stats: stats,
      conversations: logs.slice(-20).map(function(l) {
        return {
          rounds: l.rounds,
          outcome: l.summary && l.summary.outcome,
          style: l.style,
          merchantMood: l.merchantMood,
          keywords: l.keywords,
          firstMsg: l.history && l.history[0] ? l.history[0].content : '',
          summary: l.summary && l.summary.summary,
        }
      }),
    }

    var systemPrompt = '你是选品沟通策略优化专家。分析历史沟通数据，输出优化建议。\n'
      + '输出JSON格式：\n'
      + '{\n'
      + '  "bestStyle": "最佳沟通风格",\n'
      + '  "openingTips": ["开场白优化建议"],\n'
      + '  "followUpTips": ["跟进策略建议"],\n'
      + '  "materialTiming": "资料发送时机建议",\n'
      + '  "commonObjections": ["常见拒绝理由"],\n'
      + '  "successPatterns": ["成功模式"],\n'
      + '  "updatedParams": { "maxConvRounds": 数字, "replyWaitSecs": 数字, "commStyle": "推荐风格" }\n'
      + '}\n只输出JSON。'

    try {
      var reply = await cap.llm.complete([
        { role: 'system', content: systemPrompt },
        { role: 'user', content: JSON.stringify(analysisData, null, 2) }
      ], { max_tokens: 500, temperature: 0.3 })

      var suggestions = null
      if (reply) {
        var jsonStr = reply.trim()
        if (jsonStr.indexOf('{') >= 0) {
          jsonStr = jsonStr.substring(jsonStr.indexOf('{'), jsonStr.lastIndexOf('}') + 1)
          suggestions = JSON.parse(jsonStr)
        }
      }

      // 保存进化记录
      var evolveEntry = {
        id: cap.runtime.uuid(),
        ts: Date.now(),
        iso: cap.runtime.iso(),
        stats: stats,
        suggestions: suggestions,
        analyzedCount: logs.length,
      }
      cap.storage.append(EVOLVE_KEY, evolveEntry)

      // 应用优化建议到配置
      if (suggestions && suggestions.updatedParams) {
        var config = loadConfig()
        if (suggestions.updatedParams.maxConvRounds) config.maxConvRounds = suggestions.updatedParams.maxConvRounds
        if (suggestions.updatedParams.replyWaitSecs) config.replyWaitSecs = suggestions.updatedParams.replyWaitSecs
        if (suggestions.updatedParams.commStyle && COMM_STYLES[suggestions.updatedParams.commStyle]) {
          config.commStyle = suggestions.updatedParams.commStyle
        }
        saveConfig(config)
      }

      // 上报到 Hermes（用于云端分析）
      try {
        if (cap.server && cap.server.reportRun) {
          await cap.server.reportRun({
            skillId: FLOWCHART.skillId,
            version: FLOWCHART.version,
            stats: stats,
            suggestions: suggestions,
            timestamp: cap.runtime.iso(),
          })
        }
      } catch (e) {
        cap.runtime.log('evolve', 'Hermes 上报失败: ' + (e.message || e))
      }

      return { ok: true, suggestions: suggestions, stats: stats }
    } catch (e) {
      cap.runtime.log('evolve', '自进化分析失败: ' + (e.message || e))
      return { ok: false, note: '分析失败: ' + (e.message || e), stats: stats }
    }
  },

  // 获取进化历史
  getHistory: function() {
    return cap.storage.get(EVOLVE_KEY, [])
  },
}

// ═══════════════════════════════════════════════════════════════════════
// 主 handler 入口
// ═══════════════════════════════════════════════════════════════════════
async function handler(params, complete) {
  var action = params.action

  // ── 流程图查看 ──
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_judgments') return (cap.flowchart.get() || FLOWCHART).judgments || FLOWCHART.judgments
  if (action === 'get_trace')     return cap.flowchart.trace

  // ── 配置管理 ──
  if (action === 'setup')  return await setupConfig(params)
  if (action === 'status') return await getStatus()
  if (action === 'get_filter_options') return { ok: true, filterOptions: FILTER_OPTIONS, commStyles: Object.keys(COMM_STYLES) }

  // ── 资料管理 ──
  if (action === 'set_material_folder') return await setMaterialFolder(params)
  if (action === 'load_materials')      return await loadMaterials(params)
  if (action === 'add_material')        { MaterialManager.addMaterial(params.name, params.content); return { ok: true, count: MaterialManager.getAll().length } }
  if (action === 'add_materials')       { MaterialManager.addMaterials(params.materials); return { ok: true, count: MaterialManager.getAll().length } }
  if (action === 'get_materials')       return { ok: true, materials: MaterialManager.getAll() }
  if (action === 'find_materials')      return { ok: true, materials: MaterialManager.findRelevant(params.productInfo, params.keywords) }

  // ── 执行入口 ──
  if (action === 'execute') return await execute(params, complete)
  if (action === 'record')  return await record(params)

  // ── 沟通日志 ──
  if (action === 'get_logs')    return { ok: true, logs: CommunicationLogger.getAll(), stats: CommunicationLogger.getStats() }
  if (action === 'clear_logs')  { CommunicationLogger.clear(); return { ok: true } }

  // ── 自进化 ──
  if (action === 'self_evolve')      return await SelfEvolution.analyzeAndEvolve()
  if (action === 'get_evolve_history') return { ok: true, history: SelfEvolution.getHistory() }

  // ── 控制流 ──
  if (action === 'step_once') { cap.control.stepOnce(); return { ok: true, paused: false, stepOnce: true } }
  if (action === 'pause')     { cap.control.pause();    return { ok: true, paused: true } }
  if (action === 'resume')    { cap.control.resume();   return { ok: true, paused: false } }
  if (action === 'stop')      { cap.control.stop();     return { ok: true, stopRequested: true } }

  // ── 断点管理 ──
  if (action === 'add_breakpoint')    { cap.control.addBreakpoint(params.nodeId);    return { ok: true } }
  if (action === 'remove_breakpoint') { cap.control.removeBreakpoint(params.nodeId); return { ok: true } }
  if (action === 'clear_breakpoints') { cap.control.clearBreakpoints();              return { ok: true } }

  // ── 升级管理 ──
  if (action === 'check_upgrade') return await cap.skillMarket.checkUpgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'upgrade')       return await cap.skillMarket.upgrade(params.skillId || FLOWCHART.skillId)
  if (action === 'rollback')      return await cap.skillMarket.rollback(params.skillId || FLOWCHART.skillId)

  // ── 单步操作 ──
  if (action === 'open_page')        return await openPage(params)
  if (action === 'apply_filters')    return await applyAllFilters(params.filters || {})
  if (action === 'contact_merchant') return await contactMerchant(params.index || 0)
  if (action === 'send_message')     return await sendMessageAction(params)
  if (action === 'extract_page')     return await extractPageInfo()
  if (action === 'extract_merchants') return await extractMerchantList()

  // ── 对话操作 ──
  if (action === 'generate_opening')   return await generateOpeningAction(params)
  if (action === 'analyze_reply')      return await analyzeReplyAction(params)
  if (action === 'generate_followup')  return await generateFollowUpAction(params)

  return { ok: false, error: 'unknown action: ' + action }
}

// ═══════════════════════════════════════════════════════════════════════
// 配置管理
// ═══════════════════════════════════════════════════════════════════════
function loadConfig() {
  return cap.storage.get(STORAGE_KEY, {
    keywords: [],
    filters: {},
    materialFolder: '',
    maxMerchants: 5,
    maxConvRounds: 8,
    replyWaitSecs: 120,
    commStyle: '专业友好',
    autoEvolve: true,
  })
}

function saveConfig(config) {
  cap.storage.set(STORAGE_KEY, config)
}

async function setupConfig(params) {
  var config = loadConfig()
  if (params.keywords) config.keywords = Array.isArray(params.keywords) ? params.keywords : [params.keywords]
  if (params.filters) config.filters = params.filters
  if (params.materialFolder) config.materialFolder = params.materialFolder
  if (params.maxMerchants) config.maxMerchants = params.maxMerchants
  if (params.maxConvRounds) config.maxConvRounds = params.maxConvRounds
  if (params.replyWaitSecs) config.replyWaitSecs = params.replyWaitSecs
  if (params.commStyle && COMM_STYLES[params.commStyle]) config.commStyle = params.commStyle
  if (typeof params.autoEvolve === 'boolean') config.autoEvolve = params.autoEvolve
  saveConfig(config)
  return { ok: true, config: config }
}

async function getStatus() {
  var config = loadConfig()
  var targets = await safeGetTargets()
  var stats = CommunicationLogger.getStats()
  return {
    config: config,
    cdpConnected: Array.isArray(targets) && targets.length > 0,
    targets: (targets || []).map(function(t) { return { id: t.id, title: t.title, url: t.url } }),
    materialCount: MaterialManager.getAll().length,
    commStats: stats,
    evolveHistory: SelfEvolution.getHistory().slice(-3),
  }
}

// ═══════════════════════════════════════════════════════════════════════
// 资料文件夹管理
// ═══════════════════════════════════════════════════════════════════════
async function setMaterialFolder(params) {
  // 通过 UI 让用户选择文件夹
  if (!params.folder) {
    var folderInput = await askUser(
      '请输入产品介绍资料文件夹路径',
      '请输入包含产品介绍资料的文件夹完整路径。\n例如：/Users/yourname/Documents/products\n支持格式：txt, md, json, csv, doc'
    )
    if (!folderInput || !folderInput.trim()) return { ok: false, note: '用户未输入路径' }
    params.folder = folderInput.trim()
  }

  MaterialManager.setFolder(params.folder)
  var config = loadConfig()
  config.materialFolder = params.folder
  saveConfig(config)

  // 立即尝试加载
  var loadResult = await MaterialManager.loadFromFolder(params.folder)
  return { ok: loadResult.ok, folder: params.folder, note: loadResult.note, count: loadResult.count || 0 }
}

async function loadMaterials(params) {
  var config = loadConfig()
  var folder = params.folder || config.materialFolder || MaterialManager.getFolder()
  if (!folder) {
    return await setMaterialFolder({})
  }
  var result = await MaterialManager.loadFromFolder(folder)
  return result
}

// ═══════════════════════════════════════════════════════════════════════
// 交互式获取用户筛选配置
// ═══════════════════════════════════════════════════════════════════════
async function getConfigFromUser(existingKeywords) {
  // 1. 获取关键词
  var keywords = existingKeywords || []
  if (!keywords.length) {
    var kwInput = await askUser(
      '请输入选品关键词',
      '请输入用于搜索商品的关键词，多个用逗号分隔。\n例如：女装,夏季,连衣裙'
    )
    if (!kwInput || !kwInput.trim()) return { ok: false, note: '用户未输入关键词' }
    keywords = kwInput.split(/[,，\n]/).map(function(k) { return k.trim() }).filter(Boolean)
  }

  var filters = {}

  // 2. 商品排序
  var sortChoice = await askUserWithOptions('商品排序 — 请选择排序方式', FILTER_OPTIONS.sort.options, FILTER_OPTIONS.sort.default)
  if (sortChoice) filters.sort = sortChoice

  // 3. 服务保障 (多选)
  var serviceChoice = await askUserMultiSelect('服务保障 — 可多选（输入序号，逗号分隔，留空跳过）', FILTER_OPTIONS.service.options)
  if (serviceChoice && serviceChoice.length) filters.service = serviceChoice

  // 4. 价格范围
  var usePrice = await askUserWithOptions('价格范围 — 是否设置价格筛选？', ['不设置', '设置价格范围'], '不设置')
  if (usePrice === '设置价格范围') {
    var compChoice = await askUserWithOptions('价格筛选维度 — 按价格还是佣金比例？', FILTER_OPTIONS.priceRange.compositionOptions, FILTER_OPTIONS.priceRange.defaultComposition)
    var minVal = await askUser('最低' + compChoice + '（留空不限制）', '例如：0')
    var maxVal = await askUser('最高' + compChoice + '（留空不限制）', '例如：5000')
    filters.priceRange = { composition: compChoice, min: minVal || '', max: maxVal || '' }
  }

  // 5. 月销量
  var salesChoice = await askUserWithOptions('月销量 — 请选择月销量范围（留空跳过）', ['不限制'].concat(FILTER_OPTIONS.monthlySales.options), '不限制')
  if (salesChoice && salesChoice !== '不限制') filters.monthlySales = salesChoice

  // 6. 好评率
  var ratingChoice = await askUserWithOptions('好评率 — 请选择好评率范围（留空跳过）', ['不限制'].concat(FILTER_OPTIONS.positiveRate.options), '不限制')
  if (ratingChoice && ratingChoice !== '不限制') filters.positiveRate = ratingChoice

  // 7. 店铺评分
  var shopChoice = await askUserWithOptions('店铺评分 — 请选择店铺评分范围（留空跳过）', ['不限制'].concat(FILTER_OPTIONS.shopRating.options), '不限制')
  if (shopChoice && shopChoice !== '不限制') filters.shopRating = shopChoice

  // 8. 沟通风格
  var styleChoice = await askUserWithOptions('沟通风格 — 请选择', Object.keys(COMM_STYLES), '专业友好')

  // 9. 产品介绍资料文件夹
  var materialFolderInput = await askUser(
    '产品介绍资料文件夹（可选）',
    '请输入包含产品介绍资料的文件夹路径。\n留空则不使用资料辅助。'
  )
  var materialFolder = materialFolderInput && materialFolderInput.trim() ? materialFolderInput.trim() : ''

  // 10. 最多联系商家数
  var maxInput = await askUser('最多联系多少个商家？', '默认 5')
  var maxMerchants = parseInt(maxInput, 10) || 5

  // 11. 每个商家最大对话轮次
  var maxConvInput = await askUser('每个商家最多对话几轮？', '默认 8')
  var maxConvRounds = parseInt(maxConvInput, 10) || 8

  // 保存配置
  var config = loadConfig()
  config.keywords = keywords
  config.filters = filters
  config.commStyle = styleChoice || '专业友好'
  config.materialFolder = materialFolder
  config.maxMerchants = maxMerchants
  config.maxConvRounds = maxConvRounds
  saveConfig(config)

  // 加载资料
  if (materialFolder) {
    MaterialManager.setFolder(materialFolder)
    await MaterialManager.loadFromFolder(materialFolder)
  }

  return {
    ok: true, note: '配置完成',
    keywords: keywords, filters: filters, commStyle: styleChoice || '专业友好',
    materialFolder: materialFolder, maxMerchants: maxMerchants, maxConvRounds: maxConvRounds,
  }
}

// ── 用户交互辅助 ─────────────────────────────────────────────────────────
async function askUser(title, context) {
  if (!cap.ui || !cap.ui.prompt) return null
  try {
    return await cap.ui.prompt(title, { context: context || '', suggestions: [], timeout: 120000 })
  } catch (e) { return null }
}

async function askUserWithOptions(title, options, defaultOption) {
  if (!cap.ui || !cap.ui.prompt) return defaultOption || null
  try {
    var context = '请选择一个选项（输入序号或名称）：\n'
    for (var i = 0; i < options.length; i++) {
      context += (i + 1) + '. ' + options[i]
      if (options[i] === defaultOption) context += ' (默认)'
      context += '\n'
    }
    var reply = await cap.ui.prompt(title, { context: context, suggestions: options, timeout: 120000 })
    if (!reply || !reply.trim()) return defaultOption || null
    reply = reply.trim()
    var num = parseInt(reply, 10)
    if (!isNaN(num) && num >= 1 && num <= options.length) return options[num - 1]
    for (var j = 0; j < options.length; j++) {
      if (options[j] === reply || options[j].indexOf(reply) >= 0) return options[j]
    }
    return defaultOption || null
  } catch (e) { return defaultOption || null }
}

async function askUserMultiSelect(title, options) {
  if (!cap.ui || !cap.ui.prompt) return []
  try {
    var context = '可多选，输入序号用逗号分隔（如 1,3），留空跳过：\n'
    for (var i = 0; i < options.length; i++) context += (i + 1) + '. ' + options[i] + '\n'
    var reply = await cap.ui.prompt(title, { context: context, suggestions: options, timeout: 120000 })
    if (!reply || !reply.trim()) return []
    var parts = reply.split(/[,，\s]+/).map(function(s) { return s.trim() }).filter(Boolean)
    var selected = []
    for (var j = 0; j < parts.length; j++) {
      var num = parseInt(parts[j], 10)
      if (!isNaN(num) && num >= 1 && num <= options.length) {
        selected.push(options[num - 1])
      } else {
        for (var k = 0; k < options.length; k++) {
          if (options[k] === parts[j] || options[k].indexOf(parts[j]) >= 0) { selected.push(options[k]); break }
        }
      }
    }
    return selected
  } catch (e) { return [] }
}

// ═══════════════════════════════════════════════════════════════════════
// LLM 分析关键词 → 匹配分类标签
// ═══════════════════════════════════════════════════════════════════════
async function analyzeCategory(keywords, pageCategories) {
  var categories = pageCategories && pageCategories.length ? pageCategories : DEFAULT_CATEGORIES
  var realCats = categories.filter(function(c) { return c !== '全部' && c !== '更多' })

  try {
    var prompt = '你是选品分类助手。根据用户的搜索关键词，从以下商品分类中选择最匹配的一个。\n\n'
      + '可选分类：' + realCats.join('、') + '\n'
      + '用户关键词：' + keywords.join('、') + '\n\n'
      + '规则：\n1. 只返回分类名称，不要解释\n2. 如果都不匹配，返回"全部"\n\n分类：'

    var reply = await cap.llm.complete([
      { role: 'system', content: '你只返回一个分类名称，不讨论不解释。' },
      { role: 'user', content: prompt }
    ], { max_tokens: 20, temperature: 0.1 })

    if (reply) {
      reply = reply.trim()
      for (var i = 0; i < categories.length; i++) {
        if (categories[i] === reply) return reply
      }
      for (var j = 0; j < categories.length; j++) {
        if (categories[j].indexOf(reply) >= 0 || reply.indexOf(categories[j]) >= 0) {
          if (categories[j] !== '全部' && categories[j] !== '更多') return categories[j]
        }
      }
    }
  } catch (e) {
    cap.runtime.log('auto-product-comm', 'LLM 分类失败: ' + (e.message || e))
  }
  return '全部'
}

// ═══════════════════════════════════════════════════════════════════════
// execute — 主执行循环 v3.0
// ═══════════════════════════════════════════════════════════════════════
async function execute(params, complete) {
  var MAX_ROUNDS = params.maxRounds || 50
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)

  var config = loadConfig()
  var keywords = (params.keywords && params.keywords.length) ? params.keywords : (config.keywords || [])
  var filters = params.filters || config.filters || {}
  var maxMerchants = params.maxMerchants || config.maxMerchants || 5
  var maxConvRounds = params.maxConvRounds || config.maxConvRounds || 8
  var replyWaitSecs = params.replyWaitSecs || config.replyWaitSecs || 120
  var commStyle = params.commStyle || config.commStyle || '专业友好'
  var autoEvolve = params.autoEvolve !== undefined ? params.autoEvolve : config.autoEvolve

  // 1. 设置流程图 + 重置控制
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  cap.flowchart.pushTrace('start', 'ok', 'v3.0 开始执行')

  // 2. ensure — 检查 CDP
  if (!(await cap.control.check('ensure'))) return _summarize(0, 'stopped', 0)
  var ensureResult = await ensureCdp()
  cap.flowchart.pushTrace('ensure', ensureResult.ok ? 'ok' : 'fail', ensureResult.note)
  if (!ensureResult.ok) {
    cap.flowchart.pushTrace('end', 'fail', 'CDP 未连接')
    return _summarize(0, 'failed', 0)
  }
  var originalTargets = ensureResult.targets || []

  // 3. config? — 检查预配置
  if (!(await cap.control.check('config?'))) return _summarize(0, 'stopped', 0)
  var hasConfig = (keywords.length > 0 && Object.keys(filters).length > 0)

  if (!hasConfig) {
    cap.flowchart.pushTrace('config?', 'ok', 'no → get_config')
    if (!(await cap.control.check('get_config'))) return _summarize(0, 'stopped', 0)
    var configResult = await getConfigFromUser(keywords)
    cap.flowchart.pushTrace('get_config', configResult.ok ? 'ok' : 'fail', configResult.note)
    if (!configResult.ok) {
      cap.flowchart.pushTrace('end', 'stopped', '用户未完成配置')
      return _summarize(0, 'stopped', 0)
    }
    keywords = configResult.keywords
    filters = configResult.filters
    commStyle = configResult.commStyle
    maxMerchants = configResult.maxMerchants
    maxConvRounds = configResult.maxConvRounds
  } else {
    cap.flowchart.pushTrace('config?', 'ok', 'yes → load_materials')
  }

  // 4. load_materials — 加载产品资料
  if (!(await cap.control.check('load_materials'))) return _summarize(0, 'stopped', 0)
  var materialResult = { ok: true, count: MaterialManager.getAll().length }
  if (MaterialManager.getAll().length === 0) {
    var config2 = loadConfig()
    if (config2.materialFolder) {
      materialResult = await MaterialManager.loadFromFolder(config2.materialFolder)
    }
  }
  cap.flowchart.pushTrace('load_materials', materialResult.ok ? 'ok' : 'fail', materialResult.note || ('已有 ' + materialResult.count + ' 份资料'))

  // 5. analyze_cat — LLM 分析分类
  if (!(await cap.control.check('analyze_cat'))) return _summarize(0, 'stopped', 0)
  var category = await analyzeCategory(keywords)
  cap.flowchart.pushTrace('analyze_cat', 'ok', '分类: ' + category)

  // 6. navigate — 打开选品页面
  if (!(await cap.control.check('navigate'))) return _summarize(0, 'stopped', 0)
  var keywordStr = keywords.join(' ')
  var pageUrl = PAGE_BASE_URL + encodeURIComponent(keywordStr)
  var navResult = await navigateToPage(pageUrl)
  cap.flowchart.pushTrace('navigate', navResult.ok ? 'ok' : 'fail', navResult.note)
  if (!navResult.ok) {
    cap.flowchart.pushTrace('end', 'fail', '导航失败')
    return _summarize(0, 'failed', 0)
  }

  // 7. wait_page — 等待加载
  if (!(await cap.control.check('wait_page'))) return _summarize(0, 'stopped', 0)
  var pageResult = await waitForPageLoad()
  cap.flowchart.pushTrace('wait_page', pageResult.ok ? 'ok' : 'fail', pageResult.note)

  // 提取页面实际分类标签
  var pageCats = await extractCategories()
  if (pageCats.length > 0) {
    category = await analyzeCategory(keywords, pageCats)
    cap.runtime.log('auto-product-comm', '页面分类重分析: ' + category)
  }

  // 8. apply_cat — 选择分类标签
  if (!(await cap.control.check('apply_cat'))) return _summarize(0, 'stopped', 0)
  var catResult = await selectCategory(category)
  cap.flowchart.pushTrace('apply_cat', catResult.ok ? 'ok' : 'fail', catResult.note)

  // 9. apply_filters — 批量应用筛选条件
  if (!(await cap.control.check('apply_filters'))) return _summarize(0, 'stopped', 0)
  var filterResult = await applyAllFilters(filters)
  cap.flowchart.pushTrace('apply_filters', 'ok', '筛选已应用')

  // 10. wait_results — 等待筛选结果
  if (!(await cap.control.check('wait_results'))) return _summarize(0, 'stopped', 0)
  var resultsResult = await waitForResults()
  cap.flowchart.pushTrace('wait_results', resultsResult.ok ? 'ok' : 'fail', resultsResult.note)
  if (!resultsResult.ok || resultsResult.count === 0) {
    cap.flowchart.pushTrace('end', 'ok', '无筛选结果')
    return _summarize(0, 'completed', 0)
  }

  // 11. extract_merchants — 提取商家列表
  if (!(await cap.control.check('extract_merchants'))) return _summarize(0, 'stopped', 0)
  var merchantList = await extractMerchantList()
  cap.flowchart.pushTrace('extract_merchants', merchantList.ok ? 'ok' : 'fail', merchantList.note)

  // ══ 多商家沟通循环 ══
  var contactedCount = 0
  var merchantResults = []
  var skipIdx = 0

  for (var mi = 0; mi < MAX_ROUNDS && contactedCount < maxMerchants; mi++) {
    if (!(await cap.control.check('merchant_loop'))) {
      cap.flowchart.pushTrace('end', 'stopped', '用户停止')
      return _summarize(mi, 'stopped', contactedCount)
    }

    // 提取商家信息
    if (!(await cap.control.check('extract_info'))) return _summarize(mi, 'stopped', contactedCount)
    var extractResult = await extractMerchantInfo(skipIdx)
    cap.flowchart.pushTrace('extract_info', extractResult.ok ? 'ok' : 'fail', extractResult.note)

    if (!extractResult.ok) {
      cap.flowchart.pushTrace('end', 'ok', '无更多商家')
      break
    }

    // 点击联系商家
    var contactResult = await contactMerchant(skipIdx)
    cap.flowchart.pushTrace('contact', contactResult.ok ? 'ok' : 'fail', contactResult.note)
    if (!contactResult.ok) {
      skipIdx++
      continue
    }
    skipIdx++

    // 检测新标签页
    if (!(await cap.control.check('wait_reply'))) return _summarize(mi, 'stopped', contactedCount)
    var tabResult = await checkNewTab(originalTargets)
    if (!tabResult.ok || !tabResult.newTarget) {
      cap.runtime.log('auto-product-comm', '未检测到新标签页，跳过')
      continue
    }

    // 切换到聊天页面
    var switchResult = await switchToNewTab(tabResult.newTarget)
    if (!switchResult.ok) continue

    // ══ 单商家多轮对话 ══
    var merchantInfo = extractResult.productInfo || contactResult.productInfo || ''
    var convCtx = ConversationEngine.createContext(merchantInfo, keywords, commStyle, MaterialManager.getAll())

    // 生成开场白
    if (!(await cap.control.check('gen_opening'))) return _summarize(mi, 'stopped', contactedCount)
    var openingResult = await ConversationEngine.generateOpening(convCtx)
    cap.flowchart.pushTrace('gen_opening', openingResult.ok ? 'ok' : 'fail', '开场白: ' + (openingResult.message || '').slice(0, 50))

    // 发送开场白
    if (!(await cap.control.check('send_msg'))) return _summarize(mi, 'stopped', contactedCount)
    var sendResult = await typeAndSendMessage(openingResult.message)
    cap.flowchart.pushTrace('send_msg', sendResult.ok ? 'ok' : 'fail', sendResult.note)

    // 多轮对话循环
    var convDone = false
    var noReplyCount = 0
    var maxNoReply = 2  // 最多跟进2次未回复就放弃

    while (!convDone) {
      if (!(await cap.control.check())) { convDone = true; break }

      // 等待商家回复
      if (!(await cap.control.check('wait_reply'))) { convDone = true; break }
      var replyResult = await waitForMerchantReply(replyWaitSecs, convCtx.history.length)
      cap.flowchart.pushTrace('wait_reply', replyResult.ok ? 'ok' : 'fail', replyResult.note)

      // 判断是否回复
      if (!(await cap.control.check('reply?'))) { convDone = true; break }

      if (replyResult.ok && replyResult.reply) {
        // 商家回复了 → 分析回复
        cap.flowchart.pushTrace('reply?', 'ok', 'yes → analyze_reply')
        noReplyCount = 0

        if (!(await cap.control.check('analyze_reply'))) { convDone = true; break }
        var analysisResult = await ConversationEngine.analyzeReply(convCtx, replyResult.reply)
        cap.flowchart.pushTrace('analyze_reply', analysisResult.ok ? 'ok' : 'fail', '意图: ' + (analysisResult.analysis && analysisResult.analysis.intent))

        // 生成跟进消息
        if (!(await cap.control.check('gen_followup'))) { convDone = true; break }
        var followUpResult = await ConversationEngine.generateFollowUp(convCtx, analysisResult.analysis)
        cap.flowchart.pushTrace('gen_followup', followUpResult.ok ? 'ok' : 'fail', '跟进: ' + (followUpResult.message || '').slice(0, 50))

        // 判断是否需要发送资料
        if (!(await cap.control.check('send_material?'))) { convDone = true; break }
        var needMaterial = analysisResult.analysis && analysisResult.analysis.needsMaterial
        var relevantMaterials = []
        if (needMaterial) {
          relevantMaterials = MaterialManager.findRelevant(merchantInfo, keywords)
          if (relevantMaterials.length > 0) {
            cap.flowchart.pushTrace('send_material?', 'ok', 'yes → send_material')
            if (!(await cap.control.check('send_material'))) { convDone = true; break }
            var matResult = await sendMaterialToMerchant(relevantMaterials[0])
            cap.flowchart.pushTrace('send_material', matResult.ok ? 'ok' : 'fail', matResult.note)
            if (matResult.ok) convCtx.sentMaterials.push(relevantMaterials[0].name)
          } else {
            cap.flowchart.pushTrace('send_material?', 'ok', 'no → check_human (无匹配资料)')
          }
        } else {
          cap.flowchart.pushTrace('send_material?', 'ok', 'no → check_human')
        }

        // 发送跟进消息
        if (!(await cap.control.check('send_msg'))) { convDone = true; break }
        var sendFollowResult = await typeAndSendMessage(followUpResult.message)
        cap.flowchart.pushTrace('send_msg', sendFollowResult.ok ? 'ok' : 'fail', sendFollowResult.note)

      } else {
        // 商家未回复
        cap.flowchart.pushTrace('reply?', 'ok', 'no → follow_up?')
        noReplyCount++

        if (noReplyCount >= maxNoReply) {
          cap.flowchart.pushTrace('follow_up?', 'ok', '放弃 (未回复' + noReplyCount + '次)')
          convDone = true
          break
        }

        // 发送跟进提醒
        cap.flowchart.pushTrace('follow_up?', 'ok', '跟进')
        if (!(await cap.control.check('gen_followup'))) { convDone = true; break }
        var reminderResult = await ConversationEngine.generateFollowUpReminder(convCtx)
        if (!(await cap.control.check('send_msg'))) { convDone = true; break }
        await typeAndSendMessage(reminderResult.message)
        cap.flowchart.pushTrace('send_msg', 'ok', '已发送跟进提醒')
      }

      // 人工干预检查
      if (!(await cap.control.check('check_human'))) { convDone = true; break }
      if (cap.control.isPaused()) {
        cap.flowchart.pushTrace('check_human', 'ok', 'yes → human_intervene')
        if (!(await cap.control.check('human_intervene'))) { convDone = true; break }
        var humanResult = await askHumanIntervention(merchantInfo, convCtx)
        cap.flowchart.pushTrace('human_intervene', humanResult.action, humanResult.note)
        if (humanResult.action === 'takeover') {
          // 用户接管，跳过后续自动对话
          convDone = true
          break
        }
        if (humanResult.action === 'modify' && humanResult.message) {
          // 用户修改了下一条消息
          if (!(await cap.control.check('send_msg'))) { convDone = true; break }
          await typeAndSendMessage(humanResult.message)
        }
      } else {
        cap.flowchart.pushTrace('check_human', 'ok', 'no → conv_done?')
      }

      // 判断对话是否结束
      if (!(await cap.control.check('conv_done?'))) { convDone = true; break }
      var doneResult = await ConversationEngine.isConversationDone(convCtx, maxConvRounds)
      cap.flowchart.pushTrace('conv_done?', doneResult.done ? 'ok' : 'ok', doneResult.reason)
      if (doneResult.done) {
        cap.flowchart.pushTrace('conv_done?', 'ok', 'yes → log_conv: ' + doneResult.reason)
        break
      } else {
        cap.flowchart.pushTrace('conv_done?', 'ok', 'no → send_msg')
      }
    }

    // 记录沟通日志
    if (!(await cap.control.check('log_conv'))) return _summarize(mi, 'stopped', contactedCount)
    var summary = await ConversationEngine.summarizeConversation(convCtx)
    var logEntry = CommunicationLogger.logConversation(merchantInfo, convCtx, summary)
    cap.flowchart.pushTrace('log_conv', 'ok', '成果: ' + (summary.outcome || 'unknown'))
    merchantResults.push({ merchant: merchantInfo.slice(0, 100), summary: summary, rounds: convCtx.round })

    contactedCount++
    cap.runtime.log('auto-product-comm', '已联系 #' + contactedCount + ': ' + (summary.outcome || 'unknown'))

    // 继续下一个？
    if (!(await cap.control.check('next_merchant?'))) return _summarize(mi, 'stopped', contactedCount)
    if (contactedCount >= maxMerchants) {
      cap.flowchart.pushTrace('next_merchant?', 'ok', 'no → batch_report: 达上限')
      break
    }
    cap.flowchart.pushTrace('next_merchant?', 'ok', 'yes → merchant_loop')

    // 回到选品页面
    if (tabResult.ok && tabResult.newTarget) {
      await navigateToPage(pageUrl)
      await waitForPageLoad()
      if (category !== '全部') await selectCategory(category)
      originalTargets = await safeGetTargets()
    }
  }

  // ══ 批次报告 ══
  if (!(await cap.control.check('batch_report'))) return _summarize(mi, 'stopped', contactedCount)
  var batchReport = await generateBatchReport(merchantResults)
  cap.flowchart.pushTrace('batch_report', 'ok', batchReport.note)

  // ══ Hermes 自进化 ══
  if (autoEvolve) {
    if (!(await cap.control.check('self_evolve'))) return _summarize(mi, 'stopped', contactedCount)
    var evolveResult = await SelfEvolution.analyzeAndEvolve()
    cap.flowchart.pushTrace('self_evolve', evolveResult.ok ? 'ok' : 'fail', evolveResult.note || (evolveResult.suggestions ? '已优化' : ''))
  }

  cap.flowchart.pushTrace('end', 'ok', '共联系 ' + contactedCount + ' 个商家')
  return _summarize(mi, 'completed', contactedCount)
}

function _summarize(round, status, contacted) {
  return {
    ok: true,
    status: status,
    rounds: round,
    contacted: contacted || 0,
    flowchart: cap.flowchart.get() || FLOWCHART,
    judgments: FLOWCHART.judgments,
    trace: cap.flowchart.trace,
    logs: CommunicationLogger.getAll(),
    stats: CommunicationLogger.getStats(),
  }
}

// ═══════════════════════════════════════════════════════════════════════
// 人工干预
// ═══════════════════════════════════════════════════════════════════════
async function askHumanIntervention(merchantInfo, convCtx) {
  var historyText = convCtx.history.map(function(h) {
    return (h.role === 'user' ? '我' : '商家') + ': ' + h.content
  }).join('\n')

  var context = '当前商家对话已暂停，请选择操作：\n\n'
    + '对话历史：\n' + historyText.slice(-500) + '\n\n'
    + '选项：\n'
    + '1. 继续（自动接管下一轮）\n'
    + '2. 修改下一条消息（输入消息内容）\n'
    + '3. 接管（人工继续，跳过自动对话）\n'
    + '4. 跳过此商家'

  var reply = await askUser('人工干预 — 商家对话暂停', context)
  if (!reply || !reply.trim()) return { action: 'continue', note: '用户未选择，继续自动' }
  reply = reply.trim()

  if (reply === '1' || reply.indexOf('继续') >= 0) return { action: 'continue', note: '继续自动' }
  if (reply === '3' || reply.indexOf('接管') >= 0) return { action: 'takeover', note: '用户接管' }
  if (reply === '4' || reply.indexOf('跳过') >= 0) return { action: 'skip', note: '跳过此商家' }
  // 否则视为修改消息
  return { action: 'modify', message: reply, note: '用户修改消息' }
}

// ═══════════════════════════════════════════════════════════════════════
// 批次报告生成
// ═══════════════════════════════════════════════════════════════════════
async function generateBatchReport(merchantResults) {
  var stats = CommunicationLogger.getStats()
  var reportLines = [
    '═══ 选品沟通批次报告 ═══',
    '时间：' + cap.runtime.iso(),
    '联系商家数：' + merchantResults.length,
    '统计数据：',
    '  正面成果：' + stats.positive,
    '  中性成果：' + stats.neutral,
    '  负面成果：' + stats.negative,
    '  平均对话轮次：' + stats.avgRounds,
    '',
    '各商家沟通结果：',
  ]

  for (var i = 0; i < merchantResults.length; i++) {
    var r = merchantResults[i]
    reportLines.push((i + 1) + '. [' + (r.summary.outcome || 'unknown') + '] ' + r.merchant.slice(0, 50) + ' (' + r.rounds + '轮)')
    if (r.summary.summary) reportLines.push('   ' + r.summary.summary)
  }

  var reportText = reportLines.join('\n')
  cap.runtime.log('batch_report', reportText)

  // 用 LLM 生成更深入的报告分析
  try {
    var reply = await cap.llm.complete([
      { role: 'system', content: '你是选品沟通分析专家。根据沟通结果数据，生成一份简洁的批次分析报告。包括：整体效果评估、成功因素、改进建议。200字内。' },
      { role: 'user', content: reportText }
    ], { max_tokens: 300, temperature: 0.3 })
    if (reply && reply.trim()) {
      reportText += '\n\n═══ LLM 分析 ═══\n' + reply.trim()
    }
  } catch (e) {}

  return { ok: true, note: '报告已生成', report: reportText, stats: stats }
}

// ═══════════════════════════════════════════════════════════════════════
// 等待商家回复 — 轮询聊天窗口新消息
// ═══════════════════════════════════════════════════════════════════════
async function waitForMerchantReply(waitSecs, knownMsgCount) {
  var maxAttempts = Math.floor(waitSecs / 5)
  var lastMsgCount = knownMsgCount || 0

  for (var i = 0; i < maxAttempts; i++) {
    if (!(await cap.control.check())) return { ok: false, note: '用户停止' }

    // 提取聊天消息
    var js = '(function(){'
      + 'var s=[".message-list .message",".chat-msg",".msg-item","[class*=message] [class*=bubble]","[class*=chat] [class*=msg]",".im-msg"];'
      + 'for(var i=0;i<s.length;i++){var els=document.querySelectorAll(s[i]);if(els.length>0){'
      + 'var msgs=[];for(var j=0;j<els.length;j++){var t=(els[j].innerText||"").trim();if(t.length>0)msgs.push(t)}'
      + 'return JSON.stringify({ok:true,count:msgs.length,messages:msgs})'
      + '}}'
      + 'return JSON.stringify({ok:false,count:0})'
      + '})()'

    var result = await cap.cdp.eval(js)
    var info
    try { info = JSON.parse(typeof result === 'string' ? result : JSON.stringify(result)) }
    catch (e) { info = { ok: false, count: 0 } }

    if (info.ok && info.count > lastMsgCount) {
      // 有新消息
      var newMessages = info.messages.slice(lastMsgCount)
      var reply = newMessages.join('\n')
      return { ok: true, reply: reply, note: '商家回复: ' + reply.slice(0, 50) }
    }

    await cap.runtime.sleep(5000)  // 每5秒检查一次
  }

  return { ok: false, note: '等待回复超时(' + waitSecs + 's)' }
}

// ═══════════════════════════════════════════════════════════════════════
// 发送产品资料给商家
// ═══════════════════════════════════════════════════════════════════════
async function sendMaterialToMerchant(material) {
  try {
    // 将资料内容作为消息发送（截取关键部分）
    var content = material.content || ''
    if (content.length > 800) {
      // 用 LLM 精简资料
      try {
        var reply = await cap.llm.complete([
          { role: 'system', content: '你是资料整理助手。将产品资料精简为适合聊天发送的版本（200-300字），保留关键信息。只输出内容。' },
          { role: 'user', content: content.slice(0, 1000) }
        ], { max_tokens: 300, temperature: 0.3 })
        if (reply && reply.trim().length > 50) content = reply.trim()
        else content = content.slice(0, 500)
      } catch (e) {
        content = content.slice(0, 500)
      }
    }

    var message = '给您发一份我们这边的资料供参考：\n\n' + content
    var sendResult = await typeAndSendMessage(message)
    return { ok: sendResult.ok, note: '已发送资料: ' + material.name + ' (' + sendResult.note + ')' }
  } catch (e) {
    return { ok: false, note: '发送资料失败: ' + (e.message || e) }
  }
}

// ═══════════════════════════════════════════════════════════════════════
// 提取商家列表信息
// ═══════════════════════════════════════════════════════════════════════
async function extractMerchantList() {
  try {
    var js = '(function(){' + SR_JS
      + 'var btns=[];'
      + 'var allEl=sr.querySelectorAll("button,a,[role=button]");'
      + 'for(var i=0;i<allEl.length;i++){'
      + 'var t=(allEl[i].innerText||"").trim();'
      + 'if((t.indexOf("联系商家")>=0||t.indexOf("联系卖家")>=0||t==="联系")&&allEl[i].offsetParent!==null){'
      + 'btns.push({idx:i,text:t})'
      + '}'
      + '}'
      + 'return JSON.stringify({ok:true,count:btns.length,buttons:btns.slice(0,20)})'
      + '})()'
    var result = await cap.cdp.eval(js)
    var info
    try { info = JSON.parse(typeof result === 'string' ? result : JSON.stringify(result)) }
    catch (e) { info = { ok: false, count: 0 } }
    return { ok: info.ok, count: info.count, buttons: info.buttons, note: '找到 ' + info.count + ' 个商家' }
  } catch (e) {
    return { ok: false, count: 0, note: '提取失败: ' + (e.message || e) }
  }
}

async function extractMerchantInfo(index) {
  try {
    var idx = index || 0
    var js = '(function(){' + SR_JS
      + 'var allEl=sr.querySelectorAll("button,a,[role=button]");'
      + 'var btns=[];'
      + 'for(var i=0;i<allEl.length;i++){'
      + 'var t=(allEl[i].innerText||"").trim();'
      + 'if((t.indexOf("联系商家")>=0||t.indexOf("联系卖家")>=0||t==="联系")&&allEl[i].offsetParent!==null){'
      + 'btns.push(allEl[i])'
      + '}'
      + '}'
      + 'if(btns.length===0)return JSON.stringify({ok:false,note:"未找到联系按钮"});'
      + 'var bi=Math.min(idx,btns.length-1);'
      + 'var btn=btns[bi];'
      + 'var card=btn.closest("[class*=item],[class*=card],[class*=product],[class*=goods],li");'
      + 'var info=card?(card.innerText||"").slice(0,500):"";'
      + 'return JSON.stringify({ok:true,note:"商家#"+(bi+1)+"/"+btns.length,productInfo:info,totalBtns:btns.length})'
      + '})()'.replace('idx', String(idx))
    var result = await cap.cdp.eval(js)
    var info
    try { info = JSON.parse(typeof result === 'string' ? result : JSON.stringify(result)) }
    catch (e) { info = { ok: false, note: '解析失败' } }
    return info
  } catch (e) {
    return { ok: false, note: '提取失败: ' + (e.message || e) }
  }
}

// ═══════════════════════════════════════════════════════════════════════
// CDP 辅助函数（保留 v2.0 的核心逻辑）
// ═══════════════════════════════════════════════════════════════════════
async function safeGetTargets() {
  try {
    var t = await cap.cdp.getTargets()
    return Array.isArray(t) ? t : []
  } catch (e) { return [] }
}

async function ensureCdp() {
// cap.cdp.getTargets() 内部会调用 _ensureCdpSession() 自动启动浏览器
// 首次调用可能需要等待浏览器进程启动，加入重试机制
var targets = []
for (var attempt = 0; attempt < 3; attempt++) {
targets = await safeGetTargets()
if (targets.length > 0) break
if (attempt < 2) {
cap.runtime.log('cdp', '等待浏览器启动... (尝试 ' + (attempt + 1) + '/3)')
await cap.runtime.sleep(2000)
}
}
if (targets.length === 0) {
return { ok: false, note: '未检测到 CDP 目标。请确保浏览器已安装（Chrome/Edge/Brave）', targets: [] }
}
return { ok: true, note: 'CDP 已连接，' + targets.length + ' 个目标', targets: targets }
}

async function navigateToPage(url) {
  try {
    await cap.cdp.eval('window.location.href = ' + JSON.stringify(url))
    await cap.runtime.sleep(3000)
    return { ok: true, note: '已导航到 ' + url.slice(0, 80) }
  } catch (e) {
    try {
      await cap.cdp.navigate(url)
      await cap.runtime.sleep(3000)
      return { ok: true, note: '已导航 (navigate)' }
    } catch (e2) {
      return { ok: false, note: '导航失败: ' + (e2.message || e2) }
    }
  }
}

async function waitForPageLoad() {
  for (var i = 0; i < 40; i++) {
    if (!(await cap.control.check())) return { ok: false, note: '用户停止' }
    var ready = await cap.cdp.eval('document.readyState')
    if (ready === 'complete' || ready === '"complete"') {
      await cap.runtime.sleep(2000)
      var hasContent = await cap.cdp.eval('(function(){var ma=document.querySelector(\'micro-app[name="pool"]\');if(!ma||!ma.shadowRoot)return "0";var sr=ma.shadowRoot;var dd=sr.querySelectorAll(".weui-desktop-form__dropdown");var cb=sr.querySelectorAll("button");return (dd.length>0||cb.length>5)?"1":"0"})()')
      if (hasContent === '1' || hasContent === true) return { ok: true, note: '页面加载完成 (shadow DOM)' }
    }
    await cap.runtime.sleep(1000)
  }
  return { ok: false, note: '页面加载超时 (40s)' }
}

async function extractCategories() {
  try {
    var js = '(function(){' + SR_JS + 'var tags=sr.querySelectorAll(".tag");var cats=[];tags.forEach(function(t){var text=t.innerText.replace(/\\s+/g," ").trim();if(text&&text!=="更多"&&text.length<10)cats.push(text)});return JSON.stringify(cats)})()'
    var result = await cap.cdp.eval(js)
    var r = typeof result === 'string' ? result : JSON.stringify(result)
    if (r.indexOf('shadowRoot') >= 0) return []
    var cats = []
    try { cats = JSON.parse(r) } catch (e) {}
    return Array.isArray(cats) ? cats : []
  } catch (e) { return [] }
}

async function extractPageInfo() {
  var js = '(function(){' + SR_JS + 'var r={};r.url=window.location.href;r.title=document.title;'
    + 'var dd=sr.querySelectorAll(".weui-desktop-form__dropdown");r.dropdowns=[];'
    + 'dd.forEach(function(d,idx){var l=d.querySelector(".prepend-in");var cv=d.querySelector(".weui-desktop-form__dropdown__value");'
    + 'var m=d.classList.contains("weui-desktop-form__dropdown__multiple");var items=[];'
    + 'd.querySelectorAll(".weui-desktop-dropdown__list-ele").forEach(function(li){var t=li.querySelector(".weui-desktop-dropdown__list-ele__text");var c=li.classList.contains("checked");items.push({text:t?t.innerText.trim():"",checked:c})});'
    + 'r.dropdowns.push({idx:idx,label:l?l.innerText.trim():"",current:cv?cv.innerText.trim():"",multiple:m,items:items})});'
    + 'var tags=sr.querySelectorAll(".tag");r.tags=[];tags.forEach(function(t){r.tags.push({text:t.innerText.trim(),active:t.classList.contains("actived")})});'
    + 'var pi=sr.querySelectorAll(".composition-input input.t-input__inner");r.priceInputs=[];pi.forEach(function(inp){r.priceInputs.push({placeholder:inp.placeholder,value:inp.value})});'
    + 'var cb=sr.querySelectorAll("button,a,[role=button]");r.contactBtns=[];cb.forEach(function(b){var t=(b.innerText||"").trim();if(t.indexOf("联系")>=0&&b.offsetParent!==null)r.contactBtns.push({text:t,tag:b.tagName})});'
    + 'return JSON.stringify(r,null,2)})()'
  var result = await cap.cdp.eval(js)
  return typeof result === 'string' ? result : JSON.stringify(result)
}

async function selectCategory(category) {
  if (!category || category === '全部') return { ok: true, note: '使用默认分类「全部」' }
  try {
    var js = '(function(){' + SR_JS + 'var tags=sr.querySelectorAll(".tag");for(var i=0;i<tags.length;i++){var text=tags[i].innerText.replace(/\\s+/g," ").trim();if(text===cat||text.indexOf(cat)>=0){tags[i].click();return "clicked: "+text}}return "not_found"})()'
      .replace(/cat/g, JSON.stringify(category))
    var result = await cap.cdp.eval(js)
    await cap.runtime.sleep(2000)
    if (result && result.indexOf('clicked') >= 0) return { ok: true, note: '已选择分类: ' + category }
    // 尝试点击"更多"
    await cap.cdp.eval('(function(){' + SR_JS + 'var tags=sr.querySelectorAll(".tag");for(var i=0;i<tags.length;i++){if(tags[i].innerText.indexOf("更多")>=0){tags[i].click();return "more_clicked"}}return "no_more"})()')
    await cap.runtime.sleep(1000)
    var js2 = '(function(){' + SR_JS + 'var tags=sr.querySelectorAll(".tag");for(var i=0;i<tags.length;i++){var text=tags[i].innerText.replace(/\\s+/g," ").trim();if(text===cat2||text.indexOf(cat2)>=0){tags[i].click();return "clicked: "+text}}return "not_found"})()'
      .replace(/cat2/g, JSON.stringify(category))
    var result2 = await cap.cdp.eval(js2)
    await cap.runtime.sleep(2000)
    if (result2 && result2.indexOf('clicked') >= 0) return { ok: true, note: '已选择分类(展开后): ' + category }
    return { ok: false, note: '未找到分类: ' + category }
  } catch (e) {
    return { ok: false, note: '选择分类失败: ' + (e.message || e) }
  }
}

// ── WeUI 下拉菜单操作 ────────────────────────────────────────────────────
async function selectDropdown(labelText, value, isMultiple) {
  if (!value || (Array.isArray(value) && value.length === 0)) {
    return { ok: true, note: '跳过 ' + labelText + ' (未设置)' }
  }
  try {
    var labelJson = JSON.stringify(labelText)
    var values = Array.isArray(value) ? value : [value]
    var valuesJson = JSON.stringify(values)

    var openJs = '(function(){' + SR_JS
      + 'var dd=sr.querySelectorAll(".weui-desktop-form__dropdown");'
      + 'for(var i=0;i<dd.length;i++){'
      + 'var l=dd[i].querySelector(".prepend-in");'
      + 'if(l&&l.innerText.trim()===lbl){'
      + 'var dt=dd[i].querySelector(".weui-desktop-form__dropdown__dt");'
      + 'if(dt){dt.click();return JSON.stringify({ok:true,ddIdx:i})}'
      + '}'
      + '}'
      + 'return JSON.stringify({ok:false,note:"未找到"+lbl+"下拉框"})'
      + '})()'.replace(/lbl/g, labelJson)

    var openResult = await cap.cdp.eval(openJs)
    var openInfo
    try { openInfo = JSON.parse(typeof openResult === 'string' ? openResult : JSON.stringify(openResult)) }
    catch (e) { openInfo = { ok: false } }

    if (!openInfo.ok) return { ok: false, note: openInfo.note || '展开下拉框失败' }
    await cap.runtime.sleep(800)

    var selectJs = '(function(){' + SR_JS
      + 'var dd=sr.querySelectorAll(".weui-desktop-form__dropdown")[' + openInfo.ddIdx + '];'
      + 'var menu=dd.querySelector(".weui-desktop-dropdown-menu");'
      + 'if(!menu)return JSON.stringify({ok:false,note:"下拉菜单未展开"});'
      + 'menu.style.display="block";'
      + 'var items=menu.querySelectorAll(".weui-desktop-dropdown__list-ele");'
      + 'var results=[];'
      + 'for(var vi=0;vi<vals.length;vi++){'
      + 'var found=false;'
      + 'for(var j=0;j<items.length;j++){'
      + 'var t=items[j].querySelector(".weui-desktop-dropdown__list-ele__text");'
      + 'var text=t?t.innerText.trim():"";'
      + 'if(text===vals[vi]){items[j].click();results.push("clicked: "+text);found=true;break}'
      + '}'
      + 'if(!found)results.push("not_found: "+vals[vi])'
      + '}'
      + 'return JSON.stringify({ok:true,results:results})'
      + '})()'.replace(/vals/g, valuesJson === '[]' ? '[]' : valuesJson)

    var selectResult = await cap.cdp.eval(selectJs)
    var selectInfo
    try { selectInfo = JSON.parse(typeof selectResult === 'string' ? selectResult : JSON.stringify(selectResult)) }
    catch (e) { selectInfo = { ok: false } }

    await cap.cdp.eval('document.querySelector(\'micro-app[name="pool"]\').click()')
    await cap.runtime.sleep(500)

    if (selectInfo.ok) return { ok: true, note: labelText + ': ' + (selectInfo.results || []).join('; ') }
    return { ok: false, note: labelText + ' 选择失败: ' + (selectInfo.note || '') }
  } catch (e) {
    return { ok: false, note: labelText + ' 操作异常: ' + (e.message || e) }
  }
}

async function setPriceRange(priceRange) {
  if (!priceRange || (!priceRange.min && !priceRange.max)) {
    return { ok: true, note: '跳过价格范围 (未设置)' }
  }
  try {
    var comp = priceRange.composition || '价格'
    var minVal = priceRange.min || ''
    var maxVal = priceRange.max || ''

    if (comp) {
      var compJson = JSON.stringify(comp)
      await cap.cdp.eval('(function(){' + SR_JS
        + 'var cd=sr.querySelector(".composition-input .weui-desktop-form__dropdown");'
        + 'if(!cd)return "no_comp";var dt=cd.querySelector(".weui-desktop-form__dropdown__dt");if(dt)dt.click();return "opened"})()')
      await cap.runtime.sleep(500)
      await cap.cdp.eval('(function(){' + SR_JS
        + 'var cd=sr.querySelector(".composition-input .weui-desktop-form__dropdown");if(!cd)return "no_comp";'
        + 'var items=cd.querySelectorAll(".weui-desktop-dropdown__list-ele");'
        + 'for(var i=0;i<items.length;i++){var t=items[i].querySelector(".weui-desktop-dropdown__list-ele__text");var text=t?t.innerText.trim():"";if(text===c){items[i].click();return "selected: "+text}}return "not_found"})()'.replace(/c/g, compJson))
      await cap.runtime.sleep(500)
      await cap.cdp.eval('document.querySelector(\'micro-app[name="pool"]\').click()')
      await cap.runtime.sleep(300)
    }

    var minJson = JSON.stringify(minVal)
    var maxJson = JSON.stringify(maxVal)
    var inputJs = '(function(){' + SR_JS
      + 'var inputs=sr.querySelectorAll(".composition-input input.t-input__inner");'
      + 'if(inputs.length<2)return "no_inputs";'
      + 'if(minV){inputs[0].focus();inputs[0].value=minV;inputs[0].dispatchEvent(new Event("input",{bubbles:true}));inputs[0].dispatchEvent(new Event("change",{bubbles:true}))}'
      + 'if(maxV){inputs[1].focus();inputs[1].value=maxV;inputs[1].dispatchEvent(new Event("input",{bubbles:true}));inputs[1].dispatchEvent(new Event("change",{bubbles:true}))}'
      + 'return "set: "+minV+"-"+maxV'
      + '})()'.replace(/minV/g, minJson).replace(/maxV/g, maxJson)

    var result = await cap.cdp.eval(inputJs)
    await cap.runtime.sleep(500)
    return { ok: true, note: '价格范围: ' + comp + ' ' + (minVal || '0') + '-' + (maxVal || '∞') + ' (' + result + ')' }
  } catch (e) {
    return { ok: false, note: '价格范围设置失败: ' + (e.message || e) }
  }
}

async function applyAllFilters(filters) {
  var results = {}
  if (filters.sort) results.sort = await selectDropdown('商品排序', filters.sort, false)
  if (filters.service) results.service = await selectDropdown('服务保障', filters.service, true)
  if (filters.priceRange) results.priceRange = await setPriceRange(filters.priceRange)
  if (filters.monthlySales) results.monthlySales = await selectDropdown('月销量', filters.monthlySales, false)
  if (filters.positiveRate) results.positiveRate = await selectDropdown('好评率', filters.positiveRate, false)
  if (filters.shopRating) results.shopRating = await selectDropdown('店铺评分', filters.shopRating, false)
  return { ok: true, results: results }
}

async function waitForResults() {
  for (var i = 0; i < 20; i++) {
    if (!(await cap.control.check())) return { ok: false, note: '用户停止', count: 0 }
    var js = '(function(){' + SR_JS
      + 'var allEl=sr.querySelectorAll("button,a,[role=button]");var cnt=0;'
      + 'for(var i=0;i<allEl.length;i++){var t=(allEl[i].innerText||"").trim();if(t.indexOf("联系")>=0&&allEl[i].offsetParent!==null)cnt++}'
      + 'return String(cnt)'
      + '})()'
    var result = await cap.cdp.eval(js)
    var count = parseInt(result, 10) || 0
    if (count > 0) return { ok: true, note: '找到 ' + count + ' 个联系按钮', count: count }
    await cap.runtime.sleep(1000)
  }
  return { ok: false, note: '等待结果超时', count: 0 }
}

async function contactMerchant(index) {
  try {
    var idx = index || 0
    var js = '(function(){' + SR_JS
      + 'var allEl=sr.querySelectorAll("button,a,[role=button]");var btns=[];'
      + 'for(var i=0;i<allEl.length;i++){var t=(allEl[i].innerText||allEl[i].textContent||"").trim();if((t.indexOf("联系商家")>=0||t.indexOf("联系卖家")>=0||t==="联系")&&allEl[i].offsetParent!==null)btns.push(allEl[i])}'
      + 'if(btns.length===0)return JSON.stringify({ok:false,note:"未找到联系商家按钮"});'
      + 'var bi=Math.min(idx,btns.length-1);var btn=btns[bi];'
      + 'var card=btn.closest("[class*=item],[class*=card],[class*=product],[class*=goods],li");'
      + 'var info=card?(card.innerText||"").slice(0,500):"";'
      + 'btn.click();'
      + 'return JSON.stringify({ok:true,note:"已点击第"+(bi+1)+"个(共"+btns.length+"个)",productInfo:info,totalBtns:btns.length})'
      + '})()'.replace('idx', String(idx))

    var result = await cap.cdp.eval(js)
    var info
    try { info = JSON.parse(typeof result === 'string' ? result : JSON.stringify(result)) }
    catch (e) { info = { ok: false, note: '解析失败' } }
    await cap.runtime.sleep(2000)
    return info
  } catch (e) {
    return { ok: false, note: '点击失败: ' + (e.message || e) }
  }
}

async function checkNewTab(originalTargets) {
  var originalIds = new Set((originalTargets || []).map(function(t) { return t.id }))
  var originalUrls = new Set((originalTargets || []).map(function(t) { return t.url }))

  for (var i = 0; i < 15; i++) {
    if (!(await cap.control.check())) return { ok: false, note: '用户停止' }
    var currentTargets = await safeGetTargets()
    for (var j = 0; j < currentTargets.length; j++) {
      var t = currentTargets[j]
      if (!originalIds.has(t.id) && !originalUrls.has(t.url)) {
        return { ok: true, note: '新标签页: ' + (t.title || t.url || '').slice(0, 80), newTarget: t }
      }
    }
    var currentUrl = await cap.cdp.eval('window.location.href')
    var url = typeof currentUrl === 'string' ? currentUrl.replace(/^"|"$/g, '') : ''
    if (url && url.indexOf('talent/pool/home') < 0 && url.indexOf('store.weixin.qq.com') >= 0) {
      return { ok: true, note: '同页跳转: ' + url.slice(0, 80), newTarget: { url: url, id: 'same_tab', title: '' }, sameTab: true }
    }
    await cap.runtime.sleep(1000)
  }
  return { ok: false, note: '未检测到新标签页', newTarget: null }
}

async function switchToNewTab(newTarget) {
  try {
    if (newTarget.sameTab) {
      await cap.runtime.sleep(3000)
      return { ok: true, note: '同页跳转，等待加载' }
    }
    if (!newTarget.url) return { ok: false, note: '无 URL' }
    await cap.cdp.eval('window.location.href = ' + JSON.stringify(newTarget.url))
    await cap.runtime.sleep(3000)
    for (var i = 0; i < 20; i++) {
      if (!(await cap.control.check())) return { ok: false, note: '用户停止' }
      var ready = await cap.cdp.eval('document.readyState')
      if (ready === 'complete' || ready === '"complete"') {
        await cap.runtime.sleep(1000)
        var hasChat = await cap.cdp.eval('(function(){var s=["textarea","[contenteditable=true]",".chat-input","[role=textbox]",".ql-editor",".ProseMirror","input[placeholder*=输入]","input[placeholder*=消息]"];for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e&&e.offsetParent!==null)return "1"}return "0"})()')
        if (hasChat === '1' || hasChat === true) return { ok: true, note: '已切换到聊天页面' }
      }
      await cap.runtime.sleep(1000)
    }
    return { ok: true, note: '已导航（未确认输入框）' }
  } catch (e) {
    return { ok: false, note: '切换失败: ' + (e.message || e) }
  }
}

// ── 输入并发送消息 ───────────────────────────────────────────────────────
async function typeAndSendMessage(message) {
  try {
    var findJs = '(function(){var s=["textarea","[contenteditable=true]",".chat-input","[role=textbox]",".ql-editor",".ProseMirror","input[placeholder*=输入]","input[placeholder*=消息]","[class*=chat] [class*=input]","[class*=editor]"];for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e&&e.offsetParent!==null)return JSON.stringify({found:true,selector:s[i]})}return JSON.stringify({found:false})})()'
    var inputResult = await cap.cdp.eval(findJs)
    var inputInfo
    try { inputInfo = JSON.parse(typeof inputResult === 'string' ? inputResult : JSON.stringify(inputResult)) }
    catch (e) { inputInfo = { found: false } }

    if (!inputInfo.found) {
      var recResult = await cap.recognize.chain({ kind: 'text_present', text: '输入' }, ['cdp', 'ocr', 'vlm'])
      if (!recResult.ok) return { ok: false, note: '未找到聊天输入框' }
      inputInfo = { found: true, selector: 'textarea, [contenteditable="true"]' }
    }

    var selector = inputInfo.selector || 'textarea'

    // 聚焦清空
    await cap.cdp.eval('(function(){var e=document.querySelector(sel);if(!e)return;e.focus();if(e.tagName==="INPUT"||e.tagName==="TEXTAREA")e.value="";else if(e.isContentEditable)e.innerText="";e.dispatchEvent(new Event("input",{bubbles:true}))})()'.replace(/sel/, JSON.stringify(selector)))
    await cap.runtime.sleep(300)

    // 输入
    await cap.cdp.type(selector, message)
    await cap.runtime.sleep(500)

    // 验证 + fallback
    var verify = await cap.cdp.eval('(function(){var e=document.querySelector(sel);if(!e)return"";return e.tagName==="INPUT"||e.tagName==="TEXTAREA"?(e.value||""):(e.innerText||"")})()'.replace(/sel/, JSON.stringify(selector)))
    var actual = typeof verify === 'string' ? verify.replace(/^"|"$/g, '') : ''
    if (!actual || actual.length < 5) {
      var safeMsg = JSON.stringify(message)
      await cap.cdp.eval('(function(){var e=document.querySelector(sel);if(!e)return;e.focus();if(e.tagName==="INPUT"||e.tagName==="TEXTAREA")e.value=msg;else if(e.isContentEditable)e.innerText=msg;e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}))})()'.replace(/sel/, JSON.stringify(selector)).replace(/msg/g, safeMsg))
      await cap.runtime.sleep(500)
    }

    // 发送
    var sendJs = '(function(){var s=["button[class*=send]",".send-btn","[class*=send] button","button[class*=submit]"];for(var i=0;i<s.length;i++){var b=document.querySelector(s[i]);if(b&&!b.disabled&&b.offsetParent!==null){b.click();return "clicked"}}var bs=document.querySelectorAll("button,[role=button]");for(var j=0;j<bs.length;j++){var t=(bs[j].innerText||"").trim();if((t==="发送"||t==="发送消息"||t==="Send")&&!bs[j].disabled){bs[j].click();return "clicked: "+t}}var el=document.querySelector(sel2);if(el){el.dispatchEvent(new KeyboardEvent("keydown",{key:"Enter",code:"Enter",keyCode:13,which:13,bubbles:true}));el.dispatchEvent(new KeyboardEvent("keypress",{key:"Enter",code:"Enter",keyCode:13,which:13,bubbles:true}));el.dispatchEvent(new KeyboardEvent("keyup",{key:"Enter",code:"Enter",keyCode:13,which:13,bubbles:true}));return "enter"}return "no_send"})()'.replace(/sel2/, JSON.stringify(selector))
    var sendResult = await cap.cdp.eval(sendJs)
    await cap.runtime.sleep(1000)

    return { ok: true, note: '已发送 (' + sendResult + ')', message: message }
  } catch (e) {
    return { ok: false, note: '发送失败: ' + (e.message || e) }
  }
}

// ═══════════════════════════════════════════════════════════════════════
// 单步操作（供调试/外部调用）
// ═══════════════════════════════════════════════════════════════════════
async function openPage(params) {
  var keyword = params.keyword || (params.keywords && params.keywords[0]) || 'test'
  var url = params.url || (PAGE_BASE_URL + encodeURIComponent(keyword))
  var navResult = await navigateToPage(url)
  var pageResult = navResult.ok ? await waitForPageLoad() : { ok: false, note: '导航失败' }
  return { ok: navResult.ok && pageResult.ok, url: url, navigate: navResult, pageLoad: pageResult }
}

async function sendMessageAction(params) {
  var message = params.message || ''
  if (!message) {
    var config = loadConfig()
    var keywords = params.keywords || config.keywords || []
    var ctx = ConversationEngine.createContext(params.productInfo || '', keywords, config.commStyle, MaterialManager.getAll())
    var openingResult = await ConversationEngine.generateOpening(ctx)
    message = openingResult.message
  }
  return await typeAndSendMessage(message)
}

async function generateOpeningAction(params) {
  var config = loadConfig()
  var keywords = params.keywords || config.keywords || []
  var ctx = ConversationEngine.createContext(params.productInfo || '', keywords, params.commStyle || config.commStyle, MaterialManager.getAll())
  var result = await ConversationEngine.generateOpening(ctx)
  return { ok: result.ok, message: result.message, context: ctx }
}

async function analyzeReplyAction(params) {
  var ctx = params.context || ConversationEngine.createContext('', [], '专业友好', [])
  var result = await ConversationEngine.analyzeReply(ctx, params.reply)
  return { ok: result.ok, analysis: result.analysis, context: ctx }
}

async function generateFollowUpAction(params) {
  var ctx = params.context || ConversationEngine.createContext('', [], '专业友好', [])
  var analysis = params.analysis || {}
  var result = await ConversationEngine.generateFollowUp(ctx, analysis)
  return { ok: result.ok, message: result.message, context: ctx }
}

// ═══════════════════════════════════════════════════════════════════════
// record
// ═══════════════════════════════════════════════════════════════════════
async function record(params) {
  cap.flowchart.setCurrent(FLOWCHART)
  cap.control.reset()
  cap.flowchart.pushTrace('start', 'ok', 'record mode')
  if (cap.cdp && typeof cap.cdp.startRecording === 'function') {
    await cap.cdp.startRecording(params)
    return { ok: true, mode: 'record', message: '录制已开始' }
  }
  return { ok: true, mode: 'record', message: '仅记录操作日志', flowchart: cap.flowchart.get() || FLOWCHART }
}

// ═══════════════════════════════════════════════════════════════════════
// 生命周期 + 调试
// ═══════════════════════════════════════════════════════════════════════
export const lifecycle = {
  onSkillLoad:   async function(ctx) { cap.runtime.log('auto-product-comm', 'skill v3.0 loaded') },
  onTaskStart:   async function(ctx, task) { cap.runtime.log('auto-product-comm', 'task start: ' + task) },
  onTaskEnd:     async function(ctx, task, result) { cap.runtime.log('auto-product-comm', 'task end: ' + task) },
  onSkillUnload: async function(ctx) { cap.runtime.log('auto-product-comm', 'skill unloaded') },
}

export const debug = {
  getVariableScope: function(ctx) {
    return {
      locals: (ctx && ctx.locals) || {},
      flowchart: cap.flowchart.get() || FLOWCHART,
      config: loadConfig(),
      materials: MaterialManager.getAll(),
      logs: CommunicationLogger.getAll(),
    }
  },
  onBreakpoint: async function(ctx, node) { cap.runtime.log('debug', 'breakpoint: ' + node.id) },
}

export default handler
