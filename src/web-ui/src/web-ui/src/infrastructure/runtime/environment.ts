import { invoke } from '@tauri-apps/api/core';
import { isSafeHttpUrl } from '@/shared/utils/validation';

type TauriInternals = {
  invoke?: unknown;
  metadata?: {
    currentWindow?: {
      label?: string;
    };
  };
};

const getTauriInternals = (): TauriInternals | undefined => {
  if (typeof window === 'undefined') return undefined;
  return (window as unknown as { __TAURI_INTERNALS__?: TauriInternals }).__TAURI_INTERNALS__;
};

export const isTauriRuntime = (): boolean => {
  const internals = getTauriInternals();
  return typeof internals?.invoke === 'function';
};

/**
 * 安全地在系统浏览器中打开外部链接。
 *
 * 安全：先用 [`isSafeHttpUrl`] 校验协议白名单（http/https/mailto），拒绝
 * `javascript:` / `data:` / `file:` 等可执行 / 本地协议——防止从服务器配置
 * （tenant/brand website）注入的恶意 URL 经 `open_external` / `window.open` 执行。
 * 不安全 URL 仅记录警告并 no-op（不抛错，避免点击导致 UI 崩溃）。
 *
 * 行为：Tauri 运行时优先 `invoke('open_external')`；失败或 web 环境回退 `window.open`。
 */
export async function openExternalUrl(url: string): Promise<void> {
  if (!isSafeHttpUrl(url)) {
    console.warn('[openExternalUrl] blocked unsafe url scheme:', url);
    return;
  }
  if (isTauriRuntime()) {
    try {
      await invoke('open_external', { url });
      return;
    } catch (err) {
      console.error('[openExternalUrl] open_external failed, fallback to window.open:', err);
    }
  }
  try {
    window.open(url, '_blank', 'noopener,noreferrer');
  } catch {
    /* ignore */
  }
}

export const supportsNativeWindowControls = (): boolean => {
  // Tauri window APIs read metadata.currentWindow; browser builds must not call them without it.
  const currentWindow = getTauriInternals()?.metadata?.currentWindow;
  return isTauriRuntime() && typeof currentWindow?.label === 'string';
};

export const supportsNativeWindowDragging = supportsNativeWindowControls;

export const isMacOSDesktopRuntime = (): boolean =>
  supportsNativeWindowControls() &&
  typeof navigator !== 'undefined' &&
  typeof navigator.platform === 'string' &&
  navigator.platform.toUpperCase().includes('MAC');
