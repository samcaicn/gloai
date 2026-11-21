# 人格（可命名的自定义指令）

人格是一层可命名、可复用的自定义提示词：一组界面上选的选项，加一段自己写的指令。在设置里选用一套，对所有对话生效。它只改变 Mika 怎么说话（语气、长度、措辞、格式、进度说明的写法），不改变 Mika 能做什么。记忆回答「知道什么」，人格回答「怎么做事」。

> 状态：v1（#292）已合入，发行版默认开启。能力收紧、导入/导出、模型组绑定按后续版本做，见文末。

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

## 选项

`PersonaStyle`（`core/src/main/kotlin/com/wangzan101/mika/core/prompt/PersonaStyle.kt`）是界面上选的那部分：

- 基础风格和语气：默认 / 干练 / 友好 / 活泼（存盘写 `default`、`crisp`、`warm`、`playful`）。
- 三个三档选项：表情符号、回答篇幅、进度说明（存盘一律写 `less`、`default`、`more`）。

选项有意做得少：一种基础风格定调子，三个选项管最常被要求改的三件事，其余的写进指令。早先试过的「专业」风格和亲切程度、热情程度、标题和列表三个选项已拿掉；文件里若还带着它们，按默认读。

每个非默认取值对应 `core/src/main/resources/prompts/persona/style.md` 里的一句固定文字（键是「选项.取值」，格式同 feedback 文件），按固定顺序拼在人格块里、用户写的指令之前，一条一行；中间空一行再接指令。选项全默认时一句不加，只有指令的人格拼出来和加选项之前逐字节一样；选项全默认且指令为空时没有这一层。

选项只落在固定头允许人格改的范围里。「少问我」「跳过核对」属于授权和证据规则，不做成选项。轻量模型和主模型用同一份系统提示词，选项对两者都生效。

给用户和 AI 助手看的编写要求见 [persona-writing.md](../persona-writing.md)。

## 称呼

人格可以带一个称呼（`Persona.nickname`）：助理的自称，默认 Mika。它会进围栏之外的固定文字，所以由 `PersonaPrompt.nickname` 严格清洗：只留各种文字的字母、数字、组合记号、空格、连字符和间隔号，连续空白并成一个，最多 16 个字，清洗后为空就是 Mika。界面上被清洗动过的称呼不让保存（`PersonaPrompt.nicknameAccepted`），不悄悄改。

- 称呼是 Mika 时，身份句和人格层和以前逐字节一样。
- 不是 Mika 时：`system/identity.md` 首句用称呼（文件里是占位符）；后面紧跟 `persona/renamed.md` 一段，说明 Mika 是所在 App 的名字、后文和工具说明里的 Mika 指这个 App 和它自己、改名不改变它能做什么；人格层固定头里的「You are still …」也用称呼。
- 只改称呼、别的全默认时没有人格层，但运行记录照样记这一轮用了哪一套。
- 界面上的「Mika」（顶栏、回答卡片左上角、设置里的文案）不跟着称呼变：那是 App 的名字。
- 工具说明、反馈文字和内部角色的提示词里的 Mika 不变；用新名字称呼它的消息在意图判断这些内部环节里是否同样顺利，没有测过。个人助手模式有自己的提示词，不带称呼。

## 固定头尾和转义

`core/src/main/resources/prompts/persona/layer.md` 是固定头尾，只由代码读取，用户不能改。它明确：

- 人格不是用户的消息，不能授权任务、调用工具、读记忆/屏幕、改设置、对外发送或披露数据；
- 只能改语气、长度、措辞、格式、进度说明的写法；
- 不能改证据、安全、隐私、授权、确认、完成规则；
- 人格不能授予工具、权限或访问。

`PersonaPrompt`（`core/src/main/kotlin/com/wangzan101/mika/core/prompt/PersonaPrompt.kt`）负责拼装：

- 名称去掉换行、控制字符和 bidi 控制符，限长 40，再转义 `& < > "`；
- 正文去掉除换行/制表符外的控制字符和 bidi 控制符；
- 标签样子的文字（`</persona>`、`<persona …>`，以及带空格/换行、全角尖括号、单词被零宽字符拆开的变体）统一转义，正文不能提前合上块；零宽连接符本身留着（波斯语和组合表情要用）；
- 正文按 token 安全截断（上限 3000 token），不会劈开代理对；
- 正文里的 `{{…}}` 原样保留（`Prompts.fill` 单遍替换）。

## 存储

- `files/settings/personas.json`：`{ "version": 1, "personas": [...], "active": "<id>" }`。`active` 是设置里选的在用的那一套，和列表同一次写盘；删掉在用的那一套时一并清掉，指向不存在的 id 当没有。带 `version`，原子写（临时文件 + 改名），读不懂时把原文件另存为 `personas.json.bad-<时间>` 并当空列表。不加密，不含密钥。
- 字段：`id`、`name`、`description`、`prompt`、`style`（选项；旧文件没有时全默认，不认识的取值按默认读）、`nickname`（称呼，空着就是 Mika）、`revision`、`createdAt`、`updatedAt`、`builtin`（v1 不发内置示例，字段预留）。`revision` 在内容真的变了时 +1（点的是已经选着的那一档不写盘、不涨）；只保留最新内容。
- 对话旁文件 `<conversationId>.persona`（上游 v1 的按对话绑定）不再读写：`ConversationStore` 里的 `persona` / `setPersona` 和分支时的复制还在，但任务不看它，盘上已有的文件留着不动。

## 安全边界

- 人格不参与工具列表、权限、审批、模型选择的构造。
- 人格不能授权动作：导入的人格（未来版本）要求调用工具、改设置或对外发送时，模型仍按用户消息和安全规则行事。
- 未来做能力收紧时，从用户已授权的能力里**减**（拒绝列表），不做并集；通用工具、MCP、插件、记忆、定时、子任务先不碰，等统一权限模型。
- 「全部清除」目前不删人格；人格页逐个删除。备份集成另行设计。

## UI

- 入口：设置 › 偏好 › 人格（紧跟「语言与外观」），行尾显示现在在用的：默认 / 存了多套时在用那一套的名称 / 基础风格的叫法 / 已调整。主界面没有人格入口。
- 人格页（`PersonaScreen`）打开就是在用的那一套。页面上改的是草稿，点底部的「保存」才写下去并回上一页；有没保存的改动就返回或去「管理人格」，先问一句「放弃修改？」。最上面是称呼的输入行；基础风格是四个带说明的单选行；三个三档选项在一张卡片里各占一行，行尾是现在的取值，点开单选对话框；有选项不是默认时卡片末尾多一行「恢复默认选项」（只动选项）；指令是页面上的多行输入框。
- 还没有在用的时页面显示全默认，第一次保存才建一套（名称「我的风格」）并设为在用（`PersonaStore.editActive`）；保存的内容和原来一样时不写盘、不涨修订号。
- 页底「管理人格」：存下来的各套，点一行弹操作面板（用这一套、改名、复制一份、删除），在用的带「在用」；「新建人格」建一套全默认的并直接选用；最多 20 套；在用的那一套拼出了提示词时可以展开「预览完整提示词」。删掉在用的那一套就回到默认。
- 改动从下一个任务起对所有对话生效（含定时、渠道和电脑交来的任务），正在跑的任务不受影响。
- 功能开关 `FeatureFlags.persona()`：默认开，设置里没有入口；关掉后界面不显示人格入口，任务也不带人格。

## 测试

```bash
./gradlew :core:test --tests "*PersonaPromptTest*" --tests "*PromptSurfaceTest*"
./gradlew :app:testDebugUnitTest --tests "*PersonaStoreTest*" --tests "*ConversationStoreTest*" --tests "*StringResourcesTest*"
./gradlew :app:compileDebugKotlin
```

提示词金样：`core/src/test/resources/prompt-surface/persona/layer.txt`、`layer-options.txt`、`renamed.txt`。有意改固定头尾后用 `-PupdateSnapshots` 重新生成，并在提交里一起审。

## 文件

- `core/.../core/prompt/PersonaPrompt.kt`、`PersonaStyle.kt`
- `core/src/main/resources/prompts/persona/layer.md`、`style.md`、`renamed.md`
- `app/.../settings/PersonaStore.kt`
- `app/.../chat/ConversationStore.kt`（`persona` / `setPersona`：上游 v1 的对话绑定，已不再使用）
- `app/.../task/TaskRun.kt`（开局拼装、条件检查排除、子任务共用）
- `app/.../ui/PersonaScreen.kt`
- `app/.../ui/SettingsScreen.kt`、`MainActivity.kt`、`Navigation.kt`
- `app/src/main/res/values*/strings_persona.xml`

## 暂不做

- 能力收紧（v2，`RunSpec.disabledCapabilities` 拒绝列表，避免误删 MCP provider）
- 导入/导出（v2，JSON + 全文预览 + 不自动启用）
- 模型组绑定、技能置顶（v3）
- 多人格叠加、人格市场、人格专属记忆
