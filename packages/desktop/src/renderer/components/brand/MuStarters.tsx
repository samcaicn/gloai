import React from 'react';
import { Button } from '@arco-design/web-react';
import { BranchTwo, Bug, FileSearchTwo } from '@icon-park/react';
import { useTranslation } from 'react-i18next';
import styles from './Brand.module.css';

/** The three starting points of an empty conversation. The prompt text is what lands in the input. */
export const MU_STARTERS = [
  { key: 'understand', icon: FileSearchTwo },
  { key: 'fix', icon: Bug },
  { key: 'plan', icon: BranchTwo },
] as const;

export type MuStartersProps = {
  /** Receives the localized prompt of the chosen starting point. */
  onPick: (prompt: string) => void;
  className?: string;
};

/**
 * Three suggested starting points for a new conversation. Picking one only fills the input: nothing
 * is sent until the user presses send.
 */
const MuStarters: React.FC<MuStartersProps> = ({ onPick, className }) => {
  const { t } = useTranslation();
  return (
    <div className={[styles.starters, className].filter(Boolean).join(' ')} data-testid='mu-starters'>
      <div className={styles.startersHint}>{t('common.kyrn.brand.starters.hint')}</div>
      <div className={styles.startersGrid}>
        {MU_STARTERS.map(({ key, icon: Icon }) => (
          <Button
            key={key}
            type='text'
            className={styles.starter}
            data-testid={`mu-starter-${key}`}
            onClick={() => onPick(t(`common.kyrn.brand.starters.${key}.prompt`))}
          >
            <span className={styles.starterIcon}>
              <Icon theme='outline' size='16' />
            </span>
            <span className={styles.starterText}>
              <span className={styles.starterTitle}>{t(`common.kyrn.brand.starters.${key}.title`)}</span>
              <span className={styles.starterDesc}>{t(`common.kyrn.brand.starters.${key}.desc`)}</span>
            </span>
          </Button>
        ))}
      </div>
    </div>
  );
};

export default MuStarters;
