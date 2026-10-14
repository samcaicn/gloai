/** Worker 全局环境变量（wrangler.toml [vars] + wrangler secret put 的密钥）。 */
export interface Env {
  PAY_KV: KVNamespace;
  /** mock = 开发自测；wechat = 微信支付 Native 扫码 */
  PAY_MODE: string;
  /** 每 1 元人民币到账积分（比例待定，全局一个值） */
  CREDITS_PER_YUAN: string;
  /** 支付单有效期（分钟） */
  ORDER_EXPIRE_MINUTES: string;
  /** 微信支付回调地址 */
  WXPAY_NOTIFY_URL: string;
  // ---- 以下走 wrangler secret put，不落库 ----
  WXPAY_MCHID?: string;
  WXPAY_APPID?: string;
  WXPAY_SERIAL?: string;
  WXPAY_PRIVATE_KEY?: string;
  WXPAY_V3KEY?: string;
}

export type PayMode = 'mock' | 'wechat';

export interface Tier {
  id: string;
  name: string;
  tagline: string;
  /** 价格（分） */
  priceCents: number;
  /** 到账积分（下单时按 CREDITS_PER_YUAN 快照，改比例不影响已付订单） */
  credits: number;
  perks: string[];
  featured?: boolean;
}

export type OrderStatus = 'created' | 'paid' | 'expired' | 'closed';

export interface Order {
  id: string;
  /** 微信 out_trade_no，与 id 同值（≤32 位、URL 安全） */
  outTradeNo: string;
  tierId: string;
  tierName: string;
  /** 客户端实例标识（本机高熵随机、持久化），webhook 据此入账 */
  mid: string;
  amountCents: number;
  credits: number;
  status: OrderStatus;
  /** wechat：weixin:// 扫码串；mock：占位串 */
  codeUrl: string | null;
  mock: boolean;
  createdAt: number;
  expireAt: number;
  paidAt?: number;
  wxTransactionId?: string;
}

export interface CreditsRecord {
  mid: string;
  credits: number;
  updatedAt: number;
}
