/**
 * Renderer-side typed wrapper over the `window.ilink` preload bridge.
 * The desktop client drives the scan flow here: qrCreate -> render QR ->
 * statusPoll loop -> device resolve + bind. The heavy Node work (hardware
 * reads, HMAC, network) happens in the main process behind these IPC calls.
 */

export interface LoginQr {
  ticket: string;
  content: string;
  expiresIn: number;
  app: string;
}

export interface ConfirmedLogin {
  ilink: string;
  userId: string;
  botToken: string;
  baseUrl: string;
  linkToken: string;
  contextToken: string;
  app: string;
  deviceId: string;
  raw: Record<string, unknown>;
}

export interface ScanStatus {
  status: string;
  waited?: number;
  ilink?: string;
  confirmed?: ConfirmedLogin;
  raw: Record<string, unknown>;
}

export interface DeviceIdResult {
  deviceId: string;
  isNew: boolean;
  confidence: number;
  matched: number;
  source: 'server' | 'cache' | 'fallback';
}

export interface BotSendResult {
  ok: boolean;
  status: number;
  body: string;
}

export interface IlinkBridge {
  qrCreate(opts?: { app?: string; callback?: string }): Promise<LoginQr>;
  statusPoll(ticket: string): Promise<ScanStatus>;
  deviceGet(opts: unknown): Promise<DeviceIdResult>;
  deviceBind(args: { deviceId: string; ilink: string }): Promise<boolean>;
  deviceGetRecord(deviceId: string): Promise<unknown>;
  deviceReset(deviceId: string): Promise<boolean>;
  deviceSalt(app: string): Promise<string>;
  botInit(creds: unknown): Promise<{ ok: true }>;
  botSend(args: { text: string; toUser?: string }): Promise<BotSendResult>;
}

declare global {
  interface Window {
    ilink: IlinkBridge;
  }
}

const bridge = (): IlinkBridge => {
  const api = (window as Window).ilink;
  if (!api) throw new Error('ilink bridge not available');
  return api;
};

export const ilinkQrCreate = (opts?: { app?: string; callback?: string }): Promise<LoginQr> =>
  bridge().qrCreate(opts);

export const ilinkStatusPoll = (ticket: string): Promise<ScanStatus> => bridge().statusPoll(ticket);

export const ilinkDeviceGet = (opts: unknown): Promise<DeviceIdResult> => bridge().deviceGet(opts);

export const ilinkDeviceBind = (args: { deviceId: string; ilink: string }): Promise<boolean> =>
  bridge().deviceBind(args);

export const ilinkBotInit = (creds: unknown): Promise<{ ok: true }> => bridge().botInit(creds);

export const ilinkBotSend = (args: { text: string; toUser?: string }): Promise<BotSendResult> =>
  bridge().botSend(args);
