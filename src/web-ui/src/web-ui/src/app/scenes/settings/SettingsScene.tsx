/**
 * SettingsScene — content-only renderer for the Settings scene.
 *
 * The left-side navigation lives in SettingsNav (rendered by NavPanel via
 * nav-registry). This component only renders the active config content panel
 * driven by settingsStore.activeTab.
 */

import React, { lazy, Suspense, useEffect } from 'react';
import { useSettingsStore } from './settingsStore';
import { OsCompatibilityBanner } from '@/infrastructure/system/OsCompatibilityBanner';
import { WindowsOcrBanner } from '@/infrastructure/system/WindowsOcrBanner';
import './SettingsScene.scss';

const AIModelConfig = lazy(() => import('../../../infrastructure/config/components/AIModelConfig'));
const McpToolsConfig = lazy(() => import('../../../infrastructure/config/components/McpToolsConfig'));
const AcpAgentsConfig = lazy(() => import('../../../infrastructure/config/components/AcpAgentsConfig'));
const EditorConfig = lazy(() => import('../../../infrastructure/config/components/EditorConfig'));
const BasicsConfig = lazy(() => import('../../../infrastructure/config/components/BasicsConfig'));
const AppearanceConfig = lazy(() => import('../../../infrastructure/config/components/AppearanceConfig'));
const ReviewConfig = lazy(() => import('../../../infrastructure/config/components/ReviewConfig'));
const QuickActionsConfig = lazy(() => import('../../../infrastructure/config/components/QuickActionsConfig'));
const ArchivedSessionsConfig = lazy(() => import('./components/ArchivedSessionsConfig'));
const KeyboardShortcutsTab = lazy(() => import('./components/KeyboardShortcutsTab'));
const TupaiSettingsTab = lazy(() => import('./components/TupaiSettingsTab'));
const MeshSettingsTab = lazy(() => import('./components/MeshSettingsTab'));
const ImSettingsTab = lazy(() => import('./components/ImSettingsTab'));
const SessionPersonalizationConfig = lazy(() =>
  import('../../../infrastructure/config/components/SessionConfig').then((module) => ({
    default: module.SessionPersonalizationConfig,
  }))
);
const SessionPermissionsConfig = lazy(() =>
  import('../../../infrastructure/config/components/SessionConfig').then((module) => ({
    default: module.SessionPermissionsConfig,
  }))
);

function SettingsSceneLoading() {
  return (
    <div className="bitfun-settings-scene__loading" aria-busy="true" aria-hidden="true">
      <div className="bitfun-settings-scene__loading-line bitfun-settings-scene__loading-line--title" />
      <div className="bitfun-settings-scene__loading-line" />
      <div className="bitfun-settings-scene__loading-line" />
      <div className="bitfun-settings-scene__loading-block" />
    </div>
  );
}

const SettingsScene: React.FC = () => {
  const activeTab = useSettingsStore(s => s.activeTab);
  const setActiveTab = useSettingsStore(s => s.setActiveTab);

  const resolvedTab: typeof activeTab =
    (activeTab as string) === 'session-config' ? 'session-personalization' : activeTab;

  useEffect(() => {
    /** Legacy merged session settings tab removed in favor of two panels. */
    if ((activeTab as string) === 'session-config') {
      setActiveTab('session-personalization');
    }
  }, [activeTab, setActiveTab]);

  let Content: React.ComponentType | null = null;

  // IM 渠道：所有 im-* tab 统一渲染 ImSettingsTab（内部按 tab id 区分渠道类型）
  const isImChannelTab = typeof resolvedTab === 'string' && resolvedTab.startsWith('im-');
  if (isImChannelTab) {
    Content = ImSettingsTab;
  }

  switch (resolvedTab) {
    case 'basics':           Content = BasicsConfig;         break;
    case 'appearance':       Content = AppearanceConfig;     break;
    case 'models':           Content = AIModelConfig;        break;
    case 'archived-sessions': Content = ArchivedSessionsConfig; break;
    case 'session-personalization': Content = SessionPersonalizationConfig; break;
    case 'session-permissions':     Content = SessionPermissionsConfig;     break;
    case 'quick-actions':    Content = QuickActionsConfig;   break;
    case 'review':           Content = ReviewConfig;         break;
    case 'mcp-tools':        Content = McpToolsConfig;      break;
    case 'acp-agents':       Content = AcpAgentsConfig;     break;
    case 'editor':           Content = EditorConfig;         break;
    case 'keyboard':         Content = KeyboardShortcutsTab; break;
    case 'tupai':            Content = TupaiSettingsTab;      break;
    case 'mesh':             Content = MeshSettingsTab;       break;
  }

  return (
    <div className="bitfun-settings-scene">
      {/* macOS 辅助功能权限 / Windows OCR 语言包缺失提示 */}
      <OsCompatibilityBanner />
      <WindowsOcrBanner />
      {Content && (
        <div key={resolvedTab} className="bitfun-settings-scene__content-wrapper">
          <Suspense fallback={<SettingsSceneLoading />}>
            <Content />
          </Suspense>
        </div>
      )}
    </div>
  );
};

export default SettingsScene;
