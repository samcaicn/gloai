import { useTranslation } from 'react-i18next';
import type { DecisionMode } from '@/common/kyrn/manifest';

/**
 * The two states a decision point is shown in: on (`active`), or off. The harness has a third mode, `shadow` (asked
 * and recorded, changing nothing); a stored `shadow` has no effect either, so it reads as off.
 */
export type ShownMode = 'active' | 'off';

export const shownMode = (mode: DecisionMode): ShownMode => (mode === 'active' ? 'active' : 'off');

/**
 * The app's own words for the two states (开启 / 关闭), with one sentence each on what the state means. Nothing here
 * lets a mode be picked: what the settings file says about a point is shown as these words, no longer changed.
 */
export function useModeLabels(): Record<ShownMode, { label: string; help: string }> {
  const { t } = useTranslation();
  return {
    active: { label: t('mu.modes.active'), help: t('mu.modes.activeHelp') },
    off: { label: t('mu.modes.off'), help: t('mu.modes.offHelp') },
  };
}
