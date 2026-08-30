# DSH 桌面客户端项目规范

> **版本**: 1.0  
> **日期**: 2026-08-29  
> **状态**: 强制执行

---

## 1. 项目概述

基于 DeepSeek Harness (DSH) 官方框架的桌面客户端，采用 Cordis 插件架构。

**技术栈：**
- 桌面壳：Tauri 2 (Rust)
- 前端框架：DSH 官方 WebUI (Cordis)
- 插件系统：Cordis ModuleLoader + Slots
- 包管理：pnpm
- 安装包：NSIS

---

## 2. 架构规范

### 2.1 官方架构（必须遵循）

```
┌─────────────────────────────────────────────────────────────┐
│  dsh-desktop (Tauri 壳)                                      │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  WebView → http://127.0.0.1:3080                        │ │
│  │  (DSH 官方 WebUI - Cordis 运行时)                        │ │
│  │  ┌───────────────────────────────────────────────────┐  │ │
│  │  │  Cordis 插件系统                                   │  │ │
│  │  │  ├── dsh-plugin-autoskill  (slots 注册)            │  │ │
│  │  │  ├── dsh-plugin-evolution  (slots 注册)            │  │ │
│  │  │  ├── dsh-plugin-memory     (slots 注册)            │  │ │
│  │  │  ├── dsh-plugin-skill      (slots 注册)            │  │ │
│  │  │  ├── dsh-plugin-storage    (slots 注册)            │  │ │
│  │  │  └── dsh-plugin-watermark  (slots 注册)            │  │ │
│  │  └───────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
│  职责：启动 backend + 窗口管理，无业务逻辑                      │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 dsh-desktop 职责边界（铁律）

**只做三件事：**
1. 启动 DSH backend (`npx @deepseek-ai/dsh web`)
2. 打开 WebView 加载 WebUI
3. 窗口管理 (minimize/maximize/close)

**绝对禁止：**
- ❌ 直接引用 dsh_core 业务模块
- ❌ 注册业务相关的 Tauri 命令
- ❌ 在壳进程中运行业务逻辑
- ❌ 静态 Next.js 导出

---

## 3. 插件开发规范

### 3.1 命名规则

| 类型 | 规则 | 示例 |
|------|------|------|
| 插件包名 | `dsh-plugin-<name>` | `dsh-plugin-watermark` |
| 插件 ID | 同包名 | `dsh-plugin-watermark` |
| 客户端 ID | `<包名>-client` | `dsh-plugin-watermark-client` |
| 场景组件 | `<Name>Scene.tsx` | `WatermarkScene.tsx` |

### 3.2 目录结构

```
dsh-plugin-<name>/
├── package.json          # 必须包含 dsh.bundle + dsh.client
├── cordis.patch.yml      # Host 端配置（仅配置，不含 UI）
├── tsconfig.json         # Host 端编译配置
├── tsconfig.client.json  # 客户端编译配置
├── src/
│   ├── index.ts          # Host 端入口（apply 函数）
│   └── client/
│       ├── index.ts      # ModuleLoader 注册
│       └── <Name>Scene.tsx  # UI 组件
└── scripts/              # 辅助脚本（可选）
```

### 3.3 package.json 标准模板

```json
{
  "name": "dsh-plugin-<name>",
  "version": "0.1.0",
  "type": "module",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": { "types": "./dist/index.d.ts", "default": "./dist/index.js" },
    "./plugin": { "types": "./dist/dsh-plugin.d.ts", "default": "./dist/dsh-plugin.js" },
    "./client": { "types": "./dist/client.d.ts", "default": "./dist/client.js" },
    "./cordis.patch.yml": "./cordis.patch.yml",
    "./package.json": "./package.json"
  },
  "dsh": {
    "bundle": { "patch": "./cordis.patch.yml" },
    "client": { "platform": "web", "inject": ["tools", "clientModules"] }
  }
}
```

### 3.4 cordis.patch.yml 标准模板

```yaml
# DSH Plugin: dsh-plugin-<name>
# 描述
- insert:
    - id: dsh-plugin-<name>
      name: dsh-plugin-<name>
      config:
        # 配置项（不含 UI 注册）
        key: value
```

**注意：** `cordis.patch.yml` 只用于 Host 端配置，**不包含 UI 注册**。

---

## 4. UI 注册规范（铁律）

### 4.1 官方方式：Slots 系统

**Host 端 (src/index.ts)：**

```typescript
export const name = 'dsh-plugin-<name>'
export const inject = ['slots']

export function apply(ctx: any, config: Config): void {
  // 注册 UI 到侧边栏
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots.register({
        name: 'sidebar.settings',
        id: '<name>',
        order: 50,
      }, () => null))
  }
}
```

**客户端 (src/client/index.ts)：**

```typescript
interface CordisContext {
  tools: ClientTools
  clientModules: ClientModules
  slots?: SlotsService
}

interface SlotsService {
  inject(key: string, callback: () => void): void
  register(entry: SlotEntry, component: React.ComponentType<any> | (() => null)): void
}

const PLUGIN_ID = 'dsh-plugin-<name>-client'

function clientFactory(ctx: CordisContext): void {
  if (ctx.slots) {
    ctx.slots.inject('sidebar.settings', () =>
      ctx.slots!.register({
        name: 'sidebar.settings',
        id: '<name>',
        order: 50,
      }, () => null))
  }
}

// 通过 ModuleLoader 注册
window.__ModuleLoader__.load({ id: PLUGIN_ID, factory: clientFactory })
```

### 4.2 侧边栏槽位（官方）

```
sidebar
├─ sidebar.brand.mark
├─ sidebar.brand.name
├─ sidebar.footer.action
├─ sidebar.workspaces
│  └─ sidebar.workspaces.directoryFlow
└─ sidebar.settings          ← 插件入口注册这里
   ├─ settings.trigger
   ├─ settings.header
   ├─ settings.action
   ├─ settings.close
   ├─ settings.onboarding
   └─ settings.section
      ├─ settings.general.item
      ├─ settings.models.provider-card
      ├─ settings.models.footer
      └─ settings.plugins.tab
         └─ settings.plugin.item
```

### 4.3 禁止的 UI 注册方式

| 禁止 | 原因 |
|------|------|
| `cordis.patch.yml` 里写 `ui.sceneBar` | 非官方方式 |
| `cordis.patch.yml` 里写 `ui.scene` | 非官方方式 |
| `uiSlots.registerSceneBarItem()` | 非官方 API |
| `uiSlots.registerScene()` | 非官方 API |
| 静态 Next.js 导出 | 无法加载 Cordis 运行时 |

---

## 5. 客户端注册规范

### 5.1 ModuleLoader 注册

```typescript
interface WindowWithModuleLoader extends Window {
  __ModuleLoader__?: {
    load(entry: ModuleLoaderEntry): void
  }
}

function registerPlugin(): void {
  const win = window as WindowWithModuleLoader
  
  if (win.__ModuleLoader__) {
    win.__ModuleLoader__.load({ id: PLUGIN_ID, factory: clientFactory })
  } else {
    // 降级：等待 ModuleLoader 注入
    const checkInterval = window.setInterval(() => {
      if ((window as WindowWithModuleLoader).__ModuleLoader__) {
        window.clearInterval(checkInterval)
        ;(window as WindowWithModuleLoader).__ModuleLoader__?.load({ id: PLUGIN_ID, factory: clientFactory })
      }
    }, 100)
    window.setTimeout(() => window.clearInterval(checkInterval), 10000)
  }
}
```

---

## 6. 构建与部署

### 6.1 构建命令

```bash
# 构建单个插件
cd dsh-plugin-<name>
npm install
npm run build

# 构建桌面应用
cd dsh-desktop
npm install
npm run tauri build
```

### 6.2 NSIS 打包

- 使用 NSIS 格式（非 MSI）
- 安装时自动安装 task-board 插件
- 配置文件：`dsh-desktop/src-tauri/tauri.conf.json`

---

## 7. 铁律清单

| # | 铁律 | 说明 |
|---|------|------|
| 1 | **UI 入口通过 slots 注册** | 使用 `ctx.slots.inject()` + `ctx.slots.register()` |
| 2 | **dsh-desktop 不含业务逻辑** | 只做 backend + WebView + 窗口管理 |
| 3 | **所有业务逻辑在插件里** | 通过 Cordis 插件系统扩展 |
| 4 | **插件命名 `dsh-plugin-*`** | 官方推荐命名规则 |
| 5 | **package.json 声明 dsh.client** | 客户端入口 |
| 6 | **cordis.patch.yml 不含 UI** | 只用于 Host 端配置 |
| 7 | **禁止静态 Next.js 导出** | 必须使用官方 Cordis 运行时 |
| 8 | **禁止非官方 API** | 如 `uiSlots.registerSceneBarItem` |

---

## 8. 脚手架使用

### 8.1 创建新插件

```bash
node scripts/scaffold-plugin.mjs <插件名> --order 60 --icon ToolOutlined
```

示例：
```bash
node scripts/scaffold-plugin.mjs my-tool --order 60 --icon ToolOutlined --desc "我的工具"
```

自动生成：
- `package.json`（含 dsh.bundle + dsh.client）
- `cordis.patch.yml`（标准配置）
- `tsconfig.client.json`（客户端编译）
- `src/index.ts`（Host 端 apply 入口）
- `src/client/index.ts`（ModuleLoader 注册）
- `src/client/<Name>Scene.tsx`（场景页面）

### 8.2 侧边栏顺序参考

| 插件 | 顺序 | 图标 |
|------|------|------|
| dsh-plugin-autoskill | 20 | RobotOutlined |
| dsh-plugin-evolution | 30 | LineChartOutlined |
| dsh-plugin-memory | 40 | DatabaseOutlined |
| dsh-plugin-storage | 45 | DatabaseOutlined |
| dsh-plugin-skill | 50 | CodeOutlined |
| dsh-plugin-watermark | 60 | DeleteOutlined |

---

## 9. 代码审查清单

提交代码前必须检查：

- [ ] 插件使用 `ctx.slots` 注册 UI
- [ ] `cordis.patch.yml` 不含 `ui.sceneBar` / `ui.scene`
- [ ] `package.json` 包含 `dsh.client` 声明
- [ ] `src/client/index.ts` 使用 ModuleLoader 注册
- [ ] dsh-desktop 没有业务逻辑引用
- [ ] 没有静态 Next.js 导出残留
- [ ] 没有非官方 API 调用

---

## 10. 参考资源

- [DSH 官方文档](https://github.com/deepseek-ai/deepseek-harness)
- [Cordis 插件规范](https://github.com/deepseek-ai/deepseek-harness/tree/master/docs)
- [Slots 系统文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/slots.md)
- [Client Modules 文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/client-modules.md)
