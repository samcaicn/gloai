/**
 * turnRatingStore — tracks 👍/👎 ratings per dialog turn.
 *
 * Ratings are stored in-memory (zustand) and persisted to localStorage.
 * When a session is deleted/closed, `evaluateSession` is called to
 * compute an overall score and trigger Hermes auto-upgrade via the
 * backend `submit_turn_rating` / `evaluate_session_ratings` commands.
 *
 * The rating flow:
 *   1. User clicks 👍/👎 on an assistant message (ModelRoundItem footer)
 *   2. Rating is stored locally + sent to backend via `submit_turn_rating`
 *   3. When session is deleted, `evaluateSession` reads all ratings,
 *      computes a score, and if positive, auto-upgrades the skill
 *   4. Upgraded skills are uploaded to the server for evaluation
 */

import { create } from 'zustand';
import { invoke } from '@tauri-apps/api/core';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('turnRatingStore');

const STORAGE_KEY = 'tupai:turn-ratings';

/** 'positive' = 👍, 'negative' = 👎 */
export type TurnRating = 'positive' | 'negative';

interface RatingEntry {
  sessionId: string;
  turnId: string;
  rating: TurnRating;
  timestamp: number;
}

interface TurnRatingState {
  /** Map of `${sessionId}:${turnId}` → RatingEntry */
  ratings: Record<string, RatingEntry>;
  /** Set of turn IDs that have been rated (for UI highlight) */
  ratedTurnIds: Set<string>;

  /** Rate a turn (👍 or 👎). Persists locally + sends to backend. */
  rateTurn: (sessionId: string, turnId: string, rating: TurnRating) => Promise<void>;
  /** Get the rating for a specific turn (for UI display). */
  getRating: (sessionId: string, turnId: string) => TurnRating | null;
  /** Evaluate all ratings for a session and trigger auto-upgrade. Called on session deletion. */
  evaluateSession: (sessionId: string) => Promise<void>;
  /** Clear ratings for a session (after evaluation). */
  clearSession: (sessionId: string) => void;
}

function loadFromStorage(): Record<string, RatingEntry> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

function saveToStorage(ratings: Record<string, RatingEntry>) {
  try {
    // Only keep the last 500 ratings to prevent unbounded growth
    const entries = Object.values(ratings).sort((a, b) => b.timestamp - a.timestamp);
    const trimmed = entries.slice(0, 500);
    const obj: Record<string, RatingEntry> = {};
    for (const e of trimmed) {
      obj[`${e.sessionId}:${e.turnId}`] = e;
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(obj));
  } catch (err) {
    log.warn('Failed to persist turn ratings', { error: err });
  }
}

const initialRatings = loadFromStorage();
const initialRatedTurnIds = new Set(Object.values(initialRatings).map(e => e.turnId));

export const useTurnRatingStore = create<TurnRatingState>((set, get) => ({
  ratings: initialRatings,
  ratedTurnIds: initialRatedTurnIds,

  rateTurn: async (sessionId, turnId, rating) => {
    const key = `${sessionId}:${turnId}`;
    const entry: RatingEntry = {
      sessionId,
      turnId,
      rating,
      timestamp: Date.now(),
    };

    // Update local state immediately for responsive UI
    set((state) => {
      const newRatings = { ...state.ratings, [key]: entry };
      const newRatedTurnIds = new Set(state.ratedTurnIds);
      newRatedTurnIds.add(turnId);
      saveToStorage(newRatings);
      return { ratings: newRatings, ratedTurnIds: newRatedTurnIds };
    });

    // Send to backend (best-effort, don't block UI)
    try {
      await invoke('submit_turn_rating', {
        sessionId,
        turnId,
        rating,
      });
      log.debug('Turn rating submitted to backend', { sessionId, turnId, rating });
    } catch (err) {
      // Backend might not be available (non-Tauri) — rating is still stored locally
      log.debug('submit_turn_rating backend call failed (non-critical)', { error: err });
    }
  },

  getRating: (sessionId, turnId) => {
    const key = `${sessionId}:${turnId}`;
    return get().ratings[key]?.rating ?? null;
  },

  evaluateSession: async (sessionId) => {
    const state = get();
    const sessionRatings = Object.values(state.ratings).filter(r => r.sessionId === sessionId);

    if (sessionRatings.length === 0) {
      log.debug('evaluateSession: no ratings for session', { sessionId });
      return;
    }

    const positive = sessionRatings.filter(r => r.rating === 'positive').length;
    const negative = sessionRatings.filter(r => r.rating === 'negative').length;
    const total = sessionRatings.length;
    const score = positive / total;

    log.info('Evaluating session ratings', {
      sessionId,
      total,
      positive,
      negative,
      score,
    });

    // Call backend to evaluate and auto-upgrade if score is good enough
    try {
      const result = await invoke('evaluate_session_ratings', {
        sessionId,
        positiveCount: positive,
        negativeCount: negative,
        totalCount: total,
      });
      log.info('Session rating evaluation completed', { sessionId, result });

      // Clear ratings for this session after evaluation
      get().clearSession(sessionId);
    } catch (err) {
      log.warn('evaluate_session_ratings backend call failed', { error: err });
    }
  },

  clearSession: (sessionId) => {
    set((state) => {
      const newRatings: Record<string, RatingEntry> = {};
      const newRatedTurnIds = new Set(state.ratedTurnIds);
      for (const [key, entry] of Object.entries(state.ratings)) {
        if (entry.sessionId !== sessionId) {
          newRatings[key] = entry;
        } else {
          newRatedTurnIds.delete(entry.turnId);
        }
      }
      saveToStorage(newRatings);
      return { ratings: newRatings, ratedTurnIds: newRatedTurnIds };
    });
  },
}));
