# 插件运行时(P1b)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让第三方插件(以及 Mika 自己)通过装一个文件夹(zip)给 Mika 增加 shell 工具、提示词改写、技能——不用重打包 APK,并把 `mika.translate`、`com.mika.purge-lite` 两个插件跑通。

**Architecture:** 沿用现有"MCP 能力"的接入方式:core 定义 `PluginToolSpec` + `ScriptRunner` 接口 + `PluginScriptTool`(实现 `AgentTool`),app 实现 `ScriptRunner`(ProcessBuilder 跑 `/system/bin/sh`)并从 `PluginRegistry` 读出启用插件的工具规格,经 `toolDispatcher(...)` 的新参数注入;提示词钩子在 `AgentLoop.kt:436` 组装完系统提示词后、发模型前应用。

**Tech Stack:** Kotlin, kotlinx.serialization, Android `ProcessBuilder`/`/system/bin/sh`, 现有 `AgentTool`/`ToolDispatcher`/`CapabilityProvider` 机制。

**Spec:** `docs/superpowers/specs/2026-11-18-plugin-runtime-design.md`

## Global Constraints

- 只收 `.sh` 脚本(App 进程无 python3/node);`tools[].script` 与 `promptTransform` 路径必须落在插件包内。
- 脚本在 App 进程内跑,装前 UI 诚实披露"能读到对话、记忆和模型密钥"。
- 10 种 UI 语言:每个新字符串都要 10 份翻译(见 `app/src/main/res/values*/strings_plugins.xml` 现状)。
- 通用安卓(AOSP),不针对品牌。
- 清掉外部项目字样("Mika Ultra"、"dsh-purge"——在移植的插件与文档里一律不出现)。
- 真机验证(模拟器只是占位)。
- 功能前置、安全后置:本轮不做运行时权限拦截/进程隔离/密钥脱敏,只做声明 + 诚实披露;这些列为已知后置项,不假装做了。
- 版本号 0.1.N 递增,从不 0.2.0。

## Review Focus

以下五类输入最容易让用户翻车,任何一个任务都不测它们:

1. **插件脚本输出非 JSON 或崩溃** → 执行器必须给干净失败(带 stderr),绝不能把整个任务搞崩。→ Task 2 测试 `test_non_json_stdout_is_clean_failure`。
2. **插件脚本永远不退出** → 超时必须杀掉进程并返回 `timed_out` 失败。→ Task 2 `test_timeout_reported`、Task 3 `test_real_shell_timeout_kills`。
3. **`script`/`promptTransform` 路径逃逸出包(`..`/绝对路径/`.py`)** → manifest 校验阶段就拒装。→ Task 1 `test_script_path_must_stay_in_package`、`test_script_must_be_sh`。
4. **提示词钩子挂起/崩溃** → 3 秒超时回退原文,不阻塞、不坏任务。→ Task 5 `test_hook_timeout_falls_back`、`test_hook_crash_falls_back`。
5. **插件工具名与核心工具撞名** → `plugin_<id去点>_<name>` 前缀 + 组装时去重,不能静默遮蔽核心工具。→ Task 2 `test_tool_name_prefixed` + 组装去重断言。

---

## Task 1: manifest 放开 tools/permissions/promptTransform 并校验

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/plugin/PluginManifest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/plugin/PluginManifestTest.kt`

**Interfaces:**
- Consumes: 现有 `PluginManifest` / `PluginToolDecl`(字段已含 `script`/`timeoutSec`/`risk`/`inputSchema`)。
- Produces: `PluginManifest.promptTransform: String?`(新字段);`validate()` 接受 `tools`/`permissions`/`promptTransform`;错误码 `script_not_sh`、`invalid_permission`、`invalid_prompt_transform`。

- [ ] **Step 1: 写失败测试**

在 `PluginManifestTest` 增加(用一份带 `tools`+`permissions:["run-scripts"]`+`promptTransform` 的合法 manifest JSON):

```kotlin
fun test_tools_permissions_promptTransform_accepted() {
    val m = PluginManifest.parse(validP1bManifestJson)
    assertEquals(emptyList<String>(), m.validate("0.1.45"))
    assertEquals("scripts/rewrite_prompt.sh", m.promptTransform)
}
fun test_script_must_be_sh() {
    // tools[0].script = "scripts/foo.py"
    assertEquals(listOf("script_not_sh"), m.validate("0.1.45"))
}
fun test_script_path_must_stay_in_package() {
    // tools[0].script = "../evil.sh"
    assertEquals(listOf("script_not_sh"), m.validate("0.1.45")) // 或专用路径码;断言非空即可
}
fun test_unknown_permission_rejected() {
    // permissions = ["do-anything"]
    assertEquals(listOf("invalid_permission"), m.validate("0.1.45"))
}
fun test_promptTransform_path_validated() {
    // promptTransform = "../evil.sh"
    assertEquals(listOf("invalid_prompt_transform"), m.validate("0.1.45"))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginManifestTest*'`
Expected: FAIL(字段/校验未放开)

- [ ] **Step 3: 实现**

在 `PluginManifest` 加 `val promptTransform: String? = null`;`validate()` 里放开 `tools`/`permissions`/`promptTransform` 的拒绝逻辑,改为:白名单 `run-scripts`/`rewrite-prompt`;`tools[].script` 必须以 `.sh` 结尾、无 `..`、非绝对路径;`promptTransform` 同规则。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginManifestTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/plugin/PluginManifest.kt app/src/test/java/com/wangzan101/mika/plugin/PluginManifestTest.kt
git commit -m "feat(plugin): accept tools/permissions/promptTransform in manifest"
```

---

## Task 2: core 执行器 —— ScriptRunner + PluginScriptTool + toolDispatcher 接线

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/plugin/PluginTool.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/assembly/TaskTools.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/plugin/PluginScriptToolTest.kt`

**Interfaces:**
- Consumes: `AgentTool`(internal)、`Risk`、`ToolExecution`、`ok { }`/`fail(...)` 构造器、`RunAssembler`/`CapabilityProvider`。
- Produces(供 Task 3/5 用):
  - `data class PluginToolSpec(name, description, scriptPath: String, scriptDir: File, inputSchema: JsonObject, timeoutSec: Int, risk: Risk)`
  - `fun interface ScriptRunner { suspend fun run(script: File, argsFile: File, env: Map<String,String>, timeoutMs: Long): ScriptResult }`
  - `data class ScriptResult(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean)`
  - `toolDispatcher(... pluginTools: List<PluginToolSpec> = emptyList(), pluginScriptRunner: ScriptRunner? = null)`(新参数)。

- [ ] **Step 1: 写失败测试**

用假 `ScriptRunner`(不真跑 shell),`PluginScriptToolTest`:

```kotlin
fun test_executes_script_and_parses_json_result() {
    val runner = FakeRunner { script, argsFile, env, _ -> ScriptResult(0, """{"ok":true,"translation":"你好"}""", "", false) }
    val t = PluginScriptTool(spec(name="plugin_mika_translate_translate"), runner, tmpDir)
    val r = t.execute(Args.fromJson("""{"text":"hi"}"""), fakeCtx)
    assertEquals("你好", (r.content["translation"] as JsonPrimitive).content)
}
fun test_args_written_to_file_and_passed() {
    // 断言 runner 收到的 argsFile 内容 == 本次工具参数的 JSON;script 路径 == spec.scriptDir/spec.scriptPath
}
fun test_non_json_stdout_is_clean_failure() {
    // stdout = "garbage" → r 是失败,内容含 stderr/原始输出,不抛异常
}
fun test_timeout_reported() {
    // ScriptResult(timedOut=true) → 失败,错误码 "timed_out"
}
fun test_tool_name_prefixed() {
    // 组装后 ToolIndex 里既有 plugin_ 前缀名,且不与 GeneralGroup 核心工具名冲突(去重后仍只有一个)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*PluginScriptToolTest*'`
Expected: FAIL(类/参数不存在)

- [ ] **Step 3: 实现**

`PluginTool.kt` 定义上面三个类型 + `internal class PluginScriptTool : AgentTool`:`execute` 写参数 JSON 到临时文件 → `runner.run(File(scriptDir, scriptPath), argsFile, env, timeoutMs)` → 解析 stdout 为 JSON:解析成功且 `ok==true` 就 `ok(parsed)`,否则 `fail`(带 stderr/stdout);`def` 用 `inputSchema`;`risk` 用 `spec.risk`;`offScreen=true`;`concurrency=Serial`。`TaskTools.kt` 的 `toolDispatcher` 加两个参数,在 `providers` 列表加一个 `CapabilityProvider`(id=`"plugin"`),`open` 返回一个极简 `Capability`(无屏/无守卫/无节奏,`tools()` 返回去重后的 `PluginScriptTool`s,`folds=false`)。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*PluginScriptToolTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/plugin/PluginTool.kt core/src/main/kotlin/com/wangzan101/mika/core/assembly/TaskTools.kt core/src/test/kotlin/com/wangzan101/mika/core/plugin/PluginScriptToolTest.kt
git commit -m "feat(plugin): core executor runs plugin shell tools"
```

---

## Task 3: app 侧 ScriptRunner + Registry 出工具规格

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/plugin/PluginScriptRunner.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/plugin/PluginRegistry.kt`
- Test: `app/src/test/java/com/wangzan101/mika/plugin/PluginScriptRunnerTest.kt`

**Interfaces:**
- Consumes: `ScriptRunner`/`ScriptResult`/`PluginToolSpec`(Task 2)、`PluginManifest`/`PluginRecord`(Task 1)。
- Produces:
  - `class PluginScriptRunner(shell: String = "/system/bin/sh", envProvider: () -> Map<String,String> = { emptyMap() }) : ScriptRunner`(可注入 `shell` 供 JVM 测试)。
  - `PluginRegistry.toolSpecs(): List<PluginToolSpec>`(启用插件的工具,名字拼成 `plugin_<id去点>_<name>`)。

- [ ] **Step 1: 写失败测试**

`PluginScriptRunnerTest`(JVM,用 `PluginScriptRunner(shell = "sh")` 跑真 shell):

```kotlin
fun test_runs_sh_and_captures_stdout() {
    val r = PluginScriptRunner(shell = "sh")
    val out = runBlocking { r.run(scriptFile("echo '{\"ok\":true}'"), argsFile, emptyMap(), 5000) }
    assertTrue(out.stdout.contains("\"ok\":true"))
}
fun test_real_shell_timeout_kills() {
    // 脚本 sleep 30 → ScriptResult.timedOut == true
}
fun test_stderr_captured() {
    // 脚本 echo err >&2;exit 1 → stderr 非空、exitCode==1
}
```

以及 `PluginRegistry` 规格测试:给定一个启用插件目录(`manifest` 带两个 tool),`toolSpecs()` 返回两个名字为 `plugin_<id>_<name>` 的 spec。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginScriptRunnerTest*'`
Expected: FAIL(类不存在)

- [ ] **Step 3: 实现**

`PluginScriptRunner.run`:`ProcessBuilder(shell, script.path, argsFile.path)` → 设 `MIKA_ARGS_FILE=argsFile.path` 与 `envProvider()` → 读 stdout+stderr(用超时 + destroyForcibly 处理超时)→ 返回 `ScriptResult`。`PluginRegistry.toolSpecs()` 遍历 `enabledPlugins()` 的 `manifest(id)` 的 `tools`,映射 `risk`(read→Read/write→Write/external→External,缺省 Read)。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginScriptRunnerTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/plugin/PluginScriptRunner.kt app/src/main/java/com/wangzan101/mika/plugin/PluginRegistry.kt app/src/test/java/com/wangzan101/mika/plugin/PluginScriptRunnerTest.kt
git commit -m "feat(plugin): app runs plugin scripts and exposes tool specs"
```

---

## Task 4: 环境变量存储 + 设置页

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/settings/EnvVarStore.kt`
- Create: `app/src/main/java/com/wangzan101/mika/ui/EnvVarSettings.kt`(设置子页)
- Modify: `app/src/main/java/com/wangzan101/mika/ui/SettingsSubPages.kt`(登记入口)
- Modify: 各 `app/src/main/res/values*/strings_plugins.xml`(10 语言)
- Test: `app/src/test/java/com/wangzan101/mika/settings/EnvVarStoreTest.kt`

**Interfaces:**
- Consumes: 现有 settings store 模式(参照 `NetProxyStore.kt`)。
- Produces: `class EnvVarStore(...) { fun all(): Map<String,String>; fun set(k: String, v: String); fun remove(k: String); fun clear() }` + 一个 `StateFlow`/回调供 UI 观察;设置页暴露"环境变量"入口。

- [ ] **Step 1: 写失败测试**

`EnvVarStoreTest`(临时目录构造 store):

```kotlin
fun test_roundtrip_persists() {
    val s = EnvVarStore(tmpDir)
    s.set("MIKA_TRANSLATE_BASE", "https://x/chat/completions")
    val s2 = EnvVarStore(tmpDir) // 重读
    assertEquals("https://x/chat/completions", s2.all()["MIKA_TRANSLATE_BASE"])
}
fun test_remove_and_clear() { /* remove 后 all() 不含;clear 后空 */ }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnvVarStoreTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

`EnvVarStore`:key/value 落盘(参照 `NetProxyStore` 的持久化方式);`EnvVarSettings` 子页:名字=值 列表、增删改、空值即删除;`SettingsSubPages` 登记;所有新字符串补 10 语言。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnvVarStoreTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/settings/EnvVarStore.kt app/src/main/java/com/wangzan101/mika/ui/EnvVarSettings.kt app/src/main/java/com/wangzan101/mika/ui/SettingsSubPages.kt app/src/main/res app/src/test/java/com/wangzan101/mika/settings/EnvVarStoreTest.kt
git commit -m "feat(plugin): environment variable store and settings page"
```

---

## Task 5: 提示词钩子 promptTransform

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/plugin/PluginPromptHook.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt`(第 436 行附近加应用点 + 构造参数)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`(接线)
- Test: `app/src/test/java/com/wangzan101/mika/plugin/PluginPromptHookTest.kt`;`core/src/test/kotlin/com/wangzan101/mika/core/agent/PromptTransformTest.kt`

**Interfaces:**
- Consumes: `ScriptRunner`(Task 2)、`PluginScriptRunner`/`PluginRegistry`(Task 3)、`EnvVarStore`(Task 4)。
- Produces:
  - `AgentLoop(..., promptTransform: (suspend (String) -> String)? = null)`(在 `caller.systemPrompt = SystemPrompt.assemble(...)` 后应用:`caller.systemPrompt = promptTransform?.invoke(assembled) ?: assembled`)。
  - `class PluginPromptHook(registry, runner, modelName: () -> String, env) { suspend fun transform(prompt: String): String }`。

- [ ] **Step 1: 写失败测试**

`PluginPromptHookTest`(假脚本):脚本从 stdin 读、stdout 写 `PREFIX\n<prompt>` 之类;断言 prefix/suffix/replace 语义、3 秒超时回退原文、脚本崩溃回退原文、`MIKA_MODEL` 传入。`PromptTransformTest`(core):构造 `AgentLoop(promptTransform = { "X" })` 断言 `caller.systemPrompt == "X"`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginPromptHookTest*' :core:test --tests '*PromptTransformTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

`PluginPromptHook.transform`:对每个启用了 `promptTransform` 的插件跑脚本(stdin=原提示词、stdout=改写后),`MIKA_MODEL` 进 env;单个脚本 3 秒超时/非零退出 → 用原文;按(内容 + 模型名)缓存。`AgentLoop` 加参数并应用。`TaskRun.kt` 里构造 `PluginPromptHook` 传进两处 `AgentLoop(...)`(主任务 1072 行、子任务 978 行)。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginPromptHookTest*' :core:test --tests '*PromptTransformTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/plugin/PluginPromptHook.kt core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt app/src/main/java/com/wangzan101/mika/task/TaskRun.kt app/src/test/java/com/wangzan101/mika/plugin/PluginPromptHookTest.kt core/src/test/kotlin/com/wangzan101/mika/core/agent/PromptTransformTest.kt
git commit -m "feat(plugin): promptTransform hook with timeout fallback"
```

---

## Task 6: 插件管理 UI(装前风险披露 + 提示词规则查看/编辑/还原)

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/PluginsScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/plugin/PluginInstaller.kt`(安装回调带回 tools/permissions 供弹窗)
- Modify: 各 `app/src/main/res/values*/strings_plugins.xml`(10 语言)

**Interfaces:**
- Consumes: `PluginManifest`(Task 1 后的 tools/permissions/promptTransform)、`PluginRegistry`(Task 3)。
- Produces: 装前弹窗列出工具名+risk 与"能读到对话、记忆和模型密钥";插件详情页新增"查看/编辑/还原默认提示词规则"三行(编辑 `persona.conf`,还原写回默认,规则文件在插件目录内)。

- [ ] **Step 1: 写失败测试(可测部分)**

在 `app/src/test/java/com/wangzan101/mika/plugin/PluginSampleTest.kt` 增加:给定带 tools 的 manifest,安装前披露信息包含每个工具名与 risk 文案;详情返回默认提示词规则模板。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginSampleTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

`PluginsScreen` 加披露弹窗与详情三行;`PluginInstaller` 的安装结果携带解析后的 `PluginManifest`(供弹窗);`persona.conf` 查看/编辑/还原(还原写回 Task 里定义的默认模板)。所有新字符串补 10 语言。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*PluginSampleTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/PluginsScreen.kt app/src/main/java/com/wangzan101/mika/plugin/PluginInstaller.kt app/src/main/res app/src/test/java/com/wangzan101/mika/plugin/PluginSampleTest.kt
git commit -m "feat(plugin): install disclosure and prompt-rule editor UI"
```

---

## Task 7: 移植两个插件 + 打包 + 真机验证 + 版本号

**Files:**
- Create: `plugins/mika.translate/**`(manifest.json、scripts/、skills/、docs/)
- Create: `plugins/com.mika.purge-lite/**`
- Create: `plugins/build.py`(打包成 zip 到 `plugins/dist/`)
- Modify: `app/build.gradle.kts`(versionName 0.1.N → 0.1.N+1)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`(若 Task 3/5 未在此接线则补)

**Interfaces:**
- Consumes: 前面所有任务。插件内容来自 `/Users/bolyn/Downloads/飞书20261117-231039/` 下的 `mika.translate`、`com.mika.purge-lite` 源码。

- [ ] **Step 1: 移植并清理**

把两个插件目录拷进 `plugins/`,manifest `minApp` 改成当前版本号;清掉 "Mika Ultra"、"dsh-purge" 字样与指向外部项目的引用(保留许可所需署名到 `licenses/THIRD_PARTY_NOTICES.md`)。

- [ ] **Step 2: 打包**

`python3 plugins/build.py` 产出两个 zip 到 `plugins/dist/`;`unzip -l` 核对无越界路径、manifest 校验通过。

- [ ] **Step 3: JVM 全量回归**

Run: `./gradlew :core:test :app:testDebugUnitTest`
Expected: PASS(所有既有 + 新增测试)

- [ ] **Step 4: 真机验证(有用户在场)**

真机装当前构建 → 设置里装 `mika.translate` zip → 浏览器开英文页说"翻译这页"(无 key 走 segments 回落,再配 `MIKA_TRANSLATE_*` 走真接口)→ 装 `com.mika.purge-lite` → 改一条提示词规则确认生效、`restore` 还原。

- [ ] **Step 5: 记录与 Commit**

```bash
git add plugins app/build.gradle.kts app/src/main/java/com/wangzan101/mika/task/TaskRun.kt
git commit -m "feat(plugin): bundle translate and purge-lite plugins"
```

---

## 自审记录

- **Spec 覆盖**:设计文档五块(执行器/钩子/配置/UI/校验)+ 两插件均有对应任务(Task 2/5/4/6/1/7)。
- **类型一致**:`ScriptRunner`/`ScriptResult`/`PluginToolSpec` 在 Task 2 定义,Task 3/5 引用,签名一致;`PluginScriptRunner(shell)` 可注入供 JVM 测试。
- **Review Focus**:五条均已挂到对应任务的测试步骤。
- **安全后置**:本轮不做运行时权限拦截/密钥脱敏/进程隔离;已在 Global Constraints 明写为后置,不在任务里假装实现。
