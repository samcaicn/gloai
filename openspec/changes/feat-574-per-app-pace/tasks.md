# 任务：操作节奏按应用覆盖（#574）

## 1. core：节奏引擎支持按应用三态与限屏分离

- [x] 1.1 新增 `AppTempo`（FAST/NORMAL/CAREFUL，`of("careful")` 解析），放 `core/cap/phone`；`AppPacing.noteAction`/`capReached` 的 pace 入参换成 tempo + capped 判定（FAST 不记录停留；只有 capped 计屏/限屏），保持 Off 快路径
- [x] 1.2 `AppDwell` 的 `pace: (String) -> PlacePace` 换成 `tempo: (String?) -> AppTempo` + `capped: (String) -> Boolean`；`before` 对 FAST 直接不等不闸
- [x] 1.3 `PhoneCapability`/`TaskTools` 透传新参数，默认值仍从 App 知识解析（`AppTempo.of(skills?.appPace(pkg))`、capped 同源）
- [x] 1.4 `OperatingPace` 增加按 tempo 的打字节奏辅助（FAST→Instant，CAREFUL→CAREFUL 打字）；`GuideCatalog` 增加 `carefulApps()`（内置 pace=careful 的 App 包名清单）
- [x] 1.5 core 单测：AppPacing/AppDwell 的 FAST 不等、CAREFUL+capped 计屏限屏、NORMAL 不计屏；GuideCatalog.carefulApps 返回 8 个内置包名；改写受签名影响的既有用例

## 2. app：覆盖存储与任务接线

- [x] 2.1 `FeatureFlags` 增加 `appPaceOverrides()`（prefs JSON，空时 seed `GuideCatalog.carefulApps()`→HUMAN 并落盘）、`setAppPace(pkg, OperatingPace?)`；解析失败按空重新 seed
- [x] 2.2 新增纯函数解析 `effective(pkg)=overrides[pkg]?:global`、`capped(pkg)=overrides[pkg]==HUMAN`、全员 FAST 判定（供单测与 TaskRun 共用）
- [x] 2.3 `TaskRun` 三处接线：`appPacing`（全员 FAST→Off 否则 human(random)）、`appPace`（tempo/capped 按解析）、`typing`（按 effective）；评测钩子设了档位时 overrides 视为空
- [x] 2.4 app 单测：解析函数的覆盖/全局/上限/全员快各分支、seed 首次落盘与损坏回退

## 3. UI：设置入口与按应用子页

- [x] 3.1 `SettingsPage` 加 `AppPace` 路由；`DuringWorkScreen` 操作节奏组下加「按应用设置」入口行
- [x] 3.2 `AppPaceScreen`：覆盖项列表（label+档位）、四选对话框（快/标准/像人/删除）、添加对话框（launchableApps+搜索、排除已覆盖）
- [x] 3.3 新字符串补齐 values 及全部 11 个语言目录

## 4. 验证与收尾

- [x] 4.1 本地全量测试：`./gradlew :core:test :core:cliTest :app:testDebugUnitTest`
- [x] 4.2 真机验证：全局快+某 App 覆盖像人时该 App 步骤间隔显著/其余快；改档下任务不受屏上限误伤；取证进 evidence/
- [x] 4.3 提交（中文提交信息）、推 fork、建 PR 关联 #574，等 CI 通过
