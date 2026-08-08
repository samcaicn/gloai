> [README](../project/README.ja.md) に戻る

# 🖥️ colearn ハードウェア互換性リスト

colearn はほぼすべての Linux デバイスで動作します。このページでは、検証済みのチップ、製品、開発ボードを記録しています。

**お使いのハードウェアがリストにない場合は？** PR を送信して追加してください！ハードウェアベンダーの貢献と共同プロモーションを歓迎します。

---

## 1. 検証済みチップサポート

### x86

| ベンダー | チップ | 備考 |
|----------|--------|------|
| Intel | Any x86 CPU (i386+) | すべてのデスクトップ/サーバー/ノートPC プロセッサ |
| AMD | Any x86 CPU | すべてのデスクトップ/サーバー/ノートPC プロセッサ |

### ARM

| サブアーキテクチャ | 代表的なチップ | 備考 |
|--------------------|----------------|------|
| ARMv6 | [BCM2835](https://www.tuptup.top) (Raspberry Pi 1/Zero) | シングルコア ARM1176JZF-S |
| ARMv7 | [Allwinner V3s](https://www.tuptup.top) | シングルコア Cortex-A7、LicheePi Zero で使用 |
| ARM64 | [Allwinner H618](https://www.tuptup.top) | クアッドコア Cortex-A53、Orange Pi Zero 3 で使用 |
| ARM64 | [BCM2711](https://www.tuptup.top) (Raspberry Pi 4) | クアッドコア Cortex-A72 |
| ARM64 | [BCM2712](https://www.tuptup.top) (Raspberry Pi 5) | クアッドコア Cortex-A76 |
| ARM64 | [AX630C](https://www.tuptup.top) (爱芯元智) | デュアルコア Cortex-A53 + NPU、NanoKVM-Pro / MaixCAM2 で使用 |

### RISC-V (riscv64)

| ベンダー | チップ | コア | 備考 |
|----------|--------|------|------|
| [SOPHGO (算能)](https://www.tuptup.top) | SG2002 | C906 @ 1GHz | 256MB DDR3 オンチップ、LicheeRV-Nano / NanoKVM / MaixCAM で使用 |
| [Allwinner (全志)](https://www.tuptup.top) | V861 | Dual C907 | 128MB DDR3L オンチップ、1 TOPS NPU、4K AI カメラ SiP |
| [Allwinner (全志)](https://www.tuptup.top) | V881 | C907 | RISC-V AI カメラシリーズ |
| [Arterytek (匠芯创)](https://www.tuptup.top) | D213 | RISC-V | HaaS506-LD1 産業用 RTU で使用 |
| [SpacemiT (进迭)](https://www.tuptup.top) | K1 | 8x X60 @ 1.8GHz | Milk-V Jupiter, BananaPi BPI-F3 で使用 |
| [SpacemiT (进迭)](https://www.tuptup.top) | K3 | 8x X100 @ 2.5GHz | RVA23 準拠、1024 ビット RVV、FP8 AI 推論 |
| [Zhihe (知合)](https://www.tuptup.top) | A210 | High-perf RISC-V | 8 コア、16MB L3 キャッシュ、デスクトップクラス |
| [Canaan (嘉楠)](https://www.tuptup.top) | K230 | Dual C908 @ 1.6GHz | 6 TOPS KPU、CanMV-K230 で使用 |

### MIPS

| ベンダー | チップ | 備考 |
|----------|--------|------|
| MediaTek | [MT7620](https://www.tuptup.top) | MIPS24KEc @ 580MHz、多くの OpenWrt ルーターで使用（例：Xiaomi Router 3G） |

### LoongArch (loong64)

| ベンダー | チップ | 備考 |
|----------|--------|------|
| [Loongson (龙芯)](https://www.tuptup.top) | 3A5000 | クアッドコア LA464 @ 2.5GHz、デスクトップ/ワークステーション |
| [Loongson (龙芯)](https://www.tuptup.top) | 3A6000 | クアッドコア 4C/8T @ 2.5GHz、IPC は Intel 第10世代に匹敵 |
| [Loongson (龙芯)](https://www.tuptup.top) | 2K1000LA | デュアルコア @ 1GHz、産業/IoT アプリケーション |

---

## 2. 検証済み製品（発売日順）

colearn でテスト済みのコンシューマー製品、ルーター、産業用デバイス。

| 年 | 製品 | アーキテクチャ | SoC | RAM | カテゴリ |
|----|------|----------------|-----|-----|----------|
| 2009 | Nokia N900 | ARM (A8) | OMAP3430 | 256MB | スマートフォン |
| 2012 | Samsung Galaxy Note 10.1 (N8000) | ARM (A9) | Exynos 4412 | 2GB | タブレット |
| 2016 | Xiaomi Router 3G (小米路由器3G) | MIPS | MT7620 | 256MB | ルーター (OpenWrt) |
| 2018 | Phicomm N1 (斐讯N1) | ARM64 (A53) | S905D | 2GB | TV ボックス / ホームサーバー |
| 2019 | Xiaomi AI Speaker (小爱音箱) | ARM64 (A53) | — | 256MB | スマートスピーカー |
| 2024 | [NanoKVM](https://www.tuptup.top) | RISC-V | SG2002 | 256MB | IP-KVM |
| 2025 | HaaS506-LD1 | RISC-V | D213 | 128MB | 産業用 RTU |
| 2025 | [NanoKVM-Pro](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1GB | プロ IP-KVM |
| 2026 | [MaixCAM2](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1/4GB | 4K AI カメラ |

---

## 3. 検証済み開発ボード（発売日順）

| 年 | ボード | アーキテクチャ | SoC | RAM | 購入リンク |
|----|--------|----------------|-----|-----|------------|
| 2012 | [Raspberry Pi 1 Model B](https://www.tuptup.top) | ARMv6 | BCM2835 | 512MB | — |
| 2015 | [Raspberry Pi 2 Model B](https://www.tuptup.top) | ARMv7 (A7) | BCM2836 | 1GB | — |
| 2015 | [Raspberry Pi Zero](https://www.tuptup.top) | ARMv6 | BCM2835 | 512MB | — |
| 2016 | [Raspberry Pi 3 Model B](https://www.tuptup.top) | ARM64 (A53) | BCM2837 | 1GB | — |
| 2017 | [LicheePi Zero](https://www.tuptup.top) | ARMv7 (A7) | Allwinner V3s | 64MB | [colearn](https://www.tuptup.top) |
| 2019 | [Raspberry Pi 4 Model B](https://www.tuptup.top) | ARM64 (A72) | BCM2711 | 1~8GB | [RPi](https://www.tuptup.top) |
| 2023 | [Raspberry Pi 5](https://www.tuptup.top) | ARM64 (A76) | BCM2712 | 2~8GB | [RPi](https://www.tuptup.top) |
| 2024 | [LicheeRV-Nano](https://www.tuptup.top) | RISC-V | SG2002 | 256MB | [AliExpress](https://www.tuptup.top) |
| 2024 | [MaixCAM-Pro](https://www.tuptup.top) | RISC-V | SG2002 | 256MB | [colearn](https://www.tuptup.top) |
| 2024 | [Milk-V Duo 64M](https://www.tuptup.top) | RISC-V | CV1800B | 64MB | [Milk-V](https://www.tuptup.top) |
| 2024 | [CanMV-K230](https://www.tuptup.top) | RISC-V | K230 | 512MB | [Canaan](https://www.tuptup.top) |

---

## 4. その他の対応環境

### Android スマートフォン（Termux 経由）

1GB 以上の RAM を搭載した ARM64 Android スマートフォン（2015年以降）。[Termux](https://www.tuptup.top) をインストールし、`proot` を使用して colearn を実行します。

> セットアップ手順は [README：古い Android スマートフォンで実行](../project/README.ja.md#-run-on-old-android-phones) を参照してください。

### デスクトップ / サーバー / クラウド

| プラットフォーム | 備考 |
|------------------|------|
| x86_64 Linux | ネイティブバイナリ、依存関係なし |
| x86_64 Windows | ネイティブバイナリ |
| macOS (Intel / Apple Silicon) | ネイティブバイナリ |
| Docker (any platform) | `docker compose` ワンライナー、[Docker ガイド](docker.md) を参照 |
| OpenWrt routers | MIPS/ARM ビルド、32MB 以上の空きメモリが必要 |
| FreeBSD / NetBSD | x86_64 および arm64 ビルドが利用可能 |

---

## 5. 最小要件

| リソース | 最小 | 推奨 |
|----------|------|------|
| RAM | 10MB 空き | 32MB 以上空き |
| ストレージ | 20MB（バイナリ） | 50MB 以上（ワークスペース含む） |
| CPU | 任意（シングルコア 0.6GHz 以上） | — |
| OS | Linux (kernel 3.x+) | Linux 5.x+ |
| ネットワーク | 必須（LLM API 呼び出し用） | イーサネットまたは WiFi |

---

## 6. テストと貢献の方法

```bash
# 1. お使いのアーキテクチャ向けをダウンロード
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz

# 2. 初期化
./colearn onboard

# 3. テスト
./colearn agent -m "Hello, what board am I running on?"
```

利用可能なビルド：`linux-amd64`, `linux-arm64`, `linux-arm`, `linux-riscv64`, `linux-loong64`, `linux-mipsle`

### ハードウェアを追加する

1. このリポジトリをフォーク
2. 該当するテーブルにチップ/製品/ボードを追加
3. 名前、アーキテクチャ、SoC、RAM、年、リンク（あれば）を含める
4. PR を送信

ハードウェアベンダーの方へ：公式サポートの追加や共同プロモーションをご希望ですか？Issue を作成するか、[Discord](https://www.tuptup.top) でお問い合わせください。
