/**
 * Renderer-side typed wrapper over the `window.muPay` preload bridge.
 *
 * The workbench plugin drives the whole purchase flow here: getTiers ->
 * createOrder -> render QR -> pollOrder loop -> credits refresh. Everything
 * that needs the network (the pay worker) happens in the main process behind
 * these IPC calls; the merchant keys never reach this layer — the worker is
 * the only holder.
 *
 * Failures surface as rejections (the main-process handlers throw), so callers
 * use try/catch — same contract as services/ilink.ts.
 */

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
  apiBase: string;
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

export interface MuPayBridge {
  getConfig(): Promise<PayConfig>;
  setConfig(apiBase: string): Promise<PayConfig>;
  getMid(): Promise<string>;
  getTiers(): Promise<PayTier[]>;
  createOrder(tierId: string): Promise<CreatedOrder>;
  pollOrder(orderId: string): Promise<PayOrderState>;
  getCredits(): Promise<PayBalance>;
  mockConfirm(orderId: string): Promise<{ orderId: string; status: string; credits: number }>;
}

declare global {
  interface Window {
    muPay: MuPayBridge;
  }
}

const bridge = (): MuPayBridge => {
  const api = (window as Window).muPay;
  if (!api) throw new Error('muPay bridge not available');
  return api;
};

export const payGetConfig = (): Promise<PayConfig> => bridge().getConfig();

export const paySetConfig = (apiBase: string): Promise<PayConfig> => bridge().setConfig(apiBase);

export const payGetMid = (): Promise<string> => bridge().getMid();

export const payGetTiers = (): Promise<PayTier[]> => bridge().getTiers();

export const payCreateOrder = (tierId: string): Promise<CreatedOrder> => bridge().createOrder(tierId);

export const payPollOrder = (orderId: string): Promise<PayOrderState> => bridge().pollOrder(orderId);

export const payGetCredits = (): Promise<PayBalance> => bridge().getCredits();

export const payMockConfirm = (orderId: string): Promise<{ orderId: string; status: string; credits: number }> =>
  bridge().mockConfirm(orderId);

/** ¥19.9 -> "19.9"，¥5990 分 -> "59.9"。 */
export const yuanText = (cents: number): string => (cents / 100).toFixed(2).replace(/\.00$/, '');
