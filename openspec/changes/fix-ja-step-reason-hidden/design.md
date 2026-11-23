## Context

`StepRow.shouldHideRawReason`（app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt）是 #765（v0.1.50）引入的纯函数判别：非中文界面 + 用意含汉字 → 隐藏。调用方 `StepRow.text` 与收起态末行摘要都依赖它。已有单测 `StepRowLanguageTest`（app/src/test/.../ui/StepRowLanguageTest.kt）以注入 `isChineseLocale` 参数的方式覆盖了 en 界面隐藏中文、zh 界面不隐藏等场景。

## Goals / Non-Goals

- Goal：日语界面下按指令写出的日语用意不再被误判为中文而隐藏；非中文界面隐藏中文违约用意的原目的保持不变。
- Non-Goal：不做"按界面语言逐一白名单"（如 ja 界面放行一切）；不做翻译；不改 `languageDirective` 与提示词侧。

## Decisions

- **判别收紧为「含汉字且不含假名」**（假名即 `\u3040`–`\u30ff` 一个区间，覆盖平假名与片假名两块），而非按界面语言特判：
  - 假名是日/中唯一可靠的字符级判别信号，语言无关；
  - 特判 ja 界面放行会连带放行 en 界面下的日语违约用意，规则更绕且仍解决不了"这段文字是什么语言"的本问题。
- 残余局限（纯汉字无假名的日语串仍被隐藏）接受并在 proposal/spec 里如实记录：无法字符级区分，且回退标题本身已本地化，信息损失有限。
- 顺带修掉默认参数里的隐式全局依赖吗？**不修**。`isChineseLocale: Boolean = uiLocale().language == "zh"` 依赖 `UiText.source`（单测里已注入桩），保持现状，改动面最小。

## Risks / Trade-offs

- en 界面下模型违约写日语（原被隐藏，修后显示）：显示外语比丢弃模型解释的信息损失小，且该场景远比 ja 界面主路径罕见。
- 中文用意里引用单个假名字符（如中文句里夹「ナ」）会被判成日语而显示：自然中文文本不含假名，仅理论上存在。

## Migration Plan

无状态迁移；行为随下次发版直接生效。已显示中的历史步骤不受影响（显示是读时计算的）。
