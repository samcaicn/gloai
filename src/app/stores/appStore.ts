import { create } from "zustand";
import { createHostAdapter, type HostAdapter } from "@/infrastructure/adapters/host";
import { createLogger } from "@/infrastructure/logger";
import {
  IDLE_HARNESS,
  type AppSettings,
  type HarnessStatus,
  type Locale,
  type SceneId,
  type Theme,
} from "@/shared/types";

const log = createLogger("store");
let bootstrapped = false;

export interface AppStore {
  host: HostAdapter;
  ready: boolean;
  locale: Locale;
  theme: Theme;
  workspacePath: string | null;
  recentWorkspaces: AppSettings["recentWorkspaces"];
  activeScene: SceneId;
  navCollapsed: boolean;
  harness: HarnessStatus;
  hasApiKey: boolean;
  closeToTray: boolean;
  harnessCommand: string;
  logs: string[];
  errorBanner: string | null;
  bootstrap: () => Promise<void>;
  setScene: (scene: SceneId) => void;
  toggleNav: () => void;
  openWorkspace: (path?: string) => Promise<void>;
  restartHarness: () => Promise<void>;
  stopRuntime: () => Promise<void>;
  applySettings: (patch: {
    theme?: Theme;
    locale?: Locale;
    closeToTray?: boolean;
    harnessCommand?: string;
  }) => Promise<void>;
  saveApiKey: (key: string | null) => Promise<void>;
  setHarness: (status: HarnessStatus) => void;
  appendLog: (line: string) => void;
  clearBanner: () => void;
}

function asLocale(value: string | undefined): Locale {
  return value === "en" ? "en" : "zh";
}

function asTheme(value: string | undefined): Theme {
  if (value === "light" || value === "system") return value;
  return "dark";
}

export const useAppStore = create<AppStore>((set, get) => ({
  host: createHostAdapter(),
  ready: false,
  locale: "zh",
  theme: "dark",
  workspacePath: null,
  recentWorkspaces: [],
  activeScene: "welcome",
  navCollapsed: false,
  harness: IDLE_HARNESS,
  hasApiKey: false,
  closeToTray: false,
  harnessCommand: "",
  logs: [],
  errorBanner: null,

  async bootstrap() {
    if (bootstrapped) return;
    bootstrapped = true;
    const { host } = get();
    host.subscribeHarnessStatus((status) => get().setHarness(status));
    host.subscribeHarnessLog((line) => get().appendLog(line));
    try {
      const [settings, hasApiKey, harness] = await Promise.all([
        host.getSettings(),
        host.hasApiKey(),
        host.getHarnessStatus(),
      ]);
      set({
        ready: true,
        locale: asLocale(settings.locale),
        theme: asTheme(settings.theme),
        recentWorkspaces: settings.recentWorkspaces,
        hasApiKey,
        closeToTray: settings.closeToTray,
        harnessCommand: settings.harnessCommand,
        harness,
        workspacePath: harness.workspace,
        activeScene: harness.state === "ready" ? "session" : "welcome",
      });
    } catch (error) {
      log.error("bootstrap failed", error);
      set({ ready: true, errorBanner: String(error) });
    }
  },

  setScene(scene) {
    set({ activeScene: scene });
  },

  toggleNav() {
    set({ navCollapsed: !get().navCollapsed });
  },

  async openWorkspace(path) {
    const { host, hasApiKey } = get();
    if (!hasApiKey) {
      set({ activeScene: "settings", errorBanner: "need-key" });
      return;
    }
    try {
      const selected = path ?? (await host.pickWorkspaceDirectory("选择工作区"));
      if (!selected) return;
      set({
        workspacePath: selected,
        activeScene: "session",
        errorBanner: null,
        harness: { ...IDLE_HARNESS, state: "starting", workspace: selected },
      });
      const status = await host.startHarness(selected);
      get().setHarness(status);
      const settings = await host.getSettings();
      set({ recentWorkspaces: settings.recentWorkspaces });
    } catch (error) {
      log.error("open workspace failed", error);
      set({
        errorBanner: String(error),
        harness: {
          ...IDLE_HARNESS,
          state: "error",
          error: String(error),
          workspace: get().workspacePath,
        },
      });
    }
  },

  async restartHarness() {
    const path = get().workspacePath;
    if (path) await get().openWorkspace(path);
  },

  async stopRuntime() {
    await get().host.stopHarness();
    set({ harness: IDLE_HARNESS });
  },

  async applySettings(patch) {
    const settings = await get().host.saveSettings(patch);
    set({
      locale: asLocale(settings.locale),
      theme: asTheme(settings.theme),
      closeToTray: settings.closeToTray,
      harnessCommand: settings.harnessCommand,
      recentWorkspaces: settings.recentWorkspaces,
    });
  },

  async saveApiKey(key) {
    await get().host.setApiKey(key);
    set({ hasApiKey: Boolean(key && key.trim()) });
  },

  setHarness(status) {
    set({
      harness: status,
      workspacePath: status.workspace ?? get().workspacePath,
    });
  },

  appendLog(line) {
    const logs = [...get().logs, line].slice(-80);
    set({ logs });
  },

  clearBanner() {
    set({ errorBanner: null });
  },
}));
