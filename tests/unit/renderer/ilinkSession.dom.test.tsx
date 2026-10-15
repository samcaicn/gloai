/**
 * 全局登录态 provider：一次扫码从「取票」到「登录成功」之间发生的每一件事。
 *
 * 渲染层的桥（@renderer/services/ilink）整个 mock 掉，所以这里验证的是编排：
 * 轮询节奏、票过期换票、连续失败的认输、代次号防两条轮询打架、退出登录。
 * 轮询间隔是真实代码里的 1.5s，所以时钟也是假的——推进时钟 = 等一轮。
 */

import React from 'react';
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  qrCreate: vi.fn(),
  statusPoll: vi.fn(),
  loginComplete: vi.fn(),
  logout: vi.fn(),
  sessionGet: vi.fn(),
  pushSend: vi.fn(),
}));

vi.mock('@renderer/services/ilink', () => ({
  ilinkQrCreate: (opts?: { app?: string; callback?: string }) => state.qrCreate(opts),
  ilinkStatusPoll: (ticket: string) => state.statusPoll(ticket),
  ilinkLoginComplete: (args: { confirmed: unknown; app?: string }) => state.loginComplete(args),
  ilinkLogout: () => state.logout(),
  ilinkPushSend: (args: { text: string; toUser?: string }) => state.pushSend(args),
  ilinkSessionGet: () => state.sessionGet(),
}));

import { IlinkSessionProvider, useIlinkSession } from '@renderer/services/ilinkSession';

const POLL_MS = 1_500;

const ticket = { ticket: 'T1', content: 'https://liteapp.weixin.qq.com/q/abc', expiresIn: 60, app: 'tupai' };

const confirmed = {
  ilink: 'o9cq8@im.wechat',
  userId: 'o9cq8@im.wechat',
  botToken: 'bot1@im.bot:secret',
  baseUrl: 'https://ilinkai.weixin.qq.com',
  linkToken: 'LJ3DgyXkZM',
  contextToken: '',
  app: 'tupai',
  deviceId: '',
  callback: '',
};

const done = {
  session: {
    ilink: 'o9cq8@im.wechat',
    userId: 'o9cq8@im.wechat',
    botToken: 'bot1@im.bot:secret',
    baseUrl: 'https://ilinkai.weixin.qq.com',
    linkToken: 'LJ3DgyXkZM',
    contextToken: '',
    app: 'tupai',
    deviceId: 'dev-abc',
    loggedAt: 1,
  },
  device: { deviceId: 'dev-abc', isNew: false, confidence: 1, matched: 5, source: 'server' as const },
  bound: true,
  pushed: true,
};

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <IlinkSessionProvider>{children}</IlinkSessionProvider>
);

const setup = () => renderHook(() => useIlinkSession(), { wrapper });

/** 让挂载副作用（读落盘登录态）先落地。 */
const settle = () =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });

/** 推进时钟并 flush 微任务：轮询的一轮就是一个 1.5s。 */
const tick = (ms: number = 0) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

beforeEach(() => {
  vi.useFakeTimers();
  for (const fn of Object.values(state)) fn.mockReset();
  state.sessionGet.mockResolvedValue(null);
  state.qrCreate.mockResolvedValue(ticket);
  state.statusPoll.mockResolvedValue({ status: 'pending' });
  state.loginComplete.mockResolvedValue(done);
  state.logout.mockResolvedValue(true);
  state.pushSend.mockResolvedValue({ ok: true, status: 200, body: '{}' });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the stored login on startup', () => {
  it('starts signed in when the last scan left a session on disk', async () => {
    state.sessionGet.mockResolvedValue(done.session);

    const { result } = setup();
    await settle();

    expect(result.current.phase).toBe('signedIn');
    expect(result.current.session?.ilink).toBe('o9cq8@im.wechat');
    expect(result.current.pushReady).toBe(true);
  });

  it('starts anonymous when there is none, and also when reading it fails', async () => {
    const { result } = setup();
    await settle();
    expect(result.current.phase).toBe('anonymous');

    state.sessionGet.mockRejectedValue(new Error('disk gone'));
    const second = setup();
    await settle();
    expect(second.result.current.phase).toBe('anonymous');
  });
});

describe('the scan flow', () => {
  it('goes from a fresh ticket through polling to a signed-in session', async () => {
    state.statusPoll.mockResolvedValueOnce({ status: 'pending' }).mockResolvedValueOnce({
      status: 'confirmed',
      confirmed,
    });

    const { result } = setup();
    await settle();

    act(() => result.current.startLogin());
    // 取到票：二维码该出来了。
    await tick(0);
    expect(state.qrCreate).toHaveBeenCalledWith({ app: 'tupai' });
    expect(result.current.qr?.content).toBe(ticket.content);
    expect(result.current.phase).toBe('scanning');

    // 两轮轮询：先 pending，再 confirmed。
    await tick(POLL_MS);
    expect(state.statusPoll).toHaveBeenCalledWith('T1');
    await tick(POLL_MS);

    // 收尾全权交给主进程，provider 只换上登录态。
    expect(state.loginComplete).toHaveBeenCalledWith({ confirmed, app: 'tupai' });
    expect(result.current.phase).toBe('signedIn');
    expect(result.current.session?.deviceId).toBe('dev-abc');
    expect(result.current.device?.deviceId).toBe('dev-abc');
    expect(result.current.qr).toBeNull();
  });

  it('takes a new ticket when the old one expires, instead of giving up', async () => {
    state.qrCreate.mockResolvedValueOnce(ticket).mockResolvedValueOnce({ ...ticket, ticket: 'T2' });
    state.statusPoll.mockResolvedValueOnce({ status: 'expired', waited: 30_000 });

    const { result } = setup();
    await settle();
    act(() => result.current.startLogin());
    await tick(0);
    await tick(POLL_MS);

    expect(state.qrCreate).toHaveBeenCalledTimes(2);
    expect(result.current.qr?.ticket).toBe('T2');
    expect(result.current.phase).toBe('scanning');
  });

  it('surfaces a terminal status it does not recognize as an error rather than polling forever', async () => {
    state.statusPoll.mockResolvedValue({ status: 'rejected' });

    const { result } = setup();
    await settle();
    act(() => result.current.startLogin());
    await tick(0);
    await tick(POLL_MS);

    expect(result.current.phase).toBe('error');
    expect(result.current.error).toBe('rejected');
  });

  it('gives up after a run of failed polls, and keeps the reason', async () => {
    state.statusPoll.mockRejectedValue(new Error('network down'));

    const { result } = setup();
    await settle();
    act(() => result.current.startLogin());
    await tick(0);
    // 连续失败 4 次才认输：网络抖一下不该把二维码判死。
    for (let i = 0; i < 4; i += 1) await tick(POLL_MS);

    expect(state.statusPoll).toHaveBeenCalledTimes(4);
    expect(result.current.phase).toBe('error');
    expect(result.current.error).toBe('network down');
    // 认输之后就安静了：不再反复取票。
    expect(state.qrCreate).toHaveBeenCalledTimes(1);
  });

  it('does not start a second scan for someone already signed in', async () => {
    state.sessionGet.mockResolvedValue(done.session);

    const { result } = setup();
    await settle();
    act(() => result.current.startLogin());

    expect(state.qrCreate).not.toHaveBeenCalled();
    expect(result.current.phase).toBe('signedIn');
  });

  it('drops the old poll when the QR is refreshed: only the new ticket is asked about', async () => {
    let resolveFirst: ((value: unknown) => void) | undefined;
    state.statusPoll.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFirst = resolve;
        })
    );
    state.qrCreate.mockResolvedValueOnce(ticket).mockResolvedValueOnce({ ...ticket, ticket: 'T2' });

    const { result } = setup();
    await settle();
    act(() => result.current.startLogin());
    await tick(0);
    await tick(POLL_MS);
    // 旧票的轮询挂在 await 上：服务端一直不回。
    expect(state.statusPoll).toHaveBeenCalledTimes(1);

    act(() => result.current.refreshLogin());
    await tick(0);
    expect(state.qrCreate).toHaveBeenCalledTimes(2);
    expect(result.current.qr?.ticket).toBe('T2');

    // 旧票这时才返回 confirmed：它的结果不该把新票的界面顶掉。
    await act(async () => {
      resolveFirst?.({ status: 'confirmed', confirmed });
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(state.loginComplete).not.toHaveBeenCalled();
    expect(result.current.phase).toBe('scanning');
    expect(result.current.qr?.ticket).toBe('T2');
  });
});

describe('leaving', () => {
  it('logs out through the bridge and returns to anonymous', async () => {
    state.sessionGet.mockResolvedValue(done.session);

    const { result } = setup();
    await settle();
    await act(async () => {
      await result.current.logout();
    });

    expect(state.logout).toHaveBeenCalledTimes(1);
    expect(result.current.phase).toBe('anonymous');
    expect(result.current.session).toBeNull();
    expect(result.current.pushReady).toBe(false);
  });

  it('pushes a text through the bridge', async () => {
    state.sessionGet.mockResolvedValue(done.session);

    const { result } = setup();
    await settle();
    await act(async () => {
      await result.current.push('hello');
    });

    expect(state.pushSend).toHaveBeenCalledWith({ text: 'hello', toUser: undefined });
  });
});
