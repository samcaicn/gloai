# 提案：锁屏暂停不再卡死——广播丢失时轮询自愈（#638）

## Why

#638：任务停在「等你解锁手机」后，用户亮屏、解锁、正常使用手机，暂停横幅 1 小时不消失，
承诺的「解锁后自动继续」不生效，界面也没有「继续」，只能停止重发。根因是确定性的：
`DeviceStateWatcher.LockState` 的轮询守卫用上一次快照判断 `interactive && locked` 才重读，
熄屏后快照停在 interactive=false，代码注释明确约定「熄屏时不读，等 SCREEN_ON」——
恢复完全押在 SCREEN_ON / USER_PRESENT 两条广播上；ROM（HyperOS / Android 17 等）漏发任一条，
`locked` 就永久卡在 true，之后没有任何事件再触发重读。该 ROM 上 `isDeviceLocked` 的瞬时误报
（恢复后 ~50 秒再被抓住）在现状下同样会卡死，因为无人纠正。

## What Changes

- `LockState` 轮询守卫从「亮屏且锁着才重读」改为「锁着就重读」（熄屏也是锁着的一种）：
  只要上一次读到的是 locked，按 500ms 持续重读系统状态，读到解锁立即恢复；
- 有人开始订阅这份状态时立即重读一次（不等第一个 500ms），旧状态立刻自愈；
- 更新类注释与 `LockStateTest`：原「熄屏时不读，等 SCREEN_ON」的约定作废，
  新增回归用例「广播全丢（SCREEN_ON / USER_PRESENT 一条不来），亮屏+解锁后任务仍自动继续」。

不改动：暂停的判定语义（仍 `!interactive || deviceLocked`）、暂停文案、无人值守解锁路径、
其他暂停原因（挪走 / 助手断开 / 用户在页面上操作）。

## Capabilities

### New Capabilities
- `device-lock-pause-recovery`: 任务因熄屏/锁屏暂停后，如何检测恢复并自动继续（不依赖广播必达）。

### Modified Capabilities

（无——本变更不改动任何既有规格的其他需求。）

## Impact

- 代码：`app/src/main/java/com/wangzan101/mika/task/DeviceStateWatcher.kt`（仅 `LockState` 轮询与注释）。
- 测试：`app/src/test/java/com/wangzan101/mika/task/LockStateTest.kt`（改 1 个用例、新增 2 个）。
- 代价：熄屏且有人等待时每 500ms 多两次系统查询（isInteractive 本地缓存、isDeviceLocked 一次 binder 调用），
  仅发生在「任务暂停等待解锁」期间，可接受。
- 风险：真锁屏下暂停最多推迟一个轮询周期（≤0.5s），无功能影响；若 ROM 的 `isDeviceLocked`
  在已解锁时持续（而非瞬时）返回 true，本修不解决，需维护者另行仲裁——PR 里如实说明。
