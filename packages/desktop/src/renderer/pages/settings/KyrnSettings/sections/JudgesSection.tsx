import React from 'react';
import { Tag } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import SectionShell, { Card } from './SectionShell';
import styles from './sections.module.css';

/**
 * The judges page. While it works, AiBuddy asks itself many small questions: how to handle this
 * message, whether this step breaks a rule the person set. The cloud product answers them with the
 * best setup for each task on its own, so there is nothing here to choose or combine: the three
 * judge kinds, Jev's services, their keys and the order of several judges are gone from the UI.
 *
 * Everything behind them stays as it is — the profiles in judgeChoice.ts, the store, the harness,
 * LocalJudgePanel and ClmServerCheck — for whoever configures mu by hand or for an expert mode
 * later; only the way in is closed.
 */
export default function JudgesSection() {
  const { t } = useTranslation();
  return (
    <SectionShell id='judges' title={t('mu.sections.judges')} description={t('mu.judges.auto.description')}>
      <Card testId='mu-judges-auto'>
        <div className={styles.autoPanel}>
          <div className={styles.autoHead}>
            <Tag size='small' color='arcoblue'>
              {t('mu.judges.auto.status')}
            </Tag>
            <span className={styles.autoTitle}>{t('mu.judges.auto.title')}</span>
          </div>
          <div className={styles.choiceHint}>{t('mu.judges.auto.footnote')}</div>
        </div>
      </Card>
    </SectionShell>
  );
}
