import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import type { BrowserPanelRequest } from '@/common/kyrn/browserBridge';
import type { BrowserRunEvent } from '@/common/kyrn/browserRun';
import {
  browserNow,
  openBrowserPage,
  resetBrowserStoreForTest,
} from '@/renderer/pages/conversation/Preview/browser/browserStore';
import MuBrowserHost from '@/renderer/pages/conversation/Preview/browser/muBrowser/MuBrowserHost';
import { registerWebview } from '@/renderer/pages/conversation/Preview/browser/muBrowser/webviews';
import {
  onPreviewOpened,
  type OpenedIn,
  type PreviewOpener,
} from '@/renderer/pages/conversation/Preview/context/previewOpeners';

const wires = vi.hoisted(() => ({
  request: undefined as ((request: unknown) => void) | undefined,
  events: undefined as ((event: unknown) => void) | undefined,
  answer: vi.fn(async () => undefined),
  panel: vi.fn(async () => undefined),
}));
vi.mock('@/common/kyrn/browserBridge', () => ({
  kyrnBrowserBridge: {
    request: {
      on: (handler: (request: unknown) => void) => ((wires.request = handler), () => (wires.request = undefined)),
    },
    events: { on: (handler: (event: unknown) => void) => ((wires.events = handler), () => undefined) },
    snapshot: { invoke: async () => [] },
    answer: { invoke: wires.answer },
    panel: { invoke: wires.panel },
    confirm: { invoke: vi.fn() },
    control: { invoke: vi.fn() },
  },
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

const ask = (request: BrowserPanelRequest) => act(async () => wires.request?.(request));
const tell = (event: BrowserRunEvent) => act(async () => wires.events?.(event));

/** A page mu's run opened earlier, and (optionally) another page the person has in front. */
function seedRunPage(options: { personInFront?: boolean } = {}): string {
  const runPage = openBrowserPage('https://a.test/done', { by: 'agent', muRun: 'r1' }) as string;
  if (options.personInFront) openBrowserPage('https://person.test/', { by: 'user' });
  heard = [];
  return runPage;
}

/** What the work panel hears: who opened something, and in which tab it shows. */
let heard: [PreviewOpener, OpenedIn][] = [];
let stopHearing = () => {};
beforeEach(() => {
  heard = [];
  stopHearing = onPreviewOpened((by, where) => heard.push([by, where]));
});

afterEach(() => {
  stopHearing();
  cleanup();
  vi.clearAllMocks();
  resetBrowserStoreForTest();
});

describe('the browser, as it answers the main process', () => {
  it('says when the browser can be shown, and when no longer', () => {
    const view = render(<MuBrowserHost conversationId='c1' />);
    expect(wires.panel).toHaveBeenLastCalledWith({ available: true });
    view.unmount();
    expect(wires.panel).toHaveBeenLastCalledWith({ available: false });
  });

  it('offers nothing without a conversation', () => {
    render(<MuBrowserHost />);
    expect(wires.panel).not.toHaveBeenCalled();
  });

  it('opens a page of its own for a new run in the browser, and answers with it at once', async () => {
    render(<MuBrowserHost conversationId='c1' />);
    await ask({ kind: 'open', requestId: 'r1', url: 'about:blank' });

    const { tabs, activeTabId } = browserNow();
    expect(tabs).toHaveLength(1);
    expect(tabs[0]).toMatchObject({ url: 'about:blank', muRun: 'r1' });
    expect(activeTabId).toBe(tabs[0].id);
    // The agent's open: the work panel marks its 浏览器 tab, and stays as it is.
    expect(heard).toEqual([['agent', 'browser']]);
    expect(wires.answer).toHaveBeenCalledWith({
      kind: 'open',
      requestId: 'r1',
      tabId: tabs[0].id,
      conversationId: 'c1',
    });
  });

  it('hands out the conversation’s finished mu page again instead of piling up pages', async () => {
    const runPage = seedRunPage({ personInFront: true });
    render(<MuBrowserHost conversationId='c1' />);
    await tell({ type: 'started', tabId: runPage, conversationId: 'c1', url: 'about:blank', at: 1 });
    await tell({ type: 'finished', tabId: runPage, status: 'done', at: 2 });

    await ask({ kind: 'open', requestId: 'r2', url: 'about:blank' });
    expect(browserNow().tabs).toHaveLength(2);
    expect(browserNow().activeTabId).toBe(runPage);
    // Blanking the page is the main process's job alone: a second navigation from here could undo mu's first.
    expect(browserNow().tabs[0].url).toBe('https://a.test/done');
    expect(heard).toEqual([['agent', 'browser']]);
    expect(wires.answer).toHaveBeenCalledWith({ kind: 'open', requestId: 'r2', tabId: runPage, conversationId: 'c1' });
    await tell({ type: 'closed', tabId: runPage });
  });

  it('refuses when the browser is full, in words the model can act on', async () => {
    for (let index = 0; index < 10; index++) openBrowserPage(`https://site${index}.test/`);
    heard = [];
    render(<MuBrowserHost conversationId='c1' />);
    await ask({ kind: 'open', requestId: 'r3', url: 'about:blank' });
    expect(browserNow().tabs).toHaveLength(10);
    expect(browserNow().tabs.some((tab) => tab.muRun)).toBe(false);
    expect(heard).toEqual([]);
    expect(wires.answer).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'open', requestId: 'r3', error: expect.stringContaining('full') })
    );
  });

  it('brings a page into view before giving it the keyboard, and says honestly whether it holds it', async () => {
    const runPage = seedRunPage({ personInFront: true });
    const view = document.createElement('div') as unknown as Electron.WebviewTag;
    (view as unknown as HTMLElement).tabIndex = 0;
    document.body.append(view as unknown as HTMLElement);
    registerWebview(runPage, view);
    render(<MuBrowserHost conversationId='c1' />);

    await ask({ kind: 'keyboard', requestId: 'k1', tabId: runPage });
    await waitFor(() => expect(wires.answer).toHaveBeenCalledWith({ kind: 'keyboard', requestId: 'k1', held: true }));
    // mu types where the person can see it: the page comes to the front, and the work panel comes up on 浏览器.
    expect(browserNow().activeTabId).toBe(runPage);
    expect(heard).toEqual([['agent-watched', 'browser']]);
    expect(document.activeElement).toBe(view);

    await ask({ kind: 'keyboard', requestId: 'k2', tabId: 'no-such-tab' });
    await waitFor(() => expect(wires.answer).toHaveBeenCalledWith({ kind: 'keyboard', requestId: 'k2', held: false }));
    (view as unknown as HTMLElement).remove();
  });

  it('types into a page already in view without moving anything', async () => {
    const runPage = seedRunPage();
    const view = document.createElement('div') as unknown as Electron.WebviewTag;
    (view as unknown as HTMLElement).tabIndex = 0;
    document.body.append(view as unknown as HTMLElement);
    registerWebview(runPage, view);
    render(<MuBrowserHost conversationId='c1' />);

    await ask({ kind: 'keyboard', requestId: 'k1', tabId: runPage });
    await waitFor(() => expect(wires.answer).toHaveBeenCalledWith({ kind: 'keyboard', requestId: 'k1', held: true }));
    expect(browserNow().activeTabId).toBe(runPage);
    expect(heard).toEqual([]);
    (view as unknown as HTMLElement).remove();
  });

  it('brings the page forward and shows the question when mu asks for a confirmation', async () => {
    const runPage = seedRunPage({ personInFront: true });
    const view = render(<MuBrowserHost conversationId='c1' />);
    await ask({ kind: 'attention', tabId: runPage, waiting: true });
    expect(browserNow().activeTabId).toBe(runPage);
    expect(heard).toEqual([['agent-watched', 'browser']]);

    await tell({ type: 'started', tabId: runPage, conversationId: 'c1', url: 'about:blank', at: 1 });
    await tell({
      type: 'confirm',
      tabId: runPage,
      confirm: { id: 'q1', label: 'Pay now', url: 'https://shop.test/pay', askedAt: 1, deadline: Date.now() + 60_000 },
    });
    expect(view.baseElement.textContent).toContain('Pay now');
    await tell({ type: 'confirmed', tabId: runPage, id: 'q1', allowed: false });
    await waitFor(() => expect(view.baseElement.textContent).not.toContain('Pay now'));
    await tell({ type: 'closed', tabId: runPage });
  });

  it('only asks for attention when mu is waiting on the person', async () => {
    const runPage = seedRunPage({ personInFront: true });
    const inFront = browserNow().activeTabId;
    render(<MuBrowserHost conversationId='c1' />);
    await ask({ kind: 'attention', tabId: runPage, waiting: false });
    expect(browserNow().activeTabId).toBe(inFront);
    expect(heard).toEqual([]);
  });
});
