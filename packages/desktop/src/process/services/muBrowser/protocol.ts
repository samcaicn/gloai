import { timingSafeEqual } from 'node:crypto';
import type { BrowserRunStatus } from '@/common/kyrn/browserRun';

/**
 * The wire contract between the harness's browse loop and this app, as specified in
 * `kyrn/docs/features/embedded-browser.md` of the harness repository (section 3).
 * Everything in this file is pure: what a message means, never what is done about it.
 */
export const PROTOCOL_VERSION = 1;

export type BridgeRequest = {
  id: number;
  method: string;
  params: Record<string, unknown>;
  sessionId?: string;
};

/** One text frame from the harness. Anything that is not a request is dropped without an answer. */
export function parseRequest(raw: string): BridgeRequest | undefined {
  let message: unknown;
  try {
    message = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (typeof message !== 'object' || message === null) return undefined;
  const { id, method, params, sessionId } = message as Record<string, unknown>;
  if (typeof id !== 'number' || !Number.isFinite(id) || typeof method !== 'string' || !method) return undefined;
  return {
    id,
    method,
    params: typeof params === 'object' && params !== null && !Array.isArray(params) ? { ...params } : {},
    sessionId: typeof sessionId === 'string' && sessionId ? sessionId : undefined,
  };
}

/**
 * The path of the upgrade request has to be exactly `/<token>`. Compared in constant time, and on equal lengths
 * only, so a wrong guess learns nothing from how fast it was refused.
 */
export function pathCarriesToken(requestUrl: string | undefined, token: string): boolean {
  if (!requestUrl || !token) return false;
  const path = requestUrl.split('?')[0];
  const offered = Buffer.from(path.startsWith('/') ? path.slice(1) : path, 'utf8');
  const expected = Buffer.from(token, 'utf8');
  return offered.length === expected.length && timingSafeEqual(offered, expected);
}

export type Route =
  | { kind: 'createTarget' }
  | { kind: 'attachToTarget' }
  | { kind: 'closeTarget' }
  | { kind: 'hello' }
  | { kind: 'control' }
  | { kind: 'confirm' }
  | { kind: 'step' }
  | { kind: 'run' }
  /** Answered with `{}` and never sent to the page. */
  | { kind: 'swallow' }
  /** Passed to the page's debugger as it is. */
  | { kind: 'forward' }
  | { kind: 'refuse'; message: string };

const notFound = (method: string): Route => ({ kind: 'refuse', message: `'${method}' wasn't found` });

/**
 * Page-level methods that would reach past the one page a connection owns, or undo what the app decided about
 * downloads and permissions. The loop uses none of them. `Target.*` on a page session could list and attach to
 * the app's own window; `Browser.*` and `Page.setDownloadBehavior` could switch downloads back on to any folder;
 * `DOM.setFileInputFiles` hands a local file of the caller's choosing to a web page.
 */
const REFUSED_PAGE_METHODS = [/^Target\./, /^Browser\./, /^Tethering\./, /^SystemInfo\./];
const REFUSED_PAGE_EXACT = new Set(['Page.setDownloadBehavior', 'DOM.setFileInputFiles']);

/** What to do with a request. With a `sessionId` it is addressed to a page, without one to the bridge itself. */
export function route(method: string, addressedToPage: boolean, params: Record<string, unknown> = {}): Route {
  if (method.startsWith('Mu.')) {
    switch (method) {
      case 'Mu.hello':
        return { kind: 'hello' };
      case 'Mu.control':
        return { kind: 'control' };
      case 'Mu.confirm':
        return { kind: 'confirm' };
      case 'Mu.step':
        return { kind: 'step' };
      case 'Mu.run':
        return { kind: 'run' };
      default:
        return notFound(method);
    }
  }
  if (!addressedToPage) {
    switch (method) {
      case 'Target.createTarget':
        return { kind: 'createTarget' };
      case 'Target.attachToTarget':
        return { kind: 'attachToTarget' };
      case 'Target.closeTarget':
        return { kind: 'closeTarget' };
      default:
        // There is no browser behind this socket, only pages the connection opened itself.
        return notFound(method);
    }
  }
  // The page keeps the real size of the panel the person is looking at.
  if (method === 'Emulation.setDeviceMetricsOverride') return { kind: 'swallow' };
  if (REFUSED_PAGE_EXACT.has(method) || REFUSED_PAGE_METHODS.some((pattern) => pattern.test(method))) {
    return { kind: 'refuse', message: `'${method}' is not available in the app's browser` };
  }
  // A navigation asked for over the protocol does not pass the page's own navigation events, so it is checked here.
  if (method === 'Page.navigate' && !webAddress(params.url)) {
    return { kind: 'refuse', message: 'Only http and https pages can be opened' };
  }
  return { kind: 'forward' };
}

/**
 * Commands Chromium delivers to whatever holds the keyboard focus of the window, not to the page they name: keys,
 * inserted text, IME composition. Mouse and touch events go by coordinates and are not among them.
 */
export const needsKeyboard = (method: string): boolean =>
  method === 'Input.insertText' || method === 'Input.dispatchKeyEvent' || method === 'Input.imeSetComposition';

/** `Input.*` commands are the loop's own hands on the page: input seen while one is in flight is not the person's. */
export const isSyntheticInput = (method: string): boolean => method.startsWith('Input.');

const REPORTED: Readonly<Record<string, BrowserRunStatus>> = {
  done: 'done',
  blocked: 'blocked',
  budget: 'budget',
  needs_confirmation: 'needs_confirmation',
  aborted: 'stopped',
  read: 'read',
  failed: 'failed',
};

/**
 * How a run ended. The harness says so when it can (`Mu.run`); a harness that only closes its target leaves two
 * endings the bridge has seen with its own eyes (the person pressed stop, or refused a confirmation), and for the
 * rest an honest "ended": done and blocked cannot be told apart from here, and are not guessed.
 */
export function finalStatus(evidence: {
  reported?: unknown;
  stopRequested: boolean;
  confirmationRefused: boolean;
}): BrowserRunStatus {
  const reported = typeof evidence.reported === 'string' ? REPORTED[evidence.reported] : undefined;
  if (reported) return reported;
  if (evidence.stopRequested) return 'stopped';
  if (evidence.confirmationRefused) return 'needs_confirmation';
  return 'ended';
}

/** A conversation or session identifier as the app uses them: never a path, never markup. */
export function identifier(value: unknown): string | undefined {
  return typeof value === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(value) ? value : undefined;
}

/** Only web pages: no `file:`, no `chrome:`, no custom schemes. `about:blank` is the empty tab a run starts from. */
export function webAddress(url: unknown): boolean {
  if (typeof url !== 'string') return false;
  if (url === 'about:blank') return true;
  try {
    const { protocol } = new URL(url);
    return protocol === 'http:' || protocol === 'https:';
  } catch {
    return false;
  }
}
