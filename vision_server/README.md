# vision_server — 本地视觉语义引擎（llama.cpp，可选）

给 WeAuto「本地视觉语义识别」引擎提供后端服务（OpenAI 兼容接口）。
仅当设置页勾选 `LOCAL_VISION_ENABLE` 时才需要本目录；纯文字识别走
本地 OCR（RapidOCR），无需本目录。

## 全自动装机（用户侧）

bot 启动时（勾选 LOCAL_VISION_ENABLE）会起**预取守护线程**自动准备组件，
按安装者的显卡自适应，全程不阻塞消息收发：

| 机器情况 | llama-server 分发包 | GPU 卸载 |
|---|---|---|
| NVIDIA / AMD / Intel 显卡 | `llama-server-vulkan-win64.zip` | `-ngl 99` 全层卸载 |
| 无显卡 | `llama-server-cpu-win64.zip` | `-ngl 0` 纯 CPU |

模型自动从 hf-mirror 断点续传下载（HunyuanOCR-1.5 Q4_K_M 338MB +
mmproj 699MB，约 1GB，多源重试）。**下载就绪前识别自动回落云端**，
就绪后新图片即走本地。组件就绪判定用 `/health`（加载中 503 不算就绪）。

## 运行时分发源（全公开，无需上传/认证）

`local_vision.py` 的 `VISION_BIN_URLS` 按序尝试三源（固定官方预编译 b9637，MIT）：

1. **hf-mirror 镜像**（国内主源，实测 7s 下完 36MB）：
   `hf-mirror.com/limnmn/llama.cpp-b9637-Windows-Runtime`
2. **官方 release**（海外/代理兜底）：
   `github.com/ggml-org/llama.cpp/releases/download/b9637`
3. **自家 CI 的 Release 资产**（最后兜底）：
   `github.com/samcaicn/gloai/releases/download/vision-bin`
   —— 由 `.github/workflows/vision-bin.yml` 每月自动重建（cron + 手动）。
   注意 GitHub **Actions artifact 匿名下载是 401**（只有列表匿名可读），
   所以发布目标是 **Release 资产**（真正匿名只读）。

zip 解压时自动提取其中全部 `.exe`/`.dll` 到本目录；也可把 zip 预放
`packages/` 目录用作离线安装（优先于下载）。

## 本目录结构

- `models/` — GGUF 模型与 mmproj（自动下载，gitignored）
- `packages/` — llama-server 分发 zip（下载缓存/离线安装，gitignored）
- `llama-server.exe` + `*.dll` — 运行时（自动下载解压，gitignored）
- `start_vision.bat` — 手动一键启动（默认 `127.0.0.1:8080`，默认加载 HunyuanOCR）

## 手动下载模型（国内源，断点续传）

已预下载：

- **HunyuanOCR-1.5 Q4_K_M**（338MB）+ `HunyuanOCR.mmproj-q8_0.gguf`（699MB）—— OCR 专用，精度最高（OmniDocBench 领先），**默认优先**。
- Qwen3-VL-2B-Instruct Q4_K_M（约 1.1GB）+ `mmproj-F16.gguf` —— 通用视觉语义（表情包情绪、截图场景理解）兜底。

`start_vision.bat` 默认加载 HunyuanOCR；若缺失则自动回退到目录内第一个主模型。

需要单独下载时（国内源，断点续传）：

```bat
rem HunyuanOCR-1.5（默认，OCR 精度优先）
curl -L -C - -o models\HunyuanOCR.Q4_K_M.gguf "https://hf-mirror.com/prithivMLmods/HunyuanOCR-1.5-GGUF-Updated/resolve/main/HunyuanOCR.Q4_K_M.gguf"
curl -L -C - -o models\HunyuanOCR.mmproj-q8_0.gguf "https://hf-mirror.com/prithivMLmods/HunyuanOCR-1.5-GGUF-Updated/resolve/main/HunyuanOCR.mmproj-q8_0.gguf"
rem Qwen3-VL-2B（通用语义兜底）
curl -L -C - -o models\Qwen3-VL-2B-Instruct-Q4_K_M.gguf "https://hf-mirror.com/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3-VL-2B-Instruct-Q4_K_M.gguf"
curl -L -C - -o models\mmproj-F16.gguf "https://hf-mirror.com/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"
```

## 验证

```bash
curl http://127.0.0.1:8080/v1/models
curl http://127.0.0.1:8080/health   # {"status":"ok"} 才是真就绪
```
