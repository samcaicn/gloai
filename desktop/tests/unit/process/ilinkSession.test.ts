/**
 * 登录态的落盘：扫码一次之后，重启、退出、坏文件各是什么行为。
 *
 * 这里只测 session.ts 自己（userData/ilink 下的 JSON），不碰网络与硬件；
 * deviceId 的硬件读取由它自己的测试覆盖，不在这个文件里假装验证。
 */

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({ userData: '' }));

vi.mock('electron', () => ({
  app: { getPath: () => state.userData },
}));

import {
  clearIlinkSession,
  fileDeviceStore,
  readIlinkSession,
  writeIlinkSession,
  type IlinkSession,
} from '@/process/services/ilink/session';

const session = (overrides: Partial<IlinkSession> = {}): IlinkSession => ({
  ilink: 'o9cq8@im.wechat',
  userId: 'o9cq8@im.wechat',
  botToken: 'bot1@im.bot:secret',
  baseUrl: 'https://ilinkai.weixin.qq.com',
  linkToken: 'LJ3DgyXkZM',
  contextToken: '',
  app: 'tupai',
  deviceId: 'dev-1',
  loggedAt: 1_700_000_000_000,
  ...overrides,
});

const sessionFile = (): string => path.join(state.userData, 'ilink', 'session.json');
const deviceFile = (): string => path.join(state.userData, 'ilink', 'device.json');

describe('ilink session persistence', () => {
  beforeEach(() => {
    state.userData = fs.mkdtempSync(path.join(os.tmpdir(), 'ilink-session-'));
  });

  afterEach(() => {
    fs.rmSync(state.userData, { recursive: true, force: true });
    state.userData = '';
  });

  it('writes the whole credential set to userData/ilink/session.json and reads it back', () => {
    writeIlinkSession(session());

    expect(JSON.parse(fs.readFileSync(sessionFile(), 'utf-8'))).toMatchObject({
      ilink: 'o9cq8@im.wechat',
      botToken: 'bot1@im.bot:secret',
      baseUrl: 'https://ilinkai.weixin.qq.com',
      linkToken: 'LJ3DgyXkZM',
      deviceId: 'dev-1',
    });
    expect(readIlinkSession()).toMatchObject({
      ilink: 'o9cq8@im.wechat',
      userId: 'o9cq8@im.wechat',
      botToken: 'bot1@im.bot:secret',
      loggedAt: 1_700_000_000_000,
    });
  });

  it('reads nothing before a login, and nothing again after a logout', () => {
    expect(readIlinkSession()).toBeNull();

    writeIlinkSession(session());
    expect(readIlinkSession()).not.toBeNull();

    clearIlinkSession();
    expect(readIlinkSession()).toBeNull();
    // 删过一次之后再删也不该抛（退出登录可以点两次）。
    expect(() => clearIlinkSession()).not.toThrow();
  });

  it('treats a file that is not a login as no login: unreadable JSON, and JSON without the push credentials', () => {
    fs.mkdirSync(path.dirname(sessionFile()), { recursive: true });
    fs.writeFileSync(sessionFile(), '{ not json', 'utf-8');
    expect(readIlinkSession()).toBeNull();

    // 有身份没有推送凭据的旧文件：登录态不完整，宁可当作未登录。
    fs.writeFileSync(sessionFile(), JSON.stringify({ ilink: 'o9cq8@im.wechat' }), 'utf-8');
    expect(readIlinkSession()).toBeNull();

    fs.writeFileSync(sessionFile(), JSON.stringify({ botToken: 'bot1@im.bot:s', baseUrl: 'https://x.test' }), 'utf-8');
    expect(readIlinkSession()).toBeNull();
  });

  it('fills the gaps of a hand-edited file instead of failing: missing tokens become empty strings, app falls back', () => {
    fs.mkdirSync(path.dirname(sessionFile()), { recursive: true });
    fs.writeFileSync(
      sessionFile(),
      JSON.stringify({ ilink: 'o9cq8@im.wechat', botToken: 'bot1@im.bot:s', baseUrl: 'https://x.test' }),
      'utf-8'
    );

    const found = readIlinkSession();
    expect(found).not.toBeNull();
    expect(found?.linkToken).toBe('');
    expect(found?.contextToken).toBe('');
    expect(found?.deviceId).toBe('');
    expect(found?.app).toBe('tupai');
    // userId 缺省时按身份 id 走，否则推送收件人就没了。
    expect(found?.userId).toBe('o9cq8@im.wechat');
    expect(found?.loggedAt).toBe(0);
  });
});

describe('ilink device store on disk', () => {
  beforeEach(() => {
    state.userData = fs.mkdtempSync(path.join(os.tmpdir(), 'ilink-device-'));
  });

  afterEach(() => {
    fs.rmSync(state.userData, { recursive: true, force: true });
    state.userData = '';
  });

  it('keeps a device id across restarts: set then get returns the same value', () => {
    fileDeviceStore.set('device_id', 'dev-abc');
    expect(fileDeviceStore.get('device_id')).toBe('dev-abc');
    expect(JSON.parse(fs.readFileSync(deviceFile(), 'utf-8'))).toEqual({ device_id: 'dev-abc' });

    // 第二个 key 不冲掉第一个：设备文件是追加语义，不是整份覆盖。
    fileDeviceStore.set('other_key', 'other-value');
    expect(JSON.parse(fs.readFileSync(deviceFile(), 'utf-8'))).toEqual({
      device_id: 'dev-abc',
      other_key: 'other-value',
    });
  });

  it('reads nothing from a missing or broken device file, rather than throwing', () => {
    expect(fileDeviceStore.get('device_id')).toBeUndefined();

    fs.mkdirSync(path.dirname(deviceFile()), { recursive: true });
    fs.writeFileSync(deviceFile(), 'not json', 'utf-8');
    expect(fileDeviceStore.get('device_id')).toBeUndefined();

    // 写失败不能把调用方带走：设备缓存只是优化，登录照样进行。
    fs.writeFileSync(deviceFile(), 'not json', 'utf-8');
    expect(() => fileDeviceStore.set('device_id', 'dev-abc')).not.toThrow();
    expect(fileDeviceStore.get('device_id')).toBe('dev-abc');
  });
});
