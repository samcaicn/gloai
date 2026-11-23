# 豆包流式语音识别 2.0

## 入口与配置

设置 › 模型 › 语音转文字里「边说边转的语音服务」一组只有一行「豆包流式语音识别模型 2.0」：没存凭据时标「未设置」，点进单独的设置页（`app/voice/ui/SpeechModelScreen.kt`）；存了以后点圆圈就选用，右边「设置」进去改凭据、换计费方式或删除。设置页顶上分段切换新版控制台（语音服务 API Key）和旧版控制台（App ID + Access Token），计费方式选小时版 `volc.seedasr.sauc.duration` 或并发版 `volc.seedasr.sauc.concurrent`，需要账号先开通对应服务。只换计费方式、不重填凭据时沿用已存的凭据。方舟聊天服务商、聊天模型列表及其凭据不参与此流程。

保存只在本机加密存储，不调用在线服务，也不把配置标为验证通过。按住麦克风才建立连接并发送音频，可能产生服务费用。凭据可单独删除；删除当前使用的豆包凭据时关闭语音输入。系统识别、已有的整段音频转写保持各自路径，不自动切换到收费服务。

凭据使用现有 `KeyCipher` / Android Keystore 独立 alias 加密，选择 JSON 只含资源 ID。输入框不回填密钥，不使用可持久化的 Compose 状态。诊断只记录引擎及资源 ID；服务端错误消息不直接展示，避免意外回显凭据或音频内容。

## 实时路径

`SpeechChoice.Doubao` → `SpeechResolver` → `VoiceInput` → `DoubaoSpeech`。聊天输入和悬浮胶囊共用同一条入口。

- `PcmRecorder(streaming = true)` 实时提供 16 kHz、单声道、PCM16 采样，原有非流式模式仍可返回整段录音。
- `DoubaoTransport` 使用独立 OkHttp WebSocket 客户端连接 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`。凭据只发往固定官方端点；生产客户端不跟随重定向。
- 握手后先发送 gzip JSON 初始化，等服务端首个确认，再发送音频。50 ms 采样块合并为 200 ms 音频包；缓存音频也按 200 ms 间隔发送。PCM 使用小端，协议长度和可选序号使用大端。
- 音频发送与结果接收并行。请求 `result_type=full`，每个 `Partial` 覆盖之前全文，不拼接造成重复。`definite` 是分句稳定标志，不能作为整个会话结束条件。
- 松开时停止录音，保留已排队的采样，发送最后一包（flag 2，包含不足 200 ms 的尾音，或者空终包），继续等服务端 final flag/负序号，再发 `Final`。断开但没收到终包会失败，不把未完整的文本当最终结果。
- 取消会关闭事件、音频流和连接，抑制迟到结果。录音自动结束同样发送终包。握手/初始化各 10 秒，终包发出后等待最多 10 秒，总会话最多 75 秒；原有 UI 的按住录音上限仍为 60 秒。
- 音频队列最多约 5 秒，网络发送队列和响应队列也有上限。超过上限时失败并释放麦克风，不静默丢音。gzip 解码后的响应限制为 1 MiB。

不发送设备 IMEI/MAC、历史聊天或图片。双向接口按服务默认识别能力处理语言，不把仅单向接口支持的 `audio.language` 硬塞进去。

## 验证与限制

新增 `DoubaoProtocolTest`、`DoubaoSpeechTest`、`DoubaoTransportTest`、`DoubaoSettingsTest`，覆盖协议样例、实时上传、全文替换、尾包、超时、取消、两代请求头、鉴权失败、AES/GCM 凭据保存/恢复/删除。所有 fixture 为模拟采样和虚假凭据；WebSocket 测试只连接本机 MockWebServer。

正常 Android 开发环境可运行：

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'
./gradlew :app:assembleDebug
```

真实火山账号的权限、收费、延迟和准确率需要有账号的人验证；没有账号时只能确认设置页、保存与删除凭据和失败提示。

## 资料与实现来源

2026-11-01 核对的官方协议：

- [大模型流式语音识别 API（二进制帧）](https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh)
- [实时语音识别（当前参数与 2.0 资源）](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)
