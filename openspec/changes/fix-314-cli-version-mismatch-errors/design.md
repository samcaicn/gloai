## Context

CLI（`tools/mika/scripts/mika_cli.py`，纯 Python 标准库）通过 `GET /api/v1` 发现手机能力：`server_version` + `capabilities`（`tasks` / `task_recovery` / `task_steer` / `run_export` / `task_files`…）。四处门禁在能力缺失时抛 `CliError("incompatible_api", …)`，但文案只有一句「update the phone app」，且不带结构化字段——#314 的桥接用户只能靠错误码盲猜降级路径。

约束：**不碰模拟器场景本身**（维护者分诊圈定）；不动 App 侧（旧 APK 无法追改，新 App 的 capabilities 广播已正确）；CLI 面向代理/桥接，报错保持英文、stdout 只有 JSON 的既有约定不变。

## Goals / Non-Goals

- Goals：四处门禁报错可诊断（版本 + 能力名）、可行动（补救句）；`--json` 错误对象带 `capability` / `server_version` 结构化字段；SKILL.md 错误表同步。
- Non-Goals：不改退出码（保持门禁失败 = 2）；不做 CLI/App 版本号对照表进代码（能力广播已是契约，版本号只是人读的）；不改 argparse 行为（旧 CLI 拒绝 `--request-id` 无法追改，只在 SKILL.md 写明）。

## Decisions

1. **单一辅助函数 `incompatible(info, capability, remedy)`** 统一构造四处错误，而不是各处内联拼接——保证「版本（已知时）+ 能力名 + 补救」三要素不漏。`server_version` 仅在它是非空字符串时写入文案与字段。
2. **补救句按操作区分**：`--request-id` 场景写「可去掉该参数照常提交」（任务会跑，只是丢断线找回）；steer/export/files 无参数可去，只指「更新手机上的 Mika」。这来自 #314 报告人实际需要的两条出路。
3. **结构化字段走 `CliError(details)`**（既有机制：`**exc.details` 并入 error 对象），桥接方零文本解析。字段名直接用 `capability` / `server_version`，与 API 广播字段同名。
4. **`discover()` 的 404 与 api_version 不符两条路径不动**：彼时拿不到 server_version，现有文案（「install a build with CLI support」）已是正确指引，加了反而空。
5. **测试断言用既有 fake server**（`api(respond)` + `DISCOVERY`，其 `server_version` 为 `"test"`）：断言文案包含能力名、`"test"`、关键补救词；断言 `error.capability` / `error.server_version` 字段值。不断言整句（避免脆文案测试）。

## Risks / Trade-offs

- [文案措辞无人审] → 保持与既有英文报错同风格（祈使句、短句），PR 正文附真实输出样例给维护者看。
- [`--json` 输出新增字段可能惊到严格 schema 的调用方] → 仅新增键不改动既有键；错误对象本来就有按 details 扩展的先例（`http_status`、`task_id`）。
- [SKILL.md 是随技能分发的文档] → 只改错误表 1 行与 `--request-id` 段 1 句，不重排结构。

## Migration Plan

纯 CLI 单文件改动，无状态、无协议变更；App 与旧 CLI 均无需动作。回滚 = revert 单 commit。

## Open Questions

无。
