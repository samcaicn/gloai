/**
 * settingsOverlayStore — Zustand store for the Settings overlay.
 *
 * Settings 不再作为顶栏 tab 出现，而是通过浮层（Modal）展示。
 * 任何入口（SceneBar 齿轮、状态指示灯、quickActions.openSettings、
 * DeepReviewActionBar 等）调用 `openSettingsOverlay(tab?)` 即可拉起
 * 浮层并定位到指定 tab。store 持有 `isOpen` + `activeTab`，组件订阅渲染。
 *
 * 底层 tab 切换复用 `settingsStore.setActiveTab`，保证 SettingsNav 与
 * SettingsScene 的状态来源不变；这里只是叠加一层「浮层显隐」语义。
 */

import { create } from 'zustand';
import type { ConfigTab } from './settingsConfig';
import { useSettingsStore } from './settingsStore';

interface SettingsOverlayState {
  isOpen: boolean;
  /** 浮层打开时锁定的初始 tab；关闭后再打开会回到 settingsStore 当前 tab。 */
  open: (tab?: ConfigTab) => void;
  close: () => void;
  toggle: (tab?: ConfigTab) => void;
}

export const useSettingsOverlayStore = create<SettingsOverlayState>((set) => ({
  isOpen: false,
  open: (tab) => {
    if (tab) {
      useSettingsStore.getState().setActiveTab(tab);
    }
    set({ isOpen: true });
  },
  close: () => set({ isOpen: false }),
  toggle: (tab) =>
    set((state) => {
      if (!state.isOpen) {
        if (tab) {
          useSettingsStore.getState().setActiveTab(tab);
        }
        return { isOpen: true };
      }
      return { isOpen: false };
    }),
}));

/** 命令式入口：供非 hook 调用方（ide-control、event listener 等）使用。 */
export function openSettingsOverlay(tab?: ConfigTab): void {
  useSettingsOverlayStore.getState().open(tab);
}

export function closeSettingsOverlay(): void {
  useSettingsOverlayStore.getState().close();
}
