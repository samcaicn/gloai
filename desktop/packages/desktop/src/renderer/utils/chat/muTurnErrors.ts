/**
 * @license
 * Copyright 2026 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The errors the mu bridge raises into a conversation. The bridge runs as its own process without i18n and what it
 * raises is stored with the conversation, so it words them in fixed English (`MU_TURN_ERRORS` in
 * `process/agent/kyrn/KyrnAgent.ts`, plus the process errors of `piRpc.ts`) and the desktop recognises that text
 * here, wherever AionCore put it, to show a headline in the reader's language. The original text stays available as
 * a detail line.
 */
const MU_TURN_ERROR_TEXTS = [
  // Longer texts first: "AiBuddy process exited during the turn" also contains "AiBuddy process exited".
  // Each entry matches the current wording (AiBuddy, since the brand rename) AND the legacy wording (mu),
  // because conversations stored before the rename keep the old text and must still get a localised headline.
  ['processExited', 'AiBuddy process exited during the turn'],
  ['processExited', 'mu process exited during the turn'],
  ['noModel', 'AiBuddy has no model to answer with'],
  ['noModel', 'mu has no model to answer with'],
  // pi's own words for it, as conversations from before the bridge named it stored them. Before `modelFailed`: a
  // request that failed for want of a key is the same trouble, and has the same way out.
  ['noModel', 'No API key found for'],
  ['noModel', 'No models available'],
  ['noModel', 'No model selected'],
  ['modelFailed', 'Model request failed'],
  ['turnRunning', 'An AiBuddy turn is already running'],
  ['turnRunning', 'A AiBuddy turn is already running'],
  ['turnRunning', 'A mu turn is already running'],
  ['busyConfig', 'Wait for the current turn before changing configuration'],
  ['unsupportedValue', 'Unsupported configuration value'],
  ['permissionsNotSwitched', 'AiBuddy did not switch permissions'],
  ['permissionsNotSwitched', 'mu did not switch permissions'],
  ['imageUnsupported', 'Unsupported image format or size'],
  ['contentUnsupported', 'This AiBuddy adapter accepts text, images and text resources'],
  ['contentUnsupported', 'This mu adapter accepts text, images and text resources'],
  ['wrongProject', 'Session belongs to a different project'],
  ['notPersisted', 'AiBuddy did not persist the session'],
  ['notPersisted', 'mu did not persist the session'],
  ['startFailed', 'AiBuddy failed to start'],
  ['startFailed', 'mu failed to start'],
  ['timeout', 'AiBuddy control command timed out'],
  ['timeout', 'mu control command timed out'],
  ['processClosed', 'AiBuddy process exited'],
  ['processClosed', 'mu process exited'],
  ['processClosed', 'AiBuddy process is closed'],
  ['processClosed', 'mu process is closed'],
] as const;

export type MuTurnErrorCode = (typeof MU_TURN_ERROR_TEXTS)[number][0];

/** The mu bridge error found in these texts (the error's message, detail or tip content), if any. */
export function findMuTurnError(texts: ReadonlyArray<string | undefined | null>): MuTurnErrorCode | undefined {
  for (const [code, english] of MU_TURN_ERROR_TEXTS) {
    if (texts.some((value) => typeof value === 'string' && value.includes(english))) return code;
  }
  return undefined;
}

/** The i18n key of a mu bridge error's headline. */
export const muTurnErrorKey = (code: MuTurnErrorCode): string => `mu.turnErrors.${code}`;

/**
 * pi's words about a missing model without what is meant for its terminal: the advice to run `/login` and the path of
 * a harness doc, neither of which a desktop user can act on. What is left names the provider, if pi named one.
 */
export function noModelDetail(text: string): string {
  return text
    .replace(/^Agent internal error \(code -?\d+\):\s*/, '')
    .replace(/^(?:AiBuddy|mu) has no model to answer with:?\s*/, '')
    .split(/\n\s*\n/)[0]
    .replace(/\s*Use \/login[\s\S]*$/, '')
    .replace(/\s*See:?\s+\S+\.md\S*/g, '')
    .trim();
}
