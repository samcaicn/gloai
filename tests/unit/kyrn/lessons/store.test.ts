import { afterEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { StoredLesson } from '@/common/kyrn/lessons';
import { KyrnError } from '@/common/kyrn/errors';
import { appendLesson, lessonsPath, lessonsProject, LessonsStore, readLessonsFile } from '@/process/agent/kyrn/lessons';

const dirs: string[] = [];
const temp = (): string => {
  const dir = mkdtempSync(join(tmpdir(), 'mu-lessons-'));
  dirs.push(dir);
  return dir;
};
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

const lesson = (id: string, cwd: string | undefined, extra: Partial<StoredLesson> = {}): StoredLesson => ({
  id,
  kind: 'correction',
  trigger: `when ${id} comes up`,
  lesson: `Do ${id}.`,
  scope: cwd === undefined ? {} : { cwd },
  source: { origin: 'user' },
  status: 'active',
  uses: { recalled: 0, applied: 0 },
  created: '2026-09-22T08:00:00.000Z',
  updated: '2026-09-22T08:00:00.000Z',
  ...extra,
});

/** An agent directory with a lessons file holding these lines, written as given (the last one without a newline). */
function agentWith(lines: unknown[], ending = '\n'): { agentDir: string; path: string } {
  const agentDir = temp();
  const path = join(agentDir, 'mu', 'lessons.jsonl');
  mkdirSync(join(agentDir, 'mu'), { recursive: true });
  writeFileSync(path, `${lines.map((line) => JSON.stringify(line)).join('\n')}${ending}`);
  return { agentDir, path };
}

const project = { folder: '/work/app', places: ['/work/app'] };
const NOW = new Date('2026-09-23T04:00:00.000Z');

describe('where the lessons are', () => {
  it('is the agent directory’s mu/lessons.jsonl, unless mu.json names a file by its full path', () => {
    const agentDir = temp();
    expect(lessonsPath(agentDir)).toBe(join(agentDir, 'mu', 'lessons.jsonl'));
    writeFileSync(join(agentDir, 'mu.json'), JSON.stringify({ features: { memory: { path: '/data/lessons.jsonl' } } }));
    expect(lessonsPath(agentDir)).toBe('/data/lessons.jsonl');
    // A relative path is each session's own folder: no one file to show.
    writeFileSync(join(agentDir, 'mu.json'), JSON.stringify({ features: { memory: { path: 'lessons.jsonl' } } }));
    expect(lessonsPath(agentDir)).toBe(join(agentDir, 'mu', 'lessons.jsonl'));
    writeFileSync(join(agentDir, 'mu.json'), JSON.stringify({ features: { memory: false } }));
    expect(lessonsPath(agentDir)).toBe(join(agentDir, 'mu', 'lessons.jsonl'));
    writeFileSync(join(agentDir, 'mu.json'), '{ not json');
    expect(lessonsPath(agentDir)).toBe(join(agentDir, 'mu', 'lessons.jsonl'));
  });

  it('holds no lessons while the file does not exist', () => {
    expect(readLessonsFile(join(temp(), 'mu', 'lessons.jsonl'))).toEqual([]);
  });

  it('is the folder the conversation’s mu session runs in, else its workspace as given and as its real path', () => {
    const sessions = temp();
    const real = temp();
    const link = join(temp(), 'link');
    symlinkSync(real, link);
    const sessionId = '0f8fad5b-d9cb-469f-a165-70867728950e';
    writeFileSync(join(sessions, `${sessionId}.json`), JSON.stringify({ cwd: '/work/session', file: 'x.jsonl' }));

    expect(lessonsProject(sessions, sessionId, link)).toEqual({
      folder: '/work/session',
      places: ['/work/session', realpathSync(real), link],
    });
    // No session yet: the workspace, resolved.
    expect(lessonsProject(sessions, '', link)).toEqual({
      folder: realpathSync(real),
      places: [realpathSync(real), link],
    });
    // A session id that is not one is never used to build a path.
    expect(lessonsProject(sessions, '../../etc/passwd', '')).toEqual({ folder: '', places: [] });
    expect(lessonsProject(sessions, '1b671a64-40d5-491e-99b0-da01ff1f3341', '/gone')).toEqual({
      folder: '/gone',
      places: ['/gone'],
    });
  });
});

describe('writing a line', () => {
  it('starts a line of its own when the file’s last line has no newline, and only then', () => {
    const { path } = agentWith([lesson('a', '/work/app')], '');
    appendLesson(path, { id: 'a', status: 'retired', updated: NOW.toISOString() });
    appendLesson(path, { id: 'a', updated: NOW.toISOString() });
    const text = readFileSync(path, 'utf8');
    expect(text.split('\n')).toEqual([
      JSON.stringify(lesson('a', '/work/app')),
      JSON.stringify({ id: 'a', status: 'retired', updated: NOW.toISOString() }),
      JSON.stringify({ id: 'a', updated: NOW.toISOString() }),
      '',
    ]);
  });

  it('makes the folder and the file when there are none', () => {
    const path = join(temp(), 'mu', 'lessons.jsonl');
    appendLesson(path, { id: 'x', status: 'retired' });
    expect(readFileSync(path, 'utf8')).toBe('{"id":"x","status":"retired"}\n');
  });
});

describe('the lessons tab’s reads and changes', () => {
  it('shows the project’s lessons and those for everywhere, in any state, and nothing of another project', () => {
    const { agentDir } = agentWith([
      lesson('own', '/work/app'),
      lesson('everywhere', undefined),
      lesson('other', '/work/other'),
      lesson('old', '/work/app', { status: 'superseded' }),
    ]);
    const view = new LessonsStore(agentDir).view(project);
    expect(view.project).toBe('/work/app');
    expect(view.lessons.map((each) => each.id)).toEqual(['own', 'everywhere', 'old']);
    // Without a project folder only the lessons for everywhere apply.
    expect(new LessonsStore(agentDir).view({ folder: '', places: [] }).lessons.map((each) => each.id)).toEqual([
      'everywhere',
    ]);
  });

  it('edits by appending { id, lesson, updated }, one line, and leaves every earlier byte as it was', () => {
    const { agentDir, path } = agentWith([lesson('a', '/work/app'), lesson('b', undefined)], '');
    const before = readFileSync(path, 'utf8');
    const store = new LessonsStore(agentDir);

    const view = store.change(
      project,
      { conversationId: 'c', id: 'a', action: 'edit', lesson: '  Run ./test.sh\n first. ' },
      NOW
    );

    const after = readFileSync(path, 'utf8');
    expect(after.startsWith(before)).toBe(true);
    expect(after.slice(before.length)).toBe(
      `\n${JSON.stringify({ id: 'a', lesson: 'Run ./test.sh first.', updated: NOW.toISOString() })}\n`
    );
    expect(view.lessons.find((each) => each.id === 'a')).toMatchObject({
      lesson: 'Run ./test.sh first.',
      updated: NOW.toISOString(),
    });
    // The same words again write nothing.
    store.change(project, { conversationId: 'c', id: 'a', action: 'edit', lesson: 'Run ./test.sh first.' }, NOW);
    expect(readFileSync(path, 'utf8')).toBe(after);
  });

  it('retires by appending { id, status: "retired", updated }, a lesson for everywhere included', () => {
    const { agentDir, path } = agentWith([lesson('a', '/work/app'), lesson('b', undefined)]);
    const before = readFileSync(path, 'utf8');
    const view = new LessonsStore(agentDir).change(project, { conversationId: 'c', id: 'b', action: 'retire' }, NOW);
    expect(readFileSync(path, 'utf8')).toBe(
      `${before}${JSON.stringify({ id: 'b', status: 'retired', updated: NOW.toISOString() })}\n`
    );
    expect(view.lessons.map((each) => `${each.id}:${each.status}`)).toEqual(['a:active', 'b:retired']);
  });

  it('refuses a lesson that is not this project’s, one not in use, an empty text and a change it does not know', () => {
    const { agentDir, path } = agentWith([
      lesson('a', '/work/app'),
      lesson('other', '/work/other'),
      lesson('gone', '/work/app', { status: 'retired' }),
    ]);
    const before = readFileSync(path, 'utf8');
    const store = new LessonsStore(agentDir);
    const refused = (change: Parameters<LessonsStore['change']>[1]) => {
      expect(() => store.change(project, change, NOW)).toThrow(KyrnError);
    };
    refused({ conversationId: 'c', id: 'other', action: 'retire' });
    refused({ conversationId: 'c', id: 'missing', action: 'retire' });
    refused({ conversationId: 'c', id: 'gone', action: 'edit', lesson: 'Back again.' });
    refused({ conversationId: 'c', id: 'a', action: 'edit', lesson: ' \n ' });
    refused({ conversationId: 'c', id: '', action: 'retire' });
    refused({ conversationId: 'c', id: 'a', action: 'delete' } as unknown as Parameters<LessonsStore['change']>[1]);
    expect(readFileSync(path, 'utf8')).toBe(before);
  });
});
