export type HiveFocusRequest = { conversationId: string; runId: string; beeName?: string };
const EVENT = 'kyrn:hive-focus';

/** Open the native panel for one exact conversation/run, never toggle it closed. */
export function requestHiveFocus(request: HiveFocusRequest): void {
  window.dispatchEvent(new CustomEvent<HiveFocusRequest>(EVENT, { detail: request }));
}

/** Subscribe to renderer-only navigation; this cannot issue agent commands. */
export function onHiveFocus(listener: (request: HiveFocusRequest) => void): () => void {
  const receive = (event: Event) => {
    const detail: unknown = (event as CustomEvent<unknown>).detail;
    if (!detail || typeof detail !== 'object') return;
    const request = detail as Partial<HiveFocusRequest>;
    if (typeof request.conversationId !== 'string' || typeof request.runId !== 'string') return;
    listener({
      conversationId: request.conversationId,
      runId: request.runId,
      beeName: typeof request.beeName === 'string' ? request.beeName : undefined,
    });
  };
  window.addEventListener(EVENT, receive);
  return () => window.removeEventListener(EVENT, receive);
}
