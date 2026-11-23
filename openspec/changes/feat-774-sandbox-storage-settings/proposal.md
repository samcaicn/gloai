## Why

issue #774 报了三处写死的沙箱限额：预算时间（PR #780 已做成可调档位）、工作区空间（4 GB）、
单个上传文件体积（200 MiB）。后两个仍写死在 `Attachments` 常量里：聊天附件 / 微信图片 /
电脑发任务带文件都会撞上 200 MiB 单文件与 4 GB 工作区上限，用户没有任何放宽途径，只能眼睁睁
看着「上传失败：超过 200 MiB」。照已合入的沙箱时长档位同一模式，把这两个存储限额也开放成
设置里的档位单选。

## What Changes

- 新增 FeatureFlags 键与读取：`attachment_max_mb`（档位 200/512/1024/2048，默认 200，存量行为零变化）、
  `workspace_max_gb`（档位 4/8/16/32，默认 4）。
- `Attachments` 的单文件上限与工作区上限从对象常量改为可注入参数（默认值 = 现常量，现有调用方与
  测试不传参时行为不变）。档位只覆盖两条设备侧导入入口：聊天附件（MikaApp.importAttachments）与
  设备文件导入（#800 的 `DeviceWorkspaceImports`，SAF 授权目录与手机助手两路），每导入现取档位；
  电脑传输（`RemoteTaskFiles`/`PhoneTaskFiles`）与微信协议层（`WechatImages` 10 MiB）不在范围内，
  保持固定限额（PR #797 review 明确的范围决策）。
- 「任务运行」设置页新增两行档位单选（照 SandboxBudgetDialog 模式）：单文件上限、工作区上限。
- 聊天里「超过 200 MiB 限制」的失败文案改为带实际上限值（`chat_attach_too_large` 加占位符并同步
  全部翻译）。
- 设备剩余空间下限 `MIN_FREE`（500 MiB）是设备安全线，不开放。

## Capabilities

### New Capabilities

- `sandbox-storage-limits`: 单个上传文件体积上限与工作区空间上限可由用户在设置里按固定档位调整，
  默认与既有 200 MiB / 4 GB 一致；档位仅约束聊天附件与设备文件导入两条入口，电脑传输与微信图片
  保持固定限额，设置文案写明范围。

### Modified Capabilities

（无——`sandbox-time-budget` 是上一片已合入的能力，本次不动它的需求。）

## Impact

- `app/src/main/java/com/wangzan101/mika/cap/sandbox/Attachments.kt`：常量 → 注入参数。
- `app/src/main/java/com/wangzan101/mika/settings/FeatureFlags.kt`：两个新键 + 档位校验。
- `app/src/main/java/com/wangzan101/mika/MikaApp.kt`：聊天附件调用点接 FeatureFlags。
- `app/src/main/java/com/wangzan101/mika/task/DeviceWorkspaceImports.kt`（#800 新增）：构造时注入
  `limits: () -> AttachmentLimits` provider，`copy()` 逐次现取传给 `Attachments.importOne`；
  `TaskRun.kt` 构造点接 FeatureFlags。微信/电脑路径不改（`WechatImageFiles.kt`、`PhoneTaskFiles.kt`
  与 main 零差异）。
- `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsSubPages.kt`、`SettingsScreen.kt`：设置行 + 档位弹窗。
- `app/src/main/res/values*/strings_chat.xml`：`chat_attach_too_large` 加 `%2$s` 占位符，全部语言同步。
- `app/src/main/res/values*/strings_settings.xml`：新字符串 ×12 语言。
- 单测：FeatureFlags 档位解析、Attachments 限额注入（小限额拒绝/放宽后放行）、文案格式化。
