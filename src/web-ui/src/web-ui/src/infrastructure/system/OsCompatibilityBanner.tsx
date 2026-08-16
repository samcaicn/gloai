// macOS Accessibility 权限缺失提示 — 显示在设置页面顶部。
//
// 渲染规则:
//   - macOS 且 macosAccessibilityGranted === false → 辅助功能权限横幅
//   - 其他情况 → 不渲染
//
// 与 WindowsOcrBanner 布局一致, 内联渲染在设置页面。
// 用户点击「前往系统设置」后 2s 自动 refresh, 权限授予后自动消失。
import { useRef, useEffect, useState, useCallback } from 'react';
import { checkOsCompatibility, openOsPermissionPanel, type OsCompatibilityReport } from './osCompatibility';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { createLogger } from '@/shared/utils/logger';
import './OsCompatibilityBanner.scss';

const log = createLogger('OsCompatibilityBanner');

export function OsCompatibilityBanner() {
  const [report, setReport] = useState<OsCompatibilityReport | null>(null);
  const [loading, setLoading] = useState(false);
  const refreshScheduledRef = useRef(false);

  const refresh = useCallback(async () => {
    if (!isTauriRuntime()) {
      return;
    }
    setLoading(true);
    try {
      const r = await checkOsCompatibility();
      setReport(r);
    } catch (error) {
      log.warn('refresh failed', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleOpenSettings = async () => {
    await openOsPermissionPanel('macos-accessibility');
    if (refreshScheduledRef.current) {
      return;
    }
    refreshScheduledRef.current = true;
    window.setTimeout(() => {
      refreshScheduledRef.current = false;
      void refresh();
    }, 2000);
  };

  if (!report || loading) {
    return null;
  }

  if (report.macosAccessibilityGranted) {
    return null;
  }

  return (
    <div className="os-compat-banner" role="alert">
      <div className="os-compat-banner__icon">⚠️</div>
      <div className="os-compat-banner__text">
        <strong className="os-compat-banner__title">需要辅助功能权限</strong>
        <span className="os-compat-banner__desc">
          授权后才能支持自动操作与全局快捷键。打开「系统设置 → 隐私与安全性 → 辅助功能」并启用本应用。
        </span>
      </div>
      <button
        type="button"
        className="os-compat-banner__btn"
        onClick={() => void handleOpenSettings()}
      >
        前往系统设置
      </button>
    </div>
  );
}
