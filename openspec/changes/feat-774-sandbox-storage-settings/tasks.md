## 1. 核心限额注入

- [x] 1.1 `Attachments` 加 `AttachmentLimits(maxFileBytes, workspaceMaxBytes)` + `DEFAULT`（=现常量）；`importInto`/`withTaskFiles`/`roomFailure`/`copyOne` 增加 `limits` 参数（默认 DEFAULT，行为零变化）
- [x] 1.2 `FeatureFlags` 加 `attachment_max_mb`（档位 200/512/1024/2048，默认 200）与 `workspace_max_gb`（档位 4/8/16/32，默认 4）读取/写入 + 档位外回落默认
- [x] 1.3 接线三处调用方：`MikaApp.importAttachments`、`androidWechatImages` 的 room lambda、`PhoneTaskFiles.start`（前置 sum 检查与 room lambda）

## 2. 设置 UI 与文案

- [x] 2.1 `DuringWorkScreen` 加两行（单文件上限/工作区上限）+ 两个档位弹窗（照 `SandboxBudgetDialog` 模式），`SettingsScreen` 透传状态与回调
- [x] 2.2 `chat_attach_too_large` 加 `%2$s` 实际档位占位符；渲染点（ChatScreen TOO_LARGE 分支）格式化当前档位；全部语言同步该串
- [x] 2.3 新设置字符串（标题×2、档位文案）×12 语言，跑 i18n 检查

## 3. 测试与验证

- [x] 3.1 单测：FeatureLimits 档位解析（未设/档位内/档位外回落）；Attachments 注入小限额拒绝、DEFAULT 放行的 A/B；放宽工作区上限后 room 检查放行
- [x] 3.2 本地全量：`./gradlew :core:test :core:cliTest :app:testDebugUnitTest` + relayTest（Go）+ i18n 检查全绿
- [x] 3.3 真机走查：默认档位显示 → 调档回写 → 恢复默认；截屏取证入 evidence/

## 4. PR

- [x] 4.1 推分支、建 PR（标题「现状 → 改动」句式、关联 #774、首屏实测证据、如实写明测试覆盖）
- [x] 4.2 等 CI 四组全绿（app/cli/core/relay 四组 pass）

## 5. PR #797 review 返工（方案 A：档位只覆盖聊天附件与设备文件导入）

- [x] 5.1 rebase 到含 #800 的最新 main：`Attachments`/`AndroidDeviceFiles`/`TaskRun` 冲突按新结构解决（importOne 的 onCopied 重构与 limits 共存；设备导入收拢进 `DeviceWorkspaceImports`）
- [x] 5.2 回退范围外接线：`WechatImageFiles`（room 检查回固定 `WORKSPACE_MAX`，去掉构造期档位快照）、`PhoneTaskFiles`（前置 sum 与 room 检查回固定值），两文件与 main 零差异
- [x] 5.3 范围内新入口接线：`DeviceWorkspaceImports` 加 `limits: () -> AttachmentLimits` provider，`copy()` 逐次现取；`TaskRun` 构造点注入 FeatureFlags 档位
- [x] 5.4 设置副标题两串 ×12 语言改写，写明「仅聊天附件与设备文件导入；微信/电脑保持固定限额」
- [x] 5.5 单测：`DeviceWorkspaceImportsTest` 补「档位逐次现取、小限额拒绝/放宽放行」用例
- [x] 5.6 本地全量（core/cli/app 单测 + i18n 检查）+ openspec validate——app 单测 3300+ 全绿（core/relay 与 main 零差异未重跑，CI 兜底）
- [ ] 5.7 推送、更新 PR 描述、回复 review 评论、等 CI 四组全绿——前三项已完成（d920103e 已推、正文已改、范围决策与 CI 说明两条评论已发）；app/cli/relay 绿，core 红系 main 自身 `DocsConsistencyTest` 被维护者 2026-11-22 规划文档打红（本分支 core//docs/ 零改动，main 两个 tip 同款失败），main 转绿后 rebase 重推即闭环
