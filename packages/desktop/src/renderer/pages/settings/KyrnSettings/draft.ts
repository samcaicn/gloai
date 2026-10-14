import {
  sameFeatureState,
  type DecisionInfo,
  type FeatureInfo,
  type HarnessManifest,
  type OptionInfo,
} from '@/common/kyrn/manifest';
import { providerKeyVariable, type ProviderSettings } from '@/common/kyrn/models';
import type { Credential, KyrnSettings, SaveSettings } from '@/common/kyrn/types';
import { DECISION_PAGES, FEATURE_PAGES, type DecisionPage, type FeaturePage } from '../settingsNav';

/**
 * The parts of mu's settings, in the order of the settings rail: the providers and the model a new user sets up first,
 * then the kernel. Each is one page, or — the decision points and the more features — a group of pages; all of them
 * edit one draft, saved at once, and the save bar names the parts that changed. The context settings are on the
 * decision points' context page, next to the points about context; they keep their own name in the save bar.
 */
export const SECTIONS = [
  'providers',
  'defaultModel',
  'judges',
  'decisions',
  'features',
  'moreFeatures',
  'context',
] as const;
export type SectionId = (typeof SECTIONS)[number];

/** No settings page holds more rows than this: a longer list is split, onto entries of the rail or pages of options. */
export const PAGE_ROWS = 12;

/**
 * The switches that say what mu is, in the order a person meets them: the board it writes in plain language, the goal
 * it keeps to, the agents it may spawn, what it remembers and what it forgets. They are the features page; every other
 * feature the harness describes is on one of the more-features pages.
 */
export const FEATURED_FEATURES = ['board', 'goal', 'swarm', 'hive', 'memory', 'forgetting'] as const;

export const isFeatured = (name: string): boolean => (FEATURED_FEATURES as readonly string[]).includes(name);

/**
 * Features that only change the terminal (the welcome box drawn when mu starts there): nothing in the app shows what
 * they do, so the app's feature lists leave them out. The harness's manifest does not say where a feature acts; once it
 * does, that flag replaces this list.
 */
export const TERMINAL_ONLY_FEATURES = ['welcome'] as const;

export const isTerminalOnly = (name: string): boolean => (TERMINAL_ONLY_FEATURES as readonly string[]).includes(name);

export const isDecisionPage = (value: unknown): value is DecisionPage =>
  (DECISION_PAGES as readonly unknown[]).includes(value);

export const isFeaturePage = (value: unknown): value is FeaturePage =>
  (FEATURE_PAGES as readonly unknown[]).includes(value);

/**
 * The page a decision point is on: the lessons' own for the experience library's points, else its group's. A point in
 * a group the desktop has no page for is on the first page, under its group's name (see {@link isStrayDecision}).
 */
export function decisionPageOf(decision: Pick<DecisionInfo, 'group' | 'feature'>): DecisionPage {
  if (decision.feature === 'memory' || decision.group === 'memory') return 'memory';
  return isDecisionPage(decision.group) ? decision.group : DECISION_PAGES[0];
}

/** A decision point in a group the rail has no page for: it is shown on the first page, under its group's own name. */
export const isStrayDecision = (decision: Pick<DecisionInfo, 'group' | 'feature'>): boolean =>
  decisionPageOf(decision) !== 'memory' && decisionPageOf(decision) !== decision.group;

/**
 * The more-features page a feature is on: the group of the first decision point it acts at, as the decision points
 * are grouped. A feature that acts at none, or in a group without a page here, is under Other.
 */
export function featurePageOf(manifest: HarnessManifest | undefined, feature: Pick<FeatureInfo, 'name'>): FeaturePage {
  const group = manifest?.decisions.find((decision) => decision.feature === feature.name)?.group;
  return group !== 'other' && isFeaturePage(group) ? group : 'other';
}

/**
 * A feature's options as pages of at most {@link PAGE_ROWS}. A switch starts a set together with the options after
 * it, and a page ends before a set that would not fit, so a switch stays on the page of its own settings. A set longer
 * than a page is cut where the page is full. Always at least one page, empty for a feature without options.
 */
export function optionParts<T extends Pick<OptionInfo, 'kind'>>(options: readonly T[], size = PAGE_ROWS): T[][] {
  const sets: T[][] = [];
  for (const option of options) {
    if (option.kind === 'boolean' || !sets.length) sets.push([option]);
    else sets[sets.length - 1].push(option);
  }
  const parts: T[][] = [];
  for (const set of sets)
    for (let start = 0; start < set.length; start += size) {
      const piece = set.slice(start, start + size);
      const last = parts[parts.length - 1];
      if (last && last.length + piece.length <= size) last.push(...piece);
      else parts.push(piece);
    }
  return parts.length ? parts : [[]];
}

/** What is being edited: a copy of the settings, plus keys typed but not saved. Keys only ever travel towards the store. */
export type Draft = {
  settings: KyrnSettings;
  /** Credential variable of a judge -> the key typed for it. */
  judgeKeys: Record<string, string>;
  /** Provider id -> the key typed for it. */
  providerKeys: Record<string, string>;
};

export const newDraft = (settings: KyrnSettings): Draft => ({ settings, judgeKeys: {}, providerKeys: {} });

/**
 * The settings as the page reads them. A main process older than this page sends no permission default and no board
 * model: without them the whole area would fail to draw; with these it shows that mu cannot be told. The permission
 * default is read (the guide starts a conversation in it) and never written from here.
 */
export const completeSettings = (settings: KyrnSettings): KyrnSettings => ({
  ...settings,
  permissions: settings.permissions ?? { mode: '', from: 'default' },
  boardModel: settings.boardModel ?? { supported: false, model: '' },
});

export function manifestOf(settings: KyrnSettings | undefined): HarnessManifest | undefined {
  return settings?.harness.status === 'ok' ? settings.harness.manifest : undefined;
}

const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);
const hasKeys = (keys: Record<string, string>): boolean => Object.values(keys).some(Boolean);

/** The editable part of a provider: what is read-only about it (key state, header names) never makes it "changed". */
const editableProvider = ({ id, name, api, baseUrl, authHeader, models }: ProviderSettings) => ({
  id,
  name,
  api,
  baseUrl,
  authHeader,
  models: models.map(({ thinkingLevels: _levels, ...model }) => model),
});

/** Sections with unsaved changes, for the dots in the section list and the text of the save bar. */
export function dirtySections(base: KyrnSettings, draft: Draft): Set<SectionId> {
  const next = draft.settings;
  const manifest = manifestOf(base);
  const dirty = new Set<SectionId>();
  if (!same(base.tiers, next.tiers) || !same(base.judges, next.judges) || hasKeys(draft.judgeKeys)) dirty.add('judges');
  if (base.mode !== next.mode || !same(base.decisionModes, next.decisionModes)) dirty.add('decisions');
  for (const feature of manifest?.features ?? [])
    if (!sameFeatureState(feature, base.features[feature.name], next.features[feature.name]))
      dirty.add(isFeatured(feature.name) ? 'features' : 'moreFeatures');
  if (
    !same(base.models.providers.map(editableProvider), next.models.providers.map(editableProvider)) ||
    removedEntries(base, draft).length > 0 ||
    hasKeys(draft.providerKeys)
  )
    dirty.add('providers');
  if (!same(base.models.defaults, next.models.defaults) || base.boardModel.model !== next.boardModel.model)
    dirty.add('defaultModel');
  if (base.autoCompaction !== next.autoCompaction || base.maxContextTokens !== next.maxContextTokens)
    dirty.add('context');
  // The summary-free compaction is one switch on the more-features page: the feature and the old beta flag as one.
  if (base.betaCompression !== next.betaCompression) dirty.add('moreFeatures');
  return dirty;
}

/** Hand-written models.json entries that were there when the settings were read, and are removed in the draft. */
export function removedEntries(base: KyrnSettings, draft: Draft): string[] {
  const kept = new Set(draft.settings.models.foreign.map((entry) => entry.id));
  return base.models.foreign.map((entry) => entry.id).filter((id) => !kept.has(id));
}

/**
 * The one payload of the save bar. Everything is validated again by the store, which is the authority. `base`, the
 * settings the draft was made from, says which hand-written entries were removed.
 */
export function toSave(draft: Draft, base?: KyrnSettings): SaveSettings {
  const {
    keys: _keys,
    harness,
    decisionModes,
    features,
    models,
    permissions: _permissions,
    boardModel,
    ...rest
  } = draft.settings;
  const removeEntries = base ? removedEntries(base, draft) : [];
  const credentials: Credential[] = [
    ...Object.entries(draft.judgeKeys).map(([name, value]) => ({ name, value })),
    ...Object.entries(draft.providerKeys)
      .filter(([id]) => models.providers.some((provider) => provider.id === id))
      .map(([id, value]) => ({ name: providerKeyVariable(id), value })),
  ].filter((credential) => credential.value);
  return {
    ...rest,
    ...(harness.status === 'ok' ? { decisionModes, features } : {}),
    models: {
      providers: models.providers,
      defaults: models.defaults,
      ...(removeEntries.length ? { removeEntries } : {}),
    },
    ...(credentials.length ? { credentials } : {}),
    ...(boardModel.supported ? { boardModel: { model: boardModel.model } } : {}),
  };
}

/**
 * The old beta switch and `features.compaction.enabled` are one thing, shown once: the compaction feature on the
 * more-features page. Both follow its switch, so the store never sees them disagree.
 */
export function setCompaction(settings: KyrnSettings, enabled: boolean): KyrnSettings {
  const compaction = settings.features.compaction;
  return {
    ...settings,
    betaCompression: enabled,
    features: compaction ? { ...settings.features, compaction: { ...compaction, enabled } } : settings.features,
  };
}

/** Case-insensitive search over whatever texts describe an entry. An empty query matches everything. */
export function matches(query: string, ...texts: (string | undefined)[]): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const haystack = texts.filter(Boolean).join('\n').toLowerCase();
  return words.every((word) => haystack.includes(word));
}

/** A stale revision is the one failure with a remedy of its own: load again. The store says so by its code. */
export const isStale = (error: { code: string }): boolean => error.code === 'stale';
