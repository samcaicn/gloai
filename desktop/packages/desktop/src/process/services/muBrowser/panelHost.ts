import { randomUUID } from 'node:crypto';
import type { BrowserPanelAnswer, BrowserPanelRequest } from '@/common/kyrn/browserBridge';
import type { BrowserRunEvent } from '@/common/kyrn/browserRun';
import type { BrowserHost, OpenedTab, PageHandle } from './bridge';

export type PanelHostDeps = {
  /** Sends a request to the window that shows the browser panel. */
  request(request: BrowserPanelRequest): void;
  publish(event: BrowserRunEvent): void;
  /**
   * Turns a reported `webContents` id into a page, or throws: it has to be a `<webview>` of the agent's own
   * partition, whatever the renderer said. A tab that is handed out again starts from a blank page.
   */
  page(tabId: string, webContentsId: number): Promise<PageHandle>;
  /** The conversation an adapter session belongs to, when the harness named its session instead. */
  conversationOf?(session: string): string | undefined;
  /** A confirmation is waiting and the window may not be in front. */
  nudge?(waiting: boolean): void;
  id?: () => string;
  answerTimeoutMs?: number;
  keyboardTimeoutMs?: number;
};

type Waiting<T> = { resolve(value: T): void; reject(error: Error): void };

/**
 * The browser panel as the bridge needs it, spoken to over IPC. The panel lives in the renderer and only exists
 * while a conversation page is on screen; every request is matched to its answer by id and none waits for ever.
 */
export class PanelHost implements BrowserHost {
  private readonly deps: PanelHostDeps;
  private readonly id: () => string;
  private readonly opening = new Map<string, Waiting<{ tabId: string; conversationId: string }>>();
  private readonly keyboards = new Map<string, Waiting<boolean>>();
  private readonly pages = new Map<string, number>();
  private readonly pageWaiters = new Map<string, ((webContentsId: number) => void)[]>();
  private panelAvailable = false;

  constructor(deps: PanelHostDeps) {
    this.deps = deps;
    this.id = deps.id ?? randomUUID;
  }

  get available(): boolean {
    return this.panelAvailable;
  }

  /** The renderer says whether a conversation page with a browser panel is on screen. */
  setAvailable(available: boolean): void {
    this.panelAvailable = available;
    if (available) return;
    // Nobody is left to answer: whoever is waiting learns so now rather than by timeout.
    for (const waiting of this.opening.values()) waiting.reject(new Error('The conversation was closed'));
    for (const waiting of this.keyboards.values()) waiting.resolve(false);
    this.opening.clear();
    this.keyboards.clear();
  }

  answer(answer: BrowserPanelAnswer): void {
    if (answer.kind === 'keyboard') {
      this.keyboards.get(answer.requestId)?.resolve(answer.held === true);
      this.keyboards.delete(answer.requestId);
      return;
    }
    const waiting = this.opening.get(answer.requestId);
    if (!waiting) return;
    this.opening.delete(answer.requestId);
    if ('error' in answer) waiting.reject(new Error(answer.error || 'The browser panel could not open a tab'));
    else waiting.resolve({ tabId: answer.tabId, conversationId: answer.conversationId });
  }

  /** An agent tab's `<webview>` reported which `webContents` it is. */
  ready(tabId: string, webContentsId: number): void {
    this.pages.set(tabId, webContentsId);
    for (const resolve of this.pageWaiters.get(tabId) ?? []) resolve(webContentsId);
    this.pageWaiters.delete(tabId);
  }

  forget(tabId: string): void {
    this.pages.delete(tabId);
  }

  async openTab(request: { conversation?: string; session?: string; url: string }): Promise<OpenedTab> {
    if (!this.panelAvailable) throw new Error('Open a conversation in the app to use its browser');
    const conversation =
      request.conversation ?? (request.session ? this.deps.conversationOf?.(request.session) : undefined);
    const requestId = this.id();
    const timeoutMs = this.deps.answerTimeoutMs ?? 8_000;
    const opened = await this.waitFor(
      this.opening,
      requestId,
      timeoutMs,
      () => new Error('The browser panel did not answer'),
      () => this.deps.request({ kind: 'open', requestId, conversation, url: request.url })
    );
    const known = this.pages.get(opened.tabId);
    const webContentsId =
      known ??
      (await new Promise<number>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('The new tab never became a page')), timeoutMs);
        const waiters = this.pageWaiters.get(opened.tabId) ?? [];
        waiters.push((id) => {
          clearTimeout(timer);
          resolve(id);
        });
        this.pageWaiters.set(opened.tabId, waiters);
      }));
    return { ...opened, page: await this.deps.page(opened.tabId, webContentsId) };
  }

  publish(event: BrowserRunEvent): void {
    if (event.type === 'closed') this.forget(event.tabId);
    this.deps.publish(event);
  }

  attention(tabId: string, waiting: boolean): void {
    this.deps.request({ kind: 'attention', tabId, waiting });
    this.deps.nudge?.(waiting);
  }

  async keyboard(tabId: string): Promise<boolean> {
    if (!this.panelAvailable) return false;
    const requestId = this.id();
    return this.waitFor(
      this.keyboards,
      requestId,
      this.deps.keyboardTimeoutMs ?? 1_000,
      // No answer is a no: the bridge then types nothing.
      () => false,
      () => this.deps.request({ kind: 'keyboard', requestId, tabId })
    );
  }

  private waitFor<T>(
    pending: Map<string, Waiting<T>>,
    requestId: string,
    timeoutMs: number,
    onTimeout: () => T | Error,
    send: () => void
  ): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId);
        const outcome = onTimeout();
        if (outcome instanceof Error) reject(outcome);
        else resolve(outcome);
      }, timeoutMs);
      pending.set(requestId, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      send();
    });
  }
}
