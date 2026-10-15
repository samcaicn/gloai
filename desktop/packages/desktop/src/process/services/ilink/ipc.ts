import { ipcMain } from 'electron';
import { createLoginQr, pollLoginStatus, type ConfirmedLogin, type LoginQr, type ScanStatus } from './login';
import {
  getDeviceId,
  bindIlink,
  fetchDeviceSalt,
  getDevice,
  resetDevice,
  ILINK_APP,
  type DeviceIdOptions,
  type DeviceIdResult,
  type DeviceRecord,
} from './deviceId';
import type { IlinkBotCreds } from './bot';
import { activateIlinkPush, deactivateIlinkPush, isPushReady, pushIlinkText, restoreIlinkPush } from './push';
import { clearIlinkSession, fileDeviceStore, readIlinkSession, writeIlinkSession, type IlinkSession } from './session';

/** 登录成功后的第一条推送：既是欢迎语，也是推送通道可用的实测。 */
export const LOGIN_PUSH_TEXT = 'AiBuddy 登录成功，消息推送已开启 ✅';

/**
 * Register the iLink IPC surface. The renderer drives the scan flow itself
 * (qrCreate -> render QR -> statusPoll loop -> loginComplete); these handlers
 * only wrap the main-process-only SDK calls that need Node (hardware reads,
 * HMAC, network) and cannot run in the renderer.
 *
 * 登录的收尾（绑设备 → 落盘 → 开推送）刻意放在主进程 `ilink:login:complete`：
 * 渲染层只负责画二维码和轮询，凭据不经过渲染层转手。
 */
export function registerIlinkIpc(): void {
  ipcMain.handle('ilink:qr:create', async (_e, opts?: { app?: string; callback?: string }): Promise<LoginQr> => {
    return createLoginQr(opts ?? {});
  });

  ipcMain.handle('ilink:status:poll', async (_e, ticket: string): Promise<ScanStatus> => {
    return pollLoginStatus(ticket);
  });

  ipcMain.handle('ilink:device:get', async (_e, opts: DeviceIdOptions): Promise<DeviceIdResult> => {
    // 设备码落盘：不传 store 的话 deviceId 只在内存里，每次启动都变成一台新设备。
    return getDeviceId({ ...opts, store: fileDeviceStore });
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

  // ---- 登录态 + 消息推送 ----

  ipcMain.handle('ilink:session:get', (): IlinkSession | null => readIlinkSession());

  /**
   * 扫码确认后的收尾：解析设备码 → 绑定微信身份 → 落盘登录态 → 起推送通道 →
   * 发一条登录成功推送。设备解析与推送失败都不算登录失败（登录本身已成功）。
   */
  ipcMain.handle(
    'ilink:login:complete',
    async (
      _e,
      args: { confirmed: ConfirmedLogin; app?: string }
    ): Promise<{ session: IlinkSession; device?: DeviceIdResult; bound: boolean; pushed: boolean }> => {
      const confirmed = args.confirmed;
      const app = args.app || confirmed.app || ILINK_APP;

      let device: DeviceIdResult | undefined;
      let bound = false;
      try {
        device = await getDeviceId({ app, ilink: confirmed.ilink, store: fileDeviceStore });
        if (device.deviceId && confirmed.ilink) bound = await bindIlink(device.deviceId, confirmed.ilink);
      } catch {
        // 设备解析是尽力而为：没有设备码不影响登录与推送。
      }

      const session: IlinkSession = {
        ilink: confirmed.ilink,
        userId: confirmed.userId || confirmed.ilink,
        botToken: confirmed.botToken,
        baseUrl: confirmed.baseUrl,
        linkToken: confirmed.linkToken,
        contextToken: confirmed.contextToken,
        app,
        // 设备码为空串时也要回退到登录包里带的那个：?? 只在 null/undefined 时兜底。
        deviceId: device?.deviceId || confirmed.deviceId || '',
        loggedAt: Date.now(),
      };
      writeIlinkSession(session);

      let pushed = false;
      if (session.baseUrl && session.botToken) {
        activateIlinkPush({
          baseUrl: session.baseUrl,
          botToken: session.botToken,
          linkToken: session.linkToken,
          ilink: session.ilink,
          userId: session.userId,
        });
        const result = await pushIlinkText(LOGIN_PUSH_TEXT, session.ilink);
        pushed = result.ok;
      }

      return { session, ...(device ? { device } : {}), bound, pushed };
    }
  );

  /** 退出登录：销毁推送通道 + 删掉落盘凭据。服务端设备绑定留着，换账号登录会覆盖。 */
  ipcMain.handle('ilink:logout', (): boolean => {
    deactivateIlinkPush();
    clearIlinkSession();
    return true;
  });

  ipcMain.handle('ilink:push:send', async (_e, args: { text: string; toUser?: string }) => {
    return pushIlinkText(args.text, args.toUser);
  });

  ipcMain.handle('ilink:push:status', (): boolean => isPushReady());

  // 老通道保留：手动给一组凭据时也落到同一个单例上，避免两处各持一个 bot。
  ipcMain.handle('ilink:bot:init', async (_e, creds: IlinkBotCreds): Promise<{ ok: true }> => {
    activateIlinkPush(creds);
    return { ok: true };
  });

  ipcMain.handle('ilink:bot:send', async (_e, args: { text: string; toUser?: string }) => {
    return pushIlinkText(args.text, args.toUser);
  });
}

/** 启动时恢复上次的推送通道：后台推送不依赖渲染层先登录一遍。 */
export function initIlinkPush(): IlinkSession | null {
  try {
    return restoreIlinkPush();
  } catch (error) {
    console.warn('[ilink] failed to restore push session:', error);
    return null;
  }
}
