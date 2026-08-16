// invoke.ts — Tauri invoke 包装层。
// 在非 Tauri 环境（pnpm dev / web preview）下静默返回 undefined，
// 避免 "Cannot read properties of undefined (reading 'transformCallback')"
// 与 "Cannot read properties of undefined (reading 'invoke')" 等错误日志。
// 真实 Tauri 桌面环境下透传至 @tauri-apps/api/core 的 invoke。
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { isTauriRuntime } from '@/infrastructure/runtime';

export function invoke<T = unknown>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauriRuntime()) {
    // 非桌面环境：静默返回 undefined，让调用方走空数据分支
    return Promise.resolve(undefined as T);
  }
  return tauriInvoke<T>(cmd, args);
}
