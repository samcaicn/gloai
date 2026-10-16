import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  applyJudgeMode,
  envValue,
  fetchJudgeMode,
  JUDGE_SWITCH_INTERVAL_MS,
  JUDGE_VARIABLE,
  judgeConfigUrl,
  parseJudgeMode,
  readSwitch,
  switchPath,
  syncJudgeSwitch,
  writeSwitch,
} from '../../../../../packages/desktop/src/process/agent/kyrn/judgeSwitch';

/** A home of a machine that has no ~/.kyrn to fall back to. */
function home(): string {
  return mkdtempSync(join(tmpdir(), 'mu-judge-switch-'));
}

const envPath = (dir: string): string => join(dir, '.env');
const readEnv = (dir: string): string => {
  try {
    return readFileSync(envPath(dir), 'utf8');
  } catch {
    return '';
  }
};

/** A fetch that answers `payload` with `status`, or throws when status is 'offline'. */
const answering = (payload: unknown, status = 200) =>
  vi.fn(async () => ({ ok: status === 200, status, json: async () => payload }) as unknown as Response);

afterEach(() => {
  vi.restoreAllMocks();
});

describe('parseJudgeMode', () => {
  it('reads the two modes the cloud may answer with', () => {
    expect(parseJudgeMode({ judge: 'auto' })).toBe('auto');
    expect(parseJudgeMode({ judge: 'off' })).toBe('off');
  });

  it('is no answer for anything else: a server may grow the document', () => {
    expect(parseJudgeMode({ judge: 'later' })).toBeUndefined();
    expect(parseJudgeMode({ judge: 1 })).toBeUndefined();
    expect(parseJudgeMode({})).toBeUndefined();
    expect(parseJudgeMode(null)).toBeUndefined();
    expect(parseJudgeMode('off')).toBeUndefined();
  });
});

describe('judgeConfigUrl', () => {
  it('is the configuration document on the cloud host', () => {
    expect(judgeConfigUrl('https://aiapi.tuptup.top')).toBe('https://aiapi.tuptup.top/v1/config');
    expect(judgeConfigUrl()).toBe('https://aiapi.tuptup.top/v1/config');
  });
});

describe('fetchJudgeMode', () => {
  it('asks the document and reads the mode', async () => {
    const fetchImpl = answering({ judge: 'off' });
    await expect(fetchJudgeMode({ fetch: fetchImpl, host: 'https://cloud.test' })).resolves.toBe('off');
    expect(fetchImpl).toHaveBeenCalledWith('https://cloud.test/v1/config', expect.objectContaining({}));
  });

  it('is undefined for a cloud that does not answer, answers wrong, or says nothing', async () => {
    await expect(fetchJudgeMode({ fetch: answering({}, 404) })).resolves.toBeUndefined();
    await expect(fetchJudgeMode({ fetch: answering('not json') })).resolves.toBeUndefined();
    await expect(
      fetchJudgeMode({
        fetch: vi.fn(async () => {
          throw new Error('offline');
        }) as unknown as typeof fetch,
      })
    ).resolves.toBeUndefined();
  });
});

describe('applyJudgeMode', () => {
  const env = ['TYPESAFE_API_KEY=secret', 'MU_PROVIDER_AIBUDDY_API_KEY=key'].join('\n');

  it('writes off, and remembers the value that was there', () => {
    const withLaya = `${env}\n${JUDGE_VARIABLE}=laya`;
    expect(applyJudgeMode(withLaya, 'off')).toEqual({
      env: `${env}\n${JUDGE_VARIABLE}=off\n`,
      applied: 'off',
      previous: 'laya',
    });
    expect(applyJudgeMode(env, 'off')).toEqual({
      env: `${env}\n${JUDGE_VARIABLE}=off\n`,
      applied: 'off',
      previous: '',
    });
  });

  it('leaves a file that already says off alone, keeping the recorded value', () => {
    const off = `${env}\n${JUDGE_VARIABLE}=off`;
    expect(applyJudgeMode(off, 'off', { applied: 'off', previous: 'laya' })).toEqual({
      env: off,
      applied: 'off',
      previous: 'laya',
    });
  });

  it('puts back what this app took out: a hand-set value, or nothing at all', () => {
    const off = `${env}\n${JUDGE_VARIABLE}=off`;
    expect(applyJudgeMode(off, 'auto', { applied: 'off', previous: 'laya' })).toEqual({
      env: `${env}\n${JUDGE_VARIABLE}=laya\n`,
      applied: 'restored',
      previous: 'laya',
    });
    expect(applyJudgeMode(off, 'auto', { applied: 'off', previous: '' })).toEqual({
      // The line is gone; the file keeps its trailing newline, as every write leaves it.
      env: `${env}\n`,
      applied: 'restored',
      previous: '',
    });
  });

  it('restores nothing when this app never took anything out', () => {
    expect(applyJudgeMode(env, 'auto')).toEqual({ env, applied: 'restored', previous: undefined });
    expect(applyJudgeMode(`${env}\n${JUDGE_VARIABLE}=laya`, 'auto', { applied: 'off' })).toEqual({
      env: `${env}\n${JUDGE_VARIABLE}=laya`,
      applied: 'restored',
      previous: undefined,
    });
  });

  it('leaves a line somebody else wrote over the app one alone', () => {
    const taken = `${env}\n${JUDGE_VARIABLE}=clm`;
    expect(applyJudgeMode(taken, 'auto', { applied: 'off', previous: '' })).toEqual({
      env: taken,
      applied: 'restored',
      previous: '',
    });
  });
});

describe('envValue', () => {
  it('reads the variable out of the file text only', () => {
    expect(envValue('A=1\nMU_JUDGE=laya\n')).toBe('laya');
    expect(envValue('MU_JUDGE=off')).toBe('off');
    expect(envValue('MU_JUDGE_OTHER=x')).toBe('');
    expect(envValue('')).toBe('');
  });
});

describe('the remembered answer', () => {
  it('round-trips through the home, and a broken file is no answer', () => {
    const dir = home();
    writeSwitch({ mode: 'off', applied: 'off', previous: '', fetchedAt: 5 }, dir);
    expect(readSwitch(dir)).toMatchObject({ mode: 'off', applied: 'off', previous: '', fetchedAt: 5 });
    writeFileSync(switchPath(dir), '{ not json');
    expect(readSwitch(dir)).toEqual({});
  });

  it('lives beside the harness home, not in the agent directory', () => {
    expect(switchPath('/tmp/fake-home')).toMatch(/[\\/]\.mu[\\/]judge-switch\.json$/);
  });
});

describe('syncJudgeSwitch', () => {
  const setup = () => {
    const dir = home();
    writeFileSync(envPath(dir), 'TYPESAFE_API_KEY=secret\n');
    return { dir, envPath: envPath(dir), home: dir, now: () => 1700000000000 };
  };

  it('writes off when the cloud says off', async () => {
    const deps = { ...setup(), fetch: answering({ judge: 'off' }) };
    await expect(syncJudgeSwitch(deps)).resolves.toBe('off');
    expect(readEnv(deps.dir)).toContain('MU_JUDGE=off');
    expect(readSwitch(deps.home)).toMatchObject({
      mode: 'off',
      applied: 'off',
      previous: '',
      fetchedAt: 1700000000000,
    });
  });

  it('restores the file when the cloud says auto again', async () => {
    const deps = { ...setup(), fetch: answering({ judge: 'auto' }) };
    writeFileSync(deps.envPath, 'MU_JUDGE=off\nTYPESAFE_API_KEY=secret\n');
    writeSwitch({ mode: 'off', applied: 'off', previous: 'laya' }, deps.home);
    await expect(syncJudgeSwitch(deps)).resolves.toBe('auto');
    // The line this app took out is put back; the rest of the file keeps its order.
    expect(readEnv(deps.dir)).toBe('TYPESAFE_API_KEY=secret\nMU_JUDGE=laya\n');
    expect(readSwitch(deps.home)).toMatchObject({ mode: 'auto', applied: 'restored', previous: 'laya' });
  });

  it('keeps the last answer when the cloud cannot be reached', async () => {
    const deps = { ...setup(), fetch: answering({}, 500) };
    writeFileSync(deps.envPath, 'MU_JUDGE=off\n');
    writeSwitch({ mode: 'off', applied: 'off', previous: '' }, deps.home);
    await expect(syncJudgeSwitch(deps)).resolves.toBe('off');
    expect(readEnv(deps.dir)).toBe('MU_JUDGE=off\n');
  });

  it('changes nothing on a machine that never reached the cloud', async () => {
    const deps = { ...setup(), fetch: answering({}, 503) };
    await expect(syncJudgeSwitch(deps)).resolves.toBeUndefined();
    expect(readEnv(deps.dir)).toBe('TYPESAFE_API_KEY=secret\n');
    expect(readSwitch(deps.home)).toEqual({});
  });

  it('writes nothing when the answer matches what the file already says', async () => {
    const deps = { ...setup(), fetch: answering({ judge: 'off' }) };
    writeFileSync(deps.envPath, 'MU_JUDGE=off\n');
    await syncJudgeSwitch(deps);
    expect(readEnv(deps.dir)).toBe('MU_JUDGE=off\n');
  });
});

describe('JUDGE_SWITCH_INTERVAL_MS', () => {
  it('asks again every few hours, not on a timer that hammers the cloud', () => {
    expect(JUDGE_SWITCH_INTERVAL_MS).toBe(6 * 60 * 60 * 1000);
  });
});
