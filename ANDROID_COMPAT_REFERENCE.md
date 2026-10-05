# Android 兼容开发参考（jev-chat-jarvis 离线快照）

> 本文件是本地 `C:/d/android/jev-chat-jarvis` 代码的**兼容开发参考残留**。
> 该代码已删除（用户决定不再本地维护 —— "Android 早都别人开发了"），
> 源码主干仍在 **腾讯工蜂 `AImarketing/weauto` 的 `android` 分支**，
> 并每 10 分钟自动同步到 GitHub `samcaicn/gloai:jev-chat-jarvis` 跑 CI 出 APK。
> 这里只保留**做 Android 端兼容开发时最易踩的坑与关键事实**，不保留整份代码。

## 0. 项目定位（jev-chat-jarvis）
- Kotlin + AndroidX 无障碍「聊天副驾」：读微信/QQ/飞书等聊天 App 屏幕内容 → 判断式 AI 分析 → 悬浮窗给候选回复 → 一键填入输入框（发送权在用户）。
- 入口伪装：`SelectToSpeakService`（AccessibilityService 合规马甲）、`WxNotificationListener`（NotificationListenerService）。
- 关键常量（在 `ChatCaptureService.kt`）：
  - `PKG_WECHAT = "com.tencent.mm"`
  - `ACTION_WX_MSG = "com.jev.probe.action.WX_MSG"`（通知监听 → 捕获服务 的跨进程广播）
  - 裁剪 `TOP_CROP=0.12f` / `BOTTOM_CROP=0.84f`（只截聊天区域）
- ⚠️ **合规提醒**：本地下载的分支含「微信无障碍代发」整套实现（无人值守闭环）。
  上游 `origin/main`（jev-chat/jev-chat-jarvis）README 已声明「微信 Android 版已下架，不再采集或处理微信内容」。
  若将来复用这套无障碍方案，注意平台合规风险与微信版本差异。

## 1. 构建配置（app/build.gradle.kts）
| 项 | 值 | 备注 |
|---|---|---|
| compileSdk / targetSdk | 35 | |
| minSdk | 30 | 只支持 Android 11+ |
| versionCode/Name | 5 / 1.4 | |
| ndk.abiFilters | `arm64-v8a` only | 目标机 arm64，删掉其余 ABI 减重 |
| Java/Kotlin | 17 / jvmTarget 17 | CI 用 Temurin 17 |
| OCR 依赖 | `com.google.mlkit:text-recognition-chinese:16.0.1` | **bundled 中文模型**，无需 Google Play、无需下载模型、离线可用 |
| 其他 | core-ktx 1.13.1 / appcompat 1.7.0 / material 1.12.0 / constraintlayout 2.1.4 | |

- **签名**：release 用 v1+v2+v3 全开，storeType 强制 `PKCS12`
  （openssl 生成的 p12，AGP 不 pin 会误判格式拒密码）。
  来源优先级：① CI `SIGNING_KEY`(base64 p12) env → ② 本地 `JEV_KEYSTORE_PROPS`
  （默认 `H:/android/keys/jev-release.properties`）。两者皆空则 release **不签名**
  （CI 仍成功，靠 "Verify release APK is signed" 步骤兜底 fail）。
- **16 KB page-size 设备**（Android 15+）：`packaging.jniLibs.useLegacyPackaging=false`
  （未压缩、页对齐 .so，让 ML Kit 原生库走 mmap 而非安装时解包）。
- **minify 关闭**（`isMinifyEnabled=false`），无障碍/反射路径多，混淆易炸。

## 2. AndroidManifest 权限与服务（最易漏/错）
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />      <!-- 悬浮窗 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```
- AccessibilityService：`android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` +
  `<meta-data android:name="android.accessibilityservice" android:resource="@xml/config_disguised"/>`
- 前台保活 `KeepAliveService`：`android:foregroundServiceType="specialUse"` +
  `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`
- 通知监听 `WxNotificationListener`：`android:label="AI聊天助手 · 微信通知"`（给用户看的名字要正经）。

## 3. 微信无人值守闭环（Send/Receive 两侧）
- **RECEIVE 侧**（WxNotificationListener，只过滤 `com.tencent.mm`）：
  新消息 → 本 app 内广播 `ACTION_WX_MSG` → ChatCaptureService 重读会话 / 触发图片 OCR。
  可选 `autoOpenChat`（默认关）点通知 contentIntent 拉会话前台。
- **读与填**（SelectToSpeakService + ChatCaptureService）：
  无障碍 `canRetrieveWindowContent` 读节点树 → 找可编辑框 `findEditable(root)` →
  `autoFillBest`（默认关）直接填 prob 最高候选 → `autoSend`（默认 true）点发送。
- **图片 OCR**：无障碍截屏 API（避开微信手动截图风控）；
  触发条件 `imageMessage!=null && (imageIsLatest || imageOcrRequested)`。
  仅用于"读图中文字"这一步，OCR 文字挂在悬浮球/快照里。
- 完整闭环：通知 →(autoOpenChat) 拉前台 → 读树 →(autoAnalyze) 分析
  →(autoFillBest) 填最佳候选 →(autoSend) 发。中间两步默认关是有意的（AI 不经人眼回话风险高）。

## 4. ⭐ 兼容开发踩坑（别再踩）
1. **`wx_notification_listener.xml` 是「无命名空间」属性**：
   `notification-listener` 的 `defaultFilterType`/`package-filter` 的 `packageName`
   运行时由 `NotificationListenerFilter.getAttributeValue(null, ...)` 读取，**绝不能加 `android:` 前缀**，
   否则 AAPT 链接失败 `attribute android:defaultFilterType not found`。
   （run 1191 即此坑，正确写法无前缀：`defaultFilterType="disallowed"` + `<package-filter packageName="com.tencent.mm"/>`。）
2. **AAPT 失败会掩盖后续所有 Kotlin 编译错误**：资源阶段先挂，Kotlin 错误（如隐藏的 NUL 字节、`val key="$title␀$text"`）
   一直不暴露。排障时先看资源错误。
3. **运行时缺陷（静态审查看不出，真机时序才暴露）**：
   - 去重键写太早且失败不回滚（`lastImageOcrSig`）：瞬时截图失败会让某张图永久不再 OCR。
     通则：派发前置标记要区分「可重试失败（回滚）」与「终态结果（保持已处理）」。
   - companion 里的 `@Volatile` 状态（如 `weChatForeground`）比实例活得久 → `onDestroy` 必须复位，否则服务被杀后残留值让依赖它的逻辑永久失效。
   - 标志位在 guard 之前被消费：提前 `return` 会吞掉通知标志（如 `imageOcrRequested`）→ 下一张图永不 OCR。消费动作放所有 guard 之后。
   - 广播补 `setPackage(packageName)`，不单靠 `RECEIVER_NOT_EXPORTED`。
4. **`file("H:/...")` 盘符在 Kotlin DSL 里会被当 URL scheme 抛异常**：用 `File("H:/...")` 代替（路径不存在时只产生一个 exists()=false 的 File，更安全）。
5. **`gradlew` 在 checkout 后需 `chmod +x`**（644 在某些 runner 上跑不起）；CI 用 `ubuntu-latest` 自带 Android SDK，
   `android-actions/setup-android@v3` 在该 runner 上会挂 —— 改用预装 SDK + `sdkmanager --licenses`。
6. **CI `paths-ignore: '**.md'、'site/**'、'docs/**'`**：只改文档的提交**不触发构建**，是预期行为别误判成同步失败。

## 5. CI / 签名 / 同步架构
- **build.yml**（`samcaicn/gloai` 仓库 `jev-chat-jarvis` 分支，`Build APK` job）：
  - 解码 `SIGNING_KEY`(base64 p12) → `$RUNNER_TEMP/keystore.p12`；
    **guard 必须放在 shell 里，不能放 `if:`**（secrets 上下文在 `if:` 启动时不可靠，否则 0 job 启动失败）。
  - `timeout-minutes: 45` + `concurrency`（同分支连推只留最新）。
  - **Verify release APK is signed**：读 zip 找 `META-INF/*.RSA|.EC|.DSA`，缺则 `sys.exit(1)` 防静默未签名。
  - 失败自动 `gh issue create` 把 `build_*.log` 关键行贴进 issue（编译错误正文在 issue body，不在 job log）。
- **同步架构（已端到端验证）**：
  ```
  本地 → 腾讯 weauto:android（源码主干）
        ↓ sync-from-tencent.yml（每 10 分钟 cron，必须在默认分支 tmp-empty 上）
        GitHub gloai:jev-chat-jarvis → build.yml → 已签名 release APK
  ```
  - cron **只在默认分支跑**；runner 连腾讯 git 需：`HostKeyAlgorithms +ssh-rsa` +
    `PubkeyAcceptedAlgorithms +ssh-rsa,ssh-ed25519` + 显式 `IdentityFile`（非标准名 `id_rsa_tencent` ssh 不自动发现）。
  - 同步推送必须走 **PAT(`SYNC_PAT`)** 而非 `GITHUB_TOKEN`（后者 push 不触发下游工作流）。
  - 同步后**轮询校验 GitHub HEAD == 腾讯 HEAD**，不一致即 fail。
- 签名证书 SHA1（与本地 keystore 备份一致）：`0E:66:69:0F:77:58:2D:B2:FE:F0:71:F7:81:1D:C9:FC:28:D7:20:55`
  ；本地备份 `C:/Users/vangq/jev-release-key/keystore.p12` + `KEYPASS.env`（删仓库代码**不要删这个**，丢了无法重签）。

## 6. 失败日志抓取手法（CI 排障）
- Actions job 日志接口 `GET /actions/jobs/{id}/logs` 返回 302 → Azure blob，
  urllib 默认带 `Authorization: Bearer` 跟随 302 → blob 拒收 → `401`。
  **必须自定义 redirect handler 让 302 抛错拿到 Location，再不带任何 auth header 重取**。
- 下载产物/日志时**不要加 `Accept: application/octet-stream`**（会 415）。
- 验证签名最快手法：不下载 23MB APK，直接抓 job 日志 grep `release APK is signed OK`。
