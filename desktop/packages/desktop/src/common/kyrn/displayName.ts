/**
 * The app's own copy names AiBuddy: the locale files and the source say AiBuddy, the local service and the built-in agent.
 * What still names the upstream products arrives at run time, from the bundled backend (AionCore): an error message,
 * an assistant's or an agent's name, a skill description. `showAsMu` shows those names as AiBuddy. Both i18next instances
 * (renderer and main process) run every resolved string, default values included, through it, and so does every
 * place that shows backend text as it came (see `withTextsAsMu`).
 *
 * The names: AionUi (also AionUI), AionCore, Aionrs and Aion CLI (also AionCLI).
 *
 * Left alone on purpose: anything that is part of a URL, a path, a file name, an identifier or an e-mail address, the
 * lower-case ids (`aionrs`, `aionui-browser`), the upper-case codes (`AIONUI_…`) and the dev data folder `AionUi-Dev`,
 * which is a real folder. A hyphen after a name is a file name (`AionUi-update-1.zip`) unless a capitalised word
 * follows it: a German compound noun such as `AionUi-Installation` or `AionCore-Backend` is the name in a sentence.
 */

/** No lookbehind: the WebUI is opened in browsers that fail to parse one, which would break the whole bundle. */
const UPSTREAM_NAME = /(^|[^\w/.@:-])(?:AionU[iI]|AionCore|Aionrs|Aion ?CLI)(?![\w/@]|-Dev\b|-(?!\p{Lu}\p{Ll})|\.\w)/gu;

export const MU_DISPLAY_NAME = 'AiBuddy';

export function showAsMu(text: string): string {
  return text.includes('Aion') ? text.replace(UPSTREAM_NAME, `$1${MU_DISPLAY_NAME}`) : text;
}

/**
 * A backend record with the named display texts shown as mu: a string field, or a map of localized strings
 * (`name_i18n`), or a list of strings (`prompts`). Identifiers and every other field stay as they came.
 */
export function withTextsAsMu<T extends object>(record: T, fields: readonly (keyof T)[]): T {
  const next = { ...record };
  for (const field of fields) {
    const value: unknown = record[field];
    if (typeof value === 'string') {
      next[field] = showAsMu(value) as T[keyof T];
    } else if (Array.isArray(value)) {
      next[field] = value.map((item: unknown) => (typeof item === 'string' ? showAsMu(item) : item)) as T[keyof T];
    } else if (value && typeof value === 'object') {
      next[field] = Object.fromEntries(
        Object.entries(value).map(([key, item]: [string, unknown]) => [
          key,
          typeof item === 'string'
            ? showAsMu(item)
            : Array.isArray(item)
              ? item.map((entry: unknown) => (typeof entry === 'string' ? showAsMu(entry) : entry))
              : item,
        ])
      ) as T[keyof T];
    }
  }
  return next;
}

/** i18next post-processor. Register with `.use(muNamePostProcessor)` and `postProcess: [MU_NAME_POST_PROCESSOR]`. */
export const MU_NAME_POST_PROCESSOR = 'muName';

export const muNamePostProcessor = {
  type: 'postProcessor' as const,
  name: MU_NAME_POST_PROCESSOR,
  process: (value: string): string => (typeof value === 'string' ? showAsMu(value) : value),
};
