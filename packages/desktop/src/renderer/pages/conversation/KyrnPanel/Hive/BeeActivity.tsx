import React from 'react';
import { useTranslation } from 'react-i18next';
import type { BeeActivity } from '@/common/kyrn/hive';
import { useClock } from '../clock';
import { beeActivityText } from './codes';

/** A bee's last few steps, newest last, each at its time of day and worded in the app language. */
export default function BeeActivityList({ entries }: { entries: BeeActivity[] }) {
  const { t, i18n } = useTranslation();
  const clock = useClock();
  if (!entries.length) return null;
  return (
    <ul className='m-0 p-0 list-none text-12px text-t-secondary'>
      {entries.map((entry, index) => (
        <li key={`${index}:${entry.at}`} className='flex gap-8px py-2px min-w-0'>
          <span className='shrink-0 text-t-tertiary'>{clock(entry.at)}</span>
          <span className='min-w-0 whitespace-pre-wrap break-words' dir='auto'>
            {beeActivityText(t, entry, i18n.language)}
          </span>
        </li>
      ))}
    </ul>
  );
}
