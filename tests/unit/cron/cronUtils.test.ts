/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { createInstance, type TFunction } from 'i18next';
import type { ICronJob, ICronSchedule } from '@/common/adapter/ipcBridge';
import enCron from '@/renderer/services/i18n/locales/en-US/cron.json';
import zhCron from '@/renderer/services/i18n/locales/zh-CN/cron.json';
import {
  createCronSchedule,
  formatCronRunConversationTitle,
  formatSchedule,
  getCurrentCronTimeZone,
} from '@/renderer/pages/cron/cronUtils';

const translator = (language: 'en-US' | 'zh-CN', cron: Record<string, unknown>): TFunction => {
  const i18n = createInstance();
  void i18n.init({
    lng: language,
    initImmediate: false,
    resources: { [language]: { translation: { cron } } },
    interpolation: { escapeValue: false },
  });
  return i18n.t;
};

const jobWith = (schedule: ICronSchedule) => ({ schedule }) as unknown as ICronJob;
const cron = (expr: string, description = expr) => jobWith({ kind: 'cron', expr, description });

const originalDateTimeFormat = Intl.DateTimeFormat;

describe('cronUtils', () => {
  afterEach(() => {
    Intl.DateTimeFormat = originalDateTimeFormat;
    vi.restoreAllMocks();
  });

  it('uses the current system timezone when building cron schedules', () => {
    Intl.DateTimeFormat = vi.fn(
      () =>
        ({
          resolvedOptions: () => ({ timeZone: 'Asia/Shanghai' }),
        }) as Intl.DateTimeFormat
    ) as unknown as typeof Intl.DateTimeFormat;

    expect(createCronSchedule('0 10 * * *', 'Daily at 10:00')).toEqual({
      kind: 'cron',
      expr: '0 10 * * *',
      tz: 'Asia/Shanghai',
      description: 'Daily at 10:00',
    });
  });

  it('falls back to UTC when timezone resolution fails', () => {
    Intl.DateTimeFormat = vi.fn(() => {
      throw new Error('boom');
    }) as unknown as typeof Intl.DateTimeFormat;

    expect(getCurrentCronTimeZone()).toBe('UTC');
  });

  it('formats new cron run conversation titles with the execution date in the app language', () => {
    const runAt = Date.UTC(2026, 6, 1, 12, 0, 0);
    // The previous hardcoded DD-MM-YY read as the wrong date in month-first
    // locales; the date part now follows the app language.
    expect(formatCronRunConversationTitle('Daily report', runAt, 'en-US')).toBe('Daily report 07/01/26');
    expect(formatCronRunConversationTitle('Daily report', runAt, 'de-DE')).toBe('Daily report 01.07.26');
    // No language falls back to the default (en-US), never the host locale.
    expect(formatCronRunConversationTitle('Daily report', runAt)).toBe('Daily report 07/01/26');
  });
});

describe('formatSchedule', () => {
  const en = translator('en-US', enCron);
  const zh = translator('zh-CN', zhCron);
  const inEnglish = (job: ICronJob) => formatSchedule(job, en, 'en-US');
  const inChinese = (job: ICronJob) => formatSchedule(job, zh, 'zh-CN');

  it('describes intervals, with singular and plural in English', () => {
    expect(inEnglish(cron('* * * * *'))).toBe('Every minute');
    expect(inEnglish(cron('*/15 * * * *'))).toBe('Every 15 minutes');
    expect(inEnglish(cron('0 * * * *'))).toBe('Every hour');
    expect(inEnglish(cron('0 */2 * * *'))).toBe('Every 2 hours');
    expect(inChinese(cron('*/15 * * * *'))).toBe('每 15 分钟执行');
    expect(inChinese(cron('0 */2 * * *'))).toBe('每 2 小时执行');
  });

  it('writes the time of day the way the app language does', () => {
    expect(inEnglish(cron('0 9 * * *'))).toBe('Every day at 9:00 AM');
    expect(inEnglish(cron('30 21 * * *'))).toBe('Every day at 9:30 PM');
    expect(inChinese(cron('30 21 * * *'))).toBe('每天 21:30 执行');
  });

  it('names weekdays, day lists and days of the month', () => {
    expect(inEnglish(cron('0 9 * * MON-FRI'))).toBe('Weekdays at 9:00 AM');
    expect(inEnglish(cron('0 9 * * 1-5'))).toBe('Weekdays at 9:00 AM');
    expect(inEnglish(cron('0 9 * * FRI'))).toBe('Every Friday at 9:00 AM');
    expect(inEnglish(cron('30 9 * * MON,WED'))).toBe('Every Monday and Wednesday at 9:30 AM');
    expect(inEnglish(cron('0 8 * * SUN,1,3'))).toBe('Every Monday, Wednesday, and Sunday at 8:00 AM');
    expect(inChinese(cron('30 9 * * MON,WED'))).toBe('每周星期一和星期三 9:30 执行');
    expect(inEnglish(cron('0 9 1 * *'))).toBe('On day 1 of every month at 9:00 AM');
    expect(inChinese(cron('0 9 1 * *'))).toBe('每月 1 日 9:00 执行');
  });

  it('falls back to the expression itself inside a translated sentence', () => {
    expect(inEnglish(cron('0 9 * 1 *'))).toBe('Custom schedule (0 9 * 1 *)');
    expect(inChinese(cron('0 9 * 1 *'))).toBe('自定义计划（0 9 * 1 *）');
    expect(inEnglish(cron('0 0 9 * * *'))).toBe('Custom schedule (0 0 9 * * *)');
  });

  it('ignores the description stored in the language the task was created in', () => {
    const job = cron('0 9 * * *', '每天 09:00 执行');
    expect(inEnglish(job)).toBe('Every day at 9:00 AM');
    expect(inEnglish(cron('', '手动触发'))).toBe('Manual');
  });

  it('describes interval and one-off schedules', () => {
    expect(inEnglish(jobWith({ kind: 'every', everyMs: 3_600_000, description: '' }))).toBe('Every hour');
    expect(inEnglish(jobWith({ kind: 'every', everyMs: 7_200_000, description: '' }))).toBe('Every 2 hours');
    expect(inEnglish(jobWith({ kind: 'every', everyMs: 300_000, description: '' }))).toBe('Every 5 minutes');
    expect(inEnglish(jobWith({ kind: 'every', everyMs: 90_000, description: '' }))).toBe('Every 1 minute, 30 seconds');
    const atMs = new Date(2026, 0, 1, 21, 5).getTime();
    expect(inEnglish(jobWith({ kind: 'at', atMs, description: '' }))).toBe('Once at Jan 1, 2026, 9:05 PM');
  });
});
