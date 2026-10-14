/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The Jev preflight row the mu bridge adds at the top of every turn (`process/agent/kyrn/events.ts`): a tool call whose
 * id starts with `jev:`. The bridge has no i18n and its title is stored with the conversation, so the row is read from
 * stable data, `rawOutput.preflight` and the verdict's turn type, and named in the reader's language where it is shown
 * (`Messages/acp/jevLine.ts`). Conversations recorded before the bridge sent that data carry only the English title
 * (`Jev · Classifying`, `Jev · multi_step_task`, …, the name in any capitalization), which is read instead.
 */
export type JevPreflightRow = { state: 'pending' | 'verdict' | 'fallback'; turnType?: string };

const STATES = new Set<JevPreflightRow['state']>(['pending', 'verdict', 'fallback']);
const OLD_TITLE = /^jev · (.+)$/i;

const record = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const text = (value: unknown): string => (typeof value === 'string' ? value.trim() : '');

export const isJevPreflightId = (toolCallId: unknown): boolean =>
  typeof toolCallId === 'string' && toolCallId.startsWith('jev:');

/** The row a `jev:` tool call stands for, or undefined for any other tool call. */
export function readJevPreflightRow(
  toolCallId: unknown,
  title: unknown,
  rawOutput: unknown
): JevPreflightRow | undefined {
  if (!isJevPreflightId(toolCallId)) return undefined;
  const output = record(rawOutput);
  const state = text(output.preflight) as JevPreflightRow['state'];
  // The relay may have snake_cased the payload's keys.
  const turnType = text(output.turnType) || text(output.turn_type);
  if (STATES.has(state)) return state === 'verdict' && turnType ? { state, turnType } : { state };
  const label = OLD_TITLE.exec(text(title))?.[1]?.trim();
  if (!label) return turnType ? { state: 'verdict', turnType } : undefined;
  if (label === 'Classifying') return { state: 'pending' };
  if (label === 'Fallback') return { state: 'fallback' };
  if (turnType) return { state: 'verdict', turnType };
  if (label === 'Default') return { state: 'verdict' };
  return { state: 'verdict', turnType: label };
}
