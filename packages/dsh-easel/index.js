/**
 * Host half for the Easel desktop entry.
 *
 * The actual lifecycle (spawning `easel web` and opening the Easel window)
 * lives in the AiMarketing main process (src/main/easel-manager.ts), exposed
 * to the renderer through the `dshDesktop.openEasel` bridge. This package's
 * host entry is intentionally a no-op so the bundle loads under the existing
 * plugin protocol without duplicating the companion-process logic.
 */
export function apply() {}
