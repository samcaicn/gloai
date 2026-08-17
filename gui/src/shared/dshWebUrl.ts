/** Parse the `dsh web:` readiness line. Only loopback http URLs are accepted. */
export function parseDshWebUrl(line: string): string | null {
  const trimmed = line.trim();
  if (!trimmed.startsWith("dsh web:")) return null;
  const rest = trimmed.slice("dsh web:".length).trim();
  const url = rest.split(/\s+/)[0];
  if (!url) return null;
  if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
    return url;
  }
  return null;
}
