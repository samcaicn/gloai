# 店长自查 APK（Android + HarmonyOS 双端）

用店里**已有**摄像头做「打开即自查」的 AI 合规巡检工具。单代码库 **Flutter（Dart）**，
通过 OpenHarmony Flutter 引擎编译到 **HarmonyOS NEXT**。

> ⚠️ 隐私红线：只做人体检测与行为识别（口罩/厨帽/抽烟/鼠患/离岗），**绝不采集人脸、不建人脸库**。

---

## 架构

```
lib/                         纯 Dart，Android/鸿蒙共用
  config/env.dart            后端地址、自查项、隐私开关
  data/                      models / api_client / local_store / store_repository
  cam/                       摄像头能力层（纯 Dart）
    digest_auth.dart         HTTP Digest 拦截器（ONVIF 抓图鉴权）
    onvif_client.dart        GetCapabilities/GetProfiles/GetSnapshotUri
    device_discovery.dart    WS-Discovery + 网段扫描兜底
    snapshot_fetcher.dart    Digest 抓 JPEG + ≤1080P 压缩
    vendor_preset.dart       海康 ISAPI / 大华 cgi 预设 + 弱口令表
    camera_source.dart       统一 CameraSource 接口（本地直连/云端代理）
    rtsp_fallback.dart      P1：无 snapshot 设备 RTSP 取帧兜底
  service/                  保活/常亮/白名单（MethodChannel 封装）
  ui/                        登录/首页/摄像头管理/自查/报告
android/                    Flutter 安卓嵌入（Kotlin 原生通道）
ohos/                       OpenHarmony Flutter 嵌入（ArkTS 原生通道）
```

**关键决策**：厂商云 `appId/appSecret` 绝不下发手机，手机只拿服务端签发的短期 ticket；
抓图优先 ONVIF `GetSnapshotUri`（一次 HTTP 拿 JPEG），RTSP 仅兜底；保活只做合规路径。

---

## 运行 / 构建

### 前置
- Flutter SDK ≥ 3.4（Android）
- 安卓：Android SDK（minSdk 21）
- 鸿蒙：DevEco Studio + OpenHarmony Flutter SDK（`flutter_flutter` 社区版或华为适配版）

### Android
```bash
cd android
flutter pub get
flutter run                      # 真机/模拟器调试
flutter build apk --release      # 出包
```
> 首次可先 `flutter create .` 让 Flutter 补齐 `android/` 嵌入的资源文件（图标等），
> 再把自己的 Kotlin 通道文件放回 `android/app/src/main/kotlin/com/facemark/camhub/`。

### HarmonyOS（OpenHarmony Flutter）
```bash
# 在已配置 OpenHarmony Flutter 的环境下
flutter create --platforms=ohos .
flutter pub get
flutter build ohos --release     # 或用 DevEco Studio 打开 ohos/ 目录构建
```
> `ohos/entry/src/main/ets/` 下已写好 EntryAbility、页面、三个原生通道（常亮 / 白名单 / 前台保活）。
> 需把 `oh-package.json5` 的 `@ohos/flutter_ohos` 指向你本地的 flutter.har。

---

## 与服务端契约

后端见 `C:\code1\facemarkcloud\cloudflare\src\lib\cam.js`（已接入 Worker 路由）：

| action | 说明 |
|---|---|
| `store_list` | 店长可管理门店 |
| `cam_devices` | 已绑定云端摄像头列表（ezviz/imou/uniview） |
| `cam_capture` | 经 camhub 厂商云代理抓一帧，返回短期 URL |
| `cam_inspect` | 上传 JPEG 字节，云端推理，返回自查结果（multipart） |
| `cam_reports` | 历史报告 |

本地直连（ONVIF）不经后端，手机同 WiFi 直接抓图后上传 `cam_inspect` 推理。

> 推理层当前为**演示占位**（五项正确返回未命中）。接真实模型时改 `cam.inspect` 的 `realInfer(image)`
> 入参，或换云端视觉 API，前端无需改动。

---

## 待办（M5）
- [ ] RTSP 取帧兜底（Tapo 等无 snapshot 设备）：原生拉流 → 取帧 → 回传 JPEG
- [ ] 多门店批量自查
- [ ] 真实视觉模型接入（替换 demoInfer）
- [ ] 密码 Keystore/鸿蒙 KeyStore 加密（当前为 base64 占位，禁止上生产）
