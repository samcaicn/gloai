import type {
  AppSettings,
  DoctorReport,
  HarnessStatus,
  SettingsPatch,
} from "@/shared/types";

export interface HostAdapter {
  readonly isNative: boolean;
  pickWorkspaceDirectory(title: string): Promise<string | null>;
  getSettings(): Promise<AppSettings>;
  saveSettings(patch: SettingsPatch): Promise<AppSettings>;
  hasApiKey(): Promise<boolean>;
  setApiKey(key: string | null): Promise<boolean>;
  startHarness(workspace: string): Promise<HarnessStatus>;
  stopHarness(): Promise<void>;
  getHarnessStatus(): Promise<HarnessStatus>;
  subscribeHarnessStatus(onStatus: (status: HarnessStatus) => void): () => void;
  subscribeHarnessLog(onLine: (line: string) => void): () => void;
  subscribeMaximized(onChange: (maximized: boolean) => void): () => void;
  doctor(): Promise<DoctorReport>;
  minimizeWindow(): Promise<void>;
  toggleMaximizeWindow(): Promise<void>;
  closeWindow(): Promise<void>;
  startDragging(): Promise<void>;
  isMaximized(): Promise<boolean>;
  openExternal(url: string): Promise<void>;
}
