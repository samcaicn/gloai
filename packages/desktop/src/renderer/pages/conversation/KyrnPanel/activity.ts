import type { Activity } from '@/common/kyrn/types';

export const record = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
export const list = (value: unknown): Record<string, unknown>[] => (Array.isArray(value) ? value.map(record) : []);
export const str = (value: unknown): string => (typeof value === 'string' ? value : '');

/**
 * Coalesce only mutable snapshots (and the latest reply's usage: only the last one is shown). Notes, gates and
 * deliveries stay distinct and ordered.
 */
export function mergeActivity(previous: Activity[], incoming: Activity[]): Activity[] {
  const result = [...previous];
  const ids = new Set(previous.map((event) => event.id));
  for (const event of incoming) {
    if (ids.has(event.id)) continue;
    ids.add(event.id);
    if (['swarm.snapshot', 'context.usage', 'context.policy', 'turn.usage'].includes(event.kind)) {
      const at = result.findIndex((row) => row.kind === event.kind && row.run === event.run);
      if (at >= 0) {
        result[at] = event;
        continue;
      }
    }
    result.push(event);
  }
  return result;
}
