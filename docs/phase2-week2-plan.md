# Phase 2 Week 2: WebView 托管 + 原生面板 UI

**目标**: dsh web 在 WebView2 内原生运行 + 桌面端技能/记忆/进化管理面板

**时间**: Week 2 (5 个工作日)

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                  DSH Skill Platform                       │
│  ┌──────────┐  ┌─────────────────────────────────────┐  │
│  │ 侧边栏    │  │         主内容区                     │  │
│  │          │  │                                     │  │
│  │ Dashboard│  │  ┌───────────────────────────────┐  │  │
│  │ Skills   │  │  │  WebView (dsh web / 市场)     │  │  │
│  │ Memory   │  │  │  - 远程 URL 或本地 bundled    │  │  │
│  │ Evolution│  │  │  - 工具栏 (前进/后退/刷新)    │  │  │
│  │ Settings │  │  └───────────────────────────────┘  │  │
│  │          │  │  ┌───────────────────────────────┐  │  │
│  │          │  │  │  原生面板 (HTML/CSS/JS)       │  │  │
│  │          │  │  │  - 技能市场 / 记忆 / 进化     │  │  │
│  │          │  │  └───────────────────────────────┘  │  │
│  └──────────┘  └─────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 任务分解

### Day 1: 前端框架与侧边栏

**目标**: 建立主布局，侧边栏导航工作

**实现**:
1. 重构 `dist/index.html` 引入整体布局框架
2. CSS Grid: 侧边栏(200px) + 主内容区(auto)
3. 侧边栏导航按钮: Dashboard / Skills / Memory / Evolution / Settings
4. 导航切换逻辑 (JS 事件 + 内容区切换)
5. 自定义标题栏交互 (最小化/最大化/关闭)

**关键代码模式**:
```javascript
// IPC 调用封装
async function invoke(cmd, args = {}) {
    return await window.__TAURI__.invoke(cmd, args);
}

// 导航切换
document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.onclick = () => switchPanel(btn.dataset.panel);
});
```

**验收标准**: 桌面端启动后看到侧边栏，点击切换主内容区变化

---

### Day 2: WebView 区域 + 浏览器工具栏

**目标**: WebView 区域可加载和导航 URL

**实现**:
1. 主内容区分为上下两部分:
   - 上部: WebView 容器 (带工具栏)
   - 下部: 原生面板容器
2. 浏览器工具栏: 后退/前进/刷新/地址栏/转到
3. WebView 加载 URL (先支持远程 URL，如本地 dev server)
4. WebView 与 Tauri 的通信桥接

**关键技术**:
- Tauri 2 中 WebView 是主窗口默认内容，不能额外嵌入子 WebView
- **方案调整**: 
  - 模式 A: 整个窗口就是 WebView，原生面板用 HTML 覆盖层 (透明/绝对定位)
  - 模式 B: 使用 `tauri::WebviewWindow` 创建子窗口承载 web 内容
- **推荐方案 A**: 单窗口 + HTML 层分区，更简单可靠

**实现调整**:
- 主内容区全屏 WebView 显示 dsh web
- 原生面板以"浮动侧边抽屉"或"底部面板"形式覆盖
- 或者: 左侧原生导航 + 右侧 WebView

**验收标准**: WebView 加载指定 URL 成功，工具栏导航正常

---

### Day 3: 技能市场面板

**目标**: 在原生面板中浏览和管理技能

**实现**:
1. 技能市场面板 UI:
   - 搜索框
   - 技能列表卡片 (名称/描述/评分/安装状态)
   - 分页或无限滚动
2. 已安装技能管理:
   - 列表展示
   - 启用/禁用/卸载
3. 连接到 Tauri IPC:
   - `skill_list` 获取已安装技能
   - `skill_register` 注册新技能
   - `skill_execute` 执行技能

**UI 结构**:
```html
<section id="panel-skills" class="panel">
    <header>
        <input id="skill-search" placeholder="搜索技能..."/>
        <button id="btn-install-skill">+ 安装新技能</button>
    </header>
    <div id="skill-grid" class="card-grid"></div>
</section>
```

**验收标准**: 点击 Skills 面板显示技能列表，可执行已安装技能

---

### Day 4: 记忆管理 + 进化追踪面板

**目标**: 完整的记忆 CRUD 和进化可视化

**实现**:

**记忆面板**:
1. 搜索框 (按内容/importance/workspace 过滤)
2. 记忆列表 (摘要/来源/热度/日期)
3. 点击查看完整内容
4. 删除按钮
5. 手动触发 decay 按钮

**进化面板**:
1. 技能选择下拉框
2. 进化趋势图表 (纯 CSS 或 Canvas)
3. 关键指标: 成功率/平均评分/趋势方向
4. 历史版本列表

**IPC 连接**:
- `memory_search` / `memory_list` / `memory_delete` / `memory_decay`
- `evolution_report`

**验收标准**: 记忆可搜索删除，进化趋势可视化展示

---

### Day 5: Dashboard + Settings + 整体打磨

**目标**: 完善剩余面板，整体体验打磨

**实现**:

**Dashboard**:
1. 统计卡片: 记忆数/技能数/今日执行/进化趋势
2. 最近活动列表
3. 快速操作: 新建记忆/执行技能/同步数据

**Settings**:
1. API Base URL 配置
2. 数据库路径显示
3. 主题切换 (Dark/Light)
4. 关于信息

**打磨**:
1. CSS 主题变量统一
2. 动画过渡效果
3. 错误提示 toast
4. 加载状态指示

**验收标准**: Dashboard 数据实时，Settings 可配置，整体交互流畅

---

## 技术要点

### 前端技术
- **纯 HTML/CSS/JS** — 无构建步骤，无 React/Vue
- CSS 变量驱动主题 (Dark/Light)
- CSS Grid + Flexbox 布局
- `window.__TAURI__.invoke()` 调用后端
- `localStorage` 缓存 UI 状态

### Tauri 交互模式
```javascript
// 命令调用
const result = await invoke('skill_execute', {
    scene: 'default',
    yamlContent: yamlString
});

// 错误处理
try {
    const logs = await invoke('skill_logs', { scene: 'default' });
    renderLogs(logs);
} catch (e) {
    showToast('error', e);
}
```

### 状态管理 (无框架)
```javascript
const state = {
    currentPanel: 'dashboard',
    skills: [],
    memories: [],
    evolution: null
};

function updateState(key, value) {
    state[key] = value;
    render();
}
```

---

## 验收清单

- [ ] 侧边栏导航切换正常
- [ ] Dashboard 展示实时统计数据
- [ ] Skills 面板可搜索/浏览/执行技能
- [ ] Memory 面板可搜索/浏览/删除记忆
- [ ] Evolution 面板展示趋势图表
- [ ] Settings 可修改 API 配置
- [ ] 主题切换 (Dark/Light) 工作正常
- [ ] 所有 IPC 调用有错误处理和加载状态
- [ ] 桌面端在 WebView2 Runtime 下正常运行

---

## 风险与备选

| 风险 | 影响 | 备选方案 |
|------|------|---------|
| WebView2 Runtime 未安装 | 无法启动 | 引导用户安装 Evergreen Bootstrapper |
| Tauri IPC 序列化失败 | 功能异常 | 确保所有 CommandArg 实现 Deserialize |
| 远程 dsh web CORS 问题 | 无法加载 | 先用本地静态 HTML mock |
| CSS 在大屏布局错乱 | 体验差 | 添加响应式断点 |
