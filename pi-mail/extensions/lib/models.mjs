/**
 * Available-model discovery for the pi-mail daemon.
 *
 * Reads the models pi knows about for the CURRENT provider (settings.json →
 * defaultProvider) from two sources and merges them:
 *   - ~/.pi/agent/models-store.json — the runtime catalog (fetched from the
 *     provider); shape: { "<provider>": { models: [{ id, name, provider, … }] } }
 *   - ~/.pi/agent/models.json      — user-defined custom models; shape:
 *     { providers: { "<provider>": { models: [{ id, name?, … }] } } }
 *
 * Each returned entry is `{ id, name, provider }` where `id` is the FULL
 * model identifier `provider/slug` (e.g. "openrouter/deepseek/deepseek-v4-pro")
 * — the exact string pi's `--model` flag and set_model push use, so a task's
 * `model` field round-trips straight into dispatch. Depends on nothing else
 * in the daemon (like core.mjs) to keep the import graph acyclic.
 */

import fs from "node:fs";
import path from "node:path";
import os from "node:os";

export const AGENT_DIR = path.join(os.homedir(), ".pi", "agent");
const SETTINGS_FILE = path.join(AGENT_DIR, "settings.json");
const MODELS_STORE_FILE = path.join(AGENT_DIR, "models-store.json");
const MODELS_JSON_FILE = path.join(AGENT_DIR, "models.json");

function readJson(file) {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

/** The current default provider (settings.json → defaultProvider), else null. */
export function currentProvider() {
  const settings = readJson(SETTINGS_FILE);
  if (!settings) return null;
  const p = settings.defaultProvider;
  return typeof p === "string" && p.trim() ? p.trim() : null;
}

/**
 * Normalize a raw model entry from either source into `{ id, name, provider }`.
 * `id` becomes the full `provider/slug` identifier. `name` falls back to the
 * slug when the source omits it (models.json custom models often only have id).
 */
function normalize(entry, provider) {
  if (!entry || typeof entry !== "object") return null;
  const slug = String(entry.id ?? "").trim();
  if (!slug) return null;
  return {
    id: `${provider}/${slug}`,
    name: String(entry.name ?? slug),
    provider,
  };
}

/**
 * Available models for the current provider, deduped by full id, preserving
 * source order. Falls back to every provider's models when no default provider
 * is configured (so the endpoint still returns something useful).
 */
export function availableModels() {
  const provider = currentProvider();
  const store = readJson(MODELS_STORE_FILE);
  const custom = readJson(MODELS_JSON_FILE);
  const out = [];
  const seen = new Set();

  const push = (models, prov) => {
    if (!Array.isArray(models)) return;
    for (const m of models) {
      const norm = normalize(m, prov);
      if (!norm || seen.has(norm.id)) continue;
      seen.add(norm.id);
      out.push(norm);
    }
  };

  const providerList = provider ? [provider] : [];
  if (!provider && store) providerList.push(...Object.keys(store));
  if (!provider && custom?.providers) providerList.push(...Object.keys(custom.providers));

  for (const prov of [...new Set(providerList)]) {
    // Custom models.json entries (user overrides) first, then the runtime
    // store catalog — the store is the fuller/fresher list for built-ins.
    if (custom?.providers?.[prov]) push(custom.providers[prov].models, prov);
    if (store?.[prov]) push(store[prov].models, prov);
  }

  return out;
}
