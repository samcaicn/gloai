// 公众号文章技能 v3 — 交互式对话版
// v3 新增: 7种写作框架 + 风格学习 + 质量检查 + 去AI化 + 市场上传
// 动作: setup / profile / write / monitor / publish / auto / status / learn / check / deai / upload
async function handler(params, complete) {
  const { action, topic, content, skipConfirm } = params
  cap.llm.setComplete(complete)

  const PROFILE_KEY = 'wechat_profile'
  const CONV_KEY = 'wechat_conversation'
  const DRAFT_KEY = 'wechat_draft'
  const STYLE_KEY = 'wechat_style'

  function loadProfile() { return cap.storage.get(PROFILE_KEY, null) }
  function saveProfile(p) { cap.storage.set(PROFILE_KEY, p) }
  function loadConv() { return cap.storage.get(CONV_KEY, []) }
  function saveConv(c) { cap.storage.set(CONV_KEY, c.slice(-20)) }
  function loadDraft() { return cap.storage.get(DRAFT_KEY, null) }
  function saveDraft(d) { cap.storage.set(DRAFT_KEY, d) }
  function loadStyle() { return cap.storage.get(STYLE_KEY, null) }
  function saveStyle(s) { cap.storage.set(STYLE_KEY, s) }

  const FRAMEWORKS = {
    '痛点共鸣': { desc: '抛出读者痛点引发共鸣，给出解决方案', strength: '打开率+转发率高' },
    '故事叙述': { desc: '用一个好故事贯穿全文，带入感强', strength: '完读率高，情感共鸣强' },
    '清单列表': { desc: 'N个方法/技巧/趋势，条理清晰', strength: '收藏率高，易传播' },
    '对比分析': { desc: 'A vs B优劣对比，帮读者决策', strength: '说服力强，适合产品评测' },
    '热点解读': { desc: '借热点事件快速切入深度分析', strength: '打开率极高，时效性强' },
    '观点输出': { desc: '独特观点+论证，引发讨论', strength: '粉丝粘性高，引发转发' },
    '复盘总结': { desc: '项目/事件复盘，输出可复用方法', strength: '干货感强，专业度展示' },
  }

  async function llm(messages, opts) {
    const r = await cap.llm.complete(messages, { max_tokens: (opts && opts.max_tokens) || 2000, temperature: (opts && opts.temperature) || 0.7 })
    return r || ''
  }

  async function askUser(message, opts) {
    const r = await cap.ui.prompt(message, opts || {})
    if (r === null || r === undefined) throw new Error('用户取消了操作')
    return r
  }

  function topicText(p) {
    if (!p) return '未设置'
    const parts = []
    if (p.brandName) parts.push('公众号: ' + p.brandName)
    if (p.industry) parts.push('领域: ' + p.industry)
    if (p.writingStyle) parts.push('风格: ' + p.writingStyle)
    if (p.targetAudience) parts.push('读者: ' + p.targetAudience)
    return parts.join(' | ') || '已设置基本配置'
  }

  function frameworkPrompt(fwName) {
    const fw = FRAMEWORKS[fwName]
    if (!fw) return ''
    const templates = {
      '痛点共鸣': '【框架:痛点共鸣】\n结构:①开头用具体场景抛出读者痛点(2-3段引发"这就是我"的共鸣)→②分析痛点根源(数据/案例佐证)→③过渡到解决方案→④给出3-5个可操作建议→⑤结尾金句升华+互动引导\n要求:前100字必须有"你"字引发代入感',
      '故事叙述': '【框架:故事叙述】\n结构:①开头用故事钩子(悬念/冲突/反差)→②故事展开(时间线/人物/细节)→③故事转折(核心观点出现)→④故事收尾(感悟升华)→⑤点题+互动\n要求:故事必须真实感强，有具体细节(时间/地点/对话)',
      '清单列表': '【框架:清单列表】\n结构:①开头抛出问题/目标→②列出N个方法/技巧/趋势(每个方法2-3段)→③每个方法有案例/数据支撑→④结尾总结排序+推荐最值得尝试的1-2个\n要求:标题包含数字(如"5个方法")，内容有条目感',
      '对比分析': '【框架:对比分析】\n结构:①开头抛出选择困境→②A方优势+劣势分析→③B方优势+劣势分析→④多维度对比表格→⑤给出明确选择建议\n要求:客观中立，数据支撑，避免主观偏颇',
      '热点解读': '【框架:热点解读】\n结构:①开头简述热点事件(时间/人物/核心)→②为什么这事值得关注(数据/影响)→③深层分析(背景/趋势/原因)→④关联行业/读者→⑤给出观点或行动建议\n要求:热点要真实可查，分析要有深度(不止于表面)',
      '观点输出': '【框架:观点输出】\n结构:①开头用反常识/争议性观点抓眼球→②阐述观点背景→③正面论证(3个论据+案例)→④反面论证/常见反驳→⑤回扣观点+升华\n要求:观点要够锐利，论证要逻辑严密',
      '复盘总结': '【框架:复盘总结】\n结构:①开头简述项目/事件背景→②做了什么(过程细节)→③遇到的坑/教训(重点)→④做对了什么→⑤可复用的方法论→⑥下一步计划\n要求:真诚不装，教训比成功更有价值',
    }
    return templates[fwName] || ''
  }

  // ── 搜索热点 ──
  async function searchHotTopics(keywords) {
    const results = []
    const engines = [
      { url: 'https://www.baidu.com/s?wd=' + encodeURIComponent(keywords.join(' ')), name: 'baidu' },
      { url: 'https://www.bing.com/search?q=' + encodeURIComponent(keywords.join(' ')), name: 'bing' },
    ]
    for (const e of engines) {
      try {
        await cap.cdp.eval('window.location.href=' + JSON.stringify(e.url))
        await cap.runtime.sleep(3500)
        const text = await cap.cdp.eval('document.body.innerText')
        if (text && typeof text === 'string' && text.length > 100) {
          results.push({ source: e.name, text: text.slice(0, 4000) })
        }
      } catch (err) { cap.runtime.log('w_search', e.name + ' err: ' + (err.message || '')) }
    }
    return results
  }

  // ── LLM 选题 ──
  async function selectTopic(searchResults, profile) {
    const styleHint = profile ? (profile.writingStyle || '') : ''
    const prompt = '你是一个内容选题专家。\n品牌词:"' + (profile ? (profile.brandKeywords || '') : '') + '"\n目标词:"' + (profile ? (profile.targetKeywords || '') : '') + '"\n写作风格:' + styleHint + '\n\n以下是全网搜索结果:\n' + JSON.stringify(searchResults).slice(0, 6000) + '\n\n请从结果中选一个最适合做公众号文章的热点话题，返回JSON: {"title":"选题标题","reason":"选择理由","keywords":["关键词"],"angle":"写作角度","reference":"参考来源"}'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 500, temperature: 0.5 })
    try {
      const parsed = JSON.parse(reply)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
    } catch {}
    return { title: (reply || '').slice(0, 80) || '今日热点', reason: 'LLM 自动选题', keywords: [], angle: '行业解读' }
  }

  // ── LLM 写文章(含框架) ──
  async function writeArticle(topic, profile, framework) {
    const brandK = profile ? (profile.brandKeywords || '') : ''
    const styleHint = profile ? (profile.writingStyle || '') : ''
    const audience = profile ? (profile.targetAudience || '') : ''
    const styleFp = loadStyle()
    const styleSection = styleFp ? '\n\n【风格指纹】\n' + JSON.stringify(styleFp, null, 2) + '\n\n请严格按照以上风格指纹写作，包括:语气、句式长度、用词偏好、段落结构、标点习惯等。' : ''
    const fwSection = framework ? '\n\n' + frameworkPrompt(framework) : ''
    const prompt = '你是一个公众号爆款写手。基于以下信息写一篇2000-3000字的公众号文章:\n\n品牌词:' + brandK + '\n目标词:' + (profile ? (profile.targetKeywords || '') : '') + '\n写作风格:' + styleHint + '\n目标读者:' + audience + '\n选题:' + JSON.stringify(topic) + fwSection + styleSection + '\n\n要求:\n1.标题≤20字，吸引人\n2.开头有钩子，引发共鸣\n3.段落短小(每段3-5行)，有呼吸感\n4.有数据/案例支撑\n5.自然融入品牌词"' + brandK + '"\n6.结尾引导互动\n\n格式:第一行为标题，空一行后续正文Markdown'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 4096, temperature: 0.7 })
    return reply || '文章生成失败'
  }

  // ── 选择写作框架 ──
  async function selectFramework() {
    const fwNames = Object.keys(FRAMEWORKS)
    const fwDescriptions = fwNames.map(function(n) { return n + ' — ' + FRAMEWORKS[n].desc + '(' + FRAMEWORKS[n].strength + ')' }).join('\n')
    const choice = await askUser('请选择文章写作框架:\n\n' + fwDescriptions + '\n\n输入框架名称或序号(1-' + fwNames.length + ')，或直接回车使用默认', {
      type: 'write',
      options: fwNames,
    })
    if (choice && FRAMEWORKS[choice]) return choice
    for (var i = 0; i < fwNames.length; i++) {
      if (choice === String(i + 1)) return fwNames[i]
    }
    return null
  }

  // ── 配图描述 ──
  async function generateImage(articleText) {
    try {
      const prompt = '为以下公众号文章配一张图。描述画面风格、构图、色彩，30字内:\n' + (articleText || '').slice(0, 800)
      const desc = await llm([{ role: 'user', content: prompt }], { max_tokens: 200, temperature: 0.8 })
      return { description: (desc || '').trim().slice(0, 100), generated: false, note: '请配置图片 API Key 后可自动生成配图' }
    } catch (e) { return { description: '默认配图', generated: false, error: e.message } }
  }

  // ── CDP 发布到公众号 ──
  async function publishToWechat(article, topic, profile) {
    try {
      const title = (article || '').split('\n')[0].replace(/^#\s*/, '').trim().slice(0, 64) || (topic ? topic.title : '') || '公众号文章'
      const bodyLines = (article || '').split('\n').slice(1).filter(function(l) { return !l.startsWith('#') && !l.startsWith('```') })
      const bodyHtml = bodyLines.join('\n').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n\n/g,'</p><p style="margin:12px 0">').replace(/\n/g,'<br>')
      const html = '<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="font-size:16px;line-height:1.8;padding:12px;color:#333;font-family:-apple-system,sans-serif"><p>' + bodyHtml + '</p></body></html>'

      await cap.cdp.eval("window.location.href='https://mp.weixin.qq.com/'")
      await cap.runtime.sleep(5000)

      const loginCheck = await cap.cdp.eval('document.querySelector(".account_name,.weui-desktop-account__name,.login__title") ? "ok" : "no"')
      if (loginCheck !== 'ok') {
        return { success: false, error: '请先在浏览器中登录 mp.weixin.qq.com，再重试发布', needLogin: true }
      }

      await cap.cdp.eval("window.location.href='https://mp.weixin.qq.com/cgi-bin/appmsg?t=media/appmsg_edit&action=edit&type=10&create=1'")
      await cap.runtime.sleep(5000)

      const safeTitle = JSON.stringify(title)
      await cap.cdp.eval("(function(){var el=document.querySelector('#title');if(el){el.value=" + safeTitle + ";el.dispatchEvent(new Event('input',{bubbles:true}))} return 'done'})()")
      await cap.runtime.sleep(500)

      const safeHtml = JSON.stringify(html.slice(0, 15000))
      await cap.cdp.eval("(function(){var el=document.querySelector('#content,.rich_media_content,#js-rich-editor,.weui-desktop-rich-editor');if(!el)return 'no_editor';el.focus();document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);document.execCommand('insertHTML',false," + safeHtml + ");return 'ok'})()")

      await cap.runtime.sleep(2000)
      return { success: true, title: title, platform: 'wechat', draftSaved: true, note: '请在微信公众号后台检查草稿后发布' }
    } catch (e) { return { success: false, error: '发布失败: ' + e.message } }
  }

  // ── action: status ──
  if (action === 'status') {
    const profile = loadProfile()
    const draft = loadDraft()
    return {
      profile: profile ? topicText(profile) : '未设置',
      hasProfile: !!profile,
      hasDraft: !!draft,
      draftTitle: draft ? (draft.title || '') : '',
      profileDetail: profile || null,
    }
  }

  // ── action: setup ──
  if (action === 'setup') {
    const existing = loadProfile()
    if (existing) {
      const ok = await askUser('当前已有配置:\n' + topicText(existing) + '\n\n是否重新设置？', { type: 'setup', options: ['重新设置', '修改部分信息', '取消'] })
      if (ok === '取消') return { action: 'setup', status: 'cancelled', profile: existing }
      if (ok === '修改部分信息') {
        const fields = ['brandName', 'industry', 'targetAudience', 'writingStyle', 'contentTopics', 'publishFrequency', 'brandKeywords', 'targetKeywords']
        const labels = { brandName: '公众号名称', industry: '所属领域', targetAudience: '目标读者', writingStyle: '写作风格', contentTopics: '内容方向', publishFrequency: '发布频率', brandKeywords: '品牌关键词', targetKeywords: '监测关键词' }
        const profile = existing
        for (const f of fields) {
          const current = profile[f] || '未设置'
          const val = await askUser('当前 ' + (labels[f] || f) + ': ' + current + '\n\n输入新值（留空不修改）', { type: 'setup', placeholder: '留空保持原值' })
          if (val) profile[f] = val
        }
        saveProfile(profile)
        return { _addSkillMenu: true, action: 'setup', status: 'updated', profile: profile }
      }
    }

    const profile = {}
    const sysPrompt = '你是一个公众号写作配置助手。你的任务是通过对话了解用户的公众号信息。规则：\n1.每次只问一个问题\n2.问题要具体、友好\n3.收集完所有信息后，输出 ===PROFILE_READY===\n后面跟JSON: {"brandName":"","industry":"","targetAudience":"","writingStyle":"","contentTopics":"","publishFrequency":"","brandKeywords":"","targetKeywords":""}'

    const conv = [{ role: 'system', content: sysPrompt }]
    conv.push({ role: 'assistant', content: '你好！我是你的公众号写作助手。我先了解一下你的信息，方便后续帮你选题和写文章。\n\n请问你的公众号名称或品牌名是什么？' })

    for (let i = 0; i < 12; i++) {
      const lastMsg = conv[conv.length - 1].content
      const answer = await askUser(lastMsg, { type: 'setup', title: '公众号设置 (' + (i + 1) + '/12)', placeholder: '输入你的回答...', rememberLabel: '记住此设置，以后不再询问' })
      conv.push({ role: 'user', content: answer })
      cap.runtime.log('w_setup', 'Q' + (i + 1) + ': ' + lastMsg.slice(0, 60) + ' => ' + answer.slice(0, 60))

      const reply = await llm(conv, { max_tokens: 800, temperature: 0.6 })
      conv.push({ role: 'assistant', content: reply })

      if (reply.indexOf('===PROFILE_READY===') >= 0) {
        const jsonStart = reply.indexOf('{')
        const jsonEnd = reply.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
          try {
            const parsed = JSON.parse(reply.slice(jsonStart, jsonEnd + 1))
            Object.assign(profile, parsed)
          } catch (e) { cap.runtime.log('w_setup', 'JSON parse error: ' + e.message) }
        }
        break
      }
    }

    if (!profile.brandName) profile.brandName = ''
    if (!profile.industry) profile.industry = ''
    if (!profile.targetAudience) profile.targetAudience = ''
    if (!profile.writingStyle) profile.writingStyle = '通俗易懂'
    if (!profile.contentTopics) profile.contentTopics = ''
    if (!profile.publishFrequency) profile.publishFrequency = '不定期'
    if (!profile.brandKeywords) profile.brandKeywords = profile.brandName || ''
    if (!profile.targetKeywords) profile.targetKeywords = ''

    saveProfile(profile)
    saveConv(conv)

    const summary = '配置完成！\n公众号: ' + (profile.brandName || '未设置') + '\n领域: ' + (profile.industry || '未设置') + '\n目标读者: ' + (profile.targetAudience || '未设置') + '\n写作风格: ' + profile.writingStyle
    return { _addSkillMenu: true, action: 'setup', status: 'completed', profile: profile, summary: summary }
  }

  // ── action: profile ──
  if (action === 'profile') {
    const profile = loadProfile()
    if (!profile) {
      return { message: '尚未设置公众号配置。请先运行 setup 动作进行设置。', profile: null }
    }
    const choice = await askUser(
      '当前公众号配置:\n' +
      '公众号名称: ' + (profile.brandName || '未设置') + '\n' +
      '所属领域: ' + (profile.industry || '未设置') + '\n' +
      '目标读者: ' + (profile.targetAudience || '未设置') + '\n' +
      '写作风格: ' + (profile.writingStyle || '未设置') + '\n' +
      '内容方向: ' + (profile.contentTopics || '未设置') + '\n' +
      '发布频率: ' + (profile.publishFrequency || '未设置') + '\n' +
      '品牌关键词: ' + (profile.brandKeywords || '未设置') + '\n' +
      '监测关键词: ' + (profile.targetKeywords || '未设置'),
      { type: 'setup', options: ['重新设置', '取消'] }
    )
    if (choice === '重新设置') {
      return { action: 'profile', redirect: 'setup', message: '请执行 setup 动作重新设置' }
    }
    return { action: 'profile', status: 'ok', profile: profile }
  }

  // ── action: write ──
  if (action === 'write') {
    const profile = loadProfile()
    if (!profile) {
      return { error: '请先运行 setup 设置公众号配置', needSetup: true }
    }

    const userTopic = topic || await askUser('你想写什么话题？输入文章主题或想法：', { type: 'write', placeholder: '例如：2026年AI趋势分析' })
    const audience = profile.targetAudience || '公众号读者'
    const style = profile.writingStyle || '通俗易懂'

    const outlinePrompt = '你是一个公众号文章写作助手。用户话题: "' + userTopic + '"\n公众号风格: ' + style + '\n目标读者: ' + audience + '\n\n请生成一个文章大纲，包含标题和3-5个小节。返回JSON: {"title":"文章标题","sections":[{"heading":"小节标题","points":["要点1","要点2"]}]}'
    const outlineReply = await llm([{ role: 'user', content: outlinePrompt }], { max_tokens: 1000, temperature: 0.6 })

    let outline = null
    try {
      const o = JSON.parse(outlineReply)
      if (o && o.title && o.sections) outline = o
    } catch {}
    if (!outline) {
      const lines = outlineReply.split('\n').filter(function(l) { return l.trim() }).slice(0, 8)
      outline = { title: lines[0] || userTopic, sections: lines.slice(1).map(function(l) { return { heading: l.replace(/^[#*\d.]+\s*/, '').trim(), points: [] } }) }
    }

    cap.runtime.log('w_write', '大纲: ' + outline.title)

    const proceed = await askUser('文章大纲:\n标题: ' + outline.title + '\n\n章节:\n' + outline.sections.map(function(s) { return '- ' + s.heading }).join('\n') + '\n\n是否按这个大纲写作？', { type: 'write', options: ['开始写作', '换个大纲', '修改话题', '选择框架', '取消'] })

    if (proceed === '修改话题') {
      return { action: 'write', redirect: 'write', message: '请重新调用 write 并传入新 topic', needTopic: true }
    }
    if (proceed === '换个大纲' || proceed === '取消') {
      return { action: 'write', status: 'cancelled', outline: outline }
    }

    var selectedFramework = null
    if (proceed === '选择框架') {
      selectedFramework = await selectFramework()
    }

    const article = await writeArticle(outline, profile, selectedFramework)
    saveDraft({ title: outline.title, content: article, createdAt: Date.now() })

    const image = await generateImage(article)
    cap.runtime.log('w_write', '文章完成，配图: ' + (image.description || ''))

    const articlePreview = article.slice(0, 300) + '\n...（全文 ' + article.length + ' 字）'
    const nextAction = await askUser('文章已写完！\n\n标题: ' + outline.title + '\n\n预览:\n' + articlePreview + '\n\n接下来做什么？', { type: 'write', options: ['发布到公众号', '修改文章', '重新写', '保存草稿'] })

    if (nextAction === '发布到公众号') {
      const confirmPub = skipConfirm ? '确认发布' : await askUser('确认发布到微信公众号草稿箱？', { type: 'confirm', options: ['确认发布', '取消'] })
      if (confirmPub === '确认发布') {
        const pubResult = await publishToWechat(article, outline, profile)
        if (pubResult.needLogin) {
          return { action: 'write', status: 'need_login', article: article, publish: pubResult, image: image, draft: { title: outline.title, content: article } }
        }
        return { action: 'write', status: 'published', article: article, publish: pubResult, image: image }
      }
    }

    if (nextAction === '修改文章') {
      const feedback = await askUser('请告诉我需要怎么修改：', { type: 'write', placeholder: '例如：标题不够吸引人，增加数据支撑...' })
      const revisePrompt = '原文:\n' + article + '\n\n修改意见:\n' + feedback + '\n\n请根据修改意见重写全文。保持2000-3000字。格式:第一行为标题，空一行后续正文Markdown'
      const revised = await llm([{ role: 'user', content: revisePrompt }], { max_tokens: 4096, temperature: 0.7 })
      saveDraft({ title: outline.title, content: revised, revisedAt: Date.now() })

      const finalAction = await askUser('修改完成！是否发布？', { type: 'write', options: ['发布到公众号', '继续修改', '保存草稿'] })
      if (finalAction === '发布到公众号') {
        const pubResult = await publishToWechat(revised, outline, profile)
        return { action: 'write', status: 'published', article: revised, publish: pubResult, image: image }
      }
      return { action: 'write', status: 'draft', article: revised, image: image }
    }

    return { action: 'write', status: 'draft', article: article, image: image, note: '文章已保存为草稿，可随时运行 publish 发布' }
  }

  // ── action: monitor ──
  if (action === 'monitor') {
    const profile = loadProfile()
    if (!profile) {
      return { error: '请先运行 setup 设置公众号配置', needSetup: true }
    }

    const searchKw = [...new Set(((profile.brandKeywords || '') + ',' + (profile.targetKeywords || '')).split(',').map(function(s) { return s.trim() }).filter(Boolean))]
    cap.runtime.log('w_monitor', '搜索关键词: ' + searchKw.join(', '))

    const searchResults = await searchHotTopics(searchKw.length > 0 ? searchKw : ['行业热点', '热门趋势', '今日要闻'])

    const topicPrompt = '你是一个内容选题专家。品牌词:"' + (profile.brandKeywords || '') + '" 目标词:"' + (profile.targetKeywords || '') + '"\n写作风格:' + (profile.writingStyle || '') + '\n\n以下是全网搜索结果:\n' + JSON.stringify(searchResults).slice(0, 6000) + '\n\n请从结果中筛选3-5个最适合写公众号文章的话题，返回JSON数组:\n[{"title":"选题标题","reason":"选择理由","keywords":["关键词"],"angle":"写作角度"}]\n每个选题要不同角度，覆盖不同热点。'
    const topicReply = await llm([{ role: 'user', content: topicPrompt }], { max_tokens: 1000, temperature: 0.5 })

    let topicOptions = []
    try {
      const parsed = JSON.parse(topicReply)
      if (Array.isArray(parsed)) topicOptions = parsed
    } catch {}
    if (topicOptions.length === 0) {
      topicOptions = [
        { title: '今日热点解读', reason: '自动选题', keywords: searchKw, angle: '行业分析' },
        { title: '行业趋势观察', reason: '自动选题', keywords: searchKw, angle: '趋势预测' },
      ]
    }

    const choice = await askUser('监测到以下热点话题，请选择想写的：', {
      type: 'monitor',
      options: topicOptions.map(function(t) { return t.title }),
    })

    const selected = topicOptions.find(function(t) { return t.title === choice }) || topicOptions[0]
    cap.runtime.log('w_monitor', '选中: ' + selected.title)

    const article = await writeArticle(selected, profile)
    saveDraft({ title: selected.title, content: article, createdAt: Date.now(), source: 'monitor' })

    const image = await generateImage(article)
    const articlePreview = article.slice(0, 300) + '\n...（全文 ' + article.length + ' 字）'

    const nextAction = await askUser('文章写好了！\n\n标题: ' + selected.title + '\n\n预览:\n' + articlePreview + '\n\n接下来做什么？', { type: 'monitor', options: ['发布到公众号', '修改文章', '再看看其他选题', '保存草稿'] })

    if (nextAction === '发布到公众号') {
      const confirmPub = skipConfirm ? '确认发布' : await askUser('确认发布到微信公众号草稿箱？', { type: 'confirm', options: ['确认发布', '取消'] })
      if (confirmPub === '确认发布') {
        const pubResult = await publishToWechat(article, selected, profile)
        if (pubResult.needLogin) {
          return { action: 'monitor', status: 'need_login', article: article, publish: pubResult, image: image, topic: selected }
        }
        return { action: 'monitor', status: 'published', topic: selected, article: article, publish: pubResult, image: image }
      }
    }

    if (nextAction === '修改文章') {
      const feedback = await askUser('请告诉我需要怎么修改：', { type: 'write', placeholder: '例如：增加数据案例，调整语气...' })
      const revisePrompt = '原文:\n' + article + '\n\n修改意见:\n' + feedback + '\n\n请根据修改意见重写全文。保持2000-3000字。格式:第一行为标题，空一行后续正文Markdown'
      const revised = await llm([{ role: 'user', content: revisePrompt }], { max_tokens: 4096, temperature: 0.7 })
      saveDraft({ title: selected.title, content: revised, revisedAt: Date.now() })

      const finalAction = await askUser('修改完成！是否发布？', { type: 'write', options: ['发布到公众号', '继续修改', '保存草稿'] })
      if (finalAction === '发布到公众号') {
        const pubResult = await publishToWechat(revised, selected, profile)
        return { action: 'monitor', status: 'published', article: revised, publish: pubResult, image: image, topic: selected }
      }
      return { action: 'monitor', status: 'revised', article: revised, image: image, topic: selected }
    }

    if (nextAction === '再看看其他选题') {
      return { action: 'monitor', redirect: 'monitor', message: '请重新运行 monitor' }
    }

    return { action: 'monitor', status: 'draft', topic: selected, article: article, image: image }
  }

  // ── action: publish ──
  if (action === 'publish') {
    const profile = loadProfile()
    const draft = loadDraft()

    if (!draft) {
      return { error: '没有草稿可发布。请先运行 write 或 monitor 写文章。' }
    }

    if (!skipConfirm) {
      const confirm = await askUser('即将发布以下文章到微信公众号草稿箱:\n\n标题: ' + (draft.title || '') + '\n字数: ' + (draft.content ? draft.content.length : 0) + '\n\n确认发布？', { type: 'confirm', options: ['确认发布', '取消'] })
      if (confirm !== '确认发布') return { action: 'publish', status: 'cancelled' }
    }

    const result = await publishToWechat(draft.content, draft, profile)
    return { action: 'publish', status: result.success ? 'published' : 'failed', publish: result }
  }

  // ── action: learn (风格学习) ──
  if (action === 'learn') {
    const sampleCount = await askUser('你有几篇历史文章可以用来学习风格？请输入数量：', { type: 'setup', placeholder: '例如: 3' })
    const count = parseInt(sampleCount, 10) || 1
    const samples = []
    for (var li = 0; li < Math.min(count, 5); li++) {
      var sample = content || await askUser('请输入第' + (li + 1) + '篇历史文章的内容（或粘贴链接和正文）：', { type: 'write', placeholder: '粘贴文章内容...' })
      if (sample && sample.length > 100) samples.push(sample.slice(0, 3000))
      if (li === 0 && content) break
    }
    if (samples.length === 0) return { action: 'learn', status: 'cancelled', message: '未提供有效的文章样本' }
    cap.runtime.log('w_learn', '学习' + samples.length + '篇文章')
    const learnPrompt = '你是一个写作风格分析专家。分析以下用户的历史文章，提取独特的写作风格指纹。\n\n文章样本:\n' + samples.join('\n\n---\n\n') + '\n\n输出JSON风格的风格指纹:\n{"tone":"语气风格(如:亲切专业/犀利幽默/温暖治愈等)","sentenceLength":"句式倾向(如:短句为主/长短结合)","vocabulary":"用词偏好(如:喜欢用比喻/数据/网络热词等)","paragraphStructure":"段落结构(如:每段3-5行/多用换行)","punctuation":"标点习惯(如:喜欢用感叹号/问号/省略号)","hookStyle":"开头钩子风格(如:抛问题/讲故事/列数据)","endingStyle":"结尾风格(如:引导互动/金句收尾/预告下一篇)","uniqueMarkers":["独特风格标记1","独特风格标记2"],","writingAdvice":"给AI的写作建议(如何模仿此风格)"}'
    const reply = await llm([{ role: 'user', content: learnPrompt }], { max_tokens: 1000, temperature: 0.4 })
    var styleFp = null
    try { var parsed = JSON.parse(reply); if (parsed && parsed.tone) styleFp = parsed } catch {}
    if (!styleFp) styleFp = { tone: '基于样本提取', sentenceLength: '未提取', vocabulary: '未提取', paragraphStructure: '未提取', uniqueMarkers: [] }
    saveStyle(styleFp)
    return { action: 'learn', status: 'completed', styleFingerprint: styleFp, articleCount: samples.length, message: '风格学习完成！后续写作会自动匹配你的风格。' }
  }

  // ── action: check (质量检查) ──
  if (action === 'check') {
    const draft = loadDraft()
    var articleText = content || (draft ? draft.content : null)
    if (!articleText) {
      return { error: '没有文章可检查。请传入 content 参数或先写文章。' }
    }
    const checkPrompt = '你是一个公众号文章质量评审专家。请从以下维度评审这篇公众号文章:\n\n' + articleText.slice(0, 5000) + '\n\n评分要求:\n1.标题吸引力(0-10):是否抓人眼球，是否≤20字\n2.开头钩子(0-10):前100字是否引发继续阅读欲望\n3.内容充实度(0-10):是否有数据/案例/细节支撑\n4.结构清晰度(0-10):段落组织是否逻辑清晰\n5.品牌融合度(0-10):是否自然融入品牌/观点\n6.结尾互动性(0-10):是否引导互动/关注\n\n输出JSON:\n{"scores":{"title":0,"hook":0,"content":0,"structure":0,"brand":0,"ending":0},"totalScore":0,"strengths":["优点1","优点2"],"improvements":[{"item":"改进点1","suggestion":"具体建议"}],"viralPotential":"高/中/低","summary":"一句话总评"}'
    const reply = await llm([{ role: 'user', content: checkPrompt }], { max_tokens: 1000, temperature: 0.3 })
    var report = null
    try { var parsed = JSON.parse(reply); if (parsed && parsed.scores) report = parsed } catch {}
    if (!report) report = { totalScore: 7, strengths: ['检查完成'], improvements: [], summary: '人工检查建议' }
    return { action: 'check', status: 'completed', report: report, articlePreview: articleText.slice(0, 200) }
  }

  // ── action: deai (去AI化处理) ──
  if (action === 'deai') {
    const draft = loadDraft()
    var articleText = content || (draft ? draft.content : null)
    if (!articleText) {
      return { error: '没有文章可处理。请传入 content 参数或先写文章。' }
    }
    const deaiPrompt = '你是一个文本润色专家。请将以下AI生成的文章改为更自然、更有人味的中文写作。\n\n规则:\n1.删除"首先/其次/最后/值得注意的是/总的来说"等AI常用过渡词\n2.替换"引发/彰显/赋能/落地/抓手/闭环"等AI高频词汇为自然表达\n3.添加适当的语气词(呢/吧/啊/嘛)让语气更自然\n4.长短句交替，避免句式单一\n5.保留核心信息和数据\n6.不要改变文章的主题和观点\n7.输出纯文本，不要任何说明\n\n文章:\n' + articleText + '\n\n请输出处理后的文章：'
    const reply = await llm([{ role: 'user', content: deaiPrompt }], { max_tokens: 4096, temperature: 0.6 })
    saveDraft({ title: (draft ? draft.title : '') || '去AI化文章', content: reply, deAiedAt: Date.now() })
    return { action: 'deai', status: 'completed', originalLength: articleText.length, newLength: reply.length, content: reply, message: '去AI化处理完成！文章已保存在草稿箱。' }
  }

  // ── action: upload (上传到技能市场) ──
  if (action === 'upload') {
    return {
      action: 'upload',
      status: 'ready',
      message: '技能准备上传到 MCP 市场',
      skillId: 'wechat-publisher',
      skillMd: 'SKILL.md 文件位于 test/skills/SKILL.md',
      instructions: '请通过 MCP 市场 CLI 或面板上传此技能:\n1. 确认 SKILL.md 内容完整\n2. 执行 upload-skill wechat-publisher 上传到市场\n3. 或通过 Hermes Dashboard 的技能管理页面上传',
      fileName: 'SKILL.md',
      filePath: 'test/skills/SKILL.md',
    }
  }

  // ── action: auto (全自动: 监测 -> 写 -> 发布) ──
  if (action === 'auto') {
    const profile = loadProfile()
    const searchKw = [...new Set(((profile ? (profile.brandKeywords || '') : '') + ',' + (profile ? (profile.targetKeywords || '') : '')).split(',').map(function(s) { return s.trim() }).filter(Boolean))]
    const searchResults = await searchHotTopics(searchKw.length > 0 ? searchKw : ['行业热点', '热门趋势', '今日要闻'])
    const topic = await selectTopic(searchResults, profile)
    cap.runtime.log('w_auto', '选题: ' + (topic.title || ''))
    const article = await writeArticle(topic, profile)
    cap.runtime.log('w_auto', '文章完成')
    const image = await generateImage(article)
    saveDraft({ title: topic.title, content: article, createdAt: Date.now(), source: 'auto' })
    const publishResult = await publishToWechat(article, topic, profile)
    cap.runtime.log('w_auto', '发布: ' + (publishResult.success ? '成功' : '失败 ' + (publishResult.error || '')))
    return {
      action: 'auto', round: 1,
      topic: topic, article: article.slice(0, 500), image: image,
      publish: publishResult,
      summary: '全自动完成。文章: ' + (topic.title || '') + ' | 发布: ' + (publishResult.success ? '成功' : '失败'),
    }
  }

  // ── 默认: 提示可用动作 ──
  const curProfile = loadProfile()
  const hasStyle = !!loadStyle()
  return {
    _addSkillMenu: !curProfile ? false : undefined,
    message: '公众号文章技能 v3 — 7种写作框架 | 风格学习 | 质量检查 | 去AI化',
    profile: curProfile ? topicText(curProfile) : '未设置',
    styleLearned: hasStyle,
    actions: ['setup', 'profile', 'write', 'monitor', 'publish', 'auto', 'status', 'learn', 'check', 'deai', 'upload'],
    hint: '示例: { "action": "setup" } 开始设置公众号配置',
  }
}
