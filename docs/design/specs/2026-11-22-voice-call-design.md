# 应用内实时语音通话 · 设计(全量一次做齐)

日期:2026-11-22
状态:待批准
调研底稿:会话内调研笔记(同源参考实现 PR 全链精读 + 本仓库引擎层比对;笔记不入库,结论已全部落入本规格)

## 意图与为什么

在 App 内与助手实时语音对话,**并且能语音办事**:全双工、可打断、可免提、前台服务保活;用户说的事经严格安全闸交给既有任务体系执行,进度在通话面板滚动,结果由模型复述;通话文字落回会话。
引擎层(`VolcRealtimeClient`/`CallPlaybackQueue`/`CallInputPacer`/`PcmRecorder`/`PlaybackTickets`/`CallVoiceProfileStore`)本仓库已有且比参考实现更新——**不移植对方客户端**(那是我们旧引擎的分叉,移植等于降级);增量集中在壳、安全闸、路由与 UI,约 1,600–1,900 行新码 + 600–800 行测试。
用户已拍板:不分期,一次做齐;worktree 隔离执行。

## 验收(全部门槛)

1. JVM 全绿:安全闸全分支、状态机迁移、协议帧(假服务)、路由决策、转写聚合、回声守卫、背压。
2. androidTest 冒烟:通话页全链(连接→听→播音→打断→挂断→重试→上限)+ 权限拒绝路径 + 假任务执行路由。
3. **真机音频矩阵(硬门槛)**:扬声器/听筒/蓝牙耳机 × 打断/回声;VOICE_COMMUNICATION 可用性与 AEC;isSilenced;热插拔;来电中断;焦点被抢;锁屏行为;回声守卫误拦/漏拦实测。
4. **语音办事全链路真机验证**:说一句话→执行→状态行→复述;防误执行(伪造 function_call、回声冒充指令、跨轮重放)全部不生效。

## 功能语义(UX 全链路)

- **入口**:聊天输入栏听筒按钮(与按住说话共存,进通话模式);悬浮胶囊菜单「语音聊天」显式 intent 直开。前置 = 实时语音凭据已配置(复用 AI 电话配置)+ 麦克风权限。系统数字助手入口与我们 IA 不同,本规格不做。
- **权限流**:通话面板里用户点按钮才请求 RECORD_AUDIO(不自动弹);拒绝后按钮变「去设置」跳应用详情页,ON_RESUME 重查;凭据缺失时按钮跳实时语音配置页。
- **接通**:Connecting(可取消)→ 等界面 RESUMED(3 秒未回前台报「请回到前台」)→ 麦克风型前台服务拿租约 → 申请音频焦点 → 开麦校验(首块 3 秒内非空且未被系统静音)→ 扬声器起播 → 本地两音提示音 → 每次会话只播一次问候语 → 「已连接」绿点 + mm:ss 计时 + 红色挂断钮。
- **对话**:用户说话 → 服务端 VAD 出转写 → 模型被提示词约束:对每段完整话语只调一个 `mika_request` 工具并沉默 → App 侧严格校验后交给既有聊天/工作入口执行 → 进度以短状态行滚动(最多 12 行,不含坐标/日程标题等细节)→ 结果作为 function result 回模型 → 模型只复述结果一次。面板有文字输入框,打字与说话同路。
- **打断(barge-in)**:用户一开口(服务端 speech_started)→ 播放队列代次作废 + AudioTrack pause/flush/play + 取消尚未完成的本地任务(不声称撤销已生效操作)。
- **免提**:Android 12+ `setCommunicationDevice`(有线/蓝牙耳机优先于听筒;没有私密听音设备时留扬声器),更老系统用 speakerphone 开关;选中态读系统实际通信设备,热插拔经 AudioDeviceCallback 刷新;挂断恢复原音频模式。
- **结束**:挂断钮 / 通知栏挂断 / 来电(MODE_IN_CALL/RINGTONE 或电话功能忙 → busy 错误)/ 静默挂断(通话里设定的分钟数没有新内容——没人说话、没人打字、Mika 没说话、任务没进展——就挂断,显示「X 分钟没有动静，通话已结束」,不自动重试;「通话方式」里可选 2 / 5 / 10 分钟、自定义 1–120 分钟或不自动挂断,默认 5 分钟;到点前 30 秒 Mika 先问一句还在不在,这句提醒本身不算新内容;没有总时长上限,AI 电话不受它管)/ 页面 ON_STOP 且不允许后台时收起。通话双方文字在 finally 里 NonCancellable 批量写回发起会话。
- **通话和对话的关系**(2026-11-24 定):对话是主链路,通话只是对话里的一段。
  - **聊天页**是完整的对话:通话里每说完一轮就存进当前对话(轮次带 `callId`),和打字的轮次一样一句句排开;这一段前后各一行浅色小字「📞 语音通话 · 9:41」「通话结束 · 3:12」(静默挂断的写「太久没说话」)。一句话没说的通话只留一行「语音通话 · 0:05 · 没有说话」,按接通时刻排进对话。
  - **通话页**只显示这一通:每次打开都从空白开始,顶部一行「上次通话 · 9:41 ›」点了回聊天里那一段。
  - **通话记录**:通话页右上角 ⋯ →「通话记录」列出这个对话的每一通(接通时刻、时长、没说话的注明),点一条回到聊天里那一通的开头并亮一下,不另开回放页;⋯ 里还有「通话设置」进「通话方式」。
  - 记录存在对话旁边的 `<对话>.json.calls`(接通时记一条、挂断时补上结束和原因),删对话一起删。
  - **通话页是普通的一页**:进页面栈,返回键 / 收起钮退到上一页,通话继续;此时其余每一页顶上有一条绿条「通话中 · 回到通话」和计时,点了回通话页。从通话页点任务卡进任务时间线,返回回到通话页。
- **错误 UX**:鉴权、连接超时、断连、限流、服务商错误、麦克风无输入、音频焦点失败、播放失败,各占一条独立文案 + 就地重试(attempt++ 重跑整个连接流程)。

## 音频管线与全双工(既定事实,照此实现)

麦克风 AudioRecord(16k mono PCM16,320 采样/20ms 块)→ WebSocket(实时双工服务,STT+LLM+TTS 全在服务端)→ 下行 24k mono PCM16 → 环形播放队列 → AudioTrack(USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH,MODE_STREAM,非阻塞写)。VAD 完全在服务端,客户端不做本地 VAD、不做 push-to-talk。上下行独立协程;录音 `Channel<ShortArray>(100)` 背压,连接建立期最多攒 5 秒,溢出即失败绝不静默丢采样;上行 20ms 帧按绝对 deadline pacing;播放队列上限 20 秒(#695 教训),下行单包 ≤1 秒。提示音:本地合成 720/960Hz 正弦各 65ms 淡入淡出包络,直接写播放轨,不计入电平。

## 组件清单

全部新代码按通用 AOSP 设计:公开 API 能力检测 + 回退链,不为任何机型写特例;代码/注释/文档/提交信息不出现任何外部项目名称。

1. **`voice/PcmRecorder`(+~15,改)**:`inputSource`(通话传 VOICE_COMMUNICATION 触发平台 AEC/NS,初始化失败回退 MIC)、`chunkSamples`、`isSilenced` 读数(能力检测,不支持恒 false)。现有语音输入路径行为不变。
2. **`calls/VolcRealtimeProtocol`(+~80,改)**:`mikaActions` 配置项、`mika_request` 工具注册帧、`functionResult` 帧、`synthesize` 帧(朗读复用)、`response.done` 事件解析。
3. **`calls/VolcRealtimeClient`(+~60,改)**:`VolcRealtimeConfig.agentTools`、`sendFunctionResult(callId, text)`、`synthesize(text)`、`VolcRealtimeEvent.ResponseDone`。
4. **`voicecall/VoiceCallSession`(~350,新)**:通话编排,**显式枚举状态机**(Connecting→MicCheck→Active→Ended/Error;禁布尔拼接,`ensureActive()` 区分外部取消):RESUMED 等待、焦点、开麦校验(3s 首块 + isSilenced + VOICE_COMMUNICATION→MIC 回退)、提示音、问候语一次、播放 writer(复用 CallPlaybackQueue)、打断(队列作废+pause/flush/play+通知路由取消任务)、静默挂断(见上)、8 类错误文案 + 就地重试、回声守卫(播音时与助手已说内容 500 字环形缓冲互为子串的「用户转写」丢弃,阈值可调)。
5. **`voicecall/VoiceCallRequest`(~120,新)**:执行安全闸——只有「当轮、final 且 complete」的用户转写能喂执行;`utterance` 参数与转写做归一化**单向**包含校验——utterance 必须落在当轮转写里,比转写还长(模型添了用户没说的话)一律拒(不匹配不消费该转写);callId 一次性(含重连重放);助手回声剔除;转写每轮一次性消费。按语义自行重写。
6. **`voicecall/VoiceCallRoute`(~200,新)**:语音请求→既有聊天/工作任务入口的一次性目的地路由(先到先得);订阅 TaskState 出短状态行;任务需确认/接管时提示「去任务页看」;打断取消已启动未完成任务;结果回 `sendFunctionResult`。
7. **`voicecall/VoiceCallTranscript`(~50,新)**:事件→Turn(问/答/时间)聚合,结束写回发起会话。
8. **`voicecall/VoiceCallService`(~130,新)**:麦克风型前台服务 + CATEGORY_CALL ongoing 通知(含挂断动作);`select` 竞速通话协程与通知挂断;Android 14+ mic FGS 只能前台起;锁屏不开通话页。
9. **`voicecall/VoiceCallAudioRoute`(~120,新)**:免提路由双路径 + 选中态读系统实际设备 + 热插拔刷新 + 挂断恢复;决策抽纯函数供 JVM 测。
10. **`voicecall/ui/VoiceCallPage`(原 VoiceCallOverlay)**:页面栈里的一页(`Route.VoiceCall`,2026-11-24 起不再是全屏 Dialog),不透明深色;右上角 ⋯(通话记录 / 通话设置,`VoiceCallRecords`);其余页面顶上的绿条在 `VoiceCallBar`;中央呼吸球随双向电平起伏(**自己画**,复用 VoiceWave/Breathing 语言,不引入第三方粒子引擎);状态行滚动区(≤12 行)+ mm:ss + 挂断 + 免提 + 折叠 + 文字输入框。
11. **入口(各 ~30,改)**:输入栏听筒按钮进通话模式;胶囊菜单「语音聊天」直开。
12. **提示词约束**:session instructions 让模型对每段完整话语只调一个 `mika_request` 并沉默、只复述结果一次;进 prompts 资源。
13. **字符串**:约 40 条新 key × 12 个目录(values + 11 locale),问候语本地化,不硬编码。

## 执行安全(移植必须守住的核心)

模型可伪造 `function_call`——**只许当轮完整转写触发执行**:final+complete 校验、参数-转写兼容校验(不匹配连转写都不消费)、callId 一次性防重连重放、回声剔除防助手声音冒充指令、转写每轮一次性消费防重复执行。宁可拒执行,不可误执行。

## 测试策略

- JVM:VoiceCallRequest 全部分支(回声/兼容/一次性/跨轮)、状态机全迁移(重试 attempt++、外部取消 vs 内部失败)、协议帧构造与解析(MockWebServer 假实时服务:注册工具/下行音频/function_call/打断)、路由决策纯函数、转写聚合、背压溢出、错误→文案映射。
- androidTest:通话页冒烟(注入假 runCall 全链)、权限拒绝路径、假任务执行→状态行→function result。
- 真机矩阵 + 办事全链路:见验收 3、4,逐项记录进 PR 描述。

## 风险与对策

- **音频路径设备差异(最大)**:AEC 质量、AudioTrack 假死、isSilenced、setCommunicationDevice 兼容性——能力检测 + 回退链,真机矩阵是验收门槛。
- **回声自激固有残留**:平台 AEC 失效时守卫是概率性的(≥4 字子串启发式,可能误拦用户重复助手的话);保守方向优先(播音时降低执行敏感度),阈值真机调。
- **权限与政策**:RECORD_AUDIO 运行时权限;后台持续录音必须挂麦克风型 FGS(Android 14+ 只能前台起);退后台 FGS 保活 + 通知挂断。
- **耗电发热**:持续 16k 采集 + 24k 播放 + 长连;音频线程 `THREAD_PRIORITY_AUDIO`;静默挂断兜底(没人用时不会一直开着;默认 5 分钟)。
- **服务端计费/限流**:按时长计费;错误文案区分「可重试」与「去配置」。
- **凭据分用途**:通话读默认实时语音档案;试听/朗读书面凭据走错 API 的坑已知晓,按用途分开存取。
- **多播放源叠音**:进程内互斥 + 代次令牌拦迟到回调(PlaybackTickets 已是正解)。
