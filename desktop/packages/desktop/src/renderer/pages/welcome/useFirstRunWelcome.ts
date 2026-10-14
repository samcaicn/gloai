import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { kyrnBridge, unwrap } from '@/common/kyrn/bridge';
import { markOnboardingSeen, needsOnboarding, onboardingSeen } from './onboarding';

/** Opens the first-run guide once, for someone with no startup model yet. Anyone else never sees it unasked. */
export function useFirstRunWelcome(): void {
  const navigate = useNavigate();
  useEffect(() => {
    if (onboardingSeen()) return;
    let live = true;
    void kyrnBridge.settings
      .invoke()
      .then(unwrap)
      .then((settings) => {
        if (!live) return;
        if (needsOnboarding(settings)) navigate('/welcome', { replace: true });
        // Someone who set a model up before the guide existed does not need it.
        else markOnboardingSeen();
      })
      .catch((): undefined => undefined);
    return () => {
      live = false;
    };
  }, [navigate]);
}
