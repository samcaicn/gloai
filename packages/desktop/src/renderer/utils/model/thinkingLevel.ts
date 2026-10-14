import type { TFunction } from 'i18next';

/** The thinking levels pi knows, lowest first. Their names in each language are the `mu.levels` keys. */
export const THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const;

export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

export const isThinkingLevel = (value: unknown): value is ThinkingLevel =>
  typeof value === 'string' && (THINKING_LEVELS as readonly string[]).includes(value);

/**
 * A thinking level as the reader's language names it: agents report the raw id ("xhigh"), which is no word in any
 * language. A value that is not one of pi's levels is shown as the agent sent it.
 */
export function thinkingLevelLabel(t: TFunction, level: string): string {
  return isThinkingLevel(level) ? t(`mu.levels.${level}`) : level;
}
