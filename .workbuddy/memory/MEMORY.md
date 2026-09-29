# MEMORY.md — jev-chat-jarvis / gloai Android 项目（2026-09-29）

## 关键事实（易错点）
- **CI 在哪个仓库构建**：本机工程 `C:/d/android/jev-chat-jarvis`，但 APK 的 GitHub Actions
  **在 `samcaicn/gloai` 仓库**里跑（分支 `jev-chat-jarvis`）。本地分支叫 `snap`，
  推送命令：`git push gloai snap:jev-chat-jarvis`（SSH，`gloai` remote = `git@github.com:samcaicn/gloai.git`）。
- **`origin` 不是推送目标**：`origin` = `https://github.com/jev-chat/jev-chat-jarvis.git`（HTTPS，需凭据）。
  往 origin push 会因无 TTY 卡在用户名/密码 → 进程被 SIGTERM。**永远推 `gloai` remote。**
- 没有本地 JDK/SDK，编译验证只能靠 CI；push 后等 `samcaicn/gloai` 的 Actions run 结论。
- `gloai` 远程 SSH 直连可达（GitHub 直连被墙，但 SSH 协议这条线实测可通）。

## 已落地功能（微信无人值守，commit c492210 在 0cccc3d 之上）
- AccessibilityService（伪装 `SelectToSpeakService`）读微信节点树 + 填入回复；`autoSend=true` 时填完自动点发送。
- `WxNotificationListener`（NotificationListenerService，只监听 `com.tencent.mm`）做 RECEIVE 侧：
  新消息→同 app 广播 `ACTION_WX_MSG`→`ChatCaptureService` 重读会话 / 触发图片 OCR。
  可选 `autoOpenChat`（默认关）点通知 contentIntent 把会话拉前台。
- 图片消息 OCR 只用于「读图片里的文字」这一步（无障碍截屏 API，避开微信手动截图风控）；
  在 `maybeCapture` 里 `imageMessage!=null && (imageIsLatest || imageOcrRequested)` 才触发。
- Prefs 开关：`autoSend`(默认true) / `autoOpenChat`(默认false) / `ocrImages`(默认true)。

## 踩坑记录（别再踩）
- **AAPT 资源链接失败**：`res/xml/wx_notification_listener.xml` 的
  `android.service.notification.notification_listener_filter` 元数据里
  `defaultFilterType` / `packageName` 是**无命名空间**属性（运行时
  `NotificationListenerFilter.getAttributeValue(null, ...)` 读取），**绝不能加 `android:` 前缀**，
  否则 AAPT 报 `attribute android:defaultFilterType not found`（run 1191 即此坑）。正确写法无前缀。
- **GitHub Actions job 日志抓取**：`GET /repos/{o}/{r}/actions/jobs/{id}/logs` 返回 302 到
  blob 存储（SAS token 在 query）。urllib 默认会带 `Authorization: Bearer` 自动跟随 302 →
  blob 拒收 Bearer → `401 InvalidAuthenticationInfo`。必须用自定义 `HTTPRedirectHandler`
  让 `redirect_request` 抛 `HTTPError` 以捕获 `Location`，再**不带任何 auth header** 重新请求该 URL。
- **CI 失败会开 Issue**：build.yml 在 build 失败时 `gh issue create` 把 `build_*.log` 的关键行
  贴进 issue（如 issue #16），编译错误正文不在 job log 而在该 issue body 里。
