import type { Session, WebContents, WebPreferences } from 'electron';
import type { BrowserRunNoticeKind } from '@/common/kyrn/browserRun';
import { webAddress } from './protocol';

/**
 * What an agent-driven page may and may not do (contract 3.4). The pages the person browses in the work panel's
 * browser keep their own settings; nothing here touches them.
 */
export type Refusal = { kind: BrowserRunNoticeKind; detail: string };

/**
 * Top-level navigation: web pages only. Inside a page, frames may also be the documents pages build for
 * themselves (`about:`, `blob:`, `data:`); never the local disk, the browser's own pages or an app scheme.
 */
export function navigationAllowed(url: string, mainFrame: boolean): boolean {
  if (webAddress(url)) return true;
  if (mainFrame) return false;
  return /^(about|blob|data):/i.test(url);
}

/**
 * Forced onto every `<webview>` that asks for the agent's partition, before it is attached: no Node, no preload,
 * isolated and sandboxed, no popups. Attributes set by whoever wrote the tag do not get a say.
 */
export function lockWebviewPreferences(webPreferences: WebPreferences, params: Record<string, string>): void {
  delete webPreferences.preload;
  webPreferences.nodeIntegration = false;
  webPreferences.nodeIntegrationInSubFrames = false;
  webPreferences.nodeIntegrationInWorker = false;
  webPreferences.contextIsolation = true;
  webPreferences.sandbox = true;
  webPreferences.webSecurity = true;
  webPreferences.allowRunningInsecureContent = false;
  webPreferences.webviewTag = false;
  webPreferences.plugins = false;
  delete params.preload;
  delete params.allowpopups;
  delete params.nodeintegration;
  delete params.nodeintegrationinsubframes;
  delete params.disablewebsecurity;
}

/**
 * The partition's own rules: every permission a page can ask for is refused (camera, microphone, location,
 * notifications, clipboard reading, screen capture, devices…), and every download is cancelled. Certificate errors
 * need nothing here: the app has no `certificate-error` handler, so Chromium's refusal stands and no page or
 * person can click through it.
 */
export function hardenAgentSession(
  agentSession: Session,
  refused: (contents: WebContents | null, refusal: Refusal) => void
): void {
  agentSession.setPermissionRequestHandler((contents, permission, callback) => {
    callback(false);
    refused(contents, { kind: 'permission', detail: permission });
  });
  agentSession.setPermissionCheckHandler(() => false);
  agentSession.setDevicePermissionHandler(() => false);
  // The local disk does not exist for this partition, however a navigation to it was started.
  agentSession.protocol.handle('file', () => new Response(null, { status: 403 }));
  agentSession.on('will-download', (event, item, contents) => {
    event.preventDefault();
    refused(contents ?? null, { kind: 'download', detail: item.getFilename() });
  });
}

/** One agent page's own rules: where it may go, and what happens when it wants a window of its own. */
export function guardAgentPage(contents: WebContents, refused: (refusal: Refusal) => void): void {
  const check = (event: { preventDefault(): void }, url: string, mainFrame: boolean) => {
    if (navigationAllowed(url, mainFrame)) return;
    event.preventDefault();
    refused({ kind: 'navigation', detail: url });
  };
  contents.on('will-navigate', (event, url) => check(event, url, true));
  // `will-navigate` is not told about navigations the app itself starts (the address bar above the page, a changed
  // `src`). Those are caught as they start. Chromium's own error page is what a failed load turns into: let it be.
  contents.on('did-start-navigation', (_event, url, _inPlace, mainFrame) => {
    if (!mainFrame || navigationAllowed(url, true) || url.startsWith('chrome-error://')) return;
    // Not from inside the event: stopping a navigation while Chromium is still announcing it takes the process down.
    setImmediate(() => {
      if (!contents.isDestroyed()) contents.stop();
    });
    refused({ kind: 'navigation', detail: url });
  });
  contents.on('will-redirect', (event, url, _inPlace, mainFrame) => check(event, url, mainFrame));
  contents.on('will-frame-navigate', (event) => check(event, event.url, event.isMainFrame));
  // A page of the web never gets to embed a webview of its own.
  contents.on('will-attach-webview', (event) => event.preventDefault());
  contents.setWindowOpenHandler(({ url }) => {
    // The loop watches one tab: a link that wants a new window opens in this one, anything else is refused.
    if (webAddress(url) && url !== 'about:blank') void contents.loadURL(url).catch((): void => undefined);
    else refused({ kind: 'popup', detail: url });
    return { action: 'deny' };
  });
}
