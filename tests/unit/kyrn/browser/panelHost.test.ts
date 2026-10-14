import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BrowserPanelRequest } from '@/common/kyrn/browserBridge';
import type { BrowserRunEvent } from '@/common/kyrn/browserRun';
import { BrowserBridge, type PageHandle } from '@process/services/muBrowser/bridge';
import { PanelHost } from '@process/services/muBrowser/panelHost';

const page = {} as PageHandle;

function setup() {
  const requests: BrowserPanelRequest[] = [];
  const published: BrowserRunEvent[] = [];
  const pagesAsked: [string, number][] = [];
  const nudges: boolean[] = [];
  let ids = 0;
  const host = new PanelHost({
    request: (request) => requests.push(request),
    publish: (event) => published.push(event),
    page: async (tabId, webContentsId) => {
      pagesAsked.push([tabId, webContentsId]);
      if (webContentsId === 666) throw new Error("That is not a tab of the app's agent browser");
      return page;
    },
    conversationOf: (session) => (session === 'acp-1' ? 'conv-of-acp-1' : undefined),
    nudge: (waiting) => nudges.push(waiting),
    id: () => `req-${++ids}`,
    answerTimeoutMs: 500,
    keyboardTimeoutMs: 200,
  });
  return { host, requests, published, pagesAsked, nudges };
}

describe('the browser panel, as the main process talks to it', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('opens a tab: asks the panel, then waits for the tab to become a page', async () => {
    const app = setup();
    app.host.setAvailable(true);
    const opening = app.host.openTab({ conversation: 'conv-7', url: 'about:blank' });
    expect(app.requests).toEqual([{ kind: 'open', requestId: 'req-1', conversation: 'conv-7', url: 'about:blank' }]);

    app.host.answer({ kind: 'open', requestId: 'req-1', tabId: 'browser-1', conversationId: 'conv-7' });
    await vi.advanceTimersByTimeAsync(10);
    expect(app.pagesAsked).toEqual([]);
    app.host.ready('browser-1', 42);

    await expect(opening).resolves.toEqual({ tabId: 'browser-1', conversationId: 'conv-7', page });
    expect(app.pagesAsked).toEqual([['browser-1', 42]]);
  });

  it('hands out a known tab again without waiting for a new report', async () => {
    const app = setup();
    app.host.setAvailable(true);
    app.host.ready('browser-1', 42);
    const opening = app.host.openTab({ url: 'about:blank' });
    app.host.answer({ kind: 'open', requestId: 'req-1', tabId: 'browser-1', conversationId: 'c' });
    await expect(opening).resolves.toMatchObject({ tabId: 'browser-1' });

    // Once the tab is closed its page is forgotten: the same id would have to report again.
    app.host.publish({ type: 'closed', tabId: 'browser-1' });
    expect(app.published).toEqual([{ type: 'closed', tabId: 'browser-1' }]);
    const again = app.host.openTab({ url: 'about:blank' });
    const failed = expect(again).rejects.toThrow('never became a page');
    app.host.answer({ kind: 'open', requestId: 'req-2', tabId: 'browser-1', conversationId: 'c' });
    await vi.advanceTimersByTimeAsync(600);
    await failed;
  });

  it('finds the conversation of an adapter session when the harness names that instead', async () => {
    const app = setup();
    app.host.setAvailable(true);
    void app.host.openTab({ session: 'acp-1', url: 'about:blank' }).catch(() => undefined);
    void app.host.openTab({ session: 'unknown', url: 'about:blank' }).catch(() => undefined);
    expect(app.requests.map((request) => (request.kind === 'open' ? request.conversation : null))).toEqual([
      'conv-of-acp-1',
      undefined,
    ]);
    app.host.setAvailable(false);
  });

  it('fails clearly: no panel, a panel that refuses, one that never answers, a page that is not the agent’s', async () => {
    const app = setup();
    await expect(app.host.openTab({ url: 'about:blank' })).rejects.toThrow('Open a conversation');
    expect(app.requests).toEqual([]);

    app.host.setAvailable(true);
    const refused = app.host.openTab({ url: 'about:blank' });
    app.host.answer({ kind: 'open', requestId: 'req-1', error: 'Too many browser tabs are open' });
    await expect(refused).rejects.toThrow('Too many browser tabs');

    const silent = expect(app.host.openTab({ url: 'about:blank' })).rejects.toThrow('did not answer');
    await vi.advanceTimersByTimeAsync(600);
    await silent;

    const foreign = app.host.openTab({ url: 'about:blank' });
    app.host.answer({ kind: 'open', requestId: 'req-3', tabId: 'main-window', conversationId: 'c' });
    app.host.ready('main-window', 666);
    await expect(foreign).rejects.toThrow('not a tab of the app');

    const closing = app.host.openTab({ url: 'about:blank' });
    app.host.setAvailable(false);
    await expect(closing).rejects.toThrow('conversation was closed');
  });

  it('asks for the keyboard and takes silence, or no panel, as a no', async () => {
    const app = setup();
    expect(await app.host.keyboard('browser-1')).toBe(false);

    app.host.setAvailable(true);
    const held = app.host.keyboard('browser-1');
    expect(app.requests.at(-1)).toEqual({ kind: 'keyboard', requestId: 'req-1', tabId: 'browser-1' });
    app.host.answer({ kind: 'keyboard', requestId: 'req-1', held: true });
    expect(await held).toBe(true);

    const silent = app.host.keyboard('browser-1');
    await vi.advanceTimersByTimeAsync(300);
    expect(await silent).toBe(false);
    // An answer that comes after the timeout changes nothing.
    app.host.answer({ kind: 'keyboard', requestId: 'req-2', held: true });
  });

  it('asks for attention when a confirmation waits, and lets go of it afterwards', () => {
    const app = setup();
    app.host.attention('browser-1', true);
    app.host.attention('browser-1', false);
    expect(app.requests).toEqual([
      { kind: 'attention', tabId: 'browser-1', waiting: true },
      { kind: 'attention', tabId: 'browser-1', waiting: false },
    ]);
    expect(app.nudges).toEqual([true, false]);
  });

  it('tells the harness to use its own browser while no panel can be shown', async () => {
    const app = setup();
    const bridge = new BrowserBridge(app.host);
    const answers: Record<string, unknown>[] = [];
    const connection = bridge.connect({ send: (message) => answers.push(message) });
    connection.receive(JSON.stringify({ id: 1, method: 'Mu.hello', params: { version: 1 } }));
    app.host.setAvailable(true);
    connection.receive(JSON.stringify({ id: 2, method: 'Mu.hello', params: { version: 1 } }));
    await vi.advanceTimersByTimeAsync(1);
    expect(answers.map((answer) => answer.result)).toEqual([
      { embedded: false, version: 1 },
      { embedded: true, version: 1 },
    ]);
  });
});
