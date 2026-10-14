import { getPayConfig, isConfigured } from './config';
import { getMid } from './identity';
import {
  PayServiceError,
  type CreateOrderArgs,
  type CreatedOrder,
  type PayBalance,
  type PayConfig,
  type PayOrderState,
  type PayTier,
} from './types';

/**
 * 支付 Worker 客户端：主进程唯一的出网支付通道。
 * 全部走自有 Worker；商户密钥、平台证书都在 Worker 侧，本层看不到。
 */

const REQUEST_TIMEOUT_MS = 15_000;

async function request<T>(config: PayConfig, path: string, init?: RequestInit): Promise<T> {
  if (!config.apiBase) throw new PayServiceError('支付服务未配置');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const resp = await fetch(`${config.apiBase}${path}`, {
      ...init,
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    });
    const text = await resp.text();
    let data: unknown = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      throw new PayServiceError(`支付服务响应异常（HTTP ${resp.status}）`);
    }
    if (!resp.ok) {
      const message = (data as { error?: string } | null)?.error ?? `HTTP ${resp.status}`;
      throw new PayServiceError(message);
    }
    return data as T;
  } catch (error) {
    if (error instanceof PayServiceError) throw error;
    if (error instanceof Error && error.name === 'AbortError') {
      throw new PayServiceError('支付服务响应超时');
    }
    // 连接被拒 / DNS 失败：本地默认地址（wrangler dev 未起）时给出可操作提示
    const hint = isConfigured(config) ? '请检查支付服务地址或网络' : '本地支付服务未启动（wrangler dev）或未配置线上地址';
    throw new PayServiceError(`无法连接支付服务：${hint}`);
  } finally {
    clearTimeout(timer);
  }
}

export const payClient = {
  getConfig(): PayConfig {
    return getPayConfig();
  },

  async getTiers(): Promise<PayTier[]> {
    const config = getPayConfig();
    const data = await request<{ tiers: PayTier[] }>(config, '/api/pay/tiers');
    return data.tiers ?? [];
  },

  async createOrder(args: CreateOrderArgs): Promise<CreatedOrder> {
    const config = getPayConfig();
    const mid = getMid();
    return request<CreatedOrder>(config, '/api/pay/create', {
      method: 'POST',
      body: JSON.stringify({ tier: args.tierId, mid }),
    });
  },

  async pollOrder(orderId: string): Promise<PayOrderState> {
    const config = getPayConfig();
    return request<PayOrderState>(config, `/api/pay/status?orderId=${encodeURIComponent(orderId)}`);
  },

  async getCredits(): Promise<PayBalance> {
    const config = getPayConfig();
    const mid = getMid();
    return request<PayBalance>(config, `/api/pay/credits?mid=${encodeURIComponent(mid)}`);
  },

  async mockConfirm(orderId: string): Promise<{ orderId: string; status: string; credits: number }> {
    const config = getPayConfig();
    return request<{ orderId: string; status: string; credits: number }>(config, '/api/pay/mock/confirm', {
      method: 'POST',
      body: JSON.stringify({ orderId }),
    });
  },
};
