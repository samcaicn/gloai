import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { KyrnError } from '../../../../common/kyrn/errors';
import { asRecord, type JsonRecord } from '../piRpc';

/** The file's text, or '' when it does not exist. Any other failure names the file (`unreadable`). */
export function readOptional(path: string): string {
  try {
    return readFileSync(path, 'utf8');
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return '';
    throw new KyrnError('unreadable', error instanceof Error ? error.message : String(error), { file: path });
  }
}

/**
 * Writes through a temporary file in the same directory, so a reader never sees half a file. Owner-only.
 * A failure names the file (`unwritable`), with the OS words as the message.
 */
export function atomic(path: string, value: string): void {
  try {
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    const temp = `${path}.${randomUUID()}.tmp`;
    writeFileSync(temp, value, { mode: 0o600 });
    renameSync(temp, path);
  } catch (error) {
    throw new KyrnError('unwritable', error instanceof Error ? error.message : String(error), { file: path });
  }
}

/** The JSON object in `raw`. Text that is not JSON names the file it came from (`invalidJson`). */
export function parseObject(raw: string, file = ''): JsonRecord {
  try {
    return asRecord(raw ? JSON.parse(raw.replace(/^\uFEFF/, '')) : {});
  } catch (error) {
    throw new KyrnError('invalidJson', error instanceof Error ? error.message : String(error), { file });
  }
}

/** Serialises with the indentation the file already uses (tabs or spaces), two spaces for a new file. */
export function serialise(value: unknown, previous: string): string {
  const indent = /^([ \t]+)"/m.exec(previous)?.[1] ?? '  ';
  return `${JSON.stringify(value, null, indent)}\n`;
}

/** pi reads models.json with `//` comments and trailing commas. The same two replacements as pi's `stripJsonComments`. */
export function stripJsonComments(input: string): string {
  return input
    .replace(/"(?:\\.|[^"\\])*"|\/\/[^\n]*/g, (match) => (match[0] === '"' ? match : ''))
    .replace(
      /"(?:\\.|[^"\\])*"|,(\s*[}\]])/g,
      (match, tail: string | undefined) => tail ?? (match[0] === '"' ? match : '')
    );
}

/** Whether `NAME=<something>` is in the text of a .env file, or in this process's environment. */
export function hasVariable(name: string, envText: string): boolean {
  return Boolean(process.env[name]) || new RegExp(`^${name}=.+$`, 'm').test(envText);
}

/** The value of `NAME=value` in the text of a .env file. Only used inside the main process. */
export function variableValue(name: string, envText: string): string {
  const line = envText.split('\n').find((entry) => entry.startsWith(`${name}=`));
  return line ? line.slice(name.length + 1).trim() : (process.env[name] ?? '');
}

/** The .env text with these variables set (a value) or removed (undefined). Other lines keep their order. */
export function withVariables(envText: string, changes: Map<string, string | undefined>): string {
  const kept = envText
    .split('\n')
    .filter((line) => line && ![...changes.keys()].some((name) => line.startsWith(`${name}=`)));
  for (const [name, value] of changes) if (value !== undefined) kept.push(`${name}=${value}`);
  return kept.length ? `${kept.join('\n')}\n` : '';
}
