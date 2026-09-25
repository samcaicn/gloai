# Android 端开发规划 — 店长 APK

> 本文件是**开发规划**，不是已实现说明。
> 配套：`C:\code1\facemarkcloud\camhub\`（摄像头云平台统一接入层，跑在服务端）

---

## ⚠️ 决策变更（2026-09-19）：兼容鸿蒙 → 改用 Flutter 单代码库

原设计用 **Kotlin + Compose**，但 Compose **无法运行在 HarmonyOS** 上。用户确认要兼容鸿蒙，
故底座改为 **Flutter（Dart）+ OpenHarmony Flutter 引擎**：

| 层 | 实现 | 说明 |
|---|---|---|
| UI / 业务逻辑 | **Dart（100% 共享）** | 页面、状态、网络、ONVIF、店长功能全部一份代码 |
| 摄像头抓图 | **纯 Dart**（HTTP Digest + XML） | ONVIF `GetSnapshotUri` 不依赖原生，Android/鸿蒙共用 |
| 前台/后台保活、常亮、厂商白名单 | **平台通道（MethodChannel）** | Android→Kotlin，鸿蒙→ArkTS，Dart 侧统一接口 |
| 工程结构 | Flutter 标准布局 | 根 `android/` 即 Flutter 工程；内 `android/`（安卓嵌入）、`ohos/`（鸿蒙嵌入） |

**为什么是 Flutter 而不是 uni-app X**：本项目深度依赖 ONVIF SOAP / Digest 认证 / 前台服务，
Dart 能纯语言实现 ONVIF，原生面最小；uni-app X 做这些要写大量 UTS 原生插件，反而更碎。
（若你更想复用现有 Vue 生态，可改 uni-app X，本规划其余逻辑不变——告诉我即可切换。）

**HarmonyOS 构建前提**：需安装 DevEco Studio + OpenHarmony Flutter SDK（`flutter_flutter` 社区版或华为适配版），
并把 `ohos/` 目录作为鸿蒙 entry 接入。`android/` 目录下的 Kotlin 代码只服务安卓，鸿蒙侧用 `ohos/entry/src/main/ets/` 的 ArkTS 对应实现。

---

## 一、定位与边界

**做什么**：一个装在店长手机上的 APK，用店里**已有的**摄像头做「打开即自查」——
选摄像头 → 抓一帧 → 云端识别 → 出违规项 + 截图证据 + 整改建议。

**不做什么（明确砍掉）**：
- ❌ 不做 24 小时连续监测（安卓做不到，且需要边缘盒子，那是另一条产品线）
- ❌ 不做新硬件售卖/配网（本项目核心是「不新装」）
- ❌ 不做实时视频云存储、不做回放取证
- ❌ 不做人脸库 / 顾客身份识别（个保法红线，见第八节）

---

## 二、三条硬架构决策

### 决策 1：厂商云 appId/appSecret **绝不下发到手机**

`camhub` 的萤石/乐橙/宇视凭据全部留在服务端（Cloudflare Workers Secret）。
手机只拿服务端签发的**短期 ticket**（JWT，2 小时）。

```
手机 ──► Workers /api?action=cam.devices   （服务端跑 camhub，返回设备列表）
手机 ──► Workers /api?action=cam.capture   （服务端抓图，返回短期图片 URL）
手机 ──► Workers /api?action=cam.inspect   （上传图片字节，云端推理）
```

理由：密钥一旦进 APK 就是公开密钥（可被反编译），而且无法吊销。

### 决策 2：抓图优先走 **ONVIF GetSnapshotUri**，RTSP 只做预览兜底

我们的业务只需要「**一帧**」，不需要视频流。

| 方式 | 复杂度 | 耗电 | 覆盖 |
|---|---|---|---|
| **ONVIF `GetSnapshotUri`** | 低（一次 HTTP Digest 拿 JPEG） | 极低 | 海康/大华/宇视 IPC 与 NVR 基本都支持 |
| 厂商私有 snapshot（ISAPI / cgi-bin） | 低 | 极低 | 海康 `/ISAPI/Streaming/channels/1/picture`、大华 `/cgi-bin/snapshot.cgi?channel=1` |
| RTSP 拉流解码取帧 | **高**（要解码器） | 高 | 全支持，但只有 Tapo 等少数设备必须走这条 |

**已知硬限制**：TP-Link Tapo 官方明确不支持 snapshot 下载 → 只能 RTSP 取帧，列为 P1 降级项（见第八节）。

### 决策 3：保活只做合规路径，不做黑魔法

双进程守护、无声播放、1 像素 Activity —— **一律不用**。这些在高版本安卓与国产 ROM 上已被封死，
且是应用商店下架与合规风险源。做不到 24h 就诚实做成「打开即用 + 定时提醒」。

---

## 三、技术选型（Flutter 单代码库）

| 维度 | 选型 | 理由 |
|---|---|---|
| 语言 | **Dart** | 单代码库，Android + 鸿蒙共用 |
| UI | **Flutter Widgets + Material3** | 页面少（<15），热重载迭代快 |
| 状态 | `provider` / `ChangeNotifier` | 轻量够用 |
| 架构 | Repository + 单向数据流 | 上层只认 `CameraSource` 抽象 |
| 网络 | `http` + 自写 Digest auth interceptor | ONVIF 跑 Digest，Dart 纯实现 |
| 本地存储 | `shared_preferences`（配置）+ `sqflite`（设备/报告缓存） | |
| 后台/保活 | **平台通道**：安卓 ForegroundService(Kotlin) / 鸿蒙 ForegroundAbility(ArkTS) | 见第五节 |
| 图片上传 | `http` multipart | |
| ONVIF | **纯 Dart SOAP client**（约 300 行，发 XML + Digest） | 不需要任何原生代码，两端共用 |
| 预览（P1） | 鸿蒙/安卓各自原生播放器（平台通道出纹理） | 仅兜底 |
| minSdk | **Android 21（5.0）** | 兼容绝大多数在用小店安卓机 |

**鸿蒙侧**：OpenHarmony Flutter 引擎要求 API 9+（HarmonyOS NEXT / 4.0+）。

---

## 四、目录结构（Flutter 工程，根即 `android/`）

```
C:\code1\facemarkcloud\android\                      ← Flutter 工程根
├─ pubspec.yaml
├─ README.md                                        双端运行/构建说明
├─ lib\
│  ├─ main.dart
│  ├─ app\                                          MaterialApp / 主题 / 路由
│  ├─ config\env.dart                               API base / feature flags
│  ├─ data\
│  │  ├─ models.dart                               CameraDevice / InspectResult / Report / Store
│  │  ├─ api_client.dart                           Workers camhub 对接（ticket 鉴权）
│  │  ├─ store_repository.dart
│  │  └─ local_store.dart                          shared_preferences + sqflite
│  ├─ cam\                                          ★ 摄像头能力层（纯 Dart，双端共用）
│  │  ├─ onvif_client.dart                         SOAP：GetCapabilities/GetProfiles/GetSnapshotUri
│  │  ├─ digest_auth.dart                          Dart HTTP Digest 拦截器
│  │  ├─ device_discovery.dart                     WS-Discovery + 网段扫描兜底
│  │  ├─ snapshot_fetcher.dart                     Digest 抓 JPEG
│  │  ├─ vendor_preset.dart                        海康 ISAPI / 大华 cgi 私有路径
│  │  ├─ camera_source.dart                        统一 CameraSource 接口（本地/云端）
│  │  └─ rtsp_fallback.dart                        P1
│  ├─ service\
│  │  ├─ background_service.dart                   MethodChannel → 原生前台服务
│  │  ├─ keep_screen_on.dart                       MethodChannel 常亮
│  │  └─ battery_whitelist.dart                    厂商 ROM 白名单跳转（双端）
│  ├─ state\session.dart
│  ├─ platform\channels.dart                        MethodChannel 常量
│  └─ ui\                                          login / home / camera / inspect / report
├─ android\                                         Flutter 安卓嵌入（Kotlin 原生）
│  ├─ app\src\main\AndroidManifest.xml
│  └─ app\src\main\kotlin\com\facemark\camhub\
│     ├─ MainActivity.kt
│     ├─ ForegroundInspectService.kt
│     ├─ KeepScreenOnPlugin.kt
│     └─ BatteryWhitelistPlugin.kt
└─ ohos\                                            OpenHarmony Flutter 嵌入（ArkTS 原生）
   ├─ entry\src\main\module.json5
   └─ entry\src\main\ets\
      ├─ entryability\EntryAbility.ts
      ├─ pages\Index.ets
      ├─ service\ForegroundInspectService.ts
      └─ plugin\KeepScreenOnPlugin.ts
```

---

## 五、难点攻坚

### 5.1 后台服务 + 前台不黑屏

「不黑屏」拆成两件事，分别处理：

| 场景 | 症状 | 方案 |
|---|---|---|
| **前台运行时息屏** | 店长举着手机看结果，屏幕灭了 | `window.addFlags(FLAG_KEEP_SCREEN_ON)`，**仅在自查执行页与预览页开启**，离开即清 |
| **后台被杀导致任务断** | 上传/推理请求发一半进程没了 | ForegroundService + 常驻通知 + `PARTIAL_WAKE_LOCK` |

保活分三级，**逐级引导，不承诺 24h**：

- **L1 合规基线**
  - `ForegroundService`（`type=shortService` 或用 `dataSync`），必须显示常驻通知
  - `PARTIAL_WAKE_LOCK`（仅自查执行期间持有，用完立刻释放）
  - 申请忽略电池优化：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **L2 厂商白名单引导**（国产 ROM 决定生死）
  - 检测 ROM → 跳转对应「自启动管理 / 省电策略」页
  - 覆盖：小米(MIUI)、华为(EMUI/HarmonyOS)、OPPO(ColorOS)、vivo(OriginOS)、荣耀(MagicOS)、三星、一加
  - 提供「一键跳转 + 图文教程」页面，做成引导流程而非静默请求
- **L3 产品兜底**
  - 承认后台不可靠 → 用 WorkManager 做**补传**（离线缓存的抓图，联网后补传）
  - 用本地通知做「每日自查提醒」，把负担转回用户主动打开

### 5.2 本地摄像头配置（核心）

目标：店长在**店里同一 WiFi** 下，3 步内配好摄像头。

**发现（两条路并行）**
1. `WS-Discovery` UDP 多播 `239.255.255.250:3702` 发 SOAP Probe
   - ⚠️ 安卓必须拿 `WifiManager.MulticastLock`，否则收不到多播
2. 多播失败 → **网段扫描兜底**：解析本机 IP/掩码，并发探测 `1~254` 的 ONVIF 端口（`2020`/`8080`/`80`）与 RTSP `554`

**验证与抓图**
1. ONVIF `GetCapabilities` → `GetProfiles` → `GetSnapshotUri`
2. HTTP **Digest** 认证拉 JPEG（OkHttp 自定义 `Authenticator`）
3. 拿不到 → 试厂商预设路径（`VendorPreset`）
4. 仍失败 → 标记为「需 RTSP 兜底」

**配置落库**：Room 存 `{ id, 门店ID, 名称(前厅/后厨/出餐口), ip, port, 用户名, 密码(加密), onvifUrl, snapshotUrl, 来源(发现/手动/云端), 最后在线 }`

> ⚠️ **最大现实摩擦**：店长不知道摄像头密码（安装商设完就走）。
> 应对：① 提供常见弱口令自动尝试；② 引导用厂商云授权路径（服务端 camhub）绕开密码；
> ③ amera 密码用 Android Keystore 加密存储，不明文。

### 5.3 与服务端 camhub 的双通道

| 通道 | 走什么 | 何时用 |
|---|---|---|
| **本地直连** | 手机 → 同 WiFi → IPC（ONVIF） | 店长在店里自查（主场景） |
| **云端代理** | 手机 → Workers → camhub → 厂商云 | 店长不在店里，远程看一眼 |

两条通道对上层**暴露同一个 `CameraSource` 接口**，上层不关心图从哪来。

---

## 六、基础店长功能 MVP 清单

| # | 功能 | 优先级 | 说明 |
|---|---|---|---|
| 1 | 登录 / 门店切换 | P0 | 复用 facemark 现有账号体系 |
| 2 | 摄像头管理：发现 / 手动添加 / 测试抓图 / 命名 | P0 | 配好才能用，第一屏就该是它 |
| 3 | **一键自查**：选摄像头 → 抓帧 → 上传 → 出结果卡片 | P0 | 产品核心 |
| 4 | 结果卡片：违规项 + 截图证据 + 整改建议 | P0 | 口罩/厨帽/抽烟/鼠患/离岗 五件套先上 |
| 5 | 自查报告：单次报告 + 历史列表 | P0 | 明厨亮灶自查刚需，最好收钱的点 |
| 6 | 本地通知：发现问题 / 每日自查提醒 | P1 | |
| 7 | 离线缓存 + 联网补传 | P1 | 后厨 WiFi 差是常态 |
| 8 | 预览（RTSP 兜底） | P1 | 仅 Tapo 等无 snapshot 设备需要 |
| 9 | 多门店批量自查 | P2 | 小型连锁场景 |

---

## 七、分期里程碑

| 期 | 交付物 | 验收标准 |
|---|---|---|
| **M1 骨架** | Gradle 工程 + Compose 壳 + 登录 + 首页 | 能装机能跑 |
| **M2 摄像头能力** | `OnvifClient` + 发现 + 抓图 + 配置落库 | **真机实测**：能发现店里摄像头并抓出一帧 JPEG |
| **M3 自查闭环** | 抓图 → 上传 Workers → 推理 → 结果卡片 + 报告 | 端到端跑通一条完整链路 |
| **M4 保活与体验** | 前台服务 + WakeLock + 白名单引导 + 离线补传 | 主流 ROM 上自查过程不被杀 |
| **M5 兜底** | RTSP 取帧（Tapo 等）+ 多门店 | 覆盖无 snapshot 设备 |

**M2 是生死线**——真机上发现不了摄像头或抓不出图，后面全是空谈。所以 M2 必须拿真机 + 真摄像头验，不能只跑模拟器。

---

## 八、风险与红线

| 风险 | 等级 | 应对 |
|---|---|---|
| **国产 ROM 保活不可靠** | 🔴 高 | 不做过度承诺；L3 转成「打开即用 + 提醒」，产品话术对齐 |
| **店长不知道摄像头密码** | 🔴 高 | 弱口令尝试 + 云端授权绕开 + 引导安装商协助 |
| **RTSP 取帧不稳** | 🟠 中 | Media3 RTSP 扩展已知问题多；列为 P1，抓不到就降级为「提示需换设备/加盒子」 |
| **隐私合规（个保法）** | 🔴 高（红线） | **只做人体检测与行为识别，不做人脸识别、不建人脸库、不做顾客身份画像**；抓图仅保留证据所需最小时长；隐私政策明示 |
| **摄像头默认弱口令** | 🟠 中 | 扫描到弱口令时主动提示修改，不做「帮它存起来就完事」 |
| **上传带宽** | 🟢 低 | 单帧 JPEG 压缩到 ≤1080P 再传；弱网自动降质 |

---

## 九、待确认（已逐项拍板）

1. **包名应用 ID**：`com.facemark.camhub`（安卓）/ `com.facemark.camhub`（鸿蒙 bundleName）
2. **账号体系**：复用 facemark 现有体系，新增 `store` 店长角色（见 M3 后端）
3. **推理后端**：先用云端视觉 API（MVP 不自训），后期可换自建模型
4. **HarmonyOS 兼容**：✅ 已确认 → **改用 Flutter 单代码库**（见顶部决策变更）

---

## 十、当前状态

- [x] 规划完成（本文件，含鸿蒙 Flutter 决策）
- [x] M1 骨架（Flutter 工程 + 双端嵌入 + 登录/首页壳）
- [x] M2 摄像头能力（纯 Dart ONVIF + 发现 + 抓图 + 配置落库）
- [x] M3 自查闭环（UI 五页 + 后端 `action=cam.*` 已接入 `cloudflare/src/lib/cam.js`）
- [x] M4 保活与体验（ForegroundService Kotlin + ArkTS 双向实现 + 常亮/白名单通道）
- [ ] M5 兜底（RTSP 取帧 Tapo 等 + 多门店 + 真实视觉模型）

> ⚠️ 验证缺口：本沙箱无 Flutter/Android/OHOS SDK，Dart/ArkTS 代码未编译验证；
> 真机首次跑需 `flutter pub get` + 真摄像头验 ONVIF 发现/抓图（M2 生死线）。
> 后端 cam.inspect 推理为演示占位（demoInfer），接真实模型改 `realInfer` 入参即可，前端零改动。
> **密码存储当前是 base64 占位，上生产前必须换平台 Keystore/KeyStore**，绝不明文。
