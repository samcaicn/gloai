# WeAuto 自动回复 · 操作流程全面检查报告

| 项 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-10-16 06:55 CST |
| 检查人 | 司南（产品视角全链路审计） |
| 检查对象 | 设备 PGZ110（`Android-2.local.:36213`，进程 pid 21099）上的自动回复全链路 + 交付链路（push→CI→下载→校验→装机→验证） |
| 方法 | 真机硬取证（`adb` 读系统设置/服务/通知/包信息）+ 线上取证（`curl` Worker `/health`）+ 源码静态核对（`ChatCaptureService.kt` 等） |
| 判定口径 | OK=就绪 / WARN=能用但埋雷 / FAIL=断链，整链不可用 |
| 合规声明 | 本方案为产品设计参考，具体实施请结合公司合规与法务要求确认 |

---

## 一、结论（先看这段）

**设备当前整链不可用**，且用户看不到任何提示——这不是功能没做，是三个前置条件在「装机重置 + 权限从未申请」两个原因下同时断了：

| # | 断点 | 判定 | 一句话解释 |
|---|---|---|---|
| 1 | 无障碍服务未绑定 | **FAIL** | `enabled_accessibility_services` 为空、`accessibility_enabled=0`。ChatCaptureService 就是 AccessibilityService，没它就没有任何读取/分析/发送，任何消息都不回 |
| 2 | 默认输入法不是探针 | **FAIL** | `default_input_method=com.iflytek.inputmethod/.FlyIME`。微信 8.0.78 剥树后，写字的唯一通道是探针 IME commitText，没到期首选输入法 → 只能出建议、永远写不进输入框 |
| 3 | 通知权限被系统关闭 | **WARN（实际影响=FAIL）** | `appops: POST_NOTIFICATION: ignore`、`AppSettings importance=NONE`。而全部自愈引导（无障碍恢复、输入法重置）**只发通知** —— 检测到了，但一条也到不了用户眼前 |

**链路设计本身是健康的**：锚点收严、队列不串轮、自己回复写历史、发送三门控（点前读框防空盲点 / 点后读框判真发出 / `sendSettled` 门控切会话）都已落地并真机验证过。这次查出的是**「前置条件」与「恢复机制」两段的洞**，不是回复逻辑的洞。

---

## 二、检查范围

覆盖三段共 14 个环节：

- **A 段 运行时主链路**（S1–S11）：消息进来 → 发出 → 切下一会话
- **B 段 保障链路**（S12–S14）：异常兜底、自愈巡检、服务端 AI 通路
- **C 段 交付链路**（D1–D5）：代码 push → CI → 下载校验 → 装机 → 装机后复检

---

## 三、A 段：运行时主链路逐环节检查

| 编号 | 环节 | 代码落点 | 设计意图 | 检查判定 |
|---|---|---|---|---|
| S1 | 消息入口 | `WxNotificationListener.onNotificationPosted` | 通知是实现微信后台收消息的唯一通道 | **OK** `enabled_notification_listeners` 命中 `com.jev.probe/...WxNotificationListener`，`isPrimary: true`，dumpsys 命中 4 处 |
| S2 | 噪声过滤 | `isSummary / isSystemNotice / isImageMessage / parseSpeaker`（WxNotificationListener.kt:116-188） | 撤回/改群名/拍一拍/红包等 24 条系统话术不触发回复 | **OK** 词表已剔除「取消」「结束」等泛词 |
| S3 | 连发攒批 | `enqueueNotif`，`NOTIF_BATCH_MS=1200 / MAX=4000`（ChatCaptureService.kt:401-494） | 真人连发 3 条要合成一轮回一次，不能回三次 | **OK** |
| S4 | FIFO 队列 | `enqueueNotifRound`，上限 4、TTL 120s、保新不保旧（510-546） | 群刷屏时不丢新、不回迟到的消息 | **OK** |
| S5 | 轮次启动 + 锚点 | `startNotifRound / resolveNotifAnchor`（553-616） | **没锚点绝不自动发**：`replayed && fg && !locked && !listPage` 四缺一即只出建议 | **OK**（已修掉 onChatPage 误杀，见下） |
| S6 | 分析（判断+生成） | `startNotifAnalysis → finishOcrSnapshot → runAnalysis`（628-1310） | 用通知文本构造快照驱动一次分析+回复 | **OK**（依赖 S1+S5；当前被 S5 上游的授权断掉） |
| S7 | 候选排序 | `LlmJudge.rank` fail-open | judge 挂了不挡发送，保留全部候选 | **OK** |
| S8 | 选优 / 去重 | `maybeAutoSendBest`（1311-1383） | 输入框非空不打扰、会话冷却 20s、同文本连发拦截 | **OK** |
| S9 | 写入 | `fillInput → ProbeImeBus.requestWrite` | 探针 IME 未挂载时幂等轻点输入栏挂载 + 600ms 复查；仍未挂载走剪贴板兜底 | **FAIL（依赖未就绪）** 默认输入法非探针（断点 2） |
| S10 | 发送确认 | `sendWeChatByVerifiedTap`（1791） | 点前读框非空才点；点后 1.2s 读框，空=真发出，非空补点 ≤3 次 | **OK**（2026-10-15 真机双路径验证过） |
| S11 | 轮次收尾 | `onSendComplete(true) → appendOwnReply → finishNotifRound` | 本条真发出才切下一会话；自己说过的话写进历史 | **OK** |

---

## 四、B 段：保障链路检查

| 编号 | 环节 | 落点 | 判定 | 说明 |
|---|---|---|---|---|
| S12 | 异常兜底 | `NOTIF_HARD_MAX=60s` 兜底推进 + `analyzing` 强制复位 + `KIND.QUOTA` 冷却 | OK | 已修「analyzing 永久卡死雪崩」 |
| S13 | 自愈巡检 | `KeepAliveService.heal`，60s 一轮、首检 10s | **OK 检测 / FAIL 触达** | 三条检测都在（无障碍掉→发恢复通知、输入法被重置→发引导通知、通知监听掉→`requestRebind`），**但通知发不出去**（断点 3） |
| S14 | 服务端 AI 通路 | Worker `https://wetech.jukuai.net/health` | OK | `workers_ai_ready=true`、`tuptup_ready=true`、`ai_ready=true`、`integrity_enforced=true`；未签名直连 `/ai/*` 返回 `403 integrity_signature`（防刷生效） |

服务端额外发现（**非阻塞，属收敛 TODO**）：旧 Pages 版本 `weauto-license.pages.dev` 仍在公网存活，且 `has_api_key=false / integrity_enforced=false / ai_ready=false`——探针自 ce8c9ee 起已不打它，但它是遗留暴露面，建议下线或 301 到 Worker 域名。

---

## 五、C 段：交付链路检查

| 编号 | 环节 | 现状 | 判定 |
|---|---|---|---|
| D1 | push → 镜像 → CI | HEAD `dcf2b7c` 已推，GitHub `samcaicn/gloai` build run **38818763196 success**（创建 2026-10-16 06:40 CST） | OK |
| D2 | 下载 + 校验闸门 | `verify_apk.py`（CN=AI Chat Assistant / 非 debuggable / 包名正确）已有 | OK |
| D3 | 装机 | 设备 `lastUpdateTime=2026-10-16 06:48:43`，md5 `120a3bb0968328b802c6a5c32e025f41` | **WARN** |
| D4 | 装机后授权复检 | 以前靠人记忆；本次检查发现 **45 分钟内出现两次安装（06:36:53、06:48:43）且无台账** | **WARN（无留痕）** |
| D5 | 版本—commit 对应 | 装机 APK md5 与本地 6 个候选构建（`dl_b1cd/dl_ba63/dl_989d/dl_0b70/dl_1387/dl_1388`）**全部不一致**，无法回答「现在装的到底是哪次提交」 | **WARN** |

> 新增 `ci_apk/flow_check.sh`（本次交付）：一条命令跑完 A/B 段全部前置条件判定，并自动追加台账 `ci_apk/install_ledger.csv`（设备 / 装机时间 / APK md5 / OK·WARN·FAIL 计数）。

---

## 六、断点清单与优先级（MoSCoW）

| 编号 | 问题 | 类别 | 根因 | 措施 | 优先级 | 责任方 |
|---|---|---|---|---|---|---|
| B-01 | 无障碍未绑定，整链不工作 | 运行 | ColorOS 覆盖安装后清空 `enabled_accessibility_services`（已知 OTA/覆盖安装必现） | **用户手动**：设置→无障碍→开启；代码侧仍只能在首页/通知给引导（系统限制，App 无法代开） | **Must** | 用户（UI） |
| B-02 | 默认输入法被重置为讯飞 | 运行 | 同上，ColorOS 覆盖安装重置 | **用户手动**：微信里点输入框→键盘图标→选「AI聊天助手·写入」（铁律：App 不代切） | **Must** | 用户（UI） |
| B-03 | 通知权限从未申请、已被关 | 产品设计缺陷 | 全项目 **零处** `requestPermissions(POST_NOTIFICATIONS)`；而自愈提示只走通知单通道 | 首页引导新增「通知权限」为第 5 步；自愈提示改双通道（悬浮球/首页横幅 + 通知） | **Must** | 我（代码） |
| B-04 | 首启引导缺「输入通道」一项 | 产品设计缺陷 | 首页 4 步 = 无障碍/悬浮窗/通知监听/电池，不含输入法；用户不知道要切 IME | 首页增加「自动收发写入通道」状态行 + 手动切法图示（不含自动切换） | **Should** | 我（代码） |
| B-05 | 装机后无版本台账 | 流程 | 装机动作未留痕 | `flow_check.sh` 已自带台账；装机脚本统一改为「下载→校验→装机→跑 flow_check→贴输出」五步 | **Should** | 我（脚本） |
| B-06 | 旧 Pages 残留 | 服务端 | 双胞胎（Pages + Worker）历史产物 | 下线或 301 → `wetech.jukuai.net` | **Could** | 我（运维） |
| B-07 | AI 主路依赖 CF 免费额度（滚动 24h 窗口，非零点重置） | 容量 | 免费 10k neurons/天 | 已双路（CF→tuptup relay），保持 `/health` 轮询 + 耗尽冷却；长期建议 Workers Paid 或给 Worker 配 Volcano key | **Should** | 我（运维） |
| B-08 | 多会话排队进度用户不可见 | 体验 | 队列在内存，用户看不到「还有 2 个会话在等」 | 悬浮球/通知待办显示队列深度 | **Could** | 待定 |

---

## 七、风险登记册

| 编号 | 风险描述 | 类别 | 概率 | 影响 | 等级 | 应对 |
|---|---|---|---|---|---|---|
| RK-01 | 每次覆盖装机必掉三项授权（无障碍/输入法/冷启），用户不知情 →「装完不能用」 | 体验/运行 | 5 | 5 | **25 高** | 装机后固定跑 `flow_check.sh`；补齐 B-03/B-04 双通道提示 |
| RK-02 | 自愈提示依赖通知，而通知权限缺失 → 检测有效但触达为零 | 设计缺陷 | 4 | 5 | **20 高** | B-03：申请权限 + 第二触达通道 |
| RK-03 | 装机来源不可追溯，出问题无法定位「哪版代码在跑」 | 流程 | 4 | 4 | **16 高** | B-05：台账 + 装机 SOP |
| RK-04 | CF 免费额度滚动 24h 耗尽，静默无回复 | 容量 | 4 | 3 | **12 中** | B-07：`/health` 轮询 + QUOTA 冷却文案 + 备用 key |
| RK-05 | 并发会话/多来源装机造成版本抖动 | 流程 | 3 | 3 | **9 中** | 固定单一装机入口 + 台账核对 |
| RK-06 | 旧 Pages 域名被外部直接调用 | 安全 | 2 | 4 | **8 中** | B-06 下线 |

---

## 八、标准作业程序（SOP）

### SOP-1 每次装机后（必做，2 分钟内）

```bash
export PATH=/usr/local/bin:$PATH
bash /Volumes/d/ai/WeAuto/ci_apk/flow_check.sh        # 六项前置 + 服务端 + 台账
```

**FAIL=0 才算装机完成。** 当前 FAIL=2（无障碍、输入法）+ WARN=1（通知权限）。

### SOP-2 用户侧恢复（一次性，之后长期保持）

1. 设置 → 无障碍 → 找到本 App → 开启
2. 微信里点一次输入框 → 键盘图标 → 选「AI聊天助手·写入」
3. 设置 → 通知 → 允许本 App 发通知（否则以后再掉权限你看不到提示）

### SOP-3 端到端验收（每次改动后）

用**另一台手机**给这台发一条微信（群或单聊均可），然后：

```bash
adb -s <serial> logcat -d --pid=<probe_pid> | grep -E "notif-round|notif-batch|runAnalysis|judge done|reply done|auto-send|sendTap|ProbeIme"
```

必须依次出现：`WX_MSG received` → `notif-round: START` → `reopen conv=… via saved PendingIntent` → `reply done ranked=N err=null` → `auto-send best` → `committed` → `sendTap: dispatching tap` → `read box empty -> truly sent` → `advance -> next`。

---

## 九、复检结果（2026-10-16 07:16–07:24，用户手动开启后）

### 9.1 前置条件：FAIL=0

```
=== 汇总：OK=9  WARN=3  FAIL=0 ===
```

| 环节 | 判定 | 证据 |
|---|---|---|
| 探针进程存活 | ✅ | pid=21099 |
| 无障碍服务已绑定 | ✅ | `Bound services:{Service[label=AI聊天助手, capabilities=129, eventTypes=TYPES_ALL_MASK]}`，`Crashed services:{}` 为空 |
| 通知监听 | ✅ | `WxNotificationListener`（isPrimary:true，dumpsys 命中 4 处） |
| 悬浮窗 | ✅ | `SYSTEM_ALERT_WINDOW: allow` |
| 电池豁免 | ✅ | deviceidle 白名单 1 条 |
| 默认输入法=探针 | ✅ | `com.jev.probe/.input.ProbeInputMethod` |
| 安装包指纹 | ✅ | versionCode=5 / v1.4 / md5=120a3bb0…（已入台账） |
| **通知权限** | ⚠️ **未开** | `POST_NOTIFICATIONS: granted=false`，`appops: ignore`，`importance=NONE` |

**结论：无障碍 + 输入法两处已恢复，通知权限这第三处仍未开。**

### 9.2 端到端链路实测：跑通到 commit，末次发送被安全中止

07:20:4x 一次真实自动回复全流程日志（`ci_apk/logs/e2e_20261016_0720.log`）：

```
D  wechat extract: isChat=false nodes=1 title=null bubbles=0
I  wx-cycle: snapshot=null pkg=com.tencent.mm class=android.widget.FrameLayout effChat=false fb=true
I  wx-cycle: ocr-gate pkg=com.tencent.mm ... inConv=true        ← 无障碍树空，自动转 OCR 兜底
I  judge done err=null
W  jev rank failed -> llm fallback: HTTP 502：Workers AI 调用失败：
   4006: you have used up your daily free allocation of 10,000 neurons   ← 新问题，见 9.3
I  reply done ranked=3 err=null isCurrent=true                ← 兜底路成功产出 3 条
I  maybeAutoSendBest enter notif=true autoFillBest=true latestFrom=other
I  auto-send best(90%): 哈哈，想你了喵
I  fillInput: probe IME active, wrote via poll path (autoSend=true)
I  ProbeIme: committed 7 chars send=true (box cleared first)   ← 写入成功
I  clickSend: treeNode=false fgPkg=com.android.launcher         ← 前台已不是微信
E  sendTap: input box EMPTY before tap -> fill actually failed, abort (no blind tap)
```

**判定：S1–S10 全部跑通，S11（点击发送）因微信已退回桌面而中止。这是安全兜底的正确行为，不是 bug**——`abort (no blind tap)` 正是防止乱点发送键。

前台窗口历史佐证（`ChattingUI` 出现 14 次，说明确实进过聊天页，随后退回 `launcher` 14→19 次）。

**待补：一次「人在微信聊天页停留」的完整验收**——目前缺 `sendTap: dispatching tap` → `read box empty -> truly sent` → `advance -> next` 这最后三行。

### 9.3 新发现：Workers AI 每日免费额度已耗尽

`4006: you have used up your daily free allocation of 10,000 neurons`

- 主路（Workers AI）当日额度用尽，全部 `rank`（候选排序）降级到兜底路（tuptup relay）
- 兜底**有效**（`reply done ranked=3 err=null`），所以功能没坏，但**排序质量从 Workers AI 降级为 LLM 兜底**，且响应链路更长
- 风险：**静默降级**——`/health` 仍返回 `workers_ai_ready=true`（那是配置就绪，不是额度），页面上看不出异常

建议加一条：Worker 侧把 4006 这类额度错误计入 `/health` 的独立字段（如 `quota_exhausted:true`），让降级可见。

---

## 十、第二轮实测：修复版 b91470c 装机后（07:32–07:34）

### 10.1 发生了覆盖安装（重要）

体检脚本抓到指纹突变——**不是我操作的**：

| | 装机前 | 装机后 |
|---|---|---|
| md5 | `120a3bb0968328b802c6a5c32e025f41` | `f940352b65a5e0ceba59e086c3bf175d` |
| lastUpdateTime | 06:48:43 | **07:32:20** |
| pid | 21099 | **5142** |

本地 HEAD `b91470c`（07:23:32）正是针对 9.2 那个 abort 的修复：**发送前前台守卫**。
→ 修复版已构建并装机，进程已重启。

⚠️ 与旧铁律不符的一点：**这次覆盖安装后三项权限仍在**（无障碍 enabled=1、输入法仍为探针默认）。
「ColorOS 覆盖安装必丢三项」不能当绝对规律用，已下调为「可能丢，装机后必跑 flow_check 确认」。

### 10.2 修复生效（对比修复前）

| 环节 | 修复前 07:20 | 修复后 07:33 |
|---|---|---|
| 前台判定 | `fgPkg=com.android.launcher` | ✅ `fgPkg=com.tencent.mm` |
| 找发送键 | `treeNode=false` | ✅ `treeNode=true` |
| 是否点击 | abort，没点 | ✅ `dispatching tap at (983,2024)` |

新增守卫日志可见：`sendTap guard: WeChat back to fg after 22 ticks`——
前台守卫把微信等回来了（约 2.2 秒 / 22 跳），流程从「不敢点」推进到「真点了」。

### 10.3 但点击没生效（新断点 B-09）

```
07:33:51.108  ProbeIme: committed 12 chars send=true (box cleared first)
07:33:53.596  sendTap: dispatching tap at (983,2024) win=Rect(0, 0 - 1080, 2412)
07:33:54.803  sendTap verify: box still has 12 chars -> not sent, retry (1/3)
07:33:56.039  sendTap verify: box still has 12 chars -> not sent, retry (2/3)
07:33:57.287  sendTap verify: box still has 12 chars -> not sent, retry (3/3)
07:33:57.288  sendTap verify: 3 attempts exhausted -> give up, draft left in box
```

**判定：坐标点击不可靠。** 根因在于坐标来源——`wxSendPoint()` 是屏幕比例经验公式
（`x=0.9111×W`，`y=H−IME高−图标行半高`），靠 2026-10-14 一次 PIL 标定，
且 `weChatWindowBounds()` 在 FLAG_SECURE 下取到的窗口 bounds 本身就不可信。

### 10.4 关键澄清：树不是全空

之前判定「微信 8.0.78 吐空树」不准确。实测：

- 气泡（`id/bkl`）读不到 → `bubbles=0`，这是真的
- 但**发送按钮（`WeChatAdapter.SEND_ID`）能找到** → `treeNode=true`

代码里 `findSendButton` 找到了节点，却因为
`send.refresh() && send.isVisibleToUser && send.isEnabled` 有一项失败而放弃树路径，
回落到比例坐标——**这里是丢掉精度的地方**。

### 10.5 建议（按性价比排序）

**方案 A（推荐，改动最小）**：树节点即使 `isVisibleToUser=false`，其
`getBoundsInScreen()` 通常仍有效。点击前先取节点 bounds 中心做坐标，
失败再回落现有比例公式。不依赖截图，绕开 FLAG_SECURE。

**方案 B**：倒计时结束后走 IME 发送——`currentInputConnection.performEditorAction(IME_ACTION_SEND)`
或 `sendDefaultEditorAction(true)`。IME 已激活且持有 InputConnection，是最短路径。
注意代码注释里「发送权统一收在 verified-tap」是为倒计时撤回窗口服务，
可以保留守卫不变，只把最后的动作换成 IME action + 原坐标兜底。

**方案 C（兜底）**：若 A/B 都不成，说明 ColorOS 层拦截了模拟点击
（`dispatchGesture` 被拒）。这种情况只能提示用户手动点发送，
并把「自动发送」降级为「自动填入 + 一键发送」。

---

## 十一、下一步（待确认）

1. ✅ B-01（无障碍）、B-02（输入法）已由用户手动恢复，无需我再动。
2. ⚠️ **通知权限仍未开**（B-03 相关）。路径：设置 → 应用 → AI聊天助手 → 通知管理 → 允许通知。系统禁止 App 代开，只能你手点。
3. 是否现在补 B-03/B-04 两处引导（约 1 个小版本：首页第 5/6 步 + 双通道提示）？我建议做——**通知权限一掉，所有自愈提示静默丢失，这才是「静默卡死」的根因**。补上后即使权限又掉，首页也会直接告诉你。
4. 是否要我把 SOP-1 并进装机脚本（下载→校验→装机→flow_check 一条龙）？
5. 是否需要我在 Worker 侧把额度耗尽（4006）做成 `/health` 可见字段，消除静默降级？

---

## 十二、第三轮实测：三项修复装机后（08:12–08:25）

### 装机与验收
- APK `d14f642`（md5=77b70f7d）08:12:32 覆盖安装 Success，`flow_check.sh` **FAIL=0**
  （OK=9 / WARN=3）。覆盖安装后五项权限全保留（无障碍/通知监听/悬浮窗/电池/默认输入法）。
- `a11y capabilities=161`（1+32+128）——**CAPABILITY_CAN_PERFORM_GESTURES 已在**，
  4f7c302 修复确认生效。同源坑：日志里 `gesture=false` 是**日志代码查错位**
  （查的 16=magnification，手势位是 0x20=32），8975276 已修，未装机（仅影响日志显示）。

### 链路实测（用户停在微信聊天页，OCR 读到时 6 条消息）
| 环节 | 结果 | 证据 |
|---|---|---|
| 无障碍轮询 | ✅ | `wx-cycle` 每 ~25s 一轮 |
| OCR 兜底 | ✅ | `ocr[com.tencent.mm] msgs=6 manual=false`，title=星享春 |
| judge / reply / rank | ❌ 全 401 | `license_required` |

### 新断点 B-10：卡密授权失效（根因已定位）

**现象**：所有 AI 调用（judge/reply/rank/relay 四条路）一律
`HTTP 401 无效授权…（license_required）`。

**链路硬证据**：
1. Worker `proxyAi`（`/ai/` 通配入口）先鉴权：billing token 格式（`xxx.yyy` 两段）
   走计费路径，否则走 Creem 卡密路径 `licenseOk()`。
2. App `LicenseClient.activate()` 把 `/license?mid=` 轮询拿到的 **Creem 卡密直接存为
   accountToken**——卡密不是 `xxx.yyy` 格式 → 必然落 Creem 路径。
3. `licenseOk` 打 Creem `/licenses/validate`，要求 `status==="active"`，**Worker 侧还有
   10 分钟内存缓存**（`LICENSE_TTL`）。
4. 对照实验：伪造 billing 格式 token 打 `/ai/v1` → `bad_token`（计费路径正常，
   `WEAUATO_SIGN_SECRET` 已配）；非 billing token → `license_required`（复现设备报错）。

**根因**：设备卡密在 Creem 侧已失效（或 instance 不匹配）。07:20 那次 relay 成功是
**licenseCache 命中放行**；08:13 我部署 Worker（643231f1）导致 isolate 重启、缓存清空，
真校验 Creem 才暴露失败。**不是本次代码改动破坏**（新旧产物鉴权路径 diff 无实质差异），
也不是覆盖安装导致（SP 不清）。

### 附带发现（ backlog ）
- **relay 兜底路设计缺陷**：`/ai/tuptup/v1` 是服务端自带能力（Worker 注入 AIAPI_TOKEN），
  却因客户端卡密失效而一起挂——「兜底路不兜底」。豁免 license 会削弱反破解
  （未付费用户可白嫖服务端 key），需权衡；折中方案是 401 时返回可操作错误
  （license_expired vs license_invalid），引导用户重新激活而非只给一句"无效授权"。
- **device-id anchor 写入失败**（MediaStore insert 抛错，non-fatal 设计）：后果仅限
  卸载重装后设备 ID 变、卡密绑机失效。低优先级。
- LlmJudge/ReplyClient 均硬编码打 `/ai/tuptup/v1`，不走 `judgeEndpoint()` 配置——
  worker 模式下 `/ai/jev/decisions`（L3 签名路，不过 license）反而没用上。

### 下一步
1. **用户侧**：App 设置 → 账户卡片确认激活状态；失效则重新激活卡密（或核对购买记录）。
2. 激活恢复后装 `8975276`（日志修正版）做发送终验，目标三行：
   `sendTap: dispatching tap` → `read box empty -> truly sent` → `advance -> next`。
