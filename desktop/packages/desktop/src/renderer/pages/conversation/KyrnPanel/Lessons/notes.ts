import type { TFunction } from 'i18next';
import type { StoredLesson } from '@/common/kyrn/lessons';
import type { Activity } from '@/common/kyrn/types';
import { record, str } from '../activity';
import { memoryEvents } from './model';

const KEY = 'common.kyrn.lessonsView.notes';

/** The notes the tab shows at most: the latest. */
export const NOTES_SHOWN = 3;

export type LessonNote = { id: string; at: number; text: string };

const ids = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string' && id !== '') : [];

type Lookup = { named: (id: string) => string; lesson: (id: string) => StoredLesson | undefined };

/** Why a lesson was retired, in a line that names it. */
function retiredText(t: TFunction, payload: Record<string, unknown>, find: Lookup): string | undefined {
  const id = str(payload.id);
  if (!id) return undefined;
  const lesson = find.named(id);
  switch (payload.reason) {
    case 'unused': {
      // The rule retires a lesson recalled that often and never followed; the file still counts its recalls.
      const count = find.lesson(id)?.uses.recalled ?? 0;
      return count > 0 ? t(`${KEY}.retiredUnused`, { lesson, count }) : t(`${KEY}.retired`, { lesson });
    }
    case 'contradicted':
      return t(`${KEY}.retiredContradicted`, { lesson });
    case 'forgotten':
      return t(`${KEY}.retiredForgotten`, { lesson });
    default:
      return t(`${KEY}.retired`, { lesson });
  }
}

/**
 * What became of a new lesson held against a kept one, naming the lesson that stands. `same`: it was that one, so it
 * is merged into it. `refines`: the new one replaced the old. `contradicts`: when the user's newer word retired the old
 * lesson, its retirement says so and this adds nothing; otherwise the new lesson was not kept, and the one it
 * contradicts stands.
 */
function mergedText(
  t: TFunction,
  payload: Record<string, unknown>,
  find: Lookup,
  retired: ReadonlySet<string>
): string | undefined {
  const into = str(payload.into);
  if (!into) return undefined;
  if (payload.how === 'same') return t(`${KEY}.mergedSame`, { lesson: find.named(into) });
  if (payload.how === 'refines') return t(`${KEY}.mergedRefines`, { lesson: find.named(into) });
  if (payload.how === 'contradicts' && !retired.has(str(payload.id)))
    return t(`${KEY}.mergedContradicts`, { lesson: find.named(into) });
  return undefined;
}

/**
 * One quiet line for each thing the lessons did in this session, newest first: brought into a turn or into a
 * sub-agent's brief, followed, retired, merged. A stored lesson needs none: it is in the list. A lesson is named by its
 * words, as read from the file; one that is not there by the start of its id, as `/lessons` shows it. A recall recorded
 * from the message that carried it (a harness that did not say which lessons) names none and gets no note.
 */
export function lessonNotes(
  t: TFunction,
  events: readonly Activity[],
  lessons: readonly StoredLesson[],
  limit: number = NOTES_SHOWN
): LessonNote[] {
  const byId = new Map(lessons.map((lesson) => [lesson.id, lesson]));
  const find: Lookup = {
    named: (id) => byId.get(id)?.lesson ?? id.slice(0, 8),
    lesson: (id) => byId.get(id),
  };
  const all = memoryEvents(events);
  const retired = new Set(all.filter((event) => event.kind === 'memory.retired').map((event) => str(event.payload.id)));
  const notes: LessonNote[] = [];
  for (let index = all.length - 1; index >= 0 && notes.length < limit; index--) {
    const event = all[index];
    const payload = record(event.payload);
    let text: string | undefined;
    if (event.kind === 'memory.recalled') {
      const count = ids(payload.ids).length;
      const task = str(payload.task).trim();
      if (count) text = task ? t(`${KEY}.briefed`, { count, task }) : t(`${KEY}.recalled`, { count });
    } else if (event.kind === 'memory.applied') {
      const count = ids(payload.ids).length;
      if (count) text = t(`${KEY}.applied`, { count });
    } else if (event.kind === 'memory.retired') text = retiredText(t, payload, find);
    else if (event.kind === 'memory.merged') text = mergedText(t, payload, find, retired);
    if (text) notes.push({ id: event.id, at: event.at, text });
  }
  return notes;
}
