import React, { useMemo } from 'react';
import { Tooltip } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import type { Activity } from '@/common/kyrn/types';
import { formatNumber } from '@/renderer/services/i18n/format';
import { gaugeReadings, ringArc, wholePercent } from './gauges';
import styles from './GaugeRow.module.css';

/** The ring: 14px across, a 2px stroke. */
const RING_SIZE = 14;
const RING_STROKE = 2;
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;
const RING_CENTER = RING_SIZE / 2;

/**
 * The top of the board tab, one row: how full the model's context is (a thin bar) and how much of the latest reply's
 * input came from the cache (a small ring), each with its number in plain words. Both follow every reply and show 「—」
 * until there is something to show. The tooltips say what the numbers mean; the cache one adds the whole
 * conversation's share. Black and white; only the ring's arc takes the accent. A space between a name and its number
 * keeps them apart when read out or copied; the row's own gap spaces them on screen.
 */
export default function GaugeRow({ events }: { events: Activity[] }) {
  const { t, i18n } = useTranslation();
  const readings = useMemo(() => gaugeReadings(events), [events]);
  const percent = (value: number | undefined) =>
    value === undefined
      ? '—'
      : formatNumber(wholePercent(value) / 100, i18n.language, { style: 'percent', maximumFractionDigits: 0 });
  const pending = t('common.kyrn.gauges.pending');
  const contextHint =
    readings.context === undefined
      ? pending
      : t('common.kyrn.gauges.contextHint', { percent: percent(readings.context) });
  const cacheHint =
    readings.turnCache === undefined && readings.sessionCache === undefined
      ? pending
      : t('common.kyrn.gauges.cacheHint', {
          turn: percent(readings.turnCache),
          session: percent(readings.sessionCache),
        });
  const arc = ringArc(readings.turnCache, RING_CIRCUMFERENCE);

  return (
    <div className={styles.row} data-testid='mu-gauges'>
      <Tooltip content={contextHint}>
        <div className={styles.context} data-testid='mu-gauge-context'>
          <span className={styles.name}>{t('common.kyrn.gauges.context')}</span>{' '}
          <span className={styles.value} data-testid='mu-gauge-context-value'>
            {percent(readings.context)}
          </span>
          <span className={styles.track} aria-hidden='true'>
            {readings.context !== undefined ? (
              <span className={styles.fill} style={{ width: `${Math.min(100, readings.context)}%` }} />
            ) : null}
          </span>
        </div>
      </Tooltip>
      <Tooltip content={cacheHint}>
        <div className={styles.cache} data-testid='mu-gauge-cache'>
          <svg
            className={styles.ring}
            width={RING_SIZE}
            height={RING_SIZE}
            viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
            aria-hidden='true'
          >
            <circle className={styles.ringTrack} cx={RING_CENTER} cy={RING_CENTER} r={RING_RADIUS} />
            {arc ? (
              <circle
                className={styles.ringArc}
                cx={RING_CENTER}
                cy={RING_CENTER}
                r={RING_RADIUS}
                strokeDasharray={`${arc} ${RING_CIRCUMFERENCE}`}
                // From twelve o'clock, clockwise.
                transform={`rotate(-90 ${RING_CENTER} ${RING_CENTER})`}
                data-testid='mu-gauge-cache-arc'
              />
            ) : null}
          </svg>
          <span className={styles.name}>{t('common.kyrn.gauges.cache')}</span>{' '}
          <span className={styles.value} data-testid='mu-gauge-cache-value'>
            {percent(readings.turnCache)}
          </span>
        </div>
      </Tooltip>
    </div>
  );
}
