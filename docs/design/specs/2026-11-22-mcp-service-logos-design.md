# MCP 服务品牌 logo

日期：2026-11-22

## 意图

MCP 服务目录 / 列表 / 管理页现在用通用分类图标（10 大类）和统一的 Link 图标，分不出具体是哪个服务。改成每个服务的真实品牌轮廓 + 官方品牌色，一眼认出。

## 目标 / 非目标

- 目标：有品牌的远程服务显示品牌轮廓 + 官方品牌色；无品牌的 stdio 包和自定义服务器沿用现有图标兜底。
- 非目标：不追官方 press-kit；不在运行时联网拉图；不碰连接逻辑和秘密；不新增用户可见文案（无 12 语言翻译负担）。

## 约束

- CLAUDE.md：品牌标归各自品牌，署名进 `licenses/THIRD_PARTY_NOTICES.md`；图标集来源不写进代码 / 文档 / 提交信息（只进 notices）。
- 数据在本机：logo 打包进 assets，不在运行时拉取（断网可用、不泄露浏览记录）。
- 现有 UI 图标槽是 `ImageVector`；项目无图片加载库，不引入。

## 方案（已选：A + 官方品牌色）

- 来源：CC0 品牌图标集，钉死版本。`tools/mcp/build_logos.py` 一次性抓取，生成 `app/src/main/assets/mcp/mcp-logos.json`（`id → {d, color}`），与 `catalog.json` 分离（目录只管连接，logo 是展示层）。
- 渲染：`McpLogos` 用 Compose `PathParser` 把 path 转 `ImageVector` 并缓存；列表行的图标槽仍是 `ImageVector`，改动收敛在调用处。
- 颜色 / 深色模式：官方品牌色原样用。深色品牌（GitHub 黑、Slack 紫、Notion 黑等）在深色模式下会看不见，所以 logo 放在**固定浅色 tile**（两种模式下都浅色）上，品牌色作前景——即各 App「已连接账号」页的通用做法。
- 兜底规则：自定义服务器（无 `presetId`）→ Link；无品牌的 stdio 包 → 分类图标。

## 留给实现者

- 212 个 `presetId → 品牌 slug` 的策展映射表（多数直映，少数要手配，约 22 个 stdio 无 slug）。
- PathParser → ImageVector 的构建细节、缓存、浅色 tile 的 token。
- 测试：`mcp-logos.json` 解析、slug 覆盖率、fallback 路径。
