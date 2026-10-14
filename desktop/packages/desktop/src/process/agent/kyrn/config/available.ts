import { UNOFFERED_PROVIDER_IDS, type AvailableModels } from '../../../../common/kyrn/models';
import { array, asRecord, text, type JsonRecord } from '../piRpc';

/** A stored snapshot can arrive as JSON text or as a value, bare or wrapped in an object under `key`. */
function rows(value: unknown, key: string): JsonRecord[] {
  let payload = value;
  if (typeof payload === 'string') {
    try {
      payload = JSON.parse(payload);
    } catch {
      return [];
    }
  }
  return array(Array.isArray(payload) ? payload : asRecord(payload)[key]).map(asRecord);
}
const options = (value: unknown): JsonRecord[] => rows(value, 'config_options');

/**
 * What mu last told the backend it can use, from the agent's record in `/api/agents/management`.
 * The adapter reports each model as `provider/model-id` with the provider as its description
 * (`KyrnAgent.options`), and the backend keeps the last handshake. So this is a snapshot: a login made in a
 * terminal shows up after the next connection, not at once. No credential file is opened for it.
 */
export function availableModels(agent: unknown): AvailableModels {
  const row = asRecord(agent);
  const handshake = asRecord(row.handshake);
  const found = [...options(row.config_options), ...options(handshake.config_options)];
  const select = (category: string, id: string) =>
    found.find((option) => option.category === category) ?? found.find((option) => option.id === id);
  const providers = new Map<string, { id: string; name: string }[]>();
  // Older snapshots carry the models on their own (`available_models`) instead of as a config option.
  const listed = array(select('model', 'model')?.options).map(asRecord);
  const models = listed.length
    ? listed
    : [...rows(row.available_models, 'available_models'), ...rows(handshake.available_models, 'available_models')];
  for (const option of models) {
    const value = text(option.value) || text(option.id);
    const slash = value.indexOf('/');
    if (slash <= 0) continue;
    const provider = text(option.description) || value.slice(0, slash);
    // A snapshot from before the adapter stopped offering it can still list it.
    if (UNOFFERED_PROVIDER_IDS.has(provider)) continue;
    const id = value.startsWith(`${provider}/`) ? value.slice(provider.length + 1) : value.slice(slash + 1);
    const entries = providers.get(provider) ?? [];
    if (!entries.some((model) => model.id === id))
      entries.push({ id, name: text(option.name) || text(option.label) || id });
    providers.set(provider, entries);
  }
  return {
    providers: [...providers].map(([id, entries]) => ({ id, models: entries })),
    thinkingLevels: array(select('thought_level', 'thinking')?.options)
      .map((option) => text(asRecord(option).value))
      .filter(Boolean),
  };
}
