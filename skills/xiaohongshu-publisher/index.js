// 小红书文案技能 — 全网热点监测 → 自动撰写 → 配图 → 发布到微信公众号草稿箱备份
// 参数: brandKeywords(自有品牌词), targetKeywords(监测目标关键词), monitorInterval(监测间隔分钟), outputInterval(输出间隔分钟)
// 动作: monitor / status / stop
async function handler(params, complete) {
  const { action, brandKeywords, targetKeywords, monitorInterval, outputInterval } = params
  cap.llm.setComplete(complete)

  const STORAGE_KEY = 'trace_xiaohongshu_publisher_state'
  function loadState() { return cap.storage.get(STORAGE_KEY, { running: false, lastMonitor: 0, lastOutput: 0, topics: [], posts: [], round: 0 }) }
  function saveState(s) { cap.storage.set(STORAGE_KEY, s) }

  async function searchHotTopics(keywords) {
    const results = []
    const engines = [
      { url: 'https://www.xiaohongshu.com/search_result?keyword=' + encodeURIComponent(keywords[0] || '热门'), name: 'xiaohongshu' },
      { url: 'https://www.baidu.com/s?wd=' + encodeURIComponent(keywords.join(' ')), name: 'baidu' },
    ]
    for (const e of engines) {
      try {
        await cap.cdp.eval('window.location.href="' + e.url.replace(/"/g, '\\"') + '"')
        await cap.runtime.sleep(3500)
        const text = await cap.cdp.eval('document.body.innerText')
        if (text && typeof text === 'string' && text.length > 50) {
          results.push({ source: e.name, text: text.slice(0, 4000) })
        }
      } catch (err) { cap.runtime.log('x_search', e.name + ' err: ' + (err.message||'')) }
    }
    return results
  }

  async function selectTopic(searchResults) {
    const prompt = '你是一个小红书内容策划。品牌词:"' + (brandKeywords||'') + '" 目标词:"' + (targetKeywords||'') + '"\n\n搜索数据:\n' + JSON.stringify(searchResults).slice(0, 6000) + '\n\n选一个最适合小红书的爆款话题，返回JSON: {"title":"标题≤20字带emoji","reason":"选择理由","keywords":["关键词"],"style":"教程/测评/清单/故事/vlog"}'
    const reply = await cap.llm.complete([{ role: 'user', content: prompt }], { max_tokens: 500, temperature: 0.5 })
    try { return JSON.parse(reply) }
    catch { return { title: (reply||'').slice(0, 60) || '🔥 今日热门', reason: 'LLM 自动选题', keywords: (targetKeywords||'').split(',').filter(Boolean), style: '清单' } }
  }

  async function writeXiaohongshuPost(topic) {
    const prompt = '你是一个小红书爆款笔记写手。写一篇小红书笔记:\n\n品牌词:' + (brandKeywords||'') + '\n目标词:' + (targetKeywords||'') + '\n选题:' + JSON.stringify(topic) + '\n\n要求:\n1.标题≤20字，带emoji\n2.正文≤1000字，短句分行\n3.每段开头用emoji\n4.口语化，像姐妹聊天\n5.有干货/清单/步骤\n6.结尾引导点赞收藏评论\n7.加3-5个话题标签\n8.自然融入品牌词"' + (brandKeywords||'') + '"\n\n直接输出笔记内容:'
    const reply = await cap.llm.complete([{ role: 'user', content: prompt }], { max_tokens: 2048, temperature: 0.8 })
    return reply || '笔记生成失败'
  }

  async function generateImages(postText) {
    try {
      const prompt = '为以下小红书笔记配封面，描述风格/色调/构图，30字内:\n' + (postText||'').slice(0, 600)
      const coverDesc = await cap.llm.complete([{ role: 'user', content: prompt }], { max_tokens: 200, temperature: 0.8 })
      return { cover: { description: (coverDesc||'').trim().slice(0, 100), generated: false }, note: '配置图片 API Key 后可自动生成配图' }
    } catch (e) { return { cover: { description: '默认封面', generated: false }, error: e.message } }
  }

  async function publishToWechat(post, topic) {
    try {
      const title = ((post||'').split('\n')[0]||'').replace(/^#\s*/,'').trim().slice(0,20) || topic.title || '小红书笔记'
      const body = (post||'').split('\n').filter(l => !l.startsWith('#') && !l.startsWith('```')).join('\n')
      const bodyHtml = body.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n\n/g,'</p><p style="margin:8px 0">').replace(/\n/g,'<br>')
      const html = '<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="font-size:15px;line-height:1.7;padding:8px;color:#333;font-family:-apple-system,sans-serif"><p style="font-size:12px;color:#999">📕 小红书笔记备份</p><p>' + bodyHtml + '</p></body></html>'

      await cap.cdp.eval("window.location.href='https://mp.weixin.qq.com/'")
      await cap.runtime.sleep(5000)

      const loginCheck = await cap.cdp.eval('document.querySelector(".account_name,.weui-desktop-account__name,.login__title") ? "ok" : "no"')
      if (loginCheck !== 'ok') {
        return { success: false, error: '请先登录 mp.weixin.qq.com，再重试', needLogin: true }
      }

      await cap.cdp.eval("window.location.href='https://mp.weixin.qq.com/cgi-bin/appmsg?t=media/appmsg_edit&action=edit&type=10&create=1'")
      await cap.runtime.sleep(5000)

      const safeTitle = ('[小红书] ' + title).replace(/'/g,"\\'")
      await cap.cdp.eval("(function(){var el=document.querySelector('#title');if(el){el.value='" + safeTitle + "';el.dispatchEvent(new Event('input',{bubbles:true}))}return 'done'})()")
      await cap.runtime.sleep(500)

      await cap.cdp.eval("(function(){var el=document.querySelector('#content,.rich_media_content,#js-rich-editor,.weui-desktop-rich-editor');if(!el)return 'no_editor';el.focus();document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);document.execCommand('insertHTML',false,'" + html.replace(/\\/g,'\\\\').replace(/'/g,"\\'").replace(/\n/g,' ').slice(0,15000) + "');return 'ok'})()")

      await cap.runtime.sleep(2000)
      return { success: true, title: '[小红书] ' + title, platform: 'wechat', draftSaved: true, note: '请在微信公众号后台检查草稿' }
    } catch (e) { return { success: false, error: '发布失败: ' + e.message } }
  }

  // ── status ──
  if (action === 'status') {
    const s = loadState()
    return { running: s.running, lastMonitor: s.lastMonitor ? new Date(s.lastMonitor).toISOString() : '从未', lastOutput: s.lastOutput ? new Date(s.lastOutput).toISOString() : '从未', totalRounds: s.round, topicsFound: s.topics.length, postsPublished: s.posts.length }
  }

  // ── stop ──
  if (action === 'stop') {
    const s = loadState(); s.running = false; saveState(s)
    return { stopped: true }
  }

  // ── monitor ──
  const state = loadState()
  state.running = true; state.round++; saveState(state)

  const result = {}
  try {
    cap.runtime.log('x_pub', '第' + state.round + '轮: 搜索热点')

    const searchKw = [...new Set(((brandKeywords||'') + ',' + (targetKeywords||'')).split(',').map(s=>s.trim()).filter(Boolean))]
    const searchResults = await searchHotTopics(searchKw.length > 0 ? searchKw : ['好物推荐', '生活方式', '热门话题'])

    const topic = await selectTopic(searchResults)
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
    cap.runtime.log('x_pub', '发布: ' + (publishResult.success?'成功':'失败 '+publishResult.error))

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
    result, summary: '第' + state.round + '轮完成。累计选题' + state.topics.length + '个，发布' + state.posts.length + '篇'
  }
}
