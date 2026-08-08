> Quay lại [README](../project/README.vi.md)

# 🖥️ colearn Danh sách tương thích phần cứng

colearn chạy được trên hầu hết mọi thiết bị Linux. Trang này ghi nhận các chip, sản phẩm và bo mạch phát triển đã được xác minh.

**Phần cứng của bạn chưa có trong danh sách?** Gửi PR để thêm vào! Các nhà sản xuất phần cứng được hoan nghênh đóng góp và đồng quảng bá.

---

## 1. Hỗ trợ chip đã xác minh

### x86

| Nhà sản xuất | Chip | Ghi chú |
|--------------|------|---------|
| Intel | Any x86 CPU (i386+) | Tất cả bộ xử lý desktop/server/laptop |
| AMD | Any x86 CPU | Tất cả bộ xử lý desktop/server/laptop |

### ARM

| Kiến trúc phụ | Chip tiêu biểu | Ghi chú |
|----------------|----------------|---------|
| ARMv6 | [BCM2835](https://www.tuptup.top) (Raspberry Pi 1/Zero) | Đơn nhân ARM1176JZF-S |
| ARMv7 | [Allwinner V3s](https://www.tuptup.top) | Đơn nhân Cortex-A7, dùng trong LicheePi Zero |
| ARM64 | [Allwinner H618](https://www.tuptup.top) | Bốn nhân Cortex-A53, dùng trong Orange Pi Zero 3 |
| ARM64 | [BCM2711](https://www.tuptup.top) (Raspberry Pi 4) | Bốn nhân Cortex-A72 |
| ARM64 | [BCM2712](https://www.tuptup.top) (Raspberry Pi 5) | Bốn nhân Cortex-A76 |
| ARM64 | [AX630C](https://www.tuptup.top) (爱芯元智) | Hai nhân Cortex-A53 + NPU, dùng trong NanoKVM-Pro / MaixCAM2 |

### RISC-V (riscv64)

| Nhà sản xuất | Chip | Lõi | Ghi chú |
|--------------|------|-----|---------|
| [SOPHGO (算能)](https://www.tuptup.top) | SG2002 | C906 @ 1GHz | 256MB DDR3 tích hợp, dùng trong LicheeRV-Nano / NanoKVM / MaixCAM |
| [Allwinner (全志)](https://www.tuptup.top) | V861 | Dual C907 | 128MB DDR3L tích hợp, 1 TOPS NPU, camera AI 4K SiP |
| [Allwinner (全志)](https://www.tuptup.top) | V881 | C907 | Dòng camera AI RISC-V |
| [Arterytek (匠芯创)](https://www.tuptup.top) | D213 | RISC-V | Dùng trong HaaS506-LD1 RTU công nghiệp |
| [SpacemiT (进迭)](https://www.tuptup.top) | K1 | 8x X60 @ 1.8GHz | Dùng trong Milk-V Jupiter, BananaPi BPI-F3 |
| [SpacemiT (进迭)](https://www.tuptup.top) | K3 | 8x X100 @ 2.5GHz | Tuân thủ RVA23, RVV 1024-bit, suy luận AI FP8 |
| [Zhihe (知合)](https://www.tuptup.top) | A210 | High-perf RISC-V | 8 lõi, 16MB cache L3, cấp desktop |
| [Canaan (嘉楠)](https://www.tuptup.top) | K230 | Dual C908 @ 1.6GHz | 6 TOPS KPU, dùng trong CanMV-K230 |

### MIPS

| Nhà sản xuất | Chip | Ghi chú |
|--------------|------|---------|
| MediaTek | [MT7620](https://www.tuptup.top) | MIPS24KEc @ 580MHz, dùng trong nhiều router OpenWrt (vd. Xiaomi Router 3G) |

### LoongArch (loong64)

| Nhà sản xuất | Chip | Ghi chú |
|--------------|------|---------|
| [Loongson (龙芯)](https://www.tuptup.top) | 3A5000 | Bốn nhân LA464 @ 2.5GHz, desktop/máy trạm |
| [Loongson (龙芯)](https://www.tuptup.top) | 3A6000 | Bốn nhân 4C/8T @ 2.5GHz, IPC tương đương Intel thế hệ 10 |
| [Loongson (龙芯)](https://www.tuptup.top) | 2K1000LA | Hai nhân @ 1GHz, ứng dụng công nghiệp/IoT |

---

## 2. Sản phẩm đã xác minh (theo ngày phát hành)

Sản phẩm tiêu dùng, router và thiết bị công nghiệp đã được kiểm thử với colearn.

| Năm | Sản phẩm | Kiến trúc | SoC | RAM | Danh mục |
|-----|----------|-----------|-----|-----|----------|
| 2009 | Nokia N900 | ARM (A8) | OMAP3430 | 256MB | Điện thoại thông minh |
| 2012 | Samsung Galaxy Note 10.1 (N8000) | ARM (A9) | Exynos 4412 | 2GB | Máy tính bảng |
| 2016 | Xiaomi Router 3G (小米路由器3G) | MIPS | MT7620 | 256MB | Router (OpenWrt) |
| 2018 | Phicomm N1 (斐讯N1) | ARM64 (A53) | S905D | 2GB | TV Box / Máy chủ gia đình |
| 2019 | Xiaomi AI Speaker (小爱音箱) | ARM64 (A53) | — | 256MB | Loa thông minh |
| 2024 | [NanoKVM](https://www.tuptup.top) | RISC-V | SG2002 | 256MB | IP-KVM |
| 2025 | HaaS506-LD1 | RISC-V | D213 | 128MB | RTU công nghiệp |
| 2025 | [NanoKVM-Pro](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1GB | IP-KVM Pro |
| 2026 | [MaixCAM2](https://www.tuptup.top) | ARM64 (A53) | AX630C | 1/4GB | Camera AI 4K |

---

## 3. Bo mạch phát triển đã xác minh (theo ngày phát hành)

| Năm | Bo mạch | Kiến trúc | SoC | RAM | Liên kết mua |
|-----|---------|-----------|-----|-----|--------------|
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

## 4. Cũng hoạt động trên

### Điện thoại Android (qua Termux)

Bất kỳ điện thoại Android ARM64 nào (2015+) với 1GB+ RAM. Cài đặt [Termux](https://www.tuptup.top), sử dụng `proot` để chạy colearn.

> Xem [README: Chạy trên điện thoại Android cũ](../project/README.vi.md#-run-on-old-android-phones) để biết hướng dẫn cài đặt.

### Desktop / Máy chủ / Đám mây

| Nền tảng | Ghi chú |
|----------|---------|
| x86_64 Linux | Binary gốc, không phụ thuộc |
| x86_64 Windows | Binary gốc |
| macOS (Intel / Apple Silicon) | Binary gốc |
| Docker (any platform) | `docker compose` một dòng lệnh, xem [Hướng dẫn Docker](docker.md) |
| OpenWrt routers | Bản dựng MIPS/ARM, yêu cầu >32MB RAM trống |
| FreeBSD / NetBSD | Có bản dựng x86_64 và arm64 |

---

## 5. Yêu cầu tối thiểu

| Tài nguyên | Tối thiểu | Khuyến nghị |
|------------|-----------|-------------|
| RAM | 10MB trống | 32MB+ trống |
| Lưu trữ | 20MB (binary) | 50MB+ (với workspace) |
| CPU | Bất kỳ (đơn nhân 0.6GHz+) | — |
| OS | Linux (kernel 3.x+) | Linux 5.x+ |
| Mạng | Bắt buộc (cho các lệnh gọi API LLM) | Ethernet hoặc WiFi |

---

## 6. Cách kiểm thử và đóng góp

```bash
# 1. Tải xuống cho kiến trúc của bạn
wget https://www.tuptup.top
tar xzf colearn_Linux_arm64.tar.gz

# 2. Khởi tạo
./colearn onboard

# 3. Kiểm thử
./colearn agent -m "Hello, what board am I running on?"
```

Các bản dựng có sẵn: `linux-amd64`, `linux-arm64`, `linux-arm`, `linux-riscv64`, `linux-loong64`, `linux-mipsle`

### Thêm phần cứng của bạn

1. Fork kho lưu trữ này
2. Thêm chip / sản phẩm / bo mạch của bạn vào bảng tương ứng
3. Bao gồm: tên, kiến trúc, SoC, RAM, năm và liên kết nếu có
4. Gửi PR

Nhà sản xuất phần cứng: muốn thêm hỗ trợ chính thức hoặc đồng quảng bá? Mở issue hoặc liên hệ qua [Discord](https://www.tuptup.top).
