import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { ILINK_APP, type DeviceStore } from './deviceId';

/**
 * iLink 登录态持久化。
 *
 * 扫码登录只在内存里活一次：不落盘的话每次重启都要重新扫码，而推送凭据
 * （bot_token / link_token）也随渲染进程一起消失，消息推送无法在后台使用。
 * 所以确认登录后把整份凭据写进 userData/ilink/session.json：
 *
 *  - 重启后主进程自己就能恢复推送通道，不需要渲染层参与；
 *  - 退出登录即删文件 + 销毁 bot，语义干净。
 *
 * 与 pay/identity 的写法保持一致：userData 下自己的目录，JSON，读写都容错。
 */

/** 一次成功扫码登录后留下的全部东西：身份 + 推送凭据 + 绑定的设备码。 */
export interface IlinkSession {
  /** iLink 用户 id（=@im.wechat），推送的默认收件人。 */
  ilink: string;
  userId: string;
  /** 消息推送凭据，来自登录确认包，原样透传。 */
  botToken: string;
  baseUrl: string;
  linkToken: string;
  contextToken: string;
  app: string;
  deviceId: string;
  loggedAt: number;
}

function ilinkDir(): string {
  return path.join(app.getPath('userData'), 'ilink');
}

function sessionPath(): string {
  return path.join(ilinkDir(), 'session.json');
}

function devicePath(): string {
  return path.join(ilinkDir(), 'device.json');
}

/** 读回上次的登录态；文件不存在或格式不对时返回 null（视为未登录）。 */
export function readIlinkSession(): IlinkSession | null {
  try {
    const parsed = JSON.parse(fs.readFileSync(sessionPath(), 'utf-8')) as Partial<IlinkSession>;
    if (!parsed || typeof parsed.ilink !== 'string' || !parsed.ilink) return null;
    if (typeof parsed.botToken !== 'string' || typeof parsed.baseUrl !== 'string') return null;
    return {
      ilink: parsed.ilink,
      userId: typeof parsed.userId === 'string' ? parsed.userId : parsed.ilink,
      botToken: parsed.botToken,
      baseUrl: parsed.baseUrl,
      linkToken: typeof parsed.linkToken === 'string' ? parsed.linkToken : '',
      contextToken: typeof parsed.contextToken === 'string' ? parsed.contextToken : '',
      app: typeof parsed.app === 'string' && parsed.app ? parsed.app : ILINK_APP,
      deviceId: typeof parsed.deviceId === 'string' ? parsed.deviceId : '',
      loggedAt: typeof parsed.loggedAt === 'number' ? parsed.loggedAt : 0,
    };
  } catch {
    return null;
  }
}

export function writeIlinkSession(session: IlinkSession): void {
  fs.mkdirSync(ilinkDir(), { recursive: true });
  fs.writeFileSync(sessionPath(), JSON.stringify(session, null, 2), 'utf-8');
}

export function clearIlinkSession(): void {
  try {
    fs.rmSync(sessionPath(), { force: true });
  } catch {
    /* 已经不在了 */
  }
}

/**
 * 设备码的落盘存储：deviceId.getDeviceId 默认只放内存，重启就换一个设备码，
 * 服务端会不停建新设备、绑定关系也跟着漂。给它一份磁盘实现。
 */
export const fileDeviceStore: DeviceStore = {
  get(key: string): string | undefined {
    try {
      const parsed = JSON.parse(fs.readFileSync(devicePath(), 'utf-8')) as Record<string, string>;
      const value = parsed?.[key];
      return typeof value === 'string' && value ? value : undefined;
    } catch {
      return undefined;
    }
  },
  set(key: string, value: string): void {
    try {
      let parsed: Record<string, string> = {};
      try {
        const existing = JSON.parse(fs.readFileSync(devicePath(), 'utf-8')) as Record<string, string>;
        if (existing && typeof existing === 'object') parsed = existing;
      } catch {
        /* 首次写入 */
      }
      parsed[key] = value;
      fs.mkdirSync(ilinkDir(), { recursive: true });
      fs.writeFileSync(devicePath(), JSON.stringify(parsed, null, 2), 'utf-8');
    } catch {
      /* 写不进去就退化成不缓存，不影响登录 */
    }
  },
};
