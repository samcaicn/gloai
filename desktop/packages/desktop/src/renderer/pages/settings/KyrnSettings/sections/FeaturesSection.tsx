import React from 'react';
import { Alert, Button, Switch } from '@arco-design/web-react';
import { ArrowLeft, Left, Right } from '@icon-park/react';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import {
  defaultFeatureState,
  isDefaultFeatureState,
  localized,
  type FeatureInfo,
  type FeatureState,
  type HarnessManifest,
  type Localized,
} from '@/common/kyrn/manifest';
import type { KyrnSettings } from '@/common/kyrn/types';
import { FEATURE_PAGES, pageGroupLabelKey, type FeaturePage } from '../../settingsNav';
import { FEATURED_FEATURES, featurePageOf, isFeatured, isTerminalOnly, optionParts, setCompaction } from '../draft';
import OptionField from '../fields/OptionField';
import { ModifiedMark } from '../fields/Row';
import SectionShell from './SectionShell';
import styles from './sections.module.css';

/**
 * Options that have a place of their own in the settings, by feature, with the sentence that points there. The mode a
 * new conversation starts in has none: it is an option of the permission modes feature, on that feature's page.
 */
const ELSEWHERE: Record<string, { key: string; where: string }> = {
  board: { key: 'model', where: 'mu.features.boardModelElsewhere' },
};

/** The two lists of features: the featured switches (one page), and every other feature (a page per group). */
export type FeatureList = 'features' | 'moreFeatures';

type Change = (change: (settings: KyrnSettings) => KyrnSettings) => void;

/** A feature's options that are set on this feature's own page: the ones with a place elsewhere are not. */
const ownOptions = (feature: FeatureInfo) =>
  feature.options.filter((option) => option.key !== ELSEWHERE[feature.name]?.key);

/** Writes a feature's state; the compaction switch is shown in the context section too, and both follow. */
const setFeature = (onChange: Change, feature: FeatureInfo, state: FeatureState) =>
  onChange((now) => {
    const next = { ...now, features: { ...now.features, [feature.name]: state } };
    return feature.name === 'compaction' ? setCompaction(next, state.enabled) : next;
  });

/** Whether a feature's state differs from its default, leaving out an option set elsewhere. */
const isModified = (feature: FeatureInfo, state: FeatureState) =>
  !isDefaultFeatureState({ ...feature, options: ownOptions(feature) }, state);

/**
 * The features of one page in the order the manifest (or the featured list) gives them. The other features are on a
 * page per group of where they act, as the decision points are (see {@link featurePageOf}); without a page (the area
 * alone, outside the settings rail) all of them are listed, under the name of their page. A feature that only changes
 * the terminal is on none of them.
 */
function listed(
  manifest: HarnessManifest | undefined,
  list: FeatureList,
  page: FeaturePage | undefined
): { page?: FeaturePage; heading: boolean; features: FeatureInfo[] }[] {
  const features = manifest?.features ?? [];
  if (list === 'features') {
    const byName = new Map(features.map((feature) => [feature.name, feature]));
    return [
      {
        heading: false,
        features: FEATURED_FEATURES.map((name) => byName.get(name)).filter((feature): feature is FeatureInfo =>
          Boolean(feature)
        ),
      },
    ];
  }
  const rest = features.filter((feature) => !isFeatured(feature.name) && !isTerminalOnly(feature.name));
  const on = (each: FeaturePage) => rest.filter((feature) => featurePageOf(manifest, feature) === each);
  if (page) return [{ page, heading: false, features: on(page) }];
  return FEATURE_PAGES.map((each) => ({ page: each, heading: true, features: on(each) }));
}

/** The name of a list of features where it stands alone: a page's title, and the way back from a feature's page. */
const listTitleKey = (list: FeatureList, page: FeaturePage | undefined): string =>
  list === 'features' ? 'mu.sections.features' : page ? `mu.pages.features.${page}` : 'mu.sections.moreFeatures';

type FeaturesSectionProps = {
  list: FeatureList;
  /** With the other features: the page of one group. Without one, every group, each under its name. */
  page?: FeaturePage;
  settings: KyrnSettings;
  manifest?: HarnessManifest;
  onChange: Change;
  /** Opens the page of one feature's options. */
  onOpen: (feature: string) => void;
};

/**
 * One page of features as plain rows: a name, one sentence, the switch, and — when the feature has options — the way
 * to the page that holds them. No options are folded into the list: each feature's are one click away on their own
 * page, so every list stays short.
 */
export default function FeaturesSection({ list, page, settings, manifest, onChange, onOpen }: FeaturesSectionProps) {
  const { t, i18n } = useTranslation();
  const say = (text?: Localized) => localized(text, i18n.language);
  // A page without features says so, instead of an empty list.
  const groups = listed(manifest, list, page).filter((group) => group.features.length > 0);

  return (
    <SectionShell
      id={list === 'moreFeatures' && page ? `moreFeatures-${page}` : list}
      title={t(listTitleKey(list, page))}
      description={t(list === 'features' ? 'mu.features.lead' : 'mu.features.moreLead')}
    >
      {!manifest ? <Alert type='warning' content={t('mu.harness.tooOld')} /> : null}
      {manifest && !groups.length ? <div className={styles.empty}>{t('mu.features.none')}</div> : null}
      {groups.map(({ page: each, heading, features }) => (
        <React.Fragment key={each ?? list}>
          {heading && each ? <h3 className={styles.groupLabel}>{t(pageGroupLabelKey(each))}</h3> : null}
          <div className={styles.plainList} data-testid={`mu-feature-list-${each ?? list}`}>
            {features.map((feature) => {
              const state = settings.features[feature.name] ?? defaultFeatureState(feature);
              const elsewhere = ELSEWHERE[feature.name];
              const own = ownOptions(feature);
              const title = say(feature.title);
              return (
                <div key={feature.name} className={styles.plainRow} data-testid={`mu-feature-${feature.name}`}>
                  <div className={styles.plainMain}>
                    <div className={styles.plainText}>
                      <div className={styles.plainTitle}>
                        <span>{title}</span>
                        {feature.beta ? <span className={styles.featureBeta}>{t('mu.features.beta')}</span> : null}
                        <ModifiedMark show={isModified(feature, state)} />
                      </div>
                      <div className={styles.plainSummary}>{say(feature.summary)}</div>
                      {/* A feature whose one option lives elsewhere says where, instead of a page of nothing. */}
                      {elsewhere && !own.length ? (
                        <div className={styles.plainSummary}>{t(elsewhere.where)}</div>
                      ) : null}
                    </div>
                    <div className={styles.featureControls}>
                      {own.length ? (
                        <Button
                          type='text'
                          size='small'
                          data-testid={`mu-feature-open-${feature.name}`}
                          aria-label={`${title}: ${t('mu.features.options')}`}
                          onClick={() => onOpen(feature.name)}
                        >
                          <span className={styles.featureOpen}>
                            {t('mu.features.options')}
                            <Right theme='outline' size='12' className='rtl-mirror' />
                          </span>
                        </Button>
                      ) : null}
                      <Switch
                        size='small'
                        aria-label={title}
                        checked={state.enabled}
                        onChange={(enabled) => setFeature(onChange, feature, { ...state, enabled })}
                      />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </React.Fragment>
      ))}
    </SectionShell>
  );
}

type FeatureOptionsProps = {
  list: FeatureList;
  /** With the other features: the page of the group the feature was opened from. */
  page?: FeaturePage;
  name: string;
  /** Which page of the options is shown, from 1, for a feature with more than a page of them. */
  part?: number;
  settings: KyrnSettings;
  manifest?: HarnessManifest;
  onChange: Change;
  /** Back to the list the feature is on. */
  onBack: () => void;
  /** To another page of the same feature's options. */
  onPart?: (part: number) => void;
};

/**
 * The page of one feature: its switch next to its name, then its options, each drawn from its description alone. A
 * feature with more options than a page holds has them on consecutive pages (see {@link optionParts}), each with the
 * way to the one before and after. An option with a place of its own elsewhere is pointed to, not repeated.
 */
export function FeatureOptions({
  list,
  page,
  name,
  part = 1,
  settings,
  manifest,
  onChange,
  onBack,
  onPart,
}: FeatureOptionsProps) {
  const { t, i18n } = useTranslation();
  const say = (text?: Localized) => localized(text, i18n.language);
  const listTitle = t(listTitleKey(list, page));
  const back = (
    <div>
      <Button type='text' size='small' data-testid='mu-feature-back' onClick={onBack}>
        <span className={styles.featureOpen}>
          <ArrowLeft theme='outline' size='14' className='rtl-mirror' />
          {listTitle}
        </span>
      </Button>
    </div>
  );
  const feature = manifest?.features.find((each) => each.name === name);
  if (!feature)
    return (
      <SectionShell id='feature' title={listTitle} back={back}>
        <div className={styles.empty}>{t('mu.features.noSuchFeature')}</div>
      </SectionShell>
    );

  const state = settings.features[feature.name] ?? defaultFeatureState(feature);
  const elsewhere = ELSEWHERE[feature.name];
  const own = ownOptions(feature);
  const parts = optionParts(own);
  // A link to a page past the last one (the harness dropped options since) shows the last.
  const at = Math.min(Math.max(Math.trunc(part), 1), parts.length);
  const modified = isModified(feature, state);
  const reset = (): FeatureState => {
    const next = defaultFeatureState(feature);
    // An option set elsewhere is not reset from here: it is not on this page.
    if (elsewhere && elsewhere.key in state.options) next.options[elsewhere.key] = state.options[elsewhere.key];
    return next;
  };
  const title = say(feature.title);

  return (
    <SectionShell
      id={`feature-${feature.name}`}
      title={title}
      description={say(feature.summary)}
      back={back}
      actions={
        <Switch
          size='small'
          aria-label={title}
          checked={state.enabled}
          onChange={(enabled) => setFeature(onChange, feature, { ...state, enabled })}
        />
      }
    >
      {elsewhere && own.length < feature.options.length ? (
        <div className={styles.plainNote}>{t(elsewhere.where)}</div>
      ) : null}
      {own.length ? (
        <div
          className={classNames(styles.list, !state.enabled && styles.dim)}
          data-testid={`mu-feature-details-${feature.name}`}
        >
          {parts[at - 1].map((option) => (
            <OptionField
              key={option.key}
              scope={feature.name}
              option={option}
              value={state.options[option.key]}
              onChange={(value) =>
                setFeature(onChange, feature, { ...state, options: { ...state.options, [option.key]: value } })
              }
            />
          ))}
        </div>
      ) : (
        <div className={styles.empty}>{t('mu.features.noOptions')}</div>
      )}
      {parts.length > 1 ? (
        <div className={styles.featureParts} data-testid='mu-feature-parts'>
          <span>{t('mu.features.part', { part: at, parts: parts.length })}</span>
          <Button
            size='small'
            type='text'
            disabled={at === 1}
            data-testid='mu-feature-part-previous'
            onClick={() => onPart?.(at - 1)}
          >
            <span className={styles.featureOpen}>
              <Left theme='outline' size='12' className='rtl-mirror' />
              {t('mu.features.previousPart')}
            </span>
          </Button>
          <Button
            size='small'
            type='text'
            disabled={at === parts.length}
            data-testid='mu-feature-part-next'
            onClick={() => onPart?.(at + 1)}
          >
            <span className={styles.featureOpen}>
              {t('mu.features.nextPart')}
              <Right theme='outline' size='12' className='rtl-mirror' />
            </span>
          </Button>
        </div>
      ) : null}
      {modified ? (
        <div className={styles.featureReset}>
          <ModifiedMark show />
          <Button size='small' type='text' onClick={() => setFeature(onChange, feature, reset())}>
            {t('mu.features.reset')}
          </Button>
        </div>
      ) : null}
    </SectionShell>
  );
}
