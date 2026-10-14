import type { Activity } from '@/common/kyrn/types';

let next = 0;

/** A recorded event. `at` only orders the list: no fixture relies on two records being close in time. */
export function event(
  kind: string,
  payload: Record<string, unknown>,
  extra: Partial<Omit<Activity, 'kind' | 'payload'>> = {}
): Activity {
  next += 1;
  return { id: `event-${next}`, at: 1_000 + next, kind, payload, ...extra };
}

/** Correlation as the harness stamps it: runtime id, turn number and a per-runtime sequence. */
export const turn = (runtimeId: string, turnId: number, sequence: number) => ({ runtimeId, turnId, sequence });

export const verdict = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  version: 1,
  by: 'jev-latest',
  state: 'applied',
  turnType: 'multi_step_task',
  gear: 'standard',
  latencyMs: 700,
  hints: [],
  answers: [['Turn type', 'multi-step task 55% · research 32%']],
  ...overrides,
});

export const ledger = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: 'ledger-1',
  specId: 'input.preflight',
  specVersion: 1,
  mode: 'active',
  providerId: 'typesafe:jev-latest',
  modelId: 'jev-1.13.0',
  source: 'judge',
  outcome: { turnType: 'multi_step_task', gear: 'standard' },
  answers: { turn_type: { type: 'choice', choice: 'multi_step_task', probabilities: { multi_step_task: 0.55 } } },
  latencyMs: 700,
  usage: { inputTokens: 321 },
  stateDigest: 'digest',
  ...overrides,
});

export const gate = (overrides: Record<string, unknown>): Record<string, unknown> => ({
  at: '2026-09-21T10:00:00.000Z',
  score: 0.8,
  ...overrides,
});
