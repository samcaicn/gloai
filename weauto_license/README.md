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

- **客户端（EXE）** 只跟**你自己的 Worker 域名**对话，请求 `/activate`、`/validate`、`/deactivate`。
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
4. **Developers > Webhooks** 填 `https://weauto.safeopc.cn/webhook`，记下 Webhook Secret。
5. 本地测试用 `creem_test_` key + 测试卡 `4242 4242 4242 4242`。

## 部署 Worker

```bash
npm i -g wrangler
wrangler login            # 用 safeopc.cn 所在的 Cloudflare 账号登录
wrangler secret put CREEM_API_KEY         # 粘贴第 3 步的 key
wrangler secret put CREEM_WEBHOOK_SECRET   # 粘贴第 4 步的 secret
wrangler deploy           # 自动把 weauto.safeopc.cn 绑到本 Worker 并建 DNS 记录
```

`wrangler.toml` 已配好：

```toml
name = "weauto-license"
main = "weauto_license/cf_worker.js"
compatibility_date = "2024-09-23"
[vars]
CREEM_MODE = "test"                 # 上线改 "prod"
CREEM_PRODUCT_ID = "prod_xxx"
CREEM_CHECKOUT_URL = "https://www.creem.io/checkout/xxxx"
[[routes]]
custom_domain = "weauto.safeopc.cn" # safeopc.cn 的 NS 已在 Cloudflare，直接绑子域
```

> ⚠️ **大陆访问约束**：Cloudflare 的 `*.workers.dev` 默认域名在中国大陆被墙，买家打不开。
> 必须绑**你自己的自定义域名**。`safeopc.cn` 的 NS 实测已在 Cloudflare
> （`mira.ns.cloudflare.com` / `jim.ns.cloudflare.com`），zone 由 CF 托管，
> 故 `wrangler deploy` 会直接用 `[[routes]] custom_domain` 把 `weauto.safeopc.cn`
> 绑到本 Worker，并自动建好对应 DNS 记录——**无需 Cloudflare for SaaS，也无需迁 NS**。
> `config.py` 里的 `CREEM_WORKER_URL` 已填 `https://weauto.safeopc.cn`。

## 配置 config.py

```python
LICENSE_GUARD_ENABLED = True    # 正式发布设 True；开发期 False
CREEM_WORKER_URL = "https://weauto.safeopc.cn"   # 已绑定的自定义域名，勿用 .workers.dev
CREEM_LICENSE_KEY = ""          # 用户购买后填入自己的卡密
```

环境变量可覆盖（`WEAUTO_LICENSE_GUARD_ENABLED` / `WEAUTO_CREEM_WORKER_URL` / `WEAUTO_CREEM_LICENSE_KEY`），便于 dry-run。

## 给最终用户（买家）的购买指引

把下面这段原样放进你随 EXE 附带的使用说明（README / 说明.txt / 群公告）。买家全程只需一次点击：

1. 运行 WeAuto，若提示「WeAuto 未激活」——屏幕会打印一行 **购买链接**（形如 `https://weauto.safeopc.cn/buy`）。
2. 浏览器打开该链接 → 跳转 Creem 收银台 → **用支付宝完成付款**（普通用户无需信用卡）。
3. 付款成功后 Creem 会显示 **License Key（卡密）**，复制它。
4. 打开 `config.py`，把卡密填进 `CREEM_LICENSE_KEY = "这里"`，保存。
5. 重启 WeAuto，自动激活，功能解锁。

> 换机 / 退订前执行 `python cli.py license deactivate` 释放本机激活额度，否则新机器会因超设备数被拦。
> 激活一般 1–2 台设备，具体看你 Creem 产品设置的 Activation limit。

**你（开发者）对外只暴露一个链接**：`https://weauto.safeopc.cn/buy`。
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
