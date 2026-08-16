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
  metadata: { createdAt: '2026-07-28T00:00:00Z', updatedAt: '2026-07-28T00:00:00Z', author: 'tupAI' },
}

const KB = {
  project: `项目结构：
project/
├── app.js          # App() 全局入口
├── app.json        # pages/window/tabBar 配置
├── app.wxss        # 全局样式
├── project.config.json
├── sitemap.json
└── pages/
    └── index/
        ├── index.wxml  # 页面模板 (view/text/image)
        ├── index.wxss  # 页面样式 (rpx 单位)
        ├── index.js    # Page({ data, onLoad, ... })
        └── index.json  # 页面配置`,

  components: `核心组件：

view          块级容器，相当于 div
text          行内文本，支持 user-select
image         图片，mode=scaleToFill/aspectFit/aspectFill
scroll-view   可滚动区域，enable-flex 启用 flex
swiper        轮播，indicator-dots/autoplay/interval
rich-text     富文本渲染，nodes 属性接收 HTML/节点树
button        按钮，size=default/mini，type=primary/default/warn
input         输入框，type=text/number/idcard/digit
textarea      多行输入，auto-height 自动增高
picker        选择器，mode=selector/multiSelector/date/time/region
checkbox      复选框，color 自定义颜色
radio         单选框，搭配 radio-group 使用
switch        开关，checked/color
slider        滑块，min/max/step/show-value
form          表单，bindsubmit 收集数据
navigator     页面跳转，url/open-type
web-view      H5 网页嵌入，src 绑定合法域名
canvas        画布，type=2d/webgl
map           地图，longitude/latitude/markers
open-data     开放数据，type=userNickName/userAvatarUrl
ad             Banner 广告，unit-id 广告单元
video         视频播放，src/duration/controls`,

  apis: `核心 API：

wx.request({ url, method, data, header })          HTTP 请求
wx.uploadFile({ url, filePath, name })             文件上传
wx.downloadFile({ url })                           文件下载
wx.setStorageSync(key, data)                       同步存
wx.getStorageSync(key)                             同步取
wx.removeStorageSync(key)                          同步删
wx.navigateTo({ url })                             保留当前页跳转
wx.redirectTo({ url })                             关闭当前页跳转
wx.switchTab({ url })                              tab 切换
wx.reLaunch({ url })                               关闭所有页跳转
wx.navigateBack({ delta })                         返回
wx.login({ success })                              获取 code
wx.getUserProfile({ desc, lang })                  获取用户信息
wx.authorize({ scope })                            授权
wx.requestPayment({ timeStamp, nonceStr, package, signType, paySign })  支付
wx.chooseImage({ count, sizeType, sourceType })    选图
wx.getLocation({ type })                           获取位置
wx.getSystemInfo({ success })                      系统信息
wx.getUpdateManager()                              更新管理器
wx.getWindowInfo()                                 窗口信息
wx.nextTick(callback)                              下一帧回调
wx.startPullDownRefresh()                          触发下拉刷新
wx.stopPullDownRefresh()                           停止下拉刷新
wx.pageScrollTo({ scrollTop, duration })           页面滚动
wx.createSelectorQuery()                           节点查询
wx.createIntersectionObserver()                    交叉观察`,

  lifecycle: `页面生命周期 (Page)：
onLoad(query)     — 页面加载，只一次，query 含跳转参数
onShow()          — 页面显示/从后台切前台
onReady()         — 页面初次渲染完成
onHide()          — 页面隐藏/切入后台
onUnload()        — 页面卸载
onPullDownRefresh()  — 下拉刷新
onReachBottom()      — 上拉触底
onShareAppMessage()  — 用户转发
onPageScroll({ scrollTop }) — 页面滚动

组件生命周期 (lifetimes)：
created → attached → ready → moved → detached

Component 构造器：
Component({
  properties: {},          // 外部传入属性
  data: {},                // 内部状态
  methods: {},             // 组件方法
  lifetimes: { attached(){} },  // 生命周期
  observers: { 'key'(val){} }   // 数据监听
})`,

  wxml_syntax: `WXML 模板语法：

数据绑定：    <view>{{ userName }}</view>
属性绑定：    <image src="{{ avatarUrl }}"></image>
条件渲染：    <view wx:if="{{ visible }}">显示</view>
             <view wx:elif="{{ type === 'a' }}">A</view>
             <view wx:else>其他</view>
列表渲染：    <view wx:for="{{ list }}" wx:key="id">
               <text>{{ index }}: {{ item.name }}</text>
             </view>
模板引用：    <import src="template.wxml"/>
             <template is="card" data="{{ item }}"/>
事件绑定：    <button bindtap="onTap">点我</button>
             <button catchtap="onTap">阻止冒泡</button>
             <button mut-bind="onTap">互斥绑定</button>
自定义数据：  <view data-id="{{ id }}" bindtap="onTap"/>
条件展示：    <view hidden="{{ !visible }}">hidden 用 display 控制</view>

注意：wx:if 是"惰性"的（切换销毁/创建）
      hidden 始终渲染，仅切换 display 属性
      频繁切换用 hidden，否则用 wx:if`,

  wxss: `WXSS 样式：

尺寸单位：rpx（responsive pixel）
设计稿 750px 宽，1rpx = 屏幕宽度 / 750
iPhone 6: 1rpx = 0.5px

选择器：   .class   #id   element   element,element
           ::after  ::before

内置选择器：:host   选中组件自身

支持大部分 CSS 属性，不支持：
- 不支持 body/html 选择器
- 不支持媒体查询 @media（用 wx.getSystemInfo 替代）
- 不支持部分动画属性（用 CSS animation + transition）
- 不支持属性选择器 [attr]

Flex 布局示例：
.container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
}

Grid 布局示例：
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20rpx;
}`,

  cloud_dev: `云开发 (CloudBase)：

初始化 (app.js onLaunch)：
wx.cloud.init({ env: 'your-env-id', traceUser: true })

云数据库 (MongoDB-like)：
const db = wx.cloud.database()
const _ = db.command
// 增
await db.collection('todos').add({ data: { title, done: false } })
// 查
await db.collection('todos').where({ done: false }).get()
await db.collection('todos').doc('id').get()
await db.collection('todos').where({ price: _.gte(100) }).get()
// 改
await db.collection('todos').doc('id').update({ data: { done: true } })
// 删
await db.collection('todos').doc('id').remove()
// 分页 + 排序
await db.collection('todos').orderBy('createTime', 'desc').skip(0).limit(20).get()

云函数：
// 定义 cloudfunctions/add/index.js
const cloud = require('wx-server-sdk')
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })
exports.main = async (event) => ({ sum: event.x + event.y, openid: cloud.getWXContext().OPENID })

// 调用
const res = await wx.cloud.callFunction({ name: 'add', data: { x: 1, y: 2 } })

云存储：
await wx.cloud.uploadFile({ cloudPath: 'a.jpg', filePath: tmpPath })
await wx.cloud.downloadFile({ fileID: 'cloud://...' })
wx.cloud.deleteFile({ fileList: ['cloud://...'] })

云开发优势：自动鉴权（OPENID），无需管理证书，自带 CDN`,

  login_auth: `登录认证：

标准流程：
wx.login() → 临时 code
→ 后端 wx.auth.code2Session({ appid, secret, js_code })
→ 返回 { openid, session_key, unionid }
→ 后端生成自定义 token → 返回前端

关键约束：
code 有效期 5 分钟，只能用一次
session_key 绝不传输给前端（解密敏感数据用）
unionid 需绑定微信开放平台

前端存储 token：
wx.setStorageSync('token', token)

wx.request 携带 token：
wx.request({
  url: 'https://api.example.com/user',
  header: { Authorization: 'Bearer ' + wx.getStorageSync('token') }
})

权限 scope 列表：
scope.userInfo         用户信息
scope.userLocation    地理位置
scope.address         通讯地址
scope.invoiceTitle    发票抬头
scope.werun          微信运动
scope.record         录音功能
scope.writePhotosAlbum 保存到相册
scope.camera         摄像头`,

  design: `设计规范：

四大原则：
1. 友好礼貌 — 流程明确，减少干扰，重点突出
2. 清晰明确 — tab 2-5 个（≤4 推荐），返回按钮必须，层级不超过 3 级
3. 便捷优雅 — 点击热区 ≥ 7-9mm，用选择代替输入，使用系统接口
4. 统一稳定 — 控件和交互保持全局一致

视觉基准：
设计稿宽度 750px (iPhone 6 2x)
色彩：品牌色 + 辅助色 + 中性色
字号：20(导航标题)/18(大标题)/17(列表标题)/16(正文)/14(辅助)/13(说明)/11(角标)
间距：8/12/16px 间隔体系
圆角：6/12/16px 三级

加载反馈：
- 局部加载 (Loading) 优于模态加载
- 谨慎使用 wx.showLoading（全屏覆盖引起焦虑）
- 加载 > 3s 提供取消操作 + 进度
- 成功用 wx.showToast (1.5s 自动消失)
- 错误必须用 wx.showModal（用户必须确认）

UI 库推荐：
Vant Weapp (有赞)  https://vant-ui.github.io/vant-weapp/
WeUI (微信官方)    https://github.com/tencent/weui-wxss
TDesign (腾讯)     https://tdesign.tencent.com/miniprogram
ColorUI           https://github.com/weilanwl/coloruicss`,

  publish: `发布与备案：

备案 2023.09 起强制（错误码 86369）
流程：信息填写 → 平台初审(1-2天) → 工信部短信核验(24h) → 通管局(1-20天)
认证费用：个人 30元 / 企业 300元/年（认证后可被搜索和分享）

提审流程：
开发者工具上传 → 版本管理 → 提交审核 → 1-2个工作日 → 手动发布
90天内未备案完成 → 小程序强制下架

常见驳回：
- 页面路径错误 / 类目未配置 / 失效
- 未声明 requiredPrivateInfos
- UGC 类目缺少审核机制说明
- navigateToMiniProgram 未声明跳转 AppID 列表
- 代码包超过 2MB

分包规则：
主包 ≤ 2MB，总包 ≤ 30MB（含主包）
tabBar 页面必须在主包
按功能/场景/频率划分分包
使用 preloadRule 预下载常用分包
wx.loadSubpackage() 主动触发分包下载
独立分包允许外部直达页面无需下载主包`,

  performance: `性能优化：

setData 限制：单次 ≤ 256KB，频率 ≤ 20次/秒
WXML 限制：节点数 < 1000，深度 < 30 层
首屏：< 5秒，渲染 < 500ms

高频切换用 hidden（不销毁 DOM），低频用 wx:if
长列表用 虚拟列表 或 分页加载
定时器/监听 in onUnload 清理
图片宽高积 ≤ 显示宽高 × dpr²
onPageScroll 避免复杂计算

双渲染引擎对比：
WebView — 兼容全版本，每页一个 WebView，原生组件层级遮罩问题
Skyline — 基础库 2.30.4+，同层渲染，worklet 动画，手势系统，更快启动

迁移 Skyline：
app.json 配置 "renderer": "skyline"（全局）或 "renderingMode": "skyline"（页面级）
逐页测试，注意 WebView 特有 API 兼容性
Skyline 子集：不支持 selectComponent/selectAllComponents，使用 this.$widget`,

  marketing: `流量来源：

社交裂变 1-3元/用户 (拼团/砍价/盲盒)
公众号 菜单/图文/模板消息
视频号 直播挂载/信息流
朋友圈广告 CPM/CPC 精准投放
搜一搜 WSEO 优化（免费）
附近小程序 LBS 优化（免费）
线下扫码 门店铺设
企微社群 推送+运营

社交裂变公式：传播动力 = 奖励感知 - 参与成本 - 信任风险
K 因子 > 1 才能指数增长

核心指标：
CAC 电商 30-60元 / 本地生活 15-40元
7日留存 零售类 20-30%
自然流量占比 成熟项目 50-70%
UV 转化率 3-8% 健康`,

  pitfalls: `常见坑：

wx.navigateTo 页面栈最多 10 层，超出必须用 redirectTo/reLaunch
ES6 转 ES5 在开发者工具必须勾选
合法域名需在 mp 后台配置（开发可勾选不校验）
app.json permission 必须写用途说明
setData 频率和大小限制
开发者工具更新可能导致兼容问题（关键期避免升级）

组件坑：
input 的 cursor 属性在部分 Android 机型不生效
video 组件层级最高（cover-view 可覆盖）
scroll-view 内不能放 textarea/map/video
image 组件默认 300x150，必须设宽高
canvas 2d 接口与旧版不兼容

调试技巧：
vConsole 在工具/手机都可以开启
wx.getLogManager() 写日志
真机调试可打断点
WXS 脚本在 iOS 部分版本有坑
体验版二维码限制：需加入开发/体验者白名单`,

  skyline: `Skyline 渲染引擎（微信自研）：

启用：app.json "renderer": "skyline"（全局）
或页面级 "renderingMode": "skyline"

核心优势：
- 同层渲染（原生组件不再遮罩）
- worklet 动画（渲染线程执行，不阻塞 JS）
- 声明式手势系统（tap/pan/swipe/pinch）
- 更快启动速度（单线程渲染）
- 自定义 tabBar 更流畅

注意事项：
- 最低基础库 2.30.4
- 不支持 selectComponent / selectAllComponents，改用 this.$widget
- 部分 WebView API 不可用
- 自定义组件需适配
- 逐页迁移，推荐新项目直接使用

worklet 动画示例：
<view
  worklet:animate="{{ { opacity: [0, 1], transform: ['scale(0)', 'scale(1)'] } }}"
  worklet:duration="300"
>
`,

  payment: `微信支付：

流程：
1. 用户点击购买
2. 后端统一下单（appid + mchid + description + out_trade_no + notify_url + amount）
3. 后端获取 prepay_id + 签名参数
4. 前端 wx.requestPayment({
     timeStamp: '...',
     nonceStr: '...',
     package: 'prepay_id=...',
     signType: 'RSA',
     paySign: '...'
   })
5. 用户输入密码完成支付
6. 微信异步通知 notify_url → 后端更新订单

准备条件：
- 小程序认证通过（企业 300元/年）
- 微信商户平台注册并开通 JSAPI 支付
- 商户号绑定小程序 AppID
- 配置支付回调域名

云开发支付（更简单）：
CloudPay.unifiedOrder({
  body: '商品描述',
  outTradeNo: '订单号',
  spbillCreateIp: '127.0.0.1',
  subMchId: '商户号',
  totalFee: 100, // 分
  envId: '环境ID',
  functionName: '支付回调云函数'
})

注意：
prepay_id 有效期 2 小时
金额单位是分（不是元）
必须使用异步通知更新订单（不要信任前端回调）
退款调用 CloudPay.refund 或 API 退款`,
}

async function handler(params, complete) {
  const { action, query, topic, question } = params
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
    '你是一个微信小程序开发架构师。用户要新建项目。',
    '项目：' + (projectName || ''),
    'AppID：' + (appId || '测试号'),
    '描述：' + (description || ''),
    '模板：' + (template || '原生'),
    '',
    '输出 JSON：',
    '{',
    '  "setupSteps": ["注册→下载→新建→配置→创建首屏"],',
    '  "appJson": { "pages", "window", "tabBar" } 配置模板,',
    '  "firstPage": { "wxml", "wxss", "js", "json" } 代码,',
    '  "recommendations": ["后续建议..."]',
    '}',
  ].join('\n')

  let result
  if (cap.llm) {
    try {
      const resp = await cap.llm.complete(llmCtx, { temperature: 0.5 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e), fallback: true } }
  }

  if (!result || result.fallback) {
    result = {
      setupSteps: [
        '注册小程序账号 mp.weixin.qq.com → 获取 AppID',
        '下载微信开发者工具 Stable 版',
        '新建项目 → 填入 AppID → 选择 JavaScript 基础模板',
        '配置 app.json 添加页面路由',
        '创建第一个页面 pages/index 的 4 文件',
      ],
      appJson: {
        pages: ['pages/index/index', 'pages/logs/logs'],
        window: { navigationBarTitleText: projectName, navigationBarBackgroundColor: '#07c160' },
      },
      firstPage: {
        wxml: '<view class="container"><text class="title">Hello {{name}}</text></view>',
        wxss: '.container{display:flex;justify-content:center;align-items:center;height:100vh;}.title{font-size:40rpx;color:#333;}',
        js: 'Page({data:{name:"' + projectName + '"},onLoad(){console.log("loaded")}})',
        json: '{}',
      },
    }
  }

  cap.flowchart.endNode('project_setup', 'ok', '搭建指南', t0)
  return { ok: true, action: 'create', projectName, result, knowledge: { project: KB.project, wxml: KB.wxml_syntax, wxss: KB.wxss } }
}

async function devGuidance(params) {
  const t0 = cap.flowchart.beginNode('dev_guidance')
  const { topic, question, experience } = params

  const ctx = [
    '你是微信小程序开发专家。用户经验:' + (experience || '新手'),
    '主题:' + (topic || '综合') + ' 问题:' + (question || ''),
    '',
    '参考知识：',
    '---组件---\n' + KB.components,
    '---API---\n' + KB.apis,
    '---生命周期---\n' + KB.lifecycle,
    '---WXML---\n' + KB.wxml_syntax,
    '---WXSS---\n' + KB.wxss,
    '---登录---\n' + KB.login_auth,
    '---支付---\n' + KB.payment,
    '---云开发---\n' + KB.cloud_dev,
    '---设计---\n' + KB.design,
    '---Skyline---\n' + KB.skyline,
    '',
    '回答要求：包含具体代码示例 + wxss 样式 + 最佳实践',
    '如果是新手，推荐 7 天学习路线',
  ].join('\n')

  let result
  if (cap.llm) {
    try {
      const resp = await cap.llm.complete(ctx, { temperature: 0.6 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e), fallback: true } }
  }

  if (!result || result.fallback) {
    result = {
      summary: topic ? '关于「' + topic + '」的开发指导' : '综合指导',
      keyAreas: ['项目结构', 'WXML 语法', 'WXSS 样式', '组件使用', 'API 调用', '生命周期', '云开发'],
    }
  }

  cap.flowchart.endNode('dev_guidance', 'ok', '指导已生成', t0)
  return {
    ok: true, action: 'guidance', topic,
    result,
    knowledge: {
      components: KB.components, apis: KB.apis, lifecycle: KB.lifecycle,
      wxml: KB.wxml_syntax, wxss: KB.wxss, login: KB.login_auth,
      payment: KB.payment, cloud: KB.cloud_dev, design: KB.design,
      skyline: KB.skyline, pitfalls: KB.pitfalls,
    },
  }
}

async function codeTemplate(params) {
  const t0 = cap.flowchart.beginNode('code_template')
  const { pageType, feature } = params

  const TEMPLATES = {
    list: { name: '列表页', wxml: '<view class="container"><view class="search"><input bindinput="onSearch" placeholder="搜索"/></view><scroll-view scroll-y class="list"><view wx:for="{{list}}" wx:key="id" class="item" bindtap="onTap" data-id="{{item.id}}"><image src="{{item.thumb}}"/><view class="info"><text class="title">{{item.title}}</text><text class="desc">{{item.desc}}</text></view></view></scroll-view></view>', wxss: '.container{height:100vh;display:flex;flex-direction:column;}.search{padding:20rpx;}.search input{background:#f5f5f5;padding:20rpx;border-radius:12rpx;}.list{flex:1;}.item{display:flex;padding:24rpx;border-bottom:1rpx solid #eee;}.item image{width:160rpx;height:160rpx;border-radius:12rpx;}.info{margin-left:20rpx;flex:1;}.title{font-size:28rpx;font-weight:600;}.desc{font-size:24rpx;color:#999;margin-top:8rpx;}' },
    form: { name: '表单页', wxml: '<view class="container"><form bindsubmit="onSubmit"><view class="field"><text class="label">姓名</text><input name="name" placeholder="请输入姓名"/></view><view class="field"><text class="label">手机</text><input name="phone" type="number" placeholder="请输入手机号"/></view><view class="field"><text class="label">类型</text><picker mode="selector" range="{{types}}" bindchange="onTypeChange"><text>{{types[typeIndex]||"请选择"}}</text></picker></view><button form-type="submit" class="submit">提交</button></form></view>', wxss: '.container{padding:30rpx;}.field{margin-bottom:30rpx;}.label{font-size:28rpx;color:#666;margin-bottom:10rpx;display:block;}.field input,.field picker{width:100%;height:80rpx;border:1rpx solid #ddd;border-radius:12rpx;padding:0 20rpx;box-sizing:border-box;}.submit{background:#07c160;color:#fff;border-radius:12rpx;margin-top:60rpx;}' },
    detail: { name: '详情页', wxml: '<view class="container"><swiper indicator-dots autoplay circular><swiper-item wx:for="{{banners}}" wx:key="*this"><image src="{{item}}"/></swiper-item></swiper><view class="info"><text class="title">{{title}}</text><text class="price">¥{{price}}</text><text class="desc">{{desc}}</text></view><button class="buy" bindtap="onBuy">立即购买</button></view>', wxss: 'swiper{height:600rpx;}swiper image{width:100%;height:100%;}.info{padding:30rpx;}.title{font-size:36rpx;font-weight:700;}.price{font-size:48rpx;color:#ff4444;margin:20rpx 0;display:block;}.desc{font-size:26rpx;color:#666;line-height:1.6;}.buy{background:#ff4444;color:#fff;border-radius:12rpx;margin:40rpx 30rpx;}' },
    tabs: { name: 'Tab切换页', wxml: '<view class="container"><view class="tabs"><view wx:for="{{tabs}}" wx:key="*this" class="tab {{activeTab===index?'active':''}}" bindtap="switchTab" data-index="{{index}}">{{item}}</view></view><swiper current="{{activeTab}}" bindchange="onSwiper" class="content"><swiper-item wx:for="{{tabs}}" wx:key="*this"><view class="page"><text>{{item}} 内容区域</text></view></swiper-item></swiper></view>', wxss: '.tabs{display:flex;background:#fff;border-bottom:1rpx solid #eee;}.tab{flex:1;text-align:center;padding:24rpx 0;font-size:28rpx;color:#666;}.tab.active{color:#07c160;border-bottom:4rpx solid #07c160;}.content{height:calc(100vh - 88rpx);}.page{display:flex;justify-content:center;align-items:center;height:100%;}' },
    login: { name: '登录页', wxml: '<view class="container"><image class="logo" src="/images/logo.png"/><text class="title">{{appName}}</text><button class="login-btn" type="primary" bindtap="onLogin" loading="{{loading}}">微信一键登录</button><text class="agree">登录即表示同意《用户协议》</text></view>', wxss: '.container{display:flex;flex-direction:column;align-items:center;padding-top:200rpx;}.logo{width:160rpx;height:160rpx;border-radius:32rpx;}.title{font-size:48rpx;font-weight:700;margin:30rpx 0 80rpx;}.login-btn{width:600rpx;border-radius:48rpx;}.agree{font-size:24rpx;color:#999;margin-top:30rpx;}' },
  }

  if (pageType && TEMPLATES[pageType]) {
    cap.flowchart.endNode('code_template', 'ok', '模板: ' + TEMPLATES[pageType].name, t0)
    return { ok: true, action: 'template', pageType, template: TEMPLATES[pageType], knowledge: { wxml: KB.wxml_syntax, wxss: KB.wxss } }
  }

  if (feature && cap.llm) {
    try {
      const ctx = [
        '你是一个微信小程序前端工程师。生成一个「' + feature + '」功能的完整页面代码。',
        '输出 JSON：{ "name": "功能名", "wxml": "WXML 代码", "wxss": "WXSS 代码（用 rpx）", "js": "JS 代码", "json": "页面配置", "description": "说明" }',
        '代码规范：使用 rpx 单位，flex 布局，bind 事件绑定',
      ].join('\n')
      const resp = await cap.llm.complete(ctx, { temperature: 0.4 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e) } }
    cap.flowchart.endNode('code_template', 'ok', '模板已生成', t0)
    return { ok: true, action: 'template', feature, result, knowledge: { wxml: KB.wxml_syntax, wxss: KB.wxss } }
  }

  cap.flowchart.endNode('code_template', 'ok', '可选模板列表', t0)
  return { ok: true, action: 'template', availableTemplates: Object.keys(TEMPLATES).map(k => TEMPLATES[k].name), templates: TEMPLATES, knowledge: { wxml: KB.wxml_syntax, wxss: KB.wxss } }
}

async function publishGuide(params) {
  const t0 = cap.flowchart.beginNode('publish_guide')
  const { stage, appId } = params

  const ctx = [
    '你是微信小程序发布审核专家。用户阶段:' + (stage || '准备') + ' AppID:' + (appId || ''),
    '',
    '参考：' + KB.publish,
    '',
    '按阶段输出 JSON：',
    '准备阶段 → {"tasks":["备案","认证","配置"],"links":["mp.weixin.qq.com"],"tips":["..."]}',
    '待审核 → {"submitSteps":["上传→提交"],"estimateHours":24,"commonRejections":["..."]}',
    '被驳回 → {"reason":"分析","fix":"方案"}',
    '已发布 → {"promotion":["公众号","视频号","裂变"],"metrics":["CAC","留存","转化"]}',
  ].join('\n')

  let result
  if (cap.llm) {
    try {
      const resp = await cap.llm.complete(ctx, { temperature: 0.5 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e), fallback: true } }
  }

  if (!result || result.fallback) {
    result = {
      tasks: ['完成备案（必需）', '完成认证（推荐）', '配置合法域名', '开启类目', '提交审核'],
      tips: ['备案需 1-20 天，提前准备', '代码包别超 2MB', '授权 scope 需声明用途'],
    }
  }

  cap.flowchart.endNode('publish_guide', 'ok', '发布指南', t0)
  return { ok: true, action: 'publish', stage, result, knowledge: { publish: KB.publish } }
}

async function optimizeGuide(params) {
  const t0 = cap.flowchart.beginNode('optimize_guide')
  const { focus, description } = params

  const ctx = [
    '你是微信小程序性能优化专家。优化重点:' + (focus || '综合') + ' 项目:' + (description || ''),
    '',
    '参考：' + KB.performance + '\n' + KB.skyline,
    '',
    '按重点输出 JSON：',
    '首屏 → {"strategy":"...","code":"...","expected":"<3s"}',
    '分包 → {"split":"...","preload":"...","size":"<2MB"}',
    '渲染 → {"virtualList":true,"nodeReduce":"...","animation":"..."}',
    '启动 → {"plugin":"移除未用","codeSplit":"...","cache":"..."}',
  ].join('\n')

  let result
  if (cap.llm) {
    try {
      const resp = await cap.llm.complete(ctx, { temperature: 0.5 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e), fallback: true } }
  }

  if (!result || result.fallback) {
    result = {
      summary: focus + ' 优化建议',
      items: [
        '控制 setData 大小（≤256KB）和频率（≤20次/秒）',
        'WXML 节点 < 1000，深度 < 30',
        '分包：主包 ≤2MB，总包 ≤30MB',
        '首屏 < 5s，渲染 < 500ms',
      ],
    }
  }

  cap.flowchart.endNode('optimize_guide', 'ok', '优化指南', t0)
  return { ok: true, action: 'optimize', focus, result, knowledge: { performance: KB.performance, skyline: KB.skyline } }
}

async function troubleshoot(params) {
  const t0 = cap.flowchart.beginNode('troubleshoot')
  const { issue, errorCode, description } = params
  const desc = issue || description || ''

  const ctx = [
    '你是微信小程序 debug 专家。',
    '问题:' + desc + ' 错误码:' + (errorCode || '无'),
    '',
    '参考常见坑：' + KB.pitfalls,
    '参考组件：' + KB.components,
    '参考 API：' + KB.apis,
    '参考设计：' + KB.design,
    '',
    '输出 JSON：',
    '{',
    '  "rootCause": "根本原因分析",',
    '  "steps": ["排查步骤1","步骤2..."],',
    '  "solution": {"code":"修复代码示例"},',
    '  "prevention": "预防措施"',
    '}',
  ].join('\n')

  let result
  if (cap.llm) {
    try {
      const resp = await cap.llm.complete(ctx, { temperature: 0.4 })
      try { result = JSON.parse(resp) } catch { result = { guide: resp } }
    } catch (e) { result = { error: String(e), fallback: true } }
  }

  if (!result || result.fallback) {
    result = {
      rootCause: '需要更多信息诊断。请提供错误现象、复现步骤、错误码。',
      steps: ['确认开发工具 vConsole 报错', '检查 app.json 配置', '检查网络请求域名白名单', '检查页面路径是否正确'],
    }
  }

  cap.flowchart.endNode('troubleshoot', 'ok', '诊断完成', t0)
  return { ok: true, action: 'troubleshoot', issue: desc, result, knowledge: { pitfalls: KB.pitfalls, components: KB.components, apis: KB.apis } }
}

async function queryKnowledge(params) {
  const { query, topic } = params
  const topicMap = {
    project: KB.project, components: KB.components, apis: KB.apis,
    lifecycle: KB.lifecycle, wxml: KB.wxml_syntax, wxss: KB.wxss,
    cloud: KB.cloud_dev, login: KB.login_auth, design: KB.design,
    publish: KB.publish, performance: KB.performance, marketing: KB.marketing,
    pitfalls: KB.pitfalls, skyline: KB.skyline, payment: KB.payment,
  }

  if (topic && topicMap[topic]) {
    return { ok: true, action: 'query', topic, data: topicMap[topic] }
  }

  if (query && cap.llm) {
    try {
      const relevant = Object.entries(topicMap).filter(([k]) => query.includes(k) || query.includes('全部')).slice(0, 5)
      const ctx = [
        '你是微信小程序知识库。',
        '问题：' + query,
        '',
        '参考：',
        ...relevant.map(([k, v]) => '---' + k + '---\n' + v),
        '',
        '回答：包含具体代码、配置、最佳实践。中文。',
      ].join('\n')
      const resp = await cap.llm.complete(ctx, { temperature: 0.3 })
      return { ok: true, action: 'query', query, answer: resp }
    } catch (e) {
      return { ok: true, action: 'query', query, availableTopics: Object.keys(topicMap) }
    }
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
