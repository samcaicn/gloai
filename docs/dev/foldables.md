# 折叠屏适配

主界面与独立文件查看页按窗口可用空间和 Jetpack WindowManager 报告的分隔折痕布局。窗口未被折痕分隔且使用单栏时，系统栏和键盘仍由原页面处理。

| 窗口 | 布局 |
| --- | --- |
| 可用宽度不足 640dp 或高度不足 480dp | 使用原有的单栏页面和聊天抽屉 |
| 可用宽度 ≥ 640dp、高度 ≥ 480dp | 左侧为会话列表，右侧为聊天或侧栏选中的定时任务、记忆与技能、浏览器。中线可拖动，左侧至少 280dp，右侧至少 320dp |
| 一个竖直分隔铰链，左右分别至少 280dp 和 360dp，高度 ≥ 480dp | 以铰链为固定分界，包括零宽折痕 |
| 横向半折、多铰链或铰链一侧过窄 | 在面积最大的完整区域内布局 |

**操作画面。** 双栏聊天把运行中的画面放到左侧。菜单切回会话列表，列表中的「看画面」可恢复预览。手机虚拟屏按预览可用空间调整，在同一个 displayId 上更换尺寸、密度和接收画面的 Surface。长边最多 2048px，预览随应用方向旋转，等比缩放到短边最多 720px。新建的普通后台屏沿用主屏尺寸，预览短边最多 360px。网页沿用浏览器自己的视口策略。

**控制权。** 点顶部状态或首次点自动操作中的画面申请接管。当前动作结束后，画面内的点击、滑动、返回和输入会送往本次运行的目标。再点顶部状态交回，Mika 重新观察后继续。变更尺寸时拒绝旧坐标。切到窄屏后使用悬浮小窗，已接管的任务也可从聊天的「继续」交回。

**回归验证。** JVM 用例覆盖尺寸断点、铰链、坐标转换和控制权互斥。展开真机上的 `SidebarDestinationsDeviceTest` 覆盖侧栏切页和窄宽窗口切换，`InlineScreenControlDeviceTest` 在真实虚拟屏上验证拖动分栏、保持 displayId、点击输入及交回。后一用例需要已连接的手机助手与无障碍服务，输入验证使用测试包自带的原生页面。

**物理姿态验收。** `PhysicalFoldDeviceTest` 默认跳过，只有显式传入 `physicalFold=true` 才执行。适用于 `dumpsys device_state` 将物理姿态命名为 `OPENED`、`CLOSED`、`HALF_OPENED` 的设备。先横屏展开，再按 `adb logcat -s PhysicalFoldCheck` 日志中的提示依次合屏并解锁外屏、保持约 90° 半折、重新横屏展开；每个姿态最多等待三分钟。用例保留一段未发送草稿，检查外屏单栏、半折时标题和输入框不跨铰链、展开后恢复双栏。它读取物理状态并拒绝状态覆盖，不调用模拟折叠命令。截图写入应用外部文件目录的 `fold-verification/`。

```sh
adb shell am instrument -w -r --no-window-animation \
  -e physicalFold true \
  -e class com.wangzan101.mika.ui.PhysicalFoldDeviceTest \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
```
