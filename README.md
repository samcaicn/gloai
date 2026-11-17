# AI智播

AI 数字人直播中控台核心原型（单文件静态站点）。

## 分支

- `aizhibo` — 本项目原型与 CI 热更新产物分支

## 本地预览

直接用浏览器打开 `index.html` 即可，无需构建。

## CI 产物

`ci.yml` 三条腿（push 到 `aizhibo` 自动触发，也支持 Actions 手动 Run）：

| 产物 | Artifact 名 | 用途 |
|---|---|---|
| Web 热更新包 | `ai-zhibo-web` | index.html + version.json + manifest.json（含 sha256）|
| Android APK | `ai-zhibo-apk` | WebView 壳把原型打成可安装 APK（debug 签名）|

## 热更新流程

参考 weauto 的 Artifact 分发模式：

1. 修改根目录 `index.html` / `version.json`（改版本时同步递增 `version` 字段）
2. `git push origin aizhibo`
3. CI 体检 → 生成 `manifest.json` → 上传两个 Artifact（保留 90 天）
   - APK job 会自动把根目录 `index.html` 同步进 `app/src/main/assets/`，无需手动复制
4. **Web 端**：分发端按 `ai-zhibo-web` 拉取最新 index.html，用 `version`/`sha256` 比对热替换
5. **APK 端**：APK 壳优先加载 `filesDir/index.html`，装一次后只需热替换该文件即可更新原型，无需重装 APK

## 本地构建 APK

需要 Android SDK + JDK 17：

```bash
cp index.html app/src/main/assets/index.html
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```
