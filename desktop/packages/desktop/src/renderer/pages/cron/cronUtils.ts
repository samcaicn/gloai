/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { ICronJob } from '@/common/adapter/ipcBridge';
import type { TChatConversation } from '@/common/config/storage';
import { formatDate, formatDateTime, formatDuration, formatNumber, formatTime } from '@/renderer/services/i18n/format';
import { formatNameList } from '@/renderer/services/i18n/list';
import type { TFunction } from 'i18next';

/** The `cron.page.weekday.*` key of each cron day-of-week number (0 and 7 are Sunday). */
const WEEKDAY_KEYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'] as const;
const WEEKDAY_NUMBER_BY_NAME: Record<string, number> = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 };
/** Days are listed Monday first, the way the create dialog offers them. */
const MONDAY_FIRST = [1, 2, 3, 4, 5, 6, 0];
const WEEKDAYS_MON_FRI = [1, 2, 3, 4, 5];

const STEP = /^\*\/(\d+)$/;
const WHOLE = /^\d{1,2}$/;

function parseWeekday(token: string): number | null {
  const byName = WEEKDAY_NUMBER_BY_NAME[token.toUpperCase()];
  if (byName !== undefined) return byName;
  return /^[0-7]$/.test(token) ? Number(token) : null;
}

/**
 * The days a cron day-of-week field names, as 0 (Sunday) to 6; null for anything but plain days, lists and ranges
 * ("MON", "1,3,5", "MON-FRI", "1-5").
 */
function parseDayOfWeekField(field: string): Set<number> | null {
  const days = new Set<number>();
  for (const part of field.split(',')) {
    const bounds = part.split('-');
    if (bounds.length > 2) return null;
    const start = parseWeekday(bounds[0]);
    // A range may end on 7 (Sunday) so that "1-7" reads as the whole week.
    const end = bounds.length === 2 ? parseWeekday(bounds[1]) : start;
    if (start === null || end === null || end < start) return null;
    for (let day = start; day <= end; day++) days.add(day % 7);
  }
  return days;
}

/** A clock time from cron's minute and hour fields, in the app language ("9:00 AM", "09:00"); null unless both are plain numbers. */
function formatClock(hour: string, minute: string, language?: string): string | null {
  if (!WHOLE.test(hour) || !WHOLE.test(minute)) return null;
  const h = Number(hour);
  const m = Number(minute);
  if (h > 23 || m > 59) return null;
  return formatTime(new Date(2000, 0, 1, h, m), language, { hour: 'numeric', minute: '2-digit' });
}

function everyMinutes(count: number, t: TFunction): string {
  return count === 1 ? t('cron.page.scheduleDesc.everyMinute') : t('cron.page.scheduleDesc.everyNMinutes', { count });
}

function everyHours(count: number, t: TFunction): string {
  return count === 1 ? t('cron.page.scheduleDesc.hourly') : t('cron.page.scheduleDesc.everyNHours', { count });
}

/** A cron expression in words, in the app language; null when it is not one of the shapes described here. */
function formatCronExpr(expr: string, t: TFunction, language?: string): string | null {
  if (!expr.trim()) return t('cron.page.scheduleDesc.manual');

  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 5) return null;
  const [minute, hour, dayOfMonth, month, dayOfWeek] = parts;
  if (month !== '*') return null;

  if (hour === '*' && dayOfMonth === '*' && dayOfWeek === '*') {
    if (minute === '*') return everyMinutes(1, t);
    if (minute === '0') return t('cron.page.scheduleDesc.hourly');
    const step = STEP.exec(minute);
    return step && Number(step[1]) > 0 ? everyMinutes(Number(step[1]), t) : null;
  }

  const hourStep = STEP.exec(hour);
  if (hourStep && minute === '0' && dayOfMonth === '*' && dayOfWeek === '*') {
    return Number(hourStep[1]) > 0 ? everyHours(Number(hourStep[1]), t) : null;
  }

  const time = formatClock(hour, minute, language);
  if (!time) return null;

  if (dayOfMonth === '*') {
    const days = dayOfWeek === '*' ? null : parseDayOfWeekField(dayOfWeek);
    if (dayOfWeek === '*' || days?.size === 7) return t('cron.page.scheduleDesc.dailyAt', { time });
    if (!days) return null;
    if (days.size === WEEKDAYS_MON_FRI.length && WEEKDAYS_MON_FRI.every((day) => days.has(day))) {
      return t('cron.page.scheduleDesc.weekdaysAt', { time });
    }
    const names = MONDAY_FIRST.filter((day) => days.has(day)).map((day) => t(`cron.page.weekday.${WEEKDAY_KEYS[day]}`));
    if (names.length === 1) return t('cron.page.scheduleDesc.weeklyAt', { day: names[0], time });
    return t('cron.page.scheduleDesc.weeklyOnDaysAt', { days: formatNameList(names, language), time });
  }

  if (dayOfWeek === '*' && WHOLE.test(dayOfMonth)) {
    const day = Number(dayOfMonth);
    if (day >= 1 && day <= 31) {
      return t('cron.page.scheduleDesc.monthlyAt', { day: formatNumber(day, language), time });
    }
  }

  return null;
}

/**
 * A job's schedule in words, in the app language.
 *
 * Always derived from the schedule itself at render time: the stored `description` was written once, in whatever
 * language the creator used (the create dialog stores a custom schedule's bare cron expression there), so it would not
 * follow a later language switch.
 *
 * @param language app language (`i18n.language`), for times, numbers and lists
 */
export function formatSchedule(job: ICronJob, t: TFunction, language?: string): string {
  const { schedule } = job;
  switch (schedule.kind) {
    case 'cron':
      return (
        formatCronExpr(schedule.expr, t, language) ??
        t('cron.page.scheduleDesc.customExpr', { expr: schedule.expr.trim() })
      );
    case 'every': {
      const { everyMs } = schedule;
      if (!Number.isFinite(everyMs) || everyMs <= 0) return schedule.description;
      if (everyMs % 3_600_000 === 0) return everyHours(everyMs / 3_600_000, t);
      if (everyMs % 60_000 === 0) return everyMinutes(everyMs / 60_000, t);
      return t('cron.page.scheduleDesc.everyInterval', { interval: formatDuration(everyMs, language, 'long') });
    }
    case 'at':
      return t('cron.page.scheduleDesc.onceAt', {
        time: formatDateTime(schedule.atMs, language, { dateStyle: 'medium', timeStyle: 'short' }),
      });
    default:
      return '';
  }
}

/**
 * Resolve the current IANA time zone for cron scheduling.
 * Falls back to UTC when the environment cannot provide a valid identifier.
 */
export function getCurrentCronTimeZone(): string {
  try {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return timeZone && timeZone.trim() ? timeZone : 'UTC';
  } catch {
    return 'UTC';
  }
}

/**
 * Build a cron schedule payload anchored to the current local time zone.
 */
export function createCronSchedule(expr: string, description: string): Extract<ICronJob['schedule'], { kind: 'cron' }> {
  return {
    kind: 'cron',
    expr,
    tz: getCurrentCronTimeZone(),
    description,
  };
}

/**
 * Format next run time for display, in the app language.
 *
 * @param locale app language (`i18n.language`)
 */
export function formatNextRun(next_run_at_ms: number | undefined, locale?: string): string {
  if (!next_run_at_ms) return '-';
  return formatDateTime(next_run_at_ms, locale);
}

/**
 * Title for a scheduled run's conversation, e.g. "daily report 08/17/26".
 *
 * The date part follows the app language: the previous hardcoded DD-MM-YY read
 * as the wrong date in month-first locales (05-07-26 is May 7 to a US reader).
 *
 * @param language app language (`i18n.language`)
 */
export function formatCronRunConversationTitle(jobName: string, runAtMs: number, language?: string): string {
  const dateLabel = formatDate(runAtMs, language, { year: '2-digit', month: '2-digit', day: '2-digit' });
  return `${jobName.trim()} ${dateLabel}`;
}

/**
 * Get job status flags
 */
export function getJobStatusFlags(job: ICronJob): { hasError: boolean; isPaused: boolean } {
  return {
    hasError: job.state.last_status === 'error' || job.state.last_status === 'missed',
    isPaused: !job.enabled,
  };
}

export function resolveCronJobId(extra: TChatConversation['extra'] | undefined): string | undefined {
  const maybeExtra = extra as { cron_job_id?: unknown; cronJobId?: unknown } | undefined;
  const snakeCase = maybeExtra?.cron_job_id;
  if (typeof snakeCase === 'string' && snakeCase.trim()) return snakeCase;
  const camelCase = maybeExtra?.cronJobId;
  if (typeof camelCase === 'string' && camelCase.trim()) return camelCase;
  return undefined;
}
