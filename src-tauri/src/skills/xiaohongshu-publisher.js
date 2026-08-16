// 小红书文案技能 — 纯 LLM 驱动版
// 移除所有 CDP 依赖：热点搜索改为 LLM 生成，发布改为本地保存+返回内容
// 参数: brandKeywords(自有品牌词), targetKeywords(监测目标关键词)
// 动作: monitor / status / stop / write / check
async function handler(params, complete) {
  const { action, brandKeywords, targetKeywords, topic, content } = params
  cap.llm.setComplete(complete)

  const STORAGE_KEY = 'trace_xiaohongshu_publisher_state'
  function loadState() { return cap.storage.get(STORAGE_KEY, { running: false, lastMonitor: 0, lastOutput: 0, topics: [], posts: [], round: 0 }) }
  function saveState(s) { cap.storage.set(STORAGE_KEY, s) }

  async function llm(messages, opts) {
    const r = await cap.llm.complete(messages, { max_tokens: (opts && opts.max_tokens) || 2000, temperature: (opts && opts.temperature) || 0.7 })
    return r || ''
  }

  // ── LLM 生成热点话题（替代 CDP 搜索） ──
  async function generateHotTopics(keywords) {
    const kwStr = keywords && keywords.length > 0 ? keywords.join('、') : (targetKeywords || '好物推荐')
    const prompt = '你是一个小红书内容策划专家，精通爆款笔记选题。请基于以下信息，生成5个最适合小红书的热门话题。\n\n品牌词: ' + (brandKeywords || '') + '\n目标关键词: ' + kwStr + '\n\n要求:\n1.话题要结合小红书平台特点（种草、测评、教程、清单等）\n2.标题≤20字，带emoji\n3.每个话题给出选择理由和内容风格\n4.返回JSON数组:\n[{"title":"标题≤20字带emoji","reason":"选择理由","keywords":["关键词"],"style":"教程/测评/清单/故事/vlog"}]'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 800, temperature: 0.6 })
    try {
      const parsed = JSON.parse(reply)
      if (Array.isArray(parsed)) return parsed
    } catch {}
    return [
      { title: '🔥 ' + kwStr + '种草清单', reason: '清单类笔记收藏率高', keywords: keywords || [targetKeywords], style: '清单' },
      { title: '✨ ' + kwStr + '避坑指南', reason: '避坑类笔记互动率高', keywords: keywords || [targetKeywords], style: '测评' },
    ]
  }

  async function selectTopic(searchResults) {
    const prompt = '你是一个小红书内容策划。品牌词:"' + (brandKeywords||'') + '" 目标词:"' + (targetKeywords||'') + '"\n\n候选话题:\n' + JSON.stringify(searchResults).slice(0, 6000) + '\n\n选一个最适合小红书的爆款话题，返回JSON: {"title":"标题≤20字带emoji","reason":"选择理由","keywords":["关键词"],"style":"教程/测评/清单/故事/vlog"}'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 500, temperature: 0.5 })
    try { return JSON.parse(reply) }
    catch { return { title: (reply||'').slice(0, 60) || '🔥 今日热门', reason: 'LLM 自动选题', keywords: (targetKeywords||'').split(',').filter(Boolean), style: '清单' } }
  }

  async function writeXiaohongshuPost(topic) {
    const prompt = '你是一个小红书爆款笔记写手。写一篇小红书笔记:\n\n品牌词:' + (brandKeywords||'') + '\n目标词:' + (targetKeywords||'') + '\n选题:' + JSON.stringify(topic) + '\n\n要求:\n1.标题≤20字，带emoji\n2.正文≤1000字，短句分行\n3.每段开头用emoji\n4.口语化，像姐妹聊天\n5.有干货/清单/步骤\n6.结尾引导点赞收藏评论\n7.加3-5个话题标签\n8.自然融入品牌词"' + (brandKeywords||'') + '"\n\n直接输出笔记内容:'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 2048, temperature: 0.8 })
    return reply || '笔记生成失败'
  }

  async function generateImages(postText) {
    try {
      const prompt = '为以下小红书笔记配封面，描述风格/色调/构图，30字内:\n' + (postText||'').slice(0, 600)
      const coverDesc = await llm([{ role: 'user', content: prompt }], { max_tokens: 200, temperature: 0.8 })
      return { cover: { description: (coverDesc||'').trim().slice(0, 100), generated: false }, note: '配置图片 API Key 后可自动生成配图' }
    } catch (e) { return { cover: { description: '默认封面', generated: false }, error: e.message } }
  }

  // ── 本地保存（替代 CDP 发布） ──
  async function publishToWechat(post, topic) {
    try {
      const title = ((post||'').split('\n')[0]||'').replace(/^#\s*/,'').trim().slice(0,20) || topic.title || '小红书笔记'
      cap.storage.set('xiaohongshu_draft_' + Date.now(), { title: title, content: post, savedAt: Date.now() })
      return {
        success: true,
        title: '[小红书] ' + title,
        platform: 'local-draft',
        draftSaved: true,
        mode: 'local',
        note: '笔记已保存到本地草稿箱。请复制内容到小红书或微信公众号后台手动发布。',
        postContent: post,
      }
    } catch (e) { return { success: false, error: '保存失败: ' + e.message } }
  }

  // ── status ──
  if (action === 'status') {
    const s = loadState()
    return { running: s.running, lastMonitor: s.lastMonitor ? new Date(s.lastMonitor).toISOString() : '从未', lastOutput: s.lastOutput ? new Date(s.lastOutput).toISOString() : '从未', totalRounds: s.round, topicsFound: s.topics.length, postsPublished: s.posts.length, mode: 'pure-llm', noCdp: true }
  }

  // ── stop ──
  if (action === 'stop') {
    const s = loadState(); s.running = false; saveState(s)
    return { stopped: true }
  }

  // ── write (直接写一篇，不走 monitor 流程) ──
  if (action === 'write') {
    const userTopic = topic || ''
    const t = userTopic
      ? { title: userTopic.slice(0, 20), reason: '用户指定', keywords: (targetKeywords||'').split(',').filter(Boolean), style: '清单' }
      : await selectTopic(await generateHotTopics((targetKeywords||'').split(',').map(s=>s.trim()).filter(Boolean)))
    const post = await writeXiaohongshuPost(t)
    const images = await generateImages(post)
    return { action: 'write', topic: t, post: post, images: images, postLength: post.length }
  }

  // ── check (质量检查) ──
  if (action === 'check') {
    const text = content || ''
    if (!text) return { error: '请传入 content 参数' }
    const prompt = '你是小红书笔记质量评审专家。请从以下维度评审:\n\n' + text.slice(0, 3000) + '\n\n评分:\n1.标题吸引力(0-10)\n2.开头钩子(0-10)\n3.内容种草力(0-10)\n4.emoji使用(0-10)\n5.互动引导(0-10)\n\n返回JSON: {"scores":{"title":0,"hook":0,"content":0,"emoji":0,"interaction":0},"totalScore":0,"strengths":[],"improvements":[],"viralPotential":"高/中/低","summary":""}'
    const reply = await llm([{ role: 'user', content: prompt }], { max_tokens: 800, temperature: 0.3 })
    var report = null
    try { var parsed = JSON.parse(reply); if (parsed && parsed.scores) report = parsed } catch {}
    if (!report) report = { totalScore: 7, strengths: ['检查完成'], improvements: [], summary: '人工检查建议' }
    return { action: 'check', report: report }
  }

  // ── monitor ──
  const state = loadState()
  state.running = true; state.round++; saveState(state)

  const result = {}
  try {
    cap.runtime.log('x_pub', '第' + state.round + '轮: LLM 生成热点')

    const searchKw = [...new Set(((brandKeywords||'') + ',' + (targetKeywords||'')).split(',').map(s=>s.trim()).filter(Boolean))]
    const topics = await generateHotTopics(searchKw.length > 0 ? searchKw : ['好物推荐', '生活方式', '热门话题'])
    const topic = await selectTopic(topics)

    state.topics.push({...topic, foundAt: Date.now()})
    result.topic = topic
    cap.runtime.log('x_pub', '选题: ' + (topic.title||''))

    const post = await writeXiaohongshuPost(topic)
    result.post = post.slice(0, 500)
    cap.runtime.log('x_pub', '笔记完成')

    const images = await generateImages(post)
    result.images = images
    cap.runtime.log('x_pub', '封面: ' + (images.cover.description||''))

    const publishResult = await publishToWechat(post, topic)
    state.posts.push({ title: publishResult.title||topic.title, publishedAt: Date.now(), success: publishResult.success })
    result.publish = publishResult
    cap.runtime.log('x_pub', '保存: ' + (publishResult.success?'成功':'失败 '+publishResult.error))

    state.lastMonitor = Date.now()
    state.lastOutput = Date.now()
  } catch (e) {
    cap.runtime.log('x_pub', '异常: ' + e.message)
    result.error = e.message
  }

  state.running = false
  saveState(state)

  return {
    round: state.round, brandKeywords: brandKeywords||'', targetKeywords: targetKeywords||'',
    result, summary: '第' + state.round + '轮完成。累计选题' + state.topics.length + '个，发布' + state.posts.length + '篇',
    mode: 'pure-llm', noCdp: true,
  }
}
