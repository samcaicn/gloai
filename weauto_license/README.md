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
4. **Developers > Webhooks** 填 `https://<你的worker>/webhook`，记下 Webhook Secret。
5. 本地测试用 `creem_test_` key + 测试卡 `4242 4242 4242 4242`。

## 部署 Worker

```bash
npm i -g wrangler
wrangler login
wrangler secret put CREEM_API_KEY        # 粘贴第 3 步的 key
wrangler secret put CREEM_WEBHOOK_SECRET  # 粘贴第 4 步的 secret
# wrangler.toml:
#   name = "weauto-license"
#   main = "weauto_license/cf_worker.js"
#   compatibility_date = "2024-09-23"
#   [vars]
#   CREEM_MODE = "test"            # 上线改 "prod"
#   CREEM_PRODUCT_ID = "prod_xxx"
wrangler deploy
```

## 配置 config.py

```python
LICENSE_GUARD_ENABLED = True    # 正式发布设 True；开发期 False
CREEM_WORKER_URL = "https://license.xxx.workers.dev"
CREEM_LICENSE_KEY = ""          # 用户购买后填入自己的卡密
```

环境变量可覆盖（`WEAUTO_LICENSE_GUARD_ENABLED` / `WEAUTO_CREEM_WORKER_URL` / `WEAUTO_CREEM_LICENSE_KEY`），便于 dry-run。

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
