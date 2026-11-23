# 语音级联（听 → 想 → 说）实施计划

原型：`docs/design/prototypes/2026-11-23-voice-listen-speak.html`（已获批）。

## 目标

- 应用内语音通话和 AI 接打电话默认都走「听（ASR）→ 想（文字 LLM）→ 说（TTS）」，实时语音模型降为高级选项。
- 服务商 → 模型两级：一个服务商填一次凭据，带出它的听 / 说 / 实时模型。
- 内置十余家服务商，只填各自需要的字段。

## 接入点

两种通话都只认 `CallRealtimeSession`（PCM 进，`VolcRealtimeEvent` 出）：

- AI 电话：`CallRuntime.runWork` → `RealtimeSelectionController.callProvider()` → `provider.connect(...)` → `CallPcmBridge`。
- 语音通话：`VoiceCallOverlay` 的 `connect` → `VoiceCallSession`（要 `mika_request` 工具）。

级联做成另一个 `CallRealtimeSession` 实现，由 provider 工厂按「通话方式」选出，`CallRuntime` / `CallPcmBridge` / `VoiceCallSession` 的事件处理不改。

## 模块（`voice/cascade/`）

| 类 | 职责 |
|---|---|
| `FeedRecorder` | `StreamingRecorder` 实现，PCM 由外部推入；让现有所有 STT 会话（上传式、豆包流式、FunASR、百炼）都能吃通话音频 |
| `Pcm16` | 字节/采样互转、线性重采样、WAV 解析 |
| `UtteranceDetector` | 按音量切话：自适应噪声底、开口判定、静音 N 秒收尾、单句上限、前导缓冲 |
| `CascadeListener` | 每句话开一个 STT 会话并喂音频，收尾时 release，拿 Final |
| `SentenceSplitter` | 按逗号句号切句，供逐段合成 |
| `CascadeSpeaker` | 逐段 TTS → PCM → 24 kHz，`Audio` 事件；可被打断 |
| `CascadeBrain` | 「想」：统一用 `LlmBrain`（聊天模型 + 滚动历史 + 工具循环）。电话给它 `send_dtmf`；应用内给它 `mika_request`——路由回来的结果是写给模型看的英文指令，必须再过一遍模型才能说出口，所以应用内不做原样转读 |
| `CascadeVoiceSession` | 编排上面几块，产出与实时模型相同的事件序列；粗略打断 |
| `CascadeEngine` | 按选择组装听 / 说 / 想，给两种通话用 |

事件约定：用户开口 `UserSpeaking`，转写 `UserTranscript(final, complete, current)`；回答 `AssistantAudioStarted` → `AssistantTranscript` → `Audio`… → `AssistantTextEnded` → `AssistantAudioEnded`；打断 `AssistantInterrupted`；`sendGreeting(text)` 原样合成。

## 目录与选择

`VoiceSelections` 增加：

- `engine`（标准 / 实时，默认标准）
- `think`（轻模型 / 主模型）
- `silenceMs`（默认 1500）
- `bargeIn`（默认开）
- 电话专用：`callEngine`、`callStt`、`callTts`、`callTtsVoice`；为 null 时跟随

## 服务商（`voice/vendors/`）

`VoiceVendors` 注册表：每家给出字段、模型（听 / 说 / 实时）和默认音色，保存时生成 `VoicePresetPlan`。

多字段凭据映射到现有 `VoiceCredentials` 三个槽，不改存储格式：

- 腾讯：SecretId → appId，SecretKey → apiKey
- 讯飞：APPID → appId，APIKey → apiKey，APISecret → accessKey
- Azure：区域写进 binding.region

新协议：

| 能力 | 协议 |
|---|---|
| 听 | ElevenLabs、Deepgram、Azure 短音频、腾讯一句话识别、讯飞听写 |
| 说 | MiniMax T2A、火山 TTS、百炼千问 TTS、ElevenLabs、Deepgram、Azure、腾讯、讯飞 |

OpenAI 兼容的几家（Groq、硅基流动、智谱）直接用 `AudioTranscriptions` / `OpenAiSpeech` 加 endpoint。

## 界面

- 语音：首页 ① 听 / 说 / 通话方式 / 语音服务；② 听、③ 说、④ 通话方式、⑤ 实时模型、⑥ 服务商列表、⑦ 服务商详情、⑧ 添加、⑨ 填凭据、⑩ 结果。
- 电话：⑪ 接电话 / 打电话 / 记录三标签加 ⚙、就绪卡；⑫ 通话中听想说状态；⑬ 准备清单；⑭ 设置；⑮ 电话用的听和说；⑯ 通话音频；⑰ 规则；⑱ 开场白；⑲ 记录详情。
- 新字符串 12 个语言目录齐。

## 顺序

1. 目录选择与服务商注册表（JVM 测试）
2. 新协议（JVM 测试，按官方文档）
3. 级联引擎（JVM 测试：切话、切句、事件序列、打断）
4. 接进两种通话与就绪判断
5. 界面与字符串
6. 全量单测、perf 包、真机走页面与一通语音通话

## 已知限制

- 手机自带识别只能听麦克风，电话的「听」不能用它；准备清单会提示。
- 新实时协议（百炼 Omni、Gemini Live）本轮不接，实时仍是火山与 OpenAI。
- 各家接口按公开文档实现，没有真 key 的只做了请求/解析单测。
