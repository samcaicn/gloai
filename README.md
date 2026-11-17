# V播客 · AI直播助手

由 `vbaokebase.apk` 反编译还原的**第二版本**。与 `aizhibo` 分支（AI智播）同仓库、不同分支、不同产物，互不影响。

| 文件 | 说明 | 目标形态 |
|---|---|---|
| `index.html` | **V播客用户端原型**（移动竖屏）：首页数据 / 快捷入口 / 导播控制台（话术下发 + 场控弹幕 AI 回复 + 单品讲解）/ 我的 | APK 内嵌主界面 |
| `admin/index.html` | 完整中控台原型（沿用） | 部署到 `/admin` |
| `decompiled/` | `vbaokebase.apk` 反编译产物（H5 源码 + 清单 + DEX 类表 + 中文串） | 溯源与二次开发 |

## 反编译结论

- **应用名**：AI直播助手（启动标签 `Ai无人直播`），包名 `com.vbaoke.zhibo`
- **版本**：1.3.0（versionCode 132），DCloud uni-app（Vue3 / 编译器 4.84），minSdk 24 / targetSdk 33
- **后端**：`https://aizhibo.vbaoke.top`，集成 火山方舟 / 通义 / OpenAI 兼容模型、腾讯云直播、个推、微信/微博登录
- **能力**：一键开播、导播控制台、话术下发、场控与弹幕 AI 回复、数字人播报、单品讲解、音色复刻、智能体/漫剧/电商、RPA 无障碍服务（`LGAccessibilityService`）/前台保活
- **与 aizhibo 分支差异**：包名 `com.vboke.app` vs `com.aizhibo.app`；主题色 微信绿 `#07C160` vs 青蓝；产物名 `vboke-*` vs `ai-zhibo-*`

## 分支与 CI

- 分支：`vboke`（本项目第二版本分支）
- Push 到 `vboke`（或 Actions 手动 Run）触发 `ci.yml`：
  - `check`：结构体检（产品名/HTML 标签/括号匹配）
  - `package`：web 热更新产物 → artifact `vboke-web`
  - `apk`：正式签名 APK → artifact `vboke-apk-release`（复用同仓库 SIGNING_* secrets，与 aizhibo 同证书可覆盖）

## 本地预览

浏览器直接打开 `index.html`（用户端）或 `admin/index.html`（中控台），无需构建。

## 本地构建正式 APK

```bash
cp index.html app/src/main/assets/index.html
export SIGNING_STORE_FILE=/path/to/keystore.p12
export SIGNING_KEY_ALIAS=... SIGNING_KEY_PASSWORD=... SIGNING_STORE_PASSWORD=...
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```
