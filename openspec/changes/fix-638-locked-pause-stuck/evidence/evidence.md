# #638 锁屏暂停卡死——验证取证

## 单元测试（修复证明）

`./gradlew :core:test :core:cliTest :app:testDebugUnitTest` 全绿（2026-11-16）。
`LockStateTest` 5 用例全过，其中：

- `screenOnAndUnlockSeenWithoutAnyBroadcast`（新增，#638 最小复现）：熄屏锁定出发，
  SCREEN_ON / USER_PRESENT 一条不来，熄屏期间轮询照读（`reads > dark`），
  系统状态变亮屏+解锁后两个 tick 内 gate 放行。旧代码在该用例第一步就失败
  （熄屏快照 interactive=false 挡住轮询，reads 不增长）。
- `staleLockedHealsOnSubscribe`（新增）：订阅开始立即重读，漏掉的解锁不用等第一个 500ms。
- `unlockSeenWithoutAnotherBroadcast`（改造）：去掉「熄屏时不读」断言，USER_PRESENT 先到
  但设备仍锁时继续轮询，直到 deviceLocked=false 才放行。
- `unlockSeenWhenKeyguardClearsBeforeDevice`、`noReadsWithoutSubscribers`（既有，不回归）。

## 真机回归（无回归证明）

华为 Mate X2（TET-AN50，HarmonyOS 4.2 / API 31），本分支 debug 包，真实模型 glm-5.3-flash，
任务经「电脑连接」API 注入（run 20261116-090428-bdn5xo）：

1. 任务创建时手机熄屏（电源键熄屏，未到 PIN 锁定延迟）→ 约 10 秒后任务 API 状态 `paused`
   ——熄屏按「锁着」暂停的路径正常（`!interactive` 判定，改动未触碰）。
2. 电源键亮屏 → 任务 API 状态回到 `running`，无任何人工干预；
   `dumpsys window` 焦点窗口为 `com.android.settings.HWSettings`——任务恢复后确实继续操作手机
   （events.jsonl 前几步为打开设置）。**熄屏暂停 → 亮屏自动继续，链路完整。**
3. 之后任务在「回到桌面」步骤上反复尝试（导航栏查找失败、shell 命令被拒），属模型能力问题，
   与本改动无关，验证目的已达后取消（`run-090428-events.jsonl` 全程留档）。

限制：本机带 PIN 锁且 adb 解不开，「锁屏→输 PIN 解锁→自动继续」的端到端链路无法在本机完成
（本 ROM 短暂熄屏未触发 PIN 键锁，上面实测的是熄屏/亮屏变体）；该场景由
`screenOnAndUnlockSeenWithoutAnyBroadcast` 用虚拟时钟确定性覆盖（广播全丢也恢复）。

截图 `01-task-running-after-resume.png`：恢复运行中的聊天界面（状态判读以 API/事件流为准）。

## 过程记录

- 验证中发现并顺带修复了本机测试环境的坑：给 App 换 providers.json 做实验时，应用会在运行中
  重写 provider-keys.json 并丢弃不在当前配置里的服务商密钥——**换 providers 必须连同
  provider-keys.json 一起备份/恢复**，否则真实模型通道报「API Key 无效」。
  （该坑与本 PR 无关，已记入本地 AGENTS.md 流程。）
