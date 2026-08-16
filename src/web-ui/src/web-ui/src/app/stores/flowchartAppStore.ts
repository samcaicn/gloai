/**
 * flowchartAppStore — tracks the currently selected app name in FlowchartScene.
 *
 * Used by SceneBar to display the app name as a subtitle on the flowchart tab,
 * replacing the static "流程图" label with the dynamic app name.
 */
import { create } from 'zustand';

interface FlowchartAppState {
  /** Currently selected app name in FlowchartScene; empty string when none selected. */
  selectedAppName: string;
  /** Set the selected app name (called by FlowchartScene when user selects an app). */
  setSelectedAppName: (name: string) => void;
}

export const useFlowchartAppStore = create<FlowchartAppState>((set) => ({
  selectedAppName: '',
  setSelectedAppName: (name: string) => set({ selectedAppName: name }),
}));
