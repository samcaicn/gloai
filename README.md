# tuptup 端侧助手 · 安卓原生工程

Kotlin + Jetpack Compose 原生安卓 App。**手机 GPU/NPU 跑 Jev 决策模型（ONNX Runtime），文本话术走 OpenAI 兼容 API，TTS 端侧离线播讲。**

## 架构（二开自 TIDY：Kotlin + ONNX Runtime + Room 分层）

```
app/src/main/java/com/tuptup/speech/
├── data/          Room 存储（店铺/产品/音色）+ SharedPreferences 配置
├── inference/
│   ├── BpeTokenizer.kt    HuggingFace tokenizer.json 的 ByteLevel BPE（vocab 50280）
│   └── JevEngine.kt       Verdict-151M ONNX 推理：NNAPI(GPU/NPU)→XNNPACK(CPU)→规则降级
├── network/
│   ├── LlmApi.kt          OpenAI 兼容 /chat/completions（任意自建/第三方端点）
│   └── ContextApi.kt      实时上下文：时间 + Open-Meteo 天气 + 抖音/百度热搜
├── tts/TtsEngine.kt       Android TextToSpeech（离线中文）+ 录音音色特征提取
└── ui/                    Compose 5 Tab：话术/店铺/产品/音色/设置
```

## 核心流程

1. **Jev 端侧决策**（手机 GPU，~35ms/次）：把 时间+天气+热点+店铺 状态喂给 Verdict 模型，从 6 个话术策略（迎客/天气/热点/促销/产品/周末）里带概率地选一个。模型没下载时自动规则降级，不影响使用。
2. **文本 API 生成**：设置页填 base_url + key + model（DeepSeek/通义/自建网关均可），按选定策略生成口播话术；API 失败自动本地模板兜底。
3. **端侧 TTS 播讲**：系统中文语音离线播讲，可调速；"录制专属音色"用 AudioRecord 采 8~10 秒 → 端侧提取音高/响度 → 播讲自动套用（非神经网络克隆，克隆接口已预留）。

## 编译（Android Studio 直接打开）

1. Android Studio（Ladybug+）打开本目录，Gradle 同步（AGP 8.6.1 / Kotlin 2.0.20 / JDK 17）。
2. 直接 Run 即可装机。**arm64-v8a only**，包体小、性能最佳。

## 端侧模型（606MB，首次启动 App 内下载）

`Verdict-151M`（hf-mirror 国内镜像，App 设置页一键下载，带进度条）。
也可手动放置：把 `model.onnx` 推到设备 `/data/data/com.tuptup.speech/files/verdict.onnx`。

## 关键约定（二开注意）

- **prompt 契约**（对齐 Verdict 官方 webgpu-demo）：`label_marker="<>", sep_marker="<>", input_template="Question: {question}\n\nContext:\n{context}"`，校准温度 T=1.0716。改策略时保持此格式。
- **JevEngine 单例**：session 常驻，`decide()` 跑在 Dispatchers.Default；换模型只需替换 `modelFile()` 路径与 `prompt` 三常量。
- 模型输出取 logits 前 N 个（N=候选策略数），softmax(T) 后即各策略概率。
- 热点源在部分网络会被风控，`ContextApi.fetchHotspots()` 多源尽力，失败显示"热点暂不可用"不影响生成。
