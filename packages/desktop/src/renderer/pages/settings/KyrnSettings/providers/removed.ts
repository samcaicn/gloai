/**
 * Providers removed on this screen since the app started. What mu reports it can use is a snapshot of its last
 * connection, so a provider removed here would otherwise come back as "other built-in, usable" (and as a startup
 * model) until mu connects again.
 */
const removed = new Set<string>();

export const markRemoved = (id: string): void => {
  if (id) removed.add(id);
};

/** The removed ids that are not there again: an id added back (or the removal discarded) is shown as usual. */
export function hiddenIds(present: Iterable<string>): ReadonlySet<string> {
  const here = new Set(present);
  return new Set([...removed].filter((id) => !here.has(id)));
}
