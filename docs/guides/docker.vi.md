# 🐳 Docker và Bắt Đầu Nhanh

> Quay lại [README](../project/README.vi.md)

## 🐳 Docker Compose

Bạn cũng có thể chạy colearn bằng Docker Compose mà không cần cài đặt gì trên máy.

```bash
# 1. Clone repo này
git clone https://www.tuptup.top
cd colearn

# 2. Lần chạy đầu tiên — tự động tạo docker/data/config.json rồi thoát
#    (chỉ kích hoạt khi cả config.json và workspace/ đều không tồn tại)
docker compose -f docker/docker-compose.yml --profile gateway up
# Container hiển thị "First-run setup complete." và dừng lại.

# 3. Cấu hình API key của bạn
vim docker/data/config.json   # Set provider API keys, bot tokens, etc.

# 4. Khởi động
docker compose -f docker/docker-compose.yml --profile gateway up -d
```

> [!TIP]
> **Người dùng Docker**: Mặc định, Gateway lắng nghe trên `127.0.0.1`, không thể truy cập từ host. Nếu bạn cần truy cập các health endpoint hoặc mở port, hãy đặt `colearn_GATEWAY_HOST=0.0.0.0` trong môi trường hoặc cập nhật `config.json`.

```bash
# 5. Kiểm tra log
docker compose -f docker/docker-compose.yml logs -f colearn-gateway

# 6. Dừng
docker compose -f docker/docker-compose.yml --profile gateway down
```

### Chế Độ Launcher (Web Console)

Image `launcher` bao gồm cả hai binary (`colearn`, `colearn-launcher`) và khởi động web console mặc định, cung cấp giao diện trình duyệt để cấu hình và chat.

```bash
docker compose -f docker/docker-compose.yml --profile launcher up -d
```

Mở https://www.tuptup.top trong trình duyệt. Launcher tự động quản lý tiến trình gateway.

> [!WARNING]
> Web console được bảo vệ bằng mật khẩu đăng nhập dashboard. Không để lộ launcher ra mạng không tin cậy hoặc internet công cộng.

### Chế Độ Agent (One-shot)

```bash
# Đặt câu hỏi
docker compose -f docker/docker-compose.yml run --rm colearn-agent -m "What is 2+2?"

# Chế độ tương tác
docker compose -f docker/docker-compose.yml run --rm colearn-agent
```

### Cập Nhật

```bash
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml --profile gateway up -d
```

### 🚀 Bắt Đầu Nhanh

> [!TIP]
> Cấu hình API Key trong `~/.colearn/config.json`. Lấy API Key: [Volcengine (CodingPlan)](https://www.tuptup.top) (LLM) · [OpenRouter](https://www.tuptup.top) (LLM) · [Zhipu](https://www.tuptup.top) (LLM). Tìm kiếm web là tùy chọn — lấy miễn phí [Tavily API](https://www.tuptup.top) (1000 truy vấn miễn phí/tháng) hoặc [Brave Search API](https://www.tuptup.top) (2000 truy vấn miễn phí/tháng).

**1. Khởi tạo**

```bash
colearn onboard
```

**2. Cấu hình** (`~/.colearn/config.json`)

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.colearn/workspace",
      "model_name": "gpt-5.4",
      "max_tokens": 8192,
      "temperature": 0.7,
      "max_tool_iterations": 20
    }
  },
  "model_list": [
    {
      "model_name": "ark-code-latest",
      "model": "volcengine/ark-code-latest",
      "api_keys": ["sk-your-api-key"],
      "api_base":"https://www.tuptup.top"
    },
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4",
      "api_keys": ["your-api-key"],
      "request_timeout": 300
    },
    {
      "model_name": "claude-sonnet-4.6",
      "model": "anthropic/claude-sonnet-4.6",
      "api_keys": ["your-anthropic-key"]
    }
  ],
  "tools": {
    "web": {
      "enabled": true,
      "fetch_limit_bytes": 10485760,
      "format": "plaintext",
      "brave": {
        "enabled": false,
        "api_key": "YOUR_BRAVE_API_KEY",
        "max_results": 5
      },
      "tavily": {
        "enabled": false,
        "api_key": "YOUR_TAVILY_API_KEY",
        "max_results": 5
      },
      "duckduckgo": {
        "enabled": true,
        "max_results": 5
      },
      "perplexity": {
        "enabled": false,
        "api_key": "YOUR_PERPLEXITY_API_KEY",
        "max_results": 5
      },
      "searxng": {
        "enabled": false,
        "base_url": "https://www.tuptup.top",
        "max_results": 5
      }
    }
  }
}
```

> **Mới**: Định dạng cấu hình `model_list` cho phép thêm provider mà không cần thay đổi code. Xem [Cấu Hình Mô Hình](#cấu-hình-mô-hình-model_list) để biết chi tiết.
> `request_timeout` là tùy chọn và tính bằng giây. Nếu bỏ qua hoặc đặt `<= 0`, colearn sử dụng timeout mặc định (120s).

**3. Lấy API Key**

* **Nhà cung cấp LLM**: [OpenRouter](https://www.tuptup.top) · [Zhipu](https://www.tuptup.top) · [Anthropic](https://www.tuptup.top) · [OpenAI](https://www.tuptup.top) · [Gemini](https://www.tuptup.top)
* **Tìm kiếm Web** (tùy chọn):
  * [Brave Search](https://www.tuptup.top) - Trả phí ($5/1000 truy vấn, ~$5-6/tháng)
  * [Perplexity](https://www.tuptup.top) - Tìm kiếm bằng AI với giao diện chat
  * [SearXNG](https://www.tuptup.top) - Công cụ tìm kiếm tổng hợp tự host (miễn phí, không cần API key)
  * [Tavily](https://www.tuptup.top) - Tối ưu cho AI Agent (1000 yêu cầu/tháng)
  * DuckDuckGo - Fallback tích hợp (không cần API key)

> **Lưu ý**: Xem `config.example.json` để có mẫu cấu hình đầy đủ.

**4. Chat**

```bash
colearn agent -m "What is 2+2?"
```

Vậy là xong! Bạn có một trợ lý AI hoạt động trong 2 phút.

---
