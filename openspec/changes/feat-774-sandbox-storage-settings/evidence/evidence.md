# 真机走查取证：沙箱存储档位设置（#774 后续）

设备：华为 Mate X2（TET-AN50，API 31）；包：app-debug.apk（本分支构建，基于 main 3ad11d66）。
走查前确认：手机未锁屏、Mika 在主页项目列表、无正在运行的任务（截图 2026-11-22 07:28 留 /tmp 不入库）。

## 时间线（2026-11-22 07:29–07:32）

| 时刻 | 步骤 | 结果 |
|---|---|---|
| 07:29 | `adb install -r app-debug.apk` 后进 设置 › 任务运行 | 两行新设置出现：「单个上传文件上限 = 200 MB」「单个工作区上限 = 4 GB」，与默认档一致 → `01-defaults-200mb-4gb.png` |
| 07:30 | 点「单个上传文件上限」 | 弹窗四档 200/512/1024/2048 MB，副标题写明适用范围与默认值 → `02-attachment-dialog-tiers.png` |
| 07:30 | 选 1024 MB | 行值回写「1024 MB」→ `03-attachment-1024mb-set.png` |
| 07:31 | 点「单个工作区上限」 | 弹窗四档 4/8/16/32 GB → `04-workspace-dialog-tiers.png` |
| 07:31 | 选 16 GB | 行值回写「16 GB」→ `05-workspace-16gb-set.png` |
| 07:31 | `run-as … cat shared_prefs/feature_flags.xml` | `attachment_file_limit_mb=1024`、`workspace_limit_gb=16` 落盘 |
| 07:32 | 两行分别恢复 200 MB / 4 GB | 行值与 prefs 均回到默认（`attachment_file_limit_mb=200`、`workspace_limit_gb=4`）→ `06-restored-defaults.png` |

## 结论

- 默认行为零变化：不设置时两行显示 200 MB / 4 GB，与既有写死值一致。
- 档位选择、行值回显、prefs 落盘、恢复默认全链路正常（行值回显即应用读 prefs 的行为证据，未依赖截图读数）。
- 设备为用户日常机，走查期间无任务运行、未触碰其他 App。

本地测试：`:core:test` `:core:cliTest` `:app:testDebugUnitTest` `relayTest`（Go）全绿；
`python3 -m unittest discover -s tools/i18n`、`translations.py check`（3916 资源 ×12 语言）、
`test_pull_logs.py`、`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin` 通过。
设备用例（connectedDebugAndroidTest）按惯例未跑（本机 ROM 无障碍绑定会挂起，见 AGENTS.md）。
