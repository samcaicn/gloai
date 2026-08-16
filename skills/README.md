# Skills 技能集总览

tupautochrome 自动化技能集。每个技能是一个独立目录，通过标准 cap 能力层（`src-tauri/src/skills/capabilities.js`）与 Trae IDE / 系统 / 服务器交互。

## 目录结构

```
skills/
├── README.md          # 本文件 — 总览
├── manifest.json      # 技能注册表（所有技能的 id/version/file/params）
├── _template/         # 标准模板（拷贝即用）
│   ├── SKILL.md       #   技能元数据 frontmatter + 人读说明
│   ├── index.js       #   运行时 handler（三段式导出）
│   ├── flowchart.json #   标准流程图配置
│   ├── USAGE.md       #   使用流程文档
│   ├── DEBUG.md       #   调试流程文档
│   ├── UPGRADE.md     #   升级流程文档
│   ├── README.md      #   模板说明
│   └── assets/        #   静态资源
└── trace-auto/        # 参考实例（基于 _template，Trae IDE 自动化）
    ├── SKILL.md
    ├── index.js
    ├── flowchart.json
    ├── USAGE.md
    ├── DEBUG.md
    └── UPGRADE.md
```

- **`_template/`** 是新技能的起点模板，拷贝此目录即可起步
- **`trace-auto/`** 是基于模板的参考实例，演示了 Trae IDE 自动化的完整实现

## 标准技能文件结构

每个标准技能目录含「三件套 + 三份文档」：

### 三件套（运行时必需）

| 文件 | 用途 |
|------|------|
| `SKILL.md` | 技能元数据（frontmatter）+ 人读说明。frontmatter 含 `id`（反域名）/ `name` / `version`（SemVer）/ `capabilities` / `runtime.caps` / `distribution` / `signing`。被 manifest 与市场索引 |
| `index.js` | 运行时 handler，被 mid 路由调用。三段式导出：`handler`（动作入口）+ `lifecycle`（生命周期钩子）+ `debug`（调试钩子）|
| `flowchart.json` | 标准流程图配置（节点/边/判断），遵循 `$schema: https://schema.tupautochrome.io/flowchart/v1`。前端停止后用它完整重放 |

### 三份文档（开发与运维参考）

| 文档 | 用途 |
|------|------|
| `USAGE.md` | 使用流程：搜索 → 加载 → 执行 → 停止 → 回放。描述前端如何发现技能、用户点 Execute/Record 后的链路、停止后的回放机制 |
| `DEBUG.md` | 调试流程：断点 / 单步 / 变量监视 / trace 记录格式 / 序列化导出 / 前端 traceMap 回放 |
| `UPGRADE.md` | 升级流程：SemVer 规范 / checkUpgrade / upgrade / rollback / distribution.rollout 灰度策略 / 兼容性声明 |

## 如何新建一个技能

5 步：

1. **拷贝模板**：`cp -r skills/_template skills/<your-skill-id>`
2. **改 SKILL.md**：把 frontmatter 的 `id` 改成 `com.tupautochrome.skills.<your-skill-id>`，填 `name` / `software_names` / `runtime.caps` / `distribution` 等字段
3. **改 flowchart.json**：把 `id` / `skillId` / `nodes` / `connections` / `judgments` 替换成你的流程
4. **实现 index.js**：把 `FLOWCHART` 常量镜像 flowchart.json，实现 `execute` / `record` 的节点逻辑（参考 trace-auto/index.js）
5. **写三份文档 + 发布**：把 USAGE.md / DEBUG.md / UPGRADE.md 替换成你的实际场景，发布到服务器市场

发布后在 `manifest.json` 注册条目（含 `id` / `name` / `version` / `file` / `standard: "v1"`）。

## cap 能力清单速查

所有能力定义在 `src-tauri/src/skills/capabilities.js`，通过 `cap.<group>.<method>()` 调用。

### 核心能力

| 能力 | 用途 | 关键方法 |
|------|------|----------|
| `cap.cdp` | Chrome DevTools Protocol 控制 Electron/Web 应用（L0 识别） | `eval` / `click` / `type` / `getTargets` / `screenshot` |
| `cap.uia` | Windows UI Automation 适配器（L1 识别，注入点） | `find` / `click` / `type` / `getText` / `listWindows` |
| `cap.ocr` | OCR 文字识别（L2 识别，注入点） | `readText` / `findText` / `readAll` |
| `cap.vlm` | VLM 视觉理解（L3 识别，注入点） | `ask` / `describeScreen` / `findTarget` |
| `cap.llm` | 大语言模型（多提供商后备链） | `complete` / `setProvider` / `addFallback` |
| `cap.storage` | 持久化（localStorage 或注入实现） | `get` / `set` / `append` / `delete` / `keys` |
| `cap.ui` | 用户交互弹窗 | `prompt` / `respond` / `cancel` |
| `cap.runtime` | 运行时工具 | `sleep` / `now` / `iso` / `log` / `uuid` |

### 标准化能力（v6 新增）

| 能力 | 用途 | 关键方法 |
|------|------|----------|
| `cap.recognize` | 多层识别降级链（CDP>UIA>OCR>VLM 统一调度） | `chain(task, tiers)` / `run(tier, task)` / `register(tier, impl)` |
| `cap.control` | 执行控制信号（暂停/单步/停止/断点） | `check(nodeId)` / `pause` / `resume` / `stepOnce` / `stop` / `reset` / `addBreakpoint` / `removeBreakpoint` / `clearBreakpoints` |
| `cap.flowchart` | 流程图访问层 + 执行 trace 记录/序列化/导出 | `setCurrent(fc)` / `get()` / `pushTrace` / `beginNode` / `endNode` / `serialize` / `exportZip` / `trace` |
| `cap.server` | 服务器侧技能市场 API（HTTP 封装） | `searchSkills` / `getSkillDetail` / `getFlowchart` / `downloadPackage` / `reportRun` / `reportUpgrade` / `getLatestVersion` |
| `cap.skillMarket` | 技能市场客户端（加载/列表/升级/回滚） | `load` / `unload` / `listInstalled` / `isInstalled` / `checkUpgrade` / `upgrade` / `rollback` / `searchBySoftware` |

### 辅助能力

| 能力 | 用途 |
|------|------|
| `cap.os` | 平台适配器（OS-specific，参考 ComputerUse） |
| `cap.app` | 软件配置档案（将软件动作映射为具体 step，参考 CUA-Skill） |
| `mid` | 动作派发器（可扩展的 action → handler 路由 + 事件钩子） |

## 与「服务器需求.md」的对应关系

| 服务器需求 | 客户端 cap | 对应动作 |
|------------|-----------|----------|
| 技能市场搜索 | `cap.server.searchSkills` | `search_software` → `cap.skillMarket.searchBySoftware` |
| 技能详情 | `cap.server.getSkillDetail` | 前端 `getSkillFlowchart` |
| 流程图配置 | `cap.server.getFlowchart` | `get_flowchart` |
| 技能包下载 | `cap.server.downloadPackage` | `upgrade` → `cap.skillMarket.upgrade` |
| 运行 trace 上报 | `cap.server.reportRun` | `cap.flowchart.serialize` + 上报 |
| 升级结果上报 | `cap.server.reportUpgrade` | `upgrade` / `rollback` 内部上报 |
| 最新版本检查 | `cap.server.getLatestVersion` | `check_upgrade` → `cap.skillMarket.checkUpgrade` |
| 灰度下发 | `distribution.rollout` | 服务器按 `percentage` + `targetUsers` 判断 |

## 技能注册表（manifest.json）

`manifest.json` 列出所有技能的元信息。标准技能条目含 `standard: "v1"` 字段标识符合新标准：

```json
{
  "id": "trace-auto",
  "name": "AIMarketing",
  "version": "6.0.0",
  "standard": "v1",
  "file": "trace-auto/index.js",
  "flowchart": "trace-auto/flowchart.json",
  "recognition": ["cdp", "uia", "ocr", "vlm"],
  "params": { ... }
}
```

当前注册的技能：

| id | 名称 | 版本 | standard | 说明 |
|----|------|------|----------|------|
| `trace-auto` | AIMarketing | 6.0.0 | v1 | Trae IDE 自动化（参考实例） |
| `wechat-publisher` | 公众号文章技能 | 3.0.0 | — | 7 种写作框架 + 发布 |
| `xiaohongshu-publisher` | 小红书文案技能 | 1.0.0 | — | 热点监测 + 小红书文案 |
| `kuaiju-viewer` | 快剧 | 1.0.0 | — | 快捷键视频打开 |
| `auto-product-comm` | 自动选品沟通 | 1.0.0 | v1 | CDP 选品沟通自动化（微信小店） |
| `amazon-product-research` | 亚马逊选品调研 | 1.0.0 | — | Amazon 多站点搜索、ASIN 详情、BSR 分析、评论情感分析 |
| `alibaba-1688-sourcing` | 1688货源搜索 | 1.0.0 | — | 1688 批发搜索、供应商分析、跨平台比价、热销榜单 |
| `tiktok-trend-tracker` | TikTok热品追踪 | 1.0.0 | — | TikTok Shop 商品搜索、带货视频分析、达人画像 |
| `cross-border-competitor` | 跨境竞品分析 | 1.0.0 | — | Amazon/eBay/Shopee 竞品发现、定价监控、SWOT 分析 |
| `listing-optimizer` | Listing优化器 | 1.0.0 | — | AI 标题/五点/描述/搜索词优化，支持多站点 |
| `cross-border-expansion` | 跨境市场扩张战略 | 1.0.0 | — | 8维市场评分、5种物流对比、VAT/GST合规路线图 |
| `global-tax-guide` | 全球税务合规指南 | 1.0.0 | — | EU VAT/IOSS、US Sales Tax、到岸成本、产品合规认证 |
| `profit-calculator` | 跨境利润计算器 | 1.0.0 | — | FBA费用、多平台佣金、全链路利润、定价建议 |
| `shopify-operator` | Shopify店铺运营 | 1.0.0 | — | 店铺审计、商品SEO、弃购挽回、多市场扩张 |
| `listing-translator` | Listing多语言翻译 | 1.0.0 | — | 10+语言Listing翻译、SEO关键词本地化 |

## 相关文档

- `_template/SKILL.md` — 标准 frontmatter 字段规范
- `_template/USAGE.md` — 使用流程详解 + 字段语义速查
- `_template/DEBUG.md` — 调试能力详解
- `_template/UPGRADE.md` — 升级流程详解
- `trace-auto/` — 参考实例的完整实现
- `auto-product-comm/` — 自动选品沟通技能（CDP 浏览器自动化）
- `src-tauri/src/skills/capabilities.js` — 能力层源码
- `src/AutomationPage.jsx` — 前端调用约定（`callSkill` 函数）
