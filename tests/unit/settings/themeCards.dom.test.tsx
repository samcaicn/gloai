import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// The theme cards on 外观: each built-in card shows the app in its own appearance, whatever the app shows now,
// and the chosen card says so with a check and aria-checked.

const selectTheme = vi.fn(() => Promise.resolve());

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en-US' } }),
}));
vi.mock('@/common/config/configService', () => ({ configService: { get: () => [], set: vi.fn() } }));
vi.mock('@/common', () => ({
  ipcBridge: { extensions: { getThemes: { invoke: () => Promise.resolve([]) } } },
}));
vi.mock('@renderer/assets/themes/light-theme.png', () => ({ default: 'light-cover.png' }));
vi.mock('@renderer/assets/themes/dark-theme.png', () => ({ default: 'dark-cover.png' }));
vi.mock('@renderer/hooks/context/ThemeContext.tsx', () => ({
  useThemeContext: () => ({ theme: 'light', activeTheme: undefined, activeId: 'light', selectTheme }),
}));
vi.mock('@/renderer/pages/settings/AppearanceSettings/CssThemeModal.tsx', () => ({ default: () => null }));

import CssThemeSettings from '@/renderer/pages/settings/AppearanceSettings/CssThemeSettings';

afterEach(cleanup);

describe('theme cards', () => {
  it('shows the dark app on the Dark card while the app is light', async () => {
    render(<CssThemeSettings />);
    const dark = await screen.findByTestId('theme-card-dark');
    expect(dark.style.backgroundImage).toContain('dark-cover.png');
    expect(screen.getByTestId('theme-card-light').style.backgroundImage).toContain('light-cover.png');
  });

  // The ring is in the body text colour, not the accent: the settings keep lavender for buttons and switches.
  it('marks the chosen card with a ring and a check, and only that one', async () => {
    render(<CssThemeSettings />);
    const light = await screen.findByTestId('theme-card-light');
    await waitFor(() => expect(screen.getAllByRole('radio')).toHaveLength(3));
    expect(light).toHaveAttribute('aria-checked', 'true');
    expect(light.style.outline).toContain('var(--text-primary)');
    expect(light.querySelector('svg')).not.toBeNull();
    const dark = screen.getByTestId('theme-card-dark');
    expect(dark).toHaveAttribute('aria-checked', 'false');
    expect(dark.style.outline).toBe('');
  });
});
