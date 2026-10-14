import { join } from 'node:path';
import { KyrnError } from '../../../../common/kyrn/errors';
import {
  DECISION_MODES,
  checkOption,
  defaultFeatureState,
  optionEquals,
  parseManifest,
  sameFeatureState,
  type DecisionMode,
  type FeatureInfo,
  type FeatureState,
  type HarnessManifest,
  type HarnessState,
  type OptionValue,
} from '../../../../common/kyrn/manifest';
import { asRecord, type JsonRecord } from '../piRpc';
import { readOptional } from './files';

/**
 * `<harness root>/packages/kyrn-judge/manifest.json`, or the file given (the npm package keeps it elsewhere, see
 * harness.ts). A harness from before the manifest simply has no such file.
 */
export function loadManifest(
  root: string,
  file: string = join(root, 'packages', 'kyrn-judge', 'manifest.json')
): HarnessState {
  try {
    const raw = readOptional(file);
    return raw ? parseManifest(JSON.parse(raw)) : { status: 'missing' };
  } catch {
    return { status: 'missing' };
  }
}

const isMode = (value: unknown): value is DecisionMode => DECISION_MODES.includes(value as DecisionMode);

/** Overrides under `modes`, for the decisions the manifest lists. Anything else under `modes` is not ours to show. */
export function readDecisionModes(manifest: HarnessManifest, config: JsonRecord): Record<string, DecisionMode> {
  const modes = asRecord(config.modes);
  const found: Record<string, DecisionMode> = {};
  for (const { id } of manifest.decisions) if (isMode(modes[id])) found[id] = modes[id];
  return found;
}

/**
 * Sets and removes overrides. "Follow the default" is an absent key, so the default keeps flowing through.
 * Returns whether the file has to change.
 */
export function applyDecisionModes(
  manifest: HarnessManifest,
  config: JsonRecord,
  desired: Record<string, DecisionMode>
): boolean {
  const known = new Set(manifest.decisions.map((decision) => decision.id));
  for (const [id, mode] of Object.entries(desired)) {
    if (!known.has(id)) throw new KyrnError('invalid', `Unknown decision: ${id}`);
    if (!isMode(mode)) throw new KyrnError('invalid', `Invalid mode for ${id}`);
  }
  const current = readDecisionModes(manifest, config);
  const modes = { ...asRecord(config.modes) };
  let changed = false;
  for (const id of known) {
    if (current[id] === desired[id]) continue;
    changed = true;
    if (desired[id]) modes[id] = desired[id];
    else delete modes[id];
  }
  if (changed) config.modes = modes;
  return changed;
}

/**
 * `features.<name>` as the harness reads it (`featureOptions` in its config.ts): `false` is off, `true` is on, an
 * object overrides the defaults and may carry `enabled`. A value of the wrong type is shown as the default.
 */
export function readFeature(feature: FeatureInfo, value: unknown): FeatureState {
  const state = defaultFeatureState(feature);
  if (typeof value === 'boolean') return { ...state, enabled: value };
  const entry = asRecord(value);
  if (typeof entry.enabled === 'boolean') state.enabled = entry.enabled;
  for (const option of feature.options) {
    const problem = option.key in entry ? checkOption(option, entry[option.key]) : 'type';
    // Out of range is still what the file says, and what the harness uses: show it, so it can be corrected.
    if (problem === undefined || problem === 'range') state.options[option.key] = entry[option.key] as OptionValue;
  }
  return state;
}

export function readFeatures(manifest: HarnessManifest, config: JsonRecord): Record<string, FeatureState> {
  const features = asRecord(config.features);
  return Object.fromEntries(
    manifest.features.map((feature) => [feature.name, readFeature(feature, features[feature.name])])
  );
}

/**
 * The smallest entry that says `state`: values equal to the default are left out, so a changed default upstream
 * reaches the user. Keys the manifest does not describe stay where they are. `false` when off is all there is to say.
 */
export function featureEntry(feature: FeatureInfo, previous: unknown, state: FeatureState): unknown {
  const known = new Map(feature.options.map((option) => [option.key, option]));
  const next: JsonRecord = {};
  const place = (key: string) => {
    const option = known.get(key);
    if (option && !optionEquals(state.options[key], option.default)) next[key] = state.options[key];
  };
  for (const [key, value] of Object.entries(asRecord(previous))) {
    if (key === 'enabled') {
      if (state.enabled !== feature.defaultEnabled) next.enabled = state.enabled;
    } else if (known.has(key)) place(key);
    else next[key] = value;
  }
  for (const key of known.keys()) if (!(key in next)) place(key);
  if (state.enabled !== feature.defaultEnabled && !('enabled' in next)) next.enabled = state.enabled;
  const keys = Object.keys(next);
  if (keys.length === 0) return undefined;
  if (!state.enabled && keys.length === 1 && keys[0] === 'enabled') return false;
  return next;
}

/**
 * Writes the features whose state differs from what the file says now; the others are not touched at all, whatever
 * spelling they use. Only a changed feature is validated: an odd value written by hand elsewhere must not block
 * saving something unrelated. Returns whether the file has to change.
 */
export function applyFeatures(
  manifest: HarnessManifest,
  config: JsonRecord,
  desired: Record<string, FeatureState>
): boolean {
  const described = new Map(manifest.features.map((feature) => [feature.name, feature]));
  for (const name of Object.keys(desired))
    if (!described.has(name)) throw new KyrnError('invalid', `Unknown feature: ${name}`);
  const features = { ...asRecord(config.features) };
  let changed = false;
  for (const [name, feature] of described) {
    const state = desired[name];
    if (!state) continue;
    if (typeof state.enabled !== 'boolean' || asRecord(state.options) !== state.options)
      throw new KyrnError('invalid', `Invalid feature: ${name}`);
    if (sameFeatureState(feature, readFeature(feature, features[name]), state)) continue;
    for (const option of feature.options) {
      const problem = checkOption(option, state.options[option.key]);
      if (problem)
        throw new KyrnError('optionValue', `Invalid value for ${name}.${option.key} (${problem})`, {
          feature: name,
          option: option.key,
          problem,
        });
    }
    const entry = featureEntry(feature, features[name], state);
    if (entry === undefined) delete features[name];
    else features[name] = entry;
    changed = true;
  }
  if (changed) config.features = features;
  return changed;
}
