# fix-323-capsule-open-stall 取证时间线（2026-11-14）

## 代码与单测（本机）

- `OverlayMotionTest`：8/8 通过（4 个收起场景为原有，4 个展开场景为本次新增：
  `reducedMotionSkipsTheOpenAnimation`、`normalAnimationFinishesBeforeOpenTimeout`、
  `missingFramesCannotKeepTheOverlayOpeningForever`、`openSystemSlowMotionGetsEnoughTimeToFinish`）。
  结果文件：`overlay-motion-unit-tests.xml`。
- `./gradlew :app:testDebugUnitTest :core:test`：全部通过（BUILD SUCCESSFUL）。
- `./gradlew :app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`：通过。

## 真机（华为 Mate X2 / TET-AN50 / HarmonyOS 4.2 / API 31，USB）

目标：跑 `CapsuleWindowDeviceTest`（收起残帧回归 + 新增的展开几何断言）。**未能执行，环境受阻**，经过如实记录：

1. 首次 `connectedDebugAndroidTest`（class 过滤 CapsuleWindowDeviceTest）：两个用例均以
   `IllegalStateException: Mika accessibility service is not enabled`（DeviceSupport.kt:50）失败。
   与 DeviceSupport 的文档一致：instrumentation 启动强停 App，无障碍服务被记为已崩溃不再自动重绑，
   正常应由 `rebindAccessibility()` 的「摘下再放回」触发重绑。
2. 手工重绑尝试（均无效）：
   - `settings put secure enabled_accessibility_services` 写回——注意第一次误写成短形式
     `com.wangzan101.mika/.a11y.MikaAccessibilityService`，后改完整形式
     `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService`（DeviceSupport 按完整形式做字符串匹配）；
   - 摘下→force-stop→放回；完整 off/on 循环；拉长间隔（5s/15s）循环；
   - 走系统设置 UI（截图 + 视觉模型导航）：无障碍 → 已下载的服务 → Mika（状态「服务已停止」）→
     「使用 Mika」开关 → 系统确认弹窗「是否允许 Mika 使用无障碍服务？」→ 允许。
3. 系统状态一直停在 `Bound services:{} / Binding services:{com.wangzan101.mika/...MikaAccessibilityService}`，
   `Crashed services:{}` 为空；App 进程存活（`dumpsys activity services` 无该服务的 ServiceRecord，
   即绑定从未到达应用侧），logcat 无重试迹象。
4. 恢复手段评估：重启大概率可清掉挂起绑定，但本机带 PIN 锁、adb 解不开（AGENTS.md 已知），
   重启会锁死后续会话，故未执行。
5. 期间三次复跑 connected 测试，同样失败在 service() 获取实例（环境问题，非本次改动引入——
   首次失败发生在任何源码改动安装之前的新装包上；且改动不触碰无障碍/服务路径）。
6. 过程中定位并修复了一个测试基建 bug（随本 PR）：App 自己的恢复逻辑
   （`core/device/shell/RecoveryPermissions.kt` 的 `PermissionCommands.accessibilityService`）写
   `enabled_accessibility_services` 用的是短形式（`包名/.a11y.MikaAccessibilityService`），而
   `DeviceSupport.rebindAccessibility()` 只按完整形式做字符串匹配——凡是 App 自动修复跑过的机器，
   重绑都被静默跳过（本机 SPMN 日志证实 App 启动即改写；维护者在 41468b39 也提过「该像素测试尚未在
   所需无障碍环境完成运行」，疑同因）。已让 DeviceSupport 两种写法都认（移除时两种都剔）。
   本机的挂起绑定问题与该 bug 相互独立：修复后匹配生效、重绑周期照跑，但系统侧挂起的绑定仍不清，
   测试在本机依旧到不了断言。

结论：本机真机不具备跑该设备测试的环境条件（无障碍绑定在 instrumentation 强停后卡死，
ROM 不再重试）。设备测试（含新增的 `openedCardReachesFullCenteredGeometry`）需在无障碍可正常
重绑的设备上执行——上游 CI/维护者真机此前一直在跑 CapsuleWindowDeviceTest（51bd6d8f、a0c1ea33
两度修复该测试），请以其结果为准。PR 正文如实标注。

## 附：#323 原始症状与代码侧对位

- 报告人（荣耀 30 Pro+ / HarmonyOS 4.2，v0.1.33–0.1.37）：点悬浮球展开的软件画面不全（缩小、偏停靠边）；
  点 × 整张右移留一半；胶囊藏在残卡后面，点它又回到半开——循环。
- 代码对位：`OverlayMotion` 收起路径有超时兜底（`runOverlayClose`）+ 看门狗（97053397）+ 关闭后强制不可见
  （ce037717）+ 像素回归；展开路径的 `repeat(SETTLE_FRAMES){ withFrameNanos{} }` 与 `animateTo(1f)`
  无任何防护，帧停即永久卡中间值，且 `opened()`（→`cardOut`、窗口可见性同步）不执行、边窗胶囊继续画
  ——三点症状可统一由「展开卡中间态」解释（贴停靠边缩放 ≈ transformOrigin 指向停靠边的中间缩放值）。
- 本修复不依赖复现：守卫由单测的 `awaitCancellation`（帧停）场景证明，行为影响仅为超时后 snapTo(1f) 兜底，
  终态与动画自然完成一致。
