import { invoke } from '@tauri-apps/api/core';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('skillRating');

export interface SkillRatingInput {
  skillId: string;
  rating: number;
  sessionId?: string;
}

export async function submitSkillRating(input: SkillRatingInput): Promise<void> {
  try {
    await invoke('submit_skill_rating', {
      input: {
        skill_id: input.skillId,
        rating: Math.round(Math.max(1, Math.min(5, input.rating))),
        session_id: input.sessionId ?? null,
      },
    });
    log.debug('skill rating submitted', { skillId: input.skillId, rating: input.rating });
  } catch (e) {
    log.warn('submit_skill_rating failed', { skillId: input.skillId, error: e });
  }
}
