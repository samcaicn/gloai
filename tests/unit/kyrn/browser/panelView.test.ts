import { beforeAll, describe, expect, it } from 'vitest';
import { createInstance, type TFunction } from 'i18next';
import type { BrowserRunState } from '@/common/kyrn/browserRun';
import enPreview from '@/renderer/services/i18n/locales/en-US/preview.json';
import zhPreview from '@/renderer/services/i18n/locales/zh-CN/preview.json';
import twPreview from '@/renderer/services/i18n/locales/zh-TW/preview.json';
import {
  barTone,
  clock,
  confidencePercent,
  elapsedMs,
  judgeReasonKey,
  permissionKey,
  reasonLine,
  reasonText,
  secondsLeft,
  statusKey,
  verbKey,
} from '@/renderer/pages/conversation/Preview/browser/muBrowser/format';
import { decideOpen } from '@/renderer/pages/conversation/Preview/browser/muBrowser/panelPolicy';

const run = (patch: Partial<BrowserRunState> = {}): BrowserRunState => ({
  tabId: 't1',
  conversationId: 'c1',
  phase: 'running',
  goal: '',
  startUrl: 'about:blank',
  startedAt: 1_000,
  paused: false,
  stopRequested: false,
  steps: [],
  notices: [],
  ...patch,
});

const browser = (id: string, isMu = false) => ({ id, isBrowser: true, isMu });
const file = (id: string) => ({ id, isBrowser: false, isMu: false });

describe('what the step bar says', () => {
  it('names the operation, and calls anything it does not know an operation', () => {
    expect(verbKey('click')).toBe('preview.muBrowser.verb.click');
    expect(verbKey('fill')).toBe('preview.muBrowser.verb.fill');
    expect(verbKey('none')).toBe('preview.muBrowser.verb.none');
    expect(verbKey('<script>')).toBe('preview.muBrowser.verb.other');
    expect(statusKey('needs_confirmation')).toBe('preview.muBrowser.status.needs_confirmation');
  });

  it('shows the judge’s probability as a percentage, or nothing when there is none', () => {
    const step = { step: 3, kind: 'click', action: 'Search', url: '', at: 0 };
    expect(confidencePercent({ ...step, probability: 0.918 })).toBe(92);
    expect(confidencePercent({ ...step, probability: 1 })).toBe(100);
    expect(confidencePercent(step)).toBeUndefined();
  });

  it('counts the time of a run, and stops counting when it has finished', () => {
    expect(clock(0)).toBe('0:00');
    expect(clock(65_400)).toBe('1:05');
    expect(clock(3_725_000)).toBe('1:02:05');
    expect(clock(-5)).toBe('0:00');
    expect(elapsedMs(run(), 61_000)).toBe(60_000);
    expect(elapsedMs(run({ phase: 'finished', finishedAt: 5_000 }), 99_000)).toBe(4_000);
  });

  it('counts a confirmation down to zero and not below', () => {
    expect(secondsLeft(120_000, 0)).toBe(120);
    expect(secondsLeft(120_000, 119_100)).toBe(1);
    expect(secondsLeft(120_000, 125_000)).toBe(0);
  });

  it('only looks pleased when the harness itself said done', () => {
    expect(barTone(run())).toBe('running');
    expect(barTone(run({ paused: true, pausedBy: 'interaction' }))).toBe('paused');
    expect(barTone(run({ stopRequested: true }))).toBe('stopping');
    expect(barTone(run({ phase: 'finished', status: 'done' }))).toBe('good');
    expect(barTone(run({ phase: 'finished', status: 'ended' }))).toBe('quiet');
    expect(barTone(run({ phase: 'finished', status: 'stopped' }))).toBe('quiet');
    expect(barTone(run({ phase: 'finished', status: 'needs_confirmation' }))).toBe('warn');
    expect(barTone(run({ phase: 'finished', status: 'budget' }))).toBe('warn');
    expect(barTone(run({ phase: 'finished', status: 'detached' }))).toBe('bad');
  });
});

describe('where a new run gets its tab', () => {
  it('opens a new tab while there is room', () => {
    expect(decideOpen([file('f'), browser('b1')], {}, 'c1', 10)).toEqual({ kind: 'new' });
  });

  it('hands out the conversation’s own finished mu tab again, oldest first, never one that is running', () => {
    const runs = {
      m1: run({ tabId: 'm1', phase: 'finished', status: 'done', startedAt: 5 }),
      m2: run({ tabId: 'm2', phase: 'finished', status: 'ended', startedAt: 2 }),
      m3: run({ tabId: 'm3', startedAt: 1 }),
      other: run({ tabId: 'other', conversationId: 'c2', phase: 'finished', status: 'done', startedAt: 0 }),
    };
    const tabs = [browser('m1', true), browser('m2', true), browser('m3', true), browser('other', true)];
    expect(decideOpen(tabs, runs, 'c1', 10)).toEqual({ kind: 'reuse', tabId: 'm2' });
    expect(decideOpen(tabs, runs, 'c3', 10)).toEqual({ kind: 'new' });
    // A run the store knows but whose tab is gone from the panel is not a tab to hand out.
    expect(decideOpen([browser('m3', true)], runs, 'c1', 10)).toEqual({ kind: 'new' });
  });

  it('refuses when the panel is full rather than take over a tab someone is using', () => {
    const full = Array.from({ length: 10 }, (_unused, index) => browser(`b${index}`));
    const decision = decideOpen(full, {}, 'c1', 10);
    expect(decision.kind).toBe('refuse');
    expect(decision.kind === 'refuse' && decision.error).toContain('full');
    // Full, but one of them is this conversation's finished mu tab: that one is used.
    const runs = { b3: run({ tabId: 'b3', phase: 'finished', status: 'done' }) };
    const withMu = full.map((tab) => browser(tab.id, tab.id === 'b3'));
    expect(decideOpen(withMu, runs, 'c1', 10)).toEqual({ kind: 'reuse', tabId: 'b3' });
  });
});

describe('what the step bar says about the end of a run and what the app refused', () => {
  it('translates a detached run’s code, offers an unknown cause as a technical detail, and keeps the harness’s words', () => {
    const finished = { phase: 'finished' as const, status: 'detached' as const };
    expect(reasonLine(run({ ...finished, reason: 'tab_closed' }))).toEqual({
      labelKey: 'preview.muBrowser.detachCause',
      key: 'preview.muBrowser.detachReason.tab_closed',
    });
    expect(reasonLine(run({ ...finished, reason: 'target gone sideways' }))).toEqual({
      labelKey: 'common.technical_details',
      text: 'target gone sideways',
    });
    expect(reasonLine(run({ phase: 'finished', status: 'done', reason: 'Found it' }))).toEqual({
      labelKey: 'preview.muBrowser.reason',
      text: 'Found it',
    });
    expect(reasonLine(run({ reason: 'still going' }))).toBeUndefined();
    expect(reasonLine(run({ phase: 'finished', status: 'done' }))).toBeUndefined();
  });

  it('names Electron’s permission ids, and nothing else (no prototype names)', () => {
    expect(permissionKey('geolocation')).toBe('preview.muBrowser.permission.location');
    expect(permissionKey('clipboard-read')).toBe('preview.muBrowser.permission.clipboard');
    expect(permissionKey('media')).toBe('preview.muBrowser.permission.media');
    expect(permissionKey('constructor')).toBeUndefined();
    expect(permissionKey('brand-new')).toBeUndefined();
  });
});

describe('the harness’s end codes on the unfolded bar', () => {
  const ended = (patch: Partial<BrowserRunState>) =>
    reasonLine(run({ phase: 'finished', status: 'blocked', ...patch }));

  it('says a coded ending as a sentence with its params, whatever English came with it', () => {
    expect(ended({ code: 'max_steps', params: { maxSteps: 40 }, reason: 'stopped after 40 actions' })).toEqual({
      labelKey: 'preview.muBrowser.reason',
      key: 'preview.muBrowser.end.max_steps',
      values: { count: 40 },
    });
    expect(ended({ code: 'stuck', params: { actions: 3 } })).toMatchObject({ values: { count: 3 } });
    expect(ended({ code: 'not_confirmed', params: { label: 'Delete account' } })).toMatchObject({
      key: 'preview.muBrowser.end.not_confirmed',
      values: { label: 'Delete account' },
    });
    expect(ended({ code: 'no_value', params: { label: 'Email' } })).toMatchObject({ values: { label: 'Email' } });
    expect(ended({ code: 'stopped_by_user' })).toMatchObject({ key: 'preview.muBrowser.end.stopped_by_user' });
    expect(ended({ code: 'no_progress' })).toMatchObject({ key: 'preview.muBrowser.end.no_progress' });
    expect(ended({ code: 'no_judge', params: { judgeReason: 'abstain' } })).toEqual({
      labelKey: 'preview.muBrowser.reason',
      key: 'preview.muBrowser.end.no_judge',
      terms: { judgeReason: 'preview.muBrowser.judgeReason.abstain' },
    });
  });

  it('keeps the harness’s words for an unknown code, a code without its params, or no code at all', () => {
    const english = { labelKey: 'preview.muBrowser.reason', text: 'stopped after 40 actions' };
    expect(ended({ code: 'brand_new', reason: 'stopped after 40 actions' })).toEqual(english);
    expect(ended({ code: 'max_steps', reason: 'stopped after 40 actions' })).toEqual(english);
    expect(ended({ code: 'max_steps', params: { maxSteps: 'forty' }, reason: 'stopped after 40 actions' })).toEqual(
      english
    );
    expect(ended({ code: 'not_confirmed', params: { label: '' }, reason: 'stopped after 40 actions' })).toEqual(
      english
    );
    expect(
      ended({ code: 'no_judge', params: { judgeReason: 'something new' }, reason: 'stopped after 40 actions' })
    ).toEqual(english);
    expect(ended({ reason: 'stopped after 40 actions' })).toEqual(english);
    // Endings that come without a sentence stay without a line.
    expect(ended({ code: 'done' })).toBeUndefined();
    expect(ended({ code: 'read' })).toBeUndefined();
    expect(ended({ code: 'cancelled' })).toBeUndefined();
  });

  it('offers the message of a run that threw as a technical detail, keeping its code', () => {
    expect(ended({ status: 'failed', code: 'error', errorCode: 'mystery', reason: 'socket hang up' })).toEqual({
      labelKey: 'common.technical_details',
      text: 'socket hang up',
    });
  });

  it('names the judge’s own reason, and a failure of a kind it does not know as a failed judge call', () => {
    expect(judgeReasonKey('shadow')).toBe('preview.muBrowser.judgeReason.shadow');
    expect(judgeReasonKey('abstain')).toBe('preview.muBrowser.judgeReason.abstain');
    // browser.step switched off while the run was going.
    expect(judgeReasonKey('off')).toBe('preview.muBrowser.judgeReason.off');
    expect(judgeReasonKey('no verdict')).toBe('preview.muBrowser.judgeReason.noVerdict');
    for (const kind of [
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
    ]) {
      expect(judgeReasonKey(`error:${kind}`)).toBe(`preview.muBrowser.judgeReason.error.${kind}`);
    }
    expect(judgeReasonKey('error:quota_melted')).toBe('preview.muBrowser.judgeReason.error.other');
    expect(judgeReasonKey('error:constructor')).toBe('preview.muBrowser.judgeReason.error.other');
    expect(judgeReasonKey('Off')).toBeUndefined();
    expect(judgeReasonKey('constructor')).toBeUndefined();
  });

  describe('in words', () => {
    const tIn = {} as Record<string, TFunction>;
    beforeAll(async () => {
      const i18n = createInstance();
      await i18n.init({
        lng: 'en-US',
        resources: {
          'en-US': { translation: { preview: enPreview } },
          'zh-CN': { translation: { preview: zhPreview } },
          'zh-TW': { translation: { preview: twPreview } },
        },
        interpolation: { escapeValue: false },
      });
      for (const language of ['en-US', 'zh-CN', 'zh-TW']) tIn[language] = i18n.getFixedT(language);
    });
    const say = (language: string, patch: Partial<BrowserRunState>) => {
      const line = ended(patch);
      return line ? reasonText(line, tIn[language]) : undefined;
    };

    it('translates the judge’s reason inside the sentence', () => {
      const noJudge = { code: 'no_judge', params: { judgeReason: 'error:timeout' } };
      expect(say('en-US', noJudge)).toBe('No judge could choose an action (the judge timed out).');
      expect(say('zh-CN', noJudge)).toBe('没有判定器能选出下一步操作（判定器超时）。');
      expect(say('zh-TW', noJudge)).toBe('沒有判定器能選出下一步操作（判定器逾時）。');
      expect(say('zh-CN', { code: 'no_judge', params: { judgeReason: 'error:all' } })).toBe(
        '没有判定器能选出下一步操作（所有判定请求都失败了）。'
      );
      expect(say('zh-TW', { code: 'no_judge', params: { judgeReason: 'off' } })).toBe(
        '沒有判定器能選出下一步操作（判定器未啟用）。'
      );
      expect(say('zh-CN', { code: 'stopped_by_user', status: 'stopped' })).toBe('你叫停了这次运行。');
    });

    it('counts in the language’s plural forms and keeps a page’s label as it came', () => {
      expect(say('en-US', { code: 'max_steps', params: { maxSteps: 1 } })).toBe('Stopped after 1 action.');
      expect(say('en-US', { code: 'max_steps', params: { maxSteps: 40 } })).toBe('Stopped after 40 actions.');
      expect(say('zh-CN', { code: 'max_steps', params: { maxSteps: 40 } })).toBe('执行 40 个操作后停止了。');
      expect(say('en-US', { code: 'stuck', params: { actions: 3 } })).toBe('3 actions in a row changed nothing.');
      const label = { code: 'not_confirmed', params: { label: '<b>删除</b> {{x}} $t(preview.muBrowser.stop)' } };
      expect(say('zh-CN', label)).toBe(
        '「<b>删除</b> {{x}} $t(preview.muBrowser.stop)」看起来无法撤销，且没有得到确认。'
      );
      expect(say('zh-TW', label)).toBe(
        '「<b>删除</b> {{x}} $t(preview.muBrowser.stop)」看起來無法復原，且沒有得到確認。'
      );
    });

    it('gives the text of a line that has no sentence as it is', () => {
      expect(say('zh-CN', { reason: 'No search box' })).toBe('No search box');
    });
  });
});
