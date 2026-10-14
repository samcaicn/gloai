import React from 'react';
import type { HiveBee, HiveBoardLine, SwarmKind } from '@/common/kyrn/hive';
import { isBeeActive } from '@/common/kyrn/hive';
import styles from './Graph.module.css';
import { HOME, snapshotLinks } from './links';

const SIZE = 22;
const RING = 7.5;
const MOST = 12;

/**
 * The map in one glance, for the sub-agent card in the transcript: a dot per bee on a small ring (filled while it
 * works or once it is done, hollow before it starts or when it stopped badly), a hairline for each pair the board's
 * latest notes went between, or from each sub-agent that reported back to the centre. No words: the card's lines
 * say the rest.
 */
export default function HiveMiniature({
  kind,
  bees,
  latest,
}: {
  kind: SwarmKind;
  bees: readonly HiveBee[];
  latest: readonly HiveBoardLine[];
}) {
  const shown = bees.slice(0, MOST);
  const centre = SIZE / 2;
  const points = new Map<string, { x: number; y: number }>([[HOME, { x: centre, y: centre }]]);
  shown.forEach((bee, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / shown.length;
    points.set(bee.name, { x: centre + RING * Math.cos(angle), y: centre + RING * Math.sin(angle) });
  });
  const links = snapshotLinks(kind, shown, latest).filter((link) => points.has(link.from) && points.has(link.to));
  return (
    <svg
      className={styles.miniature}
      width={SIZE}
      height={SIZE}
      viewBox={`0 0 ${SIZE} ${SIZE}`}
      aria-hidden='true'
      data-testid='hive-miniature'
      data-links={links.length}
    >
      {links.map((link) => {
        const from = points.get(link.from)!;
        const to = points.get(link.to)!;
        return (
          <line
            key={`${link.from}>${link.to}`}
            className={styles.miniLine}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
          />
        );
      })}
      {kind === 'delegate' && <circle className={styles.miniDot} cx={centre} cy={centre} r={2.2} data-filled='true' />}
      {shown.map((bee) => {
        const point = points.get(bee.name)!;
        const filled = isBeeActive(bee.status) || bee.status === 'done';
        return (
          <circle key={bee.name} className={styles.miniDot} cx={point.x} cy={point.y} r={2.2} data-filled={filled} />
        );
      })}
    </svg>
  );
}
