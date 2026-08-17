import { isTauriRuntime } from "@/infrastructure/runtime";
import { tauriAdapter } from "./tauri";
import { webAdapter } from "./web";
import type { HostAdapter } from "./types";

export function createHostAdapter(): HostAdapter {
  return isTauriRuntime() ? tauriAdapter : webAdapter;
}

export type { HostAdapter } from "./types";
