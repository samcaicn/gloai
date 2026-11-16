# Compose 真机测试在输入初始化时失败

[个人助手回复复制 #644](https://github.com/wangzan101/Mika/issues/644) 的两条用例第一次运行时，都在 `Espresso.onIdle` 初始化阶段报 `NoSuchMethodException: android.hardware.input.InputManager.getInstance`。还没有点击或长按，不能将这个结果当作复制功能失败。

诊断时检查 **测试 APK** 解析出的 Espresso 版本。本次 Compose 测试间接引入的 3.5.0 在 SDK 37 真机上走了不可用的反射入口；[Espresso 3.7.0 的发布说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)列出了对此的修复。只在 `androidTestImplementation` 指定修复版本，重建、覆盖安装测试 APK，再运行同一条用例；无需调整手机的隐藏 API 策略。

验证一次：同一台 SDK 37 设备、同一个 Dev 应用，仅覆盖测试包后，`PersonalReplyCopyTest` 从两条初始化失败变为两条通过，包括长按、拖选、全选和全文复制的剪贴板断言。

适用边界：上述堆栈出现在用例执行之前。另一种失败发生在刚覆盖安装后的**剪贴板回读**：同一台手机的剪贴板（`ClipboardManager`）允许写入，但因测试窗口尚未取得焦点而拒绝读取，断言得到 null。在 `PersonalReplyCopyTest` 等待 `Activity.hasWindowFocus()` 后，按「覆盖安装 → 立即启动测试」复测通过。这个时序问题与 Espresso 初始化无关；如果已经读到了文字但范围不对，应检查回复组件的选区。
