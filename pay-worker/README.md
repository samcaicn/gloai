# mu-pay · 智创协同 AI 桌面端支付后端

Cloudflare Worker，**唯一持有微信支付商户密钥的一层**。桌面客户端只跟本 Worker 通信，
配置里只存 Worker 域名，不持有任何商户凭证。积分余额以本 Worker 的 KV 为准。

**收款形态：微信支付服务商模式 Native 扫码**（`WXPAY_SP_MODE=partner`，默认）。
服务商（`sp_mchid`）代子商户/特约商户（`sub_mchid`）下单，钱进子商户账户；
签名与平台证书一律用服务商商户的凭证。改 `merchant` 可退回直连商户模式。

## 架构

```
┌─────────────────────┐         ┌──────────────────────┐         ┌──────────────────┐
│  桌面客户端(Electron)│ ──────▶ │  本 Worker (CF)       │ ──────▶ │ 微信支付 APIv3    │
│  · 只存 Worker 域名   │        │  · 唯一持商户密钥      │        │ 服务商 Native 下单 │
│  · mid 本机持久化     │ ◀───── │  · KV: 单/余额/限流   │ ◀───── │ /v3/pay/partner/* │
└─────────────────────┘ 轮询结果  └──────────────────────┘ 验签入账  └──────────────────┘
                                          │
                                          └─ 回调丢失时：查单 / 关单 兜底
```

支付闭环：客户端生成高熵 `mid`（本机持久化）→ 下单时带入 → 微信回调按 `attach.mid`
把积分写进 KV `credits:<mid>` → 客户端轮询 `status` 拿到结果 → 积分即时到账。
全程无需用户复制粘贴任何凭证。

## 路由

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 探活：`mode`（mock/wechat）、`wxpay.access`（partner/merchant）、`configured`、`missing`（缺哪些 secret） |
| GET | `/api/pay/tiers` | 三档套餐（积分按 `CREDITS_PER_YUAN` 实时算） |
| POST | `/api/pay/create` | body `{tier, mid}` → `{orderId, codeUrl, expiresAt, mock}` |
| GET | `/api/pay/status?orderId=` | 轮询：`created` → `paid` / `expired`（wechat 模式会顺带向微信查单兜底） |
| GET | `/api/pay/credits?mid=` | 积分余额 |
| POST | `/api/pay/notify` | 微信支付回调（平台证书验签 + AES-GCM 解密 + 幂等入账） |
| POST | `/api/pay/mock/confirm` | 仅 `PAY_MODE=mock`：模拟支付成功 |

## 套餐档位（唯一档位定义源：`src/lib/tiers.ts`）

| 档位 | 价格 | 到账积分（默认 50 积分/元） |
|---|---|---|
| 基础积分包 basic | ¥19.9 | 995 |
| 进阶积分包 plus（推荐） | ¥59.9 | 2995 |
| 至尊积分包 pro | ¥99.9 | 4995 |

**兑换比例未定**：上线前只改 `wrangler.toml` 的 `CREDITS_PER_YUAN` 一个值，
全部档位、全部客户端同步生效（已支付订单按下单时快照结算，不受影响）。

## 部署

```bash
# 1. 建 KV 并把 id 填进 wrangler.toml
wrangler kv namespace create PAY_KV

# 2. 本地开发（mock 模式，零配置可跑）
cp .dev.vars.example .dev.vars   # 按需改
bun install                      # 或 pnpm install
bun run dev                      # http://127.0.0.1:8787

# 3. 部署
wrangler deploy

# 4. 回滚域名：把 WXPAY_NOTIFY_URL 改成 https://<worker域名>/api/pay/notify 后重新 deploy
```

## 切真实收款（微信支付**服务商模式** Native）

1. `wrangler.toml`：`PAY_MODE = "wechat"`、`WXPAY_SP_MODE = "partner"`（默认已是），
   `WXPAY_NOTIFY_URL` 改为部署后的公网地址。
2. 服务商模式六项 secret（**只进 secret，不落库**）：
   ```bash
   wrangler secret put WXPAY_SP_MCHID      # 服务商商户号（签名 mchid，也是收款发起方）
   wrangler secret put WXPAY_SP_APPID      # 服务商 AppID（开放平台应用/公众号/小程序，须与 sp_mchid 绑定）
   wrangler secret put WXPAY_SUB_MCHID     # 子商户（特约商户）商户号 —— 钱进这个账户
   wrangler secret put WXPAY_SERIAL        # 服务商商户 API 证书序列号
   wrangler secret put WXPAY_PRIVATE_KEY   # 服务商 apiclient_key.pem 私钥全文
   wrangler secret put WXPAY_V3KEY         # APIv3 密钥（32 字节）
   # 选填：子商户 AppID（须与 sub_mchid 绑定）；不配就不传 sub_appid
   wrangler secret put WXPAY_SUB_APPID
   ```
3. 服务商平台：子商户进件完成 + 服务商绑定 Native 支付产品；回调地址加白名单（无需鉴权）。
4. 验证：`curl https://<域名>/health` 应返回
   `{"mode":"wechat","wxpay":{"access":"partner","configured":true,"missing":[]}}`；
   真机扫码扣款 → `credits` 到账。

### 退回直连商户模式

`WXPAY_SP_MODE = "merchant"`，改配 `WXPAY_MCHID` + `WXPAY_APPID`（其余三项不变）。
代码会走 `/v3/pay/transactions/native` 那一套，其余逻辑完全一致。

### 接口对照

| 动作 | 服务商模式（partner） | 直连商户（merchant） |
|---|---|---|
| Native 下单 | `POST /v3/pay/partner/transactions/native`（`sp_appid`/`sp_mchid`/`sub_mchid`） | `POST /v3/pay/transactions/native`（`appid`/`mchid`） |
| 查单 | `GET /v3/pay/partner/transactions/out-trade-no/{no}?sp_mchid=&sub_mchid=` | `GET /v3/pay/transactions/out-trade-no/{no}?mchid=` |
| 关单 | `POST /v3/pay/partner/transactions/out-trade-no/{no}/close` | `POST /v3/pay/transactions/out-trade-no/{no}/close` |

签名一律：`mchid` = 服务商商户号（partner）/ 商户号（merchant），
`serial_no` = 该商户的 API 证书序列号；签名的 URL 不含 query string。

## 防白嫖设计

- 客户端烧不进密钥：Worker 是唯一持钥层，客户端只配域名。
- 限流：同一 `mid` 每分钟最多 20 次下单（KV 计数），超限 429。
- 金额校验：回调到账金额与下单快照不一致即拒绝入账。
- 幂等：重复回调直接 `SUCCESS`，不重复加分。
- 验签：微信支付平台证书 RSA-SHA256 + 5 分钟时效窗，防重放。
- 商户号校验：回调里的 `sp_mchid`/`sub_mchid`（直连为 `mchid`）与配置不符即拒绝入账。
- **回调兜底**：微信回调打不到本 Worker 时，客户端轮询 `status` 会顺带向微信查真单
  （同一单 5 秒内最多查一次），命中 `SUCCESS` 直接入账，命中终态置 `closed`。
- **过期关单**：本地过期且下单满 5 分钟后，向微信关一次单（只试一次），避免旧码继续可付。

## 已知边界

- 微信 Native 二维码有效期默认 30 分钟（`ORDER_EXPIRE_MINUTES`，同时作为微信侧的
  `time_expire`），过期需重新下单。
- mock 模式的 `codeUrl` 是占位串（`mock://...`），扫不出东西，靠客户端
  「模拟支付成功」按钮调 `mock/confirm` 走通全流程。
- KV 最终一致性：入账后轮询接口最多 1 分钟级延迟时，客户端以轮询到 `paid` 为准。
