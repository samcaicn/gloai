# 应用内实时语音通话 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App 内与助手全双工实时语音对话 + 语音办事(严格安全闸)+ 输入栏/胶囊入口,一次做齐。

**Architecture:** 复用既有引擎(`VolcRealtimeClient` 双协议、`CallPlaybackQueue` 代际、`PcmRecorder` 流式、`PlaybackTickets`、`CallVoiceProfileStore`),新增 `voicecall/` 包(编排/安全闸/路由/转写/前台服务/音频路由)与全屏 Compose 通话页。参考实现只取设计语义,不取代码。

**Tech Stack:** Kotlin,coroutines/Flow,Compose,AudioRecord/AudioTrack,既有 calls/voice 底座。

**Spec:** `docs/superpowers/specs/2026-11-22-voice-call-design.md`

## Global Constraints

- 通用 AOSP,无品牌特例;代码/注释/文档/提交信息不得出现任何外部项目名称。
- 每个新用户可见字符串必须 12 个目录全齐:values、values-zh、values-b+zh+Hant、values-ar/es/fa/in/ja/ko/pt/ru/tr。新文件 `strings_voicecall.xml`。
- 回归命令:`./gradlew :core:test :app:testDebugUnitTest`(禁裸 `:app:test`,OOM)。Gradle 输出必须找 "BUILD SUCCESSFUL/FAILED",不得信管道后 `$?`。
- 金样再生成必须双过滤:`./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots`。
- minSdk 29:`AudioRecord.isClientSilenced()`(API 29)直接可用,无需能力检测;`setCommunicationDevice` 需 API 31 检测。
- 引擎零重写:`VolcRealtimeProtocol` 已有 session.create/append/update(tools)/item.create/greeting/mute/close 与全部事件常量;`CallRealtimeSession` 已有 sendPcm16/sendGreeting/setMuted/sendToolResult/close。只加缺的。
- androidTest 假引擎模式:手写实现接口的 fake 作参数传入(参照 VoiceHoldTest),不走 DI;`ChineseUi()` rule。
- TaskState 订阅:并行任务必须按 conversationId 用 `runState(id)` 取,禁直接收集全局 taskState。

## Review Focus

1. 伪造 `function_call`(无当轮转写 / 跨轮重放 / 参数与转写不匹配)必须全部拒执行、且不被消费的转写不残留——Task 3 测试。
2. 播音中助手自己的声音被服务端 VAD 当用户转写 → 回声守卫丢弃、不触发执行——Task 3/4 测试。
3. barge-in 后旧代次音频绝不入耳(invalidate 后 writer 验 `isCurrent`)——Task 4 测试。
4. 上限/来电/焦点被抢/挂断时 FGS、音频焦点、AudioTrack、WebSocket 全释放(状态机收尾幂等,外部取消不 rethrow)——Task 4 测试。
5. 进度状态行最多 12 行且不得含坐标/日程标题等细节——Task 5 测试。

---

### Task 1: PcmRecorder 参数化(inputSource / chunkMs / 静音读数)

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/voice/PcmRecorder.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/PcmRecorderTest.kt`(已存在则加例)

**Interfaces:**
- Produces: `class PcmRecorder(maxMs: Int = 60_000, streaming: Boolean = false, unbounded: Boolean = false, inputSource: Int = MediaRecorder.AudioSource.MIC, chunkMs: Int = 50) : StreamingRecorder`,新增 `val silenced: StateFlow<Boolean>`(每块读 `AudioRecord.isClientSilenced()`)。既有三参数调用方行为不变(语音输入不受影响)。

- [ ] **Step 1: 写失败测试**——inputSource/chunkMs 默认值与现行为一致(20ms 时块长 320 采样、50ms 时 800 采样);silenced 初始 false。跑 `./gradlew :app:testDebugUnitTest --tests '*PcmRecorder*' -q` 确认红(参数不存在)。
- [ ] **Step 2: 实现**——构造加两参数,块长从 `Wav.RATE / 20` 改为按 chunkMs 算;录每块后更新 `_silenced`。VOICE_COMMUNICATION 初始化失败回退 MIC 的逻辑不放这里(放 Task 4 编排层)。
- [ ] **Step 3: 跑绿** + 全量 `./gradlew :app:testDebugUnitTest --tests '*Voice*' -q` 确认既有语音路径零回归。
- [ ] **Step 4: Commit** `feat(voicecall): PcmRecorder 参数化采集源/块长/静音读数`

### Task 2: 协议补齐——mika_request 注册与 agentTools 配置

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/calls/VolcRealtimeProtocol.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/VolcRealtimeClient.kt`
- Test: `app/src/test/java/com/wangzan101/mika/calls/VolcRealtimeAgentToolsTest.kt`

**Interfaces:**
- Consumes: 现有 `enablePhoneTools`(session.update tools 帧的样板)、`VolcRealtimeConfig`、`sendToolResult`(function result 已存在,直接用)。
- Produces: `VolcRealtimeConfig` 加 `agentTools: Boolean = false`;`VolcRealtimeProtocol.mikaRequestTool(): JsonObject`(工具定义:mika_request,参数 `utterance` string 必填);SESSION_CREATED 后 agentTools=true 时自动发 session.update 注册。**spec 里的 functionResult/synthesize/response.done 不再新增**(sendToolResult/sendGreeting/AssistantAudioEnded 已覆盖)——这是盘点后的收窄裁决。

- [ ] **Step 1: 写失败测试(MockWebServer 假实时服务)**——agentTools=true 时连接后收到含 mika_request 的 session.update;false 时不发;工具定义 JSON 含 utterance 必填。跑确认红。
- [ ] **Step 2: 实现**——照 enablePhoneTools 的帧构造与发送时机;mika_request 的 description 写「把用户这句完整请求交给任务系统执行」(英文,与现有工具帧同风格)。同时:agentTools=true 时 session instructions 追加约束段(模型对每段完整话语只调一个 mika_request 并沉默、结果只复述一次)——instructions 资源进 prompts,不进代码硬编码;测试钉住 instructions 含约束段。
- [ ] **Step 3: 跑绿** `./gradlew :app:testDebugUnitTest --tests '*VolcRealtime*' -q`。
- [ ] **Step 4: Commit** `feat(voicecall): 实时会话 mika_request 工具注册与配置开关`

### Task 3: VoiceCallRequest 执行安全闸(纯逻辑)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallRequest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallRequestTest.kt`

**Interfaces:**
- Produces:
  - `class VoiceCallRequest(private val echoWindow: RingBuffer<String> /* 助手已说内容,容量 500 字 */)`
  - `fun onUserTranscript(text: String)`:登记当轮 final+complete 用户转写(每轮覆盖,只留最新)
  - `fun offer(callId: String, utterance: String): VoiceCallDecision`
  - `sealed interface VoiceCallDecision { data class Accept(val text: String) : VoiceCallDecision; data object Reject : VoiceCallDecision }`
- 规则(全部可测):①无当轮转写→Reject;②callId 已见过(含重连重放)→Reject;③utterance 归一化(去空白/标点/大小写)后与当轮转写不互为子串→Reject 且不消费转写;④utterance 与回声窗内助手内容互为子串→Reject;⑤通过→Accept(转写),转写一次性消费(再 offer 同轮 → Reject)。

- [ ] **Step 1: 写失败测试**——5 规则各正反例 + 跨轮重放 + 消费后同轮二次 offer + 回声窗 500 字环形边界。跑确认红(类不存在)。
- [ ] **Step 2: 实现 VoiceCallRequest.kt**——全部自写,归一化函数文件级 private。
- [ ] **Step 3: 跑绿** `./gradlew :app:testDebugUnitTest --tests '*VoiceCallRequest*' -q`。
- [ ] **Step 4: Commit** `feat(voicecall): 语音请求执行安全闸(防伪造/防重放/防回声)`

### Task 4: VoiceCallSession 通话编排(状态机核心)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallSession.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallAudio.kt`(AudioRecord/AudioTrack 抽象,便于 fake)
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallTone.kt`(本地两音提示音合成,纯函数出 PCM)
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallSessionTest.kt`、`VoiceCallToneTest.kt`

**Interfaces:**
- Consumes: Task 1 PcmRecorder、Task 2 agentTools、`CallRealtimeSession`、`CallPlaybackQueue`、Task 3 VoiceCallRequest。
- Produces:
  - `sealed interface VoiceCallState { data object Connecting; data object MicCheck; data class Active(val startedAt: Long); data class Ended(val reason: EndReason); data class Failed(val error: VoiceCallError, val attempt: Int) }`;`enum class EndReason { Hangup, Notification, IncomingCall, TimeLimit, Background }`;`enum class VoiceCallError { Auth, ConnectTimeout, Disconnected, RateLimited, Provider, MicSilent, AudioFocus, Playback, Busy }`
  - `class VoiceCallSession(...)`,`val state: StateFlow<VoiceCallState>`,`fun hangup()`,`fun retry()`,`val micLevel/speakerLevel: StateFlow<Float>`。
  - `VoiceCallAudio` 接口:采集/播放/焦点/模式 全部抽象,生产实现 + 测试 fake 双份。

- [ ] **Step 1: 写失败测试**——状态机全迁移(Connecting→MicCheck→Active→Ended;各 Failed 路径 attempt++;外部取消不 rethrow);RESUMED 3 秒超时;开麦校验(首块 3s 空/silenced→MicSilent);VOICE_COMMUNICATION 初始化失败回退 MIC;10 分钟上限;barge-in 后 writer 验 isCurrent 弃旧块;收尾幂等(连挂两次不崩)。提示音:720/960Hz 正弦各 65ms、包络、不计电平。跑确认红。
- [ ] **Step 2: 实现**——枚举状态机(禁布尔);回声守卫用 Task 3 的 echoWindow;问候语走 `sendGreeting`(云端合成),每会话一次;播放 writer 复用 CallPlaybackQueue(20s 上限);错误分类映射 VoiceCallError。
- [ ] **Step 3: 跑绿** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`。
- [ ] **Step 4: Commit** `feat(voicecall): 通话编排状态机与音频管线`

### Task 5: VoiceCallRoute 任务路由 + VoiceCallTranscript 转写落地

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallRoute.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallTranscript.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallRouteTest.kt`、`VoiceCallTranscriptTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `VoiceCallDecision.Accept`、`TaskState`(core)、`sendToolResult`。
- Produces:
  - `fun interface VoiceCallTaskLauncher { fun launch(goal: String): String }`(返回 conversationId;装配时接 TaskService 与 ChatViewModel 相同的任务入口)
  - `class VoiceCallRoute(launcher, runState: (String) -> StateFlow<TaskState>?, sendResult: (String, String) -> Unit)`:`suspend fun execute(callId, text)`——一次性目的地(先到先得)、按 conversationId 订阅 runState、打断取消已启动未完成任务、结果回 sendResult。
  - `fun TaskState.toVoiceLine(): String?`——短状态行(≤40 字,剥坐标/日程标题等细节);状态行缓冲上限 12 行。
  - `class VoiceCallTranscript`:`fun onUser(text)`/`fun onAssistant(text)`;`fun turns(): List<Turn>`;`data class Turn(val question: String, val answer: String, val at: Long)`;会话结束 finally(NonCancellable)批量写回发起会话。

- [ ] **Step 1: 写失败测试**——路由先到先得(两个 callId 竞争只有一个执行);状态行 12 行上限与细节剥离;任务 Held/WaitingConfirmation → 「去任务页看」;打断 → 取消未完成;Succeeded → 结果文本回 sendResult;转写聚合问/答/时间、写回一次(重复结束不重复写)。跑确认红。
- [ ] **Step 2: 实现**——全部自写;写回会话走与聊天相同的存储入口。
- [ ] **Step 3: 跑绿** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`。
- [ ] **Step 4: Commit** `feat(voicecall): 语音任务路由与转写落地`

### Task 6: VoiceCallService(麦克风前台服务)+ VoiceCallAudioRoute(免提)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallService.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallAudioRoute.kt`
- Modify: `app/src/main/AndroidManifest.xml`(服务声明,foregroundServiceType="microphone")
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallAudioRouteTest.kt`

**Interfaces:**
- Produces: `VoiceCallService`(CATEGORY_CALL ongoing 通知 + 挂断 action;`select` 竞速通话协程与通知挂断);`enum class RouteDevice { Speaker, Earpiece, Wired, Bluetooth }`;`fun chooseRoute(available: List<RouteDevice>, sdkInt: Int, speakerRequested: Boolean): RouteDevice`(纯函数:有线/蓝牙优先于听筒;无私密设备留扬声器);`class VoiceCallAudioRoute`(API 31+ setCommunicationDevice,以下 speakerphone;AudioDeviceCallback 热插拔;挂断恢复)。

- [ ] **Step 1: 写失败测试**——chooseRoute 全组合(仅扬声器/听筒/有线/蓝牙/全在场 × 请求免提与否 × SDK 30/31)。跑确认红。
- [ ] **Step 2: 实现**两文件 + Manifest。
- [ ] **Step 3: 跑绿** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`。
- [ ] **Step 4: Commit** `feat(voicecall): 麦克风前台服务与免提音频路由`

### Task 7: VoiceCallOverlay 全屏 UI + 入口 + 字符串

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/ui/voicecall/VoiceCallOverlay.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/ui/ChatScreen.kt`(输入栏听筒按钮)
- Modify: `app/src/main/java/com/wangzan101/mika/capsule/CapsuleController.kt` + `CapsuleCardContent.kt`(菜单「语音聊天」)
- Modify: `app/src/main/java/com/wangzan101/mika/ui/MainActivity.kt`(EXTRA_OPEN_VOICE_CALL 直开)
- Create: `app/src/main/res/values*/strings_voicecall.xml`(×12 目录)
- Test: `app/src/androidTest/java/com/wangzan101/mika/ui/voicecall/VoiceCallOverlayTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `VoiceCallState`/`micLevel/speakerLevel`、Task 5 的状态行流、Task 6 的免提。
- Produces: `@Composable fun VoiceCallOverlay(session: VoiceCallSession, route: VoiceCallRoute, audioRoute: VoiceCallAudioRoute, onCollapse: () -> Unit)`——全屏 Dialog(`usePlatformDefaultWidth=false`,`decorFitsSystemWindows=false`,点外部不收起),不透明深色;呼吸球自绘(随电平,复用 VoiceWave 语言但自绘尺寸参数化,不抄 22dp 固定版);状态行滚动 ≤12;mm:ss;红挂断;免提;折叠;文字输入框(打字与说话同路,走 session 文本通道);权限按钮流(请求/去设置/去配置)。

- [ ] **Step 1: androidTest 冒烟(假 runCall 注入,参照 VoiceHoldTest 手写 fake)**——连接→听→播音动画→打断→挂断→重试→上限全链;权限拒绝→去设置按钮。
- [ ] **Step 2: 实现 UI + 两个入口 + EXTRA 直开**。
- [ ] **Step 3: 字符串 ×12 目录补齐(问候语本地化)**。
- [ ] **Step 4: 跑** `./gradlew :app:testDebugUnitTest -q` + `./gradlew :app:assembleDebugAndroidTest -q`(androidTest 编译过;真机跑留给验收)。
- [ ] **Step 5: Commit** `feat(voicecall): 全屏通话页与输入栏/胶囊入口`

### Task 8: 全量回归 + 真机验收清单

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest --console=plain -q` 全绿(找 BUILD SUCCESSFUL);TerminalHubTest 单挂且 diff 不碰 app/terminal 时按已知环境抖动处理。
- [ ] **Step 2:** 金样检查:`./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -q`(无提示词改动则零 diff)。
- [ ] **Step 3:** 写 `docs/superpowers/acceptance/2026-11-22-voice-call-device-matrix.md`:扬声器/听筒/蓝牙 × 打断/回声、isSilenced、热插拔、来电、焦点被抢、锁屏、语音办事全链路、防误执行四项(伪造/重放/回声/跨轮)——逐项 checkbox,真机验证时逐项勾。
- [ ] **Step 4: Commit** `test(voicecall): 全量回归与真机验收清单`
