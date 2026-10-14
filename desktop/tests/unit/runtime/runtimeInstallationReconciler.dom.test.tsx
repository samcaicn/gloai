/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createRuntimeInstallationReconciler,
  RUNTIME_RECONCILE_WINDOW_MS,
} from '@/renderer/services/runtime/runtimeInstallationReconciler';
import type { IRuntimeStatusEvent } from '@/common/adapter/ipcBridge';

const failed = (scopeId: string): IRuntimeStatusEvent => ({
  resource: 'node',
  scope: { kind: 'custom_agent', id: scopeId },
  phase: 'failed',
  failure_kind: 'bundled_resource_invalid',
  message: 'os error 1450',
});

const ready = (scopeId: string): IRuntimeStatusEvent => ({
  resource: 'node',
  scope: { kind: 'conversation', id: scopeId },
  phase: 'ready',
});

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('runtimeInstallationReconciler', () => {
  it('retracts the dialog when node ready arrives in-window (cross-scope)', () => {
    const close = vi.fn();
    const r = createRuntimeInstallationReconciler({ showDialog: () => ({ close }) });

    r.handleStatus(failed('custom_agent-1')); // failed on custom_agent scope
    r.handleStatus(ready('conversation-9')); // ready from a DIFFERENT scope

    vi.advanceTimersByTime(RUNTIME_RECONCILE_WINDOW_MS + 100);
    expect(close).toHaveBeenCalledTimes(1); // dialog retracted
  });

  it('keeps the dialog when no node ready arrives within the window', () => {
    const close = vi.fn();
    const showDialog = vi.fn(() => ({ close }));
    const r = createRuntimeInstallationReconciler({ showDialog });

    r.handleStatus(failed('custom_agent-1'));
    expect(showDialog).toHaveBeenCalledTimes(1); // shown immediately
    vi.advanceTimersByTime(RUNTIME_RECONCILE_WINDOW_MS + 100);
    r.handleStatus(ready('conversation-9')); // too late to retract
    expect(close).not.toHaveBeenCalled();
  });

  it('shows one dialog per resource while a reconciliation is pending', () => {
    const showDialog = vi.fn(() => ({ close: vi.fn() }));
    const r = createRuntimeInstallationReconciler({ showDialog });

    r.handleStatus(failed('custom_agent-1'));
    r.handleStatus(failed('custom_agent-2'));
    expect(showDialog).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(RUNTIME_RECONCILE_WINDOW_MS + 100);
    r.handleStatus(failed('custom_agent-3')); // window over: a new failure shows again
    expect(showDialog).toHaveBeenCalledTimes(2);
  });
});
