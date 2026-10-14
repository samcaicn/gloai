import type { TFunction } from 'i18next';
import type { BrowserRunState, BrowserRunStatus, BrowserRunStep, PauseReason } from '@/common/kyrn/browserRun';

/**
 * What the step bar says, as i18n keys and values. Nothing here builds markup: labels come from web pages and are
 * only ever handed to React as text.
 */
const VERBS = new Set(['click', 'fill', 'select', 'scroll', 'wait', 'none']);

export const verbKey = (kind: string): string => `preview.muBrowser.verb.${VERBS.has(kind) ? kind : 'other'}`;

export const statusKey = (status: BrowserRunStatus): string => `preview.muBrowser.status.${status}`;

const HINTED = new Set<BrowserRunStatus>(['blocked', 'needs_confirmation', 'budget', 'stopped', 'failed']);

/** One sentence under a finished bar, in the person's language. The harness's own words are kept for the details. */
export function statusHintKey(status: BrowserRunStatus | undefined): string | undefined {
  if (status === 'detached') return 'preview.muBrowser.detachedHint';
  return status && HINTED.has(status) ? `preview.muBrowser.statusHint.${status}` : undefined;
}

export const pausedKey = (reason: PauseReason | undefined): string => `preview.muBrowser.paused.${reason ?? 'user'}`;

/** The codes the main process sends as a detached run's reason (`DetachCause` in process/services/muBrowser/bridge.ts). */
const DETACH_CAUSES = new Set(['tab_closed', 'connection', 'devtools', 'crashed']);

/**
 * A line under the unfolded bar: an i18n key for its label, then either a sentence said in the person's language (a
 * key, its values, and `terms`: values that are i18n keys themselves) or `text` taken as it is.
 */
export type ReasonLine = {
  labelKey: string;
  key?: string;
  values?: Record<string, string | number>;
  terms?: Record<string, string>;
  text?: string;
};

/** The kinds of a failed judge call (`error:<kind>`), and `all`: every call of a batch failed. */
const JUDGE_ERRORS = new Set([
  'timeout',
  'aborted',
  'unreachable',
  'auth',
  'payment_required',
  'rate_limited',
  'bad_request',
  'server',
  'invalid_response',
  'unexpected',
  'all',
]);

/**
 * The judge's own reason inside `no_judge` (a decision reason: `shadow`, `abstain`, `off` when browser.step was
 * switched off during the run, `error:<kind>`, `error:all`, or the harness's `no verdict` when the decision gave none)
 * as an i18n key. A failure of a kind this app does not know yet is still a failed judge call; anything else is
 * undefined, and the whole sentence stays in the harness's words.
 */
export function judgeReasonKey(reason: string): string | undefined {
  if (reason === 'shadow' || reason === 'abstain' || reason === 'off') return `preview.muBrowser.judgeReason.${reason}`;
  if (reason === 'no verdict') return 'preview.muBrowser.judgeReason.noVerdict';
  const failed = /^error:(.+)$/.exec(reason);
  if (!failed) return undefined;
  return `preview.muBrowser.judgeReason.error.${JUDGE_ERRORS.has(failed[1]) ? failed[1] : 'other'}`;
}

const count = (value: string | number | undefined): number | undefined =>
  typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : undefined;

const named = (value: string | number | undefined): string | undefined =>
  typeof value === 'string' && value !== '' ? value : undefined;

type Sentence = Pick<ReasonLine, 'key' | 'values' | 'terms'>;

/**
 * The harness's end code (`browser.run` state `finished`, as its `Mu.run` report carries it) as a sentence, when the
 * code is one with a sentence and its params are all there. `done`, `read` and `cancelled` come without one; `error`
 * carries an exception's own message; a code this app does not know keeps the harness's words.
 */
function endSentence(code: string | undefined, params: BrowserRunState['params']): Sentence | undefined {
  const key = `preview.muBrowser.end.${code}`;
  switch (code) {
    case 'stopped_by_user':
    case 'no_progress':
      return { key };
    case 'max_steps': {
      const steps = count(params?.maxSteps);
      return steps === undefined ? undefined : { key, values: { count: steps } };
    }
    case 'stuck': {
      const actions = count(params?.actions);
      return actions === undefined ? undefined : { key, values: { count: actions } };
    }
    case 'not_confirmed':
    case 'no_value': {
      const label = named(params?.label);
      return label === undefined ? undefined : { key, values: { label } };
    }
    case 'no_judge': {
      const why = named(params?.judgeReason);
      const term = why === undefined ? undefined : judgeReasonKey(why);
      return term === undefined ? undefined : { key, terms: { judgeReason: term } };
    }
    default:
      return undefined;
  }
}

/**
 * Why a finished run ended, for the unfolded bar. A detached run's reason comes from the main process: a known code is
 * said in the person's language, anything else is Electron's own wording and only offered as a technical detail. Any
 * other run says it in the harness's code when it sent one this app knows, else in the harness's own words; the
 * message of a run that threw is a technical detail too.
 */
export function reasonLine(run: BrowserRunState): ReasonLine | undefined {
  if (run.phase !== 'finished') return undefined;
  if (run.status === 'detached') {
    if (!run.reason) return undefined;
    return DETACH_CAUSES.has(run.reason)
      ? { labelKey: 'preview.muBrowser.detachCause', key: `preview.muBrowser.detachReason.${run.reason}` }
      : { labelKey: 'common.technical_details', text: run.reason };
  }
  const sentence = endSentence(run.code, run.params);
  if (sentence) return { labelKey: 'preview.muBrowser.reason', ...sentence };
  if (!run.reason) return undefined;
  if (run.code === 'error') return { labelKey: 'common.technical_details', text: run.reason };
  return { labelKey: 'preview.muBrowser.reason', text: run.reason };
}

/** A reason line's words: its sentence in the person's language (its terms translated first), or its text. */
export function reasonText(line: ReasonLine, t: TFunction): string {
  if (!line.key) return line.text ?? '';
  const terms = Object.fromEntries(Object.entries(line.terms ?? {}).map(([name, key]) => [name, t(key)]));
  return t(line.key, { ...line.values, ...terms });
}

/** Electron's permission ids (`setPermissionRequestHandler`), grouped the way a person names them. */
const PERMISSION_NAMES = new Map<string, string>([
  ['geolocation', 'location'],
  ['media', 'media'],
  ['notifications', 'notifications'],
  ['clipboard-read', 'clipboard'],
  ['clipboard-sanitized-write', 'clipboard'],
  ['deprecated-sync-clipboard-read', 'clipboard'],
  ['display-capture', 'screen'],
  ['midi', 'midi'],
  ['midiSysex', 'midi'],
  ['fullscreen', 'fullscreen'],
  ['pointerLock', 'pointerLock'],
  ['hid', 'devices'],
  ['serial', 'devices'],
  ['usb', 'devices'],
  ['storage-access', 'storage'],
  ['top-level-storage-access', 'storage'],
  ['openExternal', 'openExternal'],
  ['mediaKeySystem', 'protectedContent'],
  ['window-management', 'windows'],
]);

/** The i18n key naming a refused permission, or undefined for an id the app does not know (shown as it came). */
export function permissionKey(permission: string): string | undefined {
  const name = PERMISSION_NAMES.get(permission);
  return name ? `preview.muBrowser.permission.${name}` : undefined;
}

/** `0.918` → `92`. Absent when the judge gave no probability: no number is better than an invented one. */
export function confidencePercent(step: BrowserRunStep): number | undefined {
  return typeof step.probability === 'number' ? Math.round(step.probability * 100) : undefined;
}

/** `m:ss`, or `h:mm:ss` for the rare long run. */
export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const seconds = String(total % 60).padStart(2, '0');
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
}

export const elapsedMs = (run: BrowserRunState, now: number): number => (run.finishedAt ?? now) - run.startedAt;

/** Whole seconds left before a confirmation counts as refused, never below zero. */
export const secondsLeft = (deadline: number, now: number): number => Math.max(0, Math.ceil((deadline - now) / 1000));

export type BarTone = 'running' | 'paused' | 'stopping' | 'good' | 'warn' | 'bad' | 'quiet';

/** How the bar should feel at a glance. A finished run is only "good" when the harness itself said done. */
export function barTone(run: BrowserRunState): BarTone {
  if (run.phase === 'running') {
    if (run.stopRequested) return 'stopping';
    return run.paused ? 'paused' : 'running';
  }
  switch (run.status) {
    case 'done':
    case 'read':
      return 'good';
    case 'blocked':
    case 'needs_confirmation':
    case 'budget':
      return 'warn';
    case 'failed':
    case 'detached':
      return 'bad';
    default:
      return 'quiet';
  }
}

export const hostOf = (url: string): string => {
  try {
    return new URL(url).host;
  } catch {
    return '';
  }
};
