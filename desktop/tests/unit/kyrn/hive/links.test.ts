import { describe, expect, it } from 'vitest';
import { buildHiveRuns } from '@/renderer/pages/conversation/KyrnPanel/Hive/activity';
import { HOME, hiveLinks, reportLinks, snapshotLinks } from '@/renderer/pages/conversation/KyrnPanel/Hive/Graph/links';
import { bendOf, linkPath, ringLayout } from '@/renderer/pages/conversation/KyrnPanel/Hive/Graph/layout';
import { parseSwarmSnapshot } from '@/common/kyrn/hive';
import { activity, hiveEvents, hiveSnapshot } from './hiveFixtures';

describe('the lines of a run', () => {
  it('counts deliveries per pair and keeps the last words that went along', () => {
    const run = buildHiveRuns([
      ...hiveEvents,
      activity('note-3', 'hive.note', {
        id: 'note-3',
        bee: 'prefix-mutations',
        kind: 'finding',
        text: 'Second finding.',
      }),
      activity('delivery-2', 'hive.delivery', { note: 'note-3', to: 'provider-cache' }),
      // A receipt without its note has no source: it cannot be drawn.
      activity('delivery-3', 'hive.delivery', { note: 'lost', to: 'provider-cache' }),
    ])[0];
    const links = hiveLinks(run.deliveries, run.notes, run.relations);
    expect(links).toEqual([
      {
        kind: 'delivery',
        from: 'prefix-mutations',
        to: 'provider-cache',
        count: 2,
        texts: ['Prefix remains stable after the first request.', 'Second finding.'],
        ids: ['delivery', 'delivery-2'],
      },
      {
        kind: 'correction',
        from: 'provider-cache',
        to: 'prefix-mutations',
        count: 1,
        texts: ['The prefix changes once the provider swaps its cache key.'],
        ids: ['relation'],
      },
    ]);
  });

  it('draws a dispute once whichever note came second, and not once a correction settled it', () => {
    const run = buildHiveRuns([
      ...hiveEvents.filter((event) => event.kind !== 'hive.relation'),
      activity('a', 'hive.relation', { later: 'note-2', earlier: 'note-1', relation: 'contradicts' }),
      activity('b', 'hive.relation', { later: 'note-1', earlier: 'note-2', relation: 'contradicts' }),
      activity('c', 'hive.relation', { later: 'note-2', earlier: 'note-1', relation: 'supports' }),
    ])[0];
    expect(
      hiveLinks(run.deliveries, run.notes, run.relations).map((link) => [link.kind, link.from, link.to, link.count])
    ).toEqual([
      ['delivery', 'prefix-mutations', 'provider-cache', 1],
      ['conflict', 'prefix-mutations', 'provider-cache', 1],
    ]);
    const settled = buildHiveRuns([
      ...hiveEvents,
      activity('a', 'hive.relation', { later: 'note-2', earlier: 'note-1', relation: 'contradicts' }),
    ])[0];
    expect(hiveLinks(settled.deliveries, settled.notes, settled.relations).map((link) => link.kind)).toEqual([
      'delivery',
      'correction',
    ]);
  });

  it('gives a delegate run a line from each sub-agent that finished to mu, and a snapshot its latest pairs', () => {
    const snapshot = parseSwarmSnapshot({
      ...hiveSnapshot,
      kind: 'delegate',
      bees: [
        { name: 'reviewer', status: 'done', said: 'Looks right.' },
        { name: 'tester', status: 'tool' },
        { name: 'writer', status: 'failed' },
      ],
    })!;
    expect(reportLinks(snapshot.bees)).toEqual([
      { kind: 'report', from: 'reviewer', to: HOME, count: 1, texts: ['Looks right.'], ids: ['report:reviewer'] },
    ]);
    const hive = parseSwarmSnapshot({
      ...hiveSnapshot,
      board: { latest: [{ bee: 'prefix-mutations', to: ['provider-cache', 'provider-cache', 'prefix-mutations'] }] },
    })!;
    expect(snapshotLinks('hive', hive.bees, hive.latest)).toMatchObject([
      { kind: 'delivery', from: 'prefix-mutations', to: 'provider-cache', count: 2 },
    ]);
  });
});

const midY = (path: string) => Number(/Q[\d.]+ ([\d.]+) /.exec(path)?.[1]);

describe('the ring', () => {
  it('faces one or two bees across the centre and spreads more of them evenly from the top', () => {
    const pair = ringLayout(['a', 'b'], 360);
    expect(pair.nodes.get('a')!.x).toBeLessThan(pair.centre.x);
    expect(pair.nodes.get('b')!.x).toBeGreaterThan(pair.centre.x);
    expect(pair.nodes.get('a')!.y).toBe(pair.centre.y);
    const three = ringLayout(['a', 'b', 'c'], 360);
    expect(three.nodes.get('a')!.y).toBeLessThan(three.centre.y);
    expect(Math.round(three.nodes.get('a')!.x)).toBe(180);
    const four = ringLayout(['a', 'b', 'c', 'd'], 360);
    // An even count stands off the axes, so no two bees have the centre exactly between them.
    for (const point of four.nodes.values()) {
      expect(Math.abs(point.x - four.centre.x)).toBeGreaterThan(10);
      expect(Math.abs(point.y - four.centre.y)).toBeGreaterThan(10);
    }
  });

  it('stretches the ring downwards for many bees on a narrow stage instead of overlapping them', () => {
    const narrow = ringLayout(
      Array.from({ length: 10 }, (_, i) => `bee-${i}`),
      320
    );
    const wide = ringLayout(
      Array.from({ length: 10 }, (_, i) => `bee-${i}`),
      720
    );
    expect(narrow.height).toBeGreaterThan(wide.height);
    expect(narrow.width).toBe(320);
    for (const point of narrow.nodes.values()) {
      expect(point.x).toBeGreaterThanOrEqual(42);
      expect(point.x).toBeLessThanOrEqual(320 - 42);
    }
    // No two boxes (84 wide, 118 tall, hanging from the avatars) touch, on either stage.
    for (const layout of [narrow, wide]) {
      const points = [...layout.nodes.values()];
      for (let i = 0; i < points.length; i += 1)
        for (let j = i + 1; j < points.length; j += 1) {
          const apart = Math.abs(points[i].x - points[j].x) >= 84 + 8 || Math.abs(points[i].y - points[j].y) >= 118 + 8;
          expect(apart).toBe(true);
        }
    }
    expect(ringLayout([], 0).width).toBe(200);
  });

  it('bends a pair of lines going both ways to opposite sides and leaves each box at its edge', () => {
    const a = { x: 50, y: 100 };
    const b = { x: 250, y: 100 };
    const there = linkPath(a, b, bendOf('a', 'b'));
    const back = linkPath(b, a, bendOf('b', 'a'));
    // Side by side: from the edge of one avatar (22 + 3) to the edge of the other (22 + 7 for the arrow).
    expect(there).toMatch(/^M75\.0 100\.0 Q/);
    expect(there).toMatch(/ 221\.0 100\.0$/);
    // The control points of the two curves lie on opposite sides of the line between the nodes.
    expect(midY(there)).toBeGreaterThan(100);
    expect(midY(back)).toBeGreaterThan(100);
    // One under the other: the line leaves below the upper node's words, never through them, and ends at the
    // lower node's avatar top; the way up leaves the lower avatar's top and ends under the upper node's words.
    const upper = { x: 100, y: 50 };
    const lower = { x: 100, y: 300 };
    expect(linkPath(upper, lower, 1)).toMatch(/^M100\.0 149\.0 .* 100\.0 271\.0$/);
    expect(linkPath(lower, upper, 1)).toMatch(/^M100\.0 275\.0 .* 100\.0 153\.0$/);
    // To mu at the centre of a delegate run: the line ends at its circle.
    expect(linkPath(upper, { x: 100, y: 200 }, 1, { toHome: true })).toMatch(/ 100\.0 175\.0$/);
    // A delegate run's ring is tall enough for the top bee's words to end well above mu's node.
    const delegate = ringLayout(['a', 'b', 'c'], 420, true);
    expect(delegate.centre.y - delegate.nodes.get('a')!.y).toBeGreaterThanOrEqual(96 + 18 + 40);
    expect(ringLayout(['a', 'b', 'c'], 420).centre.y - ringLayout(['a', 'b', 'c'], 420).nodes.get('a')!.y).toBeLessThan(
      154
    );
  });
});
