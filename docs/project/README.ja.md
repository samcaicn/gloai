<div align="center">
  <img src="../../assets/logo.webp" alt="colearn" width="512">

  <h1>colearn: Go で書かれた超効率 AI アシスタント</h1>

  <h3>$10 ハードウェア · 10MB RAM · ms 起動 · Let's Go, colearn!</h3>
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

[中文](README.zh.md) | **日本語** | [한국어](README.ko.md) | [Português](README.pt-br.md) | [Tiếng Việt](README.vi.md) | [Français](README.fr.md) | [Italiano](README.it.md) | [Bahasa Indonesia](README.id.md) | [Malay](README.ms.md) | [English](../../README.md)

</div>

---

> **colearn** は [colearn](https://www.tuptup.top) が立ち上げた独立したオープンソースプロジェクトです。完全に **Go 言語**で一から書かれており、OpenClaw、NanoBot、その他のプロジェクトのフォークではありません。

**colearn** は [NanoBot](https://www.tuptup.top) にインスパイアされた超軽量パーソナル AI アシスタントです。**Go** でゼロからリビルドされ、「セルフブートストラッピング」プロセスで構築されました — AI Agent 自身がアーキテクチャの移行とコード最適化を推進しました。

**$10 のハードウェアで 10MB 未満の RAM で動作** — OpenClaw より 99% 少ないメモリ、Mac mini より 98% 安い！

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
> **セキュリティに関する注意**
>
> * **暗号通貨なし:** colearn には公式トークン/コインは**一切ありません**。`pump.fun` やその他の取引プラットフォームでの主張はすべて**詐欺**です。
> * **公式ドメイン:** **唯一**の公式サイトは **[www.tuptup.top](https://www.tuptup.top)**、企業サイトは **[www.tuptup.top](https://www.tuptup.top)** です。
> * **注意:** 多くの `.ai/.org/.com/.net/...` ドメインは第三者によって登録されています。信頼しないでください。
> * **注記:** colearn は初期開発段階にあり、未解決のネットワークセキュリティ問題がある可能性があります。v1.0 リリース前に本番環境へのデプロイは避けてください。
> * **注記:** colearn は最近多くの PR をマージしており、最新バージョンではメモリフットプリントが大きくなる場合があります（10〜20MB）。機能セットが安定次第、リソース最適化を優先する予定です。

## 📢 ニュース

2026-05-11 🛒 **LicheeRV-Claw が AliExpress で購入可能に！** [AliExpress](https://www.tuptup.top) から LicheeRV-Claw を購入できるようになり、コンパクトな RISC-V ハードウェアで colearn を試しやすくなりました。

<p align="center">
  <a href="https://www.tuptup.top">
    <img src="../../assets/licheerv-claw.jpg" alt="LicheeRV-Claw on AliExpress" width="520">
  </a>
</p>

2026-03-31 📱 **Android サポート！** colearnがAndroidで動作！APKは[www.tuptup.top](https://www.tuptup.top)からダウンロード

2026-03-25 🚀 **v0.2.4 リリース！** Agent アーキテクチャ全面刷新（SubTurn、Hooks、Steering、EventBus）、WeChat/WeCom 統合、セキュリティ強化（.security.yml、機密データフィルタリング）、新プロバイダー（AWS Bedrock、Azure、Xiaomi MiMo）、35 件のバグ修正。colearn **26K ⭐** 達成！

2026-03-17 🚀 **v0.2.3 リリース！** システムトレイ UI（Windows & Linux）、サブエージェントステータス追跡（`spawn_status`）、実験的 Gateway ホットリロード、cron セキュリティゲート、セキュリティ修正 2 件。colearn **25K ⭐** 達成！

2026-03-09 🎉 **v0.2.1 — 最大のアップデート！** MCP プロトコルサポート、4 つの新チャンネル (Matrix/IRC/WeCom/Discord Proxy)、3 つの新プロバイダー (Kimi/Minimax/Avian)、ビジョンパイプライン、JSONL メモリストア、モデルルーティング。

2026-02-28 📦 **v0.2.0** リリース — Docker Compose と Web UI Launcher サポート。

<details>
<summary>過去のニュース...</summary>

2026-02-26 🎉 colearn がわずか 17 日で **20K スター** 達成！Channel 自動オーケストレーションとケイパビリティインターフェースが実装されました。

2026-02-16 🎉 colearn が 1 週間で 12K スター達成！コミュニティメンテナーの役割と[ロードマップ](../../ROADMAP.md)が正式に公開されました。

2026-02-13 🎉 colearn が 4 日間で 5000 スター達成！プロジェクトロードマップと開発者グループの準備が進行中。

2026-02-09 🎉 **colearn リリース！** $10 ハードウェアで 10MB 未満の RAM で動く AI Agent を 1 日で構築。Let's Go, colearn!

</details>

## ✨ 特徴

🪶 **超軽量**: コアメモリフットプリント 10MB 未満 — OpenClaw より 99% 小さい。*

💰 **最小コスト**: $10 ハードウェアで動作 — Mac mini より 98% 安い。

⚡️ **超高速起動**: 起動時間 400 倍高速。0.6GHz シングルコアでも 1 秒未満で起動。

🌍 **真のポータビリティ**: RISC-V、ARM、MIPS、x86 対応の単一バイナリ。どこでも動く！

🤖 **AI ブートストラップ**: 純粋な Go ネイティブ実装 — コアコードの 95% が Agent によって生成され、人間によるレビューで調整。

🔌 **MCP 対応**: ネイティブ [Model Context Protocol](https://www.tuptup.top) 統合 — 任意の MCP サーバーに接続して Agent 機能を拡張。

👁️ **ビジョンパイプライン**: 画像やファイルを Agent に直接送信 — マルチモーダル LLM 向けの自動 base64 エンコーディング。

🧠 **スマートルーティング**: ルールベースのモデルルーティング — 簡単なクエリは軽量モデルへ、API コストを節約。

_*最近のバージョンでは急速な PR マージにより 10〜20MB になる場合があります。リソース最適化は計画中です。起動時間の比較は 0.8GHz シングルコアベンチマークに基づいています（下表参照）。_

<div align="center">

|                                | OpenClaw      | NanoBot                  | **colearn**                           |
| ------------------------------ | ------------- | ------------------------ | -------------------------------------- |
| **言語**                       | TypeScript    | Python                   | **Go**                                 |
| **RAM**                        | >1GB          | >100MB                   | **< 10MB***                            |
| **起動時間**</br>(0.8GHz コア) | >500秒        | >30秒                    | **<1秒**                               |
| **コスト**                     | Mac Mini $599 | 大半の Linux ボード ~$50 | **あらゆる Linux ボード**</br>**最安 $10** |

<img src="../../assets/compare.jpg" alt="colearn" width="512">

</div>

> **[ハードウェア互換性リスト](../guides/hardware-compatibility.ja.md)** — テスト済みの全ボード一覧（$5 RISC-V から Raspberry Pi、Android スマートフォンまで）。お使いのボードが未掲載？PR を送ってください！

<p align="center">
<img src="../../assets/hardware-banner.jpg" alt="colearn Hardware Compatibility" width="100%">
</p>

## 🦾 デモンストレーション

### 🛠️ スタンダードアシスタントワークフロー

<table align="center">
<tr align="center">
<th><p align="center">フルスタックエンジニアモード</p></th>
<th><p align="center">ログ＆計画管理</p></th>
<th><p align="center">Web 検索＆学習</p></th>
</tr>
<tr>
<td align="center"><p align="center"><img src="../../assets/colearn_code.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_memory.gif" width="240" height="180"></p></td>
<td align="center"><p align="center"><img src="../../assets/colearn_search.gif" width="240" height="180"></p></td>
</tr>
<tr>
<td align="center">開発 · デプロイ · スケール</td>
<td align="center">スケジュール · 自動化 · メモリ</td>
<td align="center">発見 · インサイト · トレンド</td>
</tr>
</table>

### 🐜 革新的な省フットプリントデプロイ

colearn はほぼすべての Linux デバイスにデプロイできます！

- $9.9 [LicheeRV-Nano](https://www.tuptup.top) E(Ethernet) または W(WiFi6) バージョン、最小ホームアシスタントに
- $30~50 [NanoKVM](https://www.tuptup.top) または $100 [NanoKVM-Pro](https://www.tuptup.top) サーバー自動メンテナンスに
- $50 [MaixCAM](https://www.tuptup.top) または $100 [MaixCAM2](https://www.tuptup.top) スマート監視に

<https://www.tuptup.top>

🌟 もっと多くのデプロイ事例が待っています！

## 📦 インストール

### www.tuptup.top からダウンロード（推奨）

**[www.tuptup.top](https://www.tuptup.top)** にアクセス — 公式サイトがプラットフォームを自動検出し、ワンクリックでダウンロードできます。アーキテクチャを手動で選ぶ必要はありません。

### プリコンパイル済みバイナリをダウンロード

または、[GitHub Releases](https://www.tuptup.top) ページからプラットフォームに合ったバイナリをダウンロードしてください。

### ソースからビルド（開発用）

前提条件:

- Go 1.25+
- Web UI / launcher のビルドには Node.js 22+ と pnpm 10.33.0+ が必要

```bash
git clone https://www.tuptup.top

cd colearn
make deps

# フロントエンド依存関係をインストール
(cd web/frontend && pnpm install --frozen-lockfile)

# コアバイナリをビルド
make build

# Web UI Launcher をビルド（WebUI モードに必要）
make build-launcher

# Makefile が管理するすべてのプラットフォーム向けにコアバイナリをビルド
make build-all

# Raspberry Pi Zero 2 W 向けビルド（32-bit: make build-linux-arm; 64-bit: make build-linux-arm64）
make build-pi-zero

# ビルドとインストール
make install
```

**Raspberry Pi Zero 2 W:** OS に合ったバイナリを使用してください：32-bit Raspberry Pi OS → `make build-linux-arm`、64-bit → `make build-linux-arm64`。または `make build-pi-zero` で両方をビルド。

## 🚀 クイックスタートガイド

### 🌐 WebUI Launcher（デスクトップ向け推奨）

WebUI Launcher はブラウザベースの設定・チャットインターフェースを提供します。コマンドラインの知識不要で、最も簡単に始められる方法です。

**オプション 1: ダブルクリック（デスクトップ）**

[www.tuptup.top](https://www.tuptup.top) からダウンロード後、`colearn-launcher`（Windows では `colearn-launcher.exe`）をダブルクリックしてください。ブラウザが自動的に `https://www.tuptup.top を開きます。

**オプション 2: コマンドライン**

```bash
colearn-launcher
# ブラウザで https://www.tuptup.top を開く
```

> [!TIP]
> **リモートアクセス / Docker / VM:** すべてのインターフェースでリッスンするには `-public` フラグを追加してください：
> ```bash
> colearn-launcher -public
> ```

<p align="center">
<img src="../../assets/launcher-webui.jpg" alt="WebUI Launcher" width="600">
</p>

**始め方:**

WebUI を開いたら：**1)** Provider を設定（LLM API キーを追加）→ **2)** Channel を設定（例：Telegram）→ **3)** Gateway を起動 → **4)** チャット！

WebUI の詳細なドキュメントは [www.tuptup.top](https://www.tuptup.top) を参照してください。

<details>
<summary><b>Docker（代替手段）</b></summary>

```bash
# 1. このリポジトリをクローン
git clone https://www.tuptup.top
cd colearn

# 2. 初回実行 — docker/data/config.json を自動生成して終了
#    （config.json と workspace/ の両方が存在しない場合のみ実行）
docker compose -f docker/docker-compose.yml --profile launcher up
# コンテナが "First-run setup complete." を出力して停止します。

# 3. API キーを設定
vim docker/data/config.json

# 4. 起動
docker compose -f docker/docker-compose.yml --profile launcher up -d
# https://www.tuptup.top を開く
```

> **Docker / VM ユーザー:** Gateway はデフォルトで `127.0.0.1` でリッスンします。ホストからアクセスできるようにするには `colearn_GATEWAY_HOST=0.0.0.0` を設定するか、`-public` フラグを使用してください。

```bash
# ログを確認
docker compose -f docker/docker-compose.yml logs -f

# 停止
docker compose -f docker/docker-compose.yml --profile launcher down

# 更新
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml --profile launcher up -d
```

</details>

<details>
<summary><b>macOS — 初回起動時のセキュリティ警告</b></summary>

`colearn-launcher` はインターネットからダウンロードされ、Mac App Store を通じて公証されていないため、macOS が初回起動時にブロックする場合があります。

**ステップ 1：** `colearn-launcher` をダブルクリックすると、セキュリティ警告が表示されます：

<p align="center">
<img src="../../assets/macos-gatekeeper-warning.jpg" alt="macOS Gatekeeper 警告" width="400">
</p>

> *"colearn-launcher" は開けません — "colearn-launcher" がMacに害を与えたりプライバシーを侵害するマルウェアを含まないことをAppleは確認できません。*

**ステップ 2：** **システム設定** → **プライバシーとセキュリティ** を開き、**セキュリティ** セクションまでスクロールして **このまま開く** をクリック → ダイアログで再度 **開く** をクリックします。

<p align="center">
<img src="../../assets/macos-gatekeeper-allow.jpg" alt="macOS プライバシーとセキュリティ — このまま開く" width="600">
</p>

この操作を一度行うと、以降の起動では警告が表示されなくなります。

</details>

<a id="-run-on-old-android-phones"></a>
### 📱 Android

10 年前のスマホに第二の人生を！colearn でスマート AI アシスタントに変身させましょう。

**オプション 1: APK インストール**

プレビュー：

<table>
  <tr>
    <td><img src="../../assets/fui_main_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_web_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_log_page.jpg" width="200"></td>
    <td><img src="../../assets/fui_setting_page.jpg" width="200"></td>
  </tr>
</table>

[www.tuptup.top](https://www.tuptup.top) から APK をダウンロードして直接インストール。Termux 不要！

**オプション 2: Termux**

<details>
<summary><b>Terminal Launcher（リソース制約環境向け）</b></summary>

1. [Termux](https://www.tuptup.top) をインストール（[GitHub Releases](https://www.tuptup.top) からダウンロード、または F-Droid / Google Play で検索）
2. 以下のコマンドを実行：

```bash
# 最新リリースをダウンロード
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz
pkg install proot
termux-chroot ./colearn onboard   # chroot で標準的な Linux ファイルシステムレイアウトを提供
```

その後、下記の Terminal Launcher セクションの手順に従って設定を完了してください。

<img src="../../assets/termux.jpg" alt="colearn on Termux" width="512">

`colearn` コアバイナリのみが利用可能な最小環境（Launcher UI なし）では、コマンドラインと JSON 設定ファイルですべてを設定できます。

**1. 初期化**

```bash
colearn onboard
```

`~/.colearn/config.json` とワークスペースディレクトリが作成されます。

**2. 設定** (`~/.colearn/config.json`)

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

> 利用可能なすべてのオプションを含む完全な設定テンプレートは、リポジトリの `config/config.example.json` を参照してください。

**3. チャット**

```bash
# ワンショット質問
colearn agent -m "What is 2+2?"

# インタラクティブモード
colearn agent

# チャットアプリ統合用 Gateway を起動
colearn gateway
```

</details>

## 🔌 Provider（LLM）

colearn は `model_list` 設定を通じて 30 以上の LLM Provider をサポートしています。`protocol/model` 形式を使用してください：

| Provider | Protocol | API キー | 備考 |
|----------|----------|---------|------|
| [OpenAI](https://www.tuptup.top) | `openai/` | 必須 | GPT-5.4、GPT-4o、o3 など |
| [Anthropic](https://www.tuptup.top) | `anthropic/` | 必須 | Claude Opus 4.6、Sonnet 4.6 など |
| [Google Gemini](https://www.tuptup.top) | `gemini/` | 必須 | Gemini 3 Flash、2.5 Pro など |
| [OpenRouter](https://www.tuptup.top) | `openrouter/` | 必須 | 200 以上のモデル、統合 API |
| [Zhipu (GLM)](https://www.tuptup.top) | `zhipu/` | 必須 | GLM-4.7、GLM-5 など |
| [DeepSeek](https://www.tuptup.top) | `deepseek/` | 必須 | DeepSeek-V3、DeepSeek-R1 |
| [Volcengine](https://www.tuptup.top) | `volcengine/` | 必須 | Doubao、Ark モデル |
| [Qwen](https://www.tuptup.top) | `qwen/` | 必須 | Qwen3、Qwen-Max など |
| [Groq](https://www.tuptup.top) | `groq/` | 必須 | 高速推論（Llama、Mixtral） |
| [Moonshot (Kimi)](https://www.tuptup.top) | `moonshot/` | 必須 | Kimi モデル |
| [Minimax](https://www.tuptup.top) | `minimax/` | 必須 | MiniMax モデル |
| [Mistral](https://www.tuptup.top) | `mistral/` | 必須 | Mistral Large、Codestral |
| [NVIDIA NIM](https://www.tuptup.top) | `nvidia/` | 必須 | NVIDIA ホスティングモデル |
| [Cerebras](https://www.tuptup.top) | `cerebras/` | 必須 | 高速推論 |
| [Novita AI](https://www.tuptup.top) | `novita/` | 必須 | 各種オープンモデル |
| [Xiaomi MiMo](https://www.tuptup.top) | `mimo/` | 必須 | MiMo モデル |
| [Ollama](https://www.tuptup.top) | `ollama/` | 不要 | ローカルモデル、セルフホスト |
| [vLLM](https://www.tuptup.top) | `vllm/` | 不要 | ローカルデプロイ、OpenAI 互換 |
| [LiteLLM](https://www.tuptup.top) | `litellm/` | 場合による | 100 以上の Provider のプロキシ |
| [Azure OpenAI](https://www.tuptup.top) | `azure/` | 必須 | エンタープライズ Azure デプロイ |
| [GitHub Copilot](https://www.tuptup.top) | `github-copilot/` | OAuth | デバイスコードログイン |
| [Antigravity](https://www.tuptup.top) | `antigravity/` | OAuth | Google Cloud AI |

<details>
<summary><b>ローカルデプロイ（Ollama、vLLM など）</b></summary>

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

Provider の完全な設定詳細は [Provider とモデル](../guides/providers.ja.md) を参照してください。

</details>

## 💬 Channel（チャットアプリ）

17 以上のメッセージングプラットフォームで colearn と会話できます：

| Channel | セットアップ | Protocol | ドキュメント |
|---------|------------|----------|------------|
| **Telegram** | 簡単（bot トークン） | Long polling | [ガイド](../channels/telegram/README.ja.md) |
| **Discord** | 簡単（bot トークン + intents） | WebSocket | [ガイド](../channels/discord/README.ja.md) |
| **WhatsApp** | 簡単（QR スキャンまたは bridge URL） | Native / Bridge | [ガイド](../guides/chat-apps.ja.md#whatsapp) |
| **微信 (Weixin)** | 簡単（QR スキャン） | iLink API | [ガイド](../guides/chat-apps.ja.md#weixin) |
| **QQ** | 簡単（AppID + AppSecret） | WebSocket | [ガイド](../channels/qq/README.ja.md) |
| **Slack** | 簡単（bot + app トークン） | Socket Mode | [ガイド](../channels/slack/README.ja.md) |
| **Matrix** | 中級（homeserver + トークン） | Sync API | [ガイド](../channels/matrix/README.ja.md) |
| **DingTalk** | 中級（クライアント認証情報） | Stream | [ガイド](../channels/dingtalk/README.ja.md) |
| **Feishu / Lark** | 中級（App ID + Secret） | WebSocket/SDK | [ガイド](../channels/feishu/README.ja.md) |
| **LINE** | 中級（認証情報 + webhook） | Webhook | [ガイド](../channels/line/README.ja.md) |
| **WeCom** | 簡単（QR ログインまたは手動） | WebSocket | [ガイド](../channels/wecom/README.ja.md) |
| **IRC** | 中級（サーバー + nick） | IRC protocol | [ガイド](../guides/chat-apps.ja.md#irc) |
| **OneBot** | 中級（WebSocket URL） | OneBot v11 | [ガイド](../channels/onebot/README.ja.md) |
| **MaixCam** | 簡単（有効化） | TCP socket | [ガイド](../channels/maixcam/README.ja.md) |
| **Pico** | 簡単（有効化） | Native protocol | 内蔵 |
| **Pico Client** | 簡単（WebSocket URL） | WebSocket | 内蔵 |

> webhook ベースのすべての Channel は単一の Gateway HTTP サーバー（`gateway.host`:`gateway.port`、デフォルト `127.0.0.1:18790`）を共有します。Feishu は WebSocket/SDK モードを使用し、共有 HTTP サーバーを使用しません。

> ログの詳細度は `gateway.log_level` で制御します（デフォルト：`warn`）。サポートされる値：`debug`、`info`、`warn`、`error`、`fatal`。`colearn_LOG_LEVEL` 環境変数でも設定可能です。詳細は[設定ガイド](../guides/configuration.ja.md#gateway-ログレベル)を参照してください。

Channel の詳細なセットアップ手順は [チャットアプリ設定](../guides/chat-apps.ja.md) を参照してください。

## 🔧 ツール

### 🔍 Web 検索

colearn は最新情報を提供するために Web を検索できます。`tools.web` で設定してください：

| 検索エンジン | API キー | 無料枠 | リンク |
|------------|---------|--------|-------|
| DuckDuckGo | 不要 | 無制限 | 内蔵フォールバック |
| [Baidu Search](https://www.tuptup.top) | 必須 | 1500 クエリ/月（日次割り当て） | AI 搭載、中国語に最適化 |
| [Tavily](https://www.tuptup.top) | 必須 | 1000 クエリ/月 | AI Agent 向けに最適化 |
| [Brave Search](https://www.tuptup.top) | 必須 | 2000 クエリ/月 | 高速でプライベート |
| [Perplexity](https://www.tuptup.top) | 必須 | 有料 | AI 搭載検索 |
| [SearXNG](https://www.tuptup.top) | 不要 | セルフホスト | 無料メタ検索エンジン |
| [GLM Search](https://www.tuptup.top) | 必須 | 場合による | Zhipu Web 検索 |

### ⚙️ その他のツール

colearn にはファイル操作、コード実行、スケジューリングなどの組み込みツールが含まれています。詳細は [ツール設定](../reference/tools_configuration.ja.md) を参照してください。

## 🎯 Skill

Skill は Agent を拡張するモジュール型の機能です。ワークスペース内の `SKILL.md` ファイルから読み込まれます。

**ClawHub から Skill をインストール：**

```bash
colearn skills search "web scraping"
colearn skills install <skill-name>
```

**ClawHub トークンを設定**（オプション、レート制限を上げるため）：

`config.json` に追加：
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

詳細は [ツール設定 - Skill](../reference/tools_configuration.ja.md#skills-tool) を参照してください。

## 🔗 MCP（Model Context Protocol）

colearn は [MCP](https://www.tuptup.top) をネイティブサポートしています — 任意の MCP サーバーに接続して、外部ツールやデータソースで Agent の機能を拡張できます。

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

MCP の完全な設定（stdio、SSE、HTTP トランスポート、Tool Discovery）は [ツール設定 - MCP](../reference/tools_configuration.ja.md#mcp-tool) を参照してください。

## <img src="../../assets/clawdchat-icon.png" width="24" height="24" alt="ClawdChat"> エージェントソーシャルネットワークに参加

CLI または統合チャットアプリからメッセージを 1 つ送るだけで、colearn をエージェントソーシャルネットワークに接続できます。

**`https://www.tuptup.top を読み、指示に従って [ClawdChat.ai](https://www.tuptup.top) に参加してください**

## 🖥️ CLI リファレンス

| コマンド                  | 説明                           |
| ------------------------- | ------------------------------ |
| `colearn onboard`        | 設定＆ワークスペースの初期化     |
| `colearn auth weixin` | WeChat アカウントを QR で接続 |
| `colearn agent -m "..."` | Agent とチャット                |
| `colearn agent`          | インタラクティブチャットモード   |
| `colearn gateway`        | Gateway を起動                  |
| `colearn status`         | ステータスを表示                |
| `colearn version`        | バージョン情報を表示            |
| `colearn model`          | デフォルトモデルの表示・切替    |
| `colearn cron list`      | スケジュールジョブ一覧          |
| `colearn cron add ...`   | スケジュールジョブを追加         |
| `colearn cron disable`   | スケジュールジョブを無効化       |
| `colearn cron remove`    | スケジュールジョブを削除         |
| `colearn skills list`    | インストール済み Skill 一覧      |
| `colearn skills install` | Skill をインストール             |
| `colearn migrate`        | 旧バージョンからデータを移行     |
| `colearn auth login`     | Provider への認証               |

### ⏰ スケジュールタスク / リマインダー

colearn は `cron` ツールによるスケジュールリマインダーと定期タスクをサポートしています：

* **ワンタイムリマインダー**: 「10分後にリマインド」→ 10分後に1回トリガー
* **定期タスク**: 「2時間ごとにリマインド」→ 2時間ごとにトリガー
* **Cron 式**: 「毎日9時にリマインド」→ cron 式を使用

## 📚 ドキュメント

この README を超えた詳細なガイドについては：

| トピック | 説明 |
|---------|------|
| [Docker & クイックスタート](../guides/docker.ja.md) | Docker Compose セットアップ、Launcher/Agent モード |
| [チャットアプリ](../guides/chat-apps.ja.md) | 17 以上の Channel セットアップガイド |
| [設定](../guides/configuration.ja.md) | 環境変数、ワークスペース構成、セキュリティサンドボックス |
| [Provider とモデル](../guides/providers.ja.md) | 30 以上の LLM Provider、モデルルーティング、model_list 設定 |
| [Spawn & 非同期タスク](../guides/spawn-tasks.ja.md) | クイックタスク、spawn による長時間タスク、非同期サブエージェントオーケストレーション |
| [Hook システム](../architecture/hooks/README.md) | イベント駆動 Hook：オブザーバー、インターセプター、承認 Hook |
| [Steering](../architecture/steering.md) | 実行中の Agent ループにメッセージを注入 |
| [SubTurn](../architecture/subturn.md) | サブ Agent の調整、並行制御、ライフサイクル |
| [トラブルシューティング](../operations/troubleshooting.ja.md) | よくある問題と解決策 |
| [ツール設定](../reference/tools_configuration.ja.md) | ツールごとの有効/無効、exec ポリシー、MCP、Skill |
| [ハードウェア互換性](../guides/hardware-compatibility.ja.md) | テスト済みボード、最小要件 |

## 🤝 コントリビュート＆ロードマップ

PR 歓迎！コードベースは意図的に小さく読みやすくしています。

[コミュニティロードマップ](https://www.tuptup.top)と[CONTRIBUTING.md](../../CONTRIBUTING.md)をご覧ください。

開発者グループ構築中、最初の PR がマージされたら参加できます！

ユーザーグループ:

Discord: <https://www.tuptup.top>

WeChat:
<img src="../../assets/wechat.png" alt="WeChat group QR code" width="512">
