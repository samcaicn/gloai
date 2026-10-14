import React from 'react';
import { Button, Dropdown, Menu } from '@arco-design/web-react';
import { Cube, More } from '@icon-park/react';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '@/renderer/services/i18n/format';
import styles from './providers.module.css';

/** ready: usable as it is. attention: something to fill in or save. off: not signed in. busy: signing in now. */
export type DotState = 'ready' | 'attention' | 'off' | 'busy';

const DOT: Record<DotState, string> = {
  ready: styles.dotReady,
  attention: styles.dotAttention,
  off: styles.dotOff,
  busy: styles.dotBusy,
};

/** The state of a provider at the end of its row in the list, with its words for a screen reader and on hover. */
export function StatusDot({ state, label }: { state: DotState; label: string }) {
  return (
    <span className={classNames(styles.dot, DOT[state])} role='img' aria-label={label} title={label} data-dot={state} />
  );
}

/** The mark of a provider that has no brand of its own: an endpoint someone set up. */
export const EndpointMark = ({ size = 18 }: { size?: number }) => (
  <Cube theme='outline' size={size} fill='currentColor' strokeWidth={3} />
);

type HeadProps = {
  mark: React.ReactNode;
  title: React.ReactNode;
  /** Tags right of the title. */
  badges?: React.ReactNode;
  /** Right end: a button, the more-actions menu. */
  actions?: React.ReactNode;
};

/** The head of the right pane: which provider this is, what state it is in, and what can be done to it. */
export function ProviderHead({ mark, title, badges, actions }: HeadProps) {
  return (
    <div className={styles.editorHead}>
      <span className={styles.headMark}>{mark}</span>
      <span className={styles.editorTitle}>{title}</span>
      {badges}
      <span className={styles.spacer} />
      {actions}
    </div>
  );
}

/**
 * The more-actions button of a provider, with its one action: removing it. What removing means (and the question
 * before it) is the caller's.
 */
export function RemoveMenu({
  disabled,
  onRemove,
  testId,
}: {
  disabled?: boolean;
  onRemove: () => void;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <Dropdown
      trigger='click'
      position='br'
      disabled={disabled}
      droplist={
        <Menu onClickMenuItem={(key) => key === 'remove' && onRemove()}>
          <Menu.Item key='remove' data-testid={`${testId}-remove`}>
            <span className={styles.danger}>{t('mu.providers.removeProvider')}</span>
          </Menu.Item>
        </Menu>
      }
    >
      <Button
        size='small'
        type='text'
        disabled={disabled}
        aria-label={t('mu.providers.more')}
        data-testid={testId}
        icon={<More theme='outline' size='18' fill='currentColor' />}
      />
    </Dropdown>
  );
}

/**
 * A context window or output limit the way model lists write it: 128K, 1.5M. The K and M stay as they are in every
 * language (compact notation would give "12,8万" or a German "128.000"), but the number is the app language's: 1,5M
 * in German. A value that rounds to a thousand K is 1M, not "1,000K".
 */
export function tokenSize(value: number, language?: string): string {
  const thousands = Math.round(value / 1000);
  if (thousands >= 1000) return `${formatNumber(value / 1_000_000, language, { maximumFractionDigits: 1 })}M`;
  if (value >= 1000) return `${formatNumber(thousands, language)}K`;
  return formatNumber(value, language);
}
