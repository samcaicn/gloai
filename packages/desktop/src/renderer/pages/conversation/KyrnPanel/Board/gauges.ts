import type { Activity } from '@/common/kyrn/types';
import { contextView } from '../Judge/context';

/** What the two gauges above the board show: percentages from 0 to 100, each undefined while there is none yet. */
export type GaugeReadings = {
  /** The share of the model's context window the next request uses: the judge tab's number, from the same place. */
  context?: number;
  /** Cache hits of the model's latest reply: cacheRead / (input + cacheRead + cacheWrite). */
  turnCache?: number;
  /** The same over the whole session. */
  sessionCache?: number;
};

export function gaugeReadings(events: Activity[]): GaugeReadings {
  const view = contextView(events);
  return {
    // Before anything was sent the count is 0 tokens: the context is not measured yet, which is not the same as empty.
    context: view.tokens ? view.percent : undefined,
    turnCache: view.turnCache.percent,
    sessionCache: view.cache.percent,
  };
}

/**
 * A share as the gauges write it: a whole percentage, rounded down, so that 99.6% of the input read from the cache is
 * never shown as 100%.
 */
export function wholePercent(percent: number): number {
  return Math.floor(Math.min(100, Math.max(0, percent)));
}

/**
 * The length of the ring's arc for a share from 0 to 100, on a circle of this circumference; undefined when there is
 * no share to draw. A share outside 0-100 is held to it.
 */
export function ringArc(percent: number | undefined, circumference: number): number | undefined {
  if (percent === undefined || !Number.isFinite(percent)) return undefined;
  return (Math.min(100, Math.max(0, percent)) / 100) * circumference;
}
