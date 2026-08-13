import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { openUrl } from "@tauri-apps/plugin-opener";
import type { HostAdapter } from "./types";
import type {
  AppSettings,
  DoctorReport,
  HarnessStatus,
  SettingsPatch,
} from "@/shared/types";

export const tauriAdapter: HostAdapter = {
  isNative: true,

  pickWorkspaceDirectory() {
    return invoke<string | null>("pick_workspace");
  },

  getSettings() {
    return invoke<AppSettings>("get_settings");
  },

  saveSettings(patch: SettingsPatch) {
    return invoke<AppSettings>("save_settings_patch", { patch });
  },

  hasApiKey() {
    return invoke<boolean>("get_api_key_present");
  },

  setApiKey(key: string | null) {
    return invoke<boolean>("set_stored_api_key", { key });
  },

  startHarness(workspace: string) {
    return invoke<HarnessStatus>("start_harness", { workspace });
  },

  stopHarness() {
    return invoke<void>("stop_harness");
  },

  getHarnessStatus() {
    return invoke<HarnessStatus>("harness_status");
  },

  subscribeHarnessStatus(onStatus) {
    const unlisten = listen<HarnessStatus>("harness://status", (event) => {
      onStatus(event.payload);
    });
    return () => {
      void unlisten.then((fn) => fn());
    };
  },

  subscribeHarnessLog(onLine) {
    const unlisten = listen<string>("harness://log", (event) => {
      onLine(event.payload);
    });
    return () => {
      void unlisten.then((fn) => fn());
    };
  },

  subscribeMaximized(onChange) {
    const windowRef = getCurrentWindow();
    const unlisten = windowRef.onResized(() => {
      void windowRef.isMaximized().then(onChange);
    });
    return () => {
      void unlisten.then((fn) => fn());
    };
  },

  doctor() {
    return invoke<DoctorReport>("doctor");
  },

  async minimizeWindow() {
    await getCurrentWindow().minimize();
  },

  async toggleMaximizeWindow() {
    await getCurrentWindow().toggleMaximize();
  },

  async closeWindow() {
    await getCurrentWindow().close();
  },

  async startDragging() {
    await getCurrentWindow().startDragging();
  },

  isMaximized() {
    return getCurrentWindow().isMaximized();
  },

  async openExternal(url: string) {
    await openUrl(url);
  },
};
