## Why

#314：用户把 Mika 装进模拟器、用桥接程序经官方 CLI 遥控时撞上两类版本不匹配，报错都不足以定位：新版 CLI 对无 `task_recovery` 能力的旧 App 只说「Update the phone app to submit a recoverable request」——不指名缺哪个能力、不报手机当前版本（`discover()` 明明已从 `GET /api/v1` 拿到 `server_version`）、更不说「去掉 `--request-id` 即可照常提交」这个立即可用的绕过手段；steer / export / task_files 三处门禁同样只有一句笼统的「update the phone app」。维护者分诊（TRIAGE-2026-11-18 §2.3）已圈定范围：只做这两种不匹配的**明确报错**，不碰模拟器场景本身。

## What Changes

- CLI 四处能力门禁（`--request-id`→`task_recovery`、`steer`→`task_steer`、`export`→`run_export`、附件/下载→`task_files`）触发 `incompatible_api` 时，报错文案统一为「手机 Mika 版本（已知时）+ 缺失能力名 + 可执行补救」；`--request-id` 的补救必须写明可去掉该参数照常提交。
- 错误 JSON（`--json` 输出与退出码 2 的 stderr）新增结构化字段 `capability`、`server_version`（已知时），桥接程序可据此自动降级（#314 报告人正是靠错误码分支绕过的）。
- `tools/mika/SKILL.md` 错误表中 `incompatible_api` 一行同步为配对指引（能力 → 需更新手机 App / 或去掉参数重试）。
- 旧 CLI 不认 `--request-id`（argparse 拒绝）方向：已发布的旧 CLI 无法追改，只在 SKILL.md 配对说明中写明，不改代码。

## Capabilities

### New Capabilities
- `cli-capability-errors`: CLI 在手机 App 能力不足时的报错契约——必须可诊断（含手机版本与能力名）、可行动（给出补救），并为机器调用方提供结构化字段。

### Modified Capabilities

（无——`openspec/specs/` 尚无主规格，本变更首次为该能力立规格。）

## Impact

- `tools/mika/scripts/mika_cli.py`：新增 `incompatible(info, capability, remedy)` 辅助函数；替换 4 处门禁的 `CliError` 构造（`run_with_files`、`execute` 的 steer/export 分支、`require_files`）。`discover()` 404 / api_version 不符两条路径保持原文案（此时无 server_version 可报）。
- `tools/mika/tests/test_mika_cli.py`：既有 3 处 `incompatible_api` 断言补文案与结构化字段检查；新增 export 门禁文案断言。
- `tools/mika/SKILL.md`：错误表 1 行 + `--request-id` 段落 1 句。
- 不改 App 侧（Kotlin）：API 已正确广播 capabilities，旧 APK 无法追改；`RemoteCliContractTest` 走的happy path 不受影响。
