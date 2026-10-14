import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { isThinkingLevel, thinkingLevelLabel } from '@/renderer/utils/model/thinkingLevel';

const t = ((key: string) => `<${key}>`) as unknown as TFunction;

describe('thinkingLevelLabel', () => {
  it("names pi's levels with the mu.levels keys", () => {
    expect(thinkingLevelLabel(t, 'xhigh')).toBe('<mu.levels.xhigh>');
    expect(thinkingLevelLabel(t, 'off')).toBe('<mu.levels.off>');
  });

  it('shows a value that is no pi level as the agent sent it', () => {
    expect(thinkingLevelLabel(t, 'turbo')).toBe('turbo');
    expect(isThinkingLevel('turbo')).toBe(false);
    expect(isThinkingLevel(undefined)).toBe(false);
  });
});
