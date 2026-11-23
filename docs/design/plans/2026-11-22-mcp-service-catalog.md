# MCP 服务目录与引导式接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户从完整内置 MCP 目录选择服务后,只授权或填写必要字段即可连接,同时保留完整自定义接入。

**Architecture:** 以版本化、无秘密的预设数据描述连接和动态表单;解析、模板解析和校验放在纯 Kotlin core,凭据与连接编排留在 App 的 `McpServerStore`。预设和自定义配置最终共用现有 MCP client、Agent 工具装配与任务资源生命周期;stdio 增加显式环境和经确认的包安装。

**Tech Stack:** Kotlin、kotlinx.serialization、OkHttp、Compose、现有 Linux 沙箱、JUnit/MockWebServer、本地 HTML 原型。

**Spec:** `docs/design/specs/2026-11-22-mcp-service-catalog-design.md`

## Global Constraints

- 首版直接提供完整目录,目标 200–300 条可用预设;不按服务批次限制上线,不为单个服务编写专用 Kotlin 分支。
- 远程预设只使用服务方官方端点;stdio 只收官方或来源可核验且经过审查的包;社区托管代理只走用户自定义。
- 所有秘密加密保存;秘密不得进入目录、普通配置、URL、argv 或日志。
- stdio 包下载前展示包名、来源和版本,用户明确确认后统一安装和缓存;不静默下载。
- 支持多个秘密和多个普通必填字段,UI 只显示当前预设声明的项目。
- 工具目录和 schema 只从真实 MCP 连接发现,不写入预设。
- 预设和自定义服务复用现有任务连接预算、失败隔离和 `ResourceScope` 清理。
- 新 UI 先完成本地 HTML 原型并获得用户确认,再修改 App UI。
- 所有新增用户可见字符串覆盖项目现有全部 UI 语言;服务专有名称不必翻译。
- 不修改 inbound `McpRpc`、`McpTaskTools` 或电脑连接权限策略;非发版不改版本号。

## Review Focus

- 恶意或损坏预设试图把秘密写入 URL/argv、覆盖保留 header 或发往非官方地址:解析和解析器必须拒绝,见 Task 2 的 `rejectsSecretExfiltrationAndUnsupportedTargets`。
- 多秘密配置在编辑、备份、恢复和删除后不得串号或残留:见 Task 3 的 `fieldSecretsRoundTripMigrateBackupAndDelete`。
- 用户取消安装、沙箱断网、包版本变化或安装中断时不得执行包:见 Task 6 的 `installRequiresMatchingConsentAndNeverRunsAfterCancellation`。
- App 升级后的预设不得静默改变已有服务的秘密目的地:见 Task 4 的 `existingPresetConfigKeepsConnectionSnapshotAfterCatalogUpdate`。
- 单条坏目录、单个服务超时或握手失败不得影响其他条目和任务:见 Task 2、Task 4 与 Task 10 的隔离/回归测试。

---

### Task 1: 本地 UI 原型与确认门

**Files:**
- Create: `docs/design/prototypes/2026-11-22-mcp-service-catalog.html`

**Interfaces:**
- Produces: 用户确认的 MCP 首页、目录、详情/连接、成功、失败、管理和自定义页面布局;Task 8–9 只实现已确认原型。

- [ ] **Step 1: 制作单文件、可点击的本地 HTML 原型**

覆盖手机窄屏和桌面宽屏;使用现有 App 的浅色/深色语义,包含:空/非空首页、可搜索和分类的目录、OAuth、多个秘密与普通字段、无认证、连接中/取消、成功工具数、可恢复失败、stdio 包确认、管理和高级自定义。所有数据使用明确的虚构示例,不请求网络。

- [ ] **Step 2: 本地检查原型**

Run: 在浏览器打开上述 HTML,逐一点击所有流程并检查窄屏无横向滚动、键盘焦点、深浅色和长文案。
Expected: 所有状态可达,预设流程不出现 URL/transport,自定义入口始终存在。

- [ ] **Step 3: 请用户确认原型**

展示原型并明确等待确认。未获确认前不得执行 Task 8–9 的 App UI 修改;可以先审阅后续纯逻辑代码,但本计划顺序执行时在此停止。

- [ ] **Step 4: Commit**

```bash
git add docs/design/specs/2026-11-22-mcp-service-catalog-design.md docs/design/plans/2026-11-22-mcp-service-catalog.md docs/design/prototypes/2026-11-22-mcp-service-catalog.html
git commit -m "docs: design and prototype guided MCP service connections"
```

### Task 2: 版本化预设模型、目录解析与安全模板解析

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/McpPreset.kt`
- Create: `core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/McpPresetTest.kt`

**Interfaces:**
- Produces:
  - `data class McpCatalog(val formatVersion: Int, val presets: List<McpPreset>)`; `McpCatalog.Empty`。
  - `data class McpPreset(val id: String, val name: String, val summary: McpCopy, val category: String, val transport: String, val auth: String, val endpoint: String, val command: List<String>, val fields: List<McpPresetField>, val packageSpec: McpPackageSpec?, val officialSource: String)`。
  - `data class McpPresetField(val id: String, val label: McpCopy, val secret: Boolean, val required: Boolean, val help: McpCopy?)`。
  - `data class McpCopy(val values: Map<String, String>) { fun resolve(languageTag: String): String }`。
  - `data class McpPackageSpec(val ecosystem: String, val name: String, val version: String, val source: String)`。
  - `data class ResolvedMcpPreset(val url: String, val argv: List<String>, val headers: Map<String, String>, val environment: Map<String, String>, val auth: String)`。
  - `object McpCatalogParser { fun parse(text: String): McpCatalog }`。
  - `class McpPresetResolver { fun resolve(preset: McpPreset, values: Map<String, String>): ResolvedMcpPreset }`。

- [ ] **Step 1: Write failing parser/resolver tests**

Test `parsesLocalizedCatalogAndResolvesOnlyDeclaredFields`: 两个预设、多语言文案、多个秘密/普通字段、固定 argv 与模板值得到确定输出。
Test `rejectsSecretExfiltrationAndUnsupportedTargets`: 重复 ID/字段 ID、秘密进入 URL 或 argv、未知 transport/auth/target、空官方来源、未锁定包版本均失败。
Test `rejectsMalformedCatalogWithoutPartialSuccess`: 目录级格式错误整体失败;后续资产加载的单条隔离由 Task 7 覆盖。
Test `copyResolvesExactLanguageThenBaseThenDefault`: 只测语言回退;Task 7 要求正式资产无缺失翻译。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.McpPresetTest'`
Expected: FAIL, 类型不存在。

- [ ] **Step 3: Implement the catalog model, parser and resolver**

使用 `kotlinx.serialization`;预设 JSON 只保存声明式模板。Resolver 对普通字段做目标感知校验/编码,对秘密只允许 Bearer、秘密 header 和 env;禁止秘密进入 URL/argv。OAuth 与无认证不得要求秘密字段。

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.McpPresetTest'`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/McpPreset.kt core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/McpPresetTest.kt
git commit -m "feat(mcp): add validated service preset templates"
```

### Task 3: 多字段配置、加密秘密与旧配置兼容

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/settings/McpFieldSecrets.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `McpPreset` / `McpPresetField`。
- Produces:
  - `McpServerConfig` 新增 `presetId: String? = null`、`presetSnapshot: McpPreset? = null`、`fieldValues: Map<String, String> = emptyMap()`;快照不含秘密,后者只含非秘密值。
  - `class McpFieldSecrets { fun get(configId: String, fieldId: String): String?; fun set(configId: String, fieldId: String, value: String?); fun all(configId: String): Map<String, String>; fun clear(configId: String) }`,复用 `mcp-server-secrets.bin`。
  - Store 保留现有 `token(id)` / `setToken` / OAuth API,并增加 `secret(configId, fieldId)`、`setSecret(...)`、`secrets(configId)`。

- [ ] **Step 1: Write failing storage and migration tests**

Test `fieldSecretsRoundTripMigrateBackupAndDelete`: 同一服务两个秘密、两个服务互不串号;旧 Bearer 与 OAuth 键可读;删除只清目标服务;备份恢复后秘密可用且普通 JSON 无秘密。
Test `legacyCustomConfigLoadsWithoutPresetFields`: 旧 JSON 的 transport/token/OAuth/allowlist 行为完全不变。
Test `presetConfigRejectsSecretInPlainFieldValues`: 预设字段声明为秘密时拒绝写入 `fieldValues`。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpServerStoreTest' --tests 'com.wangzan101.mika.backup.BackupEngineTest'`
Expected: FAIL, 新字段/方法不存在。

- [ ] **Step 3: Implement scoped field secrets and compatible config**

在现有加密 JSON 中使用带命名空间的键,不迁移或破坏旧 token/OAuth 键;所有写操作沿用原子替换。预设字段存在性由 catalog 校验,秘密明文只经 `McpFieldSecrets`。

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpServerStoreTest' --tests 'com.wangzan101.mika.backup.BackupEngineTest'`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/settings/McpFieldSecrets.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt
git commit -m "feat(mcp): store multiple preset credentials securely"
```

### Task 4: 预设到 HTTP/OAuth 连接的统一编排

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt`
- Create: `app/src/test/java/com/wangzan101/mika/settings/McpPresetConnectionTest.kt`

**Interfaces:**
- Consumes: Task 2 的 catalog/resolver;Task 3 的配置和秘密 API。
- Produces:
  - `McpServerStore.catalog: McpCatalog` 构造参数,默认 `McpCatalog.Empty`。
  - `fun presetConfig(presetId: String, values: Map<String, String>): Result<McpServerConfig>`。
  - `suspend fun connectPreset(presetId: String, values: Map<String, String>, secrets: Map<String, String>): Result<McpTestResult>`;保存配置/秘密后走现有 `test`,失败保留停用配置。
  - 现有 `connectionsFor`、`open`、`authorize` 对预设和自定义产出相同 endpoint。

- [ ] **Step 1: Write failing HTTP/OAuth tests**

Test `presetHttpUsesResolvedUrlHeadersAndBearer`: MockWebServer 验证 URL、多个秘密 header、普通 header 与现有 initialize/tools/list。
Test `presetOAuthReusesDiscoveryPkceRefreshAndEncryptedStorage`: 使用现有 OAuth 测试替身,不新增服务专用分支。
Test `failedPresetTestLeavesDisabledEditableConfig`: 连接失败不进入健康成功状态,不产生任务连接。
Test `existingPresetConfigKeepsConnectionSnapshotAfterCatalogUpdate`: 已保存 URL、auth 目的地和 argv 快照不随目录对象替换;新建配置才使用新模板。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpPresetConnectionTest'`
Expected: FAIL, 预设入口未接线。

- [ ] **Step 3: Implement preset resolution in the existing store**

`McpServerStore` 接收调用方注入的 catalog;测试直接注入临时目录,App 资产加载在 Task 7 接线。自定义配置继续走原字段;预设配置使用保存的无秘密快照和当前秘密。所有 HTTP 安全校验、禁止重定向、OAuth 端点检查和任务生命周期复用现有实现。

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpPresetConnectionTest' --tests 'com.wangzan101.mika.settings.McpServerStoreTest'`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt app/src/test/java/com/wangzan101/mika/settings/McpPresetConnectionTest.kt
git commit -m "feat(mcp): connect presets through existing client paths"
```

### Task 5: stdio 的显式秘密环境

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClient.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPort.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/cap/sandbox/AndroidSandbox.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/cap/sandbox/ProcRunner.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClientTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/cap/sandbox/SandboxUsageConcurrencyTest.kt`

**Interfaces:**
- Produces: `McpStdioLauncher.launch(workspace: String, argv: List<String>, environment: Map<String, String>)`;`SandboxPort.openProcess(..., environment: Map<String, String> = emptyMap())`。旧调用方使用默认空环境。

- [ ] **Step 1: Write failing environment tests**

Test `stdioPassesOnlyResolvedEnvironment`: 两个秘密和一个普通 env 到达 launcher,argv 不含秘密。
Test `sandboxProcessEnvironmentUsesExplicitAllowlist`: 只追加解析出的环境,不继承模型服务商、App 或宿主环境;保留 PATH/HOME/TZ 等基础项。
Test `legacyStdioConfigStillLaunchesWithNoExtraEnvironment`: 旧自定义配置行为不变。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.StdioMcpClientTest'`
Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.cap.sandbox.SandboxUsageConcurrencyTest'`
Expected: FAIL, 环境参数不存在。

- [ ] **Step 3: Thread explicit environment through launcher and sandbox**

保持 argv 不经 shell、进程树租约和关闭语义;环境 key 只允许 `[A-Za-z_][A-Za-z0-9_]*`,值不写日志。

- [ ] **Step 4: Run tests to verify they pass**

Run: 同 Step 2。
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClient.kt core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPort.kt app/src/main/java/com/wangzan101/mika/cap/sandbox/AndroidSandbox.kt app/src/main/java/com/wangzan101/mika/cap/sandbox/ProcRunner.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClientTest.kt app/src/test/java/com/wangzan101/mika/cap/sandbox/SandboxUsageConcurrencyTest.kt
git commit -m "feat(mcp): pass explicit secrets to sandbox stdio"
```

### Task 6: 经确认的 stdio 包安装与工作区缓存

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/McpPackageManager.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/McpPackageConsent.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPackages.kt`
- Modify: `tools/sandbox/build-runtime.py`
- Modify: `app/src/main/assets/runtime/rootfs.tar.xz` 与 `app/src/main/assets/runtime/packages.json`(由现有构建脚本生成,不手改二进制)
- Create: `app/src/test/java/com/wangzan101/mika/mcp/McpPackageManagerTest.kt`

**Interfaces:**
- Consumes: `McpPackageSpec`;Task 5 的 stdio 环境。
- Produces:
  - `interface McpPackageProvisioner { suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String> }`,返回安装后 argv 前缀,不执行 MCP 服务本身。
  - `class McpPackageConsent { fun accepted(spec: McpPackageSpec): Boolean; fun accept(spec: McpPackageSpec); fun clear() }`,同意绑定包名+精确版本+来源。
  - Store 在预设含包且未缓存时要求明确确认;任务中无新确认不得下载新版本。

- [ ] **Step 1: Write failing provisioning tests**

Test `installRequiresMatchingConsentAndNeverRunsAfterCancellation`: 未确认、取消、来源或版本变化均不安装/不启动;确认精确版本后可安装。
Test `installIsPerWorkspaceCachedAndFailureIsRetryable`: 同一工作区同事务只安装一次;中断不留下成功标记;另一工作区使用已有同意但独立缓存。
Test `networkOffAndUnsupportedPackageFailWithGuidance`: 不联网或包需要原生构建/安装脚本时返回明确错误,不静默绕过。
Test `nodeAndPythonPackagesResolvePinnedArgvWithoutSecrets`: 包名@精确版本生成确定 argv,秘密只经 Task 5 环境。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.McpPackageManagerTest'`
Expected: FAIL, provisioner 不存在。

- [ ] **Step 3: Implement pinned package provisioning**

复用 `SandboxPort.run` 和树锁;Node 使用精确版本、`--ignore-scripts --no-audit --no-fund`,Python 使用精确版本、`--only-binary=:all:`。安装到工作区受控缓存,成功标记原子写入;不支持必须执行安装脚本或原生构建的包。为 Node 包管理器补齐现有 runtime 构建清单并用项目既有流程重建资产,记录验证输出。

- [ ] **Step 4: Run tests and runtime asset checks**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.McpPackageManagerTest' --tests 'com.wangzan101.mika.cap.sandbox.BundledRuntimeTest'`
Expected: PASS;生成资产的版本和包清单一致。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/McpPackageManager.kt app/src/main/java/com/wangzan101/mika/mcp/McpPackageConsent.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPackages.kt tools/sandbox/build-runtime.py app/src/main/assets/runtime/rootfs.tar.xz app/src/main/assets/runtime/packages.json app/src/test/java/com/wangzan101/mika/mcp/McpPackageManagerTest.kt
git commit -m "feat(mcp): provision confirmed stdio packages in sandbox"
```

### Task 7: 完整内置目录、所有语言与批量核验工具

**Files:**
- Create: `app/src/main/assets/mcp/catalog.json`
- Create: `app/src/main/java/com/wangzan101/mika/settings/McpCatalogAssets.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Create: `app/src/test/java/com/wangzan101/mika/settings/McpCatalogAssetsTest.kt`
- Create: `tools/mcp/check_catalog.py`
- Create: `tools/mcp/tests/test_check_catalog.py`

**Interfaces:**
- Consumes: Task 2 的 parser/model;Task 4 的 `McpServerStore.catalog`。
- Produces: `McpCatalogAssets.load(context: Context): McpCatalog`;单条损坏项记录并跳过,不阻断其他条目。目录核验工具输出结构化 JSON 报告,不写回目录或伪造验证状态。

- [ ] **Step 1: Write failing catalog tests**

Test `bundledCatalogHasAtLeast200UniqueUsablePresets`: 正式资产至少 200 条,不统计损坏项。
Test `everyPresetHasAllLocaleCopyAndValidOfficialSource`: 所有说明/字段标签覆盖现有 UI 语言;远程条目有官方来源证据;stdio 包有来源和精确版本。
Test `catalogLoaderSkipsOnlyMalformedEntries`: 坏条目不影响好条目。
Test `checkerSeparatesVerifiedUnreachableAndUnsupported`: 无秘密 initialize/tools/list 的结果分类真实,网络失败绝不写成已验证。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpCatalogAssetsTest' && python3 -I -m unittest discover -s tools/mcp/tests -p 'test_check_catalog.py' -v`
Expected: FAIL, 正式目录和工具不存在。

- [ ] **Step 3: Populate and validate the complete catalog**

批量整理 200–300 条常见服务;远程端点只收官方,stdio 包核验发布者/来源和精确版本;文案全部自行编写并覆盖所有 UI 语言。使用通用类别和通用图标,不复制外部描述。`McpCatalogAssets` 加载资产,`MikaApp` 将目录注入 `McpServerStore`。核验工具只发无秘密协议请求,结果单独输出报告;未实际核验的条目标为未验证,不谎报。

- [ ] **Step 4: Run tests and the offline/online checks**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpCatalogAssetsTest' && python3 -I -m unittest discover -s tools/mcp/tests -p 'test_check_catalog.py' -v`
Expected: PASS。
Run: `python3 -I tools/mcp/check_catalog.py app/src/main/assets/mcp/catalog.json --output <临时报告路径>`。
Expected: 报告逐项区分 verified/unreachable/unsupported;失败项有真实原因,不阻塞其他项。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/mcp/catalog.json app/src/main/java/com/wangzan101/mika/settings/McpCatalogAssets.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/test/java/com/wangzan101/mika/settings/McpCatalogAssetsTest.kt tools/mcp/check_catalog.py tools/mcp/tests/test_check_catalog.py
git commit -m "feat(mcp): bundle the complete guided service catalog"
```

### Task 8: 目录浏览与引导式连接 UI

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectorState.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt`
- Modify: `app/src/main/res/values*/strings_settings.xml` 与必要的 `strings_mcp.xml`
- Create: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt`
- Modify: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: Task 1 已确认原型;Task 4/6 的连接与确认 API;Task 7 的目录。
- Produces: `McpConnectorState` 状态流和动作:`openPreset(id)`、`updateField(id,value)`、`authorize(openBrowser)`、`confirmPackage()`、`connect()`、`cancel()`、`retry()`。UI 不自行拼装 URL/header/argv。

- [ ] **Step 1: Write failing state and UI tests**

State tests 覆盖:选择预设只加载声明字段、多秘密输入、OAuth 回调后自动测试、连接成功才启用、失败保留输入、取消不下载、stdio 包确认版本不匹配需重新确认。
UI tests 覆盖:空状态进入目录、搜索/分类、预设页无 URL/transport、自定义入口可见、成功工具数、失败重试、返回不丢未提交的非秘密输入。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:compileDebugAndroidTestKotlin`
Expected: FAIL, 状态机和页面不存在。

- [ ] **Step 3: Implement the approved prototype with existing Compose components**

首页保留已添加列表;目录支持搜索和类别;详情/连接/成功/失败按原型。所有异步动作可取消,秘密不进入 `rememberSaveable`、截图状态或日志;完整新增/替换字符串同步所有语言。

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:assembleDebugAndroidTest -PdevInstall`
Expected: PASS/构建成功;新增字符串完整性测试通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt app/src/main/res app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt
git commit -m "feat(mcp): add guided service catalog connections"
```

### Task 9: 服务管理与完整自定义页面

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCustomServerScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectorState.kt`
- Modify: `app/src/main/res/values*/strings_settings.xml`
- Modify: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt`
- Modify: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Produces: 管理动作 `test`、`setEnabled`、`reauthorize`、`replaceSecrets`、`delete`;自定义仍产出原 `McpServerConfig`,并支持全部已有高级字段。

- [ ] **Step 1: Write failing management/custom tests**

覆盖:查看真实工具、启停、重新测试、OAuth 重新授权、只替换选中秘密、删除清理所有秘密;自定义 HTTP/SSE/stdio、Bearer/OAuth、秘密请求头、stdio 秘密环境、多请求头、allowlist、本地 HTTP、信任注解和高级数值均可配置;高级项默认折叠。

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:compileDebugAndroidTestKotlin`
Expected: FAIL, 管理/自定义页面未完成。

- [ ] **Step 3: Implement management and custom flows**

按已确认原型复用组件;自定义与预设共用 store。删除文案不谎称撤销第三方授权;旧自定义配置可编辑且不失字段。

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:assembleDebugAndroidTest -PdevInstall`
Expected: PASS/构建成功。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui app/src/main/res app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt
git commit -m "feat(mcp): complete service management and custom setup"
```

### Task 10: 全链路回归、真实服务与真机验收

**Files:**
- Modify only if a reproduced defect requires it; otherwise no new files.

**Interfaces:**
- Consumes: 全部前序任务。
- Produces: 测试证据、真实服务兼容报告和真机验收结果;不把未验证项说成已通过。

- [ ] **Step 1: Run JVM and catalog regression**

Run: `./gradlew :core:test :app:testDebugUnitTest`
Run: `python3 -I -m unittest discover -s tools/mcp/tests -v`
Expected: PASS;若出现既有本地负载敏感 flake,记录原始输出并按项目既有约定判断,不隐瞒。

- [ ] **Step 2: Run protocol, backup and UI build checks**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall`
Expected: 构建成功;旧自定义 MCP、备份恢复和 inbound 测试无回归。

- [ ] **Step 3: Verify representative real services without private user data**

用专用测试账号/密钥覆盖 OAuth、单/多秘密、额外普通字段、无认证、Node stdio、Python stdio;覆盖错误密钥、取消授权、刷新、离线、分页和大结果。将真实结果与 Task 7 报告对照,失败只修通用根因,不写服务特例。

- [ ] **Step 4: Run phone smoke tests**

在真机验证:目录搜索、OAuth 往返、API Key、多字段、stdio 确认/取消/缓存、任务中真实调用、任务结束进程清理、备份恢复、横竖屏/深浅色、RTL 与至少一种 CJK 语言。使用项目约定的核心冒烟子集,不跑全量约 6 小时套件。

- [ ] **Step 5: Final review and commit**

检查 git diff 不含秘密、临时报告或测试账号;确认所有计划项和验收结果如实记录。若有收尾修正,提交:

```bash
git add <verified-changed-files>
git commit -m "test(mcp): verify guided catalog connections"
```
