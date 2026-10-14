import useSWR from 'swr';
import { kyrnBridge, unwrap } from '@/common/kyrn/bridge';
import type { ProviderNames } from '@/renderer/utils/model/providerName';

const NONE: ProviderNames = new Map();

/**
 * The names of the providers set up by hand, as the settings keep them in mu's models.json: the providers the settings
 * edit and the entries written by hand. A name left empty is no name. Nothing when the settings cannot be read.
 */
async function readProviderNames(): Promise<ProviderNames> {
  try {
    const { models } = unwrap(await kyrnBridge.settings.invoke());
    // The providers the settings edit come last, so their names win over a hand-written entry of the same id.
    return new Map(
      [...models.foreign, ...models.providers].flatMap((provider): [string, string][] => {
        const name = provider.name.trim();
        return name ? [[provider.id, name]] : [];
      })
    );
  } catch {
    return NONE;
  }
}

/**
 * The names people gave their own providers, for the model menus (`providerDisplayName`). Read from the settings when
 * a menu first mounts, and again on the next mount, so a provider renamed in the settings shows its new name when the
 * page comes back.
 */
export function useProviderNames(): ProviderNames {
  const { data } = useSWR('mu.providerNames', readProviderNames, { revalidateOnFocus: false });
  return data ?? NONE;
}
