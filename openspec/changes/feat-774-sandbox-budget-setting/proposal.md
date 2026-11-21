# 每任务沙箱时间上限做成设置里可调的档位（#774）

## Why

issue #774：用户跑耗时沙箱任务（装依赖、编译、数据处理）时，一个任务里沙箱命令累计只有硬编码的 30 分钟（`SandboxGuard.SANDBOX_BUDGET_MS`），到了就拒绝继续，用户没有任何办法放宽。设置里已有同类「用户可调限制」先例（任务步数上限、定时任务时长、token 预算），沙箱时间上限照同一模式开放成档位即可。

issue 三项诉求里本变更只做「预算时间」一项：**沙箱空间**实际是手机物理存储余量（500 MB 最低剩余门槛），没有软件旋钮可调；**上传体积上限**在电脑连接的 relay/RemoteTaskFiles 链路（200 MB），是另一条链子，不适合混在同一个变更里。

## What Changes

- 设置 › 干活时新增「每个任务的沙箱时间上限」：档位 30（默认，与现状一致）/ 60 / 120 / 240 分钟，单选弹窗，下一个任务起生效（与步数上限同语义）。
- `FeatureFlags` 新增存储键与读写（只认档位内的值，存了别的按默认 30 算）。
- 接线：`TaskRun` 主任务与子任务的 `toolDispatcher` 都传入该值 → `SandboxCapability(budgetMs)` → `SandboxGuard(budgetMs)`（构造参数本就存在，只是从没被外部赋值）。
- 耗尽提示不再硬编码「30 minutes」，按实际预算动态输出分钟数。
- 新字符串与全部语言翻译；core/app 单测。

## Capabilities

### New Capabilities

- `sandbox-time-budget`: 每任务沙箱命令累计时长的上限——默认值、用户可调档位、生效时机与耗尽行为。

### Modified Capabilities

（无现有规格目录，不适用。）

## Impact

- core：`SandboxGuard`（动态消息）、`SandboxCapability`/`TaskTools.toolDispatcher`（透传参数，默认值不变）。
- app：`FeatureFlags`（新键）、`TaskRun`（两处接线）、`SettingsScreen`/`SettingsSubPages`（一行设置 + 档位弹窗）、`strings_settings.xml` ×全部语言。
- 行为不变项：默认仍是 30 分钟；单条命令 10 分钟上限（`timeoutSec` 1..600）不变；同命令重复失败判循环（sandbox_loop）不变；用户自己开的终端不受预算约束。
