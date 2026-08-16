// Track F — interactive prompt API.
//
// Mirrors `src-tauri/src/pc_automation/skill/types.rs` (camelCase
// wire shape) and bridges the front-end to the two Tauri commands
// (`automation_answer_prompt` / `automation_cancel_prompt`) plus the
// `automation:ask_user` event the executor emits when a `SkillStep`
// carries an `InteractionPrompt`.
//
//   * `onAskUser(handler)`   — subscribe to `automation:ask_user`;
//                               the handler renders the modal.
//   * `answerPrompt(...)`    — deliver the user's answer back to the
//                               blocked executor via the
//                               `automation_answer_prompt` command.
//   * `cancelPrompt(...)`    — signal dismissal via
//                               `automation_cancel_prompt`; the
//                               executor then falls back to
//                               `default_value`.
//
// `invoke` is the tupai wrapper (`./invoke`) that no-ops in non-Tauri
// runtimes (pnpm dev / web preview) so this module is safe to import
// from anywhere. `@tauri-apps/api/event` is already a dependency
// (used by 30+ files across the app).

import { invoke } from './invoke';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';

export interface PromptChoice {
  id: string;
  label: string;
}

export type PromptInputType = 'text' | 'choice' | 'multichoice' | 'confirm';

export interface InteractionPrompt {
  promptId: string;
  question: string;
  inputType: PromptInputType;
  choices: PromptChoice[];
  bindToVar: string;
  defaultValue?: unknown;
  timeoutMs: number;
}

export interface AskUserPayload {
  correlationId: string;
  skillId: string;
  stepId: string;
  prompt: InteractionPrompt;
}

export interface PromptAnswer {
  correlationId: string;
  value: unknown;
  cancelled: boolean;
}

/**
 * Deliver the user's answer to a pending `automation:ask_user`
 * prompt. `value` is the typed string for `text`, the `choice.id`
 * for `choice`, an array of selected choice ids for `multichoice`,
 * and `"true"` / `"false"` for `confirm`.
 */
export async function answerPrompt(
  correlationId: string,
  value: unknown,
  cancelled = false,
): Promise<void> {
  return invoke<void>('automation_answer_prompt', {
    answer: { correlationId, value, cancelled },
  });
}

/** Signal that the user dismissed the prompt without answering. */
export async function cancelPrompt(correlationId: string): Promise<void> {
  return invoke<void>('automation_cancel_prompt', { correlationId });
}

/**
 * Subscribe to `automation:ask_user` events. Returns an `UnlistenFn`
 * that the caller should invoke on teardown. The handler receives
 * the decoded `AskUserPayload` (the event's `payload` field).
 */
export function onAskUser(handler: (payload: AskUserPayload) => void): Promise<UnlistenFn> {
  return listen<AskUserPayload>('automation:ask_user', (e) => handler(e.payload));
}
