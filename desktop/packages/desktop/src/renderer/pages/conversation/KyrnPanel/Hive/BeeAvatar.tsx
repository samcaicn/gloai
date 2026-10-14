import React from 'react';
import { Bee } from '@icon-park/react';
import type { HiveBeeStatus } from '@/common/kyrn/hive';
import styles from './Hive.module.css';

export default function BeeAvatar({
  name,
  status = 'unknown',
  small = false,
}: {
  name: string;
  status?: HiveBeeStatus;
  small?: boolean;
}) {
  const seed = [...name].reduce((sum, char) => sum + (char.codePointAt(0) ?? 0), 0) % 3;
  return (
    <span className={`${styles.avatar} ${small ? styles.avatarSmall : ''}`} data-tone={seed} aria-hidden='true'>
      <Bee size={small ? 16 : 24} strokeWidth={1.7} />
      <span className={styles.statusDot} data-status={status} />
    </span>
  );
}
