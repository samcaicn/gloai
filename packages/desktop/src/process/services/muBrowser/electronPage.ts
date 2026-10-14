import type { WebContents } from 'electron';
import type { PageHandle } from './bridge';

/** Adds a listener and returns the function that takes it off again. */
function subscribe<Args extends unknown[]>(
  add: (listener: (...args: Args) => void) => void,
  remove: (listener: (...args: Args) => void) => void,
  listener: (...args: Args) => void
): () => void {
  add(listener);
  return () => {
    // Removing a listener from a destroyed page throws in Electron; by then there is nothing to remove.
    try {
      remove(listener);
    } catch {
      // Ignored on purpose, see above.
    }
  };
}

/**
 * A `<webview>`'s `webContents` as the bridge sees a page. `mayDrive` is asked right before the debugger is
 * attached: the bridge must never be pointed at the app's own window (it carries the preload bridge) or at a
 * webview outside the agent's partition, whatever a renderer reports.
 */
export function electronPage(contents: WebContents, mayDrive: (contents: WebContents) => boolean): PageHandle {
  return {
    attach: () => {
      if (contents.isDestroyed()) throw new Error('The tab was closed');
      if (contents.getType() !== 'webview' || !mayDrive(contents)) {
        throw new Error("Only a tab of the app's agent browser can be driven");
      }
      try {
        contents.debugger.attach('1.3');
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`Could not attach to the page (are its developer tools open?): ${message}`, { cause: error });
      }
    },
    detach: () => {
      if (!contents.isDestroyed() && contents.debugger.isAttached()) contents.debugger.detach();
    },
    send: (method, params) => {
      if (contents.isDestroyed()) return Promise.reject(new Error('The tab was closed'));
      return contents.debugger.sendCommand(method, params);
    },
    onEvent: (listener) =>
      subscribe<[unknown, string, unknown, string?]>(
        (handler) => contents.debugger.on('message', handler),
        (handler) => contents.debugger.removeListener('message', handler),
        // Events of child sessions (out-of-process frames) are not offered: the loop has no session for them.
        (_event, method, params, childSession) => {
          if (!childSession) listener(method, params);
        }
      ),
    onDetached: (listener) =>
      subscribe<[unknown, string]>(
        (handler) => contents.debugger.on('detach', handler),
        (handler) => contents.debugger.removeListener('detach', handler),
        (_event, reason) => listener(reason)
      ),
    onGone: (listener) =>
      subscribe<[]>(
        (handler) => contents.once('destroyed', handler),
        (handler) => contents.removeListener('destroyed', handler),
        () => listener()
      ),
    onInput: (listener) =>
      subscribe<[unknown, { type: string }]>(
        (handler) => contents.on('input-event', handler),
        (handler) => contents.removeListener('input-event', handler),
        (_event, input) => {
          if (input.type === 'mouseDown' || input.type === 'rawKeyDown' || input.type === 'keyDown') listener();
        }
      ),
    focus: () => {
      if (!contents.isDestroyed()) contents.focus();
    },
  };
}

/**
 * Resolves once a freshly opened tab has drawn its (blank) page. Chromium drops input aimed at a document that has
 * not painted yet, and the first webview of a window paints late (the compositor is still warming up): the loop's
 * first click would go nowhere and cost it a step. Two animation frames of the blank page are proof enough; a tab
 * that is not visible never gets them, so the wait is bounded and the run goes on regardless.
 */
export async function firstPaint(contents: WebContents, capMs = 1500): Promise<void> {
  if (contents.isDestroyed()) return;
  const painted = contents
    .executeJavaScript('new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done(true))))')
    .catch((): void => undefined);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const cap = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, capMs);
  });
  await Promise.race([painted, cap]);
  clearTimeout(timer);
}

/**
 * What every run starts from: a blank page that has been drawn. A tab that is handed out again still shows the
 * last run's page; it is blanked here, and only here (a second navigation from the renderer could land after the
 * loop's own first navigation and wipe the page it had just opened).
 */
export async function blankAndPainted(contents: WebContents): Promise<void> {
  if (contents.isDestroyed()) return;
  if (contents.getURL() !== 'about:blank') await contents.loadURL('about:blank').catch((): void => undefined);
  await firstPaint(contents);
}
