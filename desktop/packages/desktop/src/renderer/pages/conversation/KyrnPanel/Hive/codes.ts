import type { TFunction } from 'i18next';
import type { BeeActivity, BeeErrorState, BeeWrapUp, Coded, CodeParams, SwarmTitle } from '@/common/kyrn/hive';
import { formatDuration, formatNumber } from '@/renderer/services/i18n/format';

/**
 * The harness writes what a sub-agent did and why it stopped in English, with a stable code and params beside each
 * sentence. These say the same in the app language, at render time, so stored runs follow a later language switch.
 * A code that is missing (older sessions), unknown, or whose params do not fit falls back to the English as written.
 * Messages of a model or a server, tool names, commands and task titles are data and are passed through as they are.
 */

const KEY = 'common.kyrn.hiveView';

const whole = (params: CodeParams, key: string): number | undefined => {
  const value = params[key];
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : undefined;
};
const data = (params: CodeParams, key: string): string | undefined => {
  const value = params[key];
  return typeof value === 'string' && value ? value : undefined;
};
const seconds = (value: number, language?: string | null): string => formatDuration(value * 1000, language, 'short');
const modelReason = (t: TFunction, stopReason: string | undefined): string =>
  t(stopReason === 'aborted' ? `${KEY}.errors.modelAborted` : `${KEY}.errors.modelFailed`);
/** Without a message of the provider, the harness words a failed request from its stop reason: that is not data. */
const harnessModelWords = (t: TFunction, message: string): string | undefined => {
  const stopReason = /^the model request was (aborted|error)$/.exec(message)?.[1];
  return stopReason ? modelReason(t, stopReason) : undefined;
};

/** A run's title: a delegate run's ("3 tasks", "a chain of 2 steps") by its code, a hive's goal as it is. */
export function swarmTitleText(t: TFunction, { title, titleCode }: SwarmTitle): string {
  const count = titleCode ? whole(titleCode.params, 'count') : undefined;
  if (!titleCode || count === undefined) return title;
  if (titleCode.code === 'delegate_tasks') return t(`${KEY}.runTitle.tasks`, { count });
  if (titleCode.code === 'delegate_chain') return t(`${KEY}.runTitle.chain`, { count });
  return title;
}

/** The tool's word before its first snapshot ("choosing a role, a model and a thinking level for 3 sub-agents…"). */
export function swarmProgressText(t: TFunction, progress: Coded | undefined, english: string): string {
  const count = progress ? whole(progress.params, 'count') : undefined;
  return progress?.code === 'choosing_roles' && count !== undefined ? t(`${KEY}.choosingRoles`, { count }) : english;
}

/** One line of a bee's activity log. */
export function beeActivityText(t: TFunction, entry: BeeActivity, language?: string | null): string {
  const params = entry.params;
  switch (entry.code) {
    case 'notes_received':
    case 'late_notes': {
      const count = whole(params, 'count');
      if (count === undefined) return entry.text;
      return t(`${KEY}.activity.${entry.code === 'late_notes' ? 'lateNotes' : 'notesReceived'}`, { count });
    }
    case 'asked_findings':
      return t(`${KEY}.activity.askedFindings`);
    case 'told_wrap_up':
      return t(`${KEY}.activity.toldWrapUp`);
    case 'tool_call':
      // "bash npm test": the call itself, which is data.
      return data(params, 'summary') ?? entry.text;
    case 'tool_failed': {
      const tool = data(params, 'tool');
      return tool ? t(`${KEY}.activity.toolFailed`, { tool }) : entry.text;
    }
    case 'retry': {
      const attempt = whole(params, 'attempt');
      const maxAttempts = whole(params, 'maxAttempts');
      if (attempt === undefined || maxAttempts === undefined) return entry.text;
      return t(`${KEY}.activity.retry`, {
        attempt: formatNumber(attempt, language),
        maxAttempts: formatNumber(maxAttempts, language),
        message: data(params, 'message') ?? '',
      });
    }
    case 'model_error': {
      const message = data(params, 'message');
      return message
        ? t(`${KEY}.activity.modelError`, { message: harnessModelWords(t, message) ?? message })
        : entry.text;
    }
    case 'compacting':
      return t(`${KEY}.activity.compacting`);
    default:
      return entry.text;
  }
}

/** What the harness writes when a sub-agent's process ends early, before the detail (its stderr) that may follow. */
function exitedEarly(t: TFunction, params: CodeParams): { reason: string; english: string } | undefined {
  const exitCode =
    typeof params.exitCode === 'number' && Number.isInteger(params.exitCode) ? params.exitCode : undefined;
  if (exitCode !== undefined) {
    return {
      reason: t(`${KEY}.errors.exitedCode`, { exitCode }),
      english: `sub-agent exited with code ${exitCode} before it finished`,
    };
  }
  const signal = data(params, 'signal');
  if (!signal) return undefined;
  return signal === 'unknown'
    ? { reason: t(`${KEY}.errors.exitedKilled`), english: 'sub-agent was ended by a signal before it finished' }
    : {
        reason: t(`${KEY}.errors.exitedSignal`, { signal }),
        english: `sub-agent was ended by ${signal} before it finished`,
      };
}

const withDetail = (t: TFunction, reason: string, detail: string | undefined): string =>
  detail ? t(`${KEY}.errors.detail`, { reason, detail }) : reason;

/**
 * Why a bee ended or was asked to wrap up, by code. `english` is the sentence the harness wrote for it (the bee's
 * `error`), when there is one: some codes carry their detail (a provider's message, stderr) only there. `wrapUp` gives
 * the params of the wrap-up that `no_report_in_time` names by its code.
 */
function reasonText(
  t: TFunction,
  code: string,
  params: CodeParams,
  english: string | undefined,
  wrapUp: BeeWrapUp | undefined,
  language: string | null | undefined
): string | undefined {
  switch (code) {
    case 'cancelled':
      return t(`${KEY}.errors.cancelled`);
    case 'stopped_by_user':
      return t(`${KEY}.errors.stoppedByUser`);
    case 'ended_by_user':
      return t(`${KEY}.errors.endedByUser`);
    case 'stalled': {
      const quiet = whole(params, 'seconds');
      if (quiet === undefined) return undefined;
      if (params.what === 'model') return t(`${KEY}.errors.stalledModel`, { duration: seconds(quiet, language) });
      if (params.what === 'tool') {
        return t(`${KEY}.errors.stalledTool`, {
          tool: data(params, 'tool') ?? 'tool',
          duration: seconds(quiet, language),
        });
      }
      return undefined;
    }
    case 'time_budget': {
      const minutes = whole(params, 'minutes');
      if (minutes !== undefined) return t(`${KEY}.errors.timeBudget`, { duration: seconds(minutes * 60, language) });
      // Named as the reason of a later stop, the budget itself is not repeated.
      return english === undefined ? t(`${KEY}.errors.timeBudgetReached`) : undefined;
    }
    case 'no_report_in_time': {
      const grace = whole(params, 'seconds');
      const after = data(params, 'after');
      if (grace === undefined || !after || after === code) return undefined;
      const reason = reasonText(t, after, wrapUp?.code === after ? wrapUp.params : {}, undefined, undefined, language);
      return reason && t(`${KEY}.errors.noReportInTime`, { reason, duration: seconds(grace, language) });
    }
    case 'model_error': {
      const message = english || data(params, 'message');
      const harness = message ? harnessModelWords(t, message) : undefined;
      if (harness) return harness;
      // An aborted request says so, with the provider's message after it as data.
      const reason = modelReason(t, data(params, 'stopReason'));
      return message ? withDetail(t, reason, message) : reason;
    }
    case 'retries_exhausted': {
      const message = english || data(params, 'message');
      const reason = t(`${KEY}.errors.retriesExhausted`);
      return message === 'the model request kept failing' ? reason : withDetail(t, reason, message);
    }
    case 'exited_early': {
      const exited = exitedEarly(t, params);
      if (!exited || english === undefined) return exited?.reason;
      // Worded some other way, the English stands: the detail after it cannot be told apart.
      if (!english.startsWith(exited.english)) return undefined;
      return withDetail(t, exited.reason, english.slice(exited.english.length).replace(/^:\s*/, ''));
    }
    case 'chain_broken': {
      const step = data(params, 'step');
      return step ? t(`${KEY}.errors.chainBroken`, { step }) : undefined;
    }
    case 'error':
      // Any other failure: its message is the whole sentence, and it is data.
      return english || data(params, 'message');
    default:
      return undefined;
  }
}

/** Why a bee ended, in the app language; the harness's English when there is no code for it. */
export function beeErrorText(t: TFunction, bee: BeeErrorState, language?: string | null): string {
  if (!bee.errorCode) return bee.error;
  return reasonText(t, bee.errorCode, bee.errorParams, bee.error, bee.wrapUp, language) || bee.error;
}
