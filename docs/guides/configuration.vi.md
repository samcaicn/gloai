# ⚙️ Hướng Dẫn Cấu Hình

> Quay lại [README](../project/README.vi.md)

## ⚙️ Cấu Hình

File cấu hình: `~/.colearn/config.json`

### Biến Môi Trường

Bạn có thể ghi đè các đường dẫn mặc định bằng biến môi trường. Điều này hữu ích cho cài đặt portable, triển khai container, hoặc chạy colearn như dịch vụ hệ thống. Các biến này độc lập và kiểm soát các đường dẫn khác nhau.

| Biến              | Mô tả                                                                                                                             | Đường Dẫn Mặc Định       |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------|---------------------------|
| `colearn_CONFIG` | Ghi đè đường dẫn đến file cấu hình. Chỉ định trực tiếp cho colearn file `config.json` nào cần tải, bỏ qua tất cả vị trí khác. | `~/.colearn/config.json` |
| `colearn_HOME`   | Ghi đè thư mục gốc cho dữ liệu colearn. Thay đổi vị trí mặc định của `workspace` và các thư mục dữ liệu khác.          | `~/.colearn`             |

**Ví dụ:**

```bash
# Chạy colearn với file cấu hình cụ thể
# Đường dẫn workspace sẽ được đọc từ trong file cấu hình đó
colearn_CONFIG=/etc/colearn/production.json colearn gateway

# Chạy colearn với tất cả dữ liệu lưu tại /opt/colearn
# Cấu hình sẽ được tải từ mặc định ~/.colearn/config.json
# Workspace sẽ được tạo tại /opt/colearn/workspace
colearn_HOME=/opt/colearn colearn agent

# Sử dụng cả hai cho thiết lập tùy chỉnh hoàn toàn
colearn_HOME=/srv/colearn colearn_CONFIG=/srv/colearn/main.json colearn gateway
```

### Mức Log của Gateway

`gateway.log_level` kiểm soát mức độ chi tiết của log Gateway, có thể cấu hình trong `config.json`:

```json
{
  "gateway": {
    "log_level": "warn"
  }
}
```

Giá trị mặc định là `warn`. Các giá trị được hỗ trợ: `debug`, `info`, `warn`, `error`, `fatal`.

Cũng có thể ghi đè bằng biến môi trường: `colearn_LOG_LEVEL=info`

### Bố Cục Workspace

colearn lưu trữ dữ liệu trong workspace đã cấu hình (mặc định: `~/.colearn/workspace`):

```
~/.colearn/workspace/
├── sessions/          # Phiên hội thoại và lịch sử
├── memory/           # Bộ nhớ dài hạn (MEMORY.md)
├── state/            # Trạng thái bền vững (kênh cuối, v.v.)
├── cron/             # Cơ sở dữ liệu tác vụ lên lịch
├── skills/           # Skill tùy chỉnh
├── AGENT.md          # Hướng dẫn hành vi agent
├── HEARTBEAT.md      # Prompt tác vụ định kỳ (kiểm tra mỗi 30 phút)
├── IDENTITY.md       # Danh tính agent
├── SOUL.md           # Linh hồn agent
└── USER.md           # Tùy chọn người dùng
```

> **Lưu ý:** Các thay đổi đối với `AGENT.md`, `SOUL.md`, `USER.md` và `memory/MEMORY.md` được tự động phát hiện trong thời gian chạy thông qua theo dõi thời gian sửa đổi file (mtime). **Không cần khởi động lại gateway** sau khi chỉnh sửa các file này — agent sẽ tải nội dung mới vào yêu cầu tiếp theo.

### Chính sách ngữ cảnh request

`turn_profile` là chính sách tùy chọn trong `agents.defaults.turn_profile` để kiểm soát ngữ cảnh mỗi turn mới mang theo: lịch sử, system prompt, prompt skills và các tool được phép gọi. Nếu không cấu hình, hoặc đặt `"enabled": false`, colearn giữ nguyên hành vi mặc định. Khi đặt `"enabled": true`, chính sách bên dưới áp dụng cho mỗi turn mới.

Mỗi block dùng chung các giá trị `mode`:

| Mode | Ý nghĩa |
| --- | --- |
| `default` | Giữ hành vi bình thường của colearn. Block bị thiếu hoặc thiếu `mode` đều được xem là `default`. |
| `off` | Tắt block đó cho turn. |
| `custom` | Dùng danh sách cho phép. Phiên bản này chỉ hỗ trợ `custom` cho `skills` và `tools`; dùng cho `history` hoặc `system_prompt` sẽ lỗi validate. |

Các block:

| Block | Nội dung kiểm soát |
| --- | --- |
| `history` | Đọc lịch sử và tóm tắt, ghi tin nhắn user/assistant/tool, nạp context, compact và summarize. |
| `system_prompt` | Chèn identity mặc định của colearn, chỉ dẫn workspace, memory, runtime context và summary. System prompt từ request bên ngoài vẫn được dùng khi block này `off`. |
| `skills` | Catalog skills và nội dung active skill trong prompt. `custom.allow` chỉ giữ các tên skill được liệt kê. |
| `tools` | Công cụ hiển thị cho model và được phép thực thi. `custom.allow` chỉ giữ các tool đã đăng ký và được liệt kê. |

Khi `system_prompt.mode` là `off`, tools vẫn hiển thị và không có system prompt bên ngoài, colearn dùng lại quy tắc dùng tool hiện có làm prompt fallback tối thiểu. Nếu `tools.mode` là `off`, fallback này không được thêm.

Ví dụ ngữ cảnh sạch chỉ giữ tool web:

```json
{
  "agents": {
    "defaults": {
      "turn_profile": {
        "enabled": true,
        "history": { "mode": "off" },
        "system_prompt": { "mode": "off" },
        "skills": { "mode": "off" },
        "tools": {
          "mode": "custom",
          "allow": ["web_search", "web_fetch"]
        }
      }
    }
  }
}
```

### Nguồn Skill

Mặc định, skill được tải từ:

1. `~/.colearn/workspace/skills` (workspace)
2. `~/.colearn/skills` (global)
3. `<đường-dẫn-nhúng-khi-build>/skills` (tích hợp)

Cho thiết lập nâng cao/test, bạn có thể ghi đè thư mục gốc skill builtin với:

```bash
export colearn_BUILTIN_SKILLS=/path/to/skills
```

### Dung Skill va Lenh Tu Kenh Chat

Sau khi cai dat skill, ban co the xem va ep dung truc tiep tu kenh chat:

- `/list skills` hien ten cac skill da cai dat ma agent hien tai co the dung.
- `/use <skill> <message>` ep dung mot skill cho duy nhat mot yeu cau.
- `/use <skill>` dat san skill do cho tin nhan tiep theo trong cung cuoc tro chuyen.
- `/use clear` huy skill override dang cho duoc tao boi `/use <skill>`.
- `/btw <question>` dat cau hoi phu ngay lap tuc ma khong thay doi lich su phien hien tai. `/btw` duoc xu ly nhu mot truy van truc tiep khong dung cong cu va khong di vao luong thuc thi cong cu thong thuong.

Vi du:

```text
/list skills
/use git giai thich cach squash 3 commit cuoi
/btw nhac lai giup toi chung ta da chot gi cho ke hoach deploy
/use italiapersonalfinance
dammi le ultime news
```

### Chính Sách Thực Thi Lệnh Thống Nhất

- Lệnh slash chung được thực thi qua một đường dẫn duy nhất trong `pkg/agent/loop.go` qua `commands.Executor`.
- Adapter kênh không còn xử lý lệnh chung cục bộ; chúng chuyển tiếp văn bản đầu vào đến đường dẫn bus/agent. Telegram vẫn tự động đăng ký khi khởi động các lệnh được hỗ trợ như `/start`, `/help`, `/show`, `/list`, `/use`, va `/btw`.
- Lệnh slash không xác định (ví dụ `/foo`) được chuyển sang xử lý LLM bình thường.
- Lệnh đã đăng ký nhưng không được hỗ trợ trên kênh hiện tại (ví dụ `/show` trên WhatsApp) trả về lỗi rõ ràng cho người dùng và dừng xử lý tiếp.

### 🔒 Sandbox Bảo Mật

colearn chạy trong môi trường sandbox mặc định. Agent chỉ có thể truy cập file và thực thi lệnh trong workspace đã cấu hình.

#### Cấu Hình Mặc Định

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.colearn/workspace",
      "restrict_to_workspace": true
    }
  }
}
```

| Tùy chọn                | Mặc định                | Mô tả                                    |
| ----------------------- | ----------------------- | ----------------------------------------- |
| `workspace`             | `~/.colearn/workspace` | Thư mục làm việc của agent               |
| `restrict_to_workspace` | `true`                  | Giới hạn truy cập file/lệnh trong workspace |

#### Công Cụ Được Bảo Vệ

Khi `restrict_to_workspace: true`, các công cụ sau được sandbox:

| Công cụ       | Chức năng        | Giới hạn                               |
| ------------- | ---------------- | -------------------------------------- |
| `read_file`   | Đọc file         | Chỉ file trong workspace              |
| `write_file`  | Ghi file         | Chỉ file trong workspace              |
| `list_dir`    | Liệt kê thư mục | Chỉ thư mục trong workspace           |
| `edit_file`   | Sửa file         | Chỉ file trong workspace              |
| `append_file` | Nối vào file     | Chỉ file trong workspace              |
| `exec`        | Thực thi lệnh   | Đường dẫn lệnh phải trong workspace   |

#### Bảo Vệ Exec Bổ Sung

Ngay cả khi `restrict_to_workspace: false`, công cụ `exec` chặn các lệnh nguy hiểm sau:

* `rm -rf`, `del /f`, `rmdir /s` — Xóa hàng loạt
* `format`, `mkfs`, `diskpart` — Định dạng đĩa
* `dd if=` — Tạo ảnh đĩa
* Ghi vào `/dev/sd[a-z]` — Ghi trực tiếp đĩa
* `shutdown`, `reboot`, `poweroff` — Tắt hệ thống
* Fork bomb `:(){ :|:& };:`

### Kiểm Soát Truy Cập File

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `tools.allow_read_paths` | string[] | `[]` | Additional paths allowed for reading outside workspace |
| `tools.allow_write_paths` | string[] | `[]` | Additional paths allowed for writing outside workspace |

### Bảo Mật Exec

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `tools.exec.allow_remote` | bool | `false` | Allow exec tool from remote channels (Telegram/Discord etc.) |
| `tools.exec.enable_deny_patterns` | bool | `true` | Enable dangerous command interception |
| `tools.exec.custom_deny_patterns` | string[] | `[]` | Custom regex patterns to block |
| `tools.exec.custom_allow_patterns` | string[] | `[]` | Custom regex patterns to allow |

> **Lưu ý Bảo Mật:** Bảo vệ symlink được bật mặc định — tất cả đường dẫn file được giải quyết qua `filepath.EvalSymlinks` trước khi so khớp whitelist, ngăn chặn tấn công thoát qua symlink.

#### Hạn Chế Đã Biết: Tiến Trình Con Từ Công Cụ Build

Guard bảo mật exec chỉ kiểm tra dòng lệnh mà colearn khởi chạy trực tiếp. Nó không kiểm tra đệ quy các tiến trình con được tạo bởi công cụ phát triển được phép như `make`, `go run`, `cargo`, `npm run`, hoặc script build tùy chỉnh.

Điều này có nghĩa là lệnh cấp cao nhất vẫn có thể biên dịch hoặc khởi chạy binary khác sau khi vượt qua kiểm tra guard ban đầu. Trong thực tế, hãy coi script build, Makefile, script package, và binary được tạo như mã thực thi cần cùng mức độ review như lệnh shell trực tiếp.

Cho môi trường rủi ro cao hơn:

* Review script build trước khi thực thi.
* Ưu tiên phê duyệt/review thủ công cho quy trình biên dịch và chạy.
* Chạy colearn trong container hoặc VM nếu bạn cần cách ly mạnh hơn guard tích hợp.

#### Ví Dụ Lỗi

```
[ERROR] tool: Tool execution failed
{tool=exec, error=Command blocked by safety guard (path outside working dir)}
```

```
[ERROR] tool: Tool execution failed
{tool=exec, error=Command blocked by safety guard (dangerous pattern detected)}
```

#### Tắt Giới Hạn (Rủi Ro Bảo Mật)

Nếu bạn cần agent truy cập đường dẫn ngoài workspace:

**Phương pháp 1: File cấu hình**

```json
{
  "agents": {
    "defaults": {
      "restrict_to_workspace": false
    }
  }
}
```

**Phương pháp 2: Biến môi trường**

```bash
export colearn_AGENTS_DEFAULTS_RESTRICT_TO_WORKSPACE=false
```

> ⚠️ **Cảnh báo**: Tắt giới hạn này cho phép agent truy cập bất kỳ đường dẫn nào trên hệ thống. Chỉ sử dụng cẩn thận trong môi trường được kiểm soát.

#### Tính Nhất Quán Ranh Giới Bảo Mật

Cài đặt `restrict_to_workspace` áp dụng nhất quán trên tất cả đường dẫn thực thi:

| Đường Dẫn Thực Thi | Ranh Giới Bảo Mật          |
| -------------------- | ---------------------------- |
| Main Agent           | `restrict_to_workspace` ✅   |
| Subagent / Spawn     | Kế thừa cùng giới hạn ✅    |
| Heartbeat tasks      | Kế thừa cùng giới hạn ✅    |

Tất cả đường dẫn chia sẻ cùng giới hạn workspace — không có cách nào vượt qua ranh giới bảo mật qua subagent hoặc tác vụ lên lịch.

### Heartbeat (Tác Vụ Định Kỳ)

colearn có thể thực hiện tác vụ định kỳ tự động. Tạo file `HEARTBEAT.md` trong workspace:

```markdown
# Tác Vụ Định Kỳ

- Kiểm tra email cho tin nhắn quan trọng
- Xem lịch cho sự kiện sắp tới
- Kiểm tra dự báo thời tiết
```

Agent sẽ đọc file này mỗi 30 phút (có thể cấu hình) và thực thi các tác vụ sử dụng công cụ có sẵn.

#### Tác Vụ Bất Đồng Bộ Với Spawn

Cho tác vụ chạy lâu (tìm kiếm web, gọi API), sử dụng công cụ `spawn` để tạo **subagent**:

```markdown
# Tác Vụ Định Kỳ

## Tác Vụ Nhanh (trả lời trực tiếp)

- Báo giờ hiện tại

## Tác Vụ Dài (dùng spawn cho bất đồng bộ)

- Tìm kiếm tin tức AI trên web và tóm tắt
- Kiểm tra email và báo cáo tin nhắn quan trọng
```

**Hành vi chính:**

| Tính năng        | Mô tả                                                              |
| ---------------- | ------------------------------------------------------------------ |
| **spawn**        | Tạo subagent bất đồng bộ, không chặn heartbeat                    |
| **Ngữ cảnh độc lập** | Subagent có ngữ cảnh riêng, không có lịch sử phiên             |
| **message tool** | Subagent giao tiếp trực tiếp với người dùng qua message tool      |
| **Không chặn**   | Sau khi spawn, heartbeat tiếp tục tác vụ tiếp theo                |

#### Luồng Giao Tiếp Của Subagent

```
Heartbeat kích hoạt
    ↓
Agent đọc HEARTBEAT.md
    ↓
Tác vụ dài: spawn subagent
    ↓                           ↓
Tiếp tục tác vụ tiếp theo  Subagent hoạt động độc lập
    ↓                           ↓
Hoàn thành tất cả tác vụ   Subagent dùng công cụ "message"
    ↓                           ↓
Trả lời HEARTBEAT_OK        Người dùng nhận kết quả trực tiếp
```

**Cấu hình:**

```json
{
  "heartbeat": {
    "enabled": true,
    "interval": 30
  }
}
```

| Tùy chọn   | Mặc định | Mô tả                                  |
| ---------- | -------- | -------------------------------------- |
| `enabled`  | `true`   | Bật/tắt heartbeat                      |
| `interval` | `30`     | Khoảng thời gian kiểm tra tính bằng phút (tối thiểu: 5) |

**Biến môi trường:**

* `colearn_HEARTBEAT_ENABLED=false` để tắt
* `colearn_HEARTBEAT_INTERVAL=60` để thay đổi khoảng thời gian

### Providers

> [!NOTE]
> Groq cung cấp chuyển đổi giọng nói thành văn bản miễn phí qua Whisper. Nếu được cấu hình, tin nhắn âm thanh từ bất kỳ kênh nào sẽ được tự động chuyển đổi ở cấp độ agent.

| Provider     | Mục đích                                | Lấy API Key                                                  |
| ------------ | --------------------------------------- | ------------------------------------------------------------ |
| `gemini`     | LLM (Gemini trực tiếp)                  | [www.tuptup.top](https://www.tuptup.top)           |
| `zhipu`      | LLM (Zhipu trực tiếp)                   | [bigmodel.cn](https://www.tuptup.top)                           |
| `volcengine` | LLM (Volcengine trực tiếp)              | [volcengine.com](https://www.tuptup.top) |
| `openrouter` | LLM (khuyến nghị, truy cập tất cả mô hình) | [openrouter.ai](https://www.tuptup.top)                   |
| `anthropic`  | LLM (Claude trực tiếp)                  | [www.tuptup.top](https://www.tuptup.top)       |
| `openai`     | LLM (GPT trực tiếp)                     | [www.tuptup.top](https://www.tuptup.top)           |
| `deepseek`   | LLM (DeepSeek trực tiếp)                | [www.tuptup.top](https://www.tuptup.top)       |
| `qwen`       | LLM (Qwen trực tiếp)                    | [www.tuptup.top](https://www.tuptup.top) |
| `groq`       | LLM + **Chuyển đổi giọng nói** (Whisper)| [www.tuptup.top](https://www.tuptup.top)                 |
| `cerebras`   | LLM (Cerebras trực tiếp)                | [cerebras.ai](https://www.tuptup.top)                           |
| `vivgrid`    | LLM (Vivgrid trực tiếp)                 | [vivgrid.com](https://www.tuptup.top)                           |

### Cấu Hình Mô Hình (model_list)

> **Tính năng mới:** colearn hiện sử dụng cách tiếp cận **lấy mô hình làm trung tâm**. Chỉ cần chỉ định định dạng `vendor/model` (ví dụ: `zhipu/glm-4.7`) để thêm provider mới — **không cần thay đổi code!**

#### Tất Cả Vendor Được Hỗ Trợ

| Vendor                  | Tiền tố `model` | API Base mặc định                                   | Giao thức | API Key                                                          |
| ----------------------- | --------------- | --------------------------------------------------- | --------- | ---------------------------------------------------------------- |
| **OpenAI**              | `openai/`       | `https://www.tuptup.top                         | OpenAI    | [Lấy](https://www.tuptup.top)                               |
| **Anthropic**           | `anthropic/`    | `https://www.tuptup.top                      | Anthropic | [Lấy](https://www.tuptup.top)                             |
| **智谱 AI (GLM)**       | `zhipu/`        | `https://www.tuptup.top              | OpenAI    | [Lấy](https://www.tuptup.top)     |
| **DeepSeek**            | `deepseek/`     | `https://www.tuptup.top                       | OpenAI    | [Lấy](https://www.tuptup.top)                             |
| **Google Gemini**       | `gemini/`       | `https://www.tuptup.top  | Gemini    | [Lấy](https://www.tuptup.top)                      |
| **Groq**                | `groq/`         | `https://www.tuptup.top                    | OpenAI    | [Lấy](https://www.tuptup.top)                                  |
| **通义千问 (Qwen)**     | `qwen/`         | `https://www.tuptup.top | OpenAI    | [Lấy](https://www.tuptup.top)                      |
| **Ollama**              | `ollama/`       | `https://www.tuptup.top                         | OpenAI    | Cục bộ (không cần key)                                           |
| **OpenRouter**          | `openrouter/`   | `https://www.tuptup.top                      | OpenAI    | [Lấy](https://www.tuptup.top)                                |
| **VolcEngine (Doubao)** | `volcengine/`   | `https://www.tuptup.top          | OpenAI    | [Lấy](https://www.tuptup.top) |
| **Antigravity**         | `antigravity/`  | Google Cloud                                        | Custom    | Chỉ OAuth                                                        |

#### Cân Bằng Tải

Cấu hình nhiều endpoint cho cùng tên mô hình — colearn sẽ tự động round-robin:

```json
{
  "model_list": [
    { "model_name": "gpt-5.4", "model": "openai/gpt-5.4", "api_base": "https://www.tuptup.top", "api_keys": ["sk-key1"] },
    { "model_name": "gpt-5.4", "model": "openai/gpt-5.4", "api_base": "https://www.tuptup.top", "api_keys": ["sk-key2"] }
  ]
}
```

#### Di Chuyển Từ Cấu Hình `providers` Cũ

Cấu hình `providers` cũ đã **bị deprecated** và đã được loại bỏ trong V2. Các cấu hình V0/V1 hiện có sẽ được tự động migrate. Xem [docs/migration/model-list-migration.md](../migration/model-list-migration.md).

#### Cấu Hình Streaming

Provider streaming dùng cơ chế double opt-in và bị tắt theo mặc định. Agent chỉ thử streaming khi channel hiện tại có `settings.streaming.enabled: true`, entry model đang dùng có `streaming.enabled: true`, và cả provider lẫn channel đều hỗ trợ streaming. Nếu thiếu bất kỳ điều kiện nào, colearn dùng đường dẫn yêu cầu không streaming thông thường.

Pico WebUI là channel đầu tiên được nối đầy đủ. Pico tạo message assistant đầu tiên bằng wire message hiện có `message.create`, sau đó cập nhật chính message đó bằng `message.update`; không thêm loại wire message Pico mới.

Hãy để trống `streaming` khi bạn không muốn dùng streaming. Bỏ qua block `streaming` nghĩa là đã tắt; bạn không cần viết `"streaming": {"enabled": false}`.

Ví dụ bật streaming:

```json
{
  "model_list": [
    {
      "model_name": "gpt-5.4",
      "provider": "openai",
      "model": "gpt-5.4",
      "api_keys": ["sk-your-openai-key"],
      "streaming": {
        "enabled": true
      }
    }
  ],
  "channel_list": {
    "pico": {
      "enabled": true,
      "type": "pico",
      "settings": {
        "token": "YOUR_PICO_TOKEN",
        "streaming": {
          "enabled": true
        }
      }
    }
  }
}
```

| Trường | Kiểu | Mặc định | Mô tả |
| ------ | ---- | -------- | ----- |
| `channel_list.<name>.settings.streaming.enabled` | bool | `false` | Cho phép channel này hiển thị output streaming từ provider |
| `channel_list.<name>.settings.streaming.throttle_seconds` | int | Mặc định Pico sau khi bật: `0` | Khoảng cách tối thiểu giữa các cập nhật trung gian; nội dung cuối luôn được flush |
| `channel_list.<name>.settings.streaming.min_growth_chars` | int | Mặc định Pico sau khi bật: `1` | Số ký tự tăng tối thiểu trước khi gửi cập nhật trung gian; nội dung cuối luôn được flush |
| `model_list[].streaming.enabled` | bool | `false` | Cho phép entry model này thử yêu cầu provider streaming |

Các biến môi trường Telegram cũ vẫn tương thích: `colearn_CHANNELS_TELEGRAM_STREAMING_ENABLED`, `colearn_CHANNELS_TELEGRAM_STREAMING_THROTTLE_SECONDS`, và `colearn_CHANNELS_TELEGRAM_STREAMING_MIN_GROWTH_CHARS`. Chúng chỉ áp dụng cho Telegram settings và không bật hoặc thay đổi `settings.streaming` của Pico.

Hành vi lỗi được giữ thận trọng: nếu streaming lỗi trước khi gửi bất kỳ chunk hiển thị nào, colearn thử lại một lần qua đường dẫn `Chat()` thông thường. Nếu đã có chunk hiển thị cho người dùng, colearn không gửi thêm một câu trả lời non-streaming thứ hai để tránh lặp output.

### Kiến Trúc Provider

colearn định tuyến provider theo họ giao thức:

- **Tương thích OpenAI**: OpenRouter, Groq, Zhipu, endpoint kiểu vLLM và hầu hết các provider khác.
- **Gemini native**: Google Gemini qua các endpoint native `models/*:generateContent` và `models/*:streamGenerateContent`.
- **Anthropic**: Hành vi API Claude gốc.
- **Codex/OAuth**: Tuyến xác thực OAuth/token OpenAI.

Điều này giữ runtime nhẹ trong khi khiến backend OpenAI-compatible mới chủ yếu chỉ là thao tác cấu hình (`api_base` + `api_keys`).

### Tác Vụ Đã Lên Lịch / Nhắc Nhở

colearn hỗ trợ tác vụ theo lịch qua công cụ `cron`.

```json
{
  "tools": {
    "cron": {
      "enabled": true,
      "exec_timeout_minutes": 5
    }
  }
}
```

Tác vụ đã lên lịch được lưu trữ bền vững sau khi khởi động lại tại `~/.colearn/workspace/cron/`.

### Chủ Đề Nâng Cao

| Chủ đề | Mô tả |
| ------ | ----- |
| [Hệ Thống Hook](../architecture/hooks/README.md) | Hook hướng sự kiện: observer, interceptor, approval hook |
| [Steering](../architecture/steering.md) | Chèn tin nhắn vào vòng lặp agent đang chạy |
| [SubTurn](../architecture/subturn.md) | Điều phối subagent, kiểm soát đồng thời, vòng đời |
| [Quản Lý Ngữ Cảnh](../architecture/agent-refactor/context.md) | Phát hiện ranh giới ngữ cảnh, nén |
