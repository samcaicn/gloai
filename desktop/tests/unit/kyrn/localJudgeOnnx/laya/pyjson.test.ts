// Vendored from mizchi/laya-mlx, web/packages/laya-web/test/pyjson.test.ts at dc3aa6b (Apache-2.0).
// Tests that need the real tokenizer run only with LAYA_TOKENIZER_DIR=<model folder>/tokenizer.

import { describe, expect, it } from 'vitest';

import { formatPyNumber, pyJson } from '@process/services/localJudgeOnnx/laya/pyjson.ts';

describe('pyJson', () => {
  it("uses Python's default separators", () => {
    expect(pyJson({ task: 'choose department', n: [1, 2] })).toBe('{"task": "choose department", "n": [1, 2]}');
  });
  it('keeps non-ASCII when ensureAscii is false and escapes it otherwise', () => {
    expect(pyJson({ message: '請求書' }, { ensureAscii: false })).toBe('{"message": "請求書"}');
    expect(pyJson({ message: '請求書' })).toBe('{"message": "\\u8acb\\u6c42\\u66f8"}');
    expect(pyJson('😀')).toBe('"\\ud83d\\ude00"');
  });
  it('serializes primitives like Python', () => {
    expect(pyJson(true)).toBe('true');
    expect(pyJson(null)).toBe('null');
    expect(pyJson(1.5)).toBe('1.5');
    expect(pyJson('a"b\n')).toBe('"a\\"b\\n"');
  });
  // Python: json.dumps("\x7f") -> '"\\u007f"'. ensure_ascii escapes everything outside
  // the printable ASCII range \x20-\x7e, so DEL (0x7f) must be escaped too, not just >= 0x80.
  it('escapes DEL (0x7f) under ensureAscii, not just non-ASCII', () => {
    expect(pyJson('\x7f')).toBe('"\\u007f"');
  });
  // Ground truth (repo root): python3 -c 'import json; print(json.dumps([{"a": 1}],
  // separators=(", ", ": "))); print(json.dumps({})); print(json.dumps([]));
  // print(json.dumps(-5)); print(json.dumps({"請": 1}, separators=(", ", ": ")))'
  it('serializes nested containers, empty containers, negative ints and non-ASCII keys', () => {
    expect(pyJson([{ a: 1 }])).toBe('[{"a": 1}]');
    expect(pyJson({})).toBe('{}');
    expect(pyJson([])).toBe('[]');
    expect(pyJson(-5)).toBe('-5');
    expect(pyJson({ 請: 1 })).toBe('{"\\u8acb": 1}');
  });
});

describe('formatPyNumber', () => {
  // Ground truth (repo root): python3 -c 'import json
  // for v in [1.5, 0.00001, 1.5e-07, 123456789.123, 0.1, 1e-4, -2.5, 123.456]:
  //     print(json.dumps(v))'
  // -> 1.5 / 1e-05 / 1.5e-07 / 123456789.123 / 0.1 / 0.0001 / -2.5 / 123.456
  it.each([
    [1.5, '1.5'],
    [0.00001, '1e-05'],
    [1.5e-7, '1.5e-07'],
    [123456789.123, '123456789.123'],
    [0.1, '0.1'],
    [1e-4, '0.0001'],
    [-2.5, '-2.5'],
    [123.456, '123.456'],
  ])('formats %j as %s', (value, expected) => {
    expect(formatPyNumber(value)).toBe(expected);
  });
  it("prints integer-valued numbers without a decimal point (documented gap vs Python's 1.0)", () => {
    expect(formatPyNumber(1)).toBe('1');
    expect(formatPyNumber(-0)).toBe('0');
  });
});
