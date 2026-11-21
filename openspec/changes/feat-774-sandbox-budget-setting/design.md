# 每任务沙箱时间上限做成设置里可调的档位（#774）

## Context

`SandboxGuard`（core/cap/sandbox）从任务开始累计每条沙箱命令的 `durationMs`，超过 `SANDBOX_BUDGET_MS = 30 分钟` 后 `exhausted()` 返回拒绝结果，`capTimeout()` 把单条命令的 `timeoutSec` 压到剩余时间内。构造函数本就有 `budgetMs` 参数（JVM 测试用过小预算），只是 app 侧从未传入，恒为默认。设置里已有三个同类先例：任务步数上限（自定义数字）、定时任务时长（固定档位 15/30/60/120，null=不限制）、token 预算——档位弹窗范式取「定时任务时长」（`TriggerRunLimitDialog`：RadioRow 列档位）。

## Goals / Non-Goals

**Goals:**
- 用户能把每任务沙箱命令累计时长上限在 30/60/120/240 分钟里选一档，默认 30（与现状一致），下一个任务起生效。
- 耗尽提示里的分钟数与实际预算一致（不再硬编码 30）。
- 子任务（ChildAgent）拿与主任务同一档位的预算（各自独立累计，与各自独立步数预算一致）。

**Non-Goals:**
- 不做「不限制」档：时长守卫是耗电/发热的兜底，同命令重复失败另有 `sandbox_loop` 守卫，不靠时间上限兜循环。
- 不动单条命令 1..600 秒的 `timeoutSec` 校验。
- 不动「沙箱空间」（物理存储余量 + 500 MB 门槛）与「上传体积」（relay 链路 200 MB）——issue 另两项诉求，另行处理。
- 不进 SelfConfig（`settings_set`）：定时任务时长等同类限制也没暴露给模型。

## Decisions

- **档位 30/60/120/240，存分钟数（Int），0=未设置按默认 30。** 与 `TRIGGER_RUN_MINUTES` 同风格：只认档位内的值，存了非法值按未设置算。240 封顶足够覆盖装依赖+编译的长活，又不会把守卫形同虚设。
- **core 传 `Long` 毫秒，app 侧换算。** `FeatureFlags.sandboxBudgetMinutes(): Int` 给 UI/存储用；`TaskRun` 接线时 `* 60_000L` 传给 `toolDispatcher(sandboxBudgetMs = …)`（新参数，默认 `SANDBOX_BUDGET_MS`，core 单测与其他调用方零改动）。
- **`SandboxCapability` 加 `budgetMs` 构造参数**，`guard() = SandboxGuard(budgetMs)`；`SANDBOX_BUDGET_MS` 常量保留为默认值与换算基准。
- **耗尽消息动态化**：`"Sandbox commands in this task have used their ${budgetMs / 60_000} minutes. …"`，英文给模型，与现有文案只差分钟数。
- **设置入口放「干活时」页**，紧跟「定时任务最长运行时间」之后（同属任务时长类限制），复用 `MikaIcons.Clock`、`RadioRow`、`trPlural`。

## Risks / Trade-offs

- 240 分钟前台任务耗电/发热——用户自选自担，档位不提供「无限」已是最小妥协；默认档位不变，存量用户行为零变化。
- 子任务各自满额预算理论上可叠加出 240×N 的沙箱时间——子任务由主任务编排且各有步数预算，现状（各自 30 分钟）本就如此，比例不变。
- 新字符串需 11 个语言目录同步，靠 `tools/i18n` 检查兜底。
