# WeAuto License（Creem 卡密）接入

## 架构（铁律：bot.py 接通 Creem，不受 GPL-3.0 约束，按项目方指令执行）

```
 微信机器人 / CLI / MCP (EXE)
   │  仅持有 [用户 license key] + [本机机器指纹]
   │  绝不接触 api.creem.io、绝不持有 Creem 密钥
   ▼
 你的 Cloudflare Worker  (weauto_license/cf_worker.js)
   │  持有 CREEM_API_KEY / CREEM_WEBHOOK_SECRET，代理 Creem
   ▼
 Creem API  (MoR：买家付款给 Creem，与微信本体无关联)
```

- **客户端（EXE）** 只跟 **Worker 域名**对话，请求 `/activate`、`/validate`、`/deactivate`。
- **Worker（CF）** 是唯一持有 Creem 密钥的服务端，代理 `api.creem.io` 的 license 调用，并接收 Creem webhook。
- 顺带把「微信本体」与「支付」隔离：腾讯看不到你的 Creem 交易。

## 文件清单

| 文件 | 作用 | 部署位置 |
|------|------|----------|
| `weauto_license/guard.py` | 客户端门禁：机器指纹、激活/校验、离线宽限 | 随 EXE 打包 |
| `weauto_license/cf_worker.js` | CF Worker：代理 Creem + webhook 验签 | Cloudflare Workers |
| `config.py` | `LICENSE_GUARD_ENABLED` / `CREEM_WORKER_URL` / `CREEM_LICENSE_KEY` | 项目配置 |
| `bot.py` / `cli.py` / `weauto_mcp.py` | 入口处调用 `ensure_license()` | 随 EXE 打包 |

## Creem 后台配置（一次性）

1. 注册 [Creem](https://www.creem.io)，进 Dashboard。
2. **Products** 新建产品，类型选 **License key**，设置：
   - Activation limit（每台设备数，如 1–2）
   - 有效期（如永久 / 1 年）
   - 价格（支持普通用户**支付宝**付款，Creem 已上线）
   - 记下 `Product ID`（`prod_xxx`）
3. **Settings > API Keys** 生成 API key（`creem_test_...` / `creem_live_...`）。
4. **Developers > Webhooks** 填 `https://weauto-license.tuptup-workbuddy.workers.dev/webhook`（CF 原始链接，已部署并经 CF API 确认 enabled），记下 Webhook Secret。
   - 为什么 Webhook 用 workers.dev 而购买页用自定义域名：Webhook 是 **Creem 海外服务器 → CF** 的服务器间回调，不受大陆 workers.dev 被墙影响；而 `/buy`、`/ai/v1` 是大陆买家/bot 访问，必须走 `weauto.safeopc.cn`。
5. 本地测试用 `creem_test_` key + 测试卡 `4242 4242 4242 4242`。

## 支付宝：两个必须分清的概念（最容易踩坑）

很多人把这两件事混为一谈，结果配置了半天发现没生效。它们是**完全独立的两条链路**：

| | 是什么 | 谁在用 | 状态 | 在哪配 |
|---|---|---|---|---|
| **① 买家付款方式** | 买家结账时选择「支付宝」付钱 | 你的买家 | Creem 2.0 已新增 AliPay 作为收银台支付方式 | Creem 自动提供，**你无需配置**；买家在收银台自己选 |
| **② 商家收款（Payout）** | Creem 把钱打进**你的**支付宝 | 你自己 | 中国商户**已支持**，单笔上限 5 万 CNY | Creem → Balance → Payout Account → 添加支付宝 |

- **① 不用你做任何开发**：收银台会自动列出可用方式，买家选支付宝即可。
  > 注：Creem 文档 FAQ 某页仍写「Alipay support coming soon」，属旧文案；以 Creem 2.0 发布说明（已上线 AliPay）为准。
- **② 必须你自己去后台绑定**，否则你收不到钱：
  1. Creem 后台 → **Balance → Payout Account** → Add。
  2. 国家选 **China**，币种 **CNY**，方式选 **支付宝**。
  3. 按「支付宝闪速收款」给的账号 + 账户持有人名称填写（姓名用**英文大写：姓 空格 名**）。
  4. 提交后等 Creem 审核（通常 1–2 天），审核通过才能开启真实收款。
- 提现节奏：每月 1 号 / 15 号两个窗口，余额满 **$50** 才能申请提现。

## 部署 Worker（使用 CF 默认域名，不绑自定义域名）

```bash
npm i -g wrangler
wrangler login            # 用你的 Cloudflare 账号登录
wrangler secret put CREEM_API_KEY         # 粘贴第 3 步的 key
wrangler secret put CREEM_WEBHOOK_SECRET   # 粘贴第 4 步的 secret
wrangler deploy           # 终端会输出实际地址，形如 https://weauto-license.<子域>.workers.dev
```

`wrangler.toml` 已配好（**无 `[[routes]]`，即用 CF 默认 `*.workers.dev` 域名**）：

```toml
name = "weauto-license"
main = "weauto_license/cf_worker.js"
compatibility_date = "2024-09-23"
[vars]
CREEM_MODE = "test"                 # 上线改 "prod"
CREEM_PRODUCT_ID = "prod_xxx"
CREEM_CHECKOUT_URL = "https://www.creem.io/checkout/xxxx"
PRODUCT_NAME  = "WeAuto 专业版"
PRODUCT_PRICE = "¥99"
PRODUCT_DESC  = "一次购买，解锁全部功能"
SUPPORT_EMAIL = ""
```

### 部署后自检（必做）

```bash
# 1) 看配置是否齐全（不泄露密钥）
curl https://weauto-license.<子域>.workers.dev/health
# 期望：{"ok":true,...,"has_api_key":true,"checkout_ready":true}

# 2) 看卡密网站是否出来
curl https://weauto-license.<子域>.workers.dev/buy | head
# 期望：一段含「支付宝付款」的中文 HTML

# 3) 一键购买直跳（EXE 的购买按钮实际走的链接）
curl -I "https://weauto-license.<子域>.workers.dev/buy?go=1"
# 期望：302 → Location 指向 creem.io
```

`/health` 返回 `checkout_ready:false` 说明 `CREEM_CHECKOUT_URL`/`CREEM_PRODUCT_ID` 没填，
`/buy` 会显示「暂未开放购买」页（不再是丑陋的 500）。

> ⚠️ **大陆访问约束**：Cloudflare 的 `*.workers.dev` 默认域名在中国大陆**可能被墙**，买家可能打不开。
> 本项目当前按指令**使用 CF 默认域名、不绑自定义域名**。若日后需大陆直连，再绑自定义域名
> （例如 `weauto.safeopc.cn`，其 NS 已在 Cloudflare，可直接 `[[routes]] custom_domain` 绑）。
> `config.py` 的 `CREEM_WORKER_URL` 必须填 `wrangler deploy` 后终端显示的
> `https://weauto-license.<子域>.workers.dev`，不能用占位。

## 配置 config.py

```python
LICENSE_GUARD_ENABLED = True    # 正式发布设 True；开发期 False
CREEM_WORKER_URL = "https://weauto-license.<你的cf子域>.workers.dev"   # wrangler deploy 后终端显示的地址
CREEM_LICENSE_KEY = ""          # 用户购买后填入自己的卡密
```

环境变量可覆盖（`WEAUTO_LICENSE_GUARD_ENABLED` / `WEAUTO_CREEM_WORKER_URL` / `WEAUTO_CREEM_LICENSE_KEY`），便于 dry-run。

## 给最终用户（买家）的购买指引

把下面这段原样放进你随 EXE 附带的使用说明（README / 说明.txt / 群公告）。买家全程只需一次点击：

1. 运行 WeAuto，若提示「WeAuto 未激活」——会打印一行 **购买链接**（形如 `https://weauto-license.<你的cf子域>.workers.dev/buy`）。
2. 浏览器打开该链接 → 进入**购买页** → 点「**支付宝付款 · 立即购买**」→ 在 Creem 收银台用**支付宝**完成付款（无需信用卡）。
3. 付款成功后 Creem 会显示 **License Key（卡密）**（也会发到付款邮箱），复制它。
4. 打开 WeAuto 网页后台 →「**授权管理**」→ 粘贴卡密 → 点「**激活 / 保存**」。
5. 点「**重启机器人使授权生效**」，即可正常使用。
   （旧的手动方式仍可用：直接改 `config.py` 的 `CREEM_LICENSE_KEY` 再重启，但推荐用网页后台。）

> 换机 / 退订前执行 `python cli.py license deactivate` 释放本机激活额度，否则新机器会因超设备数被拦。
> 激活一般 1–2 台设备，具体看你 Creem 产品设置的 Activation limit。
> 若买家在中国大陆打不开 `.workers.dev`，需自备代理；或等日后绑自定义域名走大陆直连。

**你（开发者）对外只暴露一个链接**：`https://weauto-license.<你的cf子域>.workers.dev/buy`
（`<你的cf子域>` 为 `wrangler deploy` 后终端显示的实际子域）。
客户端未激活时也会自动打印它，所以买家无需你手动发——运行即见。
但建议仍随包附一份上述说明，降低答疑成本。

## 客户端运行流程

1. 程序启动 → `ensure_license()`。
2. 无缓存 / 首次 → 调 Worker `/activate`（key + 机器指纹）→ 缓存 `instance_id` + 过期时间。
3. 之后启动 → 调 Worker `/validate` 刷新缓存。
4. **离线宽限**：联网失败时若缓存未超 7 天（`WEAUTO_LICENSE_GRACE`）仍放行，避免断网即停用。
5. 校验失败（key 无效 / 超设备数 / 过期）→ 打印购买提示并退出。

手动激活（CLI）：
```bash
python cli.py license activate <你的卡密>
python cli.py license deactivate   # 换机前释放本机激活
```

## 安全纪律

- Creem API key / webhook secret **只在 CF Worker（Secrets）**，绝不进客户端、不进 git。
- 客户端仅传 `HMAC/机器指纹` 与用户卡密，服务端拿不到原始硬件 PII。
- Webhook 必须验签（`creem-signature` HMAC-SHA256），否则会被伪造回调。

## LLM 统一走 Worker AI 代理（反破解核心，2026-09-25）

客户端所有 LLM 调用不再直连第三方，全部经自有 Worker 中转：

```
bot.py (base_url=<worker>/ai/v1, api_key=卡密, X-WeAuto-Instance=实例ID)
   └─> CF Worker /ai/v1/*  ──验证卡密(Creem /licenses/validate, isolate 缓存10min)──┐
           │ 有效：透传请求 -> AI_UPSTREAM_URL（注入真实 key = Secret AI_UPSTREAM_KEY）│
           │ 无效：401 license_required（fail-close）<──────────────────────────────┘
```

- **反编译跳过支付为何失效**：upstream 真实 key 只存在 Worker Secret 里；破解者 patch
  掉本地门禁后，Worker 仍会因无有效卡密拒绝 AI 请求 → bot 无 AI 可用 = 废物。
- 配置（一次性）：`wrangler secret put AI_UPSTREAM_KEY`（vg.v1api.cc 的 key）；
  `AI_UPSTREAM_URL` 已在 wrangler.toml `[vars]`（需带 /v1 后缀）。
- **表情 API（MOONSHOT 图像识别）按项目方要求保留直连**，不走 Worker、不受门禁影响。
- 开发态（无 `weauto_license/_release.py`）门禁可关、LLM 走本地 config；
  CI 发行版注入 `_release.py` → 门禁强制开启 + LLM 强制走 Worker。
- 其余加固：激活缓存 HMAC 签名（改 JSON/换机即失效）、运行期每 6h 复检
  （失效 `os._exit`）、发行版 WebUI 门禁开关锁定。

## 多档套餐（2026-09-25 改版）

3 档套餐，全部用 `creem_5fHTWBhssHvCVQ3lsbPzod`（prod key）建好：

| 档位 | 计费 | 人民币 | Creem 产品（USD） |
|---|---|---|---|
| normal 普通版（月租） | 订阅 recurring/every-month | ¥29.9/月 | `prod_3PRueiU3wkoI0MiOfH7gJI` ($4.45) |
| premium 高级版（月租） | 订阅 recurring/every-month | ¥39.9/月 | `prod_1FGMYGoMeSg1PFiT91mZ6N` ($5.94) |
| lifetime 永久授权 | 一次性 onetime | ¥199（自愿支持） | `prod_7by0YHsTNBleF1uOE2qAao` ($29.65) |

- **wrangler.toml 用 `CREEM_PRODUCTS`（TOML 单引号字面量包裹的 JSON 数组）** 描述各档，
  Worker 解析后 /buy 渲染三张卡片、/buy?go=1&tier=<档> 跳对应收银台。
  ⚠️ 不能直接写「行内数组 of 内联表」（wrangler TOML 解析器报 "extra tokens after string part"），
  必须单引号字面量。
- 自动建品：`python weauto_license/create_creem_product.py --mode prod --apply`
  （含汇率自动换算 USD、Idempotency-Key 幂等防重复；`--dry-run` 只算钱）。
- `/validate` 会尽力从 Creem 响应解析 product → 返回 `tier`，客户端缓存并在 WebUI 展示。

### 两个 Creem 限制（实测，非代码 bug）
1. **月租订阅可能无支付宝入口**：Creem 的 Alipay 已知只在一次性（onetime）收银台出现，
   订阅档大概率只有信用卡等。若你要月租也走支付宝，需改成「一次性可续费」方案（告知即可改）。
2. **「自愿付款」只能后台开**：`pay_what_you_want` 经 API 创建/PATCH 均被 Creem 忽略
   （返回 null），只能在 Creem 后台产品页手动开启；当前 lifetime 已设为固定最低 ¥199 一次性（带支付宝）。
   后台开启后 price 即最低价、买家可付更多。

