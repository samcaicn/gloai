## ADDED Requirements

### Requirement: 能力缺失报错必须可诊断、可行动
CLI 在手机 Mika App 广播的 capabilities 缺少当前操作所需能力而抛出 `incompatible_api` 时，错误信息 MUST 同时包含：缺失的能力名（`task_recovery` / `task_steer` / `run_export` / `task_files`）、手机已广播的 `server_version`（当且仅当 `GET /api/v1` 已返回该字段）、以及一句可执行的补救（更新手机上的 Mika，或去掉对应参数照常执行）。

#### Scenario: 显式 --request-id 撞上无 task_recovery 的旧 App
- **WHEN** `mika task run --request-id UUID` 且 `GET /api/v1` 返回的 capabilities 不含 `task_recovery`（含 `server_version`）
- **THEN** 以 `incompatible_api` 退出（不发起 POST），错误信息包含能力名 `task_recovery`、该 `server_version`，并写明可去掉 `--request-id` 重试以照常提交

#### Scenario: steer 撞上无 task_steer 的旧 App
- **WHEN** `mika task steer` 且 capabilities 不含 `task_steer`
- **THEN** `incompatible_api` 错误信息包含能力名 `task_steer` 与补救指引

#### Scenario: export / 附件下载撞上对应能力缺失
- **WHEN** `mika task export` 且无 `run_export`，或带附件/`download` 且无 `task_files`
- **THEN** `incompatible_api` 错误信息分别包含 `run_export` / `task_files` 与补救指引

### Requirement: 错误输出携带结构化能力字段
`incompatible_api` 错误的 JSON 输出（`--json` 的 stdout 与非零退出的 stderr 文档）MUST 附带 `capability` 字段（缺失能力名）；手机已广播版本时 MUST 附带 `server_version` 字段，供桥接程序按字段自动降级。

#### Scenario: 桥接程序按结构化字段降级
- **WHEN** 能力缺失触发 `incompatible_api` 且 `--json` 输出
- **THEN** 输出的 `error` 对象含 `capability`（及可用时的 `server_version`），无需解析自由文本

### Requirement: 无版本可报时保持明确指引
`GET /api/v1` 404（老 APK 无任务 API）或 `api_version` 不符时，错误信息 MUST 保持「装带 CLI 支持的构建」指引，MUST NOT 因缺少 `server_version` 而省略补救或抛未定义错误。

#### Scenario: 老 APK 404
- **WHEN** `GET /api/v1` 返回 404
- **THEN** 报 `incompatible_api` 并指引安装带 CLI 支持的构建，不出现版本字段也不崩溃
