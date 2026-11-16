# 任务：LockState 轮询自愈（#638）

## 1. 实现

- [x] 1.1 `DeviceStateWatcher.kt`：`LockState` 轮询守卫由 `last.interactive && last.locked` 改为 `last.locked`；
      订阅开始（watched 变 true）时立即 `refresh()` 一次再进入轮询循环
- [x] 1.2 重写 `LockState` / `Reading` 的注释：删「熄屏时不读，等 SCREEN_ON」旧约定，
      写明「锁着就重读、广播只当加速信号」，引用 #638（与 #590/#627 注释风格一致）

## 2. 测试

- [x] 2.1 改造 `LockStateTest.unlockSeenWithoutAnotherBroadcast`：熄屏期间也读；
      断言改为「广播全丢时轮询看到亮屏→解锁两步、gate 放行」
- [x] 2.2 新增 `LockStateTest.screenOnAndUnlockSeenWithoutAnyBroadcast`：
      从熄屏锁定出发、一次 refresh() 都不调，两个 tick 内 gate 放行（#638 最小复现）
- [x] 2.3 新增 `LockStateTest.staleLockedHealsOnSubscribe`：订阅开始即重读，过期「锁着」当场纠正
- [x] 2.4 复核既有用例不回归：`unlockSeenWhenKeyguardClearsBeforeDevice`、`noReadsWithoutSubscribers`

## 3. 验证与交付

- [x] 3.1 本地全量单测：`./gradlew :core:test :core:cliTest :app:testDebugUnitTest` 全绿
- [x] 3.2 构建 debug 包装真机：回归验证锁屏暂停路径仍正常（任务中熄屏 → 出现「等你解锁手机」横幅），
      截图取证入 evidence/
- [x] 3.3 取证落盘 evidence/（时间线 + 截图），说明解锁自动恢复由单测覆盖
      （本机 PIN 锁 adb 解不开，无法端到端解锁；如实写进 PR）
- [ ] 3.4 提交、推 fork、建 PR 关联 #638，正文含根因、修法、测试结果与限制说明
