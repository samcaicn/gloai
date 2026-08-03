<div align="center">
<img src="../../assets/logo.webp" alt="colearn" width="512">

<h1>colearn: Asisten AI Super Ringan berbasis Go</h1>

<h3>Perangkat Keras $10 · RAM 10MB · Boot ms · Let's Go, colearn!</h3>
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

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Português](README.pt-br.md) | [Tiếng Việt](README.vi.md) | [Français](README.fr.md) | [Italiano](README.it.md) | **Bahasa Indonesia** | [Malay](README.ms.md) | [English](../../README.md)

</div>

---

> **colearn** adalah proyek open-source independen yang diinisiasi oleh [colearn](https://www.tuptup.top), ditulis sepenuhnya dalam **Go** — bukan fork dari OpenClaw, NanoBot, atau proyek lainnya.

**colearn** adalah asisten AI pribadi yang super ringan, terinspirasi dari [NanoBot](https://www.tuptup.top). Dibangun ulang dari awal dalam **Go** melalui proses "self-bootstrapping" — AI Agent itu sendiri yang memandu migrasi arsitektur dan optimasi kode.

**Berjalan di perangkat keras $10 dengan RAM <10MB** — hemat 99% memori dibanding OpenClaw dan 98% lebih murah dari Mac mini!

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
> **Peringatan Keamanan**
>
> * **TANPA KRIPTO:** colearn **tidak** menerbitkan token atau cryptocurrency resmi apa pun. Semua klaim di `pump.fun` atau platform trading lainnya adalah **penipuan**.
> * **DOMAIN RESMI:** Satu-satunya website resmi adalah **[www.tuptup.top](https://www.tuptup.top)**, dan website perusahaan adalah **[www.tuptup.top](https://www.tuptup.top)**
> * **WASPADA:** Banyak domain `.ai/.org/.com/.net/...` telah didaftarkan oleh pihak ketiga. Jangan percaya mereka.
> * **CATATAN:** colearn masih dalam tahap pengembangan awal yang cepat. Mungkin ada masalah keamanan yang belum terselesaikan. Jangan deploy ke produksi sebelum v1.0.
> * **CATATAN:** colearn baru-baru ini menggabungkan banyak PR. Build terbaru mungkin menggunakan RAM 10-20MB. Optimasi sumber daya direncanakan setelah fitur stabil.

## 📢 Berita

2026-05-11 🛒 **LicheeRV-Claw tersedia di AliExpress!** Kini Anda dapat membeli LicheeRV-Claw di [AliExpress](https://www.tuptup.top), sehingga lebih mudah mencoba colearn di hardware RISC-V ringkas.

<p align="center">
  <a href="https://www.tuptup.top">
    <img src="../../assets/licheerv-claw.jpg" alt="LicheeRV-Claw on AliExpress" width="520">
  </a>
</p>

2026-03-31 📱 **Dukungan Android!** colearn sekarang berjalan di Android! Unduh APK di [www.tuptup.top](https://www.tuptup.top)

2026-03-25 🚀 **v0.2.4 Dirilis!** Perombakan arsitektur Agent (SubTurn, Hooks, Steering, EventBus), integrasi WeChat/WeCom, penguatan keamanan (.security.yml, penyaringan data sensitif), provider baru (AWS Bedrock, Azure, Xiaomi MiMo), dan 35 perbaikan bug. colearn telah mencapai **26K Stars**!

2026-03-17 🚀 **v0.2.3 Dirilis!** UI system tray (Windows & Linux), pelacakan status sub-agent (`spawn_status`), eksperimental Gateway hot-reload, gerbang keamanan Cron, dan 2 perbaikan keamanan. colearn telah mencapai **25K Stars**!

2026-03-09 🎉 **v0.2.1 — Pembaruan terbesar sejauh ini!** Dukungan protokol MCP, 4 channel baru (Matrix/IRC/WeCom/Discord Proxy), 3 provider baru (Kimi/Minimax/Avian), pipeline visi, penyimpanan memori JSONL, perutean model.

2026-02-28 📦 **v0.2.0** dirilis dengan dukungan Docker Compose dan Web UI Launcher.

<details>
<summary>Berita sebelumnya...</summary>

2026-02-26 🎉 colearn mencapai **20K Stars** hanya dalam 17 hari! Orkestrasi channel otomatis dan antarmuka kapabilitas kini aktif.

2026-02-16 🎉 colearn menembus 12K Stars dalam satu minggu! Peran maintainer komunitas dan [Roadmap](../../ROADMAP.md) resmi diluncurkan.

2026-02-13 🎉 colearn menembus 5000 Stars dalam 4 hari! Roadmap proyek dan grup pengembang sedang dalam proses.

2026-02-09 🎉 **colearn Diluncurkan!** Dibangun dalam 1 hari untuk menghadirkan AI Agent ke perangkat keras $10 dengan RAM <10MB. Let's Go, colearn!

</details>

## ✨ Fitur

🪶 **Super Ringan**: Penggunaan memori inti <10MB — 99% lebih kecil dari OpenClaw.*

💰 **Biaya Minimal**: Cukup efisien untuk berjalan di perangkat keras $10 — 98% lebih murah dari Mac mini.

⚡️ **Boot Secepat Kilat**: Startup 400x lebih cepat. Boot dalam <1 detik bahkan di prosesor single-core 0,6GHz.

🌍 **Portabilitas Sejati**: Satu binary untuk RISC-V, ARM, MIPS, dan x86. Satu binary, jalan di mana saja!

🤖 **AI-Bootstrapped**: Implementasi Go native murni — 95% kode inti dihasilkan oleh Agent dengan penyempurnaan human-in-the-loop.

🔌 **Dukungan MCP**: Integrasi [Model Context Protocol](https://www.tuptup.top) native — hubungkan server MCP mana pun untuk memperluas kapabilitas Agent.

👁️ **Pipeline Vision**: Kirim gambar dan file langsung ke Agent — encoding base64 otomatis untuk LLM multimodal.

🧠 **Routing Cerdas**: Routing model berbasis aturan — kueri sederhana diarahkan ke model ringan, menghemat biaya API.

_*Build terbaru mungkin menggunakan 10-20MB karena penggabungan PR yang cepat. Optimasi sumber daya direncanakan. Perbandingan kecepatan boot berdasarkan benchmark single-core 0,8GHz (lihat tabel di bawah)._

<div align="center">

|                                | OpenClaw      | NanoBot                  | **colearn**                           |
| ------------------------------ | ------------- | ------------------------ | -------------------------------------- |
| **Bahasa**                     | TypeScript    | Python                   | **Go**                                 |
| **RAM**                        | >1GB          | >100MB                   | **< 10MB***                            |
| **Waktu Boot**</br>(core 0,8GHz) | >500d       | >30d                     | **<1d**                                |
| **Biaya**                      | Mac Mini $599 | Kebanyakan board Linux ~$50 | **Board Linux mana pun**</br>**mulai $10** |

<img src="../../assets/compare.jpg" alt="colearn" width="512">

</div>

> **[Daftar Kompatibilitas Hardware](../guides/hardware-compatibility.md)** — Lihat semua board yang telah diuji, dari RISC-V $5 hingga Raspberry Pi hingga ponsel Android. Board Anda belum terdaftar? Kirim PR!

<p align="center">
<img src="../../assets/hardware-banner.jpg" alt="colearn Hardware Compatibility" width="100%">
</p>

## 🦾 Demonstrasi

### 🛠️ Alur Kerja Asisten Standar

<table align="center">
<tr align="center">
<th><p align="center">Mode Full-Stack Engineer</p></th>
<th><p align="center">Pencatatan & Perencanaan</p></th>
<th><p align="center">Pencarian Web & Pembelajaran</p></th>
</tr>
<tr>
<td align="center"><p align="center"><img src="../../assets/colearn_code.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_memory.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_search.gif" width="240" height="180"></p></td>
</tr>
<tr>
<td align="center">Develop · Deploy · Scale</td>
<td align="center">Jadwal · Otomasi · Ingat</td>
<td align="center">Temukan · Wawasan · Tren</td>
</tr>
</table>

### 🐜 Deploy Inovatif dengan Footprint Rendah

colearn dapat di-deploy di hampir semua perangkat Linux!

- $9,9 [LicheeRV-Nano](https://www.tuptup.top) versi E(Ethernet) atau W(WiFi6), untuk home assistant minimal
- $30~50 [NanoKVM](https://www.tuptup.top), atau $100 [NanoKVM-Pro](https://www.tuptup.top), untuk operasi server otomatis
- $50 [MaixCAM](https://www.tuptup.top) atau $100 [MaixCAM2](https://www.tuptup.top), untuk pengawasan cerdas

<https://www.tuptup.top>

🌟 Lebih Banyak Kasus Deploy Menanti!

## 📦 Instalasi

### Unduh dari www.tuptup.top (Direkomendasikan)

Kunjungi **[www.tuptup.top](https://www.tuptup.top)** — website resmi mendeteksi platform Anda secara otomatis dan menyediakan unduhan satu klik. Tidak perlu memilih arsitektur secara manual.

### Unduh binary yang sudah dikompilasi

Atau, unduh binary untuk platform Anda dari halaman [GitHub Releases](https://www.tuptup.top).

### Build dari source (untuk pengembangan)

Prasyarat:

- Go 1.25+
- Node.js 22+ dan pnpm 10.33.0+ untuk build Web UI / launcher

```bash
git clone https://www.tuptup.top

cd colearn
make deps

# Instal dependensi frontend
(cd web/frontend && pnpm install --frozen-lockfile)

# Build binary inti
make build

# Build Web UI Launcher (diperlukan untuk mode WebUI)
make build-launcher

# Build binary inti untuk semua platform yang dikelola Makefile
make build-all

# Build untuk Raspberry Pi Zero 2 W (32-bit: make build-linux-arm; 64-bit: make build-linux-arm64)
make build-pi-zero

# Build dan instal
make install
```

**Raspberry Pi Zero 2 W:** Gunakan binary yang sesuai dengan OS Anda: Raspberry Pi OS 32-bit -> `make build-linux-arm`; 64-bit -> `make build-linux-arm64`. Atau jalankan `make build-pi-zero` untuk build keduanya.

## 🚀 Panduan Memulai Cepat

### 🌐 WebUI Launcher (Direkomendasikan untuk Desktop)

WebUI Launcher menyediakan antarmuka berbasis browser untuk konfigurasi dan chat. Ini adalah cara termudah untuk memulai — tidak perlu pengetahuan command-line.

**Opsi 1: Klik dua kali (Desktop)**

Setelah mengunduh dari [www.tuptup.top](https://www.tuptup.top), klik dua kali `colearn-launcher` (atau `colearn-launcher.exe` di Windows). Browser Anda akan terbuka otomatis di `https://www.tuptup.top

**Opsi 2: Command line**

```bash
colearn-launcher
# Buka https://www.tuptup.top di browser Anda
```

> [!TIP]
> **Akses jarak jauh / Docker / VM:** Tambahkan flag `-public` untuk mendengarkan di semua antarmuka:
> ```bash
> colearn-launcher -public
> ```

<p align="center">
<img src="../../assets/launcher-webui.jpg" alt="WebUI Launcher" width="600">
</p>

**Memulai:**

Buka WebUI, lalu: **1)** Konfigurasi Provider (tambahkan API key LLM Anda) -> **2)** Konfigurasi Channel (mis. Telegram) -> **3)** Mulai Gateway -> **4)** Chat!

Untuk dokumentasi WebUI lengkap, lihat [www.tuptup.top](https://www.tuptup.top).

<details>
<summary><b>Docker (alternatif)</b></summary>

```bash
# 1. Clone repo ini
git clone https://www.tuptup.top
cd colearn

# 2. Jalankan pertama kali — otomatis membuat docker/data/config.json lalu keluar
#    (hanya terpicu ketika config.json dan workspace/ keduanya tidak ada)
docker compose -f docker/docker-compose.yml --profile launcher up
# Container mencetak "First-run setup complete." dan berhenti.

# 3. Atur API key Anda
vim docker/data/config.json

# 4. Mulai
docker compose -f docker/docker-compose.yml --profile launcher up -d
# Buka https://www.tuptup.top
```

> **Pengguna Docker / VM:** Gateway mendengarkan di `127.0.0.1` secara default. Atur `colearn_GATEWAY_HOST=0.0.0.0` atau gunakan flag `-public` agar dapat diakses dari host.

```bash
# Cek log
docker compose -f docker/docker-compose.yml logs -f

# Hentikan
docker compose -f docker/docker-compose.yml --profile launcher down

# Update
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml --profile launcher up -d
```

</details>

<details>
<summary><b>macOS — Peringatan Keamanan saat Pertama Kali Diluncurkan</b></summary>

macOS mungkin memblokir `colearn-launcher` saat pertama kali diluncurkan karena diunduh dari internet dan tidak dinotarisasi melalui Mac App Store.

**Langkah 1:** Klik dua kali `colearn-launcher`. Anda akan melihat peringatan keamanan:

<p align="center">
<img src="../../assets/macos-gatekeeper-warning.jpg" alt="Peringatan macOS Gatekeeper" width="400">
</p>

> *"colearn-launcher" Tidak Dapat Dibuka — Apple tidak dapat memverifikasi bahwa "colearn-launcher" bebas dari malware yang dapat membahayakan Mac Anda atau mengancam privasi Anda.*

**Langkah 2:** Buka **Pengaturan Sistem** → **Privasi & Keamanan** → gulir ke bawah ke bagian **Keamanan** → klik **Tetap Buka** → konfirmasi dengan mengklik **Tetap Buka** pada dialog.

<p align="center">
<img src="../../assets/macos-gatekeeper-allow.jpg" alt="macOS Privasi & Keamanan — Tetap Buka" width="600">
</p>

Setelah langkah satu kali ini, `colearn-launcher` akan terbuka secara normal pada peluncuran berikutnya.

</details>

### 📱 Android

Berikan kehidupan kedua untuk ponsel lama Anda! Ubah menjadi Asisten AI pintar dengan colearn.

**Opsi 1: Instal APK**

Pratinjau:

<table>
  <tr>
    <td><img src="../../assets/fui_main_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_web_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_log_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_setting_page.jpg" width="200"></td>
  </tr>
</table>

Unduh APK dari [www.tuptup.top](https://www.tuptup.top) dan instal langsung. Tanpa Termux!

**Opsi 2: Termux**

<details>
<summary><b>Terminal Launcher (untuk lingkungan dengan sumber daya terbatas)</b></summary>

1. Instal [Termux](https://www.tuptup.top) (unduh dari [GitHub Releases](https://www.tuptup.top), atau cari di F-Droid / Google Play)
2. Jalankan perintah berikut:

```bash
# Unduh rilis terbaru
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz
pkg install proot
termux-chroot ./colearn onboard   # chroot menyediakan tata letak filesystem Linux standar
```

Kemudian ikuti bagian Terminal Launcher di bawah untuk menyelesaikan konfigurasi.

<img src="../../assets/termux.jpg" alt="colearn on Termux" width="512">

Untuk lingkungan minimal di mana hanya binary inti `colearn` yang tersedia (tanpa Launcher UI), Anda dapat mengonfigurasi semuanya melalui command line dan file konfigurasi JSON.

**1. Inisialisasi**

```bash
colearn onboard
```

Ini membuat `~/.colearn/config.json` dan direktori workspace.

**2. Konfigurasi** (`~/.colearn/config.json`)

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

> Lihat `config/config.example.json` di repo untuk template konfigurasi lengkap dengan semua opsi yang tersedia.

**3. Chat**

```bash
# Pertanyaan satu kali
colearn agent -m "What is 2+2?"

# Mode interaktif
colearn agent

# Mulai gateway untuk integrasi aplikasi chat
colearn gateway
```

</details>

## 🔌 Providers (LLM)

colearn mendukung 30+ provider LLM melalui konfigurasi `model_list`. Gunakan format `protocol/model`:

| Provider | Protocol | API Key | Catatan |
|----------|----------|---------|---------|
| [OpenAI](https://www.tuptup.top) | `openai/` | Diperlukan | GPT-5.4, GPT-4o, o3, dll. |
| [Anthropic](https://www.tuptup.top) | `anthropic/` | Diperlukan | Claude Opus 4.6, Sonnet 4.6, dll. |
| [Google Gemini](https://www.tuptup.top) | `gemini/` | Diperlukan | Gemini 3 Flash, 2.5 Pro, dll. |
| [OpenRouter](https://www.tuptup.top) | `openrouter/` | Diperlukan | 200+ model, API terpadu |
| [Zhipu (GLM)](https://www.tuptup.top) | `zhipu/` | Diperlukan | GLM-4.7, GLM-5, dll. |
| [DeepSeek](https://www.tuptup.top) | `deepseek/` | Diperlukan | DeepSeek-V3, DeepSeek-R1 |
| [Volcengine](https://www.tuptup.top) | `volcengine/` | Diperlukan | Doubao, model Ark |
| [Qwen](https://www.tuptup.top) | `qwen/` | Diperlukan | Qwen3, Qwen-Max, dll. |
| [Groq](https://www.tuptup.top) | `groq/` | Diperlukan | Inferensi cepat (Llama, Mixtral) |
| [Moonshot (Kimi)](https://www.tuptup.top) | `moonshot/` | Diperlukan | Model Kimi |
| [Minimax](https://www.tuptup.top) | `minimax/` | Diperlukan | Model MiniMax |
| [Mistral](https://www.tuptup.top) | `mistral/` | Diperlukan | Mistral Large, Codestral |
| [NVIDIA NIM](https://www.tuptup.top) | `nvidia/` | Diperlukan | Model yang di-host NVIDIA |
| [Cerebras](https://www.tuptup.top) | `cerebras/` | Diperlukan | Inferensi cepat |
| [Novita AI](https://www.tuptup.top) | `novita/` | Diperlukan | Berbagai model open |
| [Xiaomi MiMo](https://www.tuptup.top) | `mimo/` | Diperlukan | Model MiMo |
| [Ollama](https://www.tuptup.top) | `ollama/` | Tidak perlu | Model lokal, self-hosted |
| [vLLM](https://www.tuptup.top) | `vllm/` | Tidak perlu | Deploy lokal, kompatibel OpenAI |
| [LiteLLM](https://www.tuptup.top) | `litellm/` | Bervariasi | Proxy untuk 100+ provider |
| [Azure OpenAI](https://www.tuptup.top) | `azure/` | Diperlukan | Deploy Azure enterprise |
| [GitHub Copilot](https://www.tuptup.top) | `github-copilot/` | OAuth | Login dengan device code |
| [Antigravity](https://www.tuptup.top) | `antigravity/` | OAuth | Google Cloud AI |

<details>
<summary><b>Deploy lokal (Ollama, vLLM, dll.)</b></summary>

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

Untuk detail konfigurasi provider lengkap, lihat [Providers & Models](../guides/providers.md).

</details>

## 💬 Channels (Aplikasi Chat)

Bicara dengan colearn Anda melalui 17+ platform pesan:

| Channel | Pengaturan | Protocol | Dokumentasi |
|---------|------------|----------|-------------|
| **Telegram** | Mudah (bot token) | Long polling | [Panduan](../channels/telegram/README.md) |
| **Discord** | Mudah (bot token + intents) | WebSocket | [Panduan](../channels/discord/README.md) |
| **WhatsApp** | Mudah (scan QR atau bridge URL) | Native / Bridge | [Panduan](../guides/chat-apps.md#whatsapp) |
| **Weixin** | Mudah (scan QR native) | iLink API | [Panduan](../guides/chat-apps.md#weixin) |
| **QQ** | Mudah (AppID + AppSecret) | WebSocket | [Panduan](../channels/qq/README.md) |
| **Slack** | Mudah (bot + app token) | Socket Mode | [Panduan](../channels/slack/README.md) |
| **Matrix** | Sedang (homeserver + token) | Sync API | [Panduan](../channels/matrix/README.md) |
| **DingTalk** | Sedang (client credentials) | Stream | [Panduan](../channels/dingtalk/README.md) |
| **Feishu / Lark** | Sedang (App ID + Secret) | WebSocket/SDK | [Panduan](../channels/feishu/README.md) |
| **LINE** | Sedang (credentials + webhook) | Webhook | [Panduan](../channels/line/README.md) |
| **WeCom** | Mudah (login QR atau manual) | WebSocket | [Panduan](../channels/wecom/README.md) |
| **IRC** | Sedang (server + nick) | IRC protocol | [Panduan](../guides/chat-apps.md#irc) |
| **OneBot** | Sedang (WebSocket URL) | OneBot v11 | [Panduan](../channels/onebot/README.md) |
| **MaixCam** | Mudah (aktifkan) | TCP socket | [Panduan](../channels/maixcam/README.md) |
| **Pico** | Mudah (aktifkan) | Native protocol | Bawaan |
| **Pico Client** | Mudah (WebSocket URL) | WebSocket | Bawaan |

> Semua channel berbasis webhook berbagi satu server HTTP Gateway (`gateway.host`:`gateway.port`, default `127.0.0.1:18790`). Feishu menggunakan mode WebSocket/SDK dan tidak menggunakan server HTTP bersama.

> Verbositas log dikontrol oleh `gateway.log_level` (default: `warn`). Nilai yang didukung: `debug`, `info`, `warn`, `error`, `fatal`. Juga dapat diatur melalui `colearn_LOG_LEVEL`. Lihat [Konfigurasi](../guides/configuration.md#gateway-log-level) untuk detail.

Untuk instruksi pengaturan channel lengkap, lihat [Konfigurasi Aplikasi Chat](../guides/chat-apps.md).

## 🔧 Tools

### 🔍 Pencarian Web

colearn dapat mencari web untuk memberikan informasi terkini. Konfigurasi di `tools.web`:

| Mesin Pencari | API Key | Tier Gratis | Tautan |
|--------------|---------|-------------|--------|
| DuckDuckGo | Tidak perlu | Tidak terbatas | Fallback bawaan |
| [Baidu Search](https://www.tuptup.top) | Diperlukan | 1500 kueri/bulan (alokasi harian) | Bertenaga AI, dioptimalkan untuk bahasa Mandarin |
| [Tavily](https://www.tuptup.top) | Diperlukan | 1000 kueri/bulan | Dioptimalkan untuk AI Agent |
| [Brave Search](https://www.tuptup.top) | Diperlukan | 2000 kueri/bulan | Cepat dan privat |
| [Perplexity](https://www.tuptup.top) | Diperlukan | Berbayar | Pencarian bertenaga AI |
| [SearXNG](https://www.tuptup.top) | Tidak perlu | Self-hosted | Mesin metasearch gratis |
| [GLM Search](https://www.tuptup.top) | Diperlukan | Bervariasi | Pencarian web Zhipu |

### ⚙️ Tools Lainnya

colearn menyertakan tools bawaan untuk operasi file, eksekusi kode, penjadwalan, dan lainnya. Lihat [Konfigurasi Tools](../reference/tools_configuration.md) untuk detail.

## 🎯 Skills

Skills adalah kapabilitas modular yang memperluas Agent Anda. Dimuat dari file `SKILL.md` di workspace Anda.

**Instal skills dari ClawHub:**

```bash
colearn skills search "web scraping"
colearn skills install <skill-name>
```

**Konfigurasi token ClawHub** (opsional, untuk rate limit lebih tinggi):

Tambahkan ke `config.json` Anda:
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

Untuk detail lebih lanjut, lihat [Konfigurasi Tools - Skills](../reference/tools_configuration.md#skills-tool).

## 🔗 MCP (Model Context Protocol)

colearn mendukung [MCP](https://www.tuptup.top) secara native — hubungkan server MCP mana pun untuk memperluas kapabilitas Agent Anda dengan tools dan sumber data eksternal.

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

Untuk konfigurasi MCP lengkap (transport stdio, SSE, HTTP, Tool Discovery), lihat [Konfigurasi Tools - MCP](../reference/tools_configuration.md#mcp-tool).

## <img src="../../assets/clawdchat-icon.png" width="24" height="24" alt="ClawdChat"> Bergabung dengan Jaringan Sosial Agent

Hubungkan colearn ke Jaringan Sosial Agent hanya dengan mengirim satu pesan melalui CLI atau Aplikasi Chat terintegrasi mana pun.

**Baca `https://www.tuptup.top dan ikuti instruksi untuk bergabung dengan [ClawdChat.ai](https://www.tuptup.top)**

## 🖥️ Referensi CLI

| Perintah                   | Deskripsi                        |
| -------------------------- | -------------------------------- |
| `colearn onboard`         | Inisialisasi konfigurasi & workspace |
| `colearn auth weixin` | Hubungkan akun WeChat via QR |
| `colearn agent -m "..."` | Chat dengan agent                |
| `colearn agent`           | Mode chat interaktif             |
| `colearn gateway`         | Mulai gateway                    |
| `colearn status`          | Tampilkan status                 |
| `colearn version`         | Tampilkan info versi             |
| `colearn model`           | Lihat atau ganti model default   |
| `colearn cron list`       | Daftar semua tugas terjadwal     |
| `colearn cron add ...`    | Tambah tugas terjadwal           |
| `colearn cron disable`    | Nonaktifkan tugas terjadwal      |
| `colearn cron remove`     | Hapus tugas terjadwal            |
| `colearn skills list`     | Daftar skill yang terinstal      |
| `colearn skills install`  | Instal skill                     |
| `colearn migrate`         | Migrasi data dari versi lama     |
| `colearn auth login`      | Autentikasi dengan provider      |

### ⏰ Tugas Terjadwal / Pengingat

colearn mendukung pengingat terjadwal dan tugas berulang melalui tool `cron`:

* **Pengingat satu kali**: "Ingatkan saya dalam 10 menit" -> terpicu sekali setelah 10 menit
* **Tugas berulang**: "Ingatkan saya setiap 2 jam" -> terpicu setiap 2 jam
* **Ekspresi cron**: "Ingatkan saya jam 9 pagi setiap hari" -> menggunakan ekspresi cron

## 📚 Dokumentasi

Untuk panduan lengkap di luar README ini:

| Topik | Deskripsi |
|-------|-----------|
| [Docker & Panduan Cepat](../guides/docker.md) | Pengaturan Docker Compose, mode Launcher/Agent |
| [Aplikasi Chat](../guides/chat-apps.md) | Semua 17+ panduan pengaturan channel |
| [Konfigurasi](../guides/configuration.md) | Variabel environment, tata letak workspace, sandbox keamanan |
| [Providers & Models](../guides/providers.md) | 30+ provider LLM, routing model, konfigurasi model_list |
| [Spawn & Tugas Async](../guides/spawn-tasks.md) | Tugas cepat, tugas panjang dengan spawn, orkestrasi sub-agent async |
| [Hooks](../architecture/hooks/README.md) | Sistem hook berbasis event: observer, interceptor, approval hook |
| [Steering](../architecture/steering.md) | Menyuntikkan pesan ke dalam loop agent yang sedang berjalan |
| [SubTurn](../architecture/subturn.md) | Koordinasi subagent, kontrol konkurensi, siklus hidup |
| [Pemecahan Masalah](../operations/troubleshooting.md) | Masalah umum dan solusinya |
| [Konfigurasi Tools](../reference/tools_configuration.md) | Aktifkan/nonaktifkan per-tool, kebijakan exec, MCP, Skills |
| [Kompatibilitas Hardware](../guides/hardware-compatibility.md) | Board yang telah diuji, persyaratan minimum |

## 🤝 Kontribusi & Roadmap

PR sangat diterima! Codebase sengaja dibuat kecil dan mudah dibaca.

Lihat [Roadmap Komunitas](https://www.tuptup.top) dan [CONTRIBUTING.md](../../CONTRIBUTING.md) untuk panduan.

Grup pengembang sedang dibangun, bergabunglah setelah PR pertama Anda di-merge!

Grup Pengguna:

Discord: <https://www.tuptup.top>

WeChat:
<img src="../../assets/wechat.png" alt="Kode QR grup WeChat" width="512">
