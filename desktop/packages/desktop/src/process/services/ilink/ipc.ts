import { ipcMain } from 'electron';
import { createLoginQr, pollLoginStatus, type LoginQr, type ScanStatus } from './login';
import {
  getDeviceId,
  bindIlink,
  fetchDeviceSalt,
  getDevice,
  resetDevice,
  type DeviceIdOptions,
  type DeviceIdResult,
  type DeviceRecord,
} from './deviceId';
import { IlinkBot, type IlinkBotCreds, type BotSendResult } from './bot';

let bot: IlinkBot | null = null;

/**
 * Register the iLink IPC surface. The renderer drives the scan flow itself
 * (qrCreate -> render QR -> statusPoll loop -> device bind); these handlers
 * only wrap the main-process-only SDK calls that need Node (hardware reads,
 * HMAC, network) and cannot run in the renderer.
 */
export function registerIlinkIpc(): void {
  ipcMain.handle('ilink:qr:create', async (_e, opts?: { app?: string; callback?: string }): Promise<LoginQr> => {
    return createLoginQr(opts ?? {});
  });

  ipcMain.handle('ilink:status:poll', async (_e, ticket: string): Promise<ScanStatus> => {
    return pollLoginStatus(ticket);
  });

  ipcMain.handle('ilink:device:get', async (_e, opts: DeviceIdOptions): Promise<DeviceIdResult> => {
    return getDeviceId(opts);
  });

  ipcMain.handle('ilink:device:bind', async (_e, args: { deviceId: string; ilink: string }): Promise<boolean> => {
    return bindIlink(args.deviceId, args.ilink);
  });

  ipcMain.handle('ilink:device:getRecord', async (_e, deviceId: string): Promise<DeviceRecord | null> => {
    return getDevice(deviceId);
  });

  ipcMain.handle('ilink:device:reset', async (_e, deviceId: string): Promise<boolean> => {
    return resetDevice(deviceId);
  });

  ipcMain.handle('ilink:device:salt', async (_e, app: string): Promise<string> => {
    return fetchDeviceSalt(app);
  });

  ipcMain.handle('ilink:bot:init', async (_e, creds: IlinkBotCreds): Promise<{ ok: true }> => {
    bot = new IlinkBot(creds);
    return { ok: true };
  });

  ipcMain.handle('ilink:bot:send', async (_e, args: { text: string; toUser?: string }): Promise<BotSendResult> => {
    if (!bot) return { ok: false, status: 0, body: 'bot not initialized' };
    return bot.sendMessage(args.text, args.toUser);
  });
}
