# 用账号登录

「添加服务商」最上面是「用账号登录 · 不用填 Key」一组：ChatGPT、xAI / Grok、OpenRouter。点一家先弹出说明面板（会发生什么、要注意什么），点了才开始登录；登录成功后打开这一家的详情：账号、状态、可用的模型（可刷新）、设为主模型、退出登录（只清本机）。

「用 ChatGPT 登录」保持原样（`core/model/chatgpt/`，授权码 + PKCE，本机回调）。这里是另外几家账号登录，以及明确不做的原因。本机回调（`core/model/oauth/LoopbackCallback.kt`）和 PKCE（`core/model/oauth/Pkce.kt`）是 ChatGPT 和 OpenRouter 共用的。

登录、加密保存、刷新、调用各家自己的接口，四步分开。访问令牌、刷新令牌、设备码不进日志、不进诊断信息、不进界面上除用户码以外的文字。会话 JSON 和 API Key 放在同一处（`provider-keys.json`），用同一套加密。

## xAI / Grok（设备码）

- 读 `https://auth.x.ai/.well-known/openid-configuration`。签发方必须是 `https://auth.x.ai`，设备码和令牌地址必须是 https 且主机是 `auth.x.ai`。对不上就停止，请求不发到别的主机。
- 设备码（RFC 8628）的 scope 是 `openid profile email offline_access grok-cli:access api:access`。用户在浏览器里打开验证页、核对短码，App 轮询换令牌。验证页的主机不在允许名单（`auth.x.ai`、`accounts.x.ai`）里就改开固定的官方页。
- 客户端是 xAI 官方命令行工具的公开登录入口（公开 client_id `b1a00492-073a-47ea-816f-4c329264a828`）。这不是 xAI 给 Mika 注册的应用，xAI 可能限制这种用法，账号有被限制的风险。界面在登录前写明，不点「继续登录」就不发生。
- 调用走 xAI 自己的 Responses：`https://api.x.ai/v1/responses`（`ResponsesModel`），不转成别的协议。
- 访问令牌快过期时用刷新令牌换新的，同一时刻只有一个在换。刷新被拒且不能再用时清掉本机令牌，报「登录已失效」。
- 登录成功但调用回 HTTP 403：说明这个订阅档位不能用这个接口（`ModelError.AccountPlanNotAllowed`），不重试；详情页和任务结局里都提示改用 xAI 的 API Key。
- 退出只清这台手机上的令牌。xAI 账号里的授权要到 xAI 的账号设置里自己断开（没有可用的公开注销）。

## OpenRouter（官方 OAuth PKCE）

OpenRouter 官方给第三方应用的登录方式（文档：https://openrouter.ai/docs/use-cases/oauth-pkce），换到的是一把普通的 OpenRouter API Key。

- 本机回调：`LoopbackCallback` 在 `127.0.0.1` 上开系统挑的空闲端口，回调地址写成 `http://localhost:<端口>/auth/callback`（OpenRouter 写明本机回调任意端口都可以）。
- 用系统浏览器打开 `https://openrouter.ai/auth?callback_url=…&code_challenge=…&code_challenge_method=S256&key_label=Mika&state=…`。校验串每次新生成，只在本机。
- 回调带回 `code` 和 `state`：state 对不上的回 400、接着等；没带 code 也没带 state 的（用户在授权页拒绝）当作没同意。
- `POST https://openrouter.ai/api/v1/auth/keys`，JSON `{code, code_verifier, code_challenge_method: "S256"}`，返回 `key`。403 是 code 或校验串不对、或 code 过期（10 分钟），400 是校验方式对不上。出错只留状态码，不留正文。换完才把结果页回给浏览器。
- 存法：按 OpenRouter 预设存成一家 OpenAI 兼容服务商（`https://openrouter.ai/api/v1`），Key 和别的 API Key 一样经 `KeyCipher` 加密存在 `provider-keys.json`；服务商上记 `signIn = "openrouter"`（`providers.json` 的 `services[].signIn`），详情里显示成账号登录的样子。授权来的和用户自己填 Key 的 OpenRouter 各是一家（列表里授权来的写作「OpenRouter（账号登录）」），授权不会顶掉用户自己填的 Key，再登录只更新授权来的那一家。列模型、调用都和填 Key 的一样。
- 退出登录只删本机的 Key，服务商、模型和用途留着；界面提示到 OpenRouter 的 Keys 页面（`https://openrouter.ai/settings/keys`）删掉名为 Mika 的那把。

## 不做

### Claude 订阅

Anthropic 写明 OAuth 只给它自己的应用；第三方产品要用 Console 的 API Key 或云厂商凭证，不允许第三方 App 提供 Claude.ai 登录，也不允许把订阅额度接到自己的请求上。Mika 继续用 Anthropic 预设填 API Key。

### MiniMax 账号

MiniMax 的官方文档没有给第三方 App 的 OAuth：没有让第三方自己登记客户端的入口，也没有公开给第三方用的 client_id。能找到的账号登录做法都要借用别家登记的客户端，Mika 不这样做。MiniMax 文档里给第三方工具的正当做法是 Token Plan（Coding Plan）的 API Key，在 Mika 里用 MiniMax 预设填 Key 即可。

### Google 账号（Gemini）

Google 不允许第三方软件借用它自家工具的 OAuth 客户端去访问后端。第三方要用 AI Studio 或 Vertex AI 的 API Key。Mika 继续用 Gemini 预设填 API Key。
