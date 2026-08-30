# DSH Skill Platform

**Skill OS for DeepSeek Harness — 让 YAML 技能真正跑起来**

DSH Skill Platform 是一个基于 Tauri 2 的桌面技能管理系统，提供技能注册、执行、进化追踪和权限沙箱能力。

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                    DSH Skill Platform                         │
│                                                              │
│  ┌─ dsh-core ──────────────────────────────────────────────┐ │
│  │  • Skill (manifest, executor, sandbox, loader)          │ │
│  │  • Memory (SQLite 存储 + 衰减)                          │ │
│  │  • Evolution (技能进化追踪)                              │ │
│  │  • AutoSkill (自动优化: 扫描 + 生成 + 评估)             │ │
│  │  • Storage (rusqlite 抽象)                              │ │
│  └─────────────────────────────────────────────────────────┘ │
│                          ↓                                   │
│  ┌─ dsh-desktop (Tauri 2) ────────────────────────────────┐ │
│  │  • Rust 后端 (IPC 命令)                                 │ │
│  │  • 前端 (右侧 WebView 对话 + 技能面板)                  │ │
│  │  • 技能搜索、选择、执行                                  │ │
│  │  • 网页在 WebView 内嵌打开                               │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 项目结构

```
dsh/
├── dsh-core/              # 核心 Rust 库
│   └── src/
│       ├── skill/         # 技能系统
│       ├── memory/        # 记忆管理
│       ├── evolution/     # 进化追踪
│       ├── autoskill/     # AutoSkill
│       └── storage/       # 存储抽象
│
├── dsh-desktop/           # Tauri 2 桌面应用 (主力)
│   ├── src-tauri/         # Rust 后端
│   ├── dist/              # 前端 UI
│   ├── index.html
│   └── package.json
│
├── dsh-cli/               # 命令行工具
├── dsh-plugin-langgraph/   # LangGraph 插件
├── dsh-acp-for-bitfun/    # ACP 项目
├── tests/safeopcapp/      # 参考实现 (更完善的 Tauri 示例)
├── scripts/               # 构建脚本
├── docs/                  # 文档
├── official-harness/      # 官方测试套件
└── pi-mail/               # 子模块 (独立项目)
```

## 快速开始

```bash
# 进入桌面项目
cd D:\code\dsh\dsh-desktop

# 安装前端依赖
pnpm install

# 开发模式 (热重载)
pnpm dev:tauri
# 或: cargo tauri dev

# 构建 NSIS 安装包
cargo tauri build
# 输出: D:\code\dsh\target\release\bundle\nsis\DSH Skill Platform_0.1.0_x64-setup.exe
```

## 技能执行

技能面板在左侧，对话在右侧 WebView 中：

1. **搜索**: 顶部搜索框按名称/描述/标签过滤
2. **选择**: 点击技能卡片选中（紫色高亮）
3. **执行**: 输入指令后按 Enter 或点击发送
4. **网页**: 网页类型技能在右侧 WebView 内嵌打开

## 技术栈

- **后端**: Rust + Tauri 2, rusqlite, serde, tokio
- **前端**: 原生 HTML/CSS/JS (通过 Tauri WebView 渲染)
- **构建**: cargo-tauri (NSIS 安装包)
- **存储**: SQLite (本地文件)

## Git

```bash
# 初始化完成，首次提交: a0d31820
# .gitignore 排除: target/, dist/, node_modules/, archive/
```

## 许可

MIT License — DSH Platform Team
