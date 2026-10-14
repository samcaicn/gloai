/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  openPreview: vi.fn(),
  upgradeFileRef: vi.fn(),
  resolvePreviewPayload: vi.fn(),
}));

vi.mock('@/renderer/pages/conversation/Preview/context/PreviewContext', () => ({
  usePreviewContext: () => ({ openPreview: mocks.openPreview }),
}));

vi.mock('@/renderer/utils/file/previewPayload', () => ({
  upgradeFileRef: (...args: unknown[]) => mocks.upgradeFileRef(...args),
  resolvePreviewPayload: (...args: unknown[]) => mocks.resolvePreviewPayload(...args),
}));

vi.mock('@/renderer/pages/conversation/explorer/currentProjectStore', () => ({
  getCurrentProject: () => null,
}));

import { useLocalFilePreview } from '@/renderer/pages/conversation/Preview/hooks/useLocalFilePreview';

describe('useLocalFilePreview', () => {
  beforeEach(() => {
    mocks.openPreview.mockReset();
    mocks.upgradeFileRef.mockReset().mockImplementation(async (ref: unknown) => ref);
    mocks.resolvePreviewPayload.mockReset().mockResolvedValue({
      content: '<h1>hi</h1>',
      oversized: false,
      sizeBytes: 11,
      thresholdBytes: 1000,
      lastModified: 1,
    });
  });

  it('opens a relative link as the file under the workspace', async () => {
    const { result } = renderHook(() => useLocalFilePreview('/workspace/demo'));

    await act(async () => {
      await result.current('index.html');
    });

    expect(mocks.upgradeFileRef).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/workspace/demo/index.html' }),
      null
    );
    expect(mocks.openPreview).toHaveBeenCalledWith(
      '<h1>hi</h1>',
      'html',
      expect.objectContaining({ file_path: '/workspace/demo/index.html', workspace: '/workspace/demo' }),
      { replace: true }
    );
  });

  it('resolves against the document directory when one is given', async () => {
    const { result } = renderHook(() => useLocalFilePreview('/workspace/demo', '/workspace/demo/docs'));

    await act(async () => {
      await result.current('guide.md');
    });

    expect(mocks.upgradeFileRef).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/workspace/demo/docs/guide.md' }),
      null
    );
  });

  it('leaves absolute paths alone', async () => {
    const { result } = renderHook(() => useLocalFilePreview('/workspace/demo'));

    await act(async () => {
      await result.current('/tmp/out.html');
    });

    expect(mocks.upgradeFileRef).toHaveBeenCalledWith(expect.objectContaining({ path: '/tmp/out.html' }), null);
  });
});
