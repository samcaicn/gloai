# mu-pay · 智创协同 AI 桌面端支付后端

Cloudflare Worker，**唯一持有微信支付商户密钥的一层**。桌面客户端只跟本 Worker 通信，
配置里只存 Worker 域名，不持有任何商户凭证。积分余额以本 Worker 的 KV 为准。

## 架构

```
┌─────────────────────┐         ┌──────────────────────┐         ┌──────────────┐
│  桌面客户端(Electron)│ ──────▶ │  本 Worker (CF)       │ ──────▶ │ 微信支付 APIv3│
│  · 只存 Worker 域名   │        │  · 唯一持商户密钥      │        │  Native 下单  │
│  · mid 本机持久化     │ ◀───── │  · KV: 单/余额/限流   │ ◀───── │  支付回调     │
└─────────────────────┘ 轮询结果  └──────────────────────┘ 验签入账  └──────────────┘
```

支付闭环：客户端生成高熵 `mid`（本机持久化）→ 下单时带入 → 微信回调按 `attach.mid`
把积分写进 KV `credits:<mid>` → 客户端轮询 `status` 拿到结果 → 积分即时到账。
全程无需用户复制粘贴任何凭证。

## 路由

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 探活，返回当前支付模式 |
| GET | `/api/pay/tiers` | 三档套餐（积分按 `CREDITS_PER_YUAN` 实时算） |
| POST | `/api/pay/create` | body `{tier, mid}` → `{orderId, codeUrl, expiresAt, mock}` |
| GET | `/api/pay/status?orderId=` | 轮询：`created` → `paid` / `expired` |
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

## 切真实收款（微信支付 Native）

1. `wrangler.toml`：`PAY_MODE = "wechat"`，`WXPAY_NOTIFY_URL` 改为部署后的公网地址。
2. 五个 secret（**只进 secret，不落库**）：
   ```bash
   wrangler secret put WXPAY_MCHID        # 商户号
   wrangler secret put WXPAY_APPID        # 公众号/移动应用 AppID
   wrangler secret put WXPAY_SERIAL       # 商户 API 证书序列号
   wrangler secret put WXPAY_PRIVATE_KEY  # apiclient_key.pem 私钥全文
   wrangler secret put WXPAY_V3KEY        # APIv3 密钥（32 字节）
   ```
3. 商户平台：完成商户号认证 + 绑定 Native 支付产品；回调地址加白名单（无需鉴权）。
4. 验证：`curl https://<域名>/health` 应返回 `{"mode":"wechat"}`；
   真机扫码扣款 → `credits` 到账。

## 防白嫖设计

- 客户端烧不进密钥：Worker 是唯一持钥层，客户端只配域名。
- 限流：同一 `mid` 每分钟最多 20 次下单（KV 计数），超限 429。
- 金额校验：回调到账金额与下单快照不一致即拒绝入账。
- 幂等：重复回调直接 `SUCCESS`，不重复加分。
- 验签：微信支付平台证书 RSA-SHA256 + 5 分钟时效窗，防重放。

## 已知边界

- 微信 Native 二维码有效期默认 30 分钟（`ORDER_EXPIRE_MINUTES`），过期需重新下单。
- mock 模式的 `codeUrl` 是占位串（`mock://...`），扫不出东西，靠客户端
  「模拟支付成功」按钮调 `mock/confirm` 走通全流程。
- KV 最终一致性：入账后轮询接口最多 1 分钟级延迟时，客户端以轮询到 `paid` 为准。
