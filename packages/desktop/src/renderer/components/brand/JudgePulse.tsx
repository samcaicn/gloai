import React from 'react';
import { Tooltip } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import styles from './Brand.module.css';

/**
 * What the judge is doing, as far as the shell knows.
 * `idle`: connected, nothing being judged. `judging`: a judgment is in flight. `off`: judging is
 * switched off or the judge cannot be reached. Today the shell always passes `idle`; the state is a
 * prop so that whoever owns judge activity can drive it without touching this component.
 */
export type JudgePulseState = 'idle' | 'judging' | 'off';

export type JudgePulseProps = {
  state?: JudgePulseState;
  className?: string;
};

/**
 * The small dot beside the mu wordmark. It only ever says what it is told: it never infers activity
 * from timing, and `judging` is a breathing dot, not a claim that anything was decided.
 */
const JudgePulse: React.FC<JudgePulseProps> = ({ state = 'idle', className }) => {
  const { t } = useTranslation();
  const label = t(`common.kyrn.brand.judge.${state}`);
  return (
    <Tooltip content={label} position='bottom' mini>
      <span
        className={[styles.pulse, className].filter(Boolean).join(' ')}
        data-state={state}
        data-testid='mu-judge-pulse'
        role='status'
        aria-label={label}
      >
        <span className={styles.pulseDot} />
      </span>
    </Tooltip>
  );
};

export default JudgePulse;
