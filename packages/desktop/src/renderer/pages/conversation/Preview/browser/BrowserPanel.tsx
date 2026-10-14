/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Message } from '@arco-design/web-react';
import type { RefInputType } from '@arco-design/web-react/es/Input/interface';
import { useTranslation } from 'react-i18next';
import type { WebviewNavigation, WebviewNavigationState } from '@/renderer/components/media/WebviewHost';
import { copyText } from '@/renderer/utils/ui/clipboard';
import { isPlatformPrimaryModifier } from '@/renderer/utils/ui/keyboardShortcuts';
import BrowserChrome from './BrowserChrome';
import BrowserTabLayer from './BrowserTabLayer';
import {
  closeBrowserTab,
  closeOtherBrowserTabs,
  openBrowserPage,
  switchBrowserTab,
  updateBrowserTab,
  useBrowser,
} from './browserStore';
import { MAX_BROWSER_TABS, resolveAddressBarInput } from './constants';
import styles from './BrowserPanel.module.css';

type NavigationRef = { current: WebviewNavigation | null };

const sameNavigation = (a: WebviewNavigationState, b: WebviewNavigationState): boolean =>
  a.url === b.url && a.canGoBack === b.canGoBack && a.canGoForward === b.canGoForward && a.loading === b.loading;

/**
 * The work panel's 浏览器 tab: the in-app browser. Its pages (`browserStore.ts`) stay mounted under one chrome, their
 * tabs and one address bar for the page in front (`BrowserChrome.tsx`); a page mu is driving shows its step bar above
 * it, and mu's questions come up over the app (`muBrowser/`). With no page open, the address bar opens one.
 *
 * Cmd/Ctrl+W inside the browser closes the page in front, as it closes the preview's tab inside the preview; the
 * pages' own keys stay theirs.
 */
export default function BrowserPanel({
  maximized,
  onToggleMaximize,
}: {
  maximized: boolean;
  /** Absent where the browser cannot fill the page (a phone's sheet). */
  onToggleMaximize?: () => void;
}) {
  const { t } = useTranslation();
  const { tabs, activeTabId, limitHitAt } = useBrowser();
  const [message, messageHolder] = Message.useMessage();
  const root = useRef<HTMLDivElement>(null);
  const address = useRef<RefInputType>(null);
  // Every page's navigation, by tab id: what it reports, and the handle the chrome's buttons use.
  const [navigation, setNavigation] = useState<Readonly<Record<string, WebviewNavigationState>>>({});
  const handles = useRef(new Map<string, NavigationRef>());

  const navigationRef = useCallback((tabId: string): NavigationRef => {
    let handle = handles.current.get(tabId);
    if (!handle) {
      handle = { current: null };
      handles.current.set(tabId, handle);
    }
    return handle;
  }, []);

  const handleNavigationChange = useCallback((tabId: string, state: WebviewNavigationState) => {
    setNavigation((known) =>
      known[tabId] && sameNavigation(known[tabId], state) ? known : { ...known, [tabId]: state }
    );
  }, []);

  // A closed page's navigation goes with it.
  useEffect(() => {
    const open = new Set(tabs.map((tab) => tab.id));
    for (const id of handles.current.keys()) if (!open.has(id)) handles.current.delete(id);
    setNavigation((known) =>
      Object.keys(known).every((id) => open.has(id))
        ? known
        : Object.fromEntries(Object.entries(known).filter(([id]) => open.has(id)))
    );
  }, [tabs]);

  // A full browser took over its oldest page: say so, or it looks like a page changed by itself. Once per time.
  const warned = useRef(limitHitAt);
  useEffect(() => {
    if (!limitHitAt || limitHitAt === warned.current) return;
    warned.current = limitHitAt;
    message.warning?.(t('preview.browser.tabLimitReached', { count: MAX_BROWSER_TABS }));
  }, [limitHitAt, message, t]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing || event.repeat || event.altKey || event.shiftKey) return;
      if (!isPlatformPrimaryModifier(event) || event.key.toLowerCase() !== 'w') return;
      const target = event.target as Node | null;
      if (!activeTabId || !target || !root.current?.contains(target)) return;
      event.preventDefault();
      closeBrowserTab(activeTabId);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeTabId]);

  // Read when a button is used: the page in front may have mounted after this render.
  const inFront = (): WebviewNavigation | null =>
    activeTabId ? (handles.current.get(activeTabId)?.current ?? null) : null;

  const newPage = useCallback(() => {
    openBrowserPage(undefined, { by: 'user' });
    // A blank page is for typing an address: the field is ready for it.
    requestAnimationFrame(() => address.current?.focus());
  }, []);

  const copyAddress = (tabId: string) => {
    const url = tabs.find((tab) => tab.id === tabId)?.url;
    if (!url) return;
    copyText(url).then(
      () => message.success?.(t('common.copySuccess')),
      () => message.error?.(t('common.copyFailed'))
    );
  };

  const submit = (input: string) => {
    const target = resolveAddressBarInput(input);
    if (!target) return;
    // The page in front goes there; a page not mounted yet starts there; with no page, a new one opens.
    const page = inFront();
    if (page) page.go(target);
    else if (activeTabId) updateBrowserTab(activeTabId, { url: target });
    else openBrowserPage(target, { by: 'user' });
  };

  return (
    <div ref={root} className={styles.panel} data-testid='browser-panel'>
      {messageHolder}
      <BrowserChrome
        tabs={tabs}
        activeTabId={activeTabId}
        navigation={activeTabId ? navigation[activeTabId] : undefined}
        addressRef={address}
        onSelect={switchBrowserTab}
        onClose={closeBrowserTab}
        onCloseOthers={closeOtherBrowserTabs}
        onCopyAddress={copyAddress}
        onNewPage={newPage}
        onBack={() => inFront()?.back()}
        onForward={() => inFront()?.forward()}
        onReload={() => inFront()?.reload()}
        onSubmit={submit}
        maximized={maximized}
        onToggleMaximize={onToggleMaximize}
      />
      <div className={styles.pagesArea}>
        {tabs.length ? (
          <BrowserTabLayer
            tabs={tabs}
            activeTabId={activeTabId}
            onNavigationChange={handleNavigationChange}
            navigationRef={navigationRef}
          />
        ) : (
          <p className={styles.quiet}>{t('common.workPanel.browserEmpty')}</p>
        )}
      </div>
    </div>
  );
}
