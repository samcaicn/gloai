import React from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import SettingsPageWrapper from '../components/SettingsPageWrapper';
import { DECISION_PAGES, FEATURE_PAGES, type SettingsPageId } from '../settingsNav';
import { featurePageOf, isFeatured, manifestOf } from './draft';
import SettingsArea, { type AreaView } from './SettingsArea';
import { useSharedMuSettings } from './useMuSettings';

/** Navigation state of a feature's page opened from its list: the way back is then one step back. */
type FromList = { fromList?: boolean };

/** mu's entries of the settings rail, and what each one shows. */
const MU_PAGES: Partial<Record<SettingsPageId, AreaView>> = {
  providers: { section: 'providers' },
  'default-model': { section: 'defaultModel' },
  judges: { section: 'judges' },
  features: { section: 'features' },
  ...Object.fromEntries(
    DECISION_PAGES.map((page): [SettingsPageId, AreaView] => [`decisions-${page}`, { section: 'decisions', page }])
  ),
  ...Object.fromEntries(
    FEATURE_PAGES.map((page): [SettingsPageId, AreaView] => [
      `more-features-${page}`,
      { section: 'moreFeatures', page },
    ])
  ),
};

/**
 * The view a settings route names: `/settings/<page>` is a page of the rail, `/settings/<page>/<feature>` one
 * feature's options on a features page, and `/<part>` after it a further page of them. Anything else opens on the
 * providers.
 */
export function viewOf(pathname: string): AreaView {
  const [, , page = '', feature, part] = pathname.split('/');
  const view = MU_PAGES[page as SettingsPageId] ?? { section: 'providers' };
  if (!feature) return view;
  const number = Number(part);
  return {
    ...view,
    feature: decodeURIComponent(feature),
    ...(Number.isInteger(number) && number > 1 ? { part: number } : {}),
  };
}

/** The route of a view: the rail's entry for its section and page, then the feature and the page of its options. */
export function routeOf({ section, page, feature, part }: AreaView): string {
  const entries = Object.entries(MU_PAGES) as [SettingsPageId, AreaView][];
  const [id] = entries.find(([, view]) => view.section === section && (view.page === page || !page)) ?? ['providers'];
  if (!feature) return `/settings/${id}`;
  return `/settings/${id}/${encodeURIComponent(feature)}${part && part > 1 ? `/${part}` : ''}`;
}

/**
 * mu's own settings pages — providers, the default model, the kernel's, the decision points' and the other features'
 * — are one area on as many routes. They share the draft of {@link MuSettingsProvider} around the settings routes:
 * moving between them keeps whatever was typed and not yet saved.
 */
export default function MuSettingsPage() {
  const { pathname, state } = useLocation();
  const navigate = useNavigate();
  const view = viewOf(pathname);
  const onView = (next: AreaView) => {
    const fromList = (state as FromList | null)?.fromList;
    // Back from a feature opened from its list is the step back; otherwise (a deep link) the list replaces it.
    if (!next.feature && view.feature && fromList) void navigate(-1);
    // Another page of the same feature's options takes this one's place, so the way back still leads to the list.
    else if (next.feature && next.feature === view.feature) void navigate(routeOf(next), { replace: true, state });
    else
      void navigate(routeOf(next), next.feature ? { state: { fromList: true } satisfies FromList } : { replace: true });
  };
  return (
    <SettingsPageWrapper>
      <SettingsArea section={view.section} page={view.page} feature={view.feature} part={view.part} onView={onView} />
    </SettingsPageWrapper>
  );
}

/**
 * `/settings/more-features/<feature>`, from when every other feature was one page: the options of that feature on the
 * page of its group now, found in the manifest once the settings are read. A featured feature's are on the features
 * page; one the harness no longer has leads to the first page of the other features.
 */
export function MovedFeatureOptions() {
  const { feature = '', part } = useParams();
  const { search, state } = useLocation();
  const mu = useSharedMuSettings();
  if (mu && !mu.base && !mu.error) return null;
  const manifest = manifestOf(mu?.base);
  const known = manifest?.features.find((each) => each.name === feature);
  const number = Number(part);
  const target = known
    ? routeOf({
        section: isFeatured(known.name) ? 'features' : 'moreFeatures',
        page: isFeatured(known.name) ? undefined : featurePageOf(manifest, known),
        feature: known.name,
        part: Number.isInteger(number) ? number : undefined,
      })
    : routeOf({ section: 'moreFeatures', page: FEATURE_PAGES[0] });
  return <Navigate to={`${target}${search}`} replace state={state} />;
}
