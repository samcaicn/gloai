/**
 * 扫码登录的收尾与消息推送通道：`ilink:login:complete` 之后这台机器到底发生了什么。
 *
 * 网络与硬件都在边界上（bot.sendMessage、getDeviceId），全部 mock 掉；真正被验证的是
 * 主进程这边的编排：绑设备 → 落盘 → 开推送 → 发第一条消息，以及每一步失败时的取舍
 * （设备解析失败不算登录失败；没有推送凭据就不推，但登录仍然成立）。
 */

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  userData: '',
  handlers: new Map<string, (event: unknown, ...args: unknown[]) => unknown>(),
  /** 假的 bot.sendMessage：记录每次推送的文本与收件人。 */
  botSend: vi.fn(),
}));

vi.mock('electron', () => ({
  app: { getPath: () => state.userData },
  ipcMain: {
    handle: (channel: string, handler: (event: unknown, ...args: unknown[]) => unknown) => {
      state.handlers.set(channel, handler);
    },
  },
}));

vi.mock('@/process/services/ilink/bot', () => ({
  IlinkBot: class {
    readonly ilink: string;
    readonly userId: string;
    constructor(creds: { ilink: string; userId: string }) {
      this.ilink = creds.ilink;
      this.userId = creds.userId;
    }
    sendMessage = (text: string, toUser?: string) => state.botSend(text, toUser);
  },
}));

vi.mock('@/process/services/ilink/login', () => ({
  createLoginQr: vi.fn(),
  pollLoginStatus: vi.fn(),
}));

vi.mock('@/process/services/ilink/deviceId', () => ({
  ILINK_APP: 'tupai',
  getDeviceId: vi.fn(),
  bindIlink: vi.fn(),
  fetchDeviceSalt: vi.fn(),
  getDevice: vi.fn(),
  resetDevice: vi.fn(),
}));

import { getDeviceId, bindIlink } from '@/process/services/ilink/deviceId';
import type { ConfirmedLogin } from '@/process/services/ilink/login';
import { initIlinkPush, LOGIN_PUSH_TEXT, registerIlinkIpc } from '@/process/services/ilink/ipc';
import { isPushReady, pushIlinkText, pushRecipient } from '@/process/services/ilink/push';
import { clearIlinkSession, readIlinkSession, writeIlinkSession } from '@/process/services/ilink/session';

const confirmed = (overrides: Partial<ConfirmedLogin> = {}): ConfirmedLogin =>
  ({
    ilink: 'o9cq8@im.wechat',
    userId: 'o9cq8@im.wechat',
    botToken: 'bot1@im.bot:secret',
    baseUrl: 'https://ilinkai.weixin.qq.com',
    linkToken: 'LJ3DgyXkZM',
    contextToken: '',
    app: 'tupai',
    deviceId: '',
    callback: '',
    ...overrides,
  }) as ConfirmedLogin;

const invoke = <T>(channel: string, ...args: unknown[]): T => {
  const handler = state.handlers.get(channel);
  if (!handler) throw new Error(`no handler registered for ${channel}`);
  return handler(null, ...args) as T;
};

beforeEach(() => {
  state.userData = fs.mkdtempSync(path.join(os.tmpdir(), 'ilink-flow-'));
  state.botSend.mockReset();
  state.botSend.mockResolvedValue({ ok: true, status: 200, body: '{}' });
  vi.mocked(getDeviceId).mockReset();
  vi.mocked(getDeviceId).mockResolvedValue({
    deviceId: 'dev-abc',
    isNew: false,
    confidence: 1,
    matched: 5,
    source: 'server',
  });
  vi.mocked(bindIlink).mockReset();
  vi.mocked(bindIlink).mockResolvedValue(true);
  // 每个用例都从「未登录、推送未开」开始，不让上一个用例的单例漏进来。
  invoke('ilink:logout');
  clearIlinkSession();
});

afterEach(() => {
  fs.rmSync(state.userData, { recursive: true, force: true });
  state.userData = '';
});

registerIlinkIpc();

describe('ilink:login:complete', () => {
  it('binds the device, persists the credentials, opens the push channel and sends the first message', async () => {
    const done = await invoke<{
      session: { ilink: string; deviceId: string; botToken: string };
      device?: { deviceId: string };
      bound: boolean;
      pushed: boolean;
    }>('ilink:login:complete', { confirmed: confirmed(), app: 'tupai' });

    // 设备码落盘存储被传给 getDeviceId：不传的话每次重启都是一台新设备。
    expect(getDeviceId).toHaveBeenCalledTimes(1);
    expect(vi.mocked(getDeviceId).mock.calls[0][0]).toMatchObject({ app: 'tupai', ilink: 'o9cq8@im.wechat' });
    expect(vi.mocked(getDeviceId).mock.calls[0][0].store).toBeDefined();
    expect(bindIlink).toHaveBeenCalledWith('dev-abc', 'o9cq8@im.wechat');

    expect(done.bound).toBe(true);
    expect(done.pushed).toBe(true);
    expect(done.device?.deviceId).toBe('dev-abc');
    expect(done.session).toMatchObject({ ilink: 'o9cq8@im.wechat', deviceId: 'dev-abc' });

    // 登录态落盘：重启后主进程能自己恢复，不等渲染层。
    const onDisk = readIlinkSession();
    expect(onDisk).toMatchObject({
      ilink: 'o9cq8@im.wechat',
      botToken: 'bot1@im.bot:secret',
      baseUrl: 'https://ilinkai.weixin.qq.com',
      linkToken: 'LJ3DgyXkZM',
      app: 'tupai',
      deviceId: 'dev-abc',
    });
    expect(typeof onDisk?.loggedAt).toBe('number');

    // 推送通道就开，并且第一条消息是登录成功那条。
    expect(isPushReady()).toBe(true);
    expect(pushRecipient()).toBe('o9cq8@im.wechat');
    expect(state.botSend).toHaveBeenCalledTimes(1);
    expect(state.botSend).toHaveBeenCalledWith(LOGIN_PUSH_TEXT, 'o9cq8@im.wechat');
  });

  it('still signs the person in when the device cannot be resolved: no device id, no binding, no lost login', async () => {
    vi.mocked(getDeviceId).mockRejectedValue(new Error('no hardware tools'));

    const done = await invoke<{ session: { ilink: string }; device?: unknown; bound: boolean; pushed: boolean }>(
      'ilink:login:complete',
      { confirmed: confirmed(), app: 'tupai' }
    );

    expect(done.bound).toBe(false);
    expect(done.device).toBeUndefined();
    // 登录本身已经成功：凭据照样落盘，推送照样开。
    expect(done.pushed).toBe(true);
    expect(readIlinkSession()).toMatchObject({ ilink: 'o9cq8@im.wechat' });
    expect(isPushReady()).toBe(true);
  });

  it('falls back to the device id from the login payload when the machine has none', async () => {
    vi.mocked(getDeviceId).mockResolvedValue({
      deviceId: '',
      isNew: false,
      confidence: 0,
      matched: 0,
      source: 'fallback',
    });

    const done = await invoke<{ session: { deviceId: string }; bound: boolean }>('ilink:login:complete', {
      confirmed: confirmed({ deviceId: 'dev-from-server' }),
    });

    expect(done.session.deviceId).toBe('dev-from-server');
    // 没有设备码就没什么可绑的，但也不该为此失败。
    expect(bindIlink).not.toHaveBeenCalled();
    expect(done.bound).toBe(false);
  });

  it('does not open a push channel when the login payload carries no push credentials', async () => {
    const done = await invoke<{ pushed: boolean }>('ilink:login:complete', {
      confirmed: confirmed({ botToken: '' }),
    });

    expect(done.pushed).toBe(false);
    expect(state.botSend).not.toHaveBeenCalled();
    expect(isPushReady()).toBe(false);
  });

  it('reports a failed first push as not pushed, without failing the login', async () => {
    state.botSend.mockResolvedValue({ ok: false, status: 500, body: 'boom' });

    const done = await invoke<{ session: { ilink: string }; pushed: boolean }>('ilink:login:complete', {
      confirmed: confirmed(),
    });

    expect(done.pushed).toBe(false);
    expect(done.session.ilink).toBe('o9cq8@im.wechat');
    expect(readIlinkSession()).not.toBeNull();
  });
});

describe('the push channel', () => {
  it('says it is not ready before a login and pushes nothing instead of throwing', async () => {
    expect(isPushReady()).toBe(false);
    await expect(pushIlinkText('hello')).resolves.toMatchObject({ ok: false, status: 0 });
  });

  it('sends through the signed-in bot, defaulting to the signed-in user as recipient', async () => {
    await invoke('ilink:login:complete', { confirmed: confirmed() });

    const sent = await pushIlinkText('a message');
    expect(sent).toMatchObject({ ok: true, status: 200 });
    expect(state.botSend).toHaveBeenLastCalledWith('a message', 'o9cq8@im.wechat');

    await pushIlinkText('to someone else', 'other@im.wechat');
    expect(state.botSend).toHaveBeenLastCalledWith('to someone else', 'other@im.wechat');
  });
});

describe('ilink:logout', () => {
  it('destroys the push channel and deletes the stored credentials', async () => {
    writeIlinkSession({
      ilink: 'o9cq8@im.wechat',
      userId: 'o9cq8@im.wechat',
      botToken: 'bot1@im.bot:secret',
      baseUrl: 'https://ilinkai.weixin.qq.com',
      linkToken: '',
      contextToken: '',
      app: 'tupai',
      deviceId: '',
      loggedAt: 0,
    });
    expect(initIlinkPush()).not.toBeNull();
    expect(isPushReady()).toBe(true);

    expect(invoke<boolean>('ilink:logout')).toBe(true);
    expect(isPushReady()).toBe(false);
    expect(pushRecipient()).toBeUndefined();
    expect(invoke('ilink:session:get')).toBeNull();
  });
});

describe('initIlinkPush', () => {
  it('restores the push channel from the last login on startup, without the renderer', () => {
    writeIlinkSession({
      ilink: 'o9cq8@im.wechat',
      userId: 'o9cq8@im.wechat',
      botToken: 'bot1@im.bot:secret',
      baseUrl: 'https://ilinkai.weixin.qq.com',
      linkToken: 'LJ3DgyXkZM',
      contextToken: '',
      app: 'tupai',
      deviceId: 'dev-abc',
      loggedAt: 0,
    });

    const restored = initIlinkPush();
    expect(restored).toMatchObject({ ilink: 'o9cq8@im.wechat', deviceId: 'dev-abc' });
    expect(isPushReady()).toBe(true);
  });

  it('finds nothing to restore on a first run, or when the stored login is incomplete', () => {
    expect(initIlinkPush()).toBeNull();
    expect(isPushReady()).toBe(false);

    // 没有推送凭据的登录态：恢复不出推送通道。
    writeIlinkSession({
      ilink: 'o9cq8@im.wechat',
      userId: 'o9cq8@im.wechat',
      botToken: '',
      baseUrl: '',
      linkToken: '',
      contextToken: '',
      app: 'tupai',
      deviceId: '',
      loggedAt: 0,
    });
    expect(initIlinkPush()).toBeNull();
    expect(isPushReady()).toBe(false);
  });
});
