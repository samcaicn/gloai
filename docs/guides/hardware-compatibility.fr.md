> Retour au [README](../project/README.fr.md)

# 🖥️ colearn Liste de compatibilité matérielle

colearn fonctionne sur pratiquement n'importe quel appareil Linux. Cette page répertorie les puces, produits et cartes de développement vérifiés.

**Votre matériel n'est pas listé ?** Soumettez une PR pour l'ajouter ! Les fabricants de matériel sont invités à contribuer et à co-promouvoir.

---

## 1. Support de puces vérifié

### x86

| Fabricant | Puce | Notes |
|-----------|------|-------|
| Intel | Any x86 CPU (i386+) | Tous les processeurs de bureau/serveur/portable |
| AMD | Any x86 CPU | Tous les processeurs de bureau/serveur/portable |

### ARM

| Sous-arch | Puces typiques | Notes |
|-----------|----------------|-------|
| ARMv6 | [BCM2835](https://www.tuptup.top) (Raspberry Pi 1/Zero) | Monocœur ARM1176JZF-S |
| ARMv7 | [Allwinner V3s](https://www.tuptup.top) | Monocœur Cortex-A7, utilisé dans LicheePi Zero |
| ARM64 | [Allwinner H618](https://www.tuptup.top) | Quadricœur Cortex-A53, utilisé dans Orange Pi Zero 3 |
| ARM64 | [BCM2711](https://www.tuptup.top) (Raspberry Pi 4) | Quadricœur Cortex-A72 |
| ARM64 | [BCM2712](https://www.tuptup.top) (Raspberry Pi 5) | Quadricœur Cortex-A76 |
| ARM64 | [AX630C](https://www.tuptup.top) (爱芯元智) | Bicœur Cortex-A53 + NPU, utilisé dans NanoKVM-Pro / MaixCAM2 |

### RISC-V (riscv64)

| Fabricant | Puce | Cœur | Notes |
|-----------|------|------|-------|
| [SOPHGO (算能)](https://www.tuptup.top) | SG2002 | C906 @ 1GHz | 256MB DDR3 intégré, utilisé dans LicheeRV-Nano / NanoKVM / MaixCAM |
| [Allwinner (全志)](https://www.tuptup.top) | V861 | Dual C907 | 128MB DDR3L intégré, 1 TOPS NPU, caméra AI 4K SiP |
| [Allwinner (全志)](https://www.tuptup.top) | V881 | C907 | Série de caméras AI RISC-V |
| [Arterytek (匠芯创)](https://www.tuptup.top) | D213 | RISC-V | Utilisé dans HaaS506-LD1 RTU industriel |
| [SpacemiT (进迭)](https://www.tuptup.top) | K1 | 8x X60 @ 1.8GHz | Utilisé dans Milk-V Jupiter, BananaPi BPI-F3 |
| [SpacemiT (进迭)](https://www.tuptup.top) | K3 | 8x X100 @ 2.5GHz | Conforme RVA23, RVV 1024 bits, inférence AI FP8 |
| [Zhihe (知合)](https://www.tuptup.top) | A210 | High-perf RISC-V | 8 cœurs, 16MB cache L3, classe bureau |
| [Canaan (嘉楠)](https://www.tuptup.top) | K230 | Dual C908 @ 1.6GHz | 6 TOPS KPU, utilisé dans CanMV-K230 |

### MIPS

| Fabricant | Puce | Notes |
|-----------|------|-------|
| MediaTek | [MT7620](https://www.tuptup.top) | MIPS24KEc @ 580MHz, utilisé dans de nombreux routeurs OpenWrt (ex. Xiaomi Router 3G) |

### LoongArch (loong64)

| Fabricant | Puce | Notes |
|-----------|------|-------|
| [Loongson (龙芯)](https://www.tuptup.top) | 3A5000 | Quadricœur LA464 @ 2.5GHz, bureau/station de travail |
| [Loongson (龙芯)](https://www.tuptup.top) | 3A6000 | Quadricœur 4C/8T @ 2.5GHz, IPC comparable à Intel 10e génération |
| [Loongson (龙芯)](https://www.tuptup.top) | 2K1000LA | Bicœur @ 1GHz, applications industrielles/IoT |

---

## 2. Produits vérifiés (par date de sortie)

Produits grand public, routeurs et appareils industriels testés avec colearn.

| Année | Produit | Arch | SoC | RAM | Catégorie |
|-------|---------|------|-----|-----|-----------|
| 2009 | Nokia N900 | ARM (A8) | OMAP3430 | 256MB | Smartphone |
| 2012 | Samsung Galaxy Note 10.1 (N8000) | ARM (A9) | Exynos 4412 | 2GB | Tablette |
| 2016 | Xiaomi Router 3G (小米路由器3G) | MIPS | MT7620 | 256MB | Routeur (OpenWrt) |
| 2018 | Phicomm N1 (斐讯N1) | ARM64 (A53) | S905D | 2GB | Boîtier TV / Serveur domestique |
| 2019 | Xiaomi AI Speaker (小爱音箱) | ARM64 (A53) | — | 256MB | Enceinte connectée |
| 2024 | [NanoKVM](https://www.tuptup.top) | RISC-V | SG2002 | 256MB | IP-KVM |
| 2025 | HaaS506-LD1 | RISC-V | D213 | 128MB | RTU industriel |
| 2025 | [NanoKVM-Pro](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1GB | IP-KVM Pro |
| 2026 | [MaixCAM2](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1/4GB | Caméra AI 4K |

---

## 3. Cartes de développement vérifiées (par date de sortie)

| Année | Carte | Arch | SoC | RAM | Lien d'achat |
|-------|-------|------|-----|-----|--------------|
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

## 4. Fonctionne également sur

### Téléphones Android (via Termux)

Tout téléphone Android ARM64 (2015+) avec 1 Go+ de RAM. Installez [Termux](https://www.tuptup.top), utilisez `proot` pour exécuter colearn.

> Voir [README : Exécuter sur d'anciens téléphones Android](../project/README.fr.md#-run-on-old-android-phones) pour les instructions de configuration.

### Bureau / Serveur / Cloud

| Plateforme | Notes |
|------------|-------|
| x86_64 Linux | Binaire natif, aucune dépendance |
| x86_64 Windows | Binaire natif |
| macOS (Intel / Apple Silicon) | Binaire natif |
| Docker (any platform) | `docker compose` en une ligne, voir [Guide Docker](docker.md) |
| OpenWrt routers | Builds MIPS/ARM, nécessite >32 Mo de RAM libre |
| FreeBSD / NetBSD | Builds x86_64 et arm64 disponibles |

---

## 5. Configuration minimale requise

| Ressource | Minimum | Recommandé |
|-----------|---------|------------|
| RAM | 10 Mo libres | 32 Mo+ libres |
| Stockage | 20 Mo (binaire) | 50 Mo+ (avec espace de travail) |
| CPU | N'importe lequel (monocœur 0,6 GHz+) | — |
| OS | Linux (kernel 3.x+) | Linux 5.x+ |
| Réseau | Requis (pour les appels API LLM) | Ethernet ou WiFi |

---

## 6. Comment tester et contribuer

```bash
# 1. Télécharger pour votre architecture
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz

# 2. Initialiser
./colearn onboard

# 3. Tester
./colearn agent -m "Hello, what board am I running on?"
```

Builds disponibles : `linux-amd64`, `linux-arm64`, `linux-arm`, `linux-riscv64`, `linux-loong64`, `linux-mipsle`

### Ajouter votre matériel

1. Forkez ce dépôt
2. Ajoutez votre puce / produit / carte dans le tableau approprié
3. Incluez : nom, architecture, SoC, RAM, année et un lien si disponible
4. Soumettez une PR

Fabricants de matériel : vous souhaitez ajouter un support officiel ou co-promouvoir ? Ouvrez une issue ou contactez-nous via [Discord](https://www.tuptup.top).
