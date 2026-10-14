import { describe, expect, it } from 'vitest';
import { appendAutoUpdateDiagnosticEvent } from '@/process/services/update/autoUpdateDiagnostics';

describe('appendAutoUpdateDiagnosticEvent', () => {
  it('records macOS native updater readiness events with platform and elapsed time', () => {
    const state = appendAutoUpdateDiagnosticEvent(
      {
        currentAppVersion: '2.1.27',
        events: [],
      },
      {
        at: '2026-07-01T09:40:33.000Z',
        elapsedMs: 1234,
        platform: 'darwin',
        status: 'native-update-ready',
        version: '2.1.28',
      }
    );

    expect(state.lastEvent).toEqual({
      at: '2026-07-01T09:40:33.000Z',
      elapsedMs: 1234,
      platform: 'darwin',
      status: 'native-update-ready',
      version: '2.1.28',
    });
    expect(state.lastQuitAndInstallAt).toBeUndefined();
  });

  it('keeps recent updater events and records quitAndInstall separately', () => {
    const state = appendAutoUpdateDiagnosticEvent(
      {
        currentAppVersion: '2.1.7',
        events: [],
      },
      {
        at: '2026-05-30T08:00:00.000Z',
        status: 'ready',
        version: '2.1.8',
      }
    );

    const next = appendAutoUpdateDiagnosticEvent(state, {
      at: '2026-05-30T08:01:00.000Z',
      status: 'quit-and-install',
    });

    expect(next).toEqual({
      currentAppVersion: '2.1.7',
      events: [
        {
          at: '2026-05-30T08:00:00.000Z',
          status: 'ready',
          version: '2.1.8',
        },
        {
          at: '2026-05-30T08:01:00.000Z',
          status: 'quit-and-install',
        },
      ],
      lastEvent: {
        at: '2026-05-30T08:01:00.000Z',
        status: 'quit-and-install',
      },
      lastQuitAndInstallAt: '2026-05-30T08:01:00.000Z',
    });
  });
});
