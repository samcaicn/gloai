/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The workbench page: the designed prototype, whole.
 *
 * It is one self-contained document — its own styles, its own script, its own idea of which panel is showing — so it
 * is shown in a frame rather than rebuilt: what ships is the design, not a re-typing of it. The frame is sandboxed
 * (scripts yes, same-origin no), so the prototype runs and still cannot reach the app around it, and `allow-modals`
 * is there because its pricing panel is a `<dialog>`.
 *
 * Two things are injected, and they are the whole of the seam with the host:
 *  - its own top bar is hidden (the window already has one, and two on screen reads as a mistake);
 *  - the panel the route names is opened on load, which is what makes the sidebar's two rows deep links.
 *
 * The payment seam (third and last): the prototype cannot fetch the pay worker itself — the sandbox has no
 * same-origin — so it talks to this host over postMessage (`pay:hello` / `pay:buy`), and this component drives the
 * one flow that needs the outside world: create the order over IPC, draw the WeChat QR here (qrcode.react, already
 * a dependency), poll until paid, and post the result back into the frame.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Button, Message, Modal } from '@arco-design/web-react';
import { QRCodeSVG } from 'qrcode.react';
import { STUDIO_EXPERTS_PATH, ZHICHUANG_ID } from './index';
import { enablePlugin, isPluginEnabled, onPluginToggle } from '@renderer/plugins/manager';
import {
  payCreateOrder,
  payGetConfig,
  payGetCredits,
  payGetTiers,
  payMockConfirm,
  payPollOrder,
  yuanText,
  type CreatedOrder,
  type PayOrderState,
  type PayTier,
} from '@renderer/services/pay';
import workbenchHtml from './workbench.html?raw';

/** The panels the prototype can open; anything else falls back to the workbench. */
const STUDIO_PAGES = ['home', 'chat', 'plan', 'exec', 'results', 'tasks', 'experts'] as const;

type StudioPage = (typeof STUDIO_PAGES)[number];

const asStudioPage = (value: string): StudioPage =>
  (STUDIO_PAGES as readonly string[]).includes(value) ? (value as StudioPage) : 'home';

const POLL_INTERVAL_MS = 2000;
/** 弹窗打开后的轮询上限：与支付单有效期对齐，到点自动关。 */
const POLL_TIMEOUT_MS = 35 * 60 * 1000;

/** 宿主 → iframe 的消息（sandbox origin 为 null，两侧都以 source 字段互认）。 */
type HostToFrameMessage =
  | { type: 'pay:sync'; tiers: PayTier[]; credits: number | null; configured: boolean }
  | {
      type: 'pay:result';
      ok: boolean;
      tierName?: string;
      credits?: number;
      balance?: number;
      error?: string;
    };

/** iframe → 宿主的消息。 */
type FrameToHostMessage =
  | { source: 'mu-studio'; type: 'pay:hello' }
  | { source: 'mu-studio'; type: 'pay:buy'; tierId: string };

const isFrameMessage = (data: unknown): data is FrameToHostMessage =>
  typeof data === 'object' &&
  data !== null &&
  (data as { source?: unknown }).source === 'mu-studio' &&
  typeof (data as { type?: unknown }).type === 'string';

/**
 * The document the frame shows. Appended rather than edited: the prototype's own markup stays exactly as designed, and
 * a replacement function (not a string) keeps `$` in the source from being read as a replacement pattern.
 */
const buildStudioDocument = (page: StudioPage): string => {
  const injected = `
    <style id='mu-host-overrides'>
      .global-header { display: none !important; }
      .app { height: 100vh !important; }
    </style>
    <script>
      (function () {
        try { show('${page}'); } catch (error) { console.warn('[studio] could not open ${page}', error); }
      })();
    </script>
  `;
  return workbenchHtml.replace('</body>', () => `${injected}</body>`);
};

const StudioPage: React.FC = () => {
  const { pathname } = useLocation();
  const [installed, setInstalled] = useState(() => isPluginEnabled(ZHICHUANG_ID));
  const [busy, setBusy] = useState(false);

  // 支付状态：档位 / 余额 / 进行中的订单
  const [tiers, setTiers] = useState<PayTier[]>([]);
  const [credits, setCredits] = useState<number | null>(null);
  const [configured, setConfigured] = useState(false);
  const [order, setOrder] = useState<CreatedOrder | null>(null);
  const [creating, setCreating] = useState(false);
  const [mockBusy, setMockBusy] = useState(false);
  const frameRef = useRef<HTMLIFrameElement | null>(null);

  useEffect(
    () =>
      onPluginToggle((name) => {
        if (name === ZHICHUANG_ID) setInstalled(isPluginEnabled(ZHICHUANG_ID));
      }),
    [],
  );

  const postToFrame = useCallback((message: HostToFrameMessage) => {
    frameRef.current?.contentWindow?.postMessage({ source: 'mu-host', ...message }, '*');
  }, []);

  const refreshCredits = useCallback(async () => {
    try {
      const balance = await payGetCredits();
      setCredits(balance.credits);
    } catch {
      /* Worker 不可用时保持 -- */
    }
  }, []);

  // 启动时拉一次配置/档位/余额（iframe 的 hello 握手见下方 message 监听）
  useEffect(() => {
    void (async () => {
      try {
        const cfg = await payGetConfig();
        setConfigured(!!cfg.apiBase && !cfg.apiBase.includes('127.0.0.1') && !cfg.apiBase.includes('localhost'));
      } catch {
        /* ignore */
      }
      try {
        setTiers(await payGetTiers());
      } catch {
        /* 页面用自带默认档位渲染 */
      }
      await refreshCredits();
    })();
  }, [refreshCredits]);

  // iframe → 宿主：hello 时同步数据；buy 时走完整购买流
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (!isFrameMessage(event.data)) return;
      if (event.data.type === 'pay:hello') {
        postToFrame({ type: 'pay:sync', tiers, credits, configured });
        return;
      }
      if (event.data.type === 'pay:buy') {
        void (async () => {
          setCreating(true);
          try {
            const created = await payCreateOrder(event.data.tierId);
            setOrder(created); // 打开二维码弹窗，轮询见下方 effect
          } catch (error) {
            postToFrame({
              type: 'pay:result',
              ok: false,
              error: error instanceof Error ? error.message : String(error),
            });
          } finally {
            setCreating(false);
          }
        })();
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [postToFrame, tiers, credits, configured]);

  // 订单轮询：paid → 关弹窗 + 通知 iframe + 刷余额；expired → 关弹窗 + 通知
  useEffect(() => {
    if (!order) return;
    let alive = true;
    const startedAt = Date.now();
    const timer = setInterval(() => {
      void (async () => {
        try {
          const state: PayOrderState = await payPollOrder(order.orderId);
          if (!alive) return;
          if (state.status === 'paid') {
            const balance = await payGetCredits();
            if (!alive) return;
            setCredits(balance.credits);
            postToFrame({
              type: 'pay:result',
              ok: true,
              tierName: state.tierName,
              credits: state.credits,
              balance: balance.credits,
            });
            Message.success(`${state.tierName} 支付成功，积分已到账`);
            setOrder(null);
          } else if (state.status === 'expired' || state.status === 'closed') {
            postToFrame({ type: 'pay:result', ok: false, error: '支付已过期，请重新下单' });
            setOrder(null);
          } else if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
            postToFrame({ type: 'pay:result', ok: false, error: '支付超时，请重新下单' });
            setOrder(null);
          }
        } catch {
          /* 网络抖动：下一轮再试，轮询上限兜底 */
        }
      })();
    }, POLL_INTERVAL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [order, postToFrame]);

  const onMockConfirm = useCallback(async () => {
    if (!order) return;
    setMockBusy(true);
    try {
      await payMockConfirm(order.orderId);
      // 轮询 effect 会在下一拍收到 paid 并收尾
    } catch (error) {
      Message.error(error instanceof Error ? error.message : String(error));
    } finally {
      setMockBusy(false);
    }
  }, [order]);

  // The plugin was uninstalled (or never installed): its sidebar rows are gone, so reaching this route directly
  // should offer to reinstall rather than show an empty frame.
  if (!installed) {
    return (
      <div className='size-full flex flex-col items-center justify-center gap-16px bg-bg-1 text-t-secondary'>
        <div className='text-15px'>{'AI 工作台插件未安装'}</div>
        <div className='text-13px text-t-tertiary'>{'安装后可在此使用智创协同 AI · 行业专家工作台'}</div>
        <Button
          type='primary'
          loading={busy}
          onClick={() => {
            setBusy(true);
            void enablePlugin(ZHICHUANG_ID).then((ok) => {
              setBusy(false);
              setInstalled(ok);
            });
          }}
        >
          {'安装插件'}
        </Button>
      </div>
    );
  }

  const page = asStudioPage(pathname === STUDIO_EXPERTS_PATH ? 'experts' : 'home');
  const document = useMemo(() => buildStudioDocument(page), [page]);

  return (
    <div className='size-full bg-bg-1'>
      <iframe
        // A new document per panel: the frame reloads on the route instead of the script inside it navigating.
        key={page}
        ref={frameRef}
        title='智创协同 AI · 行业专家工作台'
        className='block size-full border-0'
        srcDoc={document}
        sandbox='allow-scripts allow-modals allow-popups allow-forms'
      />

      <Modal
        title={order ? `微信扫码支付 · ${order.tier.name}` : '支付'}
        visible={!!order}
        onCancel={() => setOrder(null)}
        footer={null}
        autoFocus={false}
      >
        {order && (
          <div className='flex flex-col items-center gap-12px pt-8px'>
            <div className='text-13px text-t-secondary'>
              {`¥${yuanText(order.tier.priceCents)} · 到账 ${order.tier.credits} 积分`}
            </div>
            <div className='p-12px bg-white rounded-8px'>
              <QRCodeSVG value={order.codeUrl} size={200} level='M' />
            </div>
            <div className='text-12px text-t-tertiary'>
              {order.mock ? '当前为本地模拟支付（PAY_MODE=mock）' : '请使用微信扫一扫二维码完成支付'}
            </div>
            {order.mock && (
              <Button type='primary' loading={mockBusy} onClick={() => void onMockConfirm()}>
                模拟支付成功
              </Button>
            )}
            <Button loading={creating} onClick={() => setOrder(null)}>
              取消支付
            </Button>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default StudioPage;
