import { describe, expect, it } from 'vitest';
import { flatLesson, foldLessons, inScope, LESSON_CHARS, parseLessons, type StoredLesson } from '@/common/kyrn/lessons';

/** A whole lesson as the harness writes it now. */
const whole = (id: string, extra: Partial<StoredLesson> = {}): StoredLesson => ({
  id,
  kind: 'workaround',
  trigger: `when ${id} comes up`,
  lesson: `Do ${id}.`,
  scope: { cwd: '/work/app' },
  source: { origin: 'outcome', session: 'session-1', turn: 3 },
  status: 'active',
  uses: { recalled: 0, applied: 0 },
  created: '2026-09-22T08:00:00.000Z',
  updated: '2026-09-22T08:00:00.000Z',
  ...extra,
});

describe('the lessons file, folded by id', () => {
  it('reads first-version and whole lines, folds changes over them, skips what is broken and counts an open last line', () => {
    const text = [
      // The first version: no kind, no status, the project as `cwd`.
      JSON.stringify({
        id: 'v1',
        trigger: 'running the tests',
        lesson: 'Use ./test.sh, not npm test.',
        cwd: '/work/app',
        created: '2026-09-20T00:00:00.000Z',
      }),
      JSON.stringify(whole('v2')),
      '{"id": "v2", "status": "retir',
      JSON.stringify({ id: 'v2', status: 'retired', updated: '2026-09-23T01:00:00.000Z' }),
      '',
      JSON.stringify({ id: 'v1', uses: { recalled: 4, applied: 2, lastRecalled: '2026-09-23T00:00:00.000Z' } }),
      // The last line has no newline yet: it still counts.
      JSON.stringify({
        id: 'v1',
        lesson: 'Run ./test.sh from the repository root.',
        updated: '2026-09-23T02:00:00.000Z',
      }),
    ].join('\n');

    const lessons = parseLessons(text);

    expect(lessons.map((lesson) => lesson.id)).toEqual(['v1', 'v2']);
    expect(lessons[0]).toEqual({
      id: 'v1',
      kind: 'correction',
      trigger: 'running the tests',
      lesson: 'Run ./test.sh from the repository root.',
      scope: { cwd: '/work/app' },
      source: { origin: 'user' },
      status: 'active',
      uses: { recalled: 4, applied: 2, lastRecalled: '2026-09-23T00:00:00.000Z' },
      created: '2026-09-20T00:00:00.000Z',
      updated: '2026-09-23T02:00:00.000Z',
    });
    expect(lessons[1]).toEqual({ ...whole('v2'), status: 'retired', updated: '2026-09-23T01:00:00.000Z' });
  });

  it('keeps each lesson where its id first appeared, whatever changed later', () => {
    const lessons = foldLessons([
      whole('a'),
      whole('b'),
      { id: 'a', uses: { recalled: 1, applied: 1 } },
      whole('c'),
      { id: 'a', status: 'superseded' },
    ]);
    expect(lessons.map((lesson) => `${lesson.id}:${lesson.status}`)).toEqual(['a:superseded', 'b:active', 'c:active']);
  });

  it('drops a malformed field without its line, and a tombstone or a blank lesson brings nothing back', () => {
    const lessons = foldLessons([
      whole('a', { uses: { recalled: 2, applied: 1 } }),
      // `applied` is no count: the uses stay as they were, the kind on the same line still counts.
      { id: 'a', uses: { recalled: 9, applied: 'many' }, kind: 'fact' },
      { id: 'a', status: 'nonsense', source: { origin: 'model' } },
      { id: 'ghost', status: 'retired' },
      { ...whole('blank'), lesson: '  ' },
      'not an object',
      null,
      { lesson: 'no id' },
    ]);
    expect(lessons).toHaveLength(1);
    expect(lessons[0]).toMatchObject({
      id: 'a',
      kind: 'fact',
      status: 'active',
      source: { origin: 'model' },
      uses: { recalled: 2, applied: 1 },
    });
  });

  it('reads a scope of its own over the first version’s cwd, and an empty scope as everywhere', () => {
    const [scoped, everywhere, stamped] = foldLessons([
      { id: 's', trigger: 't', lesson: 'l', cwd: '/old', scope: { cwd: '/new' } },
      { id: 'e', trigger: 't', lesson: 'l', cwd: '/old', scope: {} },
      { id: 'u', trigger: 't', lesson: 'l', updated: '2026-09-23T00:00:00.000Z' },
    ]);
    expect(scoped.scope).toEqual({ cwd: '/new' });
    expect(everywhere.scope).toEqual({});
    // Only `updated` known: it is when the lesson was made, too.
    expect(stamped.created).toBe('2026-09-23T00:00:00.000Z');
  });

  it('applies a project’s own lessons and those for everywhere, under any spelling of the project', () => {
    const [own, everywhere, other] = foldLessons([
      whole('own', { scope: { cwd: '/private/var/app' } }),
      whole('everywhere', { scope: {} }),
      whole('other', { scope: { cwd: '/work/other' } }),
    ]);
    const places = ['/private/var/app', '/var/app'];
    expect([own, everywhere, other].map((lesson) => inScope(lesson, places))).toEqual([true, true, false]);
    expect(inScope(own, [])).toBe(false);
    expect(inScope(everywhere, [])).toBe(true);
  });

  it('keeps a lesson to one line of at most the harness’s length', () => {
    expect(flatLesson('  Use\n./test.sh \t now ')).toBe('Use ./test.sh now');
    const long = flatLesson('x'.repeat(LESSON_CHARS + 20));
    expect(long).toHaveLength(LESSON_CHARS);
    expect(long.endsWith('…')).toBe(true);
    expect(flatLesson(' \n ')).toBe('');
  });
});
