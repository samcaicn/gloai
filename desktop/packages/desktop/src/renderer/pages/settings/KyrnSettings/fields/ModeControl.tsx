import React from 'react';
import { Radio } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import type { DecisionMode } from '@/common/kyrn/manifest';

/**
 * The two states a decision point is shown in: on (`active`), or off. The harness has a third mode, `shadow` (asked
 * and recorded, changing nothing); a stored `shadow` has no effect either, so it reads as off.
 */
export type ShownMode = 'active' | 'off';

/** Off first, then on: left to right as a switch reads. */
const SHOWN_MODES: readonly ShownMode[] = ['off', 'active'];

export const shownMode = (mode: DecisionMode): ShownMode => (mode === 'active' ? 'active' : 'off');

/** The app's own words for the two states (开启 / 关闭), with one sentence each on what the state means. */
export function useModeLabels(): Record<ShownMode, { label: string; help: string }> {
  const { t } = useTranslation();
  return {
    active: { label: t('mu.modes.active'), help: t('mu.modes.activeHelp') },
    off: { label: t('mu.modes.off'), help: t('mu.modes.offHelp') },
  };
}

type ModeControlProps = {
  value: DecisionMode;
  /** Called with `active` or `off` only: a change never writes `shadow`. */
  onChange: (mode: ShownMode) => void;
  labels: Record<ShownMode, { label: string; help: string }>;
  disabled?: boolean;
  label: string;
};

/** Off / on. A stored `shadow` shows as off and stays as it is until the other state is picked. */
export default function ModeControl({ value, onChange, labels, disabled, label }: ModeControlProps) {
  return (
    <Radio.Group
      type='button'
      aria-label={label}
      disabled={disabled}
      value={shownMode(value)}
      options={SHOWN_MODES.map((mode) => ({ value: mode as string, label: labels[mode].label }))}
      onChange={(next: string) => onChange(next as ShownMode)}
    />
  );
}
