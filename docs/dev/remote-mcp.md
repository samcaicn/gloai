# 自托管远程 CLI / MCP

首版范围：单用户、单设备、单网关进程。网关提供 HTTPS CLI / MCP 转发，手机主动连接 WSS；不需要手机公网入站端口或额外 VPN。部署代码与英文说明见 [companions/relay/README.md](../../companions/relay/README.md)。

## 部署与配对

准备指向服务器的域名、Docker Compose、TCP 80/443 入站。不要把网关 HTTP 8787 端口直接发布到公网。

```sh
cd companions/relay
cp .env.example .env
# 编辑 .env，设置 MIKA_DOMAIN，仅填写自己的域名，不带协议或路径
docker compose build
docker build --target caddy-check .
docker compose run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose up -d
docker compose cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

在本机私下查看 owner-key 文件。它是管理密钥，不是 MCP 令牌，不填进客户端配置。初始化不会覆盖旧密钥。Caddy 自动申请证书并代理 WebSocket；仅 Caddy 发布主机端口。域名和端口必须可达，保留数据与证书卷。

浏览器打开网关 HTTPS 地址，输入管理密钥，点 Create pairing code。配对码五分钟有效、仅用一次。手机「设置 → 连接电脑 → 远程」填写地址和配对码；配对后仍需显式打开「允许远程访问」。换手机前须撤销旧配对。

### 服务器已有 Nginx

80/443 已被现有 Nginx 使用时，改用独立的 [compose.nginx.yaml](../../companions/relay/compose.nginx.yaml)，不要与默认 Compose 文件合并。配置同一份 .env，以固定项目名执行：

```sh
docker compose -p mika-relay -f compose.nginx.yaml build
docker compose -p mika-relay -f compose.nginx.yaml run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose -p mika-relay -f compose.nginx.yaml up -d
docker compose -p mika-relay -f compose.nginx.yaml cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

网关仅发布到服务器 127.0.0.1:18787；Nginx 须在同一台主机。将 [nginx.conf.example](../../companions/relay/nginx.conf.example) 中的域名和证书路径改为实际值，作为独立虚拟主机配置加入现有 http 配置。证书须覆盖该域名，并已有续期及续期后平滑加载安排。先检查域名、map 名称和 18787 端口没有冲突，再 nginx -t，通过后平滑 reload；失败时只撤回新配置。示例保留 WebSocket 升级，关闭缓冲、代理重试和转发请求的访问日志。

后续所有 Compose 命令沿用相同项目名和 -f 参数。回滚时禁用新虚拟主机，校验并平滑加载 Nginx，再 docker compose -p mika-relay -f compose.nginx.yaml down；保留数据卷。不要改为公网绑定后端端口，也不要在此部署中启动默认 Compose 文件的 Caddy。

## 客户端与统一令牌

手机现有 `McpSettings` 访问令牌同时用于局域网、远程 CLI 和 MCP。网关不生成、校验、摘要保存或同步该令牌，只透明转发 `Authorization`，每个请求由手机 `RemoteAccess` 校验。局域网开关关闭时，已显式开启的远程连接仍可使用此令牌。USB 临时令牌继续仅用于回环入口，不能通过远程访问。

- MCP：手机选「MCP → 远程」，复制配置到支持自定义 Authorization 请求头的客户端，地址为 `https://你的域名/mcp`。
- CLI：手机选「CLI / Skill → 远程」，复制访问令牌到电脑私密文件，运行页面上的连接命令。命令不携带令牌原文。

```sh
chmod 600 /path/to/token
mika connect --url https://mika.example.com --token-file /path/to/token
mika --url https://mika.example.com --token-file /path/to/token doctor
mika --url https://mika.example.com --token-file /path/to/token task list --limit 5
```

每次远程调用都须传入 `--url` 和 `--token-file`；`mika connect` 只验证连接，不保存远程配置。两种协议都访问手机同一份任务服务，任务创建、查询、等待、回答、插话和取消行为与局域网一致。

### 经网关唤醒和解锁：默认关

远程唤醒和解锁让持有令牌的人在手机上输入锁屏 PIN/密码。局域网里能连上手机的只有同一网络的设备；经网关则是整个互联网，且网关本身能看到令牌。所以经网关来的请求默认**看不到**屏幕能力（`mika_device_state`、`mika_wake_device`、`mika_unlock_device` 与 `/api/v1/device`，`/api/v1` 的 capabilities 也不列 `device_state`），工具和端点根本不出现，而不是出现了再拒绝。要经网关唤醒、解锁，三项都得在手机上打开：「允许远程访问」、远程页的「允许经网关唤醒和解锁」（默认关，存在加密的网关配置里，配对或重新配对都回到关）、以及原有的「允许远程亮屏与解锁」。局域网和 USB 的行为不变。

在手机更换访问令牌后，局域网和远程的旧令牌立即失效，无须同步网关；客户端须更新配置。解除配对仅撤销设备通道，不更换访问令牌；重新配对后持有该令牌的客户端仍能访问。若需使这些客户端失效，应在手机更换令牌。

网关不提供 OAuth 授权发现、注册、授权或刷新。仅接受 OAuth 流程且不能配置 Bearer 请求头的客户端不在当前支持范围。浏览器 Origin 请求仍由手机拒绝；现有 MCP 使用无状态 JSON，不增加 SSE。

## 架构和凭据职责

| 凭据 | 用途 | 保留位置 |
| --- | --- | --- |
| 手机访问令牌 | 统一的 CLI / MCP 请求鉴权 | 手机加密存储、用户配置的客户端 |
| 设备通道凭据 | 配对手机建立 WSS、解除配对 | 手机加密存储、网关摘要 |
| 管理密钥 | 创建配对码、管理解除配对 | 网关私密文件、进程内摘要 |

`RemoteAccess.handle` 统一鉴权和协议错误，再进入 `RemoteEndpoints`；本地监听与远程通道共用任务服务。LAN 私有地址检查保留。远程请求使用非回环来源，不获得 USB 权限。失败冷却按调用方分开记：网关把它看到的调用方地址（IPv4 地址或 IPv6 的 /64 前缀）随请求带给手机，手机按「网关 + 调用方」计数，与本地监听的地址分开。一个调用方连续猜错只冷却它自己，冷却期间连正确令牌也不放行，不会把「猜对了」透露给它。网关在反向代理后面时所有连接都来自代理，须加 `-trust-proxy`，让网关从代理追加的最后一个 X-Forwarded-For 取调用方（只在直接对端是回环或私有地址时读取）；两份 Compose 文件已加上，Nginx 示例用 `$remote_addr` 覆盖该头。网关自己的管理密钥、配对和转发限流也按同一调用方计数。`McpRuntime` 为远程通道提供手机统一令牌，远程关闭时拒绝访问；配对本身不启用远程。

`RelayGateway` 校验 HTTPS 地址和配对响应，不跟随重定向。`RelayClient` 白名单放行 `/mcp`、`/api/v1` 和 `/api/v1/…` 的 GET/POST/DELETE，独立保留 query 及 Authorization、Origin、X-Mika-Client 和协议头；各端点方法、参数和任务权限由手机继续检查。网关不代理任意 URL 或 TCP。

`RelaySettings` 使用独立 Keystore 密钥加密、原子保存设备配置，状态流仅含非秘密字段。`RelayNetwork` 感知网络切换，`McpService` 沿用前台服务和通知关闭动作。App 引导使用现有组件、中英繁体资源，切换引导不改变权限，复制时才读取令牌。

网关 Web UI 使用 Go 模板和嵌入的本地 CSS/JS，无框架、Node 构建或 CDN。配色、字号、间距、分组卡片和按钮尺寸映射 App 语义值，适配浅色/深色和窄屏。英语、简体中文、繁体中文三份词典覆盖界面、错误、表单校验、确认框；默认跟随浏览器，可单独选择，cookie 只保存语言。管理密钥不回填，配对管理不使用浏览器凭据存储，无 JS 时也可提交和确认。

## 断线和撤销

关闭远程立即关闭通道并保留配对；解除配对先关闭，再请求网关删除设备身份，断网时保留关闭状态以便重试。Web UI 的「解除手机配对」须确认后执行。

连接退避重建，操作不重放；每条请求有独立编号，同连接重复编号不执行。同时最多八个请求，正文上限 1 MiB，query 和头部各上限 8 KiB，超时 65 秒，覆盖手机最多 50 秒的状态等待。手机结果过大时返回错误，不因此撤销通道。

`device_offline` 表示无可用通道，`delivery_unknown` 表示可能已经执行但结果丢失；应查询任务状态，不自动重发任务、插话或解锁。CLI 对写请求的 HTTP 5xx 保守报告 `outcome_unknown`。取消等待不能撤销已执行动作。离线不积压命令。

## 验证与运维

```sh
cd companions/relay
go test -race ./...
go vet ./...
cd ../..
./gradlew :core:test :app:testDebugUnitTest :core:cliTest :core:relayTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall
```

跨语言测试启动真实 Go 网关，经生产 Kotlin 隧道和手机鉴权调用真实 Python CLI 与 MCP，验证共享任务、统一令牌轮换、USB 令牌拒绝和解除配对。普通 JVM 测试不要求 Go。界面测试为 `RelaySectionTest` 和 `ComputerAccessScreenTest`；开发包通过 `devInstall` 与正式版隔离。

`/healthz` 仅证明网关进程可用，不代表手机在线。代理须保留 Host、WebSocket 和协议头，用调用方地址覆盖 X-Forwarded-For，超时大于 65 秒，禁用请求自动重试。默认不记录正文或凭据，反向代理也须如此。备份包含设备身份，恢复旧备份可能恢复被撤销设备；管理密钥轮换需替换私密文件后重启，不改变手机令牌。

HTTPS 在网关/代理终止，网关技术上能读取请求头和任务内容，须使用可信网关；该方案为手机鉴权的透明转发，不是客户端到手机的端到端加密。PIN/密码仍只在手机处理。Android 后台限制、强行停止、断网和重启会影响可用性，须单独验证证书、待机、网络切换及首次解锁限制。
