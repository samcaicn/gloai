import { useSyncExternalStore } from 'react';

export type DeviceApprovalStatus =
  | 'active'
  | 'pending_approval'
  | 'rejected'
  | 'unknown'
  | 'unregistered';

const DEVICE_TOKEN_KEY = 'safeopc_device_token';

type Listener = () => void;

interface DeviceStatusState {
  approvalStatus: DeviceApprovalStatus;
  token: string | null;
  requestId: string | null;
}

function readToken(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(DEVICE_TOKEN_KEY) : null;
  } catch {
    return null;
  }
}

let state: DeviceStatusState = {
  approvalStatus: 'unknown',
  token: readToken(),
  requestId: null,
};

const listeners = new Set<Listener>();

function emit(): void {
  listeners.forEach((l) => l());
}

/**
 * Minimal dependency-free external store (the project does not use zustand).
 * Backs the device-approval status light, settings panel, and the execution
 * gate's client-side guard.
 */
export const deviceStatusStore = {
  getSnapshot(): DeviceStatusState {
    return state;
  },
  subscribe(l: Listener): () => void {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
  setStatus(next: { approvalStatus: DeviceApprovalStatus; token: string | null }): void {
    state = { ...state, ...next };
    emit();
  },
  setRequestId(requestId: string | null): void {
    state = { ...state, requestId };
    emit();
  },
  clearPending(): void {
    state = { ...state, requestId: null, approvalStatus: 'unknown' };
    emit();
  },
  reset(): void {
    state = { approvalStatus: 'unknown', token: null, requestId: null };
    emit();
  },
};

export function useDeviceStatus(): DeviceStatusState {
  return useSyncExternalStore(deviceStatusStore.subscribe, deviceStatusStore.getSnapshot);
}

// ── Token persistence (mirrors safeopcAPP localStorage contract) ──

export function readDeviceToken(): string | null {
  return readToken();
}

export function writeDeviceToken(token: string): void {
  try {
    localStorage.setItem(DEVICE_TOKEN_KEY, token);
  } catch {
    /* private mode / quota — token stays in-memory only */
  }
  state = { ...state, token };
  emit();
}

export function clearDeviceToken(): void {
  try {
    localStorage.removeItem(DEVICE_TOKEN_KEY);
  } catch {
    /* ignore */
  }
  state = { ...state, token: null };
  emit();
}
