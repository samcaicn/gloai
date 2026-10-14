import type { BrowserRuns } from '@/common/kyrn/browserRun';

export type PanelTab = { id: string; isBrowser: boolean; isMu: boolean };

export type OpenDecision = { kind: 'new' } | { kind: 'reuse'; tabId: string } | { kind: 'refuse'; error: string };

/**
 * Where a new run gets its tab. A finished mu tab of the same conversation is handed out again (one mu tab per
 * conversation instead of a pile of them; the oldest first); otherwise a new tab, unless the panel is full. The
 * panel's own rule for a full panel is to take over the first browser tab, which could be one the person is
 * using, or one that is being driven right now: so a full panel is a refusal here, in words the model can act on.
 */
export function decideOpen(
  tabs: PanelTab[],
  runs: BrowserRuns,
  conversationId: string,
  maxBrowserTabs: number
): OpenDecision {
  const reusable = tabs
    .filter((tab) => tab.isMu)
    .map((tab) => runs[tab.id])
    .filter((run) => run !== undefined && run.phase === 'finished' && run.conversationId === conversationId)
    .toSorted((a, b) => a.startedAt - b.startedAt)[0];
  if (reusable) return { kind: 'reuse', tabId: reusable.tabId };
  if (tabs.filter((tab) => tab.isBrowser).length >= maxBrowserTabs) {
    return {
      kind: 'refuse',
      error: `The app's browser panel is full (${maxBrowserTabs} tabs). Ask the user to close a tab.`,
    };
  }
  return { kind: 'new' };
}
