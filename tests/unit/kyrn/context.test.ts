import { describe, it, expect } from 'vitest';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Telemetry, activityPage } from '../../../packages/desktop/src/process/agent/kyrn/telemetry';
import {
  contextView,
  compactionEffect,
  cacheUsage,
} from '../../../packages/desktop/src/renderer/pages/conversation/KyrnPanel/Judge/context';
import { mergeActivity } from '../../../packages/desktop/src/renderer/pages/conversation/KyrnPanel/activity';
import type { Activity } from '../../../packages/desktop/src/common/kyrn/types';

const event = (kind: string, payload: Record<string, unknown>): Activity => ({
  id: randomUUID(),
  kind,
  payload,
  at: Date.now(),
});
describe('context observability', () => {
  it('weights cache hits by input tokens, excluding output and counting cache writes as misses', () => {
    expect(cacheUsage({ input: 100, cacheRead: 800, cacheWrite: 100, output: 9000 })).toEqual({
      read: 800,
      total: 1000,
      percent: 80,
    });
    expect(cacheUsage({ input: 900, cacheRead: 0, cacheWrite: 100 }).percent).toBe(0);
    expect(cacheUsage({ input: 0, cacheRead: 0, cacheWrite: 0 }).percent).toBeUndefined();
    expect(cacheUsage({ input: 100 }).percent).toBeUndefined();
  });
  it('keeps the exact share, so a view that rounds down never shows a whole hit for a partial one', () => {
    expect(cacheUsage({ input: 3, cacheRead: 99_997, cacheWrite: 0 }).percent).toBeLessThan(100);
    expect(cacheUsage({ input: 28, cacheRead: 72, cacheWrite: 0 }).percent).toBe(72);
  });
  it('reads the cache hits of the model’s latest reply beside the session’s, from their own records', () => {
    const view = contextView([
      event('turn.usage', { input: 900, output: 5, cacheRead: 100, cacheWrite: 0 }),
      event('context.usage', { usage: {}, sessionTokens: { input: 300, cacheRead: 600, cacheWrite: 100 } }),
      event('turn.usage', { input: 25, output: 5, cacheRead: 75, cacheWrite: 0 }),
    ]);
    expect(view.turnCache).toEqual({ read: 75, total: 100, percent: 75 });
    expect(view.cache.percent).toBe(60);
    // Before the first reply there is nothing to read.
    expect(contextView([event('context.usage', { usage: {} })]).turnCache.percent).toBeUndefined();
  });
  it('clears a pending compaction indicator when the runtime exits', () => {
    expect(contextView([event('compaction_start', {}), event('kyrn_rpc_closed', {})]).compacting).toBe(false);
  });
  it('never treats a plan or an aborted compaction as achieved savings', () => {
    expect(contextView([event('compaction.plan', { beforeChars: 9000, plannedChars: 1000 })]).history).toHaveLength(0);
    expect(
      compactionEffect(event('compaction_end', { applied: false, tokensBefore: 9000, tokensAfter: 1000 })).savedPercent
    ).toBeUndefined();
    expect(
      compactionEffect(event('compaction_end', { applied: true, tokensBefore: 9000, tokensAfter: 10000 })).savedPercent
    ).toBeLessThan(0);
  });
  it('shows real cap and zero usage, but preserves unknown usage after compaction', () => {
    const first = event('context.usage', {
      usage: { tokens: 0, contextWindow: 128000 },
      settings: { enabled: true, reserveTokens: 16000, maxContextTokens: 64000 },
    });
    expect(contextView([first])).toMatchObject({ tokens: 0, percent: 0, limit: 64000, auto: true });
    const next = event('context.usage', {
      usage: { tokens: null, contextWindow: 128000 },
      settings: { reserveTokens: 16000 },
    });
    const merged = mergeActivity([first], [next]);
    expect(merged).toHaveLength(1);
    expect(contextView(merged)).toMatchObject({ tokens: undefined, percent: undefined, limit: 112000 });
  });
  it('persists actual completed compaction metrics, not the removed history or private state', () => {
    const dir = mkdtempSync(join(tmpdir(), 'kyrn-context-'));
    const id = randomUUID();
    try {
      const telemetry = new Telemetry(dir, id);
      telemetry.context({
        contextUsage: { tokens: 32000, contextWindow: 128000 },
        compactionSettings: { enabled: true, maxContextTokens: 64000 },
        apiKey: 'fixture-secret',
      });
      telemetry.capture({ type: 'compaction_start', reason: 'threshold' });
      telemetry.capture({
        type: 'compaction_end',
        aborted: false,
        result: {
          tokensBefore: 32000,
          estimatedTokensAfter: 8000,
          summary: 'private content',
          details: { kyrn: { version: 1, items: ['private content'], metrics: { kept: 3, pruned: 2, dropped: 1 } } },
        },
      });
      const page = activityPage(dir, id, 0);
      expect(compactionEffect(page.events.at(-1)!)).toMatchObject({
        applied: true,
        savedPercent: 75,
        metrics: { pruned: 2 },
      });
      expect(JSON.stringify(page)).not.toMatch(/private content|fixture-secret/);
      expect(contextView(page.events).compacting).toBe(false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
