# -*- coding: utf-8 -*-

# ***********************************************************************
# Modified based on the KouriChat project
# Copyright of this modification: Copyright (C) 2025, iwyxdxl
# Licensed under GNU GPL-3.0 or higher, see the LICENSE file for details.
# 
# This file is part of WeAuto, which includes modifications to the KouriChat project.
# The original KouriChat project's copyright and license information are preserved in the LICENSE file.
# For any further details regarding the license, please refer to the LICENSE file.
# ***********************************************************************

# 用户列表：请配置要和 bot 对话的账号（格式 ["微信昵称或wxid_xxx@chatroom", "角色名"]，角色名可留空）
# 出厂必须为空 —— 打包分发时 config.py 会原样进 EXE，任何残留都是把你的真实微信号
# 和群号白送给所有付费用户。首次使用在「用户列表」面板里添加即可。
LISTEN_LIST = []

# AI 大模型配置（统一走自有 Worker 网关 /ai/v1，反破解核心：真实 key 只在 Worker 端）
# 发行版（门禁开启）下本机不直连任何供应商，base_url 由 guard 改写为 <worker>/ai/v1。
# 本地开发（门禁关闭）若想直连供应商，可把 DEEPSEEK_BASE_URL 改成火山方舟等直连地址，
# 并在 DEEPSEEK_API_KEY 填入对应 key、MODEL 改成对应模型名。

# ===== AI 来源开关（决定用不用 Cloudflare Workers AI）=====
# 三态，取值 'auto' / True / False：
#   'auto'（出厂默认）—— 自动判断：
#       · DEEPSEEK_BASE_URL 是本网关地址（wetech.jukuai.net/ai/v1 等）→ 走 Cloudflare Workers AI
#       · 或填了真实 DEEPSEEK_API_KEY 且 base_url 是别家地址 → 不用 Workers AI，直连你自己的模型
#       · 都没有 → 仍走 Workers AI（门禁开启时由卡密作凭证，客户端不接触真实上游 key）
#   True  —— 强制走 Cloudflare Workers AI（忽略本地 base_url/key）
#   False —— 强制不用 Workers AI，直连 DEEPSEEK_BASE_URL / DEEPSEEK_API_KEY / MODEL
# 在图形界面「Chat 模型配置 → AI 来源」里切换，改这里即可。
USE_WORKER_AI = 'auto'

DEEPSEEK_API_KEY = 'sk-dummy-placeholder'
# 默认指向自有 Worker 网关（门禁开启时由 guard 覆盖；门禁关闭时此为直连兜底地址）
DEEPSEEK_BASE_URL = 'https://wetech.jukuai.net/ai/v1'
# 主聊天模型：@cf/ 开头 -> Workers AI（Cloudflare，免密钥）；
# 写成火山模型名（如 doubao-seed-1.6-250615）-> 火山方舟（需 Worker 配 VOLCANO_API_KEY）
MODEL = '@cf/meta/llama-3.3-70b-instruct-fp8-fast'
# 用户和AI对话轮数
MAX_GROUPS = 5

# 如果要使用官方的API
# DEEPSEEK_BASE_URL = 'https://api.deepseek.com'
# 官方API的V3模型
# MODEL = 'deepseek-chat'

# 回复最大token
MAX_TOKEN = 2000
# DeepSeek温度
TEMPERATURE = 1.1

# Moonshot AI配置（用于图片和表情包识别）
# API申请https://platform.moonshot.cn/
MOONSHOT_API_KEY = ''
MOONSHOT_BASE_URL = 'https://wetech.jukuai.net/ai/v1'
MOONSHOT_MODEL = 'gpt-4o'
MOONSHOT_TEMPERATURE = 0.8
ENABLE_IMAGE_RECOGNITION = True
ENABLE_EMOJI_RECOGNITION = True

# 消息队列等待时间
QUEUE_WAITING_TIME = 7

# 表情包存放目录
EMOJI_DIR = 'emojis'
ENABLE_EMOJI_SENDING = True
EMOJI_SENDING_PROBABILITY = 25

# 自动消息配置
AUTO_MESSAGE = '请你模拟系统设置的角色，在微信上找对方继续刚刚的话题或者询问对方在做什么'
ENABLE_AUTO_MESSAGE = True
# 主动聊天白名单：仅本列表内的用户/群会收到「主动发消息」；留空 = 不主动向任何人发消息（最安全默认）
AUTO_MESSAGE_USER_LIST = []
# 等待时间
MIN_COUNTDOWN_HOURS = 1.0
MAX_COUNTDOWN_HOURS = 2.0
# 消息发送时间限制
QUIET_TIME_START = '22:00'
QUIET_TIME_END = '8:00'
# 不对群聊发送自动消息
IGNORE_GROUP_CHAT_FOR_AUTO_MESSAGE = False

# 消息回复时间间隔
# 间隔时间 = 字数 * (平均时间 + 随机时间)
AVERAGE_TYPING_SPEED = 0.2
RANDOM_TYPING_SPEED_MIN = 0.05
RANDOM_TYPING_SPEED_MAX = 0.1
SEPARATE_ROW_SYMBOLS = True

# 记忆功能
# 采用综合评分公式：0.6*重要度 - 0.4*(存在时间小时数)
# 示例：
# 重要度5的旧记忆（存在12小时）得分：0.65 - 0.412 = 3 - 4.8 = -1.8
# 重要度4的新记忆（存在1小时）得分：0.64 - 0.41 = 2.4 - 0.4 = 2.0 → 保留新记忆
ENABLE_MEMORY = True
MEMORY_TEMP_DIR = 'Memory_Temp'
MAX_MESSAGE_LOG_ENTRIES = 30
MAX_MEMORY_NUMBER = 50
UPLOAD_MEMORY_TO_AI = True
# 记忆存储方式：True = 保存到单独的JSON文件，False = 保存到prompt文件中
SAVE_MEMORY_TO_SEPARATE_FILE = True
CORE_MEMORY_DIR = 'CoreMemory'

# 是否接收全部群聊消息
ACCEPT_ALL_GROUP_CHAT_MESSAGES = False
ENABLE_GROUP_AT_REPLY = True
ENABLE_GROUP_KEYWORD_REPLY = True
GROUP_KEYWORD_LIST = ['你好', '机器人', '在吗']
GROUP_CHAT_RESPONSE_PROBABILITY = 100
GROUP_KEYWORD_REPLY_IGNORE_PROBABILITY = True

# 配置编辑器设置
# GUI-only 桌面软件：WebUI 只监听本机（ALLOW_OPEN_PORT=False），由 pywebview 内嵌窗口打开。
# 出厂不设密码（PASSWORD_IS_VALID=False）→ 首次启动强制进入 /password_setup 让用户自己设，
# 绝不能出厂就带一个所有人都猜得到的默认口令。
ALLOW_OPEN_PORT = False
LOGIN_PASSWORD = ''            # 登录密码（由首启 /password_setup 写入，勿在出厂值里预置）
PASSWORD_IS_VALID = False      # False = 尚未设置密码，login_required 会强制跳转设置页
PORT = 5001

# 文字指令识别开关
# 开启后，私聊/群聊（满足触发条件）中以“/”开头的指令将被解析并执行
ENABLE_TEXT_COMMANDS = True

# 定时器/提醒设置
# 启用提醒功能
ENABLE_REMINDERS = True
# 是否允许在安静时间内发送提醒 (True/False)
# 如果设置为 False，则在安静时间内安排的提醒将被跳过。
ALLOW_REMINDERS_IN_QUIET_TIME = True
# 是否使用语音通话进行提醒
# 群聊无法使用语音通话进行提醒
USE_VOICE_CALL_FOR_REMINDERS = False

# 联网API配置
ENABLE_ONLINE_API = False
ONLINE_BASE_URL = 'https://wetech.jukuai.net/ai/v1'
ONLINE_MODEL = 'net-gpt-4o-mini'
ONLINE_API_KEY = ''
ONLINE_API_TEMPERATURE = 0.7
ONLINE_API_MAX_TOKEN = 2000
SEARCH_DETECTION_PROMPT = '是否需要查询今天的天气、最新的新闻事件、特定网站的内容、股票价格、特定人物的最新动态等'
ONLINE_FIXED_PROMPT = ''

# 是否启用自动抓取消息中URL链接内容的功能
ENABLE_URL_FETCHING = True
# 网络请求超时时间 (秒)
REQUESTS_TIMEOUT = 10
# Chat API（AI 对话 / 提醒解析）调用超时时间 (秒)
# 消息泵线程会同步调用 AI（如提醒解析），若不限制超时，
# 一次慢请求会长期占用消息泵，导致 bot “活着但收不到消息”。
CHAT_API_TIMEOUT = 60
# 抓取网页时使用的 User-Agent，模拟浏览器防止被屏蔽
# 桌面 Chrome UA：与产品形态一致（本软件是 Windows 桌面端），
# 联网搜索抓的是网页而非移动端接口，用桌面 UA 更自然、也少一些站点的移动版降级。
REQUESTS_USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
# 从网页提取内容的最大字符数，防止上下文过长，影响AI处理效率和成本
MAX_WEB_CONTENT_LENGTH = 2000

# 定时重启配置
ENABLE_SCHEDULED_RESTART = True
RESTART_INTERVAL_HOURS = 2.0
RESTART_INACTIVITY_MINUTES = 15

# 强制移除括号当中的内容
REMOVE_PARENTHESES = False

# 是否使用辅助模型
ENABLE_ASSISTANT_MODEL = False
ASSISTANT_BASE_URL = 'https://wetech.jukuai.net/ai/v1'
ASSISTANT_MODEL = 'gpt-4o-mini'
ASSISTANT_API_KEY = ''
ASSISTANT_TEMPERATURE = 0.3
ASSISTANT_MAX_TOKEN = 1000
USE_ASSISTANT_FOR_MEMORY_SUMMARY = False

# 敏感词处理配置
# 开启后遇到敏感词时自动清除Memory_Temp文件和聊天上下文
ENABLE_SENSITIVE_CONTENT_CLEARING = True

# 论坛自定义模型配置（可选）
ENABLE_FORUM_CUSTOM_MODEL = False
FORUM_BASE_URL = 'https://wetech.jukuai.net/ai/v1'
FORUM_MODEL = 'deepseek-ai/DeepSeek-V3'
FORUM_API_KEY = ''
FORUM_TEMPERATURE = 1.0
FORUM_MAX_TOKEN = 1200

# ===== 对话体验优化 =====
# 1) 思考中占位提示：AI 生成回复前先发一条轻量占位，消除“对方长时间无反应”的空窗焦虑
#    （像真人聊天的“在想…”，不写入对话上下文，不影响记忆/风格学习）
ENABLE_THINKING_PLACEHOLDER = True
THINKING_PLACEHOLDERS = ["在想…", "嗯，让我想想", "稍等，我琢磨一下", "收到，思考中…", "让我理理思路~"]

# 2) 单条消息最大字数：超过自动分段发送，避免被微信单条上限（约 2000 字）截断，
#    或单条过长导致 SendMsg 失败、用户收不到完整回复
MAX_SINGLE_MSG_LEN = 800


# ===== Creem 许可（卡密）集成 =====
# 架构（铁律：bot.py 接通 Creem，不受 GPL-3.0 约束，按项目方指令执行）：
#   客户端(EXE) 仅与本项目的 Cloudflare Worker 通信，Worker 持有 Creem 密钥并代理
#   api.creem.io 的 license 调用。客户端绝不接触 Creem 域名、不持有任何 Creem 密钥。
# 详见 weauto_license/README.md
LICENSE_GUARD_ENABLED = False   # 开发期 False；正式发布设 True
CREEM_WORKER_URL = "https://wetech.jukuai.net"   # 已部署的 Worker（2026-09-28 起主用域名；weauto.safeopc.cn 仍作备用）
CREEM_LICENSE_KEY = ""          # 用户购买后在 Creem 拿到的卡密，填这里


# ===== Jev 判断式 AI ======================================================
# 内置能力，出厂自动运行，**不需要任何配置项**。
# 回复前先做一次结构化判断（对方真实意图 / 关系危险度 0-9 / 对方现在需要什么 /
# 下一步最佳动作 / 是否该给实质内容），结论注入 system 提示让主模型照着判。
# 技术来源：jev-chat-jarvis（MIT）。判断跑在自建 Cloudflare Worker 上，
# Worker 内用 Workers AI 的 JSON Mode，不出 CF、无需 OpenRouter、无需卡密。
# 要调参数（超时/阈值/端点/是否收声）改 jev_guard.py 顶部的 JEV_* 常量。
# 状态与自检见 WebUI 顶部的「Jev 状态」按钮（只读 + 一个测试按钮）。
#
# 客户端 <-> CF 后台通信密钥（防白嫖 Workers AI 额度）。
# 与 Creem 卡密无关！这是「自己的 EXE <-> 自己的 Worker」之间的 HMAC 签名密钥。
# 留空 = Jev 端点公开（开发/向后兼容）。要真防白嫖：
#   1) 在本行填一个长随机串（如 python -c "import secrets;print(secrets.token_hex(32))"）
#   2) 在 Cloudflare 注入同值：cd weauto_license && wrangler secret put WEAUATO_CLIENT_SECRET
# 两端必须一致，否则请求会被 401 拒绝。
WEAUATO_CLIENT_SECRET = "e3471d8e67bfbcb8823b038d7a2af30d4b15d91e480c98c2"


