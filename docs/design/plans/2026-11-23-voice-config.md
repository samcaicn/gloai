# 语音配置改版 实现计划

原型：`docs/design/prototypes/2026-11-23-voice-config.html`（用户 11-23 认可）。

目标：模型页「语音」一组三行（语音输入 / 朗读回答 / 语音通话）+「语音服务」；聊天账号能做的语音自动出现；接入走预设；协议只在「高级 → 自定义」出现；电话页只管电话自己的事，语音默认跟随「语音通话」。

只改迁移后的路径（`legacyMigrationComplete`）。迁移失败时旧页面原样保留。

## 现状要点

- 应用内语音通话和 AI 接打电话都读 `selections.realtime`：「跟随」本来就是现状，缺的是「电话单独指定」。
- 音色存两份：`RealtimeBinding.speaker` 和 `CallConfig.speaker`，在 `CallRuntime` 里双向同步。
- 聊天账号不会自动进目录，要在目录页手动登记一遍。
- 入口分散：`RolesSection` 的朗读卡片、`SpeechSection`、`VoiceCatalogSection`、电话页的「语音服务」/「通话配置」。

## A 数据层（纯 Kotlin，先写测试）

1. **`voice/model/ProviderVoiceOffers.kt`**：从聊天服务商推出它能提供的语音模型（只认官方地址）。
   - OpenAI（`api.openai.com`）：转写、合成、实时语音。
   - Gemini：内联识别、TTS。
   - 匹配不上的不推，用户可以走「自定义」。
   - `adopt(offer)`：按需 upsert `provider:<id>` 服务和对应模型，然后返回 ref。服务 id 和迁移用的那套保持一致。
2. **`voice/model/VoiceVoices.kt`**：每种协议可选的音色列表（TTS + 实时）。已存的自定义值保留，显示成「自定义：xxx」。
3. **电话单独指定**：`VoiceSelections` 加 `callRealtime: VoiceModelRef? = null`，为 null 时表示跟随。`RealtimeSelectionController` 加 `callCurrent()` 和 `selectForCalls()`。`CallRuntime` 改读 `callCurrent()`；应用内语音通话仍读 `current()`。

## B 语音组 + 三个用途页

1. `ModelSetupScreen`：迁移后把朗读 RoleCard、`SpeechSection` 和 `VoiceCatalogSection` 换成一个 `VoiceHubSection`，内容是三行 + 语音服务。每行写当前选择和状态（可用 / 去设置）。
2. 语音输入页：可选项 = 手机自带 + 目录里的 STT + 聊天账号推出的 offer。每项带标签（边说边出字 / 说完再转、免费 / 计费），旁边有「试一句」。下面一组是「还能接入」的预设。
3. 朗读页：TTS 选项同上，旁边是「试听」。音色改成下拉（来自 A2），语速、流式朗读、自动朗读归到「什么时候读」。
4. 新增语音通话页：实时语音选项 + 音色下拉 + 「也在用这个选择：AI 接打电话」。

## C 语音服务 + 预设接入

1. 语音服务页分两组：
   - 「来自聊天账号」：只读，按 offer 列出能力。
   - 「语音专用」：手机自带 + 目录里的 VoiceOwned / None 服务。
2. 添加页预设：火山引擎（识别和实时两栏）、阿里云百炼（1 个 Key + 地域）、OpenAI 兼容、自建识别服务。现在的目录表单挪到「高级 → 自定义」。
3. 保存后逐项测试，测试结果页每项带「用于…」按钮。

## D 电话页

1. 「语音与模型」组换成「用谁来说」一行，点进去是电话语音页：跟随 / 单独指定 + 音色。
2. 「通话配置」对话框只留开场白、默认说明、时长；音色编号和音频通道从里面移走。
3. 「这台手机」组：手机通话音频页（通道 + 双向实测），自动接听被挡住时点它直达实测。
4. 去掉电话页的密钥对话框（凭据归语音服务）。单次拨打的「这一通的语音」保留。

## E 收尾

- 新字符串补齐 12 个 values 目录。
- 单测：`./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*' ... ui.*`。
- 编 `compileDebugAndroidTestKotlin`，装真机走一遍，用 perf 包。

每个阶段单独提交。
