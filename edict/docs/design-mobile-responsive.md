# 移动端响应式适配 详细设计

> 任务ID：JJC-20260303-001 · 第一批 · 功能2

## 1. 断点策略

使用 Tailwind CSS 默认断点：

| 断点 | 宽度 | 布局 |
|------|------|------|
| 默认 (mobile) | < 640px | 单列纵向 |
| sm | ≥ 640px | 单列宽松 |
| md | ≥ 768px | 双列 |
| lg | ≥ 1024px | 完整桌面布局（现有） |

## 2. 组件适配清单

### 2.1 EdictBoard（看板主体）

**桌面（≥ lg）**：保持现有多列横向布局

**移动（< md）**：
- 切换为纵向卡片列表
- 各状态列改为可切换的 Tab（待处理 / 进行中 / 已完成 等）
- 任务卡片全宽显示，增大点击区域

```tsx
// 伪代码
<div className="flex flex-col md:flex-row gap-4">
  {/* 移动端 Tab 切换 */}
  <div className="md:hidden">
    <StatusTabs active={activeTab} onChange={setActiveTab} />
  </div>
  {/* 桌面端多列 */}
  <div className="hidden md:flex gap-4">
    {columns.map(col => <KanbanColumn key={col.status} />)}
  </div>
</div>
```

### 2.2 TaskModal（任务详情弹窗）

**移动（< md）**：全屏模式
```tsx
<div className="fixed inset-0 md:inset-auto md:max-w-2xl md:mx-auto md:my-8 md:rounded-lg">
```

### 2.3 导航栏

**移动（< md）**：
- Logo + 汉堡菜单按钮
- 点击展开侧边栏（slide-in overlay）
- 包含所有导航项

```tsx
// 移动端汉堡菜单
<button className="md:hidden" onClick={toggleMenu}>
  <HamburgerIcon />
</button>
<nav className={`fixed inset-y-0 left-0 w-64 transform ${open ? 'translate-x-0' : '-translate-x-full'} md:static md:translate-x-0`}>
  {navItems}
</nav>
```

### 2.4 其他面板

- 官员总览：移动端改为 2 列网格 → 1 列
- 模型切换面板：卡片全宽堆叠
- 奏折时间线：保持纵向，缩小边距

## 3. 触控优化

- 所有可点击元素最小 44x44px
- 添加 `touch-action: manipulation` 消除 300ms 延迟
- 滑动手势：左滑任务卡片显示操作按钮（可选，Phase 2）

## 4. 测试

- Chrome DevTools 设备模拟：iPhone SE / iPhone 14 / iPad
- 真机测试（如有条件）
- 确保无横向溢出、无文字截断
