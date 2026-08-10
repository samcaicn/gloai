> **声明**：本项目基于公开的 iLink 协议进行独立开发，与 iLink 协议官方团队无任何关联或授权关系。若相关权利方认为本项目存在侵权，请通过 Issue 联系我们，我们将在确认后第一时间予以下架处理。
>
> **Disclaimer:** This project is independently developed based on the publicly available iLink protocol and is not affiliated with or endorsed by the official iLink team. If any rights holder believes this project infringes upon their rights, please contact us via an Issue and we will take it down promptly upon confirmation.

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="./logo-black.svg">
  <img alt="CEOadmin Hub" src="./logo.svg" width="360" />
</picture>

<br><br>

**企业级微信智能 Bot 管理平台 + 应用市场**<br>
**Enterprise WeChat Bot management & App Marketplace platform (iLink protocol)**

扫码接入企业微信，通过应用市场一键打通业务系统 —— 飞书 / 钉钉 / 企业微信 / Notion 等 20+ 应用，开箱即用<br>
多账号统一管控 · 经营数据可视 · 业务系统互通 · 安全合规 · AI 智能运营 · Passkey 无密码登录

[![License](https://img.shields.io/github/license/ceoadmin/CEOadmin?style=flat-square)](LICENSE)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go&logoColor=white)](https://go.dev)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](https://react.dev)
[![Docker](https://img.shields.io/docker/pulls/ceoadmin/CEOadmin?style=flat-square&logo=docker&logoColor=white)](https://hub.docker.com/r/ceoadmin/CEOadmin)
[![ Stars](https://img.shields.io/github/stars/ceoadmin/CEOadmin?style=flat-square&logo=github)]()
[![Release](https://img.shields.io/github/v/release/ceoadmin/CEOadmin?style=flat-square)]()

[官网 & 文档](https://ceoadmin.com) · [在线体验](https://hub.ceoadmin.com) · [快速开始](#快速开始) · [应用市场](#app-应用市场) · [SDK](#sdk-生态) · [English](#english)

</div>

---

## 快速开始

```bash
# 一键安装（Linux / macOS）
curl -fsSL https://raw.githubusercontent.com/ceoadmin/CEOadmin/main/install.sh | sh

# 启动
oih

# 或者用 Docker（二选一）
docker run -d -p 9800:9800 ceoadmin/CEOadmin:latest              # Docker Hub
# 或者 / or:
docker run -d -p 9800:9800 ghcr.io/ceoadmin/CEOadmin:latest     # GHCR
```

打开 `http://localhost:9800`，注册账号（**第一个注册的自动当管理员**），扫码绑定微信号，完事。

> 默认用 SQLite，不用装数据库，不用配任何东西。想用 PostgreSQL？设个 `DATABASE_URL` 就行。

<a id="windows"></a>
> **Windows 用户**：原生 Windows 不支持，请用以下任一方式：
> - **Docker（推荐）**：`docker run -d -p 9800:9800 ceoadmin/CEOadmin:latest`
> - **WSL2**：在 WSL 的 Ubuntu/Debian 里按上面的 Linux 方式跑

---

## 这是什么？

2026 年 3 月微信推出了 **ClawBot 插件**，底层叫 **iLink（智联）** 协议 —— 第一次官方允许你用程序收发微信消息。

但对大多数企业来说，iLink 只是个原始通道：能收消息、能发回复，却无法直接变成可管控的经营能力。企业还要自己处理 context_token、CDN 加密、24 小时过期、多账号合规等问题。

**CEOadmin Hub 把 iLink 封装成一套面向企业经营管理的能力平台**，并通过 **App 应用市场** 让你低代码扩展：

- 统一管理多个企业微信账号，集中看板监控状态与到期，避免客户资产随员工离职流失
- 应用市场一键装能力 —— 打通飞书 / 钉钉 / 企业微信 / Notion 等业务系统，查客户、记工单、AI 客服，无需开发
- WebSocket / Webhook / AI 三通道并行下发，对接企业自有业务系统
- 到期前主动提醒 + 消息持久化，保障客户沟通不中断
- 消息全链路追踪，运营异常一眼定位

```mermaid
flowchart TD
    subgraph WeChat
        A[微信 ClawBot 插件]
    end

    A -->|iLink 协议| B[CEOadmin SDK]
    subgraph CEOadmin["CEOadmin Hub"]
      C[消息调度]
    end
    B --> C

    C --> D[App: 平台互通<br/>飞书 / Slack / Discord / 钉钉 / 企业微信]
    C --> E[App: 效率工具<br/> / Google / Notion / Linear]
    C --> F[App: 生活工具<br/>天气 / 汇率 / 记账 / 地图 / RSS ...]
    C --> G[App: OpenClaw]
    C --> H[自定义 App]
    C --> I[WebSocket 实时推送]
    C --> J[Webhook 转发]
    C --> K[AI 自动回复]

    D --> L[第三方平台<br/>消息互通 + AI Tools]
    E --> M[项目管理 / 文档协作 / 代码仓库]
    F --> N[日常查询 / 自动化 / 个人助手]
    G --> O[AI Agent Gateway]
    H --> P[你的业务系统]
    I --> Q[实时客户端]
    J --> R[HTTP 回调服务]
    K --> S[LLM]
```

<details>
<summary><b>和 OpenClaw 什么关系？</b></summary>

OpenClaw 是 AI Agent 框架，CEOadmin Hub 是消息管理平台，两个东西。

在 Hub 里，OpenClaw 是一个**内置 App** —— 在应用市场一键启用就行。当然你也可以完全不用 OpenClaw，选别的 App 或直接用 WebSocket/Webhook 对接你自己的服务。

简单说：**OpenClaw 管 AI 逻辑，Hub 管消息收发和 App 分发**，各干各的，想连就连。

</details>

## 为什么用 Hub？

对绝大多数企业来说，直接基于 iLink 自建消息能力，意味着持续的研发投入与合规风险。CEOadmin Hub 把这些都收敛成一套开箱即用的企业能力：

### 企业自建对接 iLink 的隐性成本

| 企业遇到的痛点 | Hub 怎么解决 |
|---|---|
| iLink 没有官方文档，全靠社区逆向 | 完善中文文档 + 7 种语言 SDK |
| context_token 管理复杂，消息经常发不出去 | SDK 自动处理，你只管收消息发回复 |
| 24 小时过期掉线，重要消息丢了 | 到期前提醒 + 消息持久化 |
| 发图片要自己搞 CDN 上传 + AES 加密 | 一行代码发图片视频文件 |
| 只能命令行操作，管不了多个 Bot | Web 控制台，扫码绑定、状态监控、消息追踪 |
| 想加功能得自己写代码 | 应用市场一键安装，不写代码也能扩展 |

### 和其他方案比

 市面上有不少 iLink 相关的方案，但大多是底层 SDK 或 Agent 桥接工具。Hub 是目前唯一带**管理后台 + 应用市场 + 多通道分发**的完整平台。

| | CEOadmin Hub | 其他方案 |
|---|---|---|
| **定位** | 企业级微信消息运营平台 | SDK / Agent 桥接器 |
| **应用市场** | 有，一键装能力，支持第三方上架 | 无 |
| **Web 控制台** | 完整管控台 + 消息追踪 + 操作审计 | 无 / 仅配置面板 |
| **消息分发** | App + WebSocket + Webhook + AI 并行 | 单一通道 |
| **安全合规** | 集中管控 + Passkey 无密码登录 + 权限隔离 | 分散、难管控 |
| **SDK** | 7 种语言 | 1~4 种 |
| **部署** | 一行命令，内置 SQLite 零配置 | 需要外部数据库 |
| **OpenClaw 依赖** | 完全独立（可选适配） | 部分强依赖 |

## 核心特性

**App 应用市场** · 无需开发即可扩展 Bot 能力。20+ 官方 App 覆盖业务系统互通（飞书、钉钉、企业微信、Notion）、效率工具（Google Workspace、Notion、Linear）、经营辅助（天气、汇率、地图、RSS）等场景。通过 PKCE OAuth 安全安装，支持第三方开发者上架。

**多 Bot 管理** · 扫码绑定多个微信号，统一面板看状态，到期前提醒防掉线。

> 说明：微信 24 小时窗口目前不能由 Hub 在后台静默自动续期。Hub 现在支持的是“到期前提醒”，会在窗口快到期时提醒你回一条消息，收到回复后窗口会重新开始计时。

**多通道消息下发**
- **App** — 已安装的 App 自动接收匹配的消息，通过 WebSocket 或 Webhook 投递
- **WebSocket** — 毫秒级实时推送
- **Webhook** — HTTP 回调，对接任意服务
- **AI 自动回复** — 接入 OpenAI 兼容 API，Bot 自动对话

**消息追踪** · 每条消息从收到到投递的完整链路，哪一步耗时多少一目了然，出问题快速定位。

**Passkey 登录** · 指纹、Face ID、硬件密钥直接登录，也支持 LinuxDo OAuth。

**管理后台** · 用户管理、角色权限、OAuth 配置、AI 全局设置、App 管理，一站式掌控。

## App 应用市场

Hub 的核心扩展机制。每个 App 是一个独立服务，通过标准协议与 Hub 交互。

### 能装什么？

**平台互通** — 微信与第三方平台双向消息互通 + AI Tools 自然语言操作

| 应用 | 能干什么 |
|------|---------|
| **Lark（飞书）** 🔥 | 消息互通 + 34 个 AI Tools，覆盖日历、云文档、任务、通讯录、多维表格、邮箱、知识库、视频会议等 11 大业务域 |
| **Slack** | 消息互通 + 23 个 AI Tools，管理频道、消息、用户、文件、提醒、书签 |
| **Discord** | 消息互通 + 19 个 AI Tools，管理频道、成员、Embed、服务器 |
| **DingTalk（钉钉）** | 消息互通 + 20 个 AI Tools，覆盖通讯录、日程、待办、审批、考勤、钉盘 |
| **WeCom（企业微信）** | 消息互通 + 18 个 AI Tools，覆盖通讯录、日程、审批、打卡、微盘、客户联系 |

**效率工具** — 在微信里用自然语言操作主流 SaaS 平台

| 应用 | 能干什么 |
|------|---------|
| **** | 36 个 AI Tools，管理 Issue、PR、Repo、Actions、Release、Gist |
| **Google Workspace** | 18 个 AI Tools，操作 Gmail、Calendar、Drive、Docs、Sheets |
| **Notion** | 15 个 AI Tools，管理页面、数据库、评论、搜索 |
| **Linear** | 13 个 AI Tools，管理 Issue、项目、团队、Cycle |

**生活工具** — 轻量实用，零配置或免费 API 即可运行

| 应用 | 能干什么 |
|------|---------|
| **Amap（高德地图）** | 10 个 AI Tools，POI 搜索、路线规划、天气、地理编码 |
| **Weather（天气）** | 全球城市天气 + 空气质量查询 |
| **Currency（汇率）** | 30+ 货币实时汇率换算 |
| **Expense（记账）** | 微信记账 + 月报 + 分类统计 |
| **Reminder（提醒）** | 一次性 / 重复 / Cron 定时提醒 |
| **Cron（定时任务）** | Cron 调度 + 自动消息推送 |
| **RSS** | RSS 订阅，新文章自动推送到微信 |
| **QRCode（二维码）** | 二维码生成与解码，纯本地运算 |

**开发者 & 基础设施**

| 应用 | 能干什么 |
|------|---------|
| **Command Service** | `/s 600519` 查股价、`/gi 赛博朋克城市` 生成图片、`/a 帮我写邮件` AI 对话，20+ 命令 |
| **OpenClaw** | 让 OpenClaw AI Agent 直接通过微信聊天 |
| **Runner** | 把本地 CLI 命令暴露为微信可调用的 App Tools |
| **Bridge** | 微信消息自动转发到你自己的服务器 |
| **Echo** | 开发者模板，3 分钟上手写自己的 App |

### App 类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **内置 App** | 随 Hub 一起提供，无需额外部署 | Bridge、OpenClaw |
| **市场 App** | 从远程 Registry 安装 | Lark、、Weather 等 20+ |
| **自定义 App** | 你自己开发的 | 你的业务系统 |

### 怎么装？

1. 在 Hub 后台进入「应用市场」
2. 看到想要的 App，点「安装」
3. 通过 PKCE OAuth 完成授权
4. App 开始接收消息，完事

### 怎么开发？

App 通过以下方式与 Hub 交互：

- **接收事件**：WebSocket 连接或 Webhook 回调
- **定义命令**：声明 tools 和 commands，用户通过 `/命令` 或 `@提及` 触发
- **发送回复**：调用 Hub API 发消息

参考 [ceoadmin-app-echo]() 快速上手。

#### Mock Server（本地开发调试）

不用跑真实 Hub + 微信 Bot，用 Mock Server 在本地开发和测试 App：

```bash
# 启动 Mock Server，将事件通过 Webhook 投递到你的 App
go run ./cmd/appmock --webhook-url http://localhost:8080/webhook

# 注入一条模拟消息，触发你的 App
curl -X POST http://localhost:9801/mock/event \
  -d '{"sender":"alice","content":"@test-app hello"}'

# 查看你的 App 发了什么消息
curl http://localhost:9801/mock/messages
```

Mock Server 复用真实的 Bot API handler，行为与生产环境完全一致。完整文档见 [`docs/mock-server.md`](docs/mock-server.md)。

## 架构总览

```mermaid
flowchart TB
    subgraph Client["客户端"]
        direction TB
        WX[微信号<br/>扫码绑定] --> Provider[Provider<br/>iLink]
        UI[Web UI<br/>React] --> API[REST API<br/>Go]
    end

    Provider --> Broker[Message Broker]
    API --> Broker

    Broker --> AppDispatch[App 调度]
    Broker --> WS[WebSocket<br/>实时客户端]
    Broker --> WH[Webhook<br/>回调]
    Broker --> AI[AI Sink<br/>自动回复]

    AppDispatch --> BuiltinApps[内置 App<br/>Bridge / OpenClaw]
    AppDispatch --> MarketApps[市场 App<br/>Echo / Command Service]
    AppDispatch --> CustomApps[自定义 App]

    subgraph Storage["存储"]
        DB[(SQLite / PostgreSQL<br/>数据)]
        MinIO[(MinIO<br/>媒体存储)]
        PK[Passkey<br/>WebAuthn]
    end
```

## 部署指南

### 数据存储

默认 SQLite，零配置。数据文件位置：
- Linux: `~/.local/share/CEOadmin/ceoadmin.db`
- macOS: `~/Library/Application Support/CEOadmin/ceoadmin.db`
- root/service: `/var/lib/CEOadmin/ceoadmin.db`

设置 `DATABASE_URL=postgres://...` 切换到 PostgreSQL。

### 注册为系统服务

```bash
oih install       # 安装 systemd (Linux) / launchd (macOS) 服务
oih uninstall     # 卸载服务
```

### Docker Compose（生产环境）

适合需要 PostgreSQL + MinIO 的场景：

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_USER: ceoadmin
      POSTGRES_PASSWORD: <改为强密码>
      POSTGRES_DB: ceoadmin
    volumes:
      - pgdata:/var/lib/postgresql/data

  hub:
    image: ceoadmin/CEOadmin:latest  # 或 ghcr.io/ceoadmin/CEOadmin:latest
    ports:
      - "9800:9800"
    environment:
      DATABASE_URL: postgres://ceoadmin:<密码>@postgres:5432/ceoadmin?sslmode=disable
      RP_ORIGIN: https://hub.example.com
      RP_ID: hub.example.com
      SECRET: <随机字符串>
    depends_on:
      - postgres

volumes:
  pgdata:
```

前面放个 Nginx / Caddy 做 HTTPS 反代就行。

### 从源码构建

```bash
cd web && pnpm install && pnpm run build && cd ..
go build -o oih .
./oih
```

## CLI 命令

| 命令 | 说明 |
|------|------|
| `oih` | 前台运行 |
| `oih install` | 装成系统服务 |
| `oih uninstall` | 卸载系统服务 |
| `oih version` | 看版本 |

## SDK 生态

7 种语言，挑你熟悉的用：

| 语言 | 安装 |
|------|------|
| **Go** | `go get github.com/openilink/openilink-sdk-go` |
| **Node.js** | `npm install @ceoadmin/ceoadmin-sdk-node` |
| **Python** | `pip install ceoadmin-sdk-python` |
| **PHP** | `composer require ceoadmin/ceoadmin-sdk-php` |
| **Java** | [ceoadmin-sdk-java]()（从源码构建） |
| **C#** | [ceoadmin-sdk-csharp]()（开发中） |
| **Lua** | [ceoadmin-sdk-lua]()（从源码引入） |

### 相关项目

**App**（完整列表见 [应用市场](#能装什么)）

| 项目 | 说明 |
|------|------|
| [ceoadmin-app-lark]() | 飞书 · 34 AI Tools 🔥 |
| [ceoadmin-app-slack]() | Slack · 23 AI Tools |
| [ceoadmin-app-discord]() | Discord · 19 AI Tools |
| [ceoadmin-app-dingtalk]() | 钉钉 · 20 AI Tools |
| [ceoadmin-app-wecom]() | 企业微信 · 18 AI Tools |
| [ceoadmin-app-github]() |  · 36 AI Tools |
| [ceoadmin-app-google]() | Google Workspace · 18 AI Tools |
| [ceoadmin-app-notion]() | Notion · 15 AI Tools |
| [ceoadmin-app-linear]() | Linear · 13 AI Tools |
| [ceoadmin-app-echo]() | App 开发模板 |

> 更多 App：[amap]() · [weather]() · [currency]() · [expense]() · [reminder]() · [cron]() · [rss]() · [qrcode]() · [command-service]() · [runner]()

**其他**

| 项目 | 说明 |
|------|------|
| [openclaw-channels]() | OpenClaw 渠道插件 |
| [ceoadmin-tg]() | 微信消息转发到其他平台 |
| [ceoadmin-webhook-plugins]() | 官方 Webhook 插件仓库 |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LISTEN` | `:9800` | 监听地址 |
| `DATABASE_URL` | SQLite | 不设就用 SQLite，设 `postgres://...` 切 PostgreSQL |
| `RP_ORIGIN` | `http://localhost:9800` | 站点地址，必须和浏览器访问的一样 |
| `RP_ID` | `localhost` | WebAuthn RP ID，填域名 |
| `SECRET` | `change-me-in-production` | 服务端密钥，**生产必须改** |
| `REGISTRY_URL` | — | 远程 App Registry 地址 |
| `REGISTRY_ENABLED` | `true` | 是否启用远程 Registry |
| `GITHUB_CLIENT_ID` | — |  OAuth |
| `GITHUB_CLIENT_SECRET` | — |  OAuth |
| `LINUXDO_CLIENT_ID` | — | LinuxDo OAuth |
| `LINUXDO_CLIENT_SECRET` | — | LinuxDo OAuth |
| `STORAGE_ENDPOINT` | — | MinIO / S3 端点 |
| `STORAGE_ACCESS_KEY` | — | 存储密钥 |
| `STORAGE_SECRET_KEY` | — | 存储密钥 |
| `STORAGE_BUCKET` | — | 存储桶 |
| `STORAGE_PUBLIC_URL` | — | 存储公开 URL |

<details>
<summary><b>配置 OAuth 登录</b></summary>

** OAuth**

1. [ Developer Settings]() → OAuth Apps → New OAuth App
2. Homepage URL: `https://hub.example.com`
3. Callback URL: `https://hub.example.com/api/auth/oauth/github/callback`
4. 拿到 Client ID 和 Secret，填环境变量

**LinuxDo OAuth**

1. [connect.linux.do](https://connect.linux.do) 创建应用
2. 回调地址: `https://hub.example.com/api/auth/oauth/linuxdo/callback`
3. 填环境变量

> 回调地址格式：`{RP_ORIGIN}/api/auth/oauth/{provider}/callback`

</details>

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Go 1.25, SQLite / PostgreSQL 17, gorilla/websocket |
| 前端 | React 19, Vite, TypeScript, Tailwind CSS |
| 认证 | WebAuthn (Passkey), OAuth 2.0 (PKCE), 密码 |
| App 系统 | PKCE OAuth 安装, WebSocket/Webhook 事件投递 |
| 存储 | MinIO / S3（可选） |
| 部署 | 单文件二进制 / Docker ([Docker Hub](https://hub.docker.com/r/ceoadmin/CEOadmin) / [GHCR]()) / Docker Compose |

## 参与贡献

欢迎提 Issue 和 PR！

- App 开发参考 [ceoadmin-app-echo]()
- 插件提交到 [ceoadmin-webhook-plugins]()
- SDK 问题到对应语言仓库

## License

[MIT](LICENSE) — 随便用，不限商用。

---

<div align="center">

**[CEOadmin](https://ceoadmin.com)** · 让微信 Bot 接入更简单

</div>

---

<a name="english"></a>

## English

**CEOadmin Hub** is a self-hosted, open-source WeChat Bot management and message relay platform built on top of the **iLink protocol** — WeChat's first official Bot API, launched in March 2026.

It wraps WeChat's raw messaging into a complete system: bind multiple accounts via QR code, install apps from the marketplace to add features like stock queries and image generation, forward messages through WebSocket / Webhook / AI auto-reply — all managed from a web dashboard.

### Key Highlights

- **App Marketplace (20+)** — Platform integration (Lark, Slack, Discord, DingTalk, WeCom), productivity (, Google Workspace, Notion, Linear), utilities (weather, expense, RSS, maps...), all with AI Tools
- **Multi-bot management** — Bind and manage multiple WeChat accounts, auto session renewal
- **Message tracing** — Full delivery timeline for every message, pinpoint issues instantly
- **Multi-channel delivery** — Apps, WebSocket, Webhook, and AI auto-reply in parallel
- **Passkey (WebAuthn)** — Passwordless login with biometric / hardware key support
- **Built-in SQLite** — Zero-config database with optional PostgreSQL
- **7 language SDKs** — Go, Node.js, Python, PHP, Java, C#, Lua

### Quick Start

```bash
# One-line install
curl -fsSL https://raw.githubusercontent.com/ceoadmin/CEOadmin/main/install.sh | sh

# Start
oih

# Or use Docker (either registry works)
docker run -d -p 9800:9800 ceoadmin/CEOadmin:latest              # Docker Hub
# or:
docker run -d -p 9800:9800 ghcr.io/ceoadmin/CEOadmin:latest     # GHCR
```

Visit `http://localhost:9800` — first user becomes admin. Zero config needed.

**Website:** [ceoadmin.com](https://ceoadmin.com) · **Live Demo:** [hub.ceoadmin.com](https://hub.ceoadmin.com)
