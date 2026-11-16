# 设计：LockState 轮询自愈（#638）

## Context

`DeviceStateWatcher` 为主屏「不可操作（熄屏或锁屏）」提供一份 `StateFlow<Boolean>`：
广播（SCREEN_OFF / SCREEN_ON / USER_PRESENT）来了重读一次；
有人订阅且快照为「亮屏且锁着」时每 500ms 轮询重读（为 #590/#627 引入：解锁时
USER_PRESENT 早于锁屏界面退下，之后没有广播，只能轮询补）。`TaskGate` 在改动屏幕的工具前
检查它，锁着就暂停成「等你解锁手机」，并挂起在 `locked.first { !it }` 等自动恢复。

#638 暴露的缺口：熄屏后快照是 `interactive=false`，轮询守卫 `last.interactive && last.locked`
恒为 false——轮询停了，恢复只能靠 SCREEN_ON / USER_PRESENT。HyperOS / Android 17 上广播
漏发时 `locked` 永久卡 true；该 ROM 上 `isDeviceLocked` 的瞬时误报也一样无人纠正。

## Goals / Non-Goals

**Goals:**
- 广播全丢（SCREEN_ON / USER_PRESENT 一条不到）时，亮屏 + 解锁后任务仍能在约一个轮询周期内自动继续；
- 瞬时的 `isDeviceLocked` 误报不再造成永久暂停（下一次读到解锁即恢复）；
- 不改暂停判定语义与暂停/恢复文案，不碰其他 gate 原因。

**Non-Goals:**
- 不解决「`isDeviceLocked` 在已解锁时持续返回 true」的 ROM 谎报（需另一层仲裁，留给维护者）；
- 不给 LOCKED 暂停加手动「继续」按钮（轮询自愈后无必要；且涉及 gate 语义）;
- 不调轮询周期、不做熄屏降频（500ms × 一次 binder 读，仅暂停期间发生，不值得复杂化）。

## Decisions

1. **守卫改为「锁着就轮询」**：`if (last.locked) refresh()`。熄屏（`!interactive`）也算锁着，
   于是熄屏期间轮询照跑——这正是自愈所需：漏掉 SCREEN_ON 后，第一个读到 `interactive=true`
   的轮询就把快照带回现实。真锁屏下多读无害；读到解锁立即恢复的既有行为不变。
2. **订阅开始立即重读**：`collectLatest { watched -> if (watched) refresh(); while … }`。
   挂在 gate 上的等待最长 500ms 才第一次自愈太浪费；订阅瞬间先读一次，旧状态当场校准。
   （无人订阅时仍然一次都不读，`noReadsWithoutSubscribers` 用例维持。）
3. **注释重写**：删掉「熄屏时不读，等 SCREEN_ON」的旧约定，改为「锁着就重读，广播只当加速信号」，
   把 #638 写进注释，与 #590/#627 的既有注释风格一致。
4. **测试对着行为写**，不测轮询实现细节：
   - 改造 `unlockSeenWithoutAnotherBroadcast`：熄屏期间也读（删掉「熄屏时不读」断言），
     广播全丢时靠轮询看到亮屏+解锁；
   - 新增 `screenOnAndUnlockSeenWithoutAnyBroadcast`：从熄屏锁定出发，只改系统状态、
     一次 `refresh()` 都不调，两个 tick 内 gate 放行——#638 的最小复现；
   - 新增 `staleStateHealsOnSubscribe`：订阅开始即重读，卡住的旧值立刻纠正。

## Risks / Trade-offs

- **熄屏期间持续 binder 读**：每 500ms 一次 `isDeviceLocked`，仅在「有人等这份状态」
  （= 任务暂停在等解锁）时发生；对比卡死一小时的用户损失，代价可忽略。
- **真锁屏时暂停不变、恢复更早**：行为对外只有变好方向的变化；若系统在亮屏后短暂仍报锁着
  （USER_PRESENT 早于锁屏退下的窗口），轮询照旧等 `deviceLocked` 变 false，不会提前放行
  （`unlockSeenWhenKeyguardClearsBeforeDevice` 用例继续覆盖）。
- **快照并发**：`last` 是 `@Volatile`，轮询与广播/订阅线程的读改写仍是「最后写赢」，
  与现状一致；所有写路径都读系统真值，不存在写死值的来源。
