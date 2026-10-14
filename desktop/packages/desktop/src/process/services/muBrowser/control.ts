import type { BrowserControlAction, PauseReason } from '@/common/kyrn/browserRun';

/**
 * What `Mu.control` answers for one run. The harness asks before every step, and keeps asking every 400 ms while
 * `paused` is true; `stop` ends the run as aborted. A new run starts from `FRESH`.
 */
export type ControlState = {
  readonly paused: boolean;
  readonly stop: boolean;
  readonly pausedBy?: PauseReason;
};

export const FRESH: ControlState = { paused: false, stop: false };

/** `interaction` is the person's mouse or keyboard on the page itself, seen while the loop was not using them. */
export type ControlInput = BrowserControlAction | 'interaction';

export function nextControl(state: ControlState, input: ControlInput): ControlState {
  // Stop is final for the run: nothing un-stops it, and a stopped run has nothing left to pause.
  if (state.stop) return state;
  switch (input) {
    case 'stop':
      return { paused: false, stop: true };
    case 'pause':
      return state.paused ? state : { paused: true, stop: false, pausedBy: 'user' };
    case 'takeover':
      // Taking over a run that was already paused only changes why it is paused.
      return { paused: true, stop: false, pausedBy: 'takeover' };
    case 'interaction':
      // Better a paused loop than two hands on one page. Once paused, more input changes nothing.
      return state.paused ? state : { paused: true, stop: false, pausedBy: 'interaction' };
    case 'resume':
      return state.paused ? FRESH : state;
  }
}

/**
 * The loop's own `Input.*` commands reach the page the same way a person's mouse does, so they are told apart by
 * time: input counts as the person's only when no such command is in flight and none finished a moment ago.
 */
export const SYNTHETIC_INPUT_GRACE_MS = 150;

export type InputWindow = {
  inFlight: number;
  lastFinishedAt: number;
};

export function personsInput(window: InputWindow, now: number): boolean {
  return window.inFlight === 0 && now - window.lastFinishedAt > SYNTHETIC_INPUT_GRACE_MS;
}
