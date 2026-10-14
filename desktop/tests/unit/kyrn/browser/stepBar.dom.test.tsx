import React from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import type { BrowserRunState } from '@/common/kyrn/browserRun';
import preview from '@/renderer/services/i18n/locales/zh-CN/preview.json';
import common from '@/renderer/services/i18n/locales/zh-CN/common.json';
import ConfirmDialog from '@/renderer/pages/conversation/Preview/browser/muBrowser/ConfirmDialog';
import StepBar from '@/renderer/pages/conversation/Preview/browser/muBrowser/StepBar';

const { control, confirm } = vi.hoisted(() => ({ control: vi.fn(), confirm: vi.fn() }));
vi.mock('@/common/kyrn/browserBridge', () => ({
  kyrnBrowserBridge: { control: { invoke: control }, confirm: { invoke: confirm } },
}));

const i18n = createInstance();
beforeAll(async () => {
  await i18n.init({
    lng: 'zh',
    resources: { zh: { translation: { preview, common } } },
    interpolation: { escapeValue: false },
  });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const run = (patch: Partial<BrowserRunState> = {}): BrowserRunState => ({
  tabId: 'browser-1',
  conversationId: 'c1',
  phase: 'running',
  goal: '在目录里搜索 WeakMap',
  startUrl: 'https://library.test/',
  startedAt: Date.now() - 65_000,
  paused: false,
  stopRequested: false,
  steps: [
    {
      step: 1,
      kind: 'fill',
      action: '搜索目录',
      url: 'https://library.test/',
      probability: 0.88,
      pageChanged: true,
      at: 1,
    },
    {
      step: 2,
      kind: 'click',
      action: '搜索',
      url: 'https://library.test/r',
      probability: 0.918,
      pageChanged: true,
      at: 2,
    },
    {
      step: 3,
      kind: 'click',
      action: '<img src=x onerror=alert(1)>',
      url: '',
      probability: 0.5,
      pageChanged: false,
      at: 3,
    },
  ],
  notices: [],
  ...patch,
});
const show = (ui: React.ReactElement) => render(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>);

describe('the step bar above a page mu is driving', () => {
  it('says the current step the way a person reads it: 第 2 步 · 点击「搜索」· 把握 92%', () => {
    show(<StepBar run={run({ steps: run().steps.slice(0, 2) })} />);
    const bar = screen.getByTestId('mu-step-bar');
    expect(bar.textContent).toContain('第 2 步');
    expect(bar.textContent).toContain('点击');
    expect(bar.textContent).toContain('搜索');
    expect(bar.textContent).toContain('把握 92%');
    expect(bar.textContent).toContain('1:05');
    expect(bar.getAttribute('data-tone')).toBe('running');
  });

  it('shows a page’s label as text, never as markup', () => {
    show(<StepBar run={run()} />);
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeTruthy();
    expect(document.querySelector('img')).toBeNull();
  });

  it('unfolds the steps so far and the goal', () => {
    show(<StepBar run={run()} />);
    expect(screen.queryByText('在目录里搜索 WeakMap')).toBeNull();
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('在目录里搜索 WeakMap')).toBeTruthy();
    expect(screen.getAllByText('搜索目录')).toHaveLength(1);
    expect(screen.getByText('页面无变化')).toBeTruthy();
  });

  it('sends pause, take over and stop; while paused it offers resume and says why it is paused', () => {
    const view = show(<StepBar run={run()} />);
    fireEvent.click(screen.getByText('暂停'));
    fireEvent.click(screen.getByText('接管'));
    fireEvent.click(screen.getByText('停止'));
    expect(control.mock.calls.map(([call]) => call)).toEqual([
      { tabId: 'browser-1', action: 'pause' },
      { tabId: 'browser-1', action: 'takeover' },
      { tabId: 'browser-1', action: 'stop' },
    ]);

    view.unmount();
    show(<StepBar run={run({ paused: true, pausedBy: 'interaction' })} />);
    expect(screen.queryByText('暂停')).toBeNull();
    expect(screen.getByText(/你刚才操作了页面，mu 已自动暂停/)).toBeTruthy();
    fireEvent.click(screen.getByText('继续'));
    expect(control).toHaveBeenLastCalledWith({ tabId: 'browser-1', action: 'resume' });
    expect(screen.getByTestId('mu-step-bar').getAttribute('data-tone')).toBe('paused');
  });

  it('keeps the final status after the run, without controls', () => {
    for (const [status, words] of [
      ['done', '完成'],
      ['blocked', '受阻'],
      ['needs_confirmation', '需要确认'],
      ['stopped', '已停止'],
      ['budget', '步数用尽'],
    ] as const) {
      const view = show(<StepBar run={run({ phase: 'finished', status, finishedAt: Date.now() })} />);
      expect(screen.getByText(words)).toBeTruthy();
      expect(screen.queryByText('停止')).toBeNull();
      expect(screen.queryByText('继续')).toBeNull();
      view.unmount();
    }
  });

  it('tells about a cancelled download', () => {
    show(<StepBar run={run({ notices: [{ kind: 'download', detail: 'report.pdf', at: 1 }] })} />);
    expect(screen.getByText('已拦截下载：report.pdf')).toBeTruthy();
  });

  it('names a refused permission in the person’s words, and an unknown one as it came', () => {
    const view = show(<StepBar run={run({ notices: [{ kind: 'permission', detail: 'geolocation', at: 1 }] })} />);
    expect(screen.getByText('已拒绝权限请求：位置')).toBeTruthy();
    view.unmount();
    show(<StepBar run={run({ notices: [{ kind: 'permission', detail: 'brand-new-thing', at: 1 }] })} />);
    expect(screen.getByText('已拒绝权限请求：brand-new-thing')).toBeTruthy();
  });

  it('says why a page was taken away in the person’s language, and keeps an unknown cause as a technical detail', () => {
    const finished = { phase: 'finished' as const, status: 'detached' as const, finishedAt: Date.now() };
    const view = show(<StepBar run={run({ ...finished, reason: 'devtools' })} />);
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('原因')).toBeTruthy();
    expect(screen.getByText('这个页面打开了开发者工具。')).toBeTruthy();
    expect(screen.queryByText('devtools')).toBeNull();
    view.unmount();

    show(<StepBar run={run({ ...finished, reason: 'Something new from Chromium' })} />);
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('技术详情')).toBeTruthy();
    expect(screen.getByText('Something new from Chromium')).toBeTruthy();
  });

  it('keeps the harness’s own verdict as mu’s words', () => {
    show(
      <StepBar run={run({ phase: 'finished', status: 'blocked', finishedAt: Date.now(), reason: 'No search box' })} />
    );
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('mu 的说明')).toBeTruthy();
    expect(screen.getByText('No search box')).toBeTruthy();
  });
});

describe('the step bar after the harness said how the run ended', () => {
  const ended = (patch: Partial<BrowserRunState>) =>
    run({ phase: 'finished', status: 'blocked', finishedAt: Date.now(), ...patch });

  it('says the harness’s code in the person’s language, the judge’s own reason included', () => {
    show(
      <StepBar
        run={ended({
          reason: 'no judge could choose an action (error:rate_limited)',
          code: 'no_judge',
          params: { judgeReason: 'error:rate_limited' },
        })}
      />
    );
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('mu 的说明')).toBeTruthy();
    expect(screen.getByText('没有判定器能选出下一步操作（判定器请求过于频繁）。')).toBeTruthy();
    expect(screen.queryByText(/no judge could choose/)).toBeNull();
  });

  it('puts a page’s label into the sentence as text, never as markup', () => {
    show(
      <StepBar
        run={ended({
          status: 'needs_confirmation',
          reason: '"<img src=x onerror=alert(1)>" looks irreversible and was not confirmed',
          code: 'not_confirmed',
          params: { label: '<img src=x onerror=alert(1)>' },
        })}
      />
    );
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('「<img src=x onerror=alert(1)>」看起来无法撤销，且没有得到确认。')).toBeTruthy();
    expect(document.querySelector('img')).toBeNull();
  });

  it('falls back to the harness’s English for a code it does not know, and calls an exception a technical detail', () => {
    const view = show(<StepBar run={ended({ code: 'brand_new', reason: 'the judge went fishing' })} />);
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('mu 的说明')).toBeTruthy();
    expect(screen.getByText('the judge went fishing')).toBeTruthy();
    view.unmount();

    show(<StepBar run={ended({ status: 'failed', code: 'error', reason: 'Target page crashed' })} />);
    fireEvent.click(screen.getByText('步骤 3'));
    expect(screen.getByText('技术详情')).toBeTruthy();
    expect(screen.getByText('Target page crashed')).toBeTruthy();
  });
});

describe('the confirmation dialog', () => {
  const asking = (seconds: number) =>
    run({
      confirm: {
        id: 'q1',
        label: '<b>删除账号</b> [链接](javascript:alert(1))',
        url: 'https://shop.test/account',
        askedAt: Date.now(),
        deadline: Date.now() + seconds * 1000,
      },
    });

  it('shows the label and the address as plain text, with a countdown that ends in a refusal', () => {
    show(<ConfirmDialog run={asking(120)} />);
    expect(screen.getByText('mu 想执行一个可能无法撤销的操作')).toBeTruthy();
    expect(screen.getByText('<b>删除账号</b> [链接](javascript:alert(1))')).toBeTruthy();
    expect(screen.getByText('https://shop.test/account')).toBeTruthy();
    const body = screen.getByTestId('mu-confirm');
    expect(body.querySelector('b')).toBeNull();
    expect(body.querySelector('a')).toBeNull();
    expect(screen.getByText(/1(20|19) 秒后自动拒绝/)).toBeTruthy();
  });

  it('answers with allow or deny, for this question only', () => {
    show(<ConfirmDialog run={asking(60)} />);
    fireEvent.click(screen.getByText('拒绝'));
    expect(confirm).toHaveBeenLastCalledWith({ tabId: 'browser-1', id: 'q1', allowed: false });
    fireEvent.click(screen.getByText('允许'));
    expect(confirm).toHaveBeenLastCalledWith({ tabId: 'browser-1', id: 'q1', allowed: true });
  });

  it('is not there when nothing is asked', () => {
    show(<ConfirmDialog run={run()} />);
    expect(screen.queryByTestId('mu-confirm')).toBeNull();
  });
});
