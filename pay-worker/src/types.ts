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
  /**
   * 接入模式：
   *   partner（默认） = 服务商模式，走 /v3/pay/partner/*，需 sp_appid + sp_mchid + sub_mchid
   *   merchant       = 普通直连商户，走 /v3/pay/*，需 mchid + appid
   */
  WXPAY_SP_MODE?: string;
  // ---- 以下走 wrangler secret put，不落库 ----
  /** 服务商模式：服务商商户号（同时是签名用的 mchid） */
  WXPAY_SP_MCHID?: string;
  /** 服务商模式：服务商 AppID（开放平台应用 / 公众号 / 小程序任一，须与 sp_mchid 有绑定） */
  WXPAY_SP_APPID?: string;
  /** 服务商模式：子商户（特约商户）商户号 —— 实际收款方 */
  WXPAY_SUB_MCHID?: string;
  /** 服务商模式：子商户 AppID（选填，须与 sub_mchid 有绑定） */
  WXPAY_SUB_APPID?: string;
  /** 直连模式：商户号 / AppID（WXPAY_SP_MODE=merchant 时使用） */
  WXPAY_MCHID?: string;
  WXPAY_APPID?: string;
  /** 商户 API 证书序列号（服务商模式下为服务商商户的证书） */
  WXPAY_SERIAL?: string;
  /** 商户 API 证书私钥 apiclient_key.pem 全文 */
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
  /** 主动向微信查单的时间戳（回调丢失时的兜底，兼作节流） */
  lastQueryAt?: number;
  /** 已向微信发起过关单的时间戳（只试一次） */
  closeTriedAt?: number;
}

export interface CreditsRecord {
  mid: string;
  credits: number;
  updatedAt: number;
}
