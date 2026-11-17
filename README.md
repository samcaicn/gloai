# AI智播

AI 数字人直播助手。仓库包含两套前端产物：

| 文件 | 说明 | 目标形态 |
|---|---|---|
| `index.html` | **用户端开播 App**（移动竖屏）：首页 / 开播配置 / 直播中 / 互动消息 / 我的 | APK 内嵌主界面 |
| `admin/index.html` | **管理后台**（从 APK 反编译还原的完整中控台） | 部署到 `aizhibo.tuptup.top/admin` |

用户端界面已预留接口接入层（`index.html` 内的 `API` 对象）：
- 短视频接口 → `API.loadShortVideos()`
- 数字人直播接口 → `API.startLiveApi()` / `API.stopLiveApi()`

后续对接百度一镜 PaaS 时只替换这几个 mock 方法即可，UI 与交互无需改动。

## 分支

- `aizhibo` — 本项目原型与 CI 热更新产物分支

## 本地预览

浏览器直接打开 `index.html`（用户端）或 `admin/index.html`（管理后台）即可，无需构建。

## CI 产物

`ci.yml` 三条腿（push 到 `aizhibo` 自动触发，也支持 Actions 手动 Run）：

| 产物 | Artifact 名 | 用途 |
|---|---|---|
| Web 热更新包 | `ai-zhibo-web` | 用户端 index.html + 管理后台 admin/index.html + version.json + manifest.json |
| 正式签名 APK | `ai-zhibo-apk-release` | WebView 壳把**用户端**界面打成 APK，复用 weauto/jev 同证书签名 |

## 热更新流程

参考 weauto 的 Artifact 分发模式：

1. 修改根目录 `index.html`（用户端）/ `admin/index.html`（后台）/ `version.json`（递增版本）
2. `git push origin aizhibo`
3. CI 体检 → 生成 `manifest.json` → 上传两个 Artifact（保留 90 天）
   - APK job 自动把根目录 `index.html` 同步进 `app/src/main/assets/`
4. **Web 端**：分发端按 `ai-zhibo-web` 拉取，按 `version`/`sha256` 比对热替换
5. **APK 端**：壳优先加载 `filesDir/index.html`，装一次后只替换该文件即可更新界面，无需重装

## 签名说明

正式包使用与 weauto / jev-chat-jarvis **同一份 keystore**（证书 SHA1 `0E:66:...:20:55`），
由仓库 Secrets 提供，CI 解码后签名并用 `apksigner verify` 校验。**不再产出 debug 包**，
同证书可直接覆盖安装旧版本，不丢数据。

CI 需要的仓库 Secrets（Settings → Secrets → Actions，与 jev 分支共用）：
- `SIGNING_KEY` = base64(keystore.p12 全文)
- `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` / `SIGNING_STORE_PASSWORD`

## 本地构建正式 APK

需要 Android SDK + JDK 17 + keystore：

```bash
cp index.html app/src/main/assets/index.html
# 方式一：环境变量
export SIGNING_STORE_FILE=/path/to/keystore.p12
export SIGNING_KEY_ALIAS=... SIGNING_KEY_PASSWORD=... SIGNING_STORE_PASSWORD=...
# 方式二：本地属性文件（AIZhibo_KEYSTORE_PROPS 或 ../keys/release.properties）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```
