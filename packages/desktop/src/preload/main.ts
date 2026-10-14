/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { contextBridge, ipcRenderer, webUtils } from 'electron';
import { ADAPTER_BRIDGE_EVENT_KEY } from '../common/adapter/constant';

/**
 * @description 注入到renderer进程中, 用于与main进程通信
 * */
contextBridge.exposeInMainWorld('electronAPI', {
  emit: (name: string, data: unknown) => {
    return ipcRenderer
      .invoke(
        ADAPTER_BRIDGE_EVENT_KEY,
        JSON.stringify({
          name: name,
          data: data,
        })
      )
      .catch((error) => {
        console.error('IPC invoke error:', error);
        throw error;
      });
  },
  on: (callback: (payload: { event: unknown; value: unknown }) => void) => {
    const handler = (event: unknown, value: unknown) => {
      callback({ event, value });
    };
    ipcRenderer.on(ADAPTER_BRIDGE_EVENT_KEY, handler);
    return () => {
      ipcRenderer.off(ADAPTER_BRIDGE_EVENT_KEY, handler);
    };
  },
  // 获取拖拽文件/目录的绝对路径 / Get absolute path for dragged file/directory
  getPathForFile: (file: File) => webUtils.getPathForFile(file),
  recoverCorruptedDatabase: () => ipcRenderer.invoke('backend:recover-corrupted-database'),
  openLogFolder: () => ipcRenderer.invoke('backend:open-log-folder'),
});

// iLink scan-login + device-code + message-push bridge.
// The renderer drives the scan flow (qrCreate -> render QR -> statusPoll loop -> device bind);
// these invoke the main-process-only SDK calls that need Node (hardware reads, HMAC, network).
contextBridge.exposeInMainWorld('ilink', {
  qrCreate: (opts?: { app?: string; callback?: string }) => ipcRenderer.invoke('ilink:qr:create', opts),
  statusPoll: (ticket: string) => ipcRenderer.invoke('ilink:status:poll', ticket),
  deviceGet: (opts: unknown) => ipcRenderer.invoke('ilink:device:get', opts),
  deviceBind: (args: { deviceId: string; ilink: string }) =>
    ipcRenderer.invoke('ilink:device:bind', args),
  deviceGetRecord: (deviceId: string) => ipcRenderer.invoke('ilink:device:getRecord', deviceId),
  deviceReset: (deviceId: string) => ipcRenderer.invoke('ilink:device:reset', deviceId),
  deviceSalt: (app: string) => ipcRenderer.invoke('ilink:device:salt', app),
  botInit: (creds: unknown) => ipcRenderer.invoke('ilink:bot:init', creds),
  botSend: (args: { text: string; toUser?: string }) => ipcRenderer.invoke('ilink:bot:send', args),
});

// 积分套餐支付桥（智创协同 AI 工作台用）。
// 渲染层驱动完整购买流（建单 → 画二维码 → 轮询到账）；这些 invoke 主进程侧
// 与自有支付 Worker 的通信——商户密钥只在 Worker，客户端不持任何凭证。
// 失败时主进程抛出，promise 以 rejection 到达渲染层（与 ilink 一致）。
// 类型在两端各自声明（preload 与 renderer 是独立 bundle），渲染层侧见
// packages/desktop/src/renderer/services/pay.ts。
interface MuPayTier {
  id: string;
  name: string;
  tagline: string;
  priceCents: number;
  credits: number;
  perks: string[];
  featured?: boolean;
}
interface MuPayCreatedOrder {
  orderId: string;
  codeUrl: string;
  expiresAt: number;
  mock: boolean;
  tier: MuPayTier;
}
interface MuPayOrderState {
  orderId: string;
  status: 'created' | 'paid' | 'expired' | 'closed';
  tierId: string;
  tierName: string;
  amountCents: number;
  credits?: number;
  paidAt?: number;
}
contextBridge.exposeInMainWorld('muPay', {
  getConfig: () => ipcRenderer.invoke('pay:config:get') as Promise<{ apiBase: string }>,
  setConfig: (apiBase: string) => ipcRenderer.invoke('pay:config:set', apiBase) as Promise<{ apiBase: string }>,
  getMid: () => ipcRenderer.invoke('pay:mid:get') as Promise<string>,
  getTiers: () => ipcRenderer.invoke('pay:tiers:get') as Promise<MuPayTier[]>,
  createOrder: (tierId: string) => ipcRenderer.invoke('pay:order:create', { tierId }) as Promise<MuPayCreatedOrder>,
  pollOrder: (orderId: string) => ipcRenderer.invoke('pay:order:status', orderId) as Promise<MuPayOrderState>,
  getCredits: () => ipcRenderer.invoke('pay:credits:get') as Promise<{ mid: string; credits: number }>,
  mockConfirm: (orderId: string) =>
    ipcRenderer.invoke('pay:mock:confirm', orderId) as Promise<{ orderId: string; status: string; credits: number }>,
});

// Synchronously fetch the aioncore port and expose it to the renderer
// via contextBridge (direct window assignment is invisible under contextIsolation).
const backendPort = ipcRenderer.sendSync('get-backend-port') as number;
const initialLanguage = ipcRenderer.sendSync('get-initial-language') as string | null;
const backendStartupFailed = ipcRenderer.sendSync('get-backend-startup-failed') as boolean;
const backendStartupFailure = ipcRenderer.sendSync('get-backend-startup-failure') as unknown;
const nodeRuntimeDeferred = ipcRenderer.sendSync('get-node-runtime-deferred') as boolean;
contextBridge.exposeInMainWorld('__backendPort', backendPort > 0 ? backendPort : 0);
contextBridge.exposeInMainWorld('__initialLanguage', initialLanguage ?? null);
contextBridge.exposeInMainWorld('__aionuiE2ETest', process.env.AIONUI_E2E_TEST === '1');
contextBridge.exposeInMainWorld('__backendStartupFailed', backendStartupFailed === true);
contextBridge.exposeInMainWorld('__backendStartupFailure', backendStartupFailure ?? null);
// The person put the Node.js runtime download off at this start: see common/adapter/nodeRuntimeDeferral.ts.
contextBridge.exposeInMainWorld('__nodeRuntimeDeferred', nodeRuntimeDeferred === true);

// Backend startup state bridge: `getState` re-reads the current failure info on
// mount (resolves the "READY arrived before the renderer subscribed" race), and
// `subscribe` receives subsequent ready/exit pushes on the backend-startup-state
// channel. All communication stays behind the preload contextBridge.
contextBridge.exposeInMainWorld('__backendStartupBridge', {
  getState: () => ipcRenderer.sendSync('get-backend-startup-failure'),
  subscribe: (callback: (state: unknown) => void) => {
    const handler = (_event: unknown, value: unknown) => callback(value);
    ipcRenderer.on('backend-startup-state', handler);
    return () => {
      ipcRenderer.off('backend-startup-state', handler);
    };
  },
});

// 托盘事件监听 - 将 IPC 事件转换为 DOM 事件
// Tray event listeners - convert IPC events to DOM events
const trayEvents = [
  'tray:navigate-to-guid',
  'tray:navigate-to-conversation',
  'tray:open-about',
  'tray:pause-all-tasks',
];

for (const channel of trayEvents) {
  ipcRenderer.on(channel, (_event, ...args) => {
    window.dispatchEvent(new CustomEvent(channel, { detail: args[0] }));
  });
}
