import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BrowserRunEvent } from '@/common/kyrn/browserRun';
import { BrowserBridge, detachCause, type BrowserHost, type PageHandle } from '@process/services/muBrowser/bridge';

type Message = { id?: number; result?: Record<string, unknown>; error?: { message: string }; method?: string } & Record<
  string,
  unknown
>;

const subscribe =
  <T>(set: Set<T>) =>
  (listener: T) => {
    set.add(listener);
    return () => set.delete(listener);
  };

/** A page with a fake debugger: records what reaches it, and lets a test take it away. */
function fakePage() {
  const listeners = {
    event: new Set<(method: string, params: unknown) => void>(),
    detached: new Set<(reason: string) => void>(),
    gone: new Set<() => void>(),
    input: new Set<() => void>(),
  };
  const sent: { method: string; params: Record<string, unknown> }[] = [];
  const held: { method: string; resolve(value: unknown): void; reject(error: Error): void }[] = [];
  const state = { attached: false, detachCalls: 0, focused: 0, hold: false, refuseAttach: '' };
  const page: PageHandle = {
    attach: () => {
      if (state.refuseAttach) throw new Error(state.refuseAttach);
      state.attached = true;
    },
    detach: () => {
      state.detachCalls++;
      state.attached = false;
    },
    send: (method, params) => {
      sent.push({ method, params });
      if (state.hold) return new Promise((resolve, reject) => held.push({ method, resolve, reject }));
      if (method === 'Runtime.evaluate') return Promise.resolve({ result: { value: 'complete' } });
      if (method === 'Page.bad') return Promise.reject(new Error('page said no'));
      return Promise.resolve({});
    },
    onEvent: subscribe(listeners.event),
    onDetached: subscribe(listeners.detached),
    onGone: subscribe(listeners.gone),
    onInput: subscribe(listeners.input),
    focus: () => {
      state.focused++;
    },
  };
  return {
    page,
    sent,
    held,
    state,
    listeners,
    emit: (method: string, params: unknown) => listeners.event.forEach((listener) => listener(method, params)),
    takeAway: (reason: string) => [...listeners.detached].forEach((listener) => listener(reason)),
    closeTab: () => [...listeners.gone].forEach((listener) => listener()),
    input: () => [...listeners.input].forEach((listener) => listener()),
  };
}

function setup(options: { openDelayMs?: number } = {}) {
  const events: BrowserRunEvent[] = [];
  const attention: [string, boolean][] = [];
  const pages: ReturnType<typeof fakePage>[] = [];
  const requests: { conversation?: string; session?: string; url: string }[] = [];
  let clock = 1_000;
  let ids = 0;
  let nextTab: string | undefined;
  const keyboard = { held: true, asked: [] as string[], error: '' };
  const host: BrowserHost = {
    keyboard: async (tabId) => {
      keyboard.asked.push(tabId);
      if (keyboard.error) throw new Error(keyboard.error);
      return keyboard.held;
    },
    openTab: async (request) => {
      requests.push(request);
      if (options.openDelayMs) await new Promise((resolve) => setTimeout(resolve, options.openDelayMs));
      const created = fakePage();
      pages.push(created);
      const tabId = nextTab ?? `tab-${pages.length}`;
      nextTab = undefined;
      return { tabId, conversationId: request.conversation ?? 'focused', page: created.page };
    },
    publish: (event) => events.push(event),
    attention: (tabId, waiting) => attention.push([tabId, waiting]),
  };
  const bridge = new BrowserBridge(host, {
    now: () => clock,
    id: () => `id-${++ids}`,
    openTabTimeoutMs: 500,
  });

  const client = () => {
    const inbox: Message[] = [];
    let nextId = 0;
    const connection = bridge.connect({ send: (message) => inbox.push(message as Message) });
    const call = async (method: string, params: Record<string, unknown> = {}, sessionId?: string) => {
      const id = ++nextId;
      connection.receive(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
      await vi.waitFor(() => expect(inbox.some((message) => message.id === id)).toBe(true));
      return inbox.find((message) => message.id === id) as Message;
    };
    /** Sends without waiting for the answer. */
    const post = (method: string, params: Record<string, unknown> = {}, sessionId?: string) => {
      const id = ++nextId;
      connection.receive(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
      return id;
    };
    const open = async () => {
      const targetId = (await call('Target.createTarget', { url: 'about:blank' })).result?.targetId as string;
      const sessionId = (await call('Target.attachToTarget', { targetId, flatten: true })).result?.sessionId as string;
      return { targetId, sessionId };
    };
    return { inbox, call, post, open, close: () => connection.close() };
  };

  return {
    bridge,
    events,
    keyboard,
    attention,
    pages,
    requests,
    client,
    tick: (ms: number) => {
      clock += ms;
    },
    reuseTab: (tabId: string) => {
      nextTab = tabId;
    },
    of: (type: BrowserRunEvent['type']) => events.filter((event) => event.type === type),
  };
}

describe('the browser bridge', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it('shakes hands as the app, not as a plain browser', async () => {
    const harness = setup().client();
    expect((await harness.call('Mu.hello', { version: 1 })).result).toEqual({ embedded: true, version: 1 });
    expect((await harness.call('Browser.getVersion')).error?.message).toContain("wasn't found");
  });

  it('opens a tab in the conversation the harness names, or the focused one', async () => {
    const app = setup();
    const named = app.client();
    await named.call('Mu.hello', { version: 1, conversation: 'conv-7', session: 'acp-1' });
    await named.call('Target.createTarget', { url: 'about:blank' });
    expect(app.requests[0]).toEqual({ conversation: 'conv-7', session: 'acp-1', url: 'about:blank' });

    const anonymous = app.client();
    await anonymous.call('Mu.hello', { version: 1, conversation: '../../etc' });
    await anonymous.call('Target.createTarget', { url: 'file:///etc/passwd' });
    expect(app.requests[1]).toEqual({ conversation: undefined, session: undefined, url: 'about:blank' });
    expect(app.of('started')).toEqual([
      { type: 'started', tabId: 'tab-1', conversationId: 'conv-7', url: 'about:blank', at: 1_000 },
      { type: 'started', tabId: 'tab-2', conversationId: 'focused', url: 'about:blank', at: 1_000 },
    ]);
  });

  it('attaches the debugger once and passes page methods through, results and errors alike', async () => {
    const app = setup();
    const harness = app.client();
    const { targetId, sessionId } = await harness.open();
    expect(app.pages[0].state.attached).toBe(true);
    expect((await harness.call('Target.attachToTarget', { targetId, flatten: true })).result).toEqual({ sessionId });

    const answer = await harness.call('Runtime.evaluate', { expression: 'document.readyState' }, sessionId);
    expect(answer.result).toEqual({ result: { value: 'complete' } });
    expect(answer.sessionId).toBe(sessionId);
    expect((await harness.call('Page.bad', {}, sessionId)).error?.message).toBe('page said no');
    expect(app.pages[0].sent.map((entry) => entry.method)).toEqual(['Runtime.evaluate', 'Page.bad']);
  });

  it('keeps the real size of the panel, and keeps focus emulation', async () => {
    const app = setup();
    const harness = app.client();
    const { sessionId } = await harness.open();
    const metrics = { width: 1120, height: 780, deviceScaleFactor: 1, mobile: false };
    expect((await harness.call('Emulation.setDeviceMetricsOverride', metrics, sessionId)).result).toEqual({});
    expect((await harness.call('Emulation.setFocusEmulationEnabled', { enabled: true }, sessionId)).result).toEqual({});
    expect(app.pages[0].sent).toEqual([{ method: 'Emulation.setFocusEmulationEnabled', params: { enabled: true } }]);
  });

  it('refuses what would leave the page: other schemes, other targets, downloads switched back on', async () => {
    const app = setup();
    const harness = app.client();
    const { sessionId } = await harness.open();
    const answers = await Promise.all(
      (
        [
          ['Page.navigate', { url: 'file:///etc/passwd' }],
          ['Target.getTargets', {}],
          ['Browser.setDownloadBehavior', { behavior: 'allow' }],
          ['DOM.setFileInputFiles', { files: ['/etc/passwd'] }],
        ] as const
      ).map(([method, params]) => harness.call(method, params, sessionId))
    );
    expect(answers.every((answer) => answer.error !== undefined)).toBe(true);
    expect(app.pages[0].sent).toEqual([]);
    expect((await harness.call('Target.attachToTarget', { targetId: 'x', flatten: true })).error).toBeDefined();
    expect((await harness.call('Runtime.evaluate', {}, 'no-such-session')).error?.message).toContain('not found');
  });

  describe('the keyboard', () => {
    it('is put on the page before anything is typed, and only then', async () => {
      const app = setup();
      const harness = app.client();
      const { sessionId } = await harness.open();
      await harness.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: 1, y: 1 }, sessionId);
      await harness.call('Runtime.evaluate', { expression: '1' }, sessionId);
      expect(app.keyboard.asked).toEqual([]);

      await harness.call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a' }, sessionId);
      await harness.call('Input.insertText', { text: 'weakmap' }, sessionId);
      await harness.call('Input.imeSetComposition', { text: 'w', selectionStart: 1, selectionEnd: 1 }, sessionId);
      expect(app.keyboard.asked).toEqual(['tab-1', 'tab-1', 'tab-1']);
      expect(app.pages[0].sent.map((entry) => entry.method)).toEqual([
        'Input.dispatchMouseEvent',
        'Runtime.evaluate',
        'Input.dispatchKeyEvent',
        'Input.insertText',
        'Input.imeSetComposition',
      ]);
    });

    it('types nothing at all when the page cannot be given the keyboard', async () => {
      const app = setup();
      const harness = app.client();
      const { sessionId } = await harness.open();
      app.keyboard.held = false;
      const answer = await harness.call('Input.insertText', { text: 'into the message box?' }, sessionId);
      expect(answer.error?.message).toContain('does not hold the keyboard');
      app.keyboard.error = 'the window is gone';
      expect(
        (await harness.call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter' }, sessionId)).error?.message
      ).toBe('the window is gone');
      expect(app.pages[0].sent).toEqual([]);
      // A refused key is no longer "in flight": the person's next touch counts again.
      app.tick(1_000);
      app.pages[0].input();
      expect(app.of('control')).toHaveLength(1);
    });
  });

  it('forwards debugger events with the session they belong to', async () => {
    const app = setup();
    const harness = app.client();
    const { sessionId } = await harness.open();
    app.pages[0].emit('Page.frameNavigated', { frame: { id: 'f' } });
    expect(harness.inbox.at(-1)).toEqual({ method: 'Page.frameNavigated', params: { frame: { id: 'f' } }, sessionId });
  });

  it('lets a connection touch only the tabs it created', async () => {
    const app = setup();
    const owner = app.client();
    const intruder = app.client();
    const { targetId, sessionId } = await owner.open();

    expect((await intruder.call('Target.attachToTarget', { targetId, flatten: true })).error?.message).toBe(
      'No target with given id found'
    );
    expect((await intruder.call('Target.closeTarget', { targetId })).error).toBeDefined();
    expect((await intruder.call('Runtime.evaluate', { expression: '1' }, sessionId)).error?.message).toContain(
      'not found'
    );
    expect((await intruder.call('Mu.control', {}, sessionId)).result).toEqual({ paused: false, stop: false });
    expect((await intruder.call('Mu.confirm', { label: 'Pay', url: 'https://a.test', targetId })).result).toEqual({
      allowed: false,
    });
    intruder.post('Mu.step', { step: 9, kind: 'click', action: 'forged', targetId });
    await intruder.call('Mu.hello', { version: 1 });

    expect(app.pages[0].sent).toEqual([]);
    expect(app.of('step')).toEqual([]);
    expect(app.of('confirm')).toEqual([]);
    expect(app.pages[0].state.attached).toBe(true);
  });

  it('closing the target detaches, leaves the tab open and marks the run as finished', async () => {
    const app = setup();
    const harness = app.client();
    const { targetId, sessionId } = await harness.open();
    expect((await harness.call('Target.closeTarget', { targetId })).result).toEqual({ success: true });
    expect(app.pages[0].state.detachCalls).toBe(1);
    expect(app.of('closed')).toEqual([]);
    expect(app.of('finished')).toEqual([
      { type: 'finished', tabId: 'tab-1', status: 'ended', reason: undefined, at: 1_000 },
    ]);
    expect(app.bridge.snapshot()).toMatchObject([{ tabId: 'tab-1', phase: 'finished', status: 'ended' }]);
    expect((await harness.call('Runtime.evaluate', {}, sessionId)).error).toBeDefined();
    // The harness's own detach is not "someone took the page away".
    app.pages[0].takeAway('target closed');
    expect(app.of('finished')).toHaveLength(1);
  });

  it('shows the harness’s own verdict and goal when it reports them (Mu.run)', async () => {
    const app = setup();
    const harness = app.client();
    const { targetId } = await harness.open();
    await harness.call('Mu.run', { state: 'started', goal: 'find the  opening\nhours', url: 'https://a.test/' });
    await harness.call('Mu.run', { state: 'finished', status: 'blocked', reason: 'three actions changed nothing' });
    await harness.call('Target.closeTarget', { targetId });
    expect(app.of('goal')).toEqual([
      { type: 'goal', tabId: 'tab-1', goal: 'find the opening hours', url: 'https://a.test/' },
    ]);
    expect(app.of('finished')).toMatchObject([{ status: 'blocked', reason: 'three actions changed nothing' }]);
  });

  it('passes the code and params of Mu.run on, so the step bar can say it in the app language', async () => {
    const app = setup();
    const harness = app.client();
    const { targetId } = await harness.open();
    await harness.call('Mu.run', { state: 'started', goal: 'book a table', url: 'https://a.test/' });
    await harness.call('Mu.run', {
      state: 'finished',
      status: 'blocked',
      reason: 'no judge could choose an action (error:timeout)',
      code: 'no_judge',
      params: { judgeReason: 'error:timeout', nested: { dropped: true } },
    });
    await harness.call('Target.closeTarget', { targetId });
    const [finished] = app.of('finished');
    expect(finished).toMatchObject({ status: 'blocked', code: 'no_judge', params: { judgeReason: 'error:timeout' } });
    expect(finished.params).not.toHaveProperty('nested');
  });

  describe('pause, resume, stop', () => {
    it('answers Mu.control from the person’s buttons and starts every run fresh', async () => {
      const app = setup();
      const harness = app.client();
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: false });
      const first = await harness.open();

      app.bridge.control('tab-1', 'pause');
      expect((await harness.call('Mu.control')).result).toEqual({ paused: true, stop: false });
      app.bridge.control('tab-1', 'resume');
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: false });
      app.bridge.control('tab-1', 'stop');
      app.bridge.control('tab-1', 'resume');
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: true });

      await harness.call('Target.closeTarget', { targetId: first.targetId });
      expect(app.of('finished')).toMatchObject([{ status: 'stopped' }]);
      // Buttons of a finished run do nothing.
      app.bridge.control('tab-1', 'pause');

      await harness.open();
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: false });
      expect(app.of('control').map((event) => [event.tabId, event.paused, event.stopRequested])).toEqual([
        ['tab-1', true, false],
        ['tab-1', false, false],
        ['tab-1', false, true],
      ]);
    });

    it('takes over: pauses and puts the keyboard on the page', async () => {
      const app = setup();
      const harness = app.client();
      await harness.open();
      app.bridge.control('tab-1', 'takeover');
      expect(app.pages[0].state.focused).toBe(1);
      expect(app.keyboard.asked).toEqual(['tab-1']);
      expect((await harness.call('Mu.control')).result).toEqual({ paused: true, stop: false });
      expect(app.of('control').at(-1)).toMatchObject({ paused: true, pausedBy: 'takeover' });
    });

    it('pauses by itself when the person touches the page, but not for the loop’s own input', async () => {
      const app = setup();
      const harness = app.client();
      const { sessionId } = await harness.open();

      // The loop's click reaches the page the same way a person's would.
      app.pages[0].state.hold = true;
      const click = harness.post('Input.dispatchMouseEvent', { type: 'mousePressed', x: 1, y: 1 }, sessionId);
      await vi.waitFor(() => expect(app.pages[0].held).toHaveLength(1));
      app.pages[0].input();
      app.pages[0].held[0].resolve({});
      await vi.waitFor(() => expect(harness.inbox.some((message) => message.id === click)).toBe(true));
      app.tick(100);
      app.pages[0].input();
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: false });

      app.tick(400);
      app.pages[0].input();
      expect((await harness.call('Mu.control')).result).toEqual({ paused: true, stop: false });
      expect(app.of('control')).toEqual([
        { type: 'control', tabId: 'tab-1', paused: true, pausedBy: 'interaction', stopRequested: false },
      ]);
    });

    it('with several runs on one connection and no address, gives the careful answer', async () => {
      const app = setup();
      const harness = app.client();
      const first = await harness.open();
      const second = await harness.open();
      app.bridge.control('tab-1', 'pause');
      expect((await harness.call('Mu.control')).result).toEqual({ paused: true, stop: false });
      expect((await harness.call('Mu.control', {}, second.sessionId)).result).toEqual({ paused: false, stop: false });
      expect((await harness.call('Mu.control', { targetId: first.targetId })).result).toEqual({
        paused: true,
        stop: false,
      });
    });
  });

  describe('when the page is taken away', () => {
    it('answers calls that were waiting with an error, and tells the loop to stop', async () => {
      const app = setup();
      const harness = app.client();
      const { targetId, sessionId } = await harness.open();
      app.pages[0].state.hold = true;
      const waiting = harness.post('Runtime.evaluate', { expression: 'slow()' }, sessionId);

      app.pages[0].takeAway('replaced_with_devtools');

      const answer = harness.inbox.find((message) => message.id === waiting);
      expect(answer?.error?.message).toContain('no longer attached');
      // Chromium's own reason is not what the person reads: the step bar gets a code it can say in their language.
      expect(app.of('finished')).toMatchObject([{ status: 'detached', reason: 'devtools' }]);
      expect((await harness.call('Runtime.evaluate', {}, sessionId)).error?.message).toBe(
        'The page is no longer attached'
      );
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: true });
      // A late answer from the page is not delivered twice.
      app.pages[0].held[0].resolve({ result: { value: 1 } });
      await Promise.resolve();
      expect(harness.inbox.filter((message) => message.id === waiting)).toHaveLength(1);
      expect((await harness.call('Target.closeTarget', { targetId })).result).toEqual({ success: true });
      expect(app.of('finished')).toHaveLength(1);
    });

    it('forgets a tab the person closed', async () => {
      const app = setup();
      const harness = app.client();
      const { sessionId } = await harness.open();
      app.pages[0].closeTab();
      expect(app.events.slice(-2)).toMatchObject([
        { type: 'finished', status: 'detached', reason: 'tab_closed' },
        { type: 'closed', tabId: 'tab-1' },
      ]);
      expect(app.bridge.snapshot()).toEqual([]);
      expect((await harness.call('Runtime.evaluate', {}, sessionId)).error).toBeDefined();
      expect((await harness.call('Mu.control')).result).toEqual({ paused: false, stop: true });
    });

    it('lets go of every page when the connection drops, and refuses what was being asked', async () => {
      const app = setup();
      const harness = app.client();
      await harness.open();
      const asked = harness.post('Mu.confirm', { label: 'Delete draft', url: 'https://a.test/mail' });
      await vi.waitFor(() => expect(app.of('confirm')).toHaveLength(1));

      harness.close();

      expect(app.pages[0].state.detachCalls).toBe(1);
      expect(app.of('confirmed')).toMatchObject([{ allowed: false }]);
      expect(app.of('finished')).toMatchObject([{ status: 'detached', reason: 'connection' }]);
      expect(harness.inbox.some((message) => message.id === asked)).toBe(false);
      // The tab is still there for the person, finished; closing it later cleans up.
      expect(app.bridge.snapshot()).toMatchObject([{ tabId: 'tab-1', phase: 'finished' }]);
      app.pages[0].closeTab();
      expect(app.bridge.snapshot()).toEqual([]);
    });

    it('says why when the debugger cannot attach', async () => {
      const app = setup();
      const harness = app.client();
      const targetId = (await harness.call('Target.createTarget', { url: 'about:blank' })).result?.targetId;
      app.pages[0].state.refuseAttach = 'Another debugger is already attached to this page';
      expect((await harness.call('Target.attachToTarget', { targetId, flatten: true })).error?.message).toContain(
        'Another debugger'
      );
    });

    it('does not wait for ever for a tab that never opens', async () => {
      const app = setup({ openDelayMs: 5_000 });
      const harness = app.client();
      const id = harness.post('Target.createTarget', { url: 'about:blank' });
      await vi.advanceTimersByTimeAsync(600);
      expect(harness.inbox.find((message) => message.id === id)?.error?.message).toContain('did not open a tab');
    });
  });

  describe('confirmation', () => {
    it('asks the person, as plain text, and passes their answer on', async () => {
      const app = setup();
      const harness = app.client();
      await harness.open();
      const asked = harness.post('Mu.confirm', {
        label: `<img src=x onerror=alert(1)>${String.fromCharCode(7)}Pay now`,
        url: 'https://shop.test/pay',
      });
      await vi.waitFor(() => expect(app.of('confirm')).toHaveLength(1));
      const [event] = app.of('confirm');
      expect(event).toMatchObject({
        tabId: 'tab-1',
        confirm: { label: '<img src=x onerror=alert(1)> Pay now', url: 'https://shop.test/pay', deadline: 121_000 },
      });
      expect(app.attention).toEqual([['tab-1', true]]);

      const confirmId = event.type === 'confirm' ? event.confirm.id : '';
      app.bridge.answerConfirm('tab-1', 'not-this-one', true);
      expect(harness.inbox.some((message) => message.id === asked)).toBe(false);
      app.bridge.answerConfirm('tab-1', confirmId, true);
      await vi.waitFor(() =>
        expect(harness.inbox.find((message) => message.id === asked)?.result).toEqual({ allowed: true })
      );
      expect(app.attention.at(-1)).toEqual(['tab-1', false]);
    });

    it('counts silence as no after 120 seconds, and the run ends as needing confirmation', async () => {
      const app = setup();
      const harness = app.client();
      const { targetId } = await harness.open();
      const asked = harness.post('Mu.confirm', { label: 'Delete', url: 'https://a.test' });
      await vi.waitFor(() => expect(app.of('confirm')).toHaveLength(1));
      await vi.advanceTimersByTimeAsync(119_000);
      expect(harness.inbox.some((message) => message.id === asked)).toBe(false);
      await vi.advanceTimersByTimeAsync(1_500);
      expect(harness.inbox.find((message) => message.id === asked)?.result).toEqual({ allowed: false });
      await harness.call('Target.closeTarget', { targetId });
      expect(app.of('finished')).toMatchObject([{ status: 'needs_confirmation' }]);
    });

    it('takes stop as a no', async () => {
      const app = setup();
      const harness = app.client();
      await harness.open();
      const asked = harness.post('Mu.confirm', { label: 'Send', url: 'https://a.test' });
      await vi.waitFor(() => expect(app.of('confirm')).toHaveLength(1));
      app.bridge.control('tab-1', 'stop');
      await vi.waitFor(() =>
        expect(harness.inbox.find((message) => message.id === asked)?.result).toEqual({ allowed: false })
      );
    });

    it('refuses when there is no run to ask about', async () => {
      const harness = setup().client();
      expect((await harness.call('Mu.confirm', { label: 'Pay', url: 'https://a.test' })).result).toEqual({
        allowed: false,
      });
    });
  });

  it('records steps as plain text for the step bar', async () => {
    const app = setup();
    const harness = app.client();
    await harness.open();
    await harness.call('Mu.step', {
      step: 3,
      kind: 'click',
      action: `Search\n${'x'.repeat(400)}`,
      url: 'https://a.test/results',
      probability: 0.92,
      pageChanged: true,
    });
    await harness.call('Mu.step', { step: 'NaN', kind: 7, action: null, probability: 4 });
    const steps = app.of('step').map((event) => (event.type === 'step' ? event.step : undefined));
    expect(steps[0]).toMatchObject({ step: 3, kind: 'click', url: 'https://a.test/results', probability: 0.92 });
    expect(steps[0]?.action).toHaveLength(300);
    expect(steps[0]?.action.startsWith('Search x')).toBe(true);
    expect(steps[1]).toMatchObject({ step: 0, kind: '', action: '', probability: undefined, pageChanged: undefined });
  });

  it('starts a reused tab from nothing', async () => {
    const app = setup();
    const harness = app.client();
    const first = await harness.open();
    await harness.call('Mu.step', { step: 1, kind: 'click', action: 'Search', url: 'https://a.test' });
    await harness.call('Target.closeTarget', { targetId: first.targetId });

    app.reuseTab('tab-1');
    await harness.open();
    expect(app.bridge.snapshot()).toMatchObject([{ tabId: 'tab-1', phase: 'running', steps: [] }]);
    // The old page handle no longer speaks for the tab.
    app.pages[0].closeTab();
    expect(app.bridge.snapshot()).toHaveLength(1);
  });

  it('passes on what the app refused on the page’s behalf', async () => {
    const app = setup();
    const harness = app.client();
    await harness.open();
    app.bridge.notice('tab-1', { kind: 'download', detail: 'report.pdf' });
    app.bridge.notice('no-such-tab', { kind: 'download', detail: 'x' });
    expect(app.of('notice')).toEqual([
      { type: 'notice', tabId: 'tab-1', notice: { kind: 'download', detail: 'report.pdf', at: 1_000 } },
    ]);
  });
});

describe('why a page was taken away', () => {
  it('turns Electron’s and Chromium’s detach reasons into the codes the step bar translates', () => {
    expect(detachCause('target closed')).toBe('tab_closed');
    expect(detachCause('target_closed')).toBe('tab_closed');
    expect(detachCause('canceled_by_user')).toBe('devtools');
    expect(detachCause('replaced_with_devtools')).toBe('devtools');
    expect(detachCause('Render process gone.')).toBe('crashed');
  });

  it('passes on a reason it does not know as it came', () => {
    expect(detachCause('Something new from Chromium')).toBe('Something new from Chromium');
  });
});
