# MCP 服务品牌 logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用真实品牌轮廓 + 官方品牌色替换 MCP 目录/列表/管理页的通用分类图标。

**Architecture:** 一个 Python 生成脚本从钉死版本的 CC0 品牌图标集抓取每个预设的 `path d` + 品牌色，产出 `assets/mcp/mcp-logos.json`（`presetId → {d, color}`，与 catalog.json 分离）。App 层 `McpLogos` 解析该 JSON 并用 Compose `PathParser` 把 path 懒加载成 `ImageVector`（缓存），三个页面用 `logo(id)` 命中则用品牌 logo（浅色 tile + 自着色），否则退回现有图标。

**Tech Stack:** Python 3（生成脚本）、kotlinx.serialization（解析）、Compose `PathParser`/`ImageVector`（渲染）。

**Spec:** `docs/design/specs/2026-11-22-mcp-service-logos-design.md`

## Global Constraints

- 品牌标归各自品牌；图标集来源与许可署名只写进 `licenses/THIRD_PARTY_NOTICES.md`，不写进代码/注释/提交信息。
- logo 打包进 assets，运行时绝不联网拉取（断网可用、不泄露浏览记录）。
- 不引入图片加载库；图标槽保持 `ImageVector`。
- 不新增用户可见文案（无 12 语言翻译负担）；不碰连接逻辑、秘密、`McpPreset` 模型字段。
- 官方品牌色原样用；logo 放固定浅色 tile（两种模式都浅色），深色品牌在深色模式下仍可读。
- 兜底：`logo(id)` 为 null（无 slug 的 stdio 包、自定义服务器）时退回现有分类图标 / `MikaIcons.Link`。

## Review Focus

1. **近乎白色的品牌色在浅色 tile 上洗白** → 生成器把亮度过高的 hex 压到一个下限，保证对比度（测试 pin）。
2. **presetId 无匹配 slug** → `logo(id)` 返回 null，页面退回分类图标，不崩（测试 pin `logo(unknown) == null`）。
3. **mcp-logos.json 损坏 / 缺字段 / 非法 hex** → 宽松解析跳过坏条目、返回可用子集，不崩（测试 pin）。
4. **自定义服务器（presetId = null）或已存服务器 presetId 无 logo** → 列表/管理页退回 `MikaIcons.Link`（测试 pin 选择逻辑）。
5. **深色模式可读性** → tile 恒为浅色、图标自着色（设计决定，无需运行时分支）。

---

### Task 1: logo 生成脚本与 `mcp-logos.json`

**Files:**
- Create: `tools/mcp/build_logos.py`
- Create: `tools/mcp/tests/test_build_logos.py`
- Create (generated, committed): `app/src/main/assets/mcp/mcp-logos.json`

**Interfaces:**
- Consumes: `app/src/main/assets/mcp/catalog.json`（读 `presets[].id` 与 `presets[].name`）。
- Produces: `mcp-logos.json`，形状 `{"formatVersion":1,"logos":{"<presetId>":{"d":"<svg path d>","color":"<6位hex>"}}}`。

- [ ] **Step 1: 写失败测试** — `tools/mcp/tests/test_build_logos.py`（`python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py'`）

用固定 fixture 断言：
- `slug_for("openai") == "openai"`、`slug_for("google-gemini") == "googlegemini"`（默认去连字符小写）、`slug_for("brave-search") == "brave"`（override 表覆盖）。
- `parse_svg('<svg><path d="M0 0"/></svg>') == "M0 0"`；多 `<path>` 取第一个 `d`。
- `clamp_color("FFFFFF")` 返回更暗的值（亮度压到下限），`clamp_color("181717")` 原样返回。
- 端到端：给定一个只有 `github`/`filesystem` 的假 catalog，`generate(...)` 产出 `{"github": {...}}` 且不含 `filesystem`（无 slug 跳过）。

- [ ] **Step 2: 运行确认失败**

Run: `python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py' -v`
Expected: FAIL（`build_logos` 未定义 / ImportError）。

- [ ] **Step 3: 实现 `tools/mcp/build_logos.py`**

内容（照 `build_catalog.py` 风格）：
- `ICON_SET_VERSION` 常量（钉死一个近版本，实现时用 `unpkg.com/simple-icons@<ver>/` 确认存在并写进脚本）。
- `SLUG_OVERRIDES: dict[str, str]`（`presetId → 图标集 slug`）策展表；默认规则 `presetId.replace("-", "").lowercase()`。
- 下载 `_data/simple-icons.json`（钉死版本）建 `slug → hex`（slug 由 title 经「去非字母数字、小写」得到，含 aliases 的 `old`）；逐 preset 解析 slug，命中则下载 `icons/<slug>.svg` 取 `d`。
- `clamp_color(hex)`：亮度（如 `0.2126 R + 0.7152 G + 0.0722 B`）超阈值则按比例压暗。
- `generate()` 写 `app/src/main/assets/mcp/mcp-logos.json`；`--check` 与已提交文件比对，不一致 exit 1。

- [ ] **Step 4: 运行确认通过 + 生成产物**

Run: `python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py' -v` → PASS；再 `python3 -I tools/mcp/build_logos.py` 生成 `mcp-logos.json`；`python3 -I tools/mcp/build_logos.py --check` → `ok`。抽查 `mcp-logos.json` 里 `github` 的 `color` 为 `181717` 且 `d` 以 `M` 开头。

- [ ] **Step 5: Commit**

```bash
git add tools/mcp/build_logos.py tools/mcp/tests/test_build_logos.py app/src/main/assets/mcp/mcp-logos.json
git commit -m "feat(mcp): 生成服务品牌 logo 资产(mcp-logos.json)"
```

---

### Task 2: `McpLogos` 模型 + 解析 + ImageVector 构建

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpLogos.kt`
- Test: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpLogosTest.kt`

**Interfaces:**
- Consumes: 资产 `mcp/mcp-logos.json`（Task 1 形状）。
- Produces:
  - `data class McpLogoData(val d: String, val color: String)`
  - `class McpLogos(val formatVersion: Int, val logos: Map<String, McpLogoData>)`，伴生 `parse(text: String): McpLogos`（宽松，跳过坏条目）、`load(context: Context): McpLogos`。
  - `fun McpLogos.logo(id: String): ImageVector?`（`PathParser` 建 24×24 `ImageVector`，`fill = SolidColor(解析后的 color)`，内部 `mutableMapOf` 缓存；未知 id / 非法 color 返回 null）。

- [ ] **Step 1: 写失败测试** — `McpLogosTest.kt`

- 解析回环：`McpLogos.parse("""{"formatVersion":1,"logos":{"github":{"d":"M0 0","color":"181717"}}}""").logos["github"] == McpLogoData("M0 0","181717")`。
- 宽松解析：含一条 `{"d":"","color":"181717"}` 与一条 `{"d":"M0 0","color":"zzz"}` 的输入，解析后这两条被跳过、其余保留。
- `logo("github")` 非 null 且 `width == 24.dp && height == 24.dp && viewportWidth == 24f`；`logo("filesystem") == null`（未知 id）。
- 颜色解析：`color("181717") == Color(0xFF181717)`，`color("zzzzzz")` 抛错或按 null 处理（使 `logo` 返回 null）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.wangzan101.mika.mcp.ui.McpLogosTest`
Expected: FAIL（`McpLogos` / `McpLogoData` 未定义）。

- [ ] **Step 3: 实现 `McpLogos.kt`**

`logo(id)` 用 `ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply { addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(color)) }.build()`；`color(hex)` 手工解析 `#RRGGBB`/`RRGGBB` → `Color(0xFF000000L or hex.toLong(16))`，非 6 位 hex 抛 `IllegalArgumentException`。`load(context)` 读 `context.assets.open("mcp/mcp-logos.json")`，异常（文件缺失/损坏）返回空 `McpLogos`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.wangzan101.mika.mcp.ui.McpLogosTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/ui/McpLogos.kt app/src/test/java/com/wangzan101/mika/mcp/ui/McpLogosTest.kt
git commit -m "feat(mcp): McpLogos 解析并懒加载品牌 logo 为 ImageVector"
```

---

### Task 3: `IconTile` / `ListRow` 品牌自着色支持

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/components/Rows.kt`（`IconTile`、`ListRow`）

**Interfaces:**
- Produces:
  - `IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral, brand: Boolean = false)`：`brand=true` 时 `bg = BrandTileBg`（固定浅色 `Color(0xFFF4F4F5)`）、`fg = Color.Unspecified`（渲染矢量自带色）；否则维持 `tone.colors(...)`。
  - `ListRow(icon: ImageVector?, ..., brand: Boolean = false, ...)`：把 `brand` 透传给 `IconTile`。

- [ ] **Step 1: 修改 `IconTile`** 加 `brand` 参数与 `BrandTileBg` 常量（上文语义）。

- [ ] **Step 2: 修改 `ListRow`** 加 `brand: Boolean = false` 参数并透传（`if (icon != null) IconTile(icon, tone, brand)`）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（本任务无独立 JVM 测试，行为在 Task 4/5 的页面与真机里验证）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/components/Rows.kt
git commit -m "feat(ui): IconTile/ListRow 支持品牌自着色浅色 tile"
```

---

### Task 4: 三个页面接入 logo

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt`（第 170 行附近的 preset 行）
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt`（第 111 行 server 行）
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt`（第 94 行状态行）

**Interfaces:**
- Consumes: `McpLogos.load(context)`、`McpLogos.logo(id)`、`ListRow(..., brand = ...)`。
- 规则（三处一致）：`val logo = presetIdOrNull?.let { logos.logo(it) }`；`ListRow(icon = logo ?: fallbackIcon, brand = logo != null, ...)`。
  - 目录：`fallbackIcon = McpCategory.icon(McpCategory.of(p))`，key 为 `p.id`。
  - 列表：`presetIdOrNull = server.presetId`，`fallbackIcon = MikaIcons.Link`。
  - 管理页：`presetIdOrNull = config.presetId`，`fallbackIcon = MikaIcons.Link`。
- 每屏 `val logos = remember(context) { McpLogos.load(context) }`（`context = LocalContext.current`）。

- [ ] **Step 1: 接入目录页**（`McpCatalogScreen`）。

- [ ] **Step 2: 接入列表页**（`McpServerList`）。

- [ ] **Step 3: 接入管理页**（`McpServerManageScreen`）。

- [ ] **Step 4: 编译 + 既有测试**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.wangzan101.mika.settings.McpCatalogAssetsTest`
Expected: BUILD SUCCESSFUL（既有 catalog 测试不回归）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt
git commit -m "feat(mcp): 目录/列表/管理页显示服务品牌 logo"
```

---

### Task 5: 署名 notices + 真机验证

**Files:**
- Modify: `licenses/THIRD_PARTY_NOTICES.md`

- [ ] **Step 1: 追加署名条目**（Source / License: CC0 / Usage：`mcp-logos.json` 里的品牌轮廓 glyph；注明品牌商标归各自品牌所有）。

- [ ] **Step 2: 构建 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 真机验证**

安装到测试机，进 设置 › MCP 服务器 › 服务目录，确认：① GitHub/Slack/Notion 等显示品牌轮廓 + 品牌色（浅色 tile）；② 一个 stdio 预设（如 Filesystem）无 logo，退回 Terminal/分类图标；③ 切深色模式，深色品牌（GitHub）在浅色 tile 上仍可读；④ 服务器列表里已连接的 GitHub（如有）显示品牌 logo、自定义服务器显示 Link。

- [ ] **Step 4: Commit**

```bash
git add licenses/THIRD_PARTY_NOTICES.md
git commit -m "docs: 品牌 logo 第三方署名"
```

---

## Self-Review

- **Spec coverage**：品牌轮廓+品牌色（Task 1/2）、浅色 tile 自着色（Task 3）、三页面接入（Task 4）、署名（Task 5）、兜底规则（Task 4 三处 + Task 2 `logo==null` 测试）、不联网/不引图片库/不碰模型字段（Global Constraints）。
- **Type consistency**：`McpLogoData(d, color)`、`McpLogos.parse/load/logo(id): ImageVector?` 在 Task 2 定义，Task 4 以 `logo(it)` 消费，命名一致。
- **Review Focus**：5 条各 pin 到 Task 1（clamp_color）、Task 2（unknown/损坏/非法 hex）、Task 4（presetId null 退 Link）、Task 3 设计（浅色 tile）。
