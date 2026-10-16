/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import React from 'react';

import DocumentTitle, { titleForPath } from '@/renderer/components/layout/DocumentTitle';

describe('titleForPath', () => {
  it('names the product on every route', () => {
    expect(titleForPath('/guid')).toBe('AiBuddy');
    expect(titleForPath('/conversation/abc')).toBe('AiBuddy');
    expect(titleForPath('/settings/agent')).toBe('AiBuddy');
  });
});

describe('DocumentTitle', () => {
  it('owns the window title', () => {
    document.title = 'stale title';
    render(
      <MemoryRouter initialEntries={['/guid']}>
        <DocumentTitle />
      </MemoryRouter>
    );
    expect(document.title).toBe('AiBuddy');
  });
});
