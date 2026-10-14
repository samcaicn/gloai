import type { AvailableCommand } from '@agentclientprotocol/sdk';
import { array, asRecord, text, type JsonRecord } from './piRpc.ts';

/** A name the send box can offer: one word, `skill:<name>` included. */
const NAME = /^[\w:.-]{1,64}$/;
/**
 * Not offered in the menu (still run when typed): `/clear` and `/new` start a fresh mu session under the same app
 * conversation, which the app would then reopen on the old one. The app has its own button for a new conversation.
 */
const NOT_OFFERED: ReadonlySet<string> = new Set(['clear', 'new']);

/**
 * mu's slash commands for the app's `/` menu, from pi's `get_commands`: mu's and its extensions' own first, then
 * prompt templates, then skills (`skill:<name>`), each once, with the first line of what it does. Picking one puts
 * `/<name>` in the message, and mu runs it when it is sent.
 */
export function slashCommands(result: JsonRecord): AvailableCommand[] {
  const seen = new Set<string>();
  const found: AvailableCommand[] = [];
  for (const command of array(result.commands).map(asRecord)) {
    const name = text(command.name);
    if (!NAME.test(name) || NOT_OFFERED.has(name) || seen.has(name)) continue;
    seen.add(name);
    found.push({ name, description: text(command.description).split('\n')[0].trim() || name });
  }
  return found;
}
