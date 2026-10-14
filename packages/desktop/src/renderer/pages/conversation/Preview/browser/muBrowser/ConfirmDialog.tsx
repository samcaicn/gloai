import React, { useEffect, useState } from 'react';
import { Button, Modal } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import { kyrnBrowserBridge } from '@/common/kyrn/browserBridge';
import type { BrowserRunState } from '@/common/kyrn/browserRun';
import { secondsLeft } from './format';
import styles from './ConfirmDialog.module.css';

/**
 * mu wants to press something that looks irreversible (pay, delete, send) and asks the person who is watching.
 *
 * The label and the address come from a web page. They are rendered as React text nodes and nothing else: no HTML,
 * no markdown, no links. Silence is a refusal: the main process counts the 120 seconds and decides; the countdown
 * here only shows it. Closing the dialog any other way than "Allow" is a refusal too.
 */
const ConfirmDialog: React.FC<{ run: BrowserRunState }> = ({ run }) => {
  const { t } = useTranslation();
  const confirm = run.confirm;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!confirm) return undefined;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, [confirm]);

  if (!confirm) return null;
  const answer = (allowed: boolean): void => {
    void kyrnBrowserBridge.confirm.invoke({ tabId: run.tabId, id: confirm.id, allowed });
  };

  return (
    <Modal
      visible
      title={t('preview.muBrowser.confirm.title')}
      maskClosable={false}
      escToExit
      onCancel={() => answer(false)}
      footer={
        <div className={styles.footer}>
          <span className={styles.countdown} aria-live='polite'>
            {t('preview.muBrowser.confirm.countdown', { seconds: secondsLeft(confirm.deadline, now) })}
          </span>
          <Button onClick={() => answer(false)}>{t('preview.muBrowser.confirm.deny')}</Button>
          <Button type='primary' status='warning' onClick={() => answer(true)}>
            {t('preview.muBrowser.confirm.allow')}
          </Button>
        </div>
      }
    >
      <div className={styles.body} data-testid='mu-confirm'>
        <div className={styles.field}>
          <span className={styles.label}>{t('preview.muBrowser.confirm.action')}</span>
          <span className={styles.action}>{confirm.label}</span>
        </div>
        <div className={styles.field}>
          <span className={styles.label}>{t('preview.muBrowser.confirm.page')}</span>
          <span className={styles.url}>{confirm.url}</span>
        </div>
        {run.goal && (
          <div className={styles.field}>
            <span className={styles.label}>{t('preview.muBrowser.goal')}</span>
            <span>{run.goal}</span>
          </div>
        )}
        <p className={styles.note}>{t('preview.muBrowser.confirm.untrusted')}</p>
      </div>
    </Modal>
  );
};

export default ConfirmDialog;
