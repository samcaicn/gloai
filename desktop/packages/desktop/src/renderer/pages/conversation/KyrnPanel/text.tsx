import React from 'react';
import classNames from 'classnames';
import type { TFunction } from 'i18next';
import { formatDuration } from '@/renderer/services/i18n/format';

/**
 * An error as the panel shows it: a translated headline the person can act on, and the raw message of the harness,
 * the bridge or the OS under it as a detail. The detail is never the only text. A line in the page's own colours,
 * marked by a rule at its start: the panel tints nothing, errors included.
 */
export function ErrorNotice({ title, detail, className }: { title: string; detail?: string; className?: string }) {
  return (
    <div
      role='alert'
      className={classNames('flex flex-col gap-2px min-w-0 ps-8px py-2px', className)}
      style={{ borderInlineStart: '2px solid var(--text-primary)' }}
    >
      <span className='text-13px leading-20px text-t-primary font-500'>{title}</span>
      {detail ? (
        <span className='text-12px leading-18px text-t-secondary whitespace-pre-wrap break-words' dir='auto'>
          {detail}
        </span>
      ) : null}
    </div>
  );
}

const amount = (value: unknown): number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0;

/** What a bee has done so far, one plural-aware part per count: "3 turns · 1 tool call · 0 shared · 2 received". */
export function beeCounters(
  t: TFunction,
  bee: { turns?: unknown; toolCalls?: unknown; published?: unknown; received?: unknown }
): string {
  return [
    t('common.kyrn.beeCounts.turns', { count: amount(bee.turns) }),
    t('common.kyrn.beeCounts.tools', { count: amount(bee.toolCalls) }),
    t('common.kyrn.beeCounts.shared', { count: amount(bee.published) }),
    t('common.kyrn.beeCounts.received', { count: amount(bee.received) }),
  ].join(' · ');
}

/** How long a bee has been silent, in minutes and seconds of the app language rather than a raw count of seconds. */
export function quietLabel(t: TFunction, quietMs: number, language: string | undefined): string {
  return t('common.kyrn.quiet', { duration: formatDuration(quietMs, language, 'short') });
}
