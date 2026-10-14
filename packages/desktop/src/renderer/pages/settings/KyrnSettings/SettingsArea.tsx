import React, { useState } from 'react';
import { Alert, Button, Message, Spin } from '@arco-design/web-react';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import { formatNameList } from '@/renderer/services/i18n/list';
import SettingsPageHeader from '../components/SettingsPageHeader';
import type { DecisionPage, FeaturePage } from '../settingsNav';
import { SECTIONS, isDecisionPage, isFeaturePage, manifestOf, type SectionId } from './draft';
import MuErrorMessage from './fields/MuErrorMessage';
import ContextRows from './sections/ContextRows';
import DecisionsSection from './sections/DecisionsSection';
import FeaturesSection, { FeatureOptions } from './sections/FeaturesSection';
import JudgesSection from './sections/JudgesSection';
import ProvidersSection, { DefaultModelSection } from './sections/ModelsSection';
import { useMuSettings, useSharedMuSettings, type MuSettings } from './useMuSettings';
import styles from './SettingsArea.module.css';

/**
 * What the area shows: one section; for the decision points and the other features, the page of one group; on a
 * features page, the options of one feature, and which page of them (from 1) when they fill more than one.
 */
export type AreaView = { section: SectionId; page?: DecisionPage | FeaturePage; feature?: string; part?: number };

type SettingsAreaProps = {
  /** From the route: this section alone, with no list of sections (the settings rail is the list). */
  section?: SectionId;
  /** From the route, with the decision points or the other features: the page of one group. */
  page?: DecisionPage | FeaturePage;
  /** With a features section: the feature whose options are open. */
  feature?: string;
  /** With a feature: the page of its options shown. */
  part?: number;
  /** A move made inside a routed section: to a feature's options, or back to its list. */
  onView?: (view: AreaView) => void;
};

/**
 * Everything mu can be told: providers, the default model, judges and their tiers, decision points, features, context.
 * They share one draft and one save, because the files behind them share one revision.
 *
 * In the settings the section comes from the route, and the draft from {@link MuSettingsProvider} around every
 * settings page: a change typed on one page is still there, unsaved, on the next. Alone (no route), the area loads its
 * own draft and shows a list of its sections to pick from.
 */
export default function SettingsArea(props: SettingsAreaProps = {}) {
  const shared = useSharedMuSettings();
  return shared ? <Area {...props} mu={shared} /> : <OwnDraft {...props} />;
}

function OwnDraft(props: SettingsAreaProps) {
  return <Area {...props} mu={useMuSettings()} />;
}

function Area({ section: routed, page, feature, part, onView, mu }: SettingsAreaProps & { mu: MuSettings }) {
  const { t, i18n } = useTranslation();
  // The hook form needs no global React adapter, unlike the static Message.
  const [message, messageHolder] = Message.useMessage();
  const [picked, setPicked] = useState<AreaView>({ section: 'providers' });
  const view: AreaView = routed ? { section: routed, page, feature, part } : picked;
  const go = (next: AreaView) => (routed ? onView?.(next) : setPicked(next));
  // The decision points' search. Kept here so it survives a visit to another section of the same area.
  const [query, setQuery] = useState('');
  const { base, draft, dirty, error } = mu;
  const manifest = manifestOf(base);

  const save = async () => {
    if (await mu.save()) message.success?.(t('mu.save.saved'));
  };

  let content: React.ReactNode = null;
  if (base && draft) {
    const { settings } = draft;
    const { section } = view;
    const onKey = (variable: string, value: string) =>
      mu.edit((now) => ({ ...now, judgeKeys: { ...now.judgeKeys, [variable]: value } }));
    if (section === 'providers')
      content = <ProvidersSection draft={draft} base={base} available={mu.available} onDraft={mu.edit} />;
    else if (section === 'defaultModel')
      content = <DefaultModelSection draft={draft} base={base} available={mu.available} onDraft={mu.edit} />;
    else if (section === 'judges')
      content = <JudgesSection draft={draft} base={base} onChange={mu.editSettings} onKey={onKey} />;
    else if (section === 'decisions' || section === 'context') {
      // The context settings are the top of the decision points' context page: one page for context.
      const shown = section === 'context' ? 'context' : isDecisionPage(view.page) ? view.page : undefined;
      content = (
        <DecisionsSection
          page={shown}
          settings={settings}
          manifest={manifest}
          query={query}
          onQuery={setQuery}
          onChange={mu.editSettings}
          lead={shown === 'context' ? <ContextRows settings={settings} base={base} onChange={mu.editSettings} /> : null}
        />
      );
    } else if ((section === 'features' || section === 'moreFeatures') && view.feature) {
      const { feature: name } = view;
      const group = section === 'moreFeatures' && isFeaturePage(view.page) ? view.page : undefined;
      content = (
        <FeatureOptions
          list={section}
          page={group}
          name={name}
          part={view.part}
          settings={settings}
          manifest={manifest}
          onChange={mu.editSettings}
          onBack={() => go({ section, page: group })}
          onPart={(next) => go({ section, page: group, feature: name, part: next })}
        />
      );
    } else if (section === 'features' || section === 'moreFeatures') {
      const group = section === 'moreFeatures' && isFeaturePage(view.page) ? view.page : undefined;
      content = (
        <FeaturesSection
          list={section}
          page={group}
          settings={settings}
          manifest={manifest}
          onChange={mu.editSettings}
          onOpen={(name) => go({ section, page: group, feature: name })}
        />
      );
    }
  }

  return (
    <div className={styles.area} data-testid='kyrn-settings'>
      {messageHolder}
      {/* Not sticky: the section list is what stays in view here. */}
      {routed ? null : <SettingsPageHeader sticky={false} title={t('mu.title')} description={t('mu.description')} />}
      {base?.harness.status === 'unsupported' ? (
        <Alert type='warning' content={t('mu.harness.unsupported', { version: base.harness.version })} />
      ) : null}
      {!base || !draft ? (
        error ? (
          <Alert
            type='error'
            title={t('mu.load.failed')}
            content={<MuErrorMessage error={error} />}
            action={<Button onClick={mu.reload}>{t('mu.reload')}</Button>}
          />
        ) : (
          <Spin />
        )
      ) : (
        <div className={classNames(styles.body, routed && styles.bodyRouted)}>
          {routed ? null : (
            <div className={styles.nav} role='tablist' aria-label={t('mu.title')}>
              {SECTIONS.map((id) => (
                <div
                  key={id}
                  role='tab'
                  tabIndex={0}
                  aria-selected={view.section === id}
                  data-testid={`mu-nav-${id}`}
                  className={classNames(styles.navItem, view.section === id && styles.navItemActive)}
                  onClick={() => setPicked({ section: id })}
                  onKeyDown={(event) => (event.key === 'Enter' || event.key === ' ') && setPicked({ section: id })}
                >
                  <span className={styles.navLabel}>{t(`mu.sections.${id}`)}</span>
                  {dirty.has(id) ? <span className={styles.dot} aria-label={t('mu.save.unsavedDot')} /> : null}
                </div>
              ))}
            </div>
          )}
          <div className={styles.content} role={routed ? undefined : 'tabpanel'}>
            <React.Fragment key={mu.generation}>{content}</React.Fragment>
            <div className={styles.note}>{t('mu.applyNote')}</div>
            {dirty.size || error ? (
              <div className={styles.saveBar} data-testid='mu-save-bar'>
                <span className={styles.saveText}>
                  {dirty.size
                    ? t('mu.save.unsaved', {
                        sections: formatNameList(
                          SECTIONS.filter((id) => dirty.has(id)).map((id) => t(`mu.sections.${id}`)),
                          i18n.language
                        ),
                      })
                    : t('mu.save.nothing')}
                </span>
                {error?.stale ? (
                  <Button size='small' onClick={mu.reload}>
                    {t('mu.reload')}
                  </Button>
                ) : null}
                <Button size='small' disabled={mu.saving || !dirty.size} onClick={mu.discard}>
                  {t('mu.save.discard')}
                </Button>
                <Button
                  size='small'
                  type='primary'
                  loading={mu.saving}
                  disabled={!dirty.size}
                  onClick={() => void save()}
                >
                  {t('common.save')}
                </Button>
                {error ? (
                  <div className={styles.saveError} role='alert'>
                    {error.stale ? (
                      t('mu.save.stale')
                    ) : (
                      <MuErrorMessage
                        error={error}
                        manifest={manifest}
                        frame={(reason) => t('mu.save.failed', { reason })}
                      />
                    )}
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
}
