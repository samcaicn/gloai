/**
 * The `<webview>` element of every agent tab on screen, by tab id. The panel host needs the element itself: giving
 * a page the keyboard means focusing its webview in this window, which only the renderer can do.
 */
const views = new Map<string, Electron.WebviewTag>();

export function registerWebview(tabId: string, view: Electron.WebviewTag): void {
  views.set(tabId, view);
}

export function forgetWebview(tabId: string): void {
  views.delete(tabId);
}

/** Focuses the tab's page inside this window and says whether the window's focus really is there now. */
export function giveKeyboard(tabId: string): boolean {
  const view = views.get(tabId);
  if (!view || !view.isConnected) return false;
  view.focus();
  return document.activeElement === view;
}
