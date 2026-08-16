// 微信小程序开发助手 v1.0.0
// =============================================================================
// LLM 驱动的全流程小程序开发指导技能。
// 支持 7 种操作：create / guidance / template / publish / optimize / troubleshoot / query
// 依赖: cap.llm -> LLM 问答, cap.flowchart -> 流程图追踪, cap.runtime -> 日志
// =============================================================================

const SKILL_ID = 'com.tupautochrome.skills.mini-program-helper'

const FLOWCHART = {
  $schema: 'https://schema.tupautochrome.io/flowchart/v1',
  id: 'mini-program-helper-flowchart',
  skillId: SKILL_ID,
  version: '1.0.0',
  name: '小程序开发流程',
  entry: 'start',
  layout: 'TB',
  style: 'business',
  recognition: [],
  nodes: [
    { id: 'start', type: 'start', label: '开始' },
    { id: 'choose', type: 'decision', label: '选择服务', branches: { create: 'project_setup', guidance: 'dev_guidance', template: 'code_template', publish: 'publish_guide', optimize: 'optimize_guide', troubleshoot: 'troubleshoot' } },
    { id: 'project_setup', type: 'process', label: '项目搭建' },
    { id: 'dev_guidance', type: 'process', label: '开发指导' },
    { id: 'code_template', type: 'process', label: '代码模板' },
    { id: 'publish_guide', type: 'process', label: '发布流程' },
    { id: 'optimize_guide', type: 'process', label: '性能优化' },
    { id: 'troubleshoot', type: 'process', label: '问题排查' },
    { id: 'report', type: 'process', label: '报告' },
    { id: 'end', type: 'end', label: '结束' },
  ],
  connections: [
    { from: 'start', to: 'choose' },
    { from: 'choose', to: 'project_setup', label: 'create' },
    { from: 'choose', to: 'dev_guidance', label: 'guidance' },
    { from: 'choose', to: 'code_template', label: 'template' },
    { from: 'choose', to: 'publish_guide', label: 'publish' },
    { from: 'choose', to: 'optimize_guide', label: 'optimize' },
    { from: 'choose', to: 'troubleshoot', label: 'troubleshoot' },
    { from: 'project_setup', to: 'report' },
    { from: 'dev_guidance', to: 'report' },
    { from: 'code_template', to: 'report' },
    { from: 'publish_guide', to: 'report' },
    { from: 'optimize_guide', to: 'report' },
    { from: 'troubleshoot', to: 'report' },
    { from: 'report', to: 'end' },
  ],
  judgments: [], selectors: {}, variables: { input: { type: 'object' } },
  metadata: { createdAt: '2026-07-28T00:00:00Z', updatedAt: '2026-07-28T00:00:00Z', author: 'AIMarketing' },
}

const KB = {
  project: '项目结构：\nproject/\n├── app.js          # App() 全局入口\n├── app.json        # pages/window/tabBar 配置\n├── app.wxss        # 全局样式\n├── project.config.json\n├── sitemap.json\n└── pages/\n    └── index/\n        ├── index.wxml  # 页面模板 (view/text/image)\n        ├── index.wxss  # 页面样式 (rpx 单位)\n        ├── index.js    # Page({ data, onLoad, ... })\n        └── index.json  # 页面配置',
  components: '核心组件：\nview 块级容器\ntext 行内文本\nimage 图片 mode=scaleToFill/aspectFit/aspectFill\nscroll-view 可滚动区域\nswiper 轮播 indicator-dots/autoplay\nrich-text 富文本\nbutton 按钮 size=default/mini type=primary/default/warn\ninput 输入框 type=text/number/idcard/digit\ntextarea 多行输入\npicker 选择器 mode=selector/multiSelector/date/time/region\ncheckbox 复选框\nradio 单选框\nswitch 开关\nslider 滑块\nform 表单\nnavigator 页面跳转\nweb-view H5 嵌入\ncanvas 画布 type=2d\nmap 地图\nopen-data 开放数据\nvideo 视频',
  apis: '核心 API：\nwx.request() HTTP 请求\nwx.uploadFile() 上传\nwx.downloadFile() 下载\nwx.setStorageSync() 存\nwx.getStorageSync() 取\nwx.navigateTo() 保留跳转\nwx.redirectTo() 关闭跳转\nwx.switchTab() tab切换\nwx.reLaunch() 关闭全部跳转\nwx.login() 获取 code\nwx.requestPayment() 支付\nwx.chooseImage() 选图\nwx.getLocation() 位置\nwx.getSystemInfo() 系统信息\nwx.getWindowInfo() 窗口信息\nwx.createSelectorQuery() 节点查询',
  lifecycle: '页面：onLoad → onShow → onReady → onHide → onUnload\n组件：created → attached → ready → moved → detached\nComponent({ properties, data, methods, lifetimes, observers })',
  wxml: '数据绑定 {{var}}\n条件 wx:if/wx:elif/wx:else\n列表 wx:for="{{list}}" wx:key="id"\n事件 bindtap catchtap\n高频切换用 hidden，低频用 wx:if',
  wxss: 'rpx 响应式单位 (750px 基准)\nFlex/Grid 布局\n支持大部分 CSS，不支持 body/media/属性选择器',
  cloud: 'wx.cloud.init({ env })\ndb.collection().add/get/update/remove\nwx.cloud.callFunction()\n自动 OPENID 鉴权',
  login: 'wx.login() → code → auth.code2Session → openid + token\ncode 5min 有效，session_key 不传前端',
  payment: '后端统一下单 → prepay_id → wx.requestPayment → 异步通知\n金额单位分，prepay_id 2h 有效',
  design: '四大原则：友好礼貌/清晰明确/便捷优雅/统一稳定\n设计稿 750px，rpx 单位，字号 20/18/17/16/14/13/11pt',
  publish: '备案强制 (86369) 1-20天\n认证 个人30/企业300\n主包≤2MB 总包≤30MB\n审核 1-2工作日',
  performance: 'setData ≤256KB ≤20次/秒\n节点<1000 深度<30\n首屏<5s 渲染<500ms\nWebView vs Skyline',
  skyline: 'app.json "renderer":"skyline"\n基础库 2.30.4+\n同层渲染 worklet 动画 手势系统\n不支持 selectComponent 用 this.$widget',
  marketing: '裂变 1-3元/用户\n公众号/视频号/朋友圈/搜一搜/附近/扫码\nK因子>1 指数增长',
  pitfalls: 'navigateTo 最多10层\nES6转ES5需勾选\n域名需配置白名单\ninput cursor 部分 Android 不生效\nvideo 层级最高需 cover-view',
}

async function handler(params, complete) {
  const { action } = params
  if (action === 'get_flowchart') return cap.flowchart.get() || FLOWCHART
  if (action === 'get_trace') return cap.flowchart.trace
  cap.flowchart.setCurrent(FLOWCHART); cap.control.reset()
  if (cap.llm && cap.llm.setComplete) cap.llm.setComplete(complete)
  switch (action) {
    case 'create': return await createProject(params)
    case 'guidance': return await devGuidance(params)
    case 'template': return await codeTemplate(params)
    case 'publish': return await publishGuide(params)
    case 'optimize': return await optimizeGuide(params)
    case 'troubleshoot': return await troubleshoot(params)
    case 'query': return await queryKnowledge(params)
    default: return { ok: false, error: '未知操作: ' + action + '，可选: create/guidance/template/publish/optimize/troubleshoot/query' }
  }
}

async function createProject(params) {
  const t0 = cap.flowchart.beginNode('project_setup')
  const { projectName, appId, description, template } = params
  if (!projectName) return { ok: false, error: '请提供项目名称 (projectName)' }
  const llmCtx = [
    '你是微信小程序开发架构师。用户要新建项目。',
    '项目：' + (projectName || ''), 'AppID：' + (appId || '测试号'),
    '描述：' + (description || ''), '模板：' + (template || '原生'),
    '输出 JSON：{"setupSteps":[...],"appJson":{...},"firstPage":{"wxml":"...","wxss":"...","js":"...","json":"..."},"recommendations":[...]}',
  ].join('\n')
  let result
  if (cap.llm) {
    try { const resp = await cap.llm.complete(llmCtx, { temperature: 0.5 }); try { result = JSON.parse(resp) } catch { result = { guide: resp } } }
    catch (e) { result = { error: String(e), fallback: true } }
  }
  if (!result || result.fallback) {
    result = { setupSteps: ['注册小程序账号 mp.weixin.qq.com', '下载开发者工具 Stable 版', '新建项目填入 AppID', '配置 app.json', '创建首屏'], appJson: { pages: ['pages/index/index'], window: { navigationBarTitleText: projectName } }, firstPage: { wxml: '<view class="container"><text>Hello {{name}}</text></view>', wxss: '.container{display:flex;align-items:center;justify-content:center;height:100vh;}', js: 'Page({data:{name:"' + projectName + '"},onLoad(){console.log("loaded")}})', json: '{}' } }
  }
  cap.flowchart.endNode('project_setup', 'ok', '搭建指南', t0)
  return { ok: true, action: 'create', projectName, result }
}

async function devGuidance(params) {
  const t0 = cap.flowchart.beginNode('dev_guidance')
  const { topic, question, experience } = params
  const ctx = ['你是微信小程序开发专家。用户经验:' + (experience || '新手'), '主题:' + (topic || '综合') + ' 问题:' + (question || ''), '参考知识：', '---组件---\n' + KB.components, '---API---\n' + KB.apis, '---生命周期---\n' + KB.lifecycle, '---WXML---\n' + KB.wxml, '---WXSS---\n' + KB.wxss, '---登录---\n' + KB.login, '---支付---\n' + KB.payment, '---云开发---\n' + KB.cloud, '---设计---\n' + KB.design, '---Skyline---\n' + KB.skyline, '回答要求：包含具体代码示例 + wxss 样式 + 最佳实践', '如果是新手，推荐 7 天学习路线'].join('\n')
  let result
  if (cap.llm) { try { const resp = await cap.llm.complete(ctx, { temperature: 0.6 }); try { result = JSON.parse(resp) } catch { result = { guide: resp } } } catch (e) { result = { error: String(e), fallback: true } } }
  if (!result || result.fallback) { result = { summary: topic ? '关于「' + topic + '」的开发指导' : '综合指导', keyAreas: ['项目结构', 'WXML', 'WXSS', '组件', 'API', '生命周期', '云开发'] } }
  cap.flowchart.endNode('dev_guidance', 'ok', '指导已生成', t0)
  return { ok: true, action: 'guidance', topic, result }
}

async function codeTemplate(params) {
  const t0 = cap.flowchart.beginNode('code_template')
  const { pageType, feature } = params
  const TEMPLATES = {
    list: { name: '列表页', wxml: '<view class="container"><view class="search"><input bindinput="onSearch" placeholder="搜索"/></view><scroll-view scroll-y class="list"><view wx:for="{{list}}" wx:key="id" class="item" bindtap="onTap" data-id="{{item.id}}"><image src="{{item.thumb}}"/><view class="info"><text class="title">{{item.title}}</text><text class="desc">{{item.desc}}</text></view></view></scroll-view></view>', wxss: '.container{height:100vh;display:flex;flex-direction:column;}.search{padding:20rpx;}.search input{background:#f5f5f5;padding:20rpx;border-radius:12rpx;}.list{flex:1;}.item{display:flex;padding:24rpx;border-bottom:1rpx solid #eee;}.item image{width:160rpx;height:160rpx;border-radius:12rpx;}.info{margin-left:20rpx;flex:1;}.title{font-size:28rpx;font-weight:600;}.desc{font-size:24rpx;color:#999;margin-top:8rpx;}' },
    form: { name: '表单页', wxml: '<view class="container"><form bindsubmit="onSubmit"><view class="field"><text class="label">姓名</text><input name="name" placeholder="请输入"/></view><view class="field"><text class="label">手机</text><input name="phone" type="number" placeholder="请输入手机号"/></view><view class="field"><text class="label">类型</text><picker mode="selector" range="{{types}}" bindchange="onTypeChange"><text>{{types[typeIndex]||"请选择"}}</text></picker></view><button form-type="submit" class="submit">提交</button></form></view>', wxss: '.container{padding:30rpx;}.field{margin-bottom:30rpx;}.label{font-size:28rpx;color:#666;margin-bottom:10rpx;display:block;}.field input,.field picker{width:100%;height:80rpx;border:1rpx solid #ddd;border-radius:12rpx;padding:0 20rpx;box-sizing:border-box;}.submit{background:#07c160;color:#fff;border-radius:12rpx;margin-top:60rpx;}' },
    detail: { name: '详情页', wxml: '<view class="container"><swiper indicator-dots autoplay circular><swiper-item wx:for="{{banners}}" wx:key="*this"><image src="{{item}}"/></swiper-item></swiper><view class="info"><text class="title">{{title}}</text><text class="price">¥{{price}}</text><text class="desc">{{desc}}</text></view><button class="buy" bindtap="onBuy">立即购买</button></view>', wxss: 'swiper{height:600rpx;}swiper image{width:100%;height:100%;}.info{padding:30rpx;}.title{font-size:36rpx;font-weight:700;}.price{font-size:48rpx;color:#ff4444;margin:20rpx 0;display:block;}.desc{font-size:26rpx;color:#666;line-height:1.6;}.buy{background:#ff4444;color:#fff;border-radius:12rpx;margin:40rpx 30rpx;}' },
    tabs: { name: 'Tab切换', wxml: '<view class="container"><view class="tabs"><view wx:for="{{tabs}}" wx:key="*this" class="tab {{activeTab===index?'active':''}}" bindtap="switchTab" data-index="{{index}}">{{item}}</view></view><swiper current="{{activeTab}}" bindchange="onSwiper" class="content"><swiper-item wx:for="{{tabs}}" wx:key="*this"><view class="page"><text>{{item}} 内容区域</text></view></swiper-item></swiper></view>', wxss: '.tabs{display:flex;background:#fff;border-bottom:1rpx solid #eee;}.tab{flex:1;text-align:center;padding:24rpx 0;font-size:28rpx;color:#666;}.tab.active{color:#07c160;border-bottom:4rpx solid #07c160;}.content{height:calc(100vh - 88rpx);}.page{display:flex;justify-content:center;align-items:center;height:100%;}' },
    login: { name: '登录页', wxml: '<view class="container"><image class="logo" src="/images/logo.png"/><text class="title">{{appName}}</text><button class="login-btn" type="primary" bindtap="onLogin" loading="{{loading}}">微信一键登录</button><text class="agree">登录即表示同意《用户协议》</text></view>', wxss: '.container{display:flex;flex-direction:column;align-items:center;padding-top:200rpx;}.logo{width:160rpx;height:160rpx;border-radius:32rpx;}.title{font-size:48rpx;font-weight:700;margin:30rpx 0 80rpx;}.login-btn{width:600rpx;border-radius:48rpx;}.agree{font-size:24rpx;color:#999;margin-top:30rpx;}' },
  }
  if (pageType && TEMPLATES[pageType]) {
    cap.flowchart.endNode('code_template', 'ok', '模板: ' + TEMPLATES[pageType].name, t0)
    return { ok: true, action: 'template', pageType, template: TEMPLATES[pageType] }
  }
  if (feature && cap.llm) {
    try {
      const ctx = ['你是一个微信小程序前端工程师。生成一个「' + feature + '」功能的完整页面代码。', '输出 JSON：{"name":"功能名","wxml":"WXML","wxss":"WXSS（rpx）","js":"JS","json":"{}","description":"说明"}'].join('\n')
      const resp = await cap.llm.complete(ctx, { temperature: 0.4 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e) } }
    cap.flowchart.endNode('code_template', 'ok', '模板已生成', t0)
    return { ok: true, action: 'template', feature, result }
  }
  cap.flowchart.endNode('code_template', 'ok', '可选模板', t0)
  return { ok: true, action: 'template', availableTemplates: Object.keys(TEMPLATES), templates: TEMPLATES }
}

async function publishGuide(params) {
  const t0 = cap.flowchart.beginNode('publish_guide')
  const { stage, appId } = params
  const ctx = ['你是微信小程序发布审核专家。用户阶段:' + (stage || '准备') + ' AppID:' + (appId || ''), '参考：' + KB.publish, '按阶段输出 JSON：准备→{"tasks":["备案","认证"],"links":["mp.weixin.qq.com"]}  待审核→{"submitSteps":["上传→提交"],"estimateHours":24}  被驳回→{"reason":"...","fix":"..."}  已发布→{"promotion":["公众号","裂变"],"metrics":["CAC","留存"]}'].join('\n')
  let result
  if (cap.llm) { try { const resp = await cap.llm.complete(ctx, { temperature: 0.5 }); try { result = JSON.parse(resp) } catch { result = { guide: resp } } } catch (e) { result = { error: String(e), fallback: true } } }
  if (!result || result.fallback) { result = { tasks: ['备案（必需）', '认证（推荐）', '配置域名', '提交审核'], tips: ['备案需 1-20 天', '代码包别超 2MB'] } }
  cap.flowchart.endNode('publish_guide', 'ok', '发布指南', t0)
  return { ok: true, action: 'publish', stage, result }
}

async function optimizeGuide(params) {
  const t0 = cap.flowchart.beginNode('optimize_guide')
  const { focus, description } = params
  const ctx = ['你是微信小程序性能优化专家。重点:' + (focus || '综合') + ' 项目:' + (description || ''), '参考：' + KB.performance + '\n' + KB.skyline, '按重点输出 JSON：首屏→{"strategy":"...","code":"..."} 分包→{"split":"...","size":"<2MB"} 渲染→{"virtualList":true} 启动→{"plugin":"移除未用","codeSplit":"..."}'].join('\n')
  let result
  if (cap.llm) { try { const resp = await cap.llm.complete(ctx, { temperature: 0.5 }); try { result = JSON.parse(resp) } catch { result = { guide: resp } } } catch (e) { result = { error: String(e), fallback: true } } }
  if (!result || result.fallback) { result = { summary: focus + ' 优化建议', items: ['setData ≤256KB ≤20次/秒', 'WXML 节点<1000 深度<30', '主包≤2MB 总包≤30MB', '首屏<5s 渲染<500ms'] } }
  cap.flowchart.endNode('optimize_guide', 'ok', '优化指南', t0)
  return { ok: true, action: 'optimize', focus, result }
}

async function troubleshoot(params) {
  const t0 = cap.flowchart.beginNode('troubleshoot')
  const { issue, errorCode, description } = params
  const desc = issue || description || ''
  const ctx = ['你是微信小程序 debug 专家。问题:' + desc + ' 错误码:' + (errorCode || '无'), '参考常见坑：' + KB.pitfalls, '输出 JSON：{"rootCause":"根本原因","steps":["步骤1","步骤2"],"solution":{"code":"修复代码"},"prevention":"预防措施"}'].join('\n')
  let result
  if (cap.llm) { try { const resp = await cap.llm.complete(ctx, { temperature: 0.4 }); try { result = JSON.parse(resp) } catch { result = { guide: resp } } } catch (e) { result = { error: String(e), fallback: true } } }
  if (!result || result.fallback) { result = { rootCause: '需要更多信息。请提供错误现象和复现步骤。', steps: ['查看 vConsole 报错', '检查 app.json 配置', '检查域名白名单', '检查页面路径'] } }
  cap.flowchart.endNode('troubleshoot', 'ok', '诊断完成', t0)
  return { ok: true, action: 'troubleshoot', issue: desc, result }
}

async function queryKnowledge(params) {
  const { query, topic } = params
  const topicMap = { project: KB.project, components: KB.components, apis: KB.apis, lifecycle: KB.lifecycle, wxml: KB.wxml, wxss: KB.wxss, cloud: KB.cloud, login: KB.login, design: KB.design, publish: KB.publish, performance: KB.performance, marketing: KB.marketing, pitfalls: KB.pitfalls, skyline: KB.skyline, payment: KB.payment }
  if (topic && topicMap[topic]) return { ok: true, action: 'query', topic, data: topicMap[topic] }
  if (query && cap.llm) {
    try {
      const relevant = Object.entries(topicMap).filter(([k]) => query.includes(k) || query.includes('全部')).slice(0, 5)
      const ctx = ['你是微信小程序知识库。问题：' + query, '', '参考：', ...relevant.map(([k, v]) => '---' + k + '---\n' + v), '', '回答：包含具体代码、配置、最佳实践。中文。'].join('\n')
      const resp = await cap.llm.complete(ctx, { temperature: 0.3 })
      return { ok: true, action: 'query', query, answer: resp }
    } catch (e) { return { ok: true, action: 'query', query, availableTopics: Object.keys(topicMap) } }
  }
  return { ok: true, action: 'query', availableTopics: Object.keys(topicMap) }
}

export const lifecycle = {
  onSkillLoad: async (ctx) => cap.runtime.log('miniprogram', 'skill loaded'),
  onTaskStart: async (ctx, task) => cap.runtime.log('miniprogram', 'task start: ' + task),
  onTaskEnd: async (ctx, task, result) => cap.runtime.log('miniprogram', 'task end: ' + task),
  onSkillUnload: async (ctx) => cap.runtime.log('miniprogram', 'skill unloaded'),
}

export default handler
