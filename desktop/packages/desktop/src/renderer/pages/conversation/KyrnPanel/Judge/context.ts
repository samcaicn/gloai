import type { Activity } from '@/common/kyrn/types';
import { record } from '../activity';

function finite(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined;
}

/**
 * Missing measurements remain unknown; plans are never counted as achieved savings. The judge tab's context panel and
 * the gauges above the board both read this: `percent` is the share of the model's context window the next request
 * uses, `cache` the session's cache hits and `turnCache` those of the model's latest reply.
 */
export function contextView(events: Activity[]) {
  const snapshot = events.findLast((e) => e.kind === 'context.usage');
  const usage = record(snapshot?.payload.usage);
  const settings = record(snapshot?.payload.settings);
  const policy = events.findLast((e) => e.kind === 'context.policy')?.payload;
  const history = events.filter((e) => e.kind === 'compaction_end');
  const last = events.findLast((e) => ['compaction_start', 'compaction_end', 'kyrn_rpc_closed'].includes(e.kind));
  const window = finite(usage.contextWindow);
  const cap = finite(settings.maxContextTokens);
  const limit =
    window === undefined
      ? undefined
      : Math.min(cap || window, Math.max(0, window - (finite(settings.reserveTokens) ?? 0)));
  const tokens = finite(usage.tokens);
  return {
    tokens,
    cache: cacheUsage(snapshot?.payload.sessionTokens),
    turnCache: cacheUsage(events.findLast((e) => e.kind === 'turn.usage')?.payload),
    window,
    limit,
    percent: tokens !== undefined && window ? Math.min(100, Math.round((tokens / window) * 1000) / 10) : undefined,
    auto: typeof settings.enabled === 'boolean' ? settings.enabled : undefined,
    beta: policy?.betaEnabled === true,
    mode: typeof policy?.mode === 'string' ? policy.mode : undefined,
    compacting: last?.kind === 'compaction_start',
    history: history.toReversed(),
  };
}

/**
 * pi normalizes input as uncached tokens. Cache writes are inputs, not hits; output is excluded. The percentage is
 * exact: each view rounds it its own way (the board's ring rounds down, so it never shows 100% for 99.97%).
 */
export function cacheUsage(value: unknown) {
  const usage = record(value);
  const input = finite(usage.input);
  const read = finite(usage.cacheRead);
  const write = finite(usage.cacheWrite);
  const total = input !== undefined && read !== undefined && write !== undefined ? input + read + write : undefined;
  // Multiplied first: a whole share (72 of 100) comes out exactly 72, never 71.999…, which rounding down would drop.
  return { read, total, percent: total && read !== undefined ? (read * 100) / total : undefined };
}

export function compactionEffect(event: Activity) {
  const p = event.payload;
  const before = finite(p.tokensBefore);
  const after = finite(p.tokensAfter);
  const applied = p.applied === true;
  return {
    before,
    after,
    applied,
    savedPercent: applied && before && after !== undefined ? ((before - after) / before) * 100 : undefined,
    metrics: record(p.metrics),
  };
}
