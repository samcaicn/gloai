/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useCallback } from 'react';
import type { WebviewNavigation, WebviewNavigationState } from '@/renderer/components/media/WebviewHost';
import { updateBrowserTab, type BrowserTab } from './browserStore';
import BrowserViewer from './BrowserViewer';
import MuBrowserTab from './muBrowser/MuBrowserTab';

export interface BrowserTabLayerProps {
  /** Every page of the browser / Every browser page */
  tabs: readonly BrowserTab[];
  /** 当前在前面的页面 / The page in front */
  activeTabId: string | null;
  /** Each page's navigation state as it changes, for the one address bar above the pages. */
  onNavigationChange: (tabId: string, state: WebviewNavigationState) => void;
  /** Each page's navigation controls, by tab id; the same ref for the same tab on every render. */
  navigationRef: (tabId: string) => React.Ref<WebviewNavigation>;
}

/**
 * 常驻挂载的浏览器层 / Always-mounted browser layer.
 *
 * 切走再切回不能重新加载页面：滚动位置、表单内容、未提交的操作、Agent 正在进行的操作都会丢。
 * 所以这一层把所有页面一直挂着，只切换可见性 —— webview 进程不销毁，页面状态完整保留。
 * 不在前面的页面用 visibility 隐藏而不是 display:none，这样它们仍有真实尺寸，切回来时不需要重新布局。
 *
 * Switching away and back must never reload a page: scroll position, form input, and any in-flight agent operation
 * would be lost. This layer keeps every page mounted and only toggles visibility, so the webview process survives.
 * Pages not in front are hidden via `visibility` rather than `display:none` so they retain real dimensions and need
 * no relayout when shown again. Where a page goes, its title and icon are written back to the browser's store, which
 * keeps them for the next start.
 */
const BrowserTabLayer: React.FC<BrowserTabLayerProps> = ({ tabs, activeTabId, onNavigationChange, navigationRef }) => {
  const handleUrlChange = useCallback((tabId: string, url: string) => updateBrowserTab(tabId, { url }), []);
  const handleTitleChange = useCallback((tabId: string, title: string) => updateBrowserTab(tabId, { title }), []);
  const handleFaviconChange = useCallback((tabId: string, favicon: string) => updateBrowserTab(tabId, { favicon }), []);

  if (tabs.length === 0) return null;

  return (
    <div className='relative flex-1 min-h-0 overflow-hidden'>
      {tabs.map((tab) => {
        const isActive = tab.id === activeTabId;
        const page = {
          url: tab.url,
          tabId: tab.id,
          onUrlChange: handleUrlChange,
          onTitleChange: handleTitleChange,
          onFaviconChange: handleFaviconChange,
          onNavigationChange,
          navigationRef: navigationRef(tab.id),
        };
        return (
          <div
            key={tab.id}
            className='absolute inset-0 flex flex-col'
            data-browser-page={tab.id}
            style={{
              visibility: isActive ? 'visible' : 'hidden',
              pointerEvents: isActive ? undefined : 'none',
              zIndex: isActive ? 1 : 0,
            }}
          >
            {/* A page mu opened gets its own partition and the step bar; everything else is unchanged. */}
            {tab.muRun ? <MuBrowserTab {...page} /> : <BrowserViewer {...page} />}
          </div>
        );
      })}
    </div>
  );
};

export default BrowserTabLayer;
