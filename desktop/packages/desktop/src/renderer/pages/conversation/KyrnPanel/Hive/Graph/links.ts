import type { HiveBee, HiveBoardLine } from '@/common/kyrn/hive';
import { isBeeActive } from '@/common/kyrn/hive';
import type { HiveDelivery, HiveNote, HiveRelation } from '../activity';

/**
 * What the map draws between two bees. A delivery is a note the judge handed from one bee to another (a receipt in
 * `deliveries.jsonl`, never a gate that merely passed). A correction is a later note that supersedes an earlier one
 * of another bee; a dispute is two notes that contradict each other and that nobody has settled. A report is what a
 * delegate's sub-agent handed back to mu when it finished.
 */
export type HiveLinkKind = 'delivery' | 'correction' | 'conflict' | 'report';

export type HiveLink = {
  kind: HiveLinkKind;
  from: string;
  /** A bee's name, or `HOME` for mu itself. */
  to: string;
  /** How many notes went this way. */
  count: number;
  /** The words that went along it, oldest first, at most the last three. */
  texts: string[];
  /** The receipts behind it, oldest first: an id not seen before is a delivery that just arrived. */
  ids: string[];
};

/** The centre of a delegate run's map: mu, whom every sub-agent reports back to. No bee can carry this name. */
export const HOME = '\u0000mu';

export const linkKey = (link: Pick<HiveLink, 'kind' | 'from' | 'to'>): string =>
  JSON.stringify([link.kind, link.from, link.to]);

function fold(rows: { kind: HiveLinkKind; from: string; to: string; text: string; id: string }[]): HiveLink[] {
  const links = new Map<string, HiveLink>();
  for (const row of rows) {
    if (!row.from || !row.to || row.from === row.to) continue;
    const key = linkKey(row);
    const link = links.get(key) ?? { kind: row.kind, from: row.from, to: row.to, count: 0, texts: [], ids: [] };
    link.count += 1;
    if (row.text) link.texts = [...link.texts, row.text].slice(-3);
    link.ids.push(row.id);
    links.set(key, link);
  }
  return [...links.values()];
}

/**
 * A hive run's lines, deliveries first so that corrections and disputes draw on top of them. A relation whose notes
 * are not on the recorded board draws nothing: the map never invents a bee. A dispute over a note that was later
 * superseded is settled by that correction and is not drawn, as the harness reads it.
 */
export function hiveLinks(
  deliveries: readonly HiveDelivery[],
  notes: readonly HiveNote[],
  relations: readonly HiveRelation[]
): HiveLink[] {
  const byId = new Map(notes.map((note) => [note.id, note]));
  const superseded = new Set(relations.filter((row) => row.relation === 'supersedes').map((row) => row.earlier));
  // One dispute per pair of notes, whichever of them the judge was looking at when it read the other.
  const disputed = new Set<string>();
  const rows: { kind: HiveLinkKind; from: string; to: string; text: string; id: string }[] = deliveries.map(
    (delivery) => ({ kind: 'delivery', from: delivery.from, to: delivery.to, text: delivery.text, id: delivery.id })
  );
  for (const row of relations) {
    const later = byId.get(row.later);
    const earlier = byId.get(row.earlier);
    if (!later || !earlier || row.relation === 'supports') continue;
    if (row.relation === 'supersedes') {
      rows.push({ kind: 'correction', from: later.from, to: earlier.from, text: later.text, id: row.id });
    } else if (!superseded.has(row.earlier) && !superseded.has(row.later)) {
      const pair = [row.earlier, row.later].toSorted().join('+');
      if (disputed.has(pair)) continue;
      disputed.add(pair);
      // A dispute has no direction: the pair is written the same way whichever note came second.
      const [from, to] = [earlier.from, later.from].toSorted();
      rows.push({ kind: 'conflict', from, to, text: later.text, id: row.id });
    }
  }
  const order: Record<HiveLinkKind, number> = { delivery: 0, report: 0, correction: 1, conflict: 2 };
  return fold(rows).toSorted((a, b) => order[a.kind] - order[b.kind]);
}

/** A delegate run's lines: one from each sub-agent that finished to mu, carrying the first of what it reported. */
export function reportLinks(bees: readonly HiveBee[]): HiveLink[] {
  return fold(
    bees
      .filter((bee) => bee.status === 'done')
      .map((bee) => ({ kind: 'report' as const, from: bee.name, to: HOME, text: bee.said, id: `report:${bee.name}` }))
  );
}

/**
 * The lines a snapshot alone can tell, for the transcript's miniature: the board's latest notes and whom each
 * reached, or the sub-agents that reported back. No receipts are read here, so nothing here animates.
 */
export function snapshotLinks(kind: 'hive' | 'delegate', bees: readonly HiveBee[], latest: readonly HiveBoardLine[]) {
  if (kind === 'delegate') return reportLinks(bees);
  return fold(
    latest.flatMap((line, index) =>
      line.to.map((to) => ({ kind: 'delivery' as const, from: line.bee, to, text: '', id: `latest:${index}:${to}` }))
    )
  );
}

/** Whether the run is still going: a delivery that arrives now is one to watch. */
export const isLive = (bees: readonly HiveBee[]): boolean => bees.some((bee) => isBeeActive(bee.status));
