import { useEffect, useState } from 'react';
import { kyrnBridge, unwrap } from '@/common/kyrn/bridge';

/**
 * The permission mode mu starts a new conversation in, as the settings' Permissions page set it (or mu's own
 * default); undefined until it is read, and when it cannot be. Read once per visit of the home page, so a mode
 * changed in the settings shows the next time the page is opened.
 */
export function useMuStartMode(): string | undefined {
  const [mode, setMode] = useState<string>();
  useEffect(() => {
    let live = true;
    void kyrnBridge.settings
      .invoke()
      .then(unwrap)
      .then((settings) => {
        if (live) setMode(settings.permissions?.mode || undefined);
      })
      // Without it the picker shows what the catalog last saw, as before.
      .catch((): void => {});
    return () => {
      live = false;
    };
  }, []);
  return mode;
}
