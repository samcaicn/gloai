# vbaokebase.apk 反编译说明

来源：`C:/d/aizhibo/vbaokebase.apk`（28,618,884 bytes，1106 个条目）

## 应用身份（来自 AndroidManifest + uni-app manifest）

| 项 | 值 |
|---|---|
| 包名 | `com.vbaoke.zhibo` |
| 应用名 | AI直播助手（label: `Ai无人直播`） |
| 版本 | 1.3.0 / versionCode 132 |
| 框架 | DCloud uni-app（Vue 3，编译器 4.84，uni-v3），App-Plus 壳 `io.dcloud.PandoraEntry` |
| SDK | min 24 / target 33 / compile 35 |
| 主 Activity | `io.dcloud.PandoraEntry` |
| 后端 | `https://aizhibo.vbaoke.top` |

## 目录

- `www/` — uni-app 完整 H5 源码（app-service.js 1.9MB、app-config-service.js、app.css、pages/*、static/*、uni_modules/*），是应用真实业务逻辑所在
- `analysis/manifest.json` — androguard 解析的 Manifest 摘要（组件/权限/版本）
- `analysis/classes.json` — 16606 个 DEX 类（`io.dcloud.*` 壳、个推、腾讯 IM/LiteAV、微博/微信 SDK、UTS 原生模块 `LGForeground`/`LGAccessibility` 等）
- `analysis/chinese.txt` — 643 条中文字符串（功能命名/话术/UI 文案）
- `analyze.py` — 复现分析脚本（隔离 venv + androguard 4.1.4）
- `AndroidManifest.bin.xml` — 原始二进制 AXML

## 业务结构（来自 routes / 中文串）

- Tab：首页 / 直播 / 我的
- 页面：登录注册、商品/购物车/订单/地址/售后、智能体（多模型对比/长期记忆/提示词库）、数字人、文章、AI 会话、直播中控、会员充值/卡密/算力
- 直播链路：选择平台 → 直播场景（语音创建）→ 一键开播 → 数字人按话术口播 + TTS → 弹幕 WebSocket 监听 → AI 智能回复 → RPA 自动点「讲解」→ 单品轮播
- 平台接入：抖音 / 快手 / 淘宝 / 京东 / 小红书 / 视频号 / 美团 / 拼多多

> 反编译产物仅用于产品原型还原与二次开发参考，未包含原签名密钥。
