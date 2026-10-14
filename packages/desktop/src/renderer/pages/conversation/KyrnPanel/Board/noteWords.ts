import type { BoardNote } from './board';

/**
 * A fixed line of the board's account in the reader's language. The harness writes most lines from fixed sentences
 * (`by: 'rules'`), in Chinese or English only, picked by what the person typed rather than by the app's language; each
 * names its sentence (`code`) and what fills it (`params`), so it is worded again here. A line whose sentence this
 * build does not know, or whose params do not fit it, keeps the harness's sentence; a line the board's model wrote is
 * its own words and stays. The contract is the harness's `board.note`.
 */

type Translate = (key: string, options?: Record<string, unknown>) => string;
/** Whether the app has a wording for a key. */
type Has = (key: string) => boolean;

const KEY = 'common.kyrn.boardView.notes';

/** Each fixed sentence and what fills it. */
export const NOTE_SENTENCES: ReadonlyMap<string, readonly string[]> = new Map([
  ['looked', ['count']],
  ['changed_file', ['file']],
  ['wrote_file', ['file']],
  ['ran_command', ['command']],
  ['command_failed', ['command']],
  ['check_passed', ['command']],
  ['check_failed', ['command']],
  ['item_done', ['item']],
  ['item_added', ['item']],
  ['helpers_sent', ['count', 'titles']],
  ['helpers_back', ['count']],
  ['permission_allowed', ['summary']],
  ['permission_denied', ['summary']],
  ['goal_round', ['round', 'reason']],
  ['goal_met', ['text']],
  ['goal_paused', ['reason']],
  ['trouble_loop', ['detail']],
  ['trouble', ['detail']],
  ['did', ['tool', 'what']],
  ['said_quote', ['text']],
  ['ended', []],
  ['waiting_reply', []],
]);

/** Counts, which also pick the plural; everything else is text. */
const NUMBERS: ReadonlySet<string> = new Set(['count', 'round']);
/** Text that arrives already formatted, and empty when there is none (": the tests fail" or ''). */
const MAY_BE_EMPTY: ReadonlySet<string> = new Set(['reason']);

const fits = (name: string, value: string | number | undefined) =>
  NUMBERS.has(name) ? typeof value === 'number' : typeof value === 'string' && (value !== '' || MAY_BE_EMPTY.has(name));

/** The line in the app's language, or undefined when it keeps the harness's (or the model's) sentence. */
export function noteWords(note: BoardNote, t: Translate, has: Has): string | undefined {
  if (note.by !== 'rules' || !note.code) return undefined;
  const names = NOTE_SENTENCES.get(note.code);
  const key = `${KEY}.${note.code}`;
  if (!names || !has(key)) return undefined;
  // Only what the sentence names is passed on: nothing else in the params reaches the translator's options.
  const values: Record<string, string | number> = {};
  for (const name of names) {
    const value = note.params?.[name];
    if (!fits(name, value)) return undefined;
    values[name] = value as string | number;
  }
  return t(key, values);
}
