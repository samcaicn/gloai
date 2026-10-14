import React from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { createInstance, type i18n as I18n } from 'i18next';
import { I18nextProvider } from 'react-i18next';
import type { Activity } from '@/common/kyrn/types';
import en from '@/renderer/services/i18n/locales/en-US/common.json';
import zh from '@/renderer/services/i18n/locales/zh-CN/common.json';
import GaugeRow from '@/renderer/pages/conversation/KyrnPanel/Board/GaugeRow';

// A tooltip's words stay on its trigger, where a test can read them without hovering.
vi.mock('@arco-design/web-react', () => ({
  Tooltip: ({ content, children }: { content?: React.ReactNode; children?: React.ReactNode }) => (
    <span data-tooltip={typeof content === 'string' ? content : ''}>{children}</span>
  ),
}));

let next = 0;
const event = (kind: string, payload: Record<string, unknown>): Activity => ({
  id: `e${++next}`,
  at: next,
  kind,
  payload,
});
const usage = (tokens: number, sessionTokens?: Record<string, number>) =>
  event('context.usage', { usage: { tokens, contextWindow: 200_000 }, sessionTokens });
const reply = (input: number, cacheRead: number) =>
  event('turn.usage', { input, output: 30, cacheRead, cacheWrite: 0 });

const instance = async (lng: string, common: unknown): Promise<I18n> => {
  const i18n = createInstance();
  await i18n.init({ lng, resources: { [lng]: { translation: { common } } }, interpolation: { escapeValue: false } });
  return i18n;
};

let chinese: I18n;
let english: I18n;
beforeAll(async () => {
  chinese = await instance('zh-CN', zh);
  english = await instance('en-US', en);
});
afterEach(cleanup);

const show = (i18n: I18n, events: Activity[]) =>
  render(
    <I18nextProvider i18n={i18n}>
      <GaugeRow events={events} />
    </I18nextProvider>
  );
const tooltipOf = (testId: string) =>
  screen.getByTestId(testId).closest('[data-tooltip]')?.getAttribute('data-tooltip');

describe('the gauges at the top of the board', () => {
  it('shows 「—」 for both before the first turn, and says when they come', () => {
    show(chinese, [usage(0)]);
    expect(screen.getByTestId('mu-gauge-context').textContent).toBe('上下文 —');
    expect(screen.getByTestId('mu-gauge-cache').textContent).toBe('缓存命中 —');
    expect(screen.queryByTestId('mu-gauge-cache-arc')).toBeNull();
    expect(tooltipOf('mu-gauge-context')).toBe(zh.kyrn.gauges.pending);
    expect(tooltipOf('mu-gauge-cache')).toBe(zh.kyrn.gauges.pending);
  });

  it('labels the context bar and the cache ring in plain words, with a number each', () => {
    show(chinese, [usage(68_000, { input: 300, cacheRead: 600, cacheWrite: 100 }), reply(300, 700)]);
    expect(screen.getByTestId('mu-gauge-context-value').textContent).toBe('34%');
    expect(screen.getByTestId('mu-gauge-cache-value').textContent).toBe('70%');
    expect(screen.getByTestId('mu-gauge-cache-arc')).toBeInTheDocument();
  });

  it('explains the bar, and gives the whole conversation’s cache hits beside the latest turn’s', () => {
    show(chinese, [usage(68_000, { input: 300, cacheRead: 600, cacheWrite: 100 }), reply(300, 700)]);
    expect(tooltipOf('mu-gauge-context')).toBe('模型下一次要读的内容占它上下文的 34%');
    expect(tooltipOf('mu-gauge-cache')).toBe('最近一轮缓存命中 70%，整个对话缓存命中 60%');
  });

  it('follows each reply', () => {
    const first = [usage(68_000), reply(300, 700)];
    const { rerender } = show(english, first);
    expect(screen.getByTestId('mu-gauge-cache').textContent).toBe('Cache hits 70%');
    rerender(
      <I18nextProvider i18n={english}>
        <GaugeRow events={[...first, usage(90_000), reply(10, 990)]} />
      </I18nextProvider>
    );
    expect(screen.getByTestId('mu-gauge-context').textContent).toBe('Context 45%');
    expect(screen.getByTestId('mu-gauge-cache-value').textContent).toBe('99%');
  });
});
