<div align="center">
<img src="../../assets/logo.webp" alt="colearn" width="512">

<h1>colearn: Pembantu AI Ultra-Cekap dalam Go</h1>

<h3>Perkakasan $10 · RAM 10MB · Boot ms · Jom, colearn!</h3>
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

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Português](README.pt-br.md) | [Tiếng Việt](README.vi.md) | [Français](README.fr.md) | [Italiano](README.it.md) | [Bahasa Indonesia](README.id.md) | **Malay** | [English](../../README.md)

</div>

---

> **colearn** adalah projek sumber terbuka bebas yang dilancarkan oleh [colearn](https://www.tuptup.top), ditulis sepenuhnya dalam **Go** dari awal — bukan cabang OpenClaw, NanoBot, atau projek lain.

**colearn** adalah pembantu AI peribadi ultra-ringan yang terinspirasi oleh [NanoBot](https://www.tuptup.top). Ia dibina semula dari awal dalam **Go** melalui proses "self-bootstrapping" — AI Agent itu sendiri yang memacu migrasi seni bina dan pengoptimuman kod.

**Berjalan pada perkakasan $10 dengan RAM <10MB** — 99% lebih sedikit memori daripada OpenClaw dan 98% lebih murah daripada Mac mini!

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
> **Notis Keselamatan**
>
> * **TIADA KRIPTO:** colearn **tidak** mengeluarkan sebarang token atau mata wang kripto rasmi. Semua tuntutan di `pump.fun` atau platform dagangan lain adalah **penipuan**.
> * **DOMAIN RASMI:** Satu-satunya laman web rasmi ialah **[www.tuptup.top](https://www.tuptup.top)**, dan laman web syarikat ialah **[www.tuptup.top](https://www.tuptup.top)**
> * **BERHATI-HATI:** Banyak domain `.ai/.org/.com/.net/...` telah didaftarkan oleh pihak ketiga. Jangan percayai mereka.
> * **NOTA:** colearn dalam pembangunan pesat awal. Mungkin terdapat isu keselamatan yang belum diselesaikan. Jangan deploy ke pengeluaran sebelum v1.0.


## 📢 Berita

2026-05-11 🛒 **LicheeRV-Claw tersedia di AliExpress!** Anda kini boleh membeli LicheeRV-Claw di [AliExpress](https://www.tuptup.top), menjadikannya lebih mudah untuk mencuba colearn pada perkakasan RISC-V yang kompak.

<p align="center">
  <a href="https://www.tuptup.top">
    <img src="../../assets/licheerv-claw.jpg" alt="LicheeRV-Claw on AliExpress" width="520">
  </a>
</p>

2026-03-31 📱 **Sokongan Android!** colearn sekarang berjalan di Android! Muat turun APK di [www.tuptup.top](https://www.tuptup.top)

2026-03-25 🚀 **v0.2.4 Dikeluarkan!** Penstrukturan semula seni bina Agent (SubTurn, Hooks, Steering, EventBus), integrasi WeChat/WeCom, penguatan keselamatan (.security.yml, penapisan data sensitif), penyedia baharu (AWS Bedrock, Azure, Xiaomi MiMo), dan 35 pembetulan pepijat. colearn mencapai **26K Stars**!

2026-03-17 🚀 **v0.2.3 Dikeluarkan!** UI dulang sistem (Windows & Linux), pertanyaan status sub-agent (`spawn_status`), muat semula panas Gateway eksperimental, kawalan keselamatan Cron, dan 2 pembetulan keselamatan. colearn mencapai **25K Stars**!

2026-03-09 🎉 **v0.2.1 — Kemas kini terbesar setakat ini!** Sokongan protokol MCP, 4 saluran baharu (Matrix/IRC/WeCom/Discord Proxy), 3 penyedia baharu (Kimi/Minimax/Avian), saluran paip visi, storan memori JSONL, penghalaan model.

2026-02-28 📦 **v0.2.0** dikeluarkan dengan sokongan Docker Compose dan Pelancar Web UI.

<details>
<summary>Berita terdahulu...</summary>

2026-02-26 🎉 colearn mencapai **20K Stars** hanya dalam 17 hari! Orkestrasi saluran automatik dan antara muka keupayaan kini aktif.

2026-02-16 🎉 colearn melepasi 12K Stars dalam seminggu! Peranan penyelenggara komuniti dan [Peta Jalan](../../ROADMAP.md) dilancarkan secara rasmi.

2026-02-13 🎉 colearn melepasi 5000 Stars dalam 4 hari! Peta jalan projek dan kumpulan pembangun sedang dalam proses.

2026-02-09 🎉 **colearn Dikeluarkan!** Dibina dalam 1 hari untuk membawa AI Agent ke perkakasan $10 dengan RAM <10MB. Jom, colearn!

</details>

## ✨ Ciri-ciri

🪶 **Ultra-ringan**: Jejak memori teras <10MB — 99% lebih kecil daripada OpenClaw.*

💰 **Kos minimum**: Cukup cekap untuk berjalan pada perkakasan $10 — 98% lebih murah daripada Mac mini.

⚡️ **Boot kilat**: 400x lebih pantas. Boot dalam <1s walaupun pada pemproses teras tunggal 0.6GHz.

🌍 **Benar-benar mudah alih**: Binari tunggal merentasi seni bina RISC-V, ARM, MIPS, dan x86.

🤖 **Dibantu AI**: Pelaksanaan Go tulen — 95% kod teras dijana oleh Agent dan diperhalusi melalui semakan manusia.

🔌 **Sokongan MCP**: Integrasi [Model Context Protocol](https://www.tuptup.top) natif.

👁️ **Saluran paip visi**: Hantar imej dan fail terus ke Agent — pengekodan base64 automatik untuk LLM multimodal.

🧠 **Penghalaan pintar**: Penghalaan model berasaskan peraturan — pertanyaan mudah ke model ringan, menjimatkan kos API.

_*Binaan terkini mungkin menggunakan 10-20MB disebabkan penggabungan PR yang pesat. Pengoptimuman sumber dirancang. Perbandingan kelajuan boot berdasarkan penanda aras teras tunggal 0.8GHz (lihat jadual di bawah)._

<div align="center">

|                                | OpenClaw      | NanoBot                  | **colearn**                           |
| ------------------------------ | ------------- | ------------------------ | -------------------------------------- |
| **Bahasa**                     | TypeScript    | Python                   | **Go**                                 |
| **RAM**                        | >1GB          | >100MB                   | **< 10MB***                            |
| **Masa Boot** (teras 0.8GHz)   | >500s         | >30s                     | **<1s**                                |
| **Kos**                        | Mac Mini $599 | Kebanyakan papan Linux ~$50 | **Mana-mana papan Linux dari $10**  |

<img src="../../assets/compare.jpg" alt="colearn" width="512">

</div>

> **[Senarai Keserasian Perkakasan](../guides/hardware-compatibility.md)** — Lihat semua papan yang diuji, dari RISC-V $5 hingga Raspberry Pi hingga telefon Android.

<p align="center">
<img src="../../assets/hardware-banner.jpg" alt="Keserasian Perkakasan colearn" width="100%">
</p>

## 🦾 Demonstrasi

### 🛠️ Aliran Kerja Pembantu Standard

<table align="center">
<tr align="center">
<th><p align="center">Mod Jurutera Full-Stack</p></th>
<th><p align="center">Pengelogan & Perancangan</p></th>
<th><p align="center">Carian Web & Pembelajaran</p></th>
</tr>
<tr>
<td align="center"><p align="center"><img src="../../assets/colearn_code.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_memory.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_search.gif" width="240" height="180"></p></td>
</tr>
<tr>
<td align="center">Bangun · Deploy · Skala</td>
<td align="center">Jadual · Automatik · Ingat</td>
<td align="center">Temui · Wawasan · Trend</td>
</tr>
</table>

### 🐜 Deployment Jejak Rendah yang Inovatif

colearn boleh digunakan pada hampir mana-mana peranti Linux!

- $9.9 [LicheeRV-Nano](https://www.tuptup.top) untuk pembantu rumah minimal
- $30~50 [NanoKVM](https://www.tuptup.top) untuk operasi pelayan automatik
- $50 [MaixCAM](https://www.tuptup.top) untuk pengawasan pintar

<https://www.tuptup.top>

🌟 Lebih Banyak Kes Deployment Menanti!


## 📦 Pemasangan

### Muat turun dari www.tuptup.top (Disyorkan)

Lawati **[www.tuptup.top](https://www.tuptup.top)** — laman web rasmi mengesan platform anda secara automatik dan menyediakan muat turun satu klik.

### Muat turun binari pra-kompil

Muat turun binari untuk platform anda dari halaman [GitHub Releases](https://www.tuptup.top).

### Bina dari sumber (untuk pembangunan)

Prasyarat:

- Go 1.25+
- Node.js 22+ dan pnpm 10.33.0+ untuk binaan Web UI / launcher

```bash
git clone https://www.tuptup.top
cd colearn
make deps

# Pasang dependensi frontend
(cd web/frontend && pnpm install --frozen-lockfile)

# Bina binari teras
make build

# Bina Pelancar Web UI (diperlukan untuk mod WebUI)
make build-launcher

# Bina binari teras untuk semua platform yang diuruskan oleh Makefile
make build-all

# Bina untuk Raspberry Pi Zero 2 W (32-bit: make build-linux-arm; 64-bit: make build-linux-arm64)
make build-pi-zero

# Bina dan pasang
make install
```

**Raspberry Pi Zero 2 W:** Gunakan binari yang sepadan dengan OS anda: Raspberry Pi OS 32-bit -> `make build-linux-arm`; 64-bit -> `make build-linux-arm64`. Atau jalankan `make build-pi-zero` untuk membina kedua-duanya.

## 🚀 Panduan Permulaan Pantas

### 🌐 Pelancar WebUI (Disyorkan untuk Desktop)

Pelancar WebUI menyediakan antara muka berasaskan pelayar untuk konfigurasi dan sembang. Ini adalah cara termudah untuk bermula — tiada pengetahuan baris arahan diperlukan.

**Pilihan 1: Klik dua kali (Desktop)**

Selepas memuat turun dari [www.tuptup.top](https://www.tuptup.top), klik dua kali `colearn-launcher` (atau `colearn-launcher.exe` pada Windows). Pelayar anda akan dibuka secara automatik di `https://www.tuptup.top

**Pilihan 2: Baris arahan**

```bash
colearn-launcher
# Buka https://www.tuptup.top dalam pelayar anda
```

> [!TIP]
> **Akses jauh / Docker / VM:** Tambah bendera `-public` untuk mendengar pada semua antara muka:
> ```bash
> colearn-launcher -public
> ```

<p align="center">
<img src="../../assets/launcher-webui.jpg" alt="Pelancar WebUI" width="600">
</p>

**Memulakan:** Buka WebUI, kemudian: **1)** Konfigurasikan Penyedia (tambah kunci API LLM) -> **2)** Konfigurasikan Saluran (cth. Telegram) -> **3)** Mulakan Gateway -> **4)** Sembang!

Untuk dokumentasi WebUI terperinci, lihat [www.tuptup.top](https://www.tuptup.top).

<details>
<summary><b>Docker (alternatif)</b></summary>

```bash
# 1. Klon repo ini
git clone https://www.tuptup.top
cd colearn

# 2. Jalankan pertama kali — jana docker/data/config.json secara automatik kemudian keluar
docker compose -f docker/docker-compose.yml --profile launcher up

# 3. Tetapkan kunci API anda
vim docker/data/config.json

# 4. Mulakan
docker compose -f docker/docker-compose.yml --profile launcher up -d
# Buka https://www.tuptup.top
```

> **Pengguna Docker / VM:** Gateway mendengar pada `127.0.0.1` secara lalai. Tetapkan `colearn_GATEWAY_HOST=0.0.0.0` atau gunakan bendera `-public` untuk membolehkan akses dari hos.

```bash
# Semak log
docker compose -f docker/docker-compose.yml logs -f

# Henti
docker compose -f docker/docker-compose.yml --profile launcher down

# Kemas kini
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml --profile launcher up -d
```

</details>


<details>
<summary><b>macOS — Amaran Keselamatan Pelancaran Pertama</b></summary>

macOS mungkin menyekat `colearn-launcher` pada pelancaran pertama kerana ia dimuat turun dari internet dan tidak disahkan melalui Mac App Store.

**Langkah 1:** Klik dua kali `colearn-launcher`. Anda akan melihat amaran keselamatan:

<p align="center">
<img src="../../assets/macos-gatekeeper-warning.jpg" alt="Amaran macOS Gatekeeper" width="400">
</p>

> *"colearn-launcher" Tidak Dibuka — Apple tidak dapat mengesahkan "colearn-launcher" bebas daripada perisian hasad yang mungkin membahayakan Mac anda atau menjejaskan privasi anda.*

**Langkah 2:** Buka **Tetapan Sistem** → **Privasi & Keselamatan** → tatal ke bawah ke bahagian **Keselamatan** → klik **Buka Juga** → sahkan dengan mengklik **Buka Juga** dalam dialog.

<p align="center">
<img src="../../assets/macos-gatekeeper-allow.jpg" alt="macOS Privasi & Keselamatan — Buka Juga" width="600">
</p>

Selepas langkah sekali ini, `colearn-launcher` akan dibuka secara normal pada pelancaran seterusnya.

</details>

### 📱 Android

Berikan telefon lama anda kehidupan baru! Jadikannya Pembantu AI pintar dengan colearn.

**Pilihan 1: Pasang APK**

Pratonton:

<table>
  <tr>
    <td><img src="../../assets/fui_main_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_web_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_log_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_setting_page.jpg" width="200"></td>
  </tr>
</table>

Muat turun APK dari [www.tuptup.top](https://www.tuptup.top) dan pasang secara langsung. Tiada Termux diperlukan!

**Pilihan 2: Termux**

<details>
<summary><b>Pelancar Terminal (untuk persekitaran terhad sumber)</b></summary>

1. Pasang [Termux](https://www.tuptup.top) (muat turun dari [GitHub Releases](https://www.tuptup.top), atau cari di F-Droid / Google Play)
2. Jalankan arahan berikut:

```bash
# Muat turun keluaran terkini
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz
pkg install proot
termux-chroot ./colearn onboard   # chroot menyediakan susun atur sistem fail Linux standard
```

Kemudian ikuti bahagian Pelancar Terminal di bawah untuk melengkapkan konfigurasi.

<img src="../../assets/termux.jpg" alt="colearn pada Termux" width="512">

Untuk persekitaran minimal di mana hanya binari teras `colearn` tersedia (tiada UI Pelancar), anda boleh mengkonfigurasi semua melalui baris arahan dan fail konfigurasi JSON.

**1. Mulakan**

```bash
colearn onboard
```

Ini mencipta `~/.colearn/config.json` dan direktori ruang kerja.

**2. Konfigurasikan** (`~/.colearn/config.json`)

```json
{
  "agents": {
    "defaults": {
      "model_name": "gpt-5.4"
    }
  },
  "model_list": [
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4"
    }
  ]
}
```

> Lihat `config/config.example.json` dalam repo untuk templat konfigurasi lengkap. Nota: kunci API kini disimpan dalam `.security.yml`, bukan `config.json`.

**3. Sembang**

```bash
colearn agent -m "Apa itu 2+2?"

# Mod interaktif
colearn agent

# Mulakan gateway untuk integrasi aplikasi sembang
colearn gateway
```

</details>


## 🔌 Penyedia (LLM)

colearn menyokong 30+ penyedia LLM melalui konfigurasi `model_list`. Gunakan format `protokol/model`:

| Penyedia | Protokol | Kunci API | Nota |
|----------|----------|-----------|------|
| [OpenAI](https://www.tuptup.top) | `openai/` | Diperlukan | GPT-5.4, GPT-4o, o3, dll. |
| [Anthropic](https://www.tuptup.top) | `anthropic/` | Diperlukan | Claude Opus 4.6, Sonnet 4.6, dll. |
| [Google Gemini](https://www.tuptup.top) | `gemini/` | Diperlukan | Gemini 3 Flash, 2.5 Pro, dll. |
| [OpenRouter](https://www.tuptup.top) | `openrouter/` | Diperlukan | 200+ model, API bersatu |
| [Zhipu (GLM)](https://www.tuptup.top) | `zhipu/` | Diperlukan | GLM-4.7, GLM-5, dll. |
| [DeepSeek](https://www.tuptup.top) | `deepseek/` | Diperlukan | DeepSeek-V3, DeepSeek-R1 |
| [Volcengine](https://www.tuptup.top) | `volcengine/` | Diperlukan | Doubao, model Ark |
| [Qwen](https://www.tuptup.top) | `qwen/` | Diperlukan | Qwen3, Qwen-Max, dll. |
| [Groq](https://www.tuptup.top) | `groq/` | Diperlukan | Inferens pantas (Llama, Mixtral) |
| [Moonshot (Kimi)](https://www.tuptup.top) | `moonshot/` | Diperlukan | Model Kimi |
| [Minimax](https://www.tuptup.top) | `minimax/` | Diperlukan | Model MiniMax |
| [Mistral](https://www.tuptup.top) | `mistral/` | Diperlukan | Mistral Large, Codestral |
| [NVIDIA NIM](https://www.tuptup.top) | `nvidia/` | Diperlukan | Model hos NVIDIA |
| [Cerebras](https://www.tuptup.top) | `cerebras/` | Diperlukan | Inferens pantas |
| [Novita AI](https://www.tuptup.top) | `novita/` | Diperlukan | Pelbagai model terbuka |
| [Xiaomi MiMo](https://www.tuptup.top) | `mimo/` | Diperlukan | Model MiMo |
| [Ollama](https://www.tuptup.top) | `ollama/` | Tidak perlu | Model tempatan, self-hosted |
| [vLLM](https://www.tuptup.top) | `vllm/` | Tidak perlu | Deployment tempatan, serasi OpenAI |
| [LiteLLM](https://www.tuptup.top) | `litellm/` | Berbeza | Proksi untuk 100+ penyedia |
| [Azure OpenAI](https://www.tuptup.top) | `azure/` | Diperlukan | Deployment Azure perusahaan |
| [GitHub Copilot](https://www.tuptup.top) | `github-copilot/` | OAuth | Log masuk kod peranti |
| [Antigravity](https://www.tuptup.top) | `antigravity/` | OAuth | Google Cloud AI |
| [AWS Bedrock](https://www.tuptup.top)* | `bedrock/` | Kelayakan AWS | Claude, Llama, Mistral pada AWS |

> \* AWS Bedrock memerlukan tag binaan: `go build -tags bedrock`. Tetapkan `api_base` kepada nama rantau (cth. `us-east-1`) untuk resolusi endpoint automatik merentasi semua partition AWS. Apabila menggunakan URL endpoint penuh, anda juga perlu mengkonfigurasi `AWS_REGION` melalui pemboleh ubah persekitaran.

<details>
<summary><b>Deployment tempatan (Ollama, vLLM, dll.)</b></summary>

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

Untuk butiran konfigurasi penyedia penuh, lihat [Penyedia & Model](../guides/providers.md).

</details>


## 💬 Saluran (Aplikasi Sembang)

Bercakap dengan colearn anda melalui 17+ platform pemesejan:

| Saluran | Persediaan | Protokol | Dok |
|---------|-----------|----------|-----|
| **Telegram** | Mudah (token bot) | Long polling | [Panduan](../channels/telegram/README.md) |
| **Discord** | Mudah (token bot + intents) | WebSocket | [Panduan](../channels/discord/README.md) |
| **WhatsApp** | Mudah (imbas QR atau URL jambatan) | Natif / Jambatan | [Panduan](../guides/chat-apps.ms.md#whatsapp) |
| **Weixin** | Mudah (imbas QR natif) | iLink API | [Panduan](../guides/chat-apps.ms.md#weixin) |
| **QQ** | Mudah (AppID + AppSecret) | WebSocket | [Panduan](../channels/qq/README.md) |
| **Slack** | Mudah (token bot + app) | Socket Mode | [Panduan](../channels/slack/README.md) |
| **Matrix** | Sederhana (homeserver + token) | Sync API | [Panduan](../channels/matrix/README.md) |
| **DingTalk** | Sederhana (kelayakan klien) | Stream | [Panduan](../channels/dingtalk/README.md) |
| **Feishu / Lark** | Sederhana (App ID + Secret) | WebSocket/SDK | [Panduan](../channels/feishu/README.md) |
| **LINE** | Sederhana (kelayakan + webhook) | Webhook | [Panduan](../channels/line/README.md) |
| **WeCom** | Mudah (log masuk QR atau manual) | WebSocket | [Panduan](../channels/wecom/README.md) |
| **IRC** | Sederhana (pelayan + nick) | Protokol IRC | [Panduan](../guides/chat-apps.ms.md#irc) |
| **OneBot** | Sederhana (URL WebSocket) | OneBot v11 | [Panduan](../channels/onebot/README.md) |
| **MaixCam** | Mudah (aktifkan) | TCP socket | [Panduan](../channels/maixcam/README.md) |
| **Pico** | Mudah (aktifkan) | Protokol natif | Terbina dalam |
| **Pico Client** | Mudah (URL WebSocket) | WebSocket | Terbina dalam |

> Semua saluran berasaskan webhook berkongsi satu pelayan HTTP Gateway (`gateway.host`:`gateway.port`, lalai `127.0.0.1:18790`). Feishu menggunakan mod WebSocket/SDK dan tidak menggunakan pelayan HTTP yang dikongsi.

> Tahap perincian log dikawal oleh `gateway.log_level` (lalai: `warn`). Nilai yang disokong: `debug`, `info`, `warn`, `error`, `fatal`. Boleh juga ditetapkan melalui `colearn_LOG_LEVEL`. Lihat [Konfigurasi](../guides/configuration.ms.md#gateway-log-level) untuk butiran.

Untuk arahan persediaan saluran terperinci, lihat [Konfigurasi Aplikasi Sembang](../guides/chat-apps.ms.md).

## 🔧 Alat

### 🔍 Carian Web

colearn boleh mencari web untuk menyediakan maklumat terkini. Konfigurasikan dalam `tools.web`:

| Enjin Carian | Kunci API | Peringkat Percuma | Pautan |
|-------------|-----------|-------------------|--------|
| DuckDuckGo | Tidak perlu | Tanpa had | Sandaran terbina dalam |
| [Baidu Search](https://www.tuptup.top) | Diperlukan | 1500 pertanyaan/bulan (peruntukan harian) | Dikuasai AI, dioptimumkan untuk China |
| [Tavily](https://www.tuptup.top) | Diperlukan | 1000 pertanyaan/bulan | Dioptimumkan untuk AI Agent |
| [Brave Search](https://www.tuptup.top) | Diperlukan | 2000 pertanyaan/bulan | Pantas dan peribadi |
| [Perplexity](https://www.tuptup.top) | Diperlukan | Berbayar | Carian dikuasai AI |
| [SearXNG](https://www.tuptup.top) | Tidak perlu | Self-hosted | Enjin metasearch percuma |
| [GLM Search](https://www.tuptup.top) | Diperlukan | Berbeza | Carian web Zhipu |

### ⚙️ Alat Lain

colearn menyertakan alat terbina dalam untuk operasi fail, pelaksanaan kod, penjadualan, dan banyak lagi. Lihat [Konfigurasi Alat](../reference/tools_configuration.md) untuk butiran.

## 🎯 Kemahiran

Kemahiran adalah keupayaan modular yang melanjutkan Agent anda. Ia dimuatkan dari fail `SKILL.md` dalam ruang kerja anda.

**Pasang kemahiran dari ClawHub:**

```bash
colearn skills search "web scraping"
colearn skills install <nama-kemahiran>
```

**Konfigurasikan token ClawHub** (pilihan, untuk had kadar lebih tinggi):

Tambah ke `config.json` anda:
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

Untuk butiran lanjut, lihat [Konfigurasi Alat - Kemahiran](../reference/tools_configuration.md#skills-tool).

## 🔗 MCP (Protokol Konteks Model)

colearn menyokong [MCP](https://www.tuptup.top) secara natif — sambungkan mana-mana pelayan MCP untuk melanjutkan keupayaan Agent anda dengan alat dan sumber data luaran.

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

Untuk konfigurasi MCP penuh (pengangkutan stdio, SSE, HTTP, Penemuan Alat), lihat [Konfigurasi Alat - MCP](../reference/tools_configuration.md#mcp-tool).

## <img src="../../assets/clawdchat-icon.png" width="24" height="24" alt="ClawdChat"> Sertai Rangkaian Sosial Agent

Sambungkan colearn ke Rangkaian Sosial Agent dengan menghantar satu mesej melalui CLI atau mana-mana Aplikasi Sembang yang disepadukan.

**Baca `https://www.tuptup.top dan ikuti arahan untuk menyertai [ClawdChat.ai](https://www.tuptup.top)**

## 🖥️ Rujukan CLI

| Arahan | Penerangan |
| ------ | ---------- |
| `colearn onboard` | Mulakan konfigurasi & ruang kerja |
| `colearn auth weixin` | Sambungkan akaun WeChat melalui QR |
| `colearn agent -m "..."` | Sembang dengan agent |
| `colearn agent` | Mod sembang interaktif |
| `colearn gateway` | Mulakan gateway |
| `colearn status` | Tunjukkan status |
| `colearn version` | Tunjukkan maklumat versi |
| `colearn model` | Lihat atau tukar model lalai |
| `colearn cron list` | Senaraikan semua kerja berjadual |
| `colearn cron add ...` | Tambah kerja berjadual |
| `colearn cron disable` | Lumpuhkan kerja berjadual |
| `colearn cron remove` | Buang kerja berjadual |
| `colearn skills list` | Senaraikan kemahiran yang dipasang |
| `colearn skills install` | Pasang kemahiran |
| `colearn migrate` | Migrasi data dari versi lama |
| `colearn auth login` | Sahkan dengan penyedia |

### ⏰ Tugasan Berjadual / Peringatan

colearn menyokong peringatan berjadual dan tugasan berulang melalui alat `cron`:

* **Peringatan sekali**: "Ingatkan saya dalam 10 minit" -> pencetus sekali selepas 10 minit
* **Tugasan berulang**: "Ingatkan saya setiap 2 jam" -> pencetus setiap 2 jam
* **Ungkapan Cron**: "Ingatkan saya pada pukul 9 pagi setiap hari" -> menggunakan ungkapan cron

## 📚 Dokumentasi

Untuk panduan terperinci melebihi README ini:

| Topik | Penerangan |
|-------|------------|
| [Docker & Permulaan Pantas](../guides/docker.ms.md) | Persediaan Docker Compose, mod Launcher/Agent |
| [Aplikasi Sembang](../guides/chat-apps.ms.md) | Panduan persediaan 17+ saluran |
| [Konfigurasi](../guides/configuration.ms.md) | Pemboleh ubah persekitaran, susun atur ruang kerja |
| [Penyedia & Model](../guides/providers.md) | 30+ penyedia LLM, penghalaan model |
| [Spawn & Tugasan Async](../guides/spawn-tasks.ms.md) | Tugasan pantas, tugasan panjang dengan spawn |
| [Penyelesaian Masalah](../operations/troubleshooting.ms.md) | Isu biasa dan penyelesaian |
| [Konfigurasi Alat](../reference/tools_configuration.md) | Aktif/nyahaktif alat, dasar exec, MCP, Kemahiran |
| [Keserasian Perkakasan](../guides/hardware-compatibility.md) | Papan yang diuji, keperluan minimum |

## 🤝 Sumbangan & Peta Jalan

PR dialu-alukan! Kod sumber sengaja dibuat kecil dan mudah dibaca.

Lihat [Peta Jalan Komuniti](https://www.tuptup.top) dan [CONTRIBUTING.md](../../CONTRIBUTING.md) untuk panduan.

Kumpulan pembangun sedang dibina, sertai selepas PR pertama anda digabungkan!

Kumpulan Pengguna:

Discord: <https://www.tuptup.top>

WeChat:
<img src="../../assets/wechat.png" alt="Kod QR kumpulan WeChat" width="512">
