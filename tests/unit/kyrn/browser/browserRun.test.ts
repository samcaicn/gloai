import { describe, expect, it } from 'vitest';
import {
  plainText,
  reduceBrowserRuns,
  runEnding,
  runsOfConversation,
  type BrowserRunEvent,
  type BrowserRuns,
} from '@/common/kyrn/browserRun';

const play = (events: BrowserRunEvent[], from: BrowserRuns = {}) => events.reduce(reduceBrowserRuns, from);
const started = (tabId: string, at = 100, conversationId = 'c1'): BrowserRunEvent => ({
  type: 'started',
  tabId,
  conversationId,
  url: 'about:blank',
  at,
});
const step = (tabId: string, number: number): BrowserRunEvent => ({
  type: 'step',
  tabId,
  step: {
    step: number,
    kind: 'click',
    action: `Button ${number}`,
    url: 'https://a.test',
    probability: 0.9,
    at: 100 + number,
  },
});

describe('the browser run store', () => {
  it('builds a run from its events', () => {
    const runs = play([
      started('t1'),
      { type: 'goal', tabId: 't1', goal: `search for${String.fromCharCode(0)} WeakMap`, url: 'https://a.test/' },
      step('t1', 1),
      step('t1', 2),
      { type: 'control', tabId: 't1', paused: true, pausedBy: 'interaction', stopRequested: false },
    ]);
    expect(runs.t1).toMatchObject({
      conversationId: 'c1',
      phase: 'running',
      goal: 'search for WeakMap',
      startUrl: 'https://a.test/',
      startedAt: 100,
      paused: true,
      pausedBy: 'interaction',
      stopRequested: false,
    });
    expect(runs.t1.steps.map((entry) => entry.step)).toEqual([1, 2]);
  });

  it('forgets why it was paused once it runs again', () => {
    const runs = play([
      started('t1'),
      { type: 'control', tabId: 't1', paused: true, pausedBy: 'takeover', stopRequested: false },
      { type: 'control', tabId: 't1', paused: false, pausedBy: 'takeover', stopRequested: false },
    ]);
    expect(runs.t1.paused).toBe(false);
    expect(runs.t1.pausedBy).toBeUndefined();
  });

  it('shows one question at a time and takes it down when answered', () => {
    const confirm = { id: 'q1', label: 'Pay now', url: 'https://shop.test', askedAt: 1, deadline: 120_001 };
    const asked = play([started('t1'), { type: 'confirm', tabId: 't1', confirm }]);
    expect(asked.t1.confirm).toEqual(confirm);
    expect(play([{ type: 'confirmed', tabId: 't1', id: 'another', allowed: true }], asked).t1.confirm).toEqual(confirm);
    expect(play([{ type: 'confirmed', tabId: 't1', id: 'q1', allowed: false }], asked).t1.confirm).toBeUndefined();
  });

  it('keeps the final status until the tab is closed or reused', () => {
    const finished = play([
      started('t1'),
      step('t1', 1),
      { type: 'control', tabId: 't1', paused: true, pausedBy: 'user', stopRequested: false },
      { type: 'confirm', tabId: 't1', confirm: { id: 'q', label: 'x', url: 'y', askedAt: 1, deadline: 2 } },
      { type: 'finished', tabId: 't1', status: 'needs_confirmation', reason: 'not confirmed', at: 900 },
    ]);
    expect(finished.t1).toMatchObject({
      phase: 'finished',
      status: 'needs_confirmation',
      reason: 'not confirmed',
      finishedAt: 900,
      paused: false,
      confirm: undefined,
    });

    // Nothing that arrives late changes a finished run.
    const late = play(
      [
        step('t1', 2),
        { type: 'control', tabId: 't1', paused: true, pausedBy: 'user', stopRequested: true },
        { type: 'finished', tabId: 't1', status: 'done', at: 950 },
      ],
      finished
    );
    expect(late).toBe(finished);

    const reused = play([started('t1', 1_000)], finished);
    expect(reused.t1).toMatchObject({ phase: 'running', steps: [], goal: '', startedAt: 1_000, stopRequested: false });
    expect(reused.t1.status).toBeUndefined();

    expect(play([{ type: 'closed', tabId: 't1' }], finished)).toEqual({});
  });

  it('ignores events for tabs it does not know, without making a new object', () => {
    const runs = play([started('t1')]);
    for (const event of [
      step('ghost', 1),
      { type: 'closed', tabId: 'ghost' },
      { type: 'goal', tabId: 'ghost', goal: 'x' },
      { type: 'finished', tabId: 'ghost', status: 'done', at: 1 },
    ] satisfies BrowserRunEvent[]) {
      expect(reduceBrowserRuns(runs, event)).toBe(runs);
    }
  });

  it('keeps notices and bounds what it remembers', () => {
    const many = play([
      started('t1'),
      ...Array.from({ length: 250 }, (_unused, index) => step('t1', index + 1)),
      ...Array.from(
        { length: 30 },
        (_unused, index): BrowserRunEvent => ({
          type: 'notice',
          tabId: 't1',
          notice: { kind: 'download', detail: `file-${index}`, at: index },
        })
      ),
    ]);
    expect(many.t1.steps).toHaveLength(200);
    expect(many.t1.steps.at(-1)?.step).toBe(250);
    expect(many.t1.notices).toHaveLength(20);
    expect(many.t1.notices.at(-1)?.detail).toBe('file-29');
  });

  it('takes a snapshot as the whole truth', () => {
    const before = play([started('old')]);
    const [run] = Object.values(play([started('t9', 5, 'c2')]));
    expect(play([{ type: 'snapshot', runs: [run] }], before)).toEqual({ t9: run });
  });

  it('lists a conversation’s runs, latest first', () => {
    const runs = play([started('a', 1, 'c1'), started('b', 3, 'c1'), started('c', 2, 'c2')]);
    expect(runsOfConversation(runs, 'c1').map((run) => run.tabId)).toEqual(['b', 'a']);
    expect(runsOfConversation(runs, 'nobody')).toEqual([]);
  });

  it('turns page text into one bounded line of plain text', () => {
    expect(plainText(`  a\n\tb${String.fromCharCode(0x2028)}c  `)).toBe('a b c');
    expect(plainText('<b>bold</b>')).toBe('<b>bold</b>');
    expect(plainText('x'.repeat(500))).toHaveLength(300);
    expect(plainText('x'.repeat(500)).endsWith('…')).toBe(true);
    expect(plainText(undefined)).toBe('');
    expect(plainText({ toString: () => 'sneaky' })).toBe('');
  });
});

describe('how the harness says a run ended', () => {
  it('keeps the code and the params the step bar translates, with the English reason beside them', () => {
    const finished = play([
      started('t1'),
      {
        type: 'finished',
        tabId: 't1',
        status: 'blocked',
        reason: 'no judge could choose an action (error:timeout)',
        code: 'no_judge',
        params: { judgeReason: 'error:timeout' },
        at: 900,
      },
    ]);
    expect(finished.t1).toMatchObject({
      status: 'blocked',
      reason: 'no judge could choose an action (error:timeout)',
      code: 'no_judge',
      params: { judgeReason: 'error:timeout' },
    });
    // A tab handed out again starts without the verdict of the run before it.
    const reused = play([started('t1', 1_000)], finished);
    expect(reused.t1.code).toBeUndefined();
    expect(reused.t1.params).toBeUndefined();
  });

  it('reads a Mu.run report: the coded fields that are what they claim to be, and nothing else', () => {
    expect(
      runEnding({
        state: 'finished',
        status: 'needs_confirmation',
        reason: '"Delete account" looks irreversible and was not confirmed',
        code: 'not_confirmed',
        params: { label: 'Delete\naccount', maxSteps: 40 },
      })
    ).toEqual({ code: 'not_confirmed', params: { label: 'Delete account', maxSteps: 40 } });
    expect(runEnding({ code: 'error', errorCode: 'browser_exited_on_start', errorParams: { exitCode: 1 } })).toEqual({
      code: 'error',
      errorCode: 'browser_exited_on_start',
      errorParams: { exitCode: 1 },
    });
    // An older harness sends no code at all.
    expect(runEnding({ state: 'finished', status: 'done' })).toEqual({});
  });

  it('drops a code that is not a plain word, params that are not flat, and params without a code', () => {
    expect(runEnding({ code: 'preview.muBrowser.status.done' })).toEqual({});
    expect(runEnding({ code: 'No Judge' })).toEqual({});
    expect(runEnding({ code: 42, params: { maxSteps: 40 } })).toEqual({});
    expect(runEnding({ code: 'stuck', params: [3] })).toEqual({ code: 'stuck' });
    expect(runEnding({ code: 'stuck', params: 'actions=3' })).toEqual({ code: 'stuck' });
    expect(
      runEnding({
        code: 'stuck',
        params: JSON.parse(
          '{"actions":3,"__proto__":{"polluted":1},"nested":{"a":1},"list":[1],"nan":null,"bad key":"x","big":1e999}'
        ),
      })
    ).toEqual({ code: 'stuck', params: { actions: 3 } });
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
    expect(runEnding({ errorParams: { exitCode: 1 } })).toEqual({});
  });

  it('bounds what it keeps of a page’s text inside the params', () => {
    const ending = runEnding({
      code: 'no_value',
      params: Object.fromEntries(Array.from({ length: 20 }, (_unused, index) => [`p${index}`, 'x'.repeat(900)])),
    });
    expect(Object.keys(ending.params ?? {})).toHaveLength(12);
    expect(String(ending.params?.p0)).toHaveLength(300);
  });
});
