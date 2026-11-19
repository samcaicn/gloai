# fix-314 取证

## 2026-11-18 端到端实测（模拟旧手机 HTTP 服务）

模拟 `GET /api/v1` 返回 `server_version=0.1.33`、`capabilities=["tasks","task_steer"]`：

```
$ mika … --json task run --goal test --request-id 00000000-0000-4000-8000-000000000001
{"error":{"code":"incompatible_api","message":"The Mika app on the phone (version 0.1.33) does not advertise 'task_recovery'. Rerun without --request-id to submit anyway, or update Mika on the phone to keep requests recoverable.","capability":"task_recovery","server_version":"0.1.33"}}
exit=2

$ mika … --json task export t1
{"error":{"code":"incompatible_api","message":"The Mika app on the phone (version 0.1.33) does not advertise 'run_export'. Update Mika on the phone to export runs.","capability":"run_export","server_version":"0.1.33"}}
exit=2
```

对照旧文案（仅一句）：
- 旧:`"Update the phone app to submit a recoverable request."`
- 旧:`"This Mika APK does not support run export."`

## 单测

`python3 -m unittest discover -s tools/mika/tests`：89 个全过（含新增 2 个用例与 3 处补强断言）。
