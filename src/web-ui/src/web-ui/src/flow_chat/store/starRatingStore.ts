import { create } from 'zustand';
import { invoke } from '@tauri-apps/api/core';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('starRatingStore');

export interface PendingSkillRating {
  skillId: string;
  /** Optional display name for UI */
  skillName?: string;
  /** Optional session id for correlation */
  sessionId?: string;
}

interface StarRatingState {
  /** Skill awaiting user star rating */
  pending: PendingSkillRating | null;
  /** Whether submission is in progress */
  submitting: boolean;

  /** Show star rating prompt for a skill */
  promptRating: (skillId: string, skillName?: string, sessionId?: string) => void;
  /** Submit rating (1-5) and dismiss */
  submitRating: (rating: number) => Promise<void>;
  /** Dismiss without rating */
  dismiss: () => void;
}

export const useStarRatingStore = create<StarRatingState>((set, get) => ({
  pending: null,
  submitting: false,

  promptRating: (skillId, skillName, sessionId) => {
    set({ pending: { skillId, skillName, sessionId } });
  },

  submitRating: async (rating: number) => {
    const { pending } = get();
    if (!pending) return;
    set({ submitting: true });
    try {
      await invoke('submit_skill_rating', {
        input: {
          skill_id: pending.skillId,
          rating: Math.round(Math.max(1, Math.min(5, rating))),
          session_id: pending.sessionId ?? null,
        },
      });
      log.debug('skill rating submitted', { skillId: pending.skillId, rating });
    } catch (e) {
      log.warn('submit_skill_rating failed', { skillId: pending.skillId, error: e });
    } finally {
      set({ pending: null, submitting: false });
    }
  },

  dismiss: () => {
    set({ pending: null });
  },
}));
