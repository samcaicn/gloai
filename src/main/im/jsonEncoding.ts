/**
 * JSON encoding helpers for IM gateways.
 * Keep request payload ASCII-only (`\uXXXX`) to avoid platform-dependent charset issues.
 */

export const JSON_UTF8_CONTENT_TYPE = 'application/json; charset=utf-8';

/**
 * Stringify JSON and escape every non-ASCII code unit to `\uXXXX`.
 */
export function stringifyAsciiJson(value: unknown): string {
  return JSON.stringify(value).replace(/[^\x00-\x7F]/g, (char) => {
    return `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`;
  });
}

/**
 * Build UTF-8 JSON request body with ASCII-only content.
 */
export function createUtf8JsonBody(value: unknown): Buffer {
  return Buffer.from(stringifyAsciiJson(value), 'utf8');
}
