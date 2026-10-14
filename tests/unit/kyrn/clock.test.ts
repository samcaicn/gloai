import { describe, expect, it } from 'vitest';
import { formatClock } from '@renderer/pages/conversation/KyrnPanel/clock';

describe('formatClock', () => {
  const at = new Date(2026, 8, 21, 8, 0, 1);

  it('follows the app language, not the operating system', () => {
    expect(formatClock(at, 'zh-CN')).toBe('08:00:01');
    expect(formatClock(at, 'en-US')).toMatch(/^8:00:01\sAM$/);
    expect(formatClock(at, 'de-DE')).toBe('08:00:01');
  });

  it('accepts a timestamp or an ISO string, and falls back to the default language', () => {
    expect(formatClock(at.getTime(), 'zh-CN')).toBe('08:00:01');
    expect(formatClock(at.toISOString(), 'zh-CN')).toBe('08:00:01');
    expect(formatClock(at)).toMatch(/^8:00:01\sAM$/);
  });

  it('shows nothing for a record without a usable time', () => {
    expect(formatClock('not a date', 'zh-CN')).toBe('');
  });
});
