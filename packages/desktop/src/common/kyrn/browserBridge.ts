import { bridge } from '../platform/bridge';
import type { BrowserControlAction, BrowserRunEvent, BrowserRunState } from './browserRun';

/**
 * What the main process asks of the browser panel. `open` and `keyboard` are answered through `answer`, matched by
 * `requestId`; `attention` needs no answer.
 */
export type BrowserPanelRequest =
  | { kind: 'open'; requestId: string; conversation?: string; url: string }
  | { kind: 'keyboard'; requestId: string; tabId: string }
  | { kind: 'attention'; tabId: string; waiting: boolean };

export type BrowserPanelAnswer =
  | { kind: 'open'; requestId: string; tabId: string; conversationId: string }
  | { kind: 'open'; requestId: string; error: string }
  | { kind: 'keyboard'; requestId: string; held: boolean };

/** Channel names keep the internal `kyrn.` prefix, like every other mu channel. */
export const kyrnBrowserBridge = {
  /** Every change of every run, in order. The renderer feeds them to the same reducer the main process uses. */
  events: bridge.buildEmitter<BrowserRunEvent>('kyrn.browser.events'),
  /** The whole truth, for a window that has just opened. */
  snapshot: bridge.buildProvider<BrowserRunState[], void>('kyrn.browser.snapshot'),
  control: bridge.buildProvider<void, { tabId: string; action: BrowserControlAction }>('kyrn.browser.control'),
  confirm: bridge.buildProvider<void, { tabId: string; id: string; allowed: boolean }>('kyrn.browser.confirm'),
  request: bridge.buildEmitter<BrowserPanelRequest>('kyrn.browser.request'),
  answer: bridge.buildProvider<void, BrowserPanelAnswer>('kyrn.browser.answer'),
  /** A conversation page with a browser panel is on screen (or no longer). */
  panel: bridge.buildProvider<void, { available: boolean }>('kyrn.browser.panel'),
  /** An agent tab's `<webview>` exists: which `webContents` it is. Sent again after every navigation. */
  ready: bridge.buildProvider<void, { tabId: string; webContentsId: number }>('kyrn.browser.ready'),
};
