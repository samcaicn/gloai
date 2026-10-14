/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Reconcile wiring: `reconcileGeneratingFromRuntime` lets an authoritative
 * runtime summary (hydrate / send-accepted) relight the sidebar spinner when a
 * WS stream frame was missed (window reload/reconnect race). It must only
 * ever turn the flag ON — clearing stays with terminal stream frames /
 * turn.completed, and with the backend itself once no live frame confirmed a
 * spinner that a summary lit.
 */

import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const bridge = vi.hoisted(() => ({
  stream: [] as Array<(message: unknown) => void>,
  turnCompleted: [] as Array<(event: unknown) => void>,
  getConversation: vi.fn(),
}));

vi.mock('@/common', () => ({
  ipcBridge: {
    database: {
      getUserConversations: { invoke: vi.fn(() => new Promise(() => {})) },
    },
    conversation: {
      get: { invoke: bridge.getConversation },
      listChanged: { on: () => () => {} },
      responseStream: {
        on: (listener: (message: unknown) => void) => {
          bridge.stream.push(listener);
          return () => {};
        },
      },
      turnCompleted: {
        on: (listener: (event: unknown) => void) => {
          bridge.turnCompleted.push(listener);
          return () => {};
        },
      },
      confirmation: { remove: { on: () => () => {} } },
    },
    application: {
      writeRendererLog: { invoke: vi.fn().mockResolvedValue(undefined) },
    },
  },
}));

vi.mock('@/renderer/utils/emitter', () => ({ addEventListener: () => () => {} }));

import {
  reconcileGeneratingFromRuntime,
  UNCONFIRMED_GENERATING_CHECK_MS,
  useConversationListSync,
} from '@/renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync';
import { useConversationRuntimeView } from '@/renderer/pages/conversation/runtime/useConversationRuntimeView';

type Summary = {
  state: 'idle' | 'running';
  can_send_message: boolean;
  has_task: boolean;
  is_processing: boolean;
  pending_confirmations: number;
  turn_id: string | null;
  supports_midturn_delivery: boolean;
};

const summary = (is_processing: boolean, turn_id: string | null): Summary => ({
  state: is_processing ? 'running' : 'idle',
  can_send_message: !is_processing,
  has_task: true,
  is_processing,
  pending_confirmations: 0,
  turn_id,
  supports_midturn_delivery: false,
});

const frame = (conversation_id: string, type: string, turn_id: string) =>
  act(() => {
    for (const listener of bridge.stream)
      listener({ type, data: '', msg_id: `${turn_id}:${type}`, conversation_id, turn_id });
  });

const turnCompleted = (session_id: string, turn_id: string) =>
  act(() => {
    for (const listener of bridge.turnCompleted) {
      listener({
        session_id,
        turn_id,
        status: 'finished',
        state: 'ai_waiting_input',
        detail: '',
        can_send_message: true,
        runtime: summary(false, null),
        workspace: '',
        model: { platform: '', name: '', use_model: '' },
        last_message: { content: null, created_at: 0 },
      });
    }
  });

/** The backend's answer to `conversation.get`, per conversation. */
const backend = new Map<string, Summary | null>();

describe('reconcileGeneratingFromRuntime', () => {
  // Each test uses a distinct conversation id since the store backing this hook is module-level and persists
  // across tests in this file.

  beforeEach(() => {
    vi.useFakeTimers();
    bridge.getConversation.mockImplementation(async ({ id }: { id: string }) =>
      backend.has(id) ? { id, runtime: backend.get(id) ?? undefined } : { id, runtime: summary(false, null) }
    );
  });

  afterEach(() => {
    vi.useRealTimers();
    bridge.getConversation.mockReset();
  });

  it('marks a conversation generating when isProcessing is true', () => {
    const { result } = renderHook(() => useConversationListSync());

    act(() => {
      reconcileGeneratingFromRuntime('conv-a', true);
    });

    expect(result.current.isConversationGenerating('conv-a')).toBe(true);
  });

  it('does not touch other conversations when reconciling one id', () => {
    const { result } = renderHook(() => useConversationListSync());

    act(() => {
      reconcileGeneratingFromRuntime('conv-b', true);
    });

    expect(result.current.isConversationGenerating('conv-b')).toBe(true);
    expect(result.current.isConversationGenerating('conv-c')).toBe(false);
  });

  it('leaves the generating state unchanged when isProcessing is false', () => {
    const { result } = renderHook(() => useConversationListSync());

    act(() => {
      reconcileGeneratingFromRuntime('conv-d', false);
    });

    expect(result.current.isConversationGenerating('conv-d')).toBe(false);
  });

  // The board switch sends `/board on` as a message and mu answers it in a few milliseconds: the turn's frames and
  // turn.completed came before the send response, whose summary (taken at acceptance) still said `is_processing`. The
  // row then kept spinning until the next turn ended (the live log of 2026-09-23 10:23:42, 335 s).
  it('keeps the spinner out when the send response of a turn that already ended comes last', async () => {
    const { result } = renderHook(() => ({
      sync: useConversationListSync(),
      runtime: useConversationRuntimeView('conv-board'),
    }));
    await act(async () => {
      await Promise.resolve();
    });

    act(() => result.current.runtime.markSendStarted());
    frame('conv-board', 'start', 'turn_board_on');
    frame('conv-board', 'content', 'turn_board_on');
    expect(result.current.sync.isConversationGenerating('conv-board')).toBe(true);

    frame('conv-board', 'finish', 'turn_board_on');
    turnCompleted('conv-board', 'turn_board_on');
    expect(result.current.sync.isConversationGenerating('conv-board')).toBe(false);

    act(() => result.current.runtime.markSendAccepted('turn_board_on', summary(true, 'turn_board_on')));

    expect(result.current.sync.isConversationGenerating('conv-board')).toBe(false);
    expect(result.current.runtime.isProcessing).toBe(false);
  });

  it('keeps it out when only the terminal frame said the turn ended', () => {
    const { result } = renderHook(() => useConversationListSync());

    frame('conv-lost-completion', 'start', 'turn_1');
    frame('conv-lost-completion', 'finish', 'turn_1');
    act(() => reconcileGeneratingFromRuntime('conv-lost-completion', true, 'turn_1'));

    expect(result.current.isConversationGenerating('conv-lost-completion')).toBe(false);
  });

  it('still lights a turn whose send response comes first, and its end puts it out', () => {
    const { result } = renderHook(() => useConversationListSync());

    frame('conv-slow', 'finish', 'turn_1');
    act(() => reconcileGeneratingFromRuntime('conv-slow', true, 'turn_2'));
    expect(result.current.isConversationGenerating('conv-slow')).toBe(true);

    frame('conv-slow', 'content', 'turn_2');
    frame('conv-slow', 'finish', 'turn_2');
    expect(result.current.isConversationGenerating('conv-slow')).toBe(false);
  });

  it('puts out a spinner no live frame confirmed once the backend says the conversation is idle', async () => {
    const { result } = renderHook(() => useConversationListSync());
    backend.set('conv-quiet', summary(true, null));

    act(() => reconcileGeneratingFromRuntime('conv-quiet', true, null));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNCONFIRMED_GENERATING_CHECK_MS);
    });
    // Still working, by the backend's word: the spinner stays and is checked again later.
    expect(result.current.isConversationGenerating('conv-quiet')).toBe(true);

    backend.set('conv-quiet', summary(false, null));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNCONFIRMED_GENERATING_CHECK_MS);
    });
    expect(result.current.isConversationGenerating('conv-quiet')).toBe(false);
  });

  it('stops checking once a live frame confirms the spinner', async () => {
    const { result } = renderHook(() => useConversationListSync());
    backend.set('conv-live', summary(false, null));

    act(() => reconcileGeneratingFromRuntime('conv-live', true, 'turn_live'));
    frame('conv-live', 'content', 'turn_live');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNCONFIRMED_GENERATING_CHECK_MS * 3);
    });

    expect(bridge.getConversation).not.toHaveBeenCalledWith({ id: 'conv-live' });
    expect(result.current.isConversationGenerating('conv-live')).toBe(true);
  });
});
