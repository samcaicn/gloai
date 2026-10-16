import { homedir } from 'node:os';
import { join } from 'node:path';
import { AIAPI_HOST, JUDGE_CONFIG_PATH, type JudgeMode } from '../../../common/kyrn/cloud';
import { atomic, readOptional, withVariables, writeJson } from './config/files';
import { muHome } from './naming';

/**
 * The cloud's switch for the judgment layer. The product shows nothing about it: the guide writes the cloud as
 * the only backend with the judge on, and from then on the cloud decides whether it runs. This module is the
 * whole mechanism -- ask the cloud, remember the answer, and write what the harness already understands.
 *
 * The harness reads the judge order from `MU_JUDGE` in its .env (`kyrn/bin/mu.mjs` hands the file's variables to
 * the agent, and `MU_JUDGE=off` is the documented way to run without a judge at all). So `off` is one line in
 * `~/.mu/.env`, and `auto` is putting back whatever was there before this app wrote that line -- never more:
 * a value somebody set by hand is remembered and restored, not overwritten for good.
 *
 * Every step is best effort. A cloud that cannot be reached, a document that is not one, a file that cannot be
 * written: the app keeps running with the last answer it has, and with the judge on when it never had one.
 */

/** The variable the launcher reads the judge order from; `off` disables the judgment layer. */
export const JUDGE_VARIABLE = 'MU_JUDGE';

/** How often the cloud is asked again while the app runs. The answer changes rarely; a start is not waited on. */
export const JUDGE_SWITCH_INTERVAL_MS = 6 * 60 * 60 * 1000;

/** Where the last answer of the cloud is remembered: `~/.mu/judge-switch.json`. */
export function switchPath(home: string = homedir()): string {
  return join(muHome(home), 'judge-switch.json');
}

/** What the cloud last said, and what this app did about it. */
export type JudgeSwitch = {
  /** The mode the cloud last answered with. */
  mode?: JudgeMode;
  /** What this app last wrote to the .env: `off` when it took the judge out, `restored` when it put one back. */
  applied?: 'off' | 'restored';
  /** The value of MU_JUDGE in the .env before this app wrote `off` there; '' when there was none. */
  previous?: string;
  /** When the cloud was last asked, in epoch milliseconds. */
  fetchedAt?: number;
};

/** Reads the remembered answer; a file that is not the expected shape is no answer at all. */
export function readSwitch(home: string = homedir()): JudgeSwitch {
  try {
    const parsed = JSON.parse(readOptional(switchPath(home)) || '{}') as JudgeSwitch;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/** Remembers the answer, so the next start and a cloud that cannot be reached agree with this one. */
export function writeSwitch(remembered: JudgeSwitch, home: string = homedir()): void {
  writeJson(switchPath(home), remembered);
}

/** The mode a configuration document asks for, or undefined when it does not ask for one. */
export function parseJudgeMode(payload: unknown): JudgeMode | undefined {
  const judge = (payload as { judge?: unknown } | null)?.judge;
  return judge === 'auto' || judge === 'off' ? judge : undefined;
}

/** The document's address on the cloud. */
export function judgeConfigUrl(host: string = AIAPI_HOST): string {
  return `${host}${JUDGE_CONFIG_PATH}`;
}

/** Asks the cloud which mode the judge runs in. Any failure -- offline, not 2xx, not JSON, not a mode -- is undefined. */
export async function fetchJudgeMode(deps: {
  fetch?: typeof fetch;
  host?: string;
  timeoutMs?: number;
}): Promise<JudgeMode | undefined> {
  const fetchImpl = deps.fetch ?? fetch;
  try {
    const response = await fetchImpl(judgeConfigUrl(deps.host), {
      signal: AbortSignal.timeout(deps.timeoutMs ?? 5000),
    });
    if (!response.ok) return undefined;
    return parseJudgeMode(await response.json());
  } catch {
    return undefined;
  }
}

/** The value of MU_JUDGE in the text of a .env file, '' when the file has no such line. */
export function envValue(envText: string): string {
  const line = envText.split('\n').find((entry) => entry.startsWith(`${JUDGE_VARIABLE}=`));
  return line ? line.slice(JUDGE_VARIABLE.length + 1).trim() : '';
}

/** The .env text with MU_JUDGE set to `value`, or with the line taken out when there is no value. */
const withJudge = (envText: string, value: string | undefined): string =>
  withVariables(envText, new Map([[JUDGE_VARIABLE, value || undefined]]));

/**
 * The .env text for `mode`, and what was done to it. Writing `off` remembers the value that was there; going back
 * to `auto` only undoes this app's own `off` -- a line somebody else wrote since is left alone, and nothing is
 * restored when this app never took anything out.
 */
export function applyJudgeMode(
  envText: string,
  mode: JudgeMode,
  remembered: JudgeSwitch = {}
): { env: string; applied?: JudgeSwitch['applied']; previous?: string } {
  const current = envValue(envText);
  if (mode === 'off') {
    // Already off, by this app or by hand: nothing to write, and the value to restore is the one on record.
    if (current === 'off') return { env: envText, applied: 'off', previous: remembered.previous ?? current };
    return { env: withJudge(envText, 'off'), applied: 'off', previous: current };
  }
  // `auto`: put back what was there, but only what this app itself took out.
  if (remembered.applied !== 'off' || current !== 'off')
    return { env: envText, applied: 'restored', previous: remembered.previous };
  return {
    env: withJudge(envText, remembered.previous || undefined),
    applied: 'restored',
    previous: remembered.previous,
  };
}

export type JudgeSwitchDeps = {
  /** The .env the harness reads: `~/.mu/.env` for the packaged app, the checkout's in development. */
  envPath: string;
  /** mu's home, where the answer is remembered. */
  home: string;
  host?: string;
  fetch?: typeof fetch;
  timeoutMs?: number;
  now?: () => number;
};

/**
 * Asks the cloud and writes what it says. The answer of a cloud that cannot be reached is the last one on
 * record; a machine that never reached one changes nothing. Returns the mode now in force, or undefined when
 * there is nothing to go by.
 */
export async function syncJudgeSwitch(deps: JudgeSwitchDeps): Promise<JudgeMode | undefined> {
  const remembered = readSwitch(deps.home);
  const fetched = await fetchJudgeMode({ fetch: deps.fetch, host: deps.host, timeoutMs: deps.timeoutMs });
  const mode = fetched ?? remembered.mode;
  if (mode === undefined) return undefined;
  const envText = readOptional(deps.envPath);
  const applied = applyJudgeMode(envText, mode, remembered);
  if (applied.env !== envText) atomic(deps.envPath, applied.env);
  writeSwitch(
    {
      mode,
      applied: applied.applied ?? remembered.applied,
      previous: applied.previous ?? remembered.previous,
      fetchedAt: (deps.now ?? Date.now)(),
    },
    deps.home
  );
  return mode;
}
