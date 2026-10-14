import { describe, expect, it } from 'vitest';
import type { Activity } from '@/common/kyrn/types';
import { gaugeReadings, ringArc, wholePercent } from '@/renderer/pages/conversation/KyrnPanel/Board/gauges';

let next = 0;
const event = (kind: string, payload: Record<string, unknown>): Activity => ({
  id: `e${++next}`,
  at: next,
  kind,
  payload,
});
const reply = (input: number, cacheRead: number, cacheWrite: number) =>
  event('turn.usage', { input, output: 40, cacheRead, cacheWrite });
const context = (tokens: number | null, contextWindow = 200_000) =>
  event('context.usage', { usage: { tokens, contextWindow } });

const CIRCUMFERENCE = 2 * Math.PI * 6;

describe('the cache ring', () => {
  it('draws nothing for no hits, part of the circle for some, the whole circle for all', () => {
    expect(ringArc(0, CIRCUMFERENCE)).toBe(0);
    expect(ringArc(25, CIRCUMFERENCE)).toBeCloseTo(CIRCUMFERENCE / 4);
    expect(ringArc(100, CIRCUMFERENCE)).toBeCloseTo(CIRCUMFERENCE);
  });

  it('has no arc without data, and holds a share outside 0 to 100 to the circle', () => {
    expect(ringArc(undefined, CIRCUMFERENCE)).toBeUndefined();
    expect(ringArc(Number.NaN, CIRCUMFERENCE)).toBeUndefined();
    expect(ringArc(140, CIRCUMFERENCE)).toBeCloseTo(CIRCUMFERENCE);
    expect(ringArc(-3, CIRCUMFERENCE)).toBe(0);
  });

  it('reads the latest reply: cacheRead over input, cacheRead and cacheWrite', () => {
    expect(gaugeReadings([reply(1000, 0, 0)]).turnCache).toBe(0);
    expect(gaugeReadings([reply(0, 5000, 0), reply(200, 700, 100)]).turnCache).toBe(70);
    expect(gaugeReadings([reply(0, 8000, 0)]).turnCache).toBe(100);
  });

  it('shows nothing before the first reply', () => {
    expect(gaugeReadings([]).turnCache).toBeUndefined();
    expect(gaugeReadings([context(0)]).turnCache).toBeUndefined();
  });

  it('writes a whole percentage rounded down: 99.97% of the input from the cache is not 100%', () => {
    const almost = gaugeReadings([reply(3, 9997, 0)]).turnCache!;
    expect(wholePercent(almost)).toBe(99);
    expect(wholePercent(100)).toBe(100);
    expect(wholePercent(0)).toBe(0);
  });
});

describe('the context bar', () => {
  it('is the judge tab’s context share, from the same record', () => {
    expect(gaugeReadings([context(68_000)]).context).toBe(34);
  });

  it('says nothing before the first turn, when nothing has been counted yet', () => {
    expect(gaugeReadings([]).context).toBeUndefined();
    expect(gaugeReadings([context(0)]).context).toBeUndefined();
  });

  it('says nothing while the count is unknown, as it is right after a compaction', () => {
    expect(gaugeReadings([context(null)]).context).toBeUndefined();
  });
});
