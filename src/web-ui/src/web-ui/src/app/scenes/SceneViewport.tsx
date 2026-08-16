/**
 * SceneViewport — renders the active scene component.
 *
 * Only tabs that have been visited at least once are mounted (lazy
 * mount).  Once mounted, a tab stays in the DOM (hidden via CSS) so
 * switching back is instant and state is preserved.
 *
 * 'welcome' is a proper scene tab; it auto-closes when any other
 * scene is explicitly opened.
 */

import React, { Suspense, lazy, useEffect, useMemo, useState } from 'react';
import type { SceneTabId } from '../components/SceneBar/types';
import { useSceneManager } from '../hooks/useSceneManager';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { useDialogCompletionNotify } from '../hooks/useDialogCompletionNotify';
import { ProcessingIndicator } from '@/flow_chat/components/modern/ProcessingIndicator';
import './SceneViewport.scss';

// All scenes are lazy-loaded so the initial bundle stays small and
// each scene's code is only fetched when first opened.  Once loaded
// the module is cached by the bundler, so subsequent tab switches
// are instant.
const SettingsScene      = lazy(() => import('./settings/SettingsScene'));
const AssistantScene     = lazy(() => import('./assistant/AssistantScene'));
const TupaiChatScene     = lazy(() => import('./session/TupaiChatScene'));
const TupaiHomeScene     = lazy(() => import('./my-agent/TupaiHomeScene'));
const TupaiSkillsScene   = lazy(() => import('./skills/TupaiSkillsScene'));
const TerminalScene      = lazy(() => import('./terminal/TerminalScene'));
const GitScene           = lazy(() => import('./git/GitScene'));
const TupaiFileViewerScene = lazy(() => import('./file-viewer/TupaiFileViewerScene'));
const ProfileScene      = lazy(() => import('./profile/ProfileScene'));
const AgentsScene        = lazy(() => import('./agents/AgentsScene'));
const MiniAppGalleryScene = lazy(() => import('./miniapps/MiniAppGalleryScene'));
const BrowserScene      = lazy(() => import('./browser/BrowserScene'));
const ShellScene        = lazy(() => import('./shell/ShellScene'));
const WelcomeScene      = lazy(() => import('./welcome/WelcomeScene'));
const MiniAppScene      = lazy(() => import('./miniapps/MiniAppScene'));
const TupaiTasksScene   = lazy(() => import('./panel-view/TupaiTasksScene'));
const AutomationScene   = lazy(() => import('./automation/AutomationScene'));
const FlowchartScene    = lazy(() => import('./automation/FlowchartScene'));
const AutoskillScene    = lazy(() => import('./autoskill/AutoskillScene'));
const TasksScene        = lazy(() => import('./tasks/TasksScene'));
const PipelinesScene    = lazy(() => import('./pipelines/PipelinesScene'));


interface SceneViewportProps {
  workspacePath?: string;
  isEntering?: boolean;
}

const SceneViewport: React.FC<SceneViewportProps> = ({ workspacePath, isEntering = false }) => {
  const { openTabs, activeTabId } = useSceneManager();
  const { t } = useI18n('common');
  useDialogCompletionNotify();

  // Track which tabs have been activated at least once.  Only tabs that
  // the user has actually visited get mounted — unvisited tabs stay
  // unmounted (no render, no state, no IPC) until first activation.
  // Once visited, the component stays mounted (preserving state) even
  // when the user switches away, so back-and-forth switches are instant.
  const [visitedTabs, setVisitedTabs] = useState<Set<string>>(new Set([activeTabId]));
  useEffect(() => {
    if (activeTabId) {
      setVisitedTabs(prev => {
        if (prev.has(activeTabId)) return prev;
        const next = new Set(prev);
        next.add(activeTabId);
        return next;
      });
    }
  }, [activeTabId]);

  // Only render tabs that have been visited AND are still open.
  const tabsToRender = useMemo(
    () => openTabs.filter(tab => visitedTabs.has(tab.id)),
    [openTabs, visitedTabs],
  );

  // All tabs closed — show empty state
  if (openTabs.length === 0) {
    return (
      <div className="bitfun-scene-viewport">
        <div className="bitfun-scene-viewport__clip bitfun-scene-viewport__clip--empty">
          <p className="bitfun-scene-viewport__empty-hint">{t('welcomeScene.emptyHint')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="bitfun-scene-viewport">
      <div className="bitfun-scene-viewport__clip">
        {tabsToRender.map(tab => {
          const isActive = tab.id === activeTabId;
          return (
            <div
              key={tab.id}
              className={[
                'bitfun-scene-viewport__scene',
                isActive && 'bitfun-scene-viewport__scene--active',
              ].filter(Boolean).join(' ')}
              aria-hidden={!isActive}
            >
              <Suspense
                fallback={
                  isActive ? (
                    <div
                      className="bitfun-scene-viewport__lazy-fallback"
                      role="status"
                      aria-busy="true"
                      aria-label={t('loading.scenes')}
                    >
                      <ProcessingIndicator visible />
                    </div>
                  ) : null
                }
              >
                {renderScene(tab.id, workspacePath, isEntering, isActive)}
              </Suspense>
            </div>
          );
        })}
      </div>
    </div>
  );
};

function renderScene(
  id: SceneTabId,
  workspacePath?: string,
  _isEntering?: boolean,
  isActive: boolean = false
) {
  switch (id) {
    case 'welcome':
      return <WelcomeScene />;
    case 'session':
      return <TupaiChatScene />;
    case 'terminal':
      return <TerminalScene isActive={isActive} />;
    case 'git':
      return <GitScene workspacePath={workspacePath} isActive={isActive} />;
    case 'settings':
      return <SettingsScene />;
    case 'file-viewer':
      return <TupaiFileViewerScene />;
    case 'profile':
      return <ProfileScene />;
    case 'agents':
      return <AgentsScene />;
    case 'skills':
      return <TupaiSkillsScene />;
    case 'miniapps':
      return <MiniAppGalleryScene />;
    case 'browser':
      return <BrowserScene />;
    case 'assistant':
      return <AssistantScene workspacePath={workspacePath} />;
    case 'insights':
      return <TupaiHomeScene />;
    case 'shell':
      return <ShellScene isActive={isActive} />;
    case 'panel-view':
      return <TupaiTasksScene />;
    case 'automation':
      return <AutomationScene />;
    case 'flowchart':
      return <FlowchartScene />;
    case 'autoskill':
      return <AutoskillScene />;
    case 'tasks':
      return <TasksScene />;
    case 'pipelines':
      return <PipelinesScene />;
    default:
      if (typeof id === 'string' && id.startsWith('miniapp:')) {
        return <MiniAppScene appId={id.slice('miniapp:'.length)} />;
      }
      return null;
  }
}

export default SceneViewport;
