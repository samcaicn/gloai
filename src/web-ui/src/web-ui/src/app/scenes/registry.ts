/**
 * SCENE_TAB_REGISTRY — static definitions for all scene tab types.
 *
 * Rules:
 *  - Max MAX_OPEN_SCENES open tabs total.
 *  - pinned = true: protected from auto-eviction and manual close.
 *  - pinned = false: can be auto-evicted and manually closed.
 *
 * tupai 阶段：保留 session / skills / automation / flowchart / settings / autoskill。
 * 其他 scene 已隐藏（welcome/terminal/git/file-viewer/profile/
 * agents/miniapps/browser/assistant/insights/shell/panel-view），后续阶段按需恢复。
 * 被隐藏的 scene 定义已从此数组中移除；getSceneDef 对这些 id 会返回 undefined，
 * sceneStore 的 openScene 会安全地忽略（早 return）。PANEL_VIEW_SCENE_DEF 已改为
 * 独立字面量定义，不再依赖数组查找。
 */

import {
  Puzzle,
  Workflow,
  GitFork,
  Settings,
  Sparkles,
  MessageSquare,
  Clock,
  IterationCw,
} from 'lucide-react';
import type { SceneTabDef, SceneTabId } from '../components/SceneBar/types';

/** Upper bound for concurrent open scene tabs (top bar); least-recently-used closable tab is evicted when exceeded. */
export const MAX_OPEN_SCENES = 8;

export const SCENE_TAB_REGISTRY: SceneTabDef[] = [
  {
    id: 'session' as SceneTabId,
    label: 'Chat',
    labelKey: 'scenes.session',
    Icon: MessageSquare,
    pinned: false,
    closable: true,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'skills' as SceneTabId,
    label: 'Skills',
    labelKey: 'scenes.skills',
    Icon: Puzzle,
    pinned: false,
    singleton: true,
    defaultOpen: true,
  },
  {
    id: 'automation' as SceneTabId,
    label: 'Automation',
    labelKey: 'scenes.automation',
    Icon: Workflow,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'flowchart' as SceneTabId,
    label: 'Flowchart',
    labelKey: 'scenes.flowchart',
    Icon: GitFork,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'settings' as SceneTabId,
    label: 'Settings',
    labelKey: 'scenes.settings',
    Icon: Settings,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'autoskill' as SceneTabId,
    label: 'Auto Skill Suggestions',
    labelKey: 'scenes.autoskill',
    Icon: Sparkles,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'tasks' as SceneTabId,
    label: 'Tasks',
    labelKey: 'scenes.tasks',
    Icon: Clock,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
  {
    id: 'pipelines',
    label: 'Pipelines',
    labelKey: 'scenes.pipelines',
    Icon: IterationCw,
    pinned: false,
    singleton: true,
    defaultOpen: false,
  },
];

export function getSceneDef(id: SceneTabId): SceneTabDef | undefined {
  return SCENE_TAB_REGISTRY.find(d => d.id === id);
}

/** Static singleton scene def for the panel-view scene. */
export const PANEL_VIEW_SCENE_DEF: SceneTabDef = {
  id: 'panel-view' as SceneTabId,
  label: 'Panel View',
  labelKey: 'scenes.panelView',
  Icon: undefined,
  pinned: false,
  fixed: false,
  closable: true,
  singleton: true,
  defaultOpen: false,
};

/** Dynamic scene def for a MiniApp tab (used by SceneBar and useSceneManager). */
export function getMiniAppSceneDef(appId: string, appName?: string): SceneTabDef {
  const id: SceneTabId = `miniapp:${appId}`;
  return {
    id,
    label: appName ?? appId,
    Icon: Puzzle,
    pinned: false,
    fixed: false,
    closable: true,
    singleton: false,
    defaultOpen: false,
  };
}
