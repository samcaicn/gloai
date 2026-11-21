# 应用内实时语音通话 · 一期(通话壳)设计

日期:2026-11-22
状态:待批准
调研底稿:会话内调研笔记(同源参考实现精读 + 本仓库引擎层比对;笔记不入库,结论已全部落入本规格)

## 意图与为什么

在 App 内与助手连续多轮实时语音对话:全双工、可打断、可免提、有前台服务保活。
引擎层(`VolcRealtimeClient`/`CallPlaybackQueue`/`CallInputPacer`/`PcmRecorder`/`PlaybackTickets`)本仓库已有且比参考实现更新——**一期只做"壳"**:录音参数化、通话编排、前台服务、音频路由、全屏 UI、权限与错误流。模型只对话,**不执行任务**(无 `mika_request`,二期再做)。

## 一期的验收(三条都要)

1. JVM 全绿:状态机迁移、错误分类映射、音频路由决策、协议帧(假服务)。
2. androidTest 冒烟:注入假 runCall 跑「连接→听→播音动画→打断→挂断→重试→上限」全链 + 权限拒绝路径。
3. **真机音频矩阵(硬门槛,不过不合并)**:扬声器/听筒/蓝牙耳机 × 打断/回声;VOICE_COMMUNICATION 可用性与 AEC 效果;isSilenced 分支;热插拔;来电中断;焦点被抢;锁屏行为;回声守卫的误拦/漏拦实测。

## 范围(一期组件)

全部新代码按通用 AOSP 设计:公开 API 能力检测 + 回退链,不为任何机型写特例。

1. **`voice/PcmRecorder` 参数化(+~15 行,改)**
   加 `inputSource`(默认 MIC,通话传 VOICE_COMMUNICATION 触发平台 AEC/NS,初始化失败回退 MIC)、`chunkSamples`(默认现状 20ms)、`isSilenced` 读数(系统级静音检测,API 23+ 能力检测,不支持恒 false)。现有语音输入路径行为不变。

2. **`voicecall/VoiceCallSession`(~350 行,新包,核心)**
   通话编排,**显式枚举状态机**(Connecting→MicCheck→Active→Ended/Error;禁止布尔拼接——参考实现曾因此卡死无法重试):
   - 等界面 RESUMED 才申请音频焦点(3 秒未回前台报「请回到前台」,可重试)。
   - 开麦校验:首块音频 3 秒内非空且未 isSilenced;失败走独立错误文案。
   - 提示音:本地合成两音(720/960Hz 正弦各 65ms,淡入淡出包络,直接写播放轨,不计入电平表);问候语每次会话只播一次,文案进字符串资源(本地化,不硬编码)。
   - 播放 writer:复用 `CallPlaybackQueue`(环形缓冲 + 代次),上限 20 秒(#695 教训:个别设备 AudioTrack 假死、8 秒太小),下行单包 ≤1 秒。
   - 打断(barge-in):服务端 speech_started → 播放队列代次作废 + AudioTrack pause/flush/play;一期没有任务可取消。
   - 上限:接通后 10 分钟自动结束,显示原因,不自动重试。
   - 结束路径:挂断钮 / 通知栏挂断 / 来电(MODE_IN_CALL/RINGTONE 或电话功能忙 → busy 错误)/ 上限。
   - 错误分类:鉴权、连接超时、断连、限流、服务商错误、麦克风无输入、音频焦点失败、播放失败——各占一条独立文案 + 就地重试(attempt++ 重跑整个连接流程;`ensureActive()` 区分外部取消,不无脑 rethrow)。
   - 回声对策双保险:VOICE_COMMUNICATION 采集源(平台 AEC)+ 应用层回声守卫(播音时与助手已说内容 500 字环形缓冲互为子串的「用户转写」判为回声丢弃;阈值留可调,真机矩阵实测)。
   - 录音通道背压:`Channel<ShortArray>(100)`,连接建立期最多攒 5 秒,绝不静默丢采样,溢出即失败。

3. **`voicecall/VoiceCallService`(~130 行)**
   `FOREGROUND_SERVICE_TYPE_MICROPHONE` 前台服务 + CATEGORY_CALL ongoing 通知(含挂断动作);`select` 竞速通话协程与通知挂断;Android 14+ mic FGS 只能前台起(权限流保证);退后台由 FGS 保活,锁屏不开通话页。

4. **`voicecall/VoiceCallAudioRoute`(~120 行)**
   免提路由:Android 12+ `setCommunicationDevice`(有线/蓝牙耳机优先于听筒;没有私密听音设备时留扬声器),更老系统 speakerphone 开关;选中态读系统实际通信设备,热插拔经 AudioDeviceCallback 刷新;挂断恢复原音频模式。路由决策抽成纯函数供 JVM 测。

5. **`ui/voicecall/VoiceCallOverlay`(~350 行)**
   Compose 全屏 Dialog(`usePlatformDefaultWidth=false`、`decorFitsSystemWindows=false`、点外部不收起),不透明深色;中央呼吸球随麦克风/扬声器电平起伏——**自己画**(复用 VoiceWave/Breathing 设计语言;不引入参考实现的第三方粒子引擎);状态行 + mm:ss 计时 + 红色挂断钮 + 免提钮 + 折叠钮。一期不做文字输入框(二期随任务路由一起来)。

6. **权限与凭据流**
   通话页里用户点按钮才请求 RECORD_AUDIO(不自动弹);拒绝后按钮变「去设置」跳应用详情页,ON_RESUME 重查;实时语音凭据缺失时按钮跳实时语音配置页(复用 AI 电话的 `CallVoiceProfileStore` 默认档案;鉴权失败指去该页)。

7. **一期入口(最小)**
   设置页语音区加一行「实时语音通话(早期)」入口,够真机矩阵用;输入栏/胶囊/助手页入口是三期。

8. **字符串**
   约 40 条新 key × 12 个目录(values + 11 locale),问候语也本地化。

## 复用(不写新码)

`VolcRealtimeClient` 主体(双工、转写、打断事件)、`CallPlaybackQueue`、`CallInputPacer`、`PlaybackTickets`、`CallVoiceProfileStore`。协议层一期零改动(mikaActions/functionResult 都是二期)。

## 测试策略

- JVM:VoiceCallSession 状态机全迁移(含重试 attempt++、外部取消 vs 内部失败)、错误→文案映射、路由决策纯函数(设备优先级/热插拔/恢复)、回声守卫(子串命中/跨轮/500 字环形边界)、背压溢出即失败。协议帧用 MockWebServer 假实时服务。
- androidTest:通话页冒烟(假 runCall 全链)、权限拒绝→去设置路径。
- 真机矩阵:见验收第 3 条,逐项记录结果进 PR 描述。

## 非目标(一期不做)

- `mika_request` 任务执行与路由、转写写回会话(二期)。
- 胶囊/助手页/锁屏入口、通话内文字输入、粒子球视觉(三期)。
- 任何机型特例代码;任何外部项目名称出现在代码/注释/文档/提交信息。

## 风险与对策

- **音频路径设备差异(最大)**:AEC 质量、AudioTrack 假死、isSilenced、setCommunicationDevice 兼容性——能力检测 + 回退链,真机矩阵是验收门槛。
- **回声自激固有残留**:平台 AEC 失效时启发式守卫是概率性的(可能误拦用户重复助手的话);保守方向优先(播音时降低执行敏感度——一期无执行,只影响转写显示),阈值真机调。
- **耗电发热**:持续 16k 采集 + 24k 播放 + 长连;音频线程 `THREAD_PRIORITY_AUDIO`;10 分钟上限兜底。
- **服务端计费/限流**:错误文案区分「可重试」与「去配置」。
