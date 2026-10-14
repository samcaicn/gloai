/**
 * 积分套餐支付服务（主进程侧）。
 *
 * 定位与 ilink 对称：渲染层只走 IPC，主进程持有与自有支付 Worker 通信的唯一通道。
 * 商户密钥一概不经过本层——Worker 是唯一持钥层，这里只存 Worker 域名。
 */

// ---------------- 类型 ----------------

export interface PayTier {
  id: string;
  name: string;
  tagline: string;
  priceCents: number;
  credits: number;
  perks: string[];
  featured?: boolean;
}

export interface PayConfig {
  /** 支付 Worker 基地址，如 https://pay.example.com */
  apiBase: string;
}

export interface CreateOrderArgs {
  tierId: string;
}

export interface CreatedOrder {
  orderId: string;
  codeUrl: string;
  expiresAt: number;
  mock: boolean;
  tier: PayTier;
}

export type PayOrderStatus = 'created' | 'paid' | 'expired' | 'closed';

export interface PayOrderState {
  orderId: string;
  status: PayOrderStatus;
  tierId: string;
  tierName: string;
  amountCents: number;
  credits?: number;
  paidAt?: number;
}

export interface PayBalance {
  mid: string;
  credits: number;
}

/** Worker 不可用 / 未配置时的统一错误形态，渲染层直接展示 message。 */
export class PayServiceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PayServiceError';
  }
}
