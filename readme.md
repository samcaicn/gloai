# WeAuto（原 WeChatBot_WXAUTO_SE）

智能微信聊天机器人 —— **WeAuto**，已支持 **微信 4.x**（同时兼容微信 3.9）。

- 由 `WeChatBot_WXAUTO_SE`（作者 **iwyxdxl**）升级改造而来，并重新命名为 **WeAuto**。
- 基于 [KouriChat](https://github.com/57u/KouriChat) 项目进行大量修改与重构，遵循 GPL-3.0 许可证。
- 微信收发基于内置的 **wechatauto 引擎**（位于 `vendor/wechatauto`，已打 WAL 日志 / 方向补丁），替代旧版 `wxauto` / `wxautox`，已通过微信 4.x 实测。
- 调用 DeepSeek、GPT、Gemini、Claude、Grok 等大语言模型，生成拟人化回复（通过 OpenAI 兼容接口）。

---

## 效果展示

<img src="Demo_Image/1.png" alt="示例图片1" width="300px">
<img src="Demo_Image/2.png" alt="示例图片2" width="300px">
<img src="Demo_Image/3.png" alt="示例图片3" width="300px">
<img src="Demo_Image/4.png" alt="示例图片4" width="900px">
<img src="Demo_Image/5.png" alt="示例图片5" width="900px">

---

## 版本信息

| 项目 | 内容 |
| --- | --- |
| 当前版本 | v3.25.1 |
| 最后更新 | 2025-10-16 |
| Python 要求 | 3.9 ~ 3.13 |
| 微信要求 | 3.9 / 4.x |
| Web 配置后台端口 | 5001 |
| 许可证 | GNU GPL-3.0（或更高版本） |

---

## 核心特性

1. **智能自动回复**：支持多用户 / 群聊同时聊天，可为每个用户或群聊分配独立提示词（Prompt）。
2. **图片与表情包识别**：识别聊天中的图片、表情包内容（可自定义识图模型，支持重试）。
3. **情绪识别 + 表情包回复**：识别对方情绪并自动回相应表情包。
4. **网页 / 链接内容提取**：读取消息中的链接、卡片链接内容。
5. **AI 时间感知**：回复自带「年-月-日 星期 时-分-秒」时间轴。
6. **主动消息**：可定时 / 条件触发主动发送，支持合并处理多条消息与表情包，支持屏蔽群聊。
7. **WebUI 配置后台（端口 5001）**：网页内启动 / 停止 Bot、可视化修改配置、生成与管理 Prompt、查看日志、一键排查。
8. **记忆功能**：AI 自动总结聊天记录，保存为 Prompt 片段或独立「核心记忆」文件；可设置上下文轮数、手动 `/总结`（`/ms`）指令。
9. **定时任务**：让 AI 设置提醒（如「15 分钟后提醒我出门」「每天 8 点叫我起床」），支持语音通话提醒，重启后不再重新计时。
10. **联网搜索**：内置联网搜索能力。
11. **语音消息接收**：接收微信语音（需在微信设置开启「聊天中的语音消息自动转文字」）。
12. **自动更新**：`updater.py` 检测并下载更新（支持国内更新源）。
13. **角色论坛**：趣味「角色论坛」功能，让你的角色发布论坛帖子（头像支持本地上传）。
14. **指令系统**：可通过指令控制重启、主动消息、语音通话、清除记忆等。
15. **微信交互增强**：识别「拍一拍」（回复 `[tickle]` / `[tickle_self]`）、撤回（`[recall]`）、引用消息、合并转发消息。
16. **群聊精细化**：可设触发关键词、回复概率、备注识别。
17. **稳定性 / 运维**：定时重启、内存监控自动清理、上下文自动保留、敏感词自动清上下文、失败自动重试、密码登录 + 随机端口。

---

## 目录结构

```
WeChatBot_WXAUTO_SE-3.25.1/
├── bot.py                 # 核心机器人逻辑（监听/回复/记忆/表情/指令）
├── config_editor.py       # Flask Web 配置后台（端口 5001，程序入口）
├── config.py              # 运行配置（含 API Key，敏感，勿外传）
├── updater.py             # 自动更新器
├── wxauto_compat.py       # 旧版 wxauto 兼容层
├── vendor/
│   └── wechatauto/        # 微信 4.x 引擎（内置，已打补丁）
├── templates/             # WebUI 页面（config_editor / login / quick_start / character_forum）
├── prompts/               # 角色 / 提示词（角色1.md、角色2.md…）
├── emojis/                # 自定义表情包（按情绪分类目录）
├── Demo_Image/            # 效果图
├── libs/                  # 离线 wheel 兜底（Python 3.9~3.12）
├── requirements.txt       # 依赖清单（全部 --only-binary）
├── Run.bat                # 一键启动器（检查环境→装依赖→更新→启动）
├── 一键检测.bat           # 一键排查程序问题
├── WeAuto.spec            # PyInstaller 打包配置
├── CHANGELOG.md           # 更新日志
└── DEPENDENCIES.txt       # 第三方依赖许可证说明
```

---

## 使用前准备

1. 安装 **Python 3.9 ~ 3.13**（建议勾选「Add to PATH」），并确认 `pip` 可用。
2. 安装 **微信 3.9 或 4.x** 并保持后台登录运行。
3. 申请大模型 API Key（推荐 WeAPIs：<https://vg.v1api.cc/register?aff=Rf3h>，支持 GPT / Grok / Claude / Gemini / DeepSeek-R1 联网版等）。

> 依赖安装全部走二进制 wheel（`--only-binary=:all:`，不本地编译）；`Run.bat` 会按 **阿里云 → 清华 → 官方** 顺序自动选择最快镜像源。

---

## 快速上手

1. 登录电脑微信（支持 4.x），保持后台运行。
2. 双击运行 **`Run.bat`**：自动检查微信 / Python 版本 → 安装依赖（或手动 `venv\Scripts\python -m pip install -r requirements.txt`）→ 检查更新 → 启动 Web 后台。
3. 浏览器打开配置后台（默认 `http://127.0.0.1:5001`），修改配置：选择 API 服务商 / 模型，填入 API Key（网页端已隐藏避免泄露）。
4. 左侧点击 **「Prompt 管理」**，参考自带提示词编写，或用提示词生成器生成。
5. 回到配置编辑器，填入微信昵称 / 群聊名称，并选择对应提示词。
6. 点击页面右上角 **「Start Bot」** 启动。
7. 自定义表情包：将 `.gif / .png / .jpg / .jpeg` 放入 `emojis/` 下对应情绪目录（可自行新增情绪种类）。
8. 若运行异常，双击 **`一键检测.bat`** 一键排查。

---

## 构建（可选 · 打包为单文件 exe）

使用 PyInstaller（配置见 `WeAuto.spec`）：

```bat
pip install pyinstaller
pyinstaller WeAuto.spec
:: 产物：dist/WeAuto.exe
```

- 入口为 `config_editor.py`，打包时一并收集 `config.py`、`templates`、`emojis`、`prompts`、`Demo_Image` 与 `wechatauto`。
- 单文件 exe 运行时会将资源释放到自身目录，冻结模式下路径已做兼容处理。

---

## 自动更新与 CI

- `updater.py` 指向 GitHub `samcaicn/gloai` 的 `weauto` 分支检查更新。
- CI（`.github/workflows/build.yml`）在该分支构建**加密混淆**的 `WeAuto.exe`，产物以 GitHub Actions Artifact 保留 **90 天**，供本机下载与自动更新使用。

---

## 许可证与依赖说明

- **主许可证**：GNU GPL-3.0 或更高版本（详见 `LICENSE`）。
- **微信自动化引擎**：采用内置 `vendor/wechatauto`（已获授权）；旧版 `wxauto`（Apache-2.0）作为开源备选，动态导入 + fallback 机制，任何情况下均有 GPL-3.0 兼容实现可用。
- **依赖清单与许可证**：详见 [DEPENDENCIES.txt](DEPENDENCIES.txt)。
- **用户权利**：无论使用哪种依赖库，用户均享有完整的 GPL-3.0 自由软件权利。

---

## 联系作者

- 邮箱：iwyxdxl@gmail.com
- QQ：2025128651

---

## 更新日志

完整历史见 [CHANGELOG.md](CHANGELOG.md)。

**近期版本：**

- **v3.25.1（2025-10-16）**：修复 bugs。
- **v3.25（2025-10-13）**：完善密码登录机制；优化服务器安全性能；修复安全漏洞；新增随机端口功能。
- **v3.24.1（2025-09-29）**：完善密码登录；修复撤回表情包误撤前条消息。
- **v3.24（2025-09-13）**：支持「拍一拍」/「撤回」；新增核心记忆；论坛头像本地上传；`/总结`（`/ms`）指令；旧版本数据一键导入。
- **v3.23（2025-08-20）**：新增角色论坛；新增指令系统；记忆整理优化；支持国内更新源。
