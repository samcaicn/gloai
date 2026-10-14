// Runs the app's own startup registration (product.ts, as it is) against the backend in CHECK_BASE.
import type { BackendRequest } from '../../../packages/desktop/src/process/agent/kyrn/product.ts';

// The desktop package is not `type: module`, so tsx hands this file over as CommonJS: take the export from
// whichever side of the interop it lands on.
const loaded = await import('../../../packages/desktop/src/process/agent/kyrn/product.ts');
const initializeKyrn: typeof import('../../../packages/desktop/src/process/agent/kyrn/product.ts').initializeKyrn =
  loaded.initializeKyrn ?? (loaded as { default?: { initializeKyrn?: unknown } }).default?.initializeKyrn;

const base = process.env.CHECK_BASE as string;
const command = process.env.CHECK_COMMAND as string;

// Same contract as the app's httpRequest: unwrap `data`, throw on a non-2xx answer or `success: false`.
async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(new URL(path, base), {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(120000),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status} ${raw.slice(0, 200)}`);
  const parsed = raw ? JSON.parse(raw) : undefined;
  if (parsed && parsed.success === false) throw new Error(`${method} ${path}: rejected`);
  return (parsed?.data ?? parsed) as T;
}
const request: BackendRequest = send;

const catalog = await initializeKyrn(request, command);
console.log(
  JSON.stringify({ agentId: catalog.agentId, assistants: catalog.assistants.map((a) => ({ id: a.id, name: a.name })) })
);
