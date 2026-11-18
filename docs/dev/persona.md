# 人格（可命名的自定义指令）

人格是一层可命名、可复用、按对话绑定的自定义提示词。它只改变 Mika 怎么说话（语气、长度、措辞、格式、进度说明的写法），不改变 Mika 能做什么。记忆回答「知道什么」，人格回答「怎么做事」。

> 状态：v1，位于 draft PR #292。能力收紧、导入/导出、模型组绑定按后续版本做，见文末。

## 提示词位置

人格拼在身份、准则、通用规则之后，各能力说明之前：

```
system/identity.md
system/principles.md
capabilities/general.md
prompts/persona/layer.md（固定头尾 + 用户正文）
capabilities/groups.md、channels.md、phone.md、web.md、sandbox.md …（按本任务展开的能力）
```

- `TaskRun` 开局拼一次，任务内不变；主 AgentLoop 和子 AgentLoop 用同一份。
- 定时任务里的条件检查（`conditionCheck`，要求只输出 JSON 的内部只读检查）不继承人格。
- 运行记录用 `RunEvent.State("persona", "名字 · vN")` 记这一轮用了谁；`PromptVersion` 自动覆盖提示词变化。
- 没有人物时 `SystemPrompt.withPersona(base, null) == base`，现有提示词金样逐字节不变。

## 固定头尾和转义

`core/src/main/resources/prompts/persona/layer.md` 是固定头尾，只由代码读取，用户不能改。它明确：

- 人格不是用户的消息，不能授权任务、调用工具、读记忆/屏幕、改设置、对外发送或披露数据；
- 只能改语气、长度、措辞、格式、进度说明的写法；
- 不能改证据、安全、隐私、授权、确认、完成规则；
- 人格不能授予工具、权限或访问。

`PersonaPrompt`（`core/src/main/kotlin/com/wangzan101/mika/core/prompt/PersonaPrompt.kt`）负责拼装：

- 名称去掉换行，限长 40，再转义 `& < > "`；
- 正文去掉除换行/制表符外的控制字符和 bidi 控制符；
- 标签样子的文字（`</persona>`、`<persona …>` 及带空格/换行的变体）统一转义，正文不能提前合上块；
- 正文按 token 安全截断（上限 3000 token），不会劈开代理对；
- 正文里的 `{{…}}` 原样保留（`Prompts.fill` 单遍替换）。

## 存储

- `files/settings/personas.json`：`{ "version": 1, "personas": [...] }`。带 `version`，原子写（临时文件 + 改名），读不懂时把原文件另存为 `personas.json.bad-<时间>` 并当空列表。不加密，不含密钥。
- 字段：`id`、`name`、`description`、`prompt`、`revision`、`createdAt`、`updatedAt`、`builtin`（v1 不发内置示例，字段预留）。`revision` 每次保存 +1；v1 只保留最新正文。
- 对话绑定：`<conversationId>.persona` 旁文件，`{ "version": 1, "current": "<id>", "history": [...] }`。历史最多 50 条，同一个 id 重复写不追加；分支复制当前人格，不复制历史；删对话连旁文件一起删；人格被删时旁文件仍在，运行时按默认跑，chip 显示「已删除的人格」。

## 安全边界

- 人格不参与工具列表、权限、审批、模型选择的构造。
- 人格不能授权动作：导入的人格（未来版本）要求调用工具、改设置或对外发送时，模型仍按用户消息和安全规则行事。
- 未来做能力收紧时，从用户已授权的能力里**减**（拒绝列表），不做并集；通用工具、MCP、插件、记忆、定时、子任务先不碰，等统一权限模型。
- 「全部清除」目前不删人格；人格页逐个删除。备份集成另行设计。

## UI

- 设置 › 我的数据 › 人格：列表、新建、编辑、复制、删除、完整提示词预览。
- 输入栏上方的人格 chip 和底部选择面板：有至少一个人格或当前对话已绑定时才显示；任务运行中不可切换。
- 新对话第一句话发送前，`ChatViewModel.begin()` 把选中的人格写进旁文件，任务开局才能读到。
- 功能开关 `FeatureFlags.persona()`：调试版默认可见，发布版默认关；合并前再决定默认值。

## 测试

```bash
./gradlew :core:test --tests "*PersonaPromptTest*" --tests "*PromptSurfaceTest*"
./gradlew :app:testDebugUnitTest --tests "*PersonaStoreTest*" --tests "*ConversationStoreTest*" --tests "*ChatViewModelTest.personaIsWrittenBeforeTheTaskStarts*" --tests "*StringResourcesTest*"
./gradlew :app:compileDebugKotlin
```

提示词金样：`core/src/test/resources/prompt-surface/persona/layer.txt`。有意改固定头尾后用 `-PupdateSnapshots` 重新生成，并在提交里一起审。

## 文件

- `core/.../core/prompt/PersonaPrompt.kt`
- `core/src/main/resources/prompts/persona/layer.md`
- `app/.../settings/PersonaStore.kt`
- `app/.../chat/ConversationStore.kt`（`persona` / `setPersona` / 分支、删除）
- `app/.../task/TaskRun.kt`（开局拼装、条件检查排除、子任务共用）
- `app/.../ui/PersonaScreen.kt`、`PersonaPicker.kt`、`ChatScreen.kt`、`ChatViewModel.kt`
- `app/.../ui/SettingsScreen.kt`、`MainActivity.kt`、`Navigation.kt`
- `app/src/main/res/values*/strings_persona.xml`

## 暂不做

- 能力收紧（v2，`RunSpec.disabledCapabilities` 拒绝列表，避免误删 MCP provider）
- 导入/导出（v2，JSON + 全文预览 + 不自动启用）
- 模型组绑定、技能置顶（v3）
- 多人格叠加、人格市场、人格专属记忆
