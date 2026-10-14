import { randomUUID } from 'node:crypto';
import {
  CONFIRM_TIMEOUT_MS,
  plainText,
  reduceBrowserRuns,
  runEnding,
  type BrowserControlAction,
  type BrowserRunEnding,
  type BrowserRunEvent,
  type BrowserRunNotice,
  type BrowserRuns,
  type BrowserRunState,
  type BrowserRunStatus,
} from '@/common/kyrn/browserRun';
import { FRESH, nextControl, personsInput, type ControlInput, type ControlState, type InputWindow } from './control';
import {
  finalStatus,
  identifier,
  isSyntheticInput,
  needsKeyboard,
  parseRequest,
  PROTOCOL_VERSION,
  route,
  webAddress,
} from './protocol';
import type { BridgeRequest } from './protocol';

/** One browsing page, as much of it as the bridge needs. The real one wraps a `<webview>`'s `webContents`. */
export type PageHandle = {
  /** Attaches the debugger. Throws a readable error when it cannot (DevTools already open on that page). */
  attach(): void;
  detach(): void;
  send(method: string, params: Record<string, unknown>): Promise<unknown>;
  /** Every subscription returns the function that ends it. */
  onEvent(listener: (method: string, params: unknown) => void): () => void;
  /** The debugger was taken away by someone else: DevTools opened on the page, or the page crashed. */
  onDetached(listener: (reason: string) => void): () => void;
  /** The tab was closed. */
  onGone(listener: () => void): () => void;
  /** A mouse button or a key went down on the page, whoever pressed it. */
  onInput(listener: () => void): () => void;
  focus(): void;
};

export type OpenedTab = { tabId: string; conversationId: string; page: PageHandle };

/** The app around the bridge: the browser panel that opens tabs, and whoever shows the runs. */
export type BrowserHost = {
  /**
   * False while no browser panel can be shown (no conversation on screen). The handshake then says so, and the
   * harness uses its own browser instead of failing on a tab that cannot open.
   */
  readonly available?: boolean;
  /** Opens a tab in the conversation's browser panel (opening the panel first) and resolves once its page exists. */
  openTab(request: { conversation?: string; session?: string; url: string }): Promise<OpenedTab>;
  publish(event: BrowserRunEvent): void;
  /** A confirmation is waiting (or no longer): bring its conversation and tab to the person's attention. */
  attention?(tabId: string, waiting: boolean): void;
  /**
   * Puts the keyboard focus of the app's window on this tab's page and says whether it is there now. Chromium sends
   * protocol key events and inserted text to whatever holds that focus, not to the page they were addressed to:
   * without this, the loop's typing lands in the app's own message box when the person has clicked back into it.
   */
  keyboard(tabId: string): Promise<boolean>;
};

export type Peer = { send(message: Record<string, unknown>): void };

export type BridgeOptions = {
  now?: () => number;
  id?: () => string;
  openTabTimeoutMs?: number;
  confirmTimeoutMs?: number;
};

type PendingConfirm = { id: string; settle(allowed: boolean): void };

type Run = {
  readonly targetId: string;
  readonly tabId: string;
  readonly page: PageHandle;
  readonly connection: Connection;
  readonly createdAt: number;
  sessionId?: string;
  control: ControlState;
  input: InputWindow;
  /** The harness closed it, or it was lost: either way no page call goes through any more. */
  over: boolean;
  /** Lost rather than closed: the next `Mu.control` says stop, so the loop ends instead of failing on a dead page. */
  lost: boolean;
  announced: boolean;
  reported?: { status: unknown; reason?: string; ending: BrowserRunEnding };
  confirmationRefused: boolean;
  lastUrl: string;
  confirm?: PendingConfirm;
  readonly calls: Map<number, (message: string) => void>;
  readonly subscriptions: (() => void)[];
  endGoneWatch?: () => void;
};

type Connection = {
  readonly peer: Peer;
  readonly runs: Map<string, Run>;
  conversation?: string;
  session?: string;
  latest?: Run;
  open: boolean;
};

const NO_RUN: ControlState = FRESH;

/**
 * Why a page was taken away from its run: the code a finished `detached` run carries in `reason`. The main process
 * has no say in the person's language, so it sends the code and the step bar says it in theirs
 * (`preview.muBrowser.detachReason.*`, the list is `DETACH_CAUSES` in the step bar's format.ts).
 */
export type DetachCause = 'tab_closed' | 'connection' | 'devtools' | 'crashed';

/**
 * Electron's debugger `detach` reason (`target closed`, or Chromium's `Inspector.detached` reason: `target_closed`,
 * `canceled_by_user` when DevTools take the page, `Render process gone.`) as a `DetachCause`. A reason it does not
 * know is passed on as it came, and the step bar shows it as a technical detail.
 */
export function detachCause(reason: string): string {
  const normalized = reason
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, ' ')
    .replace(/\.$/, '');
  if (normalized === 'target closed') return 'tab_closed' satisfies DetachCause;
  if (normalized === 'canceled by user' || normalized === 'replaced with devtools')
    return 'devtools' satisfies DetachCause;
  if (normalized.startsWith('render process gone')) return 'crashed' satisfies DetachCause;
  return reason;
}

/**
 * The bridge between the harness's browse loop and the app's browser panel, without any Electron or socket in it:
 * connections come in as `Peer`s, pages as `PageHandle`s. A connection can only ever reach tabs it opened itself.
 */
export class BrowserBridge {
  private readonly host: BrowserHost;
  private readonly now: () => number;
  private readonly id: () => string;
  private readonly openTabTimeoutMs: number;
  private readonly confirmTimeoutMs: number;
  private readonly connections = new Set<Connection>();
  /** The latest run of every open agent tab, finished ones included: what a freshly opened window is told. */
  private readonly tabs = new Map<string, Run>();
  private runs: BrowserRuns = {};

  constructor(host: BrowserHost, options: BridgeOptions = {}) {
    this.host = host;
    this.now = options.now ?? Date.now;
    this.id = options.id ?? randomUUID;
    this.openTabTimeoutMs = options.openTabTimeoutMs ?? 15_000;
    this.confirmTimeoutMs = options.confirmTimeoutMs ?? CONFIRM_TIMEOUT_MS;
  }

  snapshot(): BrowserRunState[] {
    return Object.values(this.runs);
  }

  connect(peer: Peer): { receive(raw: string): void; close(): void } {
    const connection: Connection = { peer, runs: new Map(), open: true };
    this.connections.add(connection);
    return {
      receive: (raw) => {
        const request = parseRequest(raw);
        if (request) void this.handle(connection, request);
      },
      close: () => this.drop(connection),
    };
  }

  /** The person's buttons on the step bar. */
  control(tabId: string, action: BrowserControlAction): void {
    const run = this.tabs.get(tabId);
    if (!run || run.over) return;
    this.steer(run, action);
    if (action === 'takeover') {
      run.page.focus();
      void this.host.keyboard(run.tabId).catch(() => false);
    }
    // Stop means stop: a question still on screen is answered with no.
    if (action === 'stop') run.confirm?.settle(false);
  }

  answerConfirm(tabId: string, confirmId: string, allowed: boolean): void {
    const pending = this.tabs.get(tabId)?.confirm;
    if (pending?.id === confirmId) pending.settle(allowed);
  }

  /** Something the app refused on the page's behalf (a download, a permission, a popup), for the step bar. */
  notice(tabId: string, notice: Omit<BrowserRunNotice, 'at'>): void {
    if (!this.tabs.has(tabId)) return;
    this.publish({
      type: 'notice',
      tabId,
      notice: { kind: notice.kind, detail: plainText(notice.detail), at: this.now() },
    });
  }

  dispose(): void {
    for (const connection of this.connections) this.drop(connection);
  }

  private publish(event: BrowserRunEvent): void {
    this.runs = reduceBrowserRuns(this.runs, event);
    this.host.publish(event);
  }

  private steer(run: Run, input: ControlInput): void {
    const next = nextControl(run.control, input);
    if (next === run.control) return;
    run.control = next;
    this.publish({
      type: 'control',
      tabId: run.tabId,
      paused: next.paused,
      pausedBy: next.pausedBy,
      stopRequested: next.stop,
    });
  }

  private async handle(connection: Connection, request: BridgeRequest): Promise<void> {
    const reply = (result: unknown) => {
      if (connection.open) connection.peer.send({ id: request.id, result: result ?? {}, sessionId: request.sessionId });
    };
    const fail = (message: string) => {
      if (connection.open) connection.peer.send({ id: request.id, error: { message }, sessionId: request.sessionId });
    };
    const decided = route(request.method, request.sessionId !== undefined, request.params);
    try {
      switch (decided.kind) {
        case 'refuse':
          return fail(decided.message);
        case 'hello':
          connection.conversation = identifier(request.params.conversation);
          connection.session = identifier(request.params.session);
          return reply({ embedded: this.host.available !== false, version: PROTOCOL_VERSION });
        case 'createTarget':
          return reply({ targetId: await this.createTarget(connection, request.params) });
        case 'attachToTarget':
          return reply({ sessionId: this.attach(connection, request.params) });
        case 'closeTarget':
          return reply({ success: this.closeTarget(connection, request.params) });
        case 'control':
          return reply(this.controlFor(connection, request));
        case 'confirm':
          return reply({ allowed: await this.confirm(connection, request) });
        case 'step':
          this.step(connection, request);
          return reply({});
        case 'run':
          this.reportRun(connection, request);
          return reply({});
        case 'swallow':
          return this.pageOf(connection, request) ? reply({}) : fail('Session with given id not found.');
        case 'forward':
          return this.forward(connection, request, reply, fail);
      }
    } catch (error) {
      fail(error instanceof Error ? error.message : String(error));
    }
  }

  private async createTarget(connection: Connection, params: Record<string, unknown>): Promise<string> {
    const url = webAddress(params.url) ? String(params.url) : 'about:blank';
    let timer: ReturnType<typeof setTimeout> | undefined;
    const opened = await Promise.race([
      this.host.openTab({ conversation: connection.conversation, session: connection.session, url }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(
          () => reject(new Error("The app's browser panel did not open a tab in time")),
          this.openTabTimeoutMs
        );
      }),
    ]).finally(() => clearTimeout(timer));

    // A tab the panel hands out again belongs to the new run from here on.
    const previous = this.tabs.get(opened.tabId);
    if (previous && !previous.over) throw new Error('That tab is still being driven by another run');
    if (previous) this.forget(previous);

    const run: Run = {
      targetId: this.id(),
      tabId: opened.tabId,
      page: opened.page,
      connection,
      createdAt: this.now(),
      control: FRESH,
      input: { inFlight: 0, lastFinishedAt: Number.NEGATIVE_INFINITY },
      over: false,
      lost: false,
      announced: false,
      confirmationRefused: false,
      lastUrl: url,
      calls: new Map(),
      subscriptions: [],
    };
    this.tabs.set(run.tabId, run);
    connection.runs.set(run.targetId, run);
    connection.latest = run;
    run.endGoneWatch = run.page.onGone(() => this.gone(run));
    run.subscriptions.push(
      run.page.onInput(() => {
        if (!run.over && personsInput(run.input, this.now())) this.steer(run, 'interaction');
      })
    );
    this.publish({
      type: 'started',
      tabId: run.tabId,
      conversationId: opened.conversationId,
      url,
      at: run.createdAt,
    });
    // The harness went away while the tab was opening: nobody is left to drive it.
    if (!connection.open) this.lose(run, 'connection' satisfies DetachCause);
    return run.targetId;
  }

  private attach(connection: Connection, params: Record<string, unknown>): string {
    const run = typeof params.targetId === 'string' ? connection.runs.get(params.targetId) : undefined;
    if (!run || run.over) throw new Error('No target with given id found');
    if (params.flatten !== true) throw new Error('Only flattened sessions are supported');
    if (run.sessionId) return run.sessionId;
    run.page.attach();
    run.sessionId = this.id();
    const sessionId = run.sessionId;
    run.subscriptions.push(
      run.page.onEvent((method, eventParams) => {
        if (connection.open) connection.peer.send({ method, params: eventParams ?? {}, sessionId });
      }),
      run.page.onDetached((reason) => this.lose(run, detachCause(reason)))
    );
    return sessionId;
  }

  private closeTarget(connection: Connection, params: Record<string, unknown>): boolean {
    const run = typeof params.targetId === 'string' ? connection.runs.get(params.targetId) : undefined;
    if (!run) throw new Error('No target with given id found');
    if (!run.over) {
      this.release(run, 'The run was closed');
      this.finish(
        run,
        finalStatus({
          reported: run.reported?.status,
          stopRequested: run.control.stop,
          confirmationRefused: run.confirmationRefused,
        }),
        run.reported?.reason,
        run.reported?.ending
      );
    }
    // The tab stays open for the person; only the connection's claim on it ends here.
    connection.runs.delete(run.targetId);
    return true;
  }

  private pageOf(connection: Connection, request: BridgeRequest): Run | undefined {
    for (const run of connection.runs.values()) {
      if (run.sessionId !== undefined && run.sessionId === request.sessionId) return run;
    }
    return undefined;
  }

  private forward(
    connection: Connection,
    request: BridgeRequest,
    reply: (result: unknown) => void,
    fail: (message: string) => void
  ): void {
    const run = this.pageOf(connection, request);
    if (!run) return fail('Session with given id not found.');
    if (run.over) return fail('The page is no longer attached');
    const synthetic = isSyntheticInput(request.method);
    // Answered exactly once: by the page, or by whatever takes the page away first.
    const settle = (answer: () => void) => {
      if (!run.calls.delete(request.id)) return;
      if (synthetic) {
        run.input.inFlight--;
        run.input.lastFinishedAt = this.now();
      }
      answer();
    };
    run.calls.set(request.id, (message) => settle(() => fail(message)));
    if (synthetic) run.input.inFlight++;
    const refuse = (error: unknown) => settle(() => fail(error instanceof Error ? error.message : String(error)));
    const send = () => {
      // Taken away while the keyboard was being fetched: already answered, and nothing may reach the page now.
      if (!run.calls.has(request.id)) return;
      run.page.send(request.method, request.params).then((result) => settle(() => reply(result)), refuse);
    };
    if (!needsKeyboard(request.method)) return send();
    this.host.keyboard(run.tabId).then((held) => {
      if (held) send();
      // Rather no typing at all than typing into the app's own message box.
      else refuse(new Error('The page does not hold the keyboard, so nothing was typed'));
    }, refuse);
  }

  /** Runs of this connection that can still be driven. */
  private active(connection: Connection): Run[] {
    return [...connection.runs.values()].filter((run) => !run.over);
  }

  /**
   * `Mu.*` calls carry no tab today. One run per connection is the normal case and needs none; with several, a
   * `sessionId` or `targetId` is honoured when given, then the page address, then the newest run.
   */
  private runFor(connection: Connection, request: BridgeRequest): Run | undefined {
    const bySession = this.pageOf(connection, request);
    if (bySession) return bySession.over ? undefined : bySession;
    const { targetId, url } = request.params;
    if (typeof targetId === 'string') {
      const run = connection.runs.get(targetId);
      return run && !run.over ? run : undefined;
    }
    const active = this.active(connection);
    if (active.length <= 1) return active[0];
    const sameUrl = typeof url === 'string' ? active.filter((run) => run.lastUrl === url) : [];
    const candidates = sameUrl.length > 0 ? sameUrl : active;
    return candidates.reduce((newest, run) => (run.createdAt >= newest.createdAt ? run : newest));
  }

  private controlFor(connection: Connection, request: BridgeRequest): { paused: boolean; stop: boolean } {
    const addressed = request.sessionId !== undefined || typeof request.params.targetId === 'string';
    const targets = addressed ? [this.runFor(connection, request)].filter((run) => run !== undefined) : [];
    const runs = addressed ? targets : this.active(connection);
    if (runs.length === 0) {
      // A page that was taken away cannot be driven any further: end the loop cleanly rather than let it fail.
      const lost = addressed ? false : connection.latest?.lost === true;
      return lost ? { paused: false, stop: true } : { paused: NO_RUN.paused, stop: NO_RUN.stop };
    }
    // Unaddressed with several runs: the careful answer for all of them.
    return {
      paused: runs.some((run) => run.control.paused),
      stop: runs.some((run) => run.control.stop),
    };
  }

  private confirm(connection: Connection, request: BridgeRequest): Promise<boolean> {
    const run = this.runFor(connection, request);
    // Nobody can be asked: that is a no.
    if (!run) return Promise.resolve(false);
    run.confirm?.settle(false);
    const id = this.id();
    const askedAt = this.now();
    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => pending.settle(false), this.confirmTimeoutMs);
      const pending: PendingConfirm = {
        id,
        settle: (allowed) => {
          if (run.confirm !== pending) return;
          run.confirm = undefined;
          clearTimeout(timer);
          run.confirmationRefused = !allowed;
          this.publish({ type: 'confirmed', tabId: run.tabId, id, allowed });
          this.host.attention?.(run.tabId, false);
          resolve(allowed);
        },
      };
      run.confirm = pending;
      this.publish({
        type: 'confirm',
        tabId: run.tabId,
        confirm: {
          id,
          label: plainText(request.params.label),
          url: plainText(request.params.url, 2000),
          askedAt,
          deadline: askedAt + this.confirmTimeoutMs,
        },
      });
      this.host.attention?.(run.tabId, true);
    });
  }

  private step(connection: Connection, request: BridgeRequest): void {
    const run = this.runFor(connection, request);
    if (!run) return;
    const { step, kind, action, url, probability, pageChanged } = request.params;
    const address = plainText(url, 2000);
    if (address) run.lastUrl = address;
    this.publish({
      type: 'step',
      tabId: run.tabId,
      step: {
        step: typeof step === 'number' && Number.isFinite(step) ? step : 0,
        kind: plainText(kind, 40),
        action: plainText(action),
        url: address,
        probability: typeof probability === 'number' && probability >= 0 && probability <= 1 ? probability : undefined,
        pageChanged: typeof pageChanged === 'boolean' ? pageChanged : undefined,
        at: this.now(),
      },
    });
  }

  /** `Mu.run`: the run's goal when it starts, its own verdict when it ends. Optional; see `finalStatus`. */
  private reportRun(connection: Connection, request: BridgeRequest): void {
    const run = this.runFor(connection, request);
    if (!run) return;
    const { state, goal, url, status, reason } = request.params;
    if (state === 'started') {
      this.publish({
        type: 'goal',
        tabId: run.tabId,
        goal: plainText(goal, 600),
        url: webAddress(url) ? String(url) : undefined,
      });
    } else if (state === 'finished') {
      // `code`, `params`, `errorCode` and `errorParams`: what the step bar says in the person's language.
      run.reported = { status, reason: plainText(reason, 600) || undefined, ending: runEnding(request.params) };
    }
  }

  private finish(run: Run, status: BrowserRunStatus, reason?: string, ending?: BrowserRunEnding): void {
    if (run.announced) return;
    run.announced = true;
    this.publish({ type: 'finished', tabId: run.tabId, status, reason, ...ending, at: this.now() });
  }

  /** Ends everything the run holds on its page. Calls still waiting for the page are answered with an error. */
  private release(run: Run, why: string): void {
    run.over = true;
    for (const end of run.subscriptions.splice(0)) end();
    for (const failCall of run.calls.values()) failCall(why);
    run.confirm?.settle(false);
    try {
      run.page.detach();
    } catch {
      // Already detached, or the page is gone: there is nothing left to let go of.
    }
  }

  /**
   * The page was taken away while the run was going: DevTools, a crash, or the harness hanging up. `cause` is a
   * `DetachCause` whenever the cause is known (see `detachCause`).
   */
  private lose(run: Run, cause: string): void {
    if (run.over) return;
    run.lost = true;
    this.release(run, `The page is no longer attached (${cause})`);
    this.finish(run, 'detached', cause);
  }

  private gone(run: Run): void {
    this.lose(run, 'tab_closed' satisfies DetachCause);
    this.forget(run);
    this.publish({ type: 'closed', tabId: run.tabId });
  }

  private forget(run: Run): void {
    run.endGoneWatch?.();
    run.endGoneWatch = undefined;
    if (this.tabs.get(run.tabId) === run) this.tabs.delete(run.tabId);
    // `latest` keeps pointing at it so that the loop's next `Mu.control` still learns that its page is gone.
    run.connection.runs.delete(run.targetId);
  }

  private drop(connection: Connection): void {
    if (!connection.open) return;
    connection.open = false;
    this.connections.delete(connection);
    for (const run of connection.runs.values()) this.lose(run, 'connection' satisfies DetachCause);
    connection.runs.clear();
  }
}
