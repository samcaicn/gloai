import { ipcMain } from 'electron';
import { payClient } from './client';
import { setPayConfig } from './config';
import { getMid } from './identity';
import type { CreatedOrder, PayBalance, PayConfig, PayOrderState, PayTier } from './types';

/**
 * 支付 IPC 面板。与 ilink 同一模式：渲染层驱动流程（建单 → 画二维码 → 轮询），
 * 这些 handler 只包住需要主进程出网的 Worker 调用。失败一律抛出，
 * 由渲染层 try/catch 呈现——与 ilink 的行为保持一致。
 */
export function registerPayIpc(): void {
  ipcMain.handle('pay:config:get', (): PayConfig => payClient.getConfig());

  ipcMain.handle('pay:config:set', (_e, apiBase: string): PayConfig => setPayConfig(apiBase));

  ipcMain.handle('pay:mid:get', (): string => getMid());

  ipcMain.handle('pay:tiers:get', async (): Promise<PayTier[]> => payClient.getTiers());

  ipcMain.handle('pay:order:create', async (_e, args: { tierId: string }): Promise<CreatedOrder> =>
    payClient.createOrder(args),
  );

  ipcMain.handle('pay:order:status', async (_e, orderId: string): Promise<PayOrderState> =>
    payClient.pollOrder(orderId),
  );

  ipcMain.handle('pay:credits:get', async (): Promise<PayBalance> => payClient.getCredits());

  ipcMain.handle(
    'pay:mock:confirm',
    async (_e, orderId: string): Promise<{ orderId: string; status: string; credits: number }> =>
      payClient.mockConfirm(orderId),
  );
}
