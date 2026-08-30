# DSH Skill Platform — 架构计划

> 核心原则：**Tauri 2 + 官方 Web UI 嵌入 + 插件化技能系统**
> 
> 单exe安装，零外部依赖（无需Node.js）。官方 Web UI 静态资源编译嵌入 Tauri，运行时通过嵌入式 HTTP 服务 + DSH 后端子进程提供完整能力。

---

## 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                     DSH Skill Platform (Tauri 2)                     │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Rust Backend (src-tauri/src)                                │   │
│  │                                                               │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐  │   │
│  │  │ embedded    │  │ DSH backend  │  │ Tauri IPC commands │  │   │
│  │  │ HTTP server │  │ (child proc) │  │ (30+ commands)     │  │   │
│  │  │ :random     │  │ `dsh web`    │  │ memory/skill/evo   │  │   │
│  │  │ serves dist │  │ :3080        │  │ autoskill/chat     │  │   │
│  │  └──────┬──────┘  └──────┬───────┘  └────────┬───────────┘  │   │
│  │         │                │                    │              │   │
│  │         ▼                ▼                    │              │   │
│  │    WebView导航 ◄──── /api代理 ──────────────────────────────│   │
│  └──────────────────────────────────────────────────────────────┘   │
│                          │                                           │
│  ┌───────────────────────▼───────────────────────────────────────┐  │
│  │  WebView (主窗口)                                              │  │
│  │  • 导航到嵌入式服务器端口 (e.g. http://127.0.0.1:51734)        │  │
│  │  • 渲染官方 Web UI (React + Monaco + Mermaid + XTerm)         │  │
│  │  • /api 请求经服务器代理至 :3080 的 DSH 后端                   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 双进程模型 | 嵌入式运行

**为何不用纯静态HTML?**  
官方 DSH Web UI 是完整 React SPA（Monaco 编辑器、Mermaid 绘图、XTerm 终端、流式对话、插件管理），在 Tauri WebView 中直接运行可获得官方全部交互能力。

**进程关系:**

| 进程 | 角色 | 生命周期 |
|------|------|----------|
| `dsh-desktop.exe` (Tauri) | 主进程，启动嵌入式服务器和后端子进程 | 随App |
| `embedded HTTP server` (Rust) | 提供 dist/ 静态资源，代理 /api → :3080 | 随App |
| `dsh web` (npx) | 官方 DSH 后端 (:3080)，skill执行/ACP/memory | 随App |
| `WebView` | 渲染官方 UI，通过 embedded port 访问 | 随窗 |

**启动序列:**

```
Tauri setup()
  ├─ server.rs:start_embedded_server()      → 监听 127.0.0.1:0 (随机端口)
  │    └─ 绑定 TcpListener，关联 dist/ 目录
  │    └─ 返回 port (如 51734)
  │
  ├─ start_dsh_backend()                     → spawn("npx", ["dsh","web", "--port","3080"])
  │    └─ 后端进程监听 3080
  │
  └─ thread::spawn (2000ms delay)
       └─ WebView.navigate("http://127.0.0.1:51734")
              └─ 官方 UI 加载
              └─ UI 的 /api/* 请求 → 51734 → 代理至 3080 → DSH 响应
```

---

## 前端构建 | 官方 UI 嵌入

**为什么不能依赖官方子模块源码?**  
`official-harness/` 子模块目前为空（仅占位），且其 `pnpm install` 需要缺失的 `patches/node-pty@1.2.0-beta.15.patch`。无法从源码构建。

**当前方案: dsh-web-frontend npm 包**  

```bash
# 从 npm registry 获取预构建的官方 Web UI
npm pack @deepseek-ai/dsh-web-frontend
# 或直接 pnpm add，将产物 copy 至 dist/
```

| 资产类别 | 路径 | 大小 |
|---------|------|------|
| 主 HTML | dist/index.html | ~2 KB |
| JS bundle | dist/assets/index-*.js | ~2.5 MB |
| CSS + vendor | dist/assets/vendor-*.css/js | ~1.8 MB |
| Monaco 内核 | dist/assets/vs/* | ~3 MB (按需) |
| 字体 (KaTeX) | dist/assets/fonts/* | ~0.5 MB |
| 其他 | langs/, mermaid | ~0.2 MB |

**构建产物管理:**

- `dist/` 已被 `.gitignore` 排除 — 构建机器生成
- NSIS 打包时 `frontendDist: "../dist"` 直接引用
- `tauri.conf.json` 中 `beforeDevCommand` / `beforeBuildCommand` 保持空 — 不自动触发 pnpm

---

## 插件系统 | dsh-core 原生实现

> 用户原则: "插件化"。dsh-core 实现 **不依赖官方 Cordis** 的轻量插件框架。

### 插件生命周期

```
  ┌───────────┐    ┌───────────┐    ┌───────────┐    ┌───────────┐
  │ DISCOVERY │───▶│  INSTALL  │───▶│  EXECUTE  │───▶│  EVOLVE   │
  │           │    │           │    │           │    │           │
  │ • embedded│    │ • regis-  │    │ • sandbox │    │ • track   │
  │   (Rust   │    │   try     │    │   .rs     │    │   record  │
  │   inclu-  │    │   .add()  │    │ • per-    │    │ • succee- │
  │   de_str) │    │ • FS      │    │   mission │    │   ss_rate │
  │ • FS scan │    │   persist │    │   check   │    │ • trend   │
  │   ~/.dsh/ │    │   ~/.dsh/ │    │ • worker_ │    │ • roll-   │
  │   skills/ │    │   skills/ │    │   task_log│    │   back    │
  └───────────┘    └───────────┘    └───────────┘    └───────────┘
```

### dsh-core 模块

| 模块 | 文件 | 职责 |
|------|------|------|
| **skill/manifest.rs** | SkillManifest | YAML frontmatter 解析、校验 |
| **skill/executor.rs** | SkillExecutor | 技能主循环，动作分派 |
| **skill/sandbox.rs** | SandboxConfig | 权限校验 (Network/File/MaxIter) |
| **skill/registry.rs** | SkillRegistry | 内存注册表，运行态管理 |
| **skill/eval.rs** | SkillEvalEngine | 执行评分，输出判定 |
| **skill/loader.rs** | SkillLoader | 文件系统发现 (~/.dsh/skills/) |
| **skill/embedded.rs** | EmbeddedSkill | 编译期内置技能 (include_str!) |
| **memory/** | MemoryOps + DAO | CRUD + 衰减 + 统计 |
| **autoskill/** | AutoSkillEngine | 扫描/生成/流水线/状态机 |
| **evolution/** | EvolutionTracker | 滑动窗口 + 趋势 + 报告 |
| **storage/** | Storage + schema | SQLite 抽象 + 迁移 |

### 技能格式 (YAML frontmatter)

```yaml
---
name: code-reviewer
version: 1
permissions:
  - FileRead
  - FileWrite(.workdir)
  - Network(https://api.github.com)
capabilities: [git, diff, comment]
---

你是一个代码审查专家。分析输入的 diff，输出：
1. 安全性问题
2. 性能建议
3. 风格改进

确保每个问题都有文件:行号定位。
```

### IPC 命令 (Tauri invoke)

| 类别 | 命令 | 签名 |
|------|------|------|
| Chat | `chat_send` | `message → ChatResponse` |
| Memory | `memory_insert/list/search/delete/decay/stats` | 完整 CRUD |
| Skill | `skill_list/register/unregister/get_yaml/execute/logs/stop` | 生命周期 |
| FS Skill | `load_filesystem_skills/install/uninstall` | 热加载 |
| AutoSkill | `scan/generate_draft` | 技能进化 |
| Evolution | `report/push` | 追踪窗口数据 |
| Dashboard | `stats_summary/recent_logs` | 聚合统计 |
| Window | `minimize/maximize/close/open_web_window` | Titlebar |
| Settings | `load/save/update_sandbox/reset_db/export/import` | 持久化 |

---

## 数据流

### API 请求路径（WebView 中）

```
[WebView UI]
  │  fetch('/api/v1/sessions')
  ▼
[Embedded Server :51734]
  │  路径匹配 /api/*
  │  代理转发
  ▼
[DSH Backend :3080]
  │  处理请求
  ▼
[SQLite / ACP / SkillRunner]
  │  结果
  ▼
[WebView 渲染]
```

### Tauri 直接 IPC（内存/技能/统计）

```
[WebView UI]
  │  invoke('skill_list')
  ▼
[Tauri IPC]
  │  AppState 读取
  ▼
[SkillRegistry / Storage]
  │  结果
  ▼
[WebView 渲染]
```

---

## 构建与分发

```bash
# 1. 准备官方 Web UI 到 dist/
#    手动: copy 预构建 assets 到 dsh-desktop/dist/

# 2. 构建 NSIS
cd dsh-desktop
cargo tauri build

# 3. 产物
#    target/release/bundle/nsis/DSH Skill Platform_0.1.0_x64-setup.exe (~5-6 MB)
```

**NSIS 包含:**
- Tauri 主程序 (.exe)
- WebView2 Loader (系统通常已有)
- 官方 Web UI (dist/ 全部资源 — 已嵌入 NSIS)

---

## 依赖一览

| 类别 | 依赖 | 用途 |
|------|------|------|
| 桌面框架 | tauri 2 | IPC + WebView + 窗口 |
| 网络 | reqwest | chat请求 + 代理转发 |
| 序列化 | serde/json/yaml | 技能 manifest, IPC |
| 存储 | rusqlite (bundled) | 内存/日志/设置/注册表 |
| 异步 | tokio | 后端运行时 |
| 工具 | chrono/uuid/sha2/log | 各模块 |

**零运行时外部依赖** — 用户安装exe后直接运行。

---

## 路线状态

### Phase 1 ✓ 已完成
- [x] dsh-core 核心库 (memory/skill/evolution/autoskill/storage)
- [x] dsh-desktop Tauri 壳 (lib.rs + server.rs + tauri.conf.json)
- [x] 30+ Tauri IPC 命令
- [x] 嵌入式 HTTP 服务器 (:auto 端口 + /api 代理)
- [x] DSH 后端子进程启动 (:3080)
- [x] WebView 导航至嵌入式服务器
- [x] NSIS 安装包 (~4-6 MB)
- [x] lib.rs 添加 papi(DSH) 后端管理/导入导出/sandbox

### Phase 2 ⟳ 进行中
- [ ] 官方 Web UI 资产嵌入 (dist/)
- [ ] DSH 后端 (npx dsh web) 实际功能联调
- [ ] WebView → 嵌入式服务器 → DSH 后端 端到端联调
- [ ] 权限沙箱 UI 面板
- [ ] 设置面板联接 load/save_settings
- [ ] 错误处理 + 加载状态 + toast 提示

### Phase 3 ○ 计划中
- [ ] npm registry 获取 dsh-web-frontend 预构建产物
- [ ] 自动化: GitHub Actions 构建 NSIS
- [ ] 自动更新 (tauri updater)
- [ ] 技能市场面板 (浏览/安装/卸载社区技能)
- [ ] 多工作区切换
- [ ] 国际化 (中/英)

### Phase 4 ○ 远期
- [ ] Cordis 内核集成 (真正 "一切皆插件")
- [ ] ACP 协议 (Agent Control Protocol) 接入
- [ ] WASM 技能沙箱
- [ ] 远程执行 (SSH Container)

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Web UI 资产缺失 | WebView白屏 | 纯静态降级 UI (dist/index.html 保底) |
| WebView2 Runtime 未装 | 无法启动 | NSIS 内置 Evergreen Bootstrapper 检测 |
| npx 启动失败 | 后端缺失 | IPC 直连 Rust 模式 (bypass dsh web) |
| CSP 过严 | 功能异常 | 已配置 `unsafe-inline` + `aiapi.tuptup.top` |
| 代理延迟 | 体验差 | 嵌入式服务器维护 keep-alive |

---

## 文件索引

```
dsh/
├── docs/
│   └── PLAN.md ← 本文档（项目主计划）
│
├── dsh-core/               # 核心库 (插件/内存/进化/存储)
│   └── src/
│       ├── skill/          # 系统: manifest/executor/sandbox/registry/eval/loader/embedded
│       ├── memory/         # 衰减记忆系统
│       ├── evolution/      # 技能进化追踪
│       ├── autoskill/      # 自动优化引擎
│       └── storage/        # SQLite 抽象层
│
├── dsh-desktop/
│   ├── dist/               # 官方 Web UI 静态资源 (gitignored)
│   │   ├── index.html
│   │   ├── assets/         # JS/CSS/Fonts/Langs
│   │   └── @tauri-apps/    # Tauri API 模块 (注入用)
│   │
│   └── src-tauri/
│       ├── tauri.conf.json # Tauri 配置 (CSP + frontendDist + NSIS)
│       ├── src/
│       │   ├── lib.rs      # 主入口 + 30+ IPC 命令 + 进程管理
│       │   ├── server.rs   # 嵌入式 HTTP 服务器 (静态服务 + API 代理)
│       │   └── core/       # 预留模块扩展点
│       └── Cargo.toml
│
├── dsh-cli/                # CLI 工具 (debug/admin)
├── tests/                  # 参考实现 + 测试
├── scripts/                # 构建脚本
└── target/release/bundle/nsis/ → DSH Skill Platform_0.1.0_x64-setup.exe
```
