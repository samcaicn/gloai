// Windows OCR 语言包缺失提示 — 显示在设置页面顶部。
//
// 渲染规则:
//   - Windows 且 windowsOcrAvailable === false → 显示 OCR 语言包提示
//   - 其他情况 → 不渲染
//
// 与 OsCompatibilityBanner (全局 fixed 横幅) 不同，此组件是内联渲染，
// 只在设置页面内展示，不干扰用户在首页的体验。
//
// 用户点击「前往设置」后 2s 自动 refresh，权限授予后提示自动消失。
import { useRef, useEffect, useState, useCallback } from 'react';
import { checkOsCompatibility, openOsPermissionPanel, type OsCompatibilityReport } from './osCompatibility';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { createLogger } from '@/shared/utils/logger';
import './WindowsOcrBanner.scss';

const log = createLogger('WindowsOcrBanner');

export function WindowsOcrBanner() {
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
      log.warn('WindowsOcrBanner refresh failed', error);
    } finally {
      setLoading(false);
    }
  }, []);

  // 挂载时检查一次
  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleOpenSettings = async () => {
    await openOsPermissionPanel('windows-ocr');
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

  if (report.windowsOcrAvailable) {
    return null;
  }

  return (
    <div className="win-ocr-banner" role="alert">
      <div className="win-ocr-banner__icon">⚠️</div>
      <div className="win-ocr-banner__text">
        <strong className="win-ocr-banner__title">未安装 OCR 语言包</strong>
        <span className="win-ocr-banner__desc">
          文字识别 (OCR) 将不可用。打开「设置 → 时间和语言 → 语言」添加带 OCR 支持的语言包。
        </span>
      </div>
      <button
        type="button"
        className="win-ocr-banner__btn"
        onClick={() => void handleOpenSettings()}
      >
        前往设置
      </button>
    </div>
  );
}
