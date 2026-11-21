## 1. 实现

- [x] 1.1 `ChatViewModel.StepRow.shouldHideRawReason`：判别改为「含汉字（U+4E00–U+9FA5）且不含假名（U+3040–U+30FF）」，注释同步写明假名是日/中判别信号
- [x] 1.2 `StepRowLanguageTest` 补场景：非中文界面 + 日语用意（汉字+假名）→ 不隐藏；非中文界面 + 纯汉字无假名 → 仍隐藏；中文界面 + 日语用意 → 显示

## 2. 验证

- [x] 2.1 `./gradlew :app:testDebugUnitTest` 全绿（含既有 StepRowLanguageTest 场景不回退）
- [x] 2.2 `./gradlew :core:test :core:cliTest` 不受影响（纯 app 侧改动，跑一遍兜底）
- [x] 2.3 证据落 `evidence/evidence.md`：判别式前后行为矩阵、测试输出摘要
