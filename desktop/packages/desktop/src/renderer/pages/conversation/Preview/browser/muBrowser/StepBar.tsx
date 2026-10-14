import React, { useEffect, useState } from 'react';
import { Button, Tooltip } from '@arco-design/web-react';
import { Down, HandUp, Pause, PlayOne, Square, Up } from '@icon-park/react';
import { useTranslation } from 'react-i18next';
import { kyrnBrowserBridge } from '@/common/kyrn/browserBridge';
import type { BrowserControlAction, BrowserRunNotice, BrowserRunState, BrowserRunStep } from '@/common/kyrn/browserRun';
import {
  barTone,
  clock,
  confidencePercent,
  elapsedMs,
  hostOf,
  pausedKey,
  permissionKey,
  reasonLine,
  reasonText,
  statusHintKey,
  statusKey,
  verbKey,
} from './format';
import styles from './StepBar.module.css';

const StepLine: React.FC<{ step: BrowserRunStep; compact?: boolean }> = ({ step, compact }) => {
  const { t } = useTranslation();
  const percent = confidencePercent(step);
  return (
    <span className={styles.line}>
      <span className={styles.number}>{t('preview.muBrowser.stepNumber', { step: step.step })}</span>
      <span className={styles.dot}>·</span>
      <span className={styles.verb}>{t(verbKey(step.kind))}</span>
      {/* The label is a web page's own text: a React text node, never markup. */}
      {step.action && step.kind !== 'wait' && <span className={styles.target}>{step.action}</span>}
      {percent !== undefined && (
        <>
          <span className={styles.dot}>·</span>
          <span className={styles.confidence}>{t('preview.muBrowser.confidence', { percent })}</span>
        </>
      )}
      {!compact && step.pageChanged === false && (
        <span className={styles.unchanged}>{t('preview.muBrowser.noChange')}</span>
      )}
    </span>
  );
};

/** One refusal under the bar. A permission arrives as Electron's id ("geolocation") and is named in the person's words. */
const NoticeLine: React.FC<{ notice: BrowserRunNotice }> = ({ notice }) => {
  const { t } = useTranslation();
  const named = notice.kind === 'permission' ? permissionKey(notice.detail) : undefined;
  return (
    <div className={styles.hint} data-kind='notice'>
      {t(`preview.muBrowser.notice.${notice.kind}`, { detail: named ? t(named) : notice.detail })}
    </div>
  );
};

/**
 * The slim bar above a page that mu is driving: what it just did and how sure the judge was, the steps so far, the
 * goal, the time, and the three things a person can do about it. It stays after the run with the final status.
 */
const StepBar: React.FC<{ run: BrowserRunState }> = ({ run }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const running = run.phase === 'running';

  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  const send = (action: BrowserControlAction): void => {
    void kyrnBrowserBridge.control.invoke({ tabId: run.tabId, action });
  };
  const last = run.steps.at(-1);
  const notice = run.notices.at(-1);
  const tone = barTone(run);
  const hint = running ? undefined : statusHintKey(run.status);
  const reason = reasonLine(run);

  return (
    <div className={styles.bar} data-tone={tone} data-testid='mu-step-bar'>
      <div className={styles.row}>
        <span className={styles.pulse} aria-hidden />
        <div className={styles.now}>
          {!running && run.status ? (
            <span className={styles.status}>{t(statusKey(run.status))}</span>
          ) : run.stopRequested ? (
            <span className={styles.status}>{t('preview.muBrowser.stopping')}</span>
          ) : run.paused ? (
            <span className={styles.status}>{t('preview.muBrowser.pausedShort')}</span>
          ) : null}
          {last ? (
            <StepLine step={last} compact />
          ) : (
            <span className={styles.line}>{t('preview.muBrowser.observing')}</span>
          )}
        </div>
        <span className={styles.time} title={t('preview.muBrowser.elapsed')}>
          {clock(elapsedMs(run, now))}
        </span>
        <Button
          size='mini'
          type='text'
          className={styles.toggle}
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          icon={open ? <Up size='12' /> : <Down size='12' />}
        >
          {t('preview.muBrowser.steps', { total: run.steps.length })}
        </Button>
        {running && (
          <div className={styles.controls}>
            {run.paused ? (
              <Button size='mini' type='primary' icon={<PlayOne size='12' />} onClick={() => send('resume')}>
                {t('preview.muBrowser.resume')}
              </Button>
            ) : (
              <Button size='mini' icon={<Pause size='12' />} disabled={run.stopRequested} onClick={() => send('pause')}>
                {t('preview.muBrowser.pause')}
              </Button>
            )}
            <Tooltip content={t('preview.muBrowser.takeoverTip')}>
              <Button
                size='mini'
                icon={<HandUp size='12' />}
                disabled={run.stopRequested || run.pausedBy === 'takeover'}
                onClick={() => send('takeover')}
              >
                {t('preview.muBrowser.takeover')}
              </Button>
            </Tooltip>
            <Button
              size='mini'
              status='danger'
              icon={<Square size='12' />}
              disabled={run.stopRequested}
              onClick={() => send('stop')}
            >
              {t('preview.muBrowser.stop')}
            </Button>
          </div>
        )}
      </div>

      {running && run.paused && <div className={styles.hint}>{t(pausedKey(run.pausedBy))}</div>}
      {!running && hint && <div className={styles.hint}>{t(hint)}</div>}
      {notice && <NoticeLine notice={notice} />}

      {open && (
        <div className={styles.details}>
          {(run.goal || run.startUrl) && (
            <div className={styles.goal}>
              <span className={styles.label}>{t('preview.muBrowser.goal')}</span>
              <span className={styles.goalText}>{run.goal || hostOf(run.startUrl) || run.startUrl}</span>
            </div>
          )}
          {reason && (
            <div className={styles.goal}>
              <span className={styles.label}>{t(reason.labelKey)}</span>
              <span className={styles.goalText}>{reasonText(reason, t)}</span>
            </div>
          )}
          {run.steps.length === 0 ? (
            <div className={styles.empty}>{t('preview.muBrowser.noSteps')}</div>
          ) : (
            <ol className={styles.list}>
              {run.steps.map((step) => (
                <li key={`${step.step}-${step.at}`}>
                  <StepLine step={step} />
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </div>
  );
};

export default StepBar;
