export type Locale = "zh" | "en";
export type Theme = "dark" | "light" | "system";
export type SceneId = "welcome" | "session" | "settings";
export type HarnessState = "idle" | "starting" | "ready" | "error";

export interface RecentWorkspace {
  path: string;
  name: string;
  openedAt: string;
}

export interface AppSettings {
  harnessCommand: string;
  harnessArgs: string[];
  theme: Theme | string;
  locale: Locale | string;
  closeToTray: boolean;
  recentWorkspaces: RecentWorkspace[];
}

export interface HarnessStatus {
  state: HarnessState;
  url: string | null;
  workspace: string | null;
  pid: number | null;
  source: string | null;
  error: string | null;
  logTail: string[];
}

export interface Probe {
  found: boolean;
  path: string | null;
  version: string | null;
  error: string | null;
}

export interface DoctorReport {
  node: Probe;
  dsh: Probe;
  npx: Probe;
  hasApiKey: boolean;
  keyring: boolean;
  launchProgram: string;
  launchArgs: string[];
  launchSource: string;
}

export interface SettingsPatch {
  harnessCommand?: string;
  harnessArgs?: string[];
  theme?: string;
  locale?: string;
  closeToTray?: boolean;
  recentWorkspaces?: RecentWorkspace[];
}

export const IDLE_HARNESS: HarnessStatus = {
  state: "idle",
  url: null,
  workspace: null,
  pid: null,
  source: null,
  error: null,
  logTail: [],
};
