/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { resolveAgentLogo, useAgentLogos } from '@/renderer/utils/model/agentLogo';
import React from 'react';
import ThemedLogo from './ThemedLogo';

export type AgentBadgeProps = {
  /** Agent backend type */
  backend?: string;
  /** Display name for the agent */
  agent_name?: string;
  /** Custom agent logo (SVG path or emoji string) */
  agentLogo?: string;
  /** Whether the logo is an emoji */
  agentLogoIsEmoji?: boolean;
  /** Whether the explicit assistant logo is intentionally empty. */
  agentLogoIsFallback?: boolean;
};

/**
 * Render the agent logo: the assistant's own logo, else the backend's logo, else nothing. A stand-in picture (it
 * used to be a robot) says nothing the name beside it does not, so an agent without a logo gets no icon.
 *
 * The logo is decorative (`alt=''`): every place that shows it puts the agent's or the conversation's name right
 * beside it, and an alt text built from a raw backend id ("openai logo") would be English in every language.
 * `agent_name` is accepted for the callers' convenience and not read.
 */
export const AgentLogoIcon: React.FC<
  Pick<AgentBadgeProps, 'backend' | 'agentLogo' | 'agentLogoIsEmoji' | 'agentLogoIsFallback' | 'agent_name'>
> = ({ backend, agentLogo, agentLogoIsEmoji, agentLogoIsFallback }) => {
  const logos = useAgentLogos();
  const logoContent = (() => {
    if (agentLogoIsFallback) return null;
    if (agentLogo) {
      if (agentLogoIsEmoji) {
        return <span className='text-14px leading-none'>{agentLogo}</span>;
      }
      return <ThemedLogo src={agentLogo} alt='' className='block w-16px h-16px object-contain' />;
    }
    const logo = resolveAgentLogo(logos, { backend });
    return logo ? <ThemedLogo src={logo} alt='' className='block w-16px h-16px object-contain' /> : null;
  })();

  if (!logoContent) return null;
  return (
    <span className='inline-flex w-16px h-16px items-center justify-center shrink-0 leading-none'>{logoContent}</span>
  );
};
