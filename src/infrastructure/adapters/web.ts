import { IDLE_HARNESS, type AppSettings, type HarnessStatus } from "@/shared/types";
import type { HostAdapter } from "./types";

const SETTINGS_KEY = "dshg.settings";
const KEY_KEY = "dshg.apiKey";

const defaultSettings = (): AppSettings => ({
  harnessCommand: "",
  harnessArgs: [],
  theme: "dark",
  locale: "zh",
  closeToTray: false,
  recentWorkspaces: [],
});

function readSettings(): AppSettings {
  if (typeof localStorage === "undefined") return defaultSettings();
  const raw = localStorage.getItem(SETTINGS_KEY);
  if (!raw) return defaultSettings();
  try {
    return { ...defaultSettings(), ...(JSON.parse(raw) as AppSettings) };
  } catch {
    return defaultSettings();
  }
}

function writeSettings(settings: AppSettings): void {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
}

export const webAdapter: HostAdapter = {
  isNative: false,

  async pickWorkspaceDirectory() {
    return window.prompt("输入工作区绝对路径") || null;
  },

  async getSettings() {
    return readSettings();
  },

  async saveSettings(patch) {
    const next = { ...readSettings(), ...patch };
    writeSettings(next);
    return next;
  },

  async hasApiKey() {
    return Boolean(localStorage.getItem(KEY_KEY));
  },

  async setApiKey(key) {
    if (key) localStorage.setItem(KEY_KEY, key);
    else localStorage.removeItem(KEY_KEY);
    return false;
  },

  async startHarness(): Promise<HarnessStatus> {
    throw new Error("浏览器预览无法启动 dsh web。请运行 pnpm desktop:dev。");
  },

  async stopHarness() {},

  async getHarnessStatus() {
    return IDLE_HARNESS;
  },

  subscribeHarnessStatus() {
    return () => undefined;
  },

  subscribeHarnessLog() {
    return () => undefined;
  },

  subscribeMaximized() {
    return () => undefined;
  },

  async doctor() {
    return {
      node: { found: false, path: null, version: null, error: "web preview" },
      dsh: { found: false, path: null, version: null, error: "web preview" },
      npx: { found: false, path: null, version: null, error: "web preview" },
      hasApiKey: Boolean(localStorage.getItem(KEY_KEY)),
      keyring: false,
      launchProgram: "",
      launchArgs: [],
      launchSource: "web",
    };
  },

  async minimizeWindow() {},
  async toggleMaximizeWindow() {},
  async closeWindow() {},
  async startDragging() {},
  async isMaximized() {
    return false;
  },
  async openExternal(url: string) {
    window.open(url, "_blank", "noopener,noreferrer");
  },
};
