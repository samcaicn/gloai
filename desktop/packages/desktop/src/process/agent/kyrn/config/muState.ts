import type { FeatureState, HarnessManifest } from '../../../../common/kyrn/manifest';
import type { BoardModel, PermissionDefault } from '../../../../common/kyrn/types';
import { asRecord, text, type JsonRecord } from '../piRpc';

/*
 * Two settings mu keeps in `<agentDir>/mu/` and that the settings page sets too: the permission mode new conversations
 * start in (`permissions.json`, which `/permissions` also writes) and the model that writes the plain-language board
 * (`model` in `board.json`, which `/board model` also sets). The formats are the harness's (src/permissions/modes.ts,
 * src/board/projects.ts). Whatever else board.json holds, the per-project switches, is kept as it is.
 */

/** `features.permissions.mode` as the manifest describes it, when this harness has permission modes. */
function modeOption(manifest: HarnessManifest | undefined) {
  const option = manifest?.features
    .find((feature) => feature.name === 'permissions')
    ?.options.find((each) => each.key === 'mode');
  return option?.kind === 'choice' ? option : undefined;
}

/** The permission modes this harness offers; none when it has no permission modes. */
export const permissionModes = (manifest: HarnessManifest | undefined): string[] =>
  modeOption(manifest)?.choices.map((choice) => choice.value) ?? [];

/** The mode permissions.json holds, when it is one mu reads. */
function pickedMode(raw: string, modes: string[]): string | undefined {
  try {
    const parsed = asRecord(JSON.parse(raw));
    const mode = text(parsed.mode).trim().toLowerCase();
    return parsed.version === 1 && modes.includes(mode) ? mode : undefined;
  } catch {
    return undefined;
  }
}

/** The mode a new conversation starts in, as mu works it out when nothing in the environment says otherwise. */
export function readPermissionDefault(
  manifest: HarnessManifest | undefined,
  config: JsonRecord,
  features: Record<string, FeatureState>,
  raw: string
): PermissionDefault {
  const option = modeOption(manifest);
  const modes = permissionModes(manifest);
  if (!option || !modes.length) return { mode: '', from: 'default' };
  const picked = raw ? pickedMode(raw, modes) : undefined;
  if (picked) return { mode: picked, from: 'picked' };
  const configured = features.permissions?.options.mode;
  const written = modes.includes(text(asRecord(asRecord(config.features).permissions).mode));
  return {
    mode: typeof configured === 'string' && modes.includes(configured) ? configured : option.default,
    from: written ? 'config' : 'default',
  };
}

export const permissionsFile = (mode: string): string => `${JSON.stringify({ version: 1, mode }, null, '\t')}\n`;

/** "provider/model", "session", or '' for none. */
export const isBoardModel = (value: string): boolean =>
  value === '' || value === 'session' || /^[^\s/]{1,80}\/\S{1,200}$/.test(value);

function boardFileModel(raw: string): string {
  try {
    const parsed = asRecord(JSON.parse(raw));
    const model = text(parsed.model);
    return parsed.version === 1 && (model.includes('/') || model === 'session') ? model : '';
  } catch {
    return '';
  }
}

/** mu.json's `features.board.model`, which mu reads before board.json; '' when it is not set. */
export function configuredBoardModel(config: JsonRecord): string {
  return text(asRecord(asRecord(config.features).board).model).trim();
}

export function readBoardModel(manifest: HarnessManifest | undefined, config: JsonRecord, raw: string): BoardModel {
  const supported = Boolean(
    manifest?.features.find((feature) => feature.name === 'board')?.options.some((each) => each.key === 'model')
  );
  return { supported, model: configuredBoardModel(config) || (raw ? boardFileModel(raw) : '') };
}

/** board.json with `model` set, or removed for ''. A file mu would not read starts over, as mu itself would. */
export function withBoardModel(raw: string, model: string): string {
  let doc: JsonRecord;
  try {
    doc = asRecord(raw ? JSON.parse(raw) : {});
  } catch {
    doc = {};
  }
  const next: JsonRecord = doc.version === 1 ? { ...doc } : { version: 1, projects: {} };
  if (model) next.model = model;
  else delete next.model;
  return `${JSON.stringify(next, null, '\t')}\n`;
}

/** Takes `model` out of mu.json's `features.board`, so the board's own file decides. Returns whether it was there. */
export function dropConfiguredBoardModel(config: JsonRecord): boolean {
  const features = asRecord(config.features);
  const board = asRecord(features.board);
  if (!('model' in board)) return false;
  const { model: _model, ...rest } = board;
  const next = { ...features };
  if (Object.keys(rest).length) next.board = rest;
  else delete next.board;
  config.features = next;
  return true;
}
