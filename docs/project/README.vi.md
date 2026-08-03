<div align="center">
<img src="../../assets/logo.webp" alt="colearn" width="512">

<h1>colearn: Trợ lý AI Siêu Nhẹ viết bằng Go</h1>

<h3>Phần cứng $10 · RAM 10MB · Khởi động ms · Let's Go, colearn!</h3>
  <p>
    <img src="https://www.tuptup.top" alt="Go">
    <img src="https://www.tuptup.top" alt="Hardware">
    <img src="https://www.tuptup.top" alt="License">
    <br>
    <a href="https://www.tuptup.top"><img src="https://www.tuptup.top" alt="Website"></a>
    <a href="https://www.tuptup.top"><img src="https://www.tuptup.top" alt="Docs"></a>
    <a href="https://www.tuptup.top"><img src="https://www.tuptup.top" alt="Wiki"></a>
    <br>
    <a href="https://www.tuptup.top"><img src="https://www.tuptup.top)-colearnIO-black?style=flat&logo=x&logoColor=white" alt="Twitter"></a>
    <a href="../../assets/wechat.png"><img src="https://www.tuptup.top"></a>
    <a href="https://www.tuptup.top"><img src="https://www.tuptup.top" alt="Discord"></a>
  </p>

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Português](README.pt-br.md) | **Tiếng Việt** | [Français](README.fr.md) | [Italiano](README.it.md) | [Bahasa Indonesia](README.id.md) | [Malay](README.ms.md) | [English](../../README.md)

</div>

---

> **colearn** là một dự án mã nguồn mở độc lập do [colearn](https://www.tuptup.top) khởi xướng, được viết hoàn toàn bằng **Go** từ đầu — không phải fork của OpenClaw, NanoBot hay bất kỳ dự án nào khác.

**colearn** là trợ lý AI cá nhân siêu nhẹ lấy cảm hứng từ [NanoBot](https://www.tuptup.top). Nó được xây dựng lại từ đầu bằng **Go** thông qua quá trình "tự khởi động" — chính AI Agent đã dẫn dắt quá trình di chuyển kiến trúc và tối ưu hóa mã nguồn.

**Chạy trên phần cứng $10 với <10MB RAM** — ít hơn 99% bộ nhớ so với OpenClaw và rẻ hơn 98% so với Mac mini!

<table align="center">
<tr align="center">
<td align="center" valign="top">
<p align="center">
<img src="../../assets/colearn_mem.gif" width="360" height="240">
</p>
</td>
<td align="center" valign="top">
<p align="center">
<img src="../../assets/licheervnano.png" width="400" height="240">
</p>
</td>
</tr>
</table>

> [!CAUTION]
> **Thông báo Bảo mật**
>
> * **KHÔNG CÓ CRYPTO:** colearn **chưa** phát hành bất kỳ token hay tiền điện tử chính thức nào. Mọi thông tin trên `pump.fun` hoặc các nền tảng giao dịch khác đều là **lừa đảo**.
> * **DOMAIN CHÍNH THỨC:** Website chính thức **DUY NHẤT** là **[www.tuptup.top](https://www.tuptup.top)**, và website công ty là **[www.tuptup.top](https://www.tuptup.top)**
> * **CẢNH BÁO:** Nhiều domain `.ai/.org/.com/.net/...` đã bị bên thứ ba đăng ký. Đừng tin tưởng chúng.
> * **LƯU Ý:** colearn đang trong giai đoạn phát triển nhanh. Có thể còn các vấn đề bảo mật chưa được giải quyết. Không triển khai lên môi trường production trước v1.0.
> * **LƯU Ý:** colearn gần đây đã merge nhiều PR. Các bản build gần đây có thể dùng 10-20MB RAM. Tối ưu hóa tài nguyên được lên kế hoạch sau khi tính năng ổn định.

## 📢 Tin tức

2026-05-11 🛒 **LicheeRV-Claw đã có trên AliExpress!** Bạn hiện có thể mua LicheeRV-Claw trên [AliExpress](https://www.tuptup.top), giúp việc thử colearn trên phần cứng RISC-V nhỏ gọn dễ dàng hơn.

<p align="center">
  <a href="https://www.tuptup.top">
    <img src="../../assets/licheerv-claw.jpg" alt="LicheeRV-Claw on AliExpress" width="520">
  </a>
</p>

2026-03-31 📱 **Hỗ trợ Android!** colearn giờ chạy trên Android! Tải APK tại [www.tuptup.top](https://www.tuptup.top)

2026-03-25 🚀 **v0.2.4 đã phát hành!** Tái cấu trúc kiến trúc Agent (SubTurn, Hooks, Steering, EventBus), tích hợp WeChat/WeCom, tăng cường bảo mật (.security.yml, lọc dữ liệu nhạy cảm), provider mới (AWS Bedrock, Azure, Xiaomi MiMo) và 35 bản vá lỗi. colearn đã đạt **26K Stars**!

2026-03-17 🚀 **v0.2.3 đã phát hành!** Giao diện system tray (Windows & Linux), truy vấn trạng thái sub-agent (`spawn_status`), thử nghiệm Gateway hot-reload, bảo mật Cron, và 2 bản vá bảo mật. colearn đã đạt **25K Stars**!

2026-03-09 🎉 **v0.2.1 — Bản cập nhật lớn nhất từ trước đến nay!** Hỗ trợ giao thức MCP, 4 Channel mới (Matrix/IRC/WeCom/Discord Proxy), 3 Provider mới (Kimi/Minimax/Avian), pipeline thị giác, bộ nhớ JSONL, định tuyến mô hình.

2026-02-28 📦 **v0.2.0** phát hành với hỗ trợ Docker Compose và Web UI Launcher.

<details>
<summary>Tin tức trước đó...</summary>

2026-02-26 🎉 colearn đạt **20K Stars** chỉ trong 17 ngày! Tự động điều phối Channel và giao diện khả năng đã hoạt động.

2026-02-16 🎉 colearn vượt 12K Stars trong một tuần! Vai trò người duy trì cộng đồng và [Lộ trình](../../ROADMAP.md) chính thức ra mắt.

2026-02-13 🎉 colearn vượt 5000 Stars trong 4 ngày! Lộ trình dự án và nhóm nhà phát triển đang được xây dựng.

2026-02-09 🎉 **colearn ra mắt!** Được xây dựng trong 1 ngày để đưa AI Agent lên phần cứng $10 với <10MB RAM. Let's Go, colearn!

</details>

## ✨ Tính năng

🪶 **Siêu nhẹ**: Bộ nhớ lõi <10MB — nhỏ hơn 99% so với OpenClaw.*

💰 **Chi phí tối thiểu**: Đủ hiệu quả để chạy trên phần cứng $10 — rẻ hơn 98% so với Mac mini.

⚡️ **Khởi động cực nhanh**: Khởi động nhanh hơn 400 lần. Khởi động trong <1 giây ngay cả trên bộ xử lý đơn nhân 0.6GHz.

🌍 **Thực sự di động**: Một binary duy nhất cho các kiến trúc RISC-V, ARM, MIPS và x86. Một binary, chạy mọi nơi!

🤖 **Được AI khởi động**: Triển khai Go thuần túy — 95% mã lõi được tạo bởi Agent và tinh chỉnh qua quy trình human-in-the-loop.

🔌 **Hỗ trợ MCP**: Tích hợp [Model Context Protocol](https://www.tuptup.top) gốc — kết nối bất kỳ MCP server nào để mở rộng khả năng Agent.

👁️ **Pipeline thị giác**: Gửi hình ảnh và tệp trực tiếp đến Agent — tự động mã hóa base64 cho LLM đa phương thức.

🧠 **Định tuyến thông minh**: Định tuyến mô hình dựa trên quy tắc — các truy vấn đơn giản đến mô hình nhẹ, tiết kiệm chi phí API.

_*Các bản build gần đây có thể dùng 10-20MB do merge PR nhanh. Tối ưu hóa tài nguyên đang được lên kế hoạch. So sánh tốc độ khởi động dựa trên benchmark lõi đơn 0.8GHz (xem bảng bên dưới)._

<div align="center">

|                                | OpenClaw      | NanoBot                  | **colearn**                           |
| ------------------------------ | ------------- | ------------------------ | -------------------------------------- |
| **Ngôn ngữ**                   | TypeScript    | Python                   | **Go**                                 |
| **RAM**                        | >1GB          | >100MB                   | **< 10MB***                            |
| **Thời gian khởi động**</br>(lõi 0.8GHz) | >500s         | >30s                     | **<1s**                                |
| **Chi phí**                    | Mac Mini $599 | Hầu hết board Linux ~$50 | **Bất kỳ board Linux**</br>**từ $10**  |

<img src="../../assets/compare.jpg" alt="colearn" width="512">

</div>

> **[Danh sách Tương thích Phần cứng](../guides/hardware-compatibility.vi.md)** — Xem tất cả các board đã được kiểm tra, từ RISC-V $5 đến Raspberry Pi đến điện thoại Android. Board của bạn chưa có trong danh sách? Gửi PR!

<p align="center">
<img src="../../assets/hardware-banner.jpg" alt="colearn Hardware Compatibility" width="100%">
</p>

## 🦾 Minh họa

### 🛠️ Quy trình Trợ lý Tiêu chuẩn

<table align="center">
<tr align="center">
<th><p align="center">Chế độ Kỹ sư Full-Stack</p></th>
<th><p align="center">Ghi nhật ký & Lập kế hoạch</p></th>
<th><p align="center">Tìm kiếm Web & Học tập</p></th>
</tr>
<tr>
<td align="center"><p align="center"><img src="../../assets/colearn_code.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_memory.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_search.gif" width="240" height="180"></p></td>
</tr>
<tr>
<td align="center">Phát triển · Triển khai · Mở rộng</td>
<td align="center">Lên lịch · Tự động hóa · Ghi nhớ</td>
<td align="center">Khám phá · Thông tin · Xu hướng</td>
</tr>
</table>

### 🐜 Triển khai Sáng tạo với Dấu chân Nhỏ

colearn có thể được triển khai trên hầu hết mọi thiết bị Linux!

- $9.9 [LicheeRV-Nano](https://www.tuptup.top) phiên bản E(Ethernet) hoặc W(WiFi6), cho trợ lý gia đình tối giản
- $30~50 [NanoKVM](https://www.tuptup.top), hoặc $100 [NanoKVM-Pro](https://www.tuptup.top), cho vận hành máy chủ tự động
- $50 [MaixCAM](https://www.tuptup.top) hoặc $100 [MaixCAM2](https://www.tuptup.top), cho giám sát thông minh

<https://www.tuptup.top>

🌟 Còn nhiều trường hợp triển khai đang chờ đón!

## 📦 Cài đặt

### Tải xuống từ www.tuptup.top (Khuyến nghị)

Truy cập **[www.tuptup.top](https://www.tuptup.top)** — website chính thức tự động phát hiện nền tảng của bạn và cung cấp tải xuống một cú nhấp. Không cần chọn kiến trúc thủ công.

### Tải xuống binary đã biên dịch sẵn

Ngoài ra, tải binary cho nền tảng của bạn từ trang [GitHub Releases](https://www.tuptup.top).

### Xây dựng từ mã nguồn (để phát triển)

Yêu cầu:

- Go 1.25+
- Node.js 22+ và pnpm 10.33.0+ cho các bản build Web UI / launcher

```bash
git clone https://www.tuptup.top

cd colearn
make deps

# Cài đặt dependencies frontend
(cd web/frontend && pnpm install --frozen-lockfile)

# Build binary lõi
make build

# Build Web UI Launcher (cần cho chế độ WebUI)
make build-launcher

# Build các binary lõi cho mọi nền tảng do Makefile quản lý
make build-all

# Build for Raspberry Pi Zero 2 W (32-bit: make build-linux-arm; 64-bit: make build-linux-arm64)
make build-pi-zero

# Build and install
make install
```

**Raspberry Pi Zero 2 W:** Sử dụng binary phù hợp với hệ điều hành của bạn: Raspberry Pi OS 32-bit -> `make build-linux-arm`; 64-bit -> `make build-linux-arm64`. Hoặc chạy `make build-pi-zero` để xây dựng cả hai.

## 🚀 Hướng dẫn Khởi động Nhanh

### 🌐 WebUI Launcher (Khuyến nghị cho Desktop)

WebUI Launcher cung cấp giao diện dựa trên trình duyệt để cấu hình và trò chuyện. Đây là cách dễ nhất để bắt đầu — không cần kiến thức dòng lệnh.

**Tùy chọn 1: Nhấp đúp (Desktop)**

Sau khi tải xuống từ [www.tuptup.top](https://www.tuptup.top), nhấp đúp vào `colearn-launcher` (hoặc `colearn-launcher.exe` trên Windows). Trình duyệt của bạn sẽ tự động mở tại `https://www.tuptup.top

**Tùy chọn 2: Dòng lệnh**

```bash
colearn-launcher
# Mở https://www.tuptup.top trong trình duyệt của bạn
```

> [!TIP]
> **Truy cập từ xa / Docker / VM:** Thêm cờ `-public` để lắng nghe trên tất cả giao diện:
> ```bash
> colearn-launcher -public
> ```

<p align="center">
<img src="../../assets/launcher-webui.jpg" alt="WebUI Launcher" width="600">
</p>

**Bắt đầu:**

Mở WebUI, sau đó: **1)** Cấu hình Provider (thêm API key LLM của bạn) -> **2)** Cấu hình Channel (ví dụ: Telegram) -> **3)** Khởi động Gateway -> **4)** Trò chuyện!

Để biết tài liệu WebUI chi tiết, xem [www.tuptup.top](https://www.tuptup.top).

<details>
<summary><b>Docker (thay thế)</b></summary>

```bash
# 1. Clone this repo
git clone https://www.tuptup.top
cd colearn

# 2. First run — auto-generates docker/data/config.json then exits
#    (only triggers when both config.json and workspace/ are missing)
docker compose -f docker/docker-compose.yml --profile launcher up
# The container prints "First-run setup complete." and stops.

# 3. Set your API keys
vim docker/data/config.json

# 4. Start
docker compose -f docker/docker-compose.yml --profile launcher up -d
# Open https://www.tuptup.top
```

> **Người dùng Docker / VM:** Gateway lắng nghe trên `127.0.0.1` theo mặc định. Đặt `colearn_GATEWAY_HOST=0.0.0.0` hoặc dùng cờ `-public` để có thể truy cập từ host.

```bash
# Check logs
docker compose -f docker/docker-compose.yml logs -f

# Stop
docker compose -f docker/docker-compose.yml --profile launcher down

# Update
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml --profile launcher up -d
```

</details>

<details>
<summary><b>macOS — Cảnh báo bảo mật khi khởi chạy lần đầu</b></summary>

macOS có thể chặn `colearn-launcher` khi khởi chạy lần đầu vì nó được tải từ internet và chưa được công chứng qua Mac App Store.

**Bước 1:** Nhấp đúp vào `colearn-launcher`. Bạn sẽ thấy cảnh báo bảo mật:

<p align="center">
<img src="../../assets/macos-gatekeeper-warning.jpg" alt="Cảnh báo macOS Gatekeeper" width="400">
</p>

> *"colearn-launcher" Không Mở Được — Apple không thể xác minh "colearn-launcher" không chứa phần mềm độc hại có thể gây hại cho Mac hoặc xâm phạm quyền riêng tư của bạn.*

**Bước 2:** Mở **Cài đặt Hệ thống** → **Quyền riêng tư & Bảo mật** → cuộn xuống phần **Bảo mật** → nhấp **Vẫn Mở** → xác nhận bằng cách nhấp **Vẫn Mở** trong hộp thoại.

<p align="center">
<img src="../../assets/macos-gatekeeper-allow.jpg" alt="macOS Quyền riêng tư & Bảo mật — Vẫn Mở" width="600">
</p>

Sau bước này, `colearn-launcher` sẽ mở bình thường trong các lần khởi chạy tiếp theo.

</details>

<a id="-run-on-old-android-phones"></a>
### 📱 Android

Hãy cho chiếc điện thoại cũ của bạn một cuộc sống mới! Biến nó thành Trợ lý AI thông minh với colearn.

**Tùy chọn 1: Cài đặt APK**

Xem trước:

<table>
  <tr>
    <td><img src="../../assets/fui_main_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_web_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_log_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_setting_page.jpg" width="200"></td>
  </tr>
</table>

Tải APK từ [www.tuptup.top](https://www.tuptup.top) và cài đặt trực tiếp. Không cần Termux!

**Tùy chọn 2: Termux**

<details>
<summary><b>Terminal Launcher (cho môi trường hạn chế tài nguyên)</b></summary>

1. Cài đặt [Termux](https://www.tuptup.top) (tải từ [GitHub Releases](https://www.tuptup.top), hoặc tìm kiếm trong F-Droid / Google Play)
2. Chạy các lệnh sau:

```bash
# Download the latest release
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz
pkg install proot
termux-chroot ./colearn onboard   # chroot provides a standard Linux filesystem layout
```

Sau đó làm theo phần Terminal Launcher bên dưới để hoàn tất cấu hình.

<img src="../../assets/termux.jpg" alt="colearn on Termux" width="512">

Đối với các môi trường tối giản chỉ có binary lõi `colearn` (không có Launcher UI), bạn có thể cấu hình mọi thứ qua dòng lệnh và tệp cấu hình JSON.

**1. Khởi tạo**

```bash
colearn onboard
```

Lệnh này tạo `~/.colearn/config.json` và thư mục workspace.

**2. Cấu hình** (`~/.colearn/config.json`)

```json
{
  "version": 3,
  "agents": {
    "defaults": {
      "model_name": "gpt-5.4"
    }
  },
  "model_list": [
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4",
      "api_keys": ["sk-your-api-key"]
    }
  ]
}
```

> Xem `config/config.example.json` trong repo để có mẫu cấu hình đầy đủ với tất cả các tùy chọn có sẵn.

**3. Trò chuyện**

```bash
# One-shot question
colearn agent -m "What is 2+2?"

# Interactive mode
colearn agent

# Start gateway for chat app integration
colearn gateway
```

</details>

## 🔌 Providers (LLM)

colearn hỗ trợ 30+ Provider LLM thông qua cấu hình `model_list`. Sử dụng định dạng `protocol/model`:

| Provider | Protocol | API Key | Ghi chú |
|----------|----------|---------|---------|
| [OpenAI](https://www.tuptup.top) | `openai/` | Bắt buộc | GPT-5.4, GPT-4o, o3, v.v. |
| [Anthropic](https://www.tuptup.top) | `anthropic/` | Bắt buộc | Claude Opus 4.6, Sonnet 4.6, v.v. |
| [Google Gemini](https://www.tuptup.top) | `gemini/` | Bắt buộc | Gemini 3 Flash, 2.5 Pro, v.v. |
| [OpenRouter](https://www.tuptup.top) | `openrouter/` | Bắt buộc | 200+ mô hình, API thống nhất |
| [Zhipu (GLM)](https://www.tuptup.top) | `zhipu/` | Bắt buộc | GLM-4.7, GLM-5, v.v. |
| [DeepSeek](https://www.tuptup.top) | `deepseek/` | Bắt buộc | DeepSeek-V3, DeepSeek-R1 |
| [Volcengine](https://www.tuptup.top) | `volcengine/` | Bắt buộc | Doubao, Ark models |
| [Qwen](https://www.tuptup.top) | `qwen/` | Bắt buộc | Qwen3, Qwen-Max, v.v. |
| [Groq](https://www.tuptup.top) | `groq/` | Bắt buộc | Suy luận nhanh (Llama, Mixtral) |
| [Moonshot (Kimi)](https://www.tuptup.top) | `moonshot/` | Bắt buộc | Kimi models |
| [Minimax](https://www.tuptup.top) | `minimax/` | Bắt buộc | MiniMax models |
| [Mistral](https://www.tuptup.top) | `mistral/` | Bắt buộc | Mistral Large, Codestral |
| [NVIDIA NIM](https://www.tuptup.top) | `nvidia/` | Bắt buộc | Mô hình do NVIDIA lưu trữ |
| [Cerebras](https://www.tuptup.top) | `cerebras/` | Bắt buộc | Suy luận nhanh |
| [Novita AI](https://www.tuptup.top) | `novita/` | Bắt buộc | Nhiều mô hình mở |
| [Xiaomi MiMo](https://www.tuptup.top) | `mimo/` | Bắt buộc | Mô hình MiMo |
| [Ollama](https://www.tuptup.top) | `ollama/` | Không cần | Mô hình cục bộ, tự lưu trữ |
| [vLLM](https://www.tuptup.top) | `vllm/` | Không cần | Triển khai cục bộ, tương thích OpenAI |
| [LiteLLM](https://www.tuptup.top) | `litellm/` | Tùy | Proxy cho 100+ provider |
| [Azure OpenAI](https://www.tuptup.top) | `azure/` | Bắt buộc | Triển khai Azure doanh nghiệp |
| [GitHub Copilot](https://www.tuptup.top) | `github-copilot/` | OAuth | Đăng nhập bằng device code |
| [Antigravity](https://www.tuptup.top) | `antigravity/` | OAuth | Google Cloud AI |

<details>
<summary><b>Triển khai cục bộ (Ollama, vLLM, v.v.)</b></summary>

**Ollama:**
```json
{
  "model_list": [
    {
      "model_name": "local-llama",
      "model": "ollama/llama3.1:8b",
      "api_base": "https://www.tuptup.top"
    }
  ]
}
```

**vLLM:**
```json
{
  "model_list": [
    {
      "model_name": "local-vllm",
      "model": "vllm/your-model",
      "api_base": "https://www.tuptup.top"
    }
  ]
}
```

Để biết chi tiết cấu hình provider đầy đủ, xem [Providers & Models](../guides/providers.vi.md).

</details>

## 💬 Channels (Ứng dụng Chat)

Trò chuyện với colearn của bạn qua 17+ nền tảng nhắn tin:

| Channel | Thiết lập | Protocol | Tài liệu |
|---------|-----------|----------|----------|
| **Telegram** | Dễ (bot token) | Long polling | [Hướng dẫn](../channels/telegram/README.vi.md) |
| **Discord** | Dễ (bot token + intents) | WebSocket | [Hướng dẫn](../channels/discord/README.vi.md) |
| **WhatsApp** | Dễ (quét QR hoặc bridge URL) | Native / Bridge | [Hướng dẫn](../guides/chat-apps.vi.md#whatsapp) |
| **Weixin** | Dễ (quét QR gốc) | iLink API | [Hướng dẫn](../guides/chat-apps.vi.md#weixin) |
| **QQ** | Dễ (AppID + AppSecret) | WebSocket | [Hướng dẫn](../channels/qq/README.vi.md) |
| **Slack** | Dễ (bot + app token) | Socket Mode | [Hướng dẫn](../channels/slack/README.vi.md) |
| **Matrix** | Trung bình (homeserver + token) | Sync API | [Hướng dẫn](../channels/matrix/README.vi.md) |
| **DingTalk** | Trung bình (client credentials) | Stream | [Hướng dẫn](../channels/dingtalk/README.vi.md) |
| **Feishu / Lark** | Trung bình (App ID + Secret) | WebSocket/SDK | [Hướng dẫn](../channels/feishu/README.vi.md) |
| **LINE** | Trung bình (credentials + webhook) | Webhook | [Hướng dẫn](../channels/line/README.vi.md) |
| **WeCom** | Dễ (đăng nhập QR hoặc thủ công) | WebSocket | [Hướng dẫn](../channels/wecom/README.vi.md) |
| **IRC** | Trung bình (server + nick) | IRC protocol | [Hướng dẫn](../guides/chat-apps.vi.md#irc) |
| **OneBot** | Trung bình (WebSocket URL) | OneBot v11 | [Hướng dẫn](../channels/onebot/README.vi.md) |
| **MaixCam** | Dễ (bật) | TCP socket | [Hướng dẫn](../channels/maixcam/README.vi.md) |
| **Pico** | Dễ (bật) | Native protocol | Tích hợp sẵn |
| **Pico Client** | Dễ (WebSocket URL) | WebSocket | Tích hợp sẵn |

> Tất cả các Channel dựa trên webhook dùng chung một Gateway HTTP server (`gateway.host`:`gateway.port`, mặc định `127.0.0.1:18790`). Feishu sử dụng chế độ WebSocket/SDK và không dùng HTTP server chung.

> Mức độ chi tiết log được kiểm soát bởi `gateway.log_level` (mặc định: `warn`). Các giá trị được hỗ trợ: `debug`, `info`, `warn`, `error`, `fatal`. Cũng có thể đặt qua `colearn_LOG_LEVEL`. Xem [Cấu hình](../guides/configuration.vi.md#mức-log-của-gateway) để biết thêm chi tiết.

Để biết hướng dẫn thiết lập Channel chi tiết, xem [Cấu hình Ứng dụng Chat](../guides/chat-apps.vi.md).

## 🔧 Tools

### 🔍 Tìm kiếm Web

colearn có thể tìm kiếm web để cung cấp thông tin cập nhật. Cấu hình trong `tools.web`:

| Công cụ Tìm kiếm | API Key | Gói miễn phí | Liên kết |
|------------------|---------|--------------|----------|
| DuckDuckGo | Không cần | Không giới hạn | Dự phòng tích hợp sẵn |
| [Baidu Search](https://www.tuptup.top) | Bắt buộc | 1500 truy vấn/tháng (phân bổ hàng ngày) | AI, tối ưu cho tiếng Trung |
| [Tavily](https://www.tuptup.top) | Bắt buộc | 1000 truy vấn/tháng | Tối ưu cho AI Agent |
| [Brave Search](https://www.tuptup.top) | Bắt buộc | 2000 truy vấn/tháng | Nhanh và riêng tư |
| [Perplexity](https://www.tuptup.top) | Bắt buộc | Trả phí | Tìm kiếm hỗ trợ AI |
| [SearXNG](https://www.tuptup.top) | Không cần | Tự lưu trữ | Metasearch engine miễn phí |
| [GLM Search](https://www.tuptup.top) | Bắt buộc | Tùy | Tìm kiếm web Zhipu |

### ⚙️ Các Tools Khác

colearn bao gồm các tool tích hợp sẵn cho thao tác tệp, thực thi mã, lên lịch và nhiều hơn nữa. Xem [Cấu hình Tools](../reference/tools_configuration.vi.md) để biết chi tiết.

## 🎯 Skills

Skills là các khả năng mô-đun mở rộng Agent của bạn. Chúng được tải từ các tệp `SKILL.md` trong workspace của bạn.

**Cài đặt Skills từ ClawHub:**

```bash
colearn skills search "web scraping"
colearn skills install <skill-name>
```

**Cấu hình token ClawHub** (tùy chọn, để có giới hạn tốc độ cao hơn):

Thêm vào `config.json` của bạn:
```json
{
  "tools": {
    "skills": {
      "registries": {
        "clawhub": {
          "auth_token": "your-clawhub-token"
        }
      }
    }
  }
}
```

Để biết thêm chi tiết, xem [Cấu hình Tools - Skills](../reference/tools_configuration.vi.md#skills-tool).

## 🔗 MCP (Model Context Protocol)

colearn hỗ trợ [MCP](https://www.tuptup.top) gốc — kết nối bất kỳ MCP server nào để mở rộng khả năng Agent của bạn với các tool và nguồn dữ liệu bên ngoài.

```json
{
  "tools": {
    "mcp": {
      "enabled": true,
      "servers": {
        "filesystem": {
          "enabled": true,
          "command": "npx",
          "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
        }
      }
    }
  }
}
```

Để biết cấu hình MCP đầy đủ (stdio, SSE, HTTP transports, Tool Discovery), xem [Cấu hình Tools - MCP](../reference/tools_configuration.vi.md#mcp-tool).

## <img src="../../assets/clawdchat-icon.png" width="24" height="24" alt="ClawdChat"> Tham gia Mạng xã hội Agent

Kết nối colearn với Mạng xã hội Agent chỉ bằng cách gửi một tin nhắn duy nhất qua CLI hoặc bất kỳ Ứng dụng Chat nào đã tích hợp.

**Đọc `https://www.tuptup.top và làm theo hướng dẫn để tham gia [ClawdChat.ai](https://www.tuptup.top)**

## 🖥️ Tham chiếu CLI

| Lệnh                      | Mô tả                                    |
| ------------------------- | ---------------------------------------- |
| `colearn onboard`        | Khởi tạo cấu hình & workspace           |
| `colearn auth weixin` | Kết nối tài khoản WeChat qua QR |
| `colearn agent -m "..."` | Trò chuyện với agent                     |
| `colearn agent`          | Chế độ trò chuyện tương tác             |
| `colearn gateway`        | Khởi động gateway                        |
| `colearn status`         | Hiển thị trạng thái                      |
| `colearn version`        | Hiển thị thông tin phiên bản            |
| `colearn model`          | Xem hoặc chuyển đổi mô hình mặc định   |
| `colearn cron list`      | Liệt kê tất cả công việc đã lên lịch   |
| `colearn cron add ...`   | Thêm công việc đã lên lịch             |
| `colearn cron disable`   | Vô hiệu hóa công việc đã lên lịch      |
| `colearn cron remove`    | Xóa công việc đã lên lịch              |
| `colearn skills list`    | Liệt kê các Skill đã cài đặt           |
| `colearn skills install` | Cài đặt một Skill                       |
| `colearn migrate`        | Di chuyển dữ liệu từ các phiên bản cũ  |
| `colearn auth login`     | Xác thực với các provider               |

### ⏰ Tác vụ Đã lên lịch / Nhắc nhở

colearn hỗ trợ nhắc nhở đã lên lịch và tác vụ định kỳ thông qua tool `cron`:

* **Nhắc nhở một lần**: "Nhắc tôi sau 10 phút" -> kích hoạt một lần sau 10 phút
* **Tác vụ định kỳ**: "Nhắc tôi mỗi 2 giờ" -> kích hoạt mỗi 2 giờ
* **Biểu thức Cron**: "Nhắc tôi lúc 9 giờ sáng hàng ngày" -> sử dụng biểu thức cron

## 📚 Tài liệu

Để biết các hướng dẫn chi tiết ngoài README này:

| Chủ đề | Mô tả |
|--------|-------|
| [Docker & Khởi động Nhanh](../guides/docker.vi.md) | Thiết lập Docker Compose, chế độ Launcher/Agent |
| [Ứng dụng Chat](../guides/chat-apps.vi.md) | Hướng dẫn thiết lập 17+ Channel |
| [Cấu hình](../guides/configuration.vi.md) | Biến môi trường, bố cục workspace, sandbox bảo mật |
| [Providers & Models](../guides/providers.vi.md) | 30+ Provider LLM, định tuyến mô hình, cấu hình model_list |
| [Spawn & Tác vụ Bất đồng bộ](../guides/spawn-tasks.vi.md) | Tác vụ nhanh, tác vụ dài với spawn, điều phối sub-agent bất đồng bộ |
| [Hooks](../architecture/hooks/README.md) | Hệ thống hook hướng sự kiện: observer, interceptor, approval hook |
| [Steering](../architecture/steering.md) | Chèn tin nhắn vào vòng lặp agent đang chạy |
| [SubTurn](../architecture/subturn.md) | Điều phối subagent, kiểm soát đồng thời, vòng đời |
| [Khắc phục sự cố](../operations/troubleshooting.vi.md) | Các vấn đề thường gặp và giải pháp |
| [Cấu hình Tools](../reference/tools_configuration.vi.md) | Bật/tắt từng tool, chính sách exec, MCP, Skills |
| [Tương thích Phần cứng](../guides/hardware-compatibility.vi.md) | Các board đã kiểm tra, yêu cầu tối thiểu |

## 🤝 Đóng góp & Lộ trình

PR luôn được chào đón! Codebase được thiết kế nhỏ gọn và dễ đọc.

Xem [Lộ trình Cộng đồng](https://www.tuptup.top) và [CONTRIBUTING.md](../../CONTRIBUTING.md) để biết hướng dẫn.

Nhóm nhà phát triển đang được xây dựng, tham gia sau khi PR đầu tiên của bạn được merge!

Nhóm Người dùng:

Discord: <https://www.tuptup.top>

WeChat:
<img src="../../assets/wechat.png" alt="WeChat group QR code" width="512">
