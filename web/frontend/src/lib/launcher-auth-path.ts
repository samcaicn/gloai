/** Normalize URL pathname for comparisons (trailing slashes, empty). */
export function normalizePathname(p: string): string {
  const t = p.replace(/\/+$/, "")
  return t === "" ? "/" : t
}

export function isLauncherSetupPathname(pathname: string): boolean {
  return normalizePathname(pathname) === "/launcher-setup"
}

/** True for the launcher bind (setup) page — the only launcher auth page. */
export function isLauncherAuthPathname(pathname: string): boolean {
  return isLauncherSetupPathname(pathname)
}
