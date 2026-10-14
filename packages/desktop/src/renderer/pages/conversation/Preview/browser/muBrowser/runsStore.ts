import { useSyncExternalStore } from 'react';
import { kyrnBrowserBridge } from '@/common/kyrn/browserBridge';
import {
  reduceBrowserRuns,
  type BrowserRunEvent,
  type BrowserRuns,
  type BrowserRunState,
} from '@/common/kyrn/browserRun';

/**
 * The renderer's mirror of the browsing runs, keyed by tab id. One subscription for the whole window, started by
 * the first component that looks: a snapshot first, then every event through the same reducer the main process
 * uses. Events that arrive while the snapshot is on its way are replayed on top of it.
 */
let runs: BrowserRuns = {};
let started = false;
const listeners = new Set<() => void>();

function apply(event: BrowserRunEvent): void {
  const next = reduceBrowserRuns(runs, event);
  if (next === runs) return;
  runs = next;
  for (const listener of listeners) listener();
}

function start(): void {
  if (started) return;
  started = true;
  let early: BrowserRunEvent[] | undefined = [];
  kyrnBrowserBridge.events.on((event) => {
    if (early) early.push(event);
    else apply(event);
  });
  const replay = (snapshot: BrowserRunState[]) => {
    apply({ type: 'snapshot', runs: snapshot });
    for (const event of early ?? []) apply(event);
    early = undefined;
  };
  kyrnBrowserBridge.snapshot.invoke().then(replay, () => replay([]));
}

function subscribe(listener: () => void): () => void {
  start();
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const current = (): BrowserRuns => runs;

export const useBrowserRuns = (): BrowserRuns => useSyncExternalStore(subscribe, current, current);

export const useBrowserRun = (tabId: string): BrowserRunState | undefined => useBrowserRuns()[tabId];

/** For code outside React (the panel host decides which finished tab to hand out again). */
export const browserRunsNow = (): BrowserRuns => runs;
