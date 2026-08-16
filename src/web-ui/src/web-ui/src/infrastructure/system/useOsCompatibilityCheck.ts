// OS 兼容性检查 Hook。
// 在首次启动 (交互壳就绪 + 启动遮罩消失后) 调一次 check_os_compatibility,
// 把 macOS Accessibility / Windows OCR 缺失状态暴露给 OsCompatibilityBanner。
// 同时暴露 refresh(), 供用户从系统设置返回后重新检查。
//
// 设计原则:
//   - 仅 Tauri 桌面环境触发; 非 Tauri (jsdom dev) report 恒为 null, 横幅不渲染。
//   - 复用 App.tsx 已有的首次启动 guard (interactiveShellReady + !startupOverlayVisible),
//     与 ensureDeviceToken 同一时机, 避免在启动遮罩期间发 IPC。
//   - ranRef 保证只自动跑一次; 手动 refresh() 不受 ranRef 限制。
import { useCallback, useEffect, useRef, useState } from 'react';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { checkOsCompatibility, type OsCompatibilityReport } from './osCompatibility';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('useOsCompatibilityCheck');

export interface OsCompatibilityCheckOptions {
  /** 交互壳是否就绪 (来自 App.tsx, 与 ensureDeviceToken 同源) */
  interactiveShellReady: boolean;
  /** 启动遮罩是否仍可见 (可见时跳过, 避免遮罩期间发 IPC) */
  startupOverlayVisible: boolean;
}

export interface OsCompatibilityCheckState {
  /** 兼容性报告; null 表示尚未检查或非 Tauri 环境 */
  report: OsCompatibilityReport | null;
  /** 是否正在 (重新) 检查 */
  loading: boolean;
  /** 手动重新检查 (用户从系统设置返回后调用) */
  refresh: () => Promise<void>;
}

/**
 * 首次启动 OS 兼容性检查。在 App 根组件挂载一次 (经 OsCompatibilityBanner)。
 * 失败不抛错: check_os_compatibility 内部已 catch, 此处再兜一层, report 保持 null。
 */
export function useOsCompatibilityCheck(
  opts: OsCompatibilityCheckOptions,
): OsCompatibilityCheckState {
  const { interactiveShellReady, startupOverlayVisible } = opts;
  const [report, setReport] = useState<OsCompatibilityReport | null>(null);
  const [loading, setLoading] = useState(false);
  const ranRef = useRef(false);

  const refresh = useCallback(async () => {
    if (!isTauriRuntime()) {
      return;
    }
    setLoading(true);
    try {
      const r = await checkOsCompatibility();
      setReport(r);
    } catch (error) {
      // checkOsCompatibility 内部已 catch, 此处仅作防御性兜底。
      log.warn('useOsCompatibilityCheck refresh failed', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    if (!interactiveShellReady || startupOverlayVisible) {
      return;
    }
    if (ranRef.current) {
      return;
    }
    ranRef.current = true;
    void refresh();
  }, [interactiveShellReady, startupOverlayVisible, refresh]);

  return { report, loading, refresh };
}
