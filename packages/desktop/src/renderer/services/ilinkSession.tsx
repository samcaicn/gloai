import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  ilinkLoginComplete,
  ilinkLogout,
  ilinkPushSend,
  ilinkQrCreate,
  ilinkSessionGet,
  ilinkStatusPoll,
  type BotSendResult,
  type DeviceIdResult,
  type IlinkSession,
  type LoginQr,
} from './ilink';

/**
 * 全应用共享的 iLink 登录态。
 *
 * 登录不是一个页面的私事：登录页、设置页、以及将来任何要推消息的地方，都得看到
 * 同一份「我是谁 / 能不能推」。所以状态放在这里，二维码轮询也只跑一份——两个页面
 * 各自 startLogin 只会抢同一张票，白白让服务端发两次二维码。
 *
 * 真正的凭据不在这里：落盘与推送通道都在主进程（process/services/ilink），
 * 这里只是它的渲染层镜像。
 */

/** 服务端给的 app 标识：与线上确认包里的 app 一致，换了设备码就另算一套。 */
export const ILINK_LOGIN_APP = 'tupai';

const POLL_INTERVAL_MS = 1_500;
/** 连续失败几次才认输：网络抖一下不该把二维码判死。 */
const MAX_CONSECUTIVE_ERRORS = 4;

export type IlinkPhase = 'loading' | 'anonymous' | 'scanning' | 'signedIn' | 'error';

export interface IlinkSessionState {
  /** loading：还在读落盘登录态；其余按字面意思。 */
  phase: IlinkPhase;
  session: IlinkSession | null;
  /** 当前该画的那张二维码；未开始/已登录时为 null。 */
  qr: LoginQr | null;
  device: DeviceIdResult | null;
  /** 服务端最后返回的 status 原词（pending / expired / …），用来给 UI 直接显示。 */
  status: string | null;
  error: string | null;
  /** 登录中（二维码已在轮询）。 */
  scanning: boolean;
  pushReady: boolean;
  startLogin: () => void;
  /** 弃掉当前二维码，换一张新的。 */
  refreshLogin: () => void;
  cancelLogin: () => void;
  logout: () => Promise<void>;
  push: (text: string, toUser?: string) => Promise<BotSendResult>;
}

const IlinkSessionContext = createContext<IlinkSessionState | null>(null);

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function IlinkSessionProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<IlinkSession | null>(null);
  const [phase, setPhase] = useState<IlinkPhase>('loading');
  const [qr, setQr] = useState<LoginQr | null>(null);
  const [device, setDevice] = useState<DeviceIdResult | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 二维码轮询是否在跑；provider 唯一，所以全局只有一条轮询。 */
  const activeRef = useRef(false);
  /**
   * 轮询的代次：每起一条就 +1。上一条在 await 醒来后发现代次变了就自行退出，
   * 这样「刷新二维码」不会出现两条轮询同时问状态、互相覆盖 UI。
   */
  const generationRef = useRef(0);
  /** session 的 ref 镜像：startLogin 要按「当前是否已登录」判断，闭包里的 state 会是旧值。 */
  const sessionRef = useRef<IlinkSession | null>(null);
  const liveRef = useRef(true);

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  useEffect(() => {
    liveRef.current = true;
    return () => {
      liveRef.current = false;
      activeRef.current = false;
    };
  }, []);

  // 启动读一次落盘登录态：上一次扫过码的人不该每次重启都重新扫。
  useEffect(() => {
    let live = true;
    void ilinkSessionGet()
      .then((found) => {
        if (!live) return;
        setSession(found);
        setPhase(found ? 'signedIn' : 'anonymous');
      })
      .catch(() => {
        if (live) setPhase('anonymous');
      });
    return () => {
      live = false;
    };
  }, []);

  const startLogin = useCallback(() => {
    // 已登录的人不该被一张新二维码打回「扫码中」：二维码 UI 都只在未登录时出现，
    // 但 provider 是全局的，挡在这里比指望每个调用点都自觉更稳。
    if (sessionRef.current) return;
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    const current = () => generation === generationRef.current;
    activeRef.current = true;
    setError(null);
    setStatus(null);
    setPhase('scanning');

    void (async () => {
      let failures = 0;
      // 外层：取一张票；票过期了再取一张。内层：在这张票活着的时候反复问状态。
      while (current()) {
        let created: LoginQr;
        try {
          created = await ilinkQrCreate({ app: ILINK_LOGIN_APP });
          failures = 0;
        } catch (e) {
          failures += 1;
          if (!current()) return;
          if (failures >= MAX_CONSECUTIVE_ERRORS) {
            setError(messageOf(e));
            setPhase('error');
            activeRef.current = false;
            return;
          }
          await sleep(POLL_INTERVAL_MS);
          continue;
        }
        if (!current() || !liveRef.current) return;
        setQr(created);

        const deadline = Date.now() + Math.max(30_000, created.expiresIn * 1000);
        while (current() && Date.now() < deadline) {
          try {
            const polled = await ilinkStatusPoll(created.ticket);
            if (!current() || !liveRef.current) return;
            setStatus(polled.status);
            failures = 0;

            if (polled.status === 'confirmed' && polled.confirmed) {
              const done = await ilinkLoginComplete({ confirmed: polled.confirmed, app: ILINK_LOGIN_APP });
              if (!current() || !liveRef.current) return;
              setSession(done.session);
              setDevice(done.device ?? null);
              setQr(null);
              setPhase('signedIn');
              activeRef.current = false;
              return;
            }
            if (polled.status === 'expired') break;
            if (polled.status !== 'pending') {
              // 服务端给了没见过的终态：原样露出，不再空转。
              setError(polled.status);
              setPhase('error');
              activeRef.current = false;
              return;
            }
          } catch (e) {
            failures += 1;
            if (!current()) return;
            if (failures >= MAX_CONSECUTIVE_ERRORS) {
              setError(messageOf(e));
              setPhase('error');
              activeRef.current = false;
              return;
            }
          }
          await sleep(POLL_INTERVAL_MS);
        }
      }
    })();
  }, []);

  const cancelLogin = useCallback(() => {
    generationRef.current += 1;
    activeRef.current = false;
    setQr(null);
    setStatus(null);
    setError(null);
    setPhase((now) => (now === 'signedIn' ? now : 'anonymous'));
  }, []);

  /** 换新一张二维码：先让上一条轮询作废，再起新的。 */
  const refreshLogin = useCallback(() => {
    generationRef.current += 1;
    activeRef.current = false;
    setQr(null);
    setStatus(null);
    setError(null);
    // 让上一条在下一个 await 边界退出（它自己会看到代次变了），再取新票。
    setTimeout(() => startLogin(), 0);
  }, [startLogin]);

  const logout = useCallback(async () => {
    activeRef.current = false;
    await ilinkLogout();
    setSession(null);
    setDevice(null);
    setQr(null);
    setStatus(null);
    setError(null);
    setPhase('anonymous');
  }, []);

  const push = useCallback((text: string, toUser?: string) => ilinkPushSend({ text, toUser }), []);

  const value = useMemo<IlinkSessionState>(
    () => ({
      phase,
      session,
      qr,
      device,
      status,
      error,
      scanning: phase === 'scanning',
      pushReady: Boolean(session?.botToken && session?.baseUrl),
      startLogin,
      refreshLogin,
      cancelLogin,
      logout,
      push,
    }),
    [phase, session, qr, device, status, error, startLogin, refreshLogin, cancelLogin, logout, push]
  );

  return <IlinkSessionContext.Provider value={value}>{children}</IlinkSessionContext.Provider>;
}

export function useIlinkSession(): IlinkSessionState {
  const value = useContext(IlinkSessionContext);
  if (!value) throw new Error('useIlinkSession must be used inside <IlinkSessionProvider>');
  return value;
}

/**
 * 同一份登录态，但在 provider 之外返回 null 而不是抛错。
 * 给那些「能推就推」的边缘消费者用（比如通知）：它们跑在测试里、或者还没挂上
 * provider 时，不该因为读不到登录态就把整个组件树炸掉。
 */
export function useIlinkSessionOptional(): IlinkSessionState | null {
  return useContext(IlinkSessionContext);
}
