import React, { useState } from 'react';
import { Alert, Button } from '@arco-design/web-react';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import MuMark from '@renderer/components/brand/MuMark';
import LanguageSwitcher from '@/renderer/components/settings/LanguageSwitcher';
import ChoiceTile from '@/renderer/pages/settings/KyrnSettings/fields/ChoiceTile';
import MuErrorMessage from '@/renderer/pages/settings/KyrnSettings/fields/MuErrorMessage';
import { choiceOf } from '@/renderer/pages/settings/KyrnSettings/judgeChoice';
import { providerLabel } from '@/renderer/pages/settings/KyrnSettings/providers/endpoints';
import choiceStyles from '@/renderer/pages/settings/KyrnSettings/sections/sections.module.css';
import { useMuSettings } from '@/renderer/pages/settings/KyrnSettings/useMuSettings';
import { useIlinkSessionOptional } from '@renderer/services/ilinkSession';
import { AIAPI_HOST, markOnboardingSeen, withCloudModel } from './onboarding';
import IlinkStep from './IlinkStep';
import styles from './Welcome.module.css';

type Step = 'intro' | 'cloud' | 'ilink' | 'done';
/** The steps of the setup itself; the introduction before them is not one. */
const STEPS: Step[] = ['cloud', 'ilink', 'done'];
const INTRO_POINTS = ['judge', 'control', 'ready'] as const;

/**
 * The first-run guide: what mu is, then connect to the AiBuddy cloud, done. One question at a time, nothing that can
 * wait; all of it is written in one save at the end, and every step can be skipped. The cloud is the only backend, so
 * the guide asks for no API endpoint or key, and the judge is the built-in cloud one. Everything here is also in the
 * settings.
 */
export default function Welcome() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const mu = useMuSettings();
  /** 微信那一步只是顺带一提：这份登录态读不到（单测里没有 provider）就当没连。 */
  const ilink = useIlinkSessionOptional();
  const [step, setStep] = useState<Step>('intro');

  const { base, draft } = mu;

  const leave = () => {
    markOnboardingSeen();
    navigate('/guid', { replace: true });
  };

  const nextFromCloud = () => {
    if (!draft) return setStep('ilink');
    mu.edit(() => withCloudModel(draft).draft);
    setStep('ilink');
  };

  const finish = async () => {
    if (mu.dirty.size === 0 || (await mu.save())) leave();
  };

  if (!base || !draft) {
    return (
      <div className={styles.screen} data-testid='mu-welcome'>
        <div className={styles.drag} />
        <main className={styles.column}>
          {mu.error ? (
            <Alert
              type='error'
              title={t('mu.load.failed')}
              content={<MuErrorMessage error={mu.error} />}
              action={<Button onClick={mu.reload}>{t('mu.reload')}</Button>}
            />
          ) : (
            <MuMark size={48} halo />
          )}
        </main>
      </div>
    );
  }

  const { settings } = draft;
  const judge = choiceOf(settings);

  let body: React.ReactNode;
  if (step === 'intro') {
    body = (
      <>
        <h1 className={styles.heroTitle}>{t('mu.welcome.intro.title')}</h1>
        <p className={styles.lead}>{t('mu.welcome.intro.lead')}</p>
        <ul className={styles.points}>
          {INTRO_POINTS.map((point) => (
            <li key={point} className={styles.point}>
              <span className={styles.pointMark} aria-hidden='true' />
              <div>
                <div className={styles.pointTitle}>{t(`mu.welcome.intro.points.${point}.title`)}</div>
                <div className={styles.pointText}>{t(`mu.welcome.intro.points.${point}.text`)}</div>
              </div>
            </li>
          ))}
        </ul>
      </>
    );
  } else if (step === 'cloud') {
    body = (
      <>
        <h1 className={styles.title}>{t('mu.welcome.cloud.title')}</h1>
        <p className={styles.subtitle}>{t('mu.welcome.cloud.subtitle')}</p>
        <div className={choiceStyles.choices} role='radiogroup' aria-label={t('mu.welcome.cloud.title')}>
          <ChoiceTile
            testId='mu-welcome-way-cloud'
            title={t('mu.welcome.cloud.title')}
            tag={t('mu.welcome.cloud.tag')}
            description={t('mu.welcome.cloud.description', { host: AIAPI_HOST })}
            active
            onPick={() => {}}
          />
        </div>
      </>
    );
  } else if (step === 'ilink') {
    // Optional on purpose: the QR is here once, in the guide, and again in the settings.
    body = (
      <>
        <h1 className={styles.title}>{t('mu.welcome.ilink.title')}</h1>
        <p className={styles.subtitle}>{t('mu.welcome.ilink.subtitle')}</p>
        <IlinkStep />
      </>
    );
  } else {
    const { provider: startProvider, model } = settings.models.defaults;
    body = (
      <>
        <h1 className={styles.title}>{t('mu.welcome.done.title')}</h1>
        <p className={styles.subtitle}>{t('mu.welcome.done.subtitle')}</p>
        <dl className={styles.summary}>
          <div className={styles.summaryRow}>
            <dt>{t('mu.welcome.done.model')}</dt>
            <dd>
              {startProvider && model
                ? t('mu.welcome.done.modelValue', {
                    provider: providerLabel(t, settings.models, startProvider),
                    model,
                  })
                : t('mu.welcome.done.none')}
            </dd>
          </div>
          <div className={styles.summaryRow}>
            <dt>{t('mu.welcome.done.judge')}</dt>
            <dd>{judge ? t(`mu.judges.choices.${judge}.title`) : t('mu.welcome.done.none')}</dd>
          </div>
          <div className={styles.summaryRow}>
            <dt>{t('mu.welcome.done.ilink')}</dt>
            <dd>{ilink?.phase === 'signedIn' ? t('mu.welcome.done.ilinkOn') : t('mu.welcome.done.none')}</dd>
          </div>
        </dl>
        {mu.error ? (
          <Alert
            type='error'
            content={
              <MuErrorMessage error={mu.error} frame={(reason) => t('mu.welcome.done.saveFailed', { reason })} />
            }
          />
        ) : null}
      </>
    );
  }

  const index = STEPS.indexOf(step);
  const back = () => setStep(index <= 0 ? 'intro' : STEPS[index - 1]);
  return (
    <div className={styles.screen} data-testid='mu-welcome'>
      <div className={styles.drag} />
      <main className={classNames(styles.column, step === 'intro' && styles.columnIntro)}>
        <header className={styles.header}>
          <div className={styles.headerTop}>
            <MuMark size={step === 'intro' ? 64 : 40} halo />
            {/* Someone on a system in another language can switch before reading a word of the guide. */}
            <div
              className={styles.language}
              role='group'
              aria-label={t('settings.language')}
              data-testid='mu-welcome-language'
            >
              <span>{t('settings.language')}</span>
              <LanguageSwitcher />
            </div>
          </div>
          {step === 'intro' ? null : (
            <ol className={styles.steps} aria-label={t('mu.welcome.progress')}>
              {STEPS.map((name, position) => (
                <li
                  key={name}
                  aria-current={name === step ? 'step' : undefined}
                  className={classNames(styles.stepItem, position <= index && styles.stepReached)}
                >
                  <span className={styles.stepDot} aria-hidden='true' />
                  {t(`mu.welcome.steps.${name}`)}
                </li>
              ))}
            </ol>
          )}
        </header>
        <section key={step} className={styles.body} data-testid={`mu-welcome-step-${step}`}>
          {body}
        </section>
        <footer className={styles.footer}>
          {step === 'intro' ? (
            <button type='button' className={styles.textButton} onClick={leave}>
              {t('mu.welcome.skipAll')}
            </button>
          ) : (
            <button type='button' className={styles.textButton} onClick={back}>
              {t('mu.welcome.back')}
            </button>
          )}
          <span className={styles.spacer} />
          {/* Skipping is a quiet text button on both steps; the lavender button is always the way on. */}
          {step === 'cloud' || step === 'ilink' ? (
            <button
              type='button'
              className={styles.textButton}
              data-testid='mu-welcome-skip'
              onClick={() => setStep(step === 'cloud' ? 'ilink' : 'done')}
            >
              {t('mu.welcome.skipStep')}
            </button>
          ) : null}
          {step === 'intro' ? (
            <Button type='primary' shape='round' data-testid='mu-welcome-begin' onClick={() => setStep('cloud')}>
              {t('mu.welcome.intro.start')}
            </Button>
          ) : step === 'cloud' ? (
            <Button type='primary' shape='round' data-testid='mu-welcome-next' onClick={nextFromCloud}>
              {t('mu.welcome.next')}
            </Button>
          ) : step === 'ilink' ? (
            <Button type='primary' shape='round' data-testid='mu-welcome-next' onClick={() => setStep('done')}>
              {t('mu.welcome.next')}
            </Button>
          ) : (
            <Button
              type='primary'
              shape='round'
              data-testid='mu-welcome-start'
              loading={mu.saving}
              onClick={() => void finish()}
            >
              {t('mu.welcome.start')}
            </Button>
          )}
        </footer>
      </main>
    </div>
  );
}
