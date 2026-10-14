import React from 'react';
import { Alert, Button, Input } from '@arco-design/web-react';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import {
  localized,
  localizedTexts,
  type DecisionInfo,
  type DecisionMode,
  type HarnessManifest,
  type Localized,
} from '@/common/kyrn/manifest';
import type { KyrnSettings } from '@/common/kyrn/types';
import { DECISION_PAGES, pageGroupLabelKey, type DecisionPage } from '../../settingsNav';
import { decisionPageOf, isStrayDecision, matches } from '../draft';
import ModeControl, { shownMode, useModeLabels } from '../fields/ModeControl';
import SectionShell, { GroupTitle } from './SectionShell';
import styles from './sections.module.css';

/** Groups the desktop has words for, should a manifest list decisions under one without naming it. */
const KNOWN_GROUPS = new Set(['input', 'context', 'tools', 'turn', 'team']);

/** The first page of the decision points: it holds what applies to all of them, the default mode and the search. */
const FIRST_PAGE: DecisionPage = DECISION_PAGES[0];

type DecisionsSectionProps = {
  /**
   * The page shown: the points of one group. Without one (the area alone, outside the settings rail) every point is
   * shown, under the name of its page.
   */
  page?: DecisionPage;
  settings: KyrnSettings;
  manifest?: HarnessManifest;
  query: string;
  onQuery: (query: string) => void;
  onChange: (change: (settings: KyrnSettings) => KyrnSettings) => void;
  /** Above the points: settings of the page's own that are not decision points (the context page's compaction). */
  lead?: React.ReactNode;
};

/**
 * The name of a group of decision points that has no page of its own: the manifest's own words, else the desktop's
 * for a group it knows, else "Other".
 */
function useGroupTitle(manifest: HarnessManifest | undefined): (group: string) => string {
  const { t, i18n } = useTranslation();
  return (group) =>
    localized(manifest?.groups[group], i18n.language) ||
    (KNOWN_GROUPS.has(group) ? t(`mu.decisions.groups.${group}`) : t('mu.decisions.otherGroup'));
}

type Block = { key: string; title?: string; rows: DecisionInfo[] };

/**
 * The decision points of one page, in plain rows: a name, one sentence and whether it is on. A point without a mode of
 * its own shows the default mode; one with its own mode offers to go back to the default. The first page also holds
 * the default mode and a search over every point, whose results come from all the pages. The context page starts with
 * the compaction settings, so that everything about context is one page.
 */
export default function DecisionsSection({
  page,
  settings,
  manifest,
  query,
  onQuery,
  onChange,
  lead,
}: DecisionsSectionProps) {
  const { t, i18n } = useTranslation();
  const labels = useModeLabels();
  const say = (text?: Localized) => localized(text, i18n.language);
  const groupTitle = useGroupTitle(manifest);
  const first = page === undefined || page === FIRST_PAGE;
  const pageLead = t('mu.decisions.pageLead', { page: t(`mu.pages.decisions.${FIRST_PAGE}`) });
  // The whole list: the area alone, or a search from the first page, which looks through every page.
  const whole = page === undefined || (first && query.trim() !== '');

  const setMode = (id: string, mode: DecisionMode | undefined) =>
    onChange((now) => {
      const { [id]: _removed, ...rest } = now.decisionModes;
      return { ...now, decisionModes: mode ? { ...rest, [id]: mode } : rest };
    });

  const features = new Map(manifest?.features.map((feature) => [feature.name, feature]));
  const decisions = manifest?.decisions ?? [];
  const shown = whole
    ? decisions.filter((decision) => {
        const feature = features.get(decision.feature);
        // Every language is searched: an id or an English word finds its entry in a Chinese screen too.
        return matches(
          query,
          decision.id,
          ...localizedTexts(decision.title),
          ...localizedTexts(decision.summary),
          ...localizedTexts(feature?.title)
        );
      })
    : decisions.filter((decision) => decisionPageOf(decision) === page);

  // A point in a group the rail has no page for keeps its group's name as a heading, on every view it is on.
  const strays = [...new Set([...Object.keys(manifest?.groups ?? {}), ...shown.map((decision) => decision.group)])].map(
    (group): Block => ({
      key: group,
      title: groupTitle(group),
      rows: shown.filter((decision) => isStrayDecision(decision) && decision.group === group),
    })
  );
  const blocks: Block[] = [
    ...(whole
      ? DECISION_PAGES.map(
          (each): Block => ({
            key: each,
            title: t(pageGroupLabelKey(each)),
            rows: shown.filter((decision) => !isStrayDecision(decision) && decisionPageOf(decision) === each),
          })
        )
      : [{ key: page ?? FIRST_PAGE, rows: shown.filter((decision) => !isStrayDecision(decision)) }]),
    ...strays,
  ].filter((block) => block.rows.length > 0);

  return (
    <SectionShell
      id={page ? `decisions-${page}` : 'decisions'}
      title={page ? t(`mu.pages.decisions.${page}`) : t('mu.sections.decisions')}
      description={first ? t('mu.decisions.description') : page === 'context' ? t('mu.context.lead') : pageLead}
      actions={
        first && manifest ? (
          <Input.Search
            allowClear
            size='small'
            className={styles.search}
            aria-label={t('mu.search')}
            placeholder={t('mu.search')}
            value={query}
            onChange={onQuery}
          />
        ) : null
      }
    >
      {first ? (
        <div className={styles.plainList}>
          <div className={styles.plainRow} data-testid='mu-default-mode'>
            <div className={styles.plainMain}>
              <div className={styles.plainText}>
                <div className={styles.plainTitle}>{t('mu.decisions.defaultMode')}</div>
                <div className={styles.plainSummary}>{t('mu.decisions.defaultModeHelp')}</div>
                <div className={styles.plainSummary}>{labels[shownMode(settings.mode)].help}</div>
              </div>
              <div className={styles.plainControl}>
                <ModeControl
                  label={t('mu.decisions.defaultMode')}
                  labels={labels}
                  value={settings.mode}
                  onChange={(mode) => onChange((now) => ({ ...now, mode }))}
                />
              </div>
            </div>
          </div>
        </div>
      ) : null}
      {lead}
      {!manifest ? <Alert type='warning' content={t('mu.harness.tooOld')} /> : null}
      {/* Under the page's own settings the points are a group of the page, which says where their default is. */}
      {lead && blocks.length ? (
        <div className={styles.groupHead}>
          <GroupTitle>{t('mu.sections.decisions')}</GroupTitle>
          <div className={styles.groupHelp}>{pageLead}</div>
        </div>
      ) : null}
      {blocks.map((block) => (
        <React.Fragment key={block.key}>
          {block.title ? <h3 className={styles.groupLabel}>{block.title}</h3> : null}
          <div
            className={styles.plainList}
            data-testid={block.title ? `mu-decision-group-${block.key}` : `mu-decision-page-${block.key}`}
          >
            {block.rows.map((decision) => {
              const title = say(decision.title);
              const own = settings.decisionModes[decision.id];
              const off = settings.features[decision.feature]?.enabled === false;
              return (
                <div key={decision.id} className={styles.plainRow} data-testid={`mu-decision-${decision.id}`}>
                  <div className={classNames(styles.plainMain, off && styles.plainMuted)}>
                    <div className={styles.plainText}>
                      <div className={styles.plainTitle}>{title}</div>
                      <div className={styles.plainSummary}>{say(decision.summary)}</div>
                      {off ? <div className={styles.plainSummary}>{t('mu.decisions.featureOff')}</div> : null}
                    </div>
                    <div className={styles.plainControl}>
                      <ModeControl
                        label={title}
                        labels={labels}
                        value={own ?? settings.mode}
                        onChange={(mode) => setMode(decision.id, mode)}
                      />
                      {own ? (
                        <Button
                          size='mini'
                          type='text'
                          data-testid={`mu-decision-default-${decision.id}`}
                          onClick={() => setMode(decision.id, undefined)}
                        >
                          {t('mu.decisions.useDefault', { mode: labels[shownMode(settings.mode)].label })}
                        </Button>
                      ) : null}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </React.Fragment>
      ))}
      {manifest && !blocks.length ? (
        <div className={styles.empty}>{whole ? t('mu.noMatch') : t('mu.decisions.none')}</div>
      ) : null}
    </SectionShell>
  );
}
