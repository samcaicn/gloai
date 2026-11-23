## 1. CLI 报错实现

- [x] 1.1 `mika_cli.py` 新增 `incompatible(info, capability, remedy)`：从 `info` 取 `server_version`（非空字符串时），构造含能力名 + 版本（已知时）+ 补救的文案，`CliError("incompatible_api", …, capability=…, server_version=…)`
- [x] 1.2 四处门禁换用该函数：`run_with_files` 显式 `--request-id`（补救=去掉参数照常提交）、`execute` 的 steer 分支、export 分支、`require_files`
- [x] 1.3 `discover()` 404 / api_version 不符路径保持原样，确认无回归

## 2. 测试

- [x] 2.1 `test_explicit_request_id_never_downgrades_on_an_old_phone` 补断言：文案含 `task_recovery`、`"test"`（DISCOVERY 版本）、`without --request-id`；`error.capability`/`error.server_version` 字段正确
- [x] 2.2 `test_steer_requires_capability_and_never_falls_back_to_run` 补断言：文案含 `task_steer`，`error.capability` 正确
- [x] 2.3 新增 export 门禁断言（挂在既有 `test_export_requires_capability_and_unknown_id_is_explicit` 第一段）：文案含 `run_export`
- [x] 2.4 新增/扩展无 `server_version` 的场景：字段与版本短语不出现、指引仍在
- [x] 2.5 跑 `companions/mika` 全量 CLI 测试（含既有 `RemoteCliContractTest` 由 cliTest 覆盖）

## 3. 文档与验证

- [x] 3.1 `companions/mika/SKILL.md`：错误表 `incompatible_api` 行改为配对指引；`--request-id` 段补「手机报 incompatible_api 时可去掉该参数照常提交」一句
- [x] 3.2 本地跑 CI 四组对应项：`python3 -m unittest discover -s companions/mika`、`:core:test`、`:core:cliTest`（cliTest 覆盖真实 CLI×真实服务契约）
- [ ] 3.3 开分支 `fix/314-cli-version-mismatch-errors`，中文提交，PR 关联 #314，正文附实测错误输出样例
