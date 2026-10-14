// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/questions.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { describe, expect, it } from 'vitest';

import { renderOptions, toInternal } from '@process/services/localJudgeOnnx/laya/questions.ts';
import { fixture } from './fixtures.ts';

describe('toInternal', () => {
  it('turns choice label lists into null-valued maps', () => {
    expect(toInternal({ type: 'choice', instructions: 'Pick', criteria: ['a', 'b'] })).toEqual({
      t: 'choice',
      ins: 'Pick',
      crit: { a: null, b: null },
    });
  });
  it('serializes non-string instructions with Python json.dumps (ASCII escaped)', () => {
    expect(toInternal({ type: 'score', instructions: { k: '請' }, criteria: ['x'] }).ins).toBe('{"k": "\\u8acb"}');
  });
  it.each([
    [{ type: 'choice', instructions: 'x', criteria: ['a', 'a'] }, /unique/],
    [{ type: 'choice', instructions: 'x', criteria: [] }, /nonempty/],
    [{ type: 'choice', instructions: 'x', criteria: [1] }, /strings/],
    [{ type: 'score', instructions: 'x', criteria: [] }, /nonempty/],
    [{ type: 'noul', instructions: 'x', criteria: ['a'] }, /dictionary/],
    [{ type: 'other', instructions: 'x' }, /Unknown question type/],
    [{ type: 'noul' }, /missing instructions/],
    // "toString" is a real own-prototype-chain property of plain objects; `in` would
    // wrongly accept it (or any other Object.prototype member) as a question type.
    [{ type: 'toString', instructions: 'x' }, /Unknown question type/],
  ])('rejects %j', (question, message) => {
    expect(() => toInternal(question)).toThrow(message);
  });
  it('validates criteria before serializing instructions', () => {
    expect(() => toInternal({ type: 'choice', instructions: NaN, criteria: [] })).toThrow(
      /nonempty dictionary or list/
    );
  });
  it('reprs the unknown type like Python (quoted string, or None when missing)', () => {
    expect(() => toInternal({ type: 'other', instructions: 'x' })).toThrow(
      "Unknown question type 'other'; expected choice, score, or noul"
    );
    expect(() => toInternal({ instructions: 'x' })).toThrow(
      'Unknown question type None; expected choice, score, or noul'
    );
  });
  it('accepts a choice criteria dict whose keys are all integer-like', () => {
    expect(toInternal({ type: 'choice', instructions: 'x', criteria: { '0': 'a', '1': 'b' } })).toEqual({
      t: 'choice',
      ins: 'x',
      crit: { '0': 'a', '1': 'b' },
    });
  });
  it('rejects a choice criteria dict mixing integer-like and other keys', () => {
    expect(() => toInternal({ type: 'choice', instructions: 'x', criteria: { '0': 'a', other: 'b' } })).toThrow(
      /numeric labels must use the list form/
    );
  });
});

describe('renderOptions', () => {
  it('renders choice, score and noul like Python', () => {
    expect(
      renderOptions({
        t: 'choice',
        ins: '',
        crit: { billing: 'refunds', other: null, zero: 0, empty: '' },
      })
    ).toEqual(['billing: refunds', 'other', 'zero: 0', 'empty']);
    expect(renderOptions({ t: 'score', ins: '', crit: ['low', { level: 'high' }] })).toEqual([
      'level 0: low',
      'level 1: {"level": "high"}',
    ]);
    expect(renderOptions({ t: 'noul', ins: '', crit: null })).toEqual([
      'false: no, the statement does not hold',
      'true: yes, the statement holds',
    ]);
    expect(renderOptions({ t: 'noul', ins: '', crit: { true: { reason: 'money back' } } })).toEqual([
      'false: no, the statement does not hold',
      'true: {"reason": "money back"}',
    ]);
  });
  it('produces the same internal question and option texts as Python for every fixture question', () => {
    for (const c of fixture.cases) {
      const questions = Object.values(c.questions);
      questions.forEach((q, i) => {
        const internal = toInternal(q);
        expect(internal, `${c.name}#${i}`).toEqual(c.internal[i]);
        expect(renderOptions(internal), `${c.name}#${i}`).toEqual(c.options[i]);
      });
    }
  });
});
