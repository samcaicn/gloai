# 电脑端 CLI 与 Agent Skill

简体中文 | [English](cli.en.md)

> 从电脑使用 Mika，先看[从电脑使用 Mika](computer.md)：怎么在 CLI + Skill 和 MCP 之间选、怎么连。

CLI 与 MCP 是并列入口,共用手机端的远程任务服务。CLI 调用普通 HTTP 任务 API,不需要 MCP SDK、握手、工具发现或 MCP 客户端配置。Skill 告诉电脑 Agent 怎样使用 CLI、处理追问和报告真实结果;手机上的 Mika 仍负责规划和执行。

```mermaid
flowchart LR
    Skill["电脑 Agent + Skill"] --> CLI["Mika CLI"]
    Shell["用户 / Shell 脚本"] --> CLI
    Client["MCP 客户端"] --> MCP
    subgraph Phone["Android 手机 · 同一监听与鉴权"]
        API["RemoteTaskApi · /api/v1"]
        MCP["McpRpc + McpTaskTools · /mcp"]
        Shared["RemoteTaskService · 共用任务用例"]
        Hub["RemoteTaskHub · 同一任务索引"]
        Engine["TaskService / TaskRun / AgentLoop"]
        API --> Shared
        MCP --> Shared
        Shared --> Hub --> Engine
    end
    CLI -->|"HTTP，可经 ADB 转发"| API
```

## 安装与维护

安装、升级、回退和卸载见 [CLI / Skill 安装指南](computer-install.md)。CLI 与 MCP 通常二选一。手机需要 task API v1,官方 APK 从 v0.1.23 起提供。

只装 Skill 时,下面的 `mika` 代表 `python3 <技能目录>/mika/scripts/mika_cli.py`（Windows 用 `py -3`）。

## 连接手机

ADB 模式需要 Android platform-tools。数据线或已经配对并连接的无线 ADB 都可以；无线 ADB 需要这台电脑与手机完成配对、连接，并在 `adb devices` 中显示为 `device`。Mika 在手机内为启动助手完成的配对，不等于电脑已配对；它们也不同于下面的直接局域网访问。

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

只有一个在线设备时可省略 `--device`;也支持已有的 ANDROID_SERIAL 环境变量。多设备不猜目标,连接失败也不切换到另一台手机。`connect` 调用 Mika 写到手机上的官方启动脚本,打开电脑连接并启动所需助手;此动作不提交业务任务。其余命令只读取当前连接。

每次调用重新读取当前令牌、端口和开机编号,申请自己的 `adb forward tcp:0` 临时端口,完成后只删除该转发。令牌只保存在进程内存中,启动脚本的原始输出不转发到终端。CLI 退出不关闭手机服务,也不停止正在执行的任务。

同一台手机并排装了调试版时，可以用 `mika connect --package com.wangzan101.mika.dev` 连接。后续用 `doctor`、`task` 或 `ui` 时继续指定同一个包名。省略 `--package` 时连接 `com.wangzan101.mika`。如果两个版本都开启电脑连接，请为它们设置不同的监听端口。`--package` 用于 ADB 连接，不能与 `--url` 同用。

CLI 会处理端口转发，无需照 MCP 步骤手工转发固定端口。拔插后先让同一台手机重新出现在 ADB 中；手机重启或电脑连接开关被关闭后，再运行 `connect`。

也可以使用手机设置里显式开启的局域网/私有网络连接:

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

令牌文件只包含访问令牌,由用户按私有文件保存。URL 使用服务器根地址或 `/api/v1`,不要填 `/mcp`;不允许 URL 内嵌凭据。CLI 不跟随 HTTP 重定向、不使用环境中的 HTTP 代理。服务器沿用私有网络限制;局域网 HTTP 的链路本身不加密,可使用 ADB 或可信私有组网。

## 任务命令

```bash
mika --device SERIAL task run --goal-file task.txt --json
mika --device SERIAL task status TASK_ID --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
mika --device SERIAL task answer TASK_ID --text-file answer.txt --json
mika --device SERIAL task steer TASK_ID --text "补充一下，只整理今天的消息" --json
mika --device SERIAL task cancel TASK_ID --json
mika --device SERIAL task list --limit 10 --json
mika --device SERIAL task export TASK_OR_RUN_ID --json
```

简单文本也可用 `--goal` / `--text`;文件参数为 `-` 时读取标准输入。`run --conversation CONVERSATION_ID` 创建同一远程对话中的下一项任务,`answer` 只回答当前追问。保留现有对话的 mcp- 前缀以兼容已经发出的 ID;这个历史前缀不代表 CLI 走 MCP 协议。

`run` 返回任务 ID 即结束。`wait` 一次最多等待 50 秒,状态变化或任务结束时返回;如果已经在等回答或手机接管,立即交回。预算用完仍返回当前状态,不判失败、不取消、不重新提交。取消返回的 `accepted` 只表示请求已交给任务服务;必须查询到 cancelled 才能报告已经取消。

`steer` 向 `running` 的远程任务追加要求,保留同一个任务 ID 和上下文;也支持 `--text-file`(文件或 `-` 标准输入)。最多 4000 字符。返回 `accepted: true` 与 `message_id` 只表示收件箱已接收;用 `status` 查询 `steers` 中这条消息的 `state`: `received` 为已收到,`joined` 为已加入当前任务上下文,`not_applied` 为结束或取消前未用上。`joined` 不表示要求已经执行成功。正在执行的动作会先做完,追加要求不会撤销已做的事。

排队、暂停、追问、手机接管和终态任务拒绝追加(409);追问用 `answer`,接管/审批在手机上处理。任务结束竞态也不会把追加要求转成新任务。响应丢失时不要重复发送;查询同一任务,没有收到消息编号且无法确认时照实报告不确定。此入口需要 APK 的 `task_steer` 能力;旧 APK 会提示升级,不回退成新任务。

`export` 按任务编号或运行记录编号拉取这一轮已经落盘的脱敏记录(和手机「查看过程」同一份 `run.json` + `events.jsonl`,再过一遍脱敏)。默认一个 JSON 对象,`exportFormat` 为 1,头里写明 `task_id`、`conversation_id`、run_id;`events` 是事件数组。`partial` 为 true 表示还在进行,内容是到目前为止的事件。`--events` 改成第一行是头、之后一行一个事件。默认不含截图文件,`media` 事件只留编号和步号。未知编号会失败并说明原因;手机上自己发起的运行拉不到。旧 APK 没有 run_export 能力时会提示升级。

`--json` 将成功结果和错误都作为单个 JSON 对象写入 stdout;默认输出缩进 JSON,错误写入 stderr。连接选项和 `--json` 可以写在命令前后。可用 `--request-timeout` 调整 HTTP 超时;等待请求的读取预算至少是任务等待时间加 5 秒。

| 退出码 | 含义 |
| --- | --- |
| 0 | 调用成功;任务仍可能运行、等待用户或尚未完成取消。 |
| 1 | API 明确拒绝,或查询到任务 failed / cancelled。 |
| 2 | 输入、配置、连接或协议不兼容。 |
| 4 | 写入结果不明;可能已被手机接受,没有自动重试。 |
| 130 | 本地读取/等待被中断,没有请求取消手机任务。 |

支持 `task_recovery` 的手机会在执行前保存收件记录。新版 CLI 会在发送前生成小写 UUID，用作任务编号。自动化程序也可以先保存自己的 UUID，通过 `task run --request-id UUID --goal ...` 提交。回执丢失时，退出码 4 的错误里带有 `task_id`。重新连接同一台手机，运行 `mika task status UUID` 即可查询。若暂时返回 404，需要重试时请使用原编号、原目标及原对话参数。相同请求返回原任务，复用编号提交不同内容返回 409。CLI 不会自动重发。

手机进程重启后，可用原编号读取原答复、核验状态、失败原因或取消状态。未正常收尾的任务返回 `failed` 和 `failure_kind=interrupted`。删除对话会清除对应的远程任务内容，旧编号返回 410，也不能再次启动任务。已收尾的任务记录保留 30 天、至多最近 1000 条，过后同一编号按新请求处理。升级前只在内存里的编号无法补回。在旧手机版本上，普通提交仍可用，丢回执后需要核对近期任务。显式指定 `--request-id` 会要求升级手机；此时报错写明手机版本与缺失的能力，去掉该参数即可照常提交。

`doctor` 只检查连接/API 和可用的设备状态,`model_configuration` 明确为 unverified。它不会调用模型或提交任务,也不能证明真实手机操作已经验收。结果缺少 `verified` 时表示核实情况未知。付款确认、手机接管和手机本地任务的访问边界与 MCP 共用同一套规则。

## 任务附件与下载

任务 API 声明 `task_files` 时，CLI 可以随任务上传本地文件，并按任务编号下载该轮实际导出的文件。每批最多 9 件、合计 200 MiB。文件按块传输，完整导入对话工作区后才派发任务。回答已有追问和运行中追加仍使用文本。

```bash
mika task run --goal "核对清单并导出结果" --attach 清单.csv --attach 图片.png --json
mika task status TASK_ID --json
mika task download TASK_ID FILE_ID --output 核对结果.csv
```

`status` 返回的 `files` 含输出文件的编号、名称、字节数和导出时的 SHA-256。CLI 在目标目录暂存并校验数据，校验通过后才创建指定文件。已有同名文件会保留。macOS 和 Linux 上不支持硬链接的卷（例如 exFAT）使用独占创建后复制，若这一步写盘中断，需检查输出文件。可下载范围沿用手机保留的远程任务索引。任务编号的保留期结束后，已导出的文件仍可从手机的下载目录查看。带附件的提交丢失回执时，先按原请求编号查询。任务台重试会沿用原上传编号及顺序。重新运行带附件路径的 CLI 命令会得到新的上传编号。如果原请求已受理，手机会返回 409，可按原请求编号查回结果。

## 浏览器任务台

```bash
mika ui                      # 打开本机浏览器里的任务台
mika ui --device SERIAL --no-open
```

`mika ui` 在 `127.0.0.1` 的随机端口起一个只给本机用的页面，用同一套任务命令派任务、看进度、回答追问、追加要求和取消，界面按浏览器语言显示简体中文或英文。链接末尾 `#` 后面是本次启动的会话密钥，浏览器不会把它发给别处；服务端还核对 Host 和 Origin，只接受固定几种操作。它是给人用的交互界面，不支持 `--json`。详见[从电脑使用 Mika](computer.md#电脑任务台mika-ui)。

## HTTP 契约

以下路径都在 `/api/v1` 下,使用同一 Bearer 认证;POST 正文是 application/json 对象。API 通过结构化错误的 code/message 和 HTTP 状态返回失败,不返回 MCP 信封。未知字段会被拒绝。

| 方法与路径 | 输入 / 返回 |
| --- | --- |
| GET 根路径 | API 版本、服务器版本、能力和输入上限。 |
| GET /device | 只读设备状态;未提供设备接口时返回 503。 |
| POST /uploads | `name` 和字节数 `size`，返回一次性 `upload_id`。 |
| POST /uploads/ID | 按顺序发送 `offset` 和 Base64 的 `data`，每块原始数据最多 128 KiB，确认值为 `next_offset`。 |
| POST /uploads/ID/discard | 空对象，放弃暂存的附件。未使用的编号空闲 30 分钟后失效。 |
| POST /tasks | `goal`、可选的 `conversation`、“request_id” 和上传编号列表 `attachments`。带请求编号时，返回的任务编号与之相同。 |
| GET /tasks/ID/files/FILE_ID?offset=N | 返回本轮输出文件自该字节偏移起的一块数据，响应含 `offset` 与 Base64 的 `data`。 |
| GET /tasks?limit=10 | 最近远程任务;limit 范围 1–50。 |
| GET /tasks/ID | 当前任务状态。 |
| POST /tasks/ID/wait | timeout_s 范围 1–50,默认 30;返回状态与 changed。 |
| POST /tasks/ID/answer | text;返回 task_id 与 accepted。 |
| POST /tasks/ID/steer | text;202 返回 task_id、message_id 与 accepted。只接受 running 任务。 |
| POST /tasks/ID/cancel | 空对象;接受时 202,终态任务 200,返回 accepted。 |

业务校验失败为 400,任务不存在为 404,当前状态不能回答为 409,任务服务拒绝启动为 503。认证、来源和限流策略与 `/mcp` 共用。两种入口使用同一远程任务索引；任务控制接口只认电脑交来的任务。状态中有运行记录时额外返回 `"run_id"`，任务 ID 与运行 ID 各自保留。

支持历史查询的手机在发现响应中报告 `history` 能力及 `"history_enabled"` 字段。机主先在手机「连接电脑 → 高级设置」授权历史查看，以下 GET 接口才可读取本机对话与运行记录。未授权或读取期间撤销授权返回 `403 history_disabled`，缺失的对话、运行或请求返回 404。

| 路径 | 返回 |
| --- | --- |
| /history/conversations | `conversations` 列出 ID、标题和更新时间，`next_offset` 指向下一页。 |
| /history/conversations/C | `turns` 含要求、答复、`"run_id"` 与 `turn_index`，页内按对话顺序排列。`offset` 从最新轮次往前计，`total` 为当前总轮数。 |
| /history/conversations/C/runs/R | 该对话所属运行的 `events` 与 Trace `rows`，并报告归档是否存在及是否仍在录制。 |
| /history/conversations/C/runs/R/bodies/S | 指定 Trace span 的脱敏请求与回复正文，`text` 为一页，`next_offset` 为后续字符位置。 |

保存的轮次只有在实际核验过时才返回 `verified`。旧记录缺少核验信息时省略该字段。由电脑派发的轮次可带 `"source_task_id"`，即使排队时取消、没有运行编号，也能与近期任务对应。

各接口只接受 `offset`、`limit`。默认每页 20 项，最多 50 项；正文默认且最多每页 3,000 个字符。`next_offset` 为 null 表示已到末页。长对话文本和运行事件的展示节选分别带 `text_truncated` 或 `truncated`。历史读取不把手机任务加入远程控制索引，不能用它的编号去回答、追加或取消手机任务。

## 开发验证

```bash
./gradlew :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug
# Python 可单独运行;指定解释器可用 -PmikaPython=/path/to/python3
python3 -m unittest discover -s companions/mika/tests -v
```

纯 JVM 测试覆盖共享服务、API/MCP 互操作、鉴权、状态与原有协议回归。`cliTest`(要 Python 3.10+,不在 `check` 里,需要时显式运行)运行真实 CLI 对 Kotlin HTTP 服务的契约测试(核对 CLI 只请求 `/api/v1`),以及假 ADB、丢响应、令牌变化、端口清理和独立 Skill 目录测试。这些都不需要真机或模型 Key。真机无线断连、手机重启和真实 Agent 执行需要另行验收,不能用这些隔离测试代替。
