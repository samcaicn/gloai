import classNames from 'classnames';
import React from 'react';
import { useLayoutContext } from '@/renderer/hooks/context/LayoutContext';
import {
  SettingsTabNavigateProvider,
  SettingsViewModeProvider,
} from '@/renderer/components/settings/SettingsModal/settingsViewContext';
import { useLocation, useNavigate } from 'react-router-dom';
import { isSettingsRouteActive } from '../settingsNav';
import { useSettingsNav } from './SettingsSider';
import './settings.css';

/**
 * The one frame of a settings page: the side gutter, the column (880px, centred) and the space above the title. A page
 * that keeps its header out of its scroll body (the assistants, the scheduled tasks) lays itself out with the same
 * three, so every page has its title in the same place and the same width.
 */
export const SETTINGS_PAGE_GUTTER = 'px-16px md:px-32px';
export const SETTINGS_PAGE_COLUMN = 'mx-auto w-full md:max-w-880px';
export const SETTINGS_PAGE_TOP = 'pt-16px md:pt-24px';
/**
 * A sticky header takes back exactly the space above it, as margin, and returns it as padding: it then sits where a
 * plain header would, and its title lands at the same height as every other page's.
 */
export const SETTINGS_PAGE_STICKY_TOP = '-mt-16px pt-16px md:-mt-24px md:pt-24px';

interface SettingsPageWrapperProps {
  children: React.ReactNode;
  className?: string;
  contentClassName?: string;
}

const SettingsPageWrapper: React.FC<SettingsPageWrapperProps> = ({ children, className, contentClassName }) => {
  const layout = useLayoutContext();
  const isMobile = layout?.isMobile ?? false;
  const navigate = useNavigate();
  const { pathname } = useLocation();
  // On a phone the rail is folded away: the same entries, in the same order, as a row of chips on top.
  const groups = useSettingsNav();
  const menuItems = React.useMemo(() => groups.flatMap((group) => group.items), [groups]);

  // Keep only horizontal padding on the scroll container — vertical padding is
  // moved to the content layer below. A sticky header inside a scroll container
  // with top padding would otherwise stick that far down, letting content peek
  // through the gap above it.
  const containerClass = classNames(
    'settings-page-wrapper w-full min-h-full box-border overflow-y-auto',
    isMobile ? 'px-16px' : SETTINGS_PAGE_GUTTER,
    className
  );

  const contentClass = classNames(
    'settings-page-content',
    SETTINGS_PAGE_COLUMN,
    SETTINGS_PAGE_TOP,
    'pb-16px md:pb-24px',
    contentClassName
  );

  const navigateToTab = React.useCallback(
    (tabId: string) => {
      void navigate(`/settings/${tabId}`, { replace: true });
    },
    [navigate]
  );

  return (
    <SettingsViewModeProvider value='page'>
      <SettingsTabNavigateProvider value={navigateToTab}>
        <div className={containerClass}>
          {isMobile && (
            <div className='settings-mobile-top-nav'>
              {menuItems.map((item) => {
                const active = isSettingsRouteActive(pathname, `/settings/${item.path}`);
                return (
                  <button
                    key={item.path}
                    type='button'
                    className={classNames('settings-mobile-top-nav__item', {
                      'settings-mobile-top-nav__item--active': active,
                    })}
                    onClick={() => {
                      void navigate(`/settings/${item.path}`, { replace: true });
                    }}
                  >
                    <span className='settings-mobile-top-nav__icon'>
                      {item.isImageIcon ? (
                        <span className='w-16px h-16px flex items-center justify-center'>{item.icon}</span>
                      ) : (
                        React.cloneElement(item.icon as React.ReactElement<{ theme?: string; size?: string }>, {
                          theme: 'outline',
                          size: '16',
                        })
                      )}
                    </span>
                    <span className='settings-mobile-top-nav__label'>{item.label}</span>
                  </button>
                );
              })}
            </div>
          )}
          <div className={contentClass}>{children}</div>
        </div>
      </SettingsTabNavigateProvider>
    </SettingsViewModeProvider>
  );
};

export default SettingsPageWrapper;
