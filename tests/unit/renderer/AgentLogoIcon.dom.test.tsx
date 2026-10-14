/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { act, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// AgentLogoIcon lives in AgentBadge but is a named export consumed by
// AgentModeSelector, MobileConversationBrand, and ChatLayout.
import { AgentLogoIcon } from '@/renderer/components/agent/AgentBadge';
import ThemedLogo from '@/renderer/components/agent/ThemedLogo';

const { useAgentLogosMock } = vi.hoisted(() => ({
  useAgentLogosMock: vi.fn(),
}));

vi.mock('@/renderer/utils/model/agentLogo', () => ({
  useAgentLogos: (...args: unknown[]) => useAgentLogosMock(...args),
  resolveAgentLogo: (_logos: unknown, opts: { backend?: string | null }) =>
    opts.backend ? `http://127.0.0.1:1/api/assets/logos/ai-major/${opts.backend}.svg` : null,
}));

describe('AgentLogoIcon', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, text: () => Promise.resolve('<svg></svg>') }))
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it('passes backend logo through ThemedLogo when resolved', async () => {
    useAgentLogosMock.mockReturnValue({});

    const { container } = render(<AgentLogoIcon backend='openai' />);

    // Wait for ThemedLogo's detection fetch to settle
    await act(async () => {
      await Promise.resolve();
    });

    // Non-tintable SVG (no currentColor in stub) → renders as <img>
    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    // Decorative: the name beside the logo already says which agent it is, and a raw id is no word in any language.
    expect(img?.getAttribute('alt')).toBe('');
    expect(img?.getAttribute('src')).toContain('openai.svg');
  });

  it('marks a custom assistant logo as decorative too', async () => {
    useAgentLogosMock.mockReturnValue({});

    const { container } = render(<AgentLogoIcon agentLogo='http://127.0.0.1:1/logo.png' agent_name='Writer' />);

    const img = container.querySelector('img');
    expect(img?.getAttribute('alt')).toBe('');
    expect(container.textContent).not.toContain('Writer');
  });

  it('renders emoji when agentLogoIsEmoji is set', () => {
    useAgentLogosMock.mockReturnValue({});

    render(<AgentLogoIcon agentLogo='🤖' agentLogoIsEmoji />);

    expect(screen.getByText('🤖')).toBeInTheDocument();
  });

  // No stand-in picture (it used to be a robot): the name beside the slot already says which agent it is.
  it('renders nothing when no logo or backend is available', () => {
    useAgentLogosMock.mockReturnValue({});

    const { container } = render(<AgentLogoIcon />);

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the assistant logo is intentionally empty, even with a backend logo', () => {
    useAgentLogosMock.mockReturnValue({});

    const { container } = render(<AgentLogoIcon agentLogoIsFallback backend='openai' />);

    expect(container).toBeEmptyDOMElement();
  });
});
