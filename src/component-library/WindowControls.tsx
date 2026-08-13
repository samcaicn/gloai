import { useI18n } from "@/infrastructure/i18n";
import { Tooltip } from "./Tooltip";
import "./WindowControls.scss";

export function WindowControls({
  isMaximized,
  onMinimize,
  onMaximize,
  onClose,
}: {
  isMaximized: boolean;
  onMinimize: () => void;
  onMaximize: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="window-controls" data-dshg-component="window-controls">
      <Tooltip content={t("window.minimize")}>
        <button type="button" className="window-controls__btn" aria-label={t("window.minimize")} onClick={onMinimize}>
          <svg width="10" height="10" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <line x1="3" y1="7" x2="11" y2="7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </button>
      </Tooltip>
      <Tooltip content={isMaximized ? t("window.restore") : t("window.maximize")}>
        <button
          type="button"
          className="window-controls__btn"
          aria-label={isMaximized ? t("window.restore") : t("window.maximize")}
          onClick={onMaximize}
        >
          {isMaximized ? (
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none" aria-hidden="true">
              <path
                d="M4 4 L4 1.5 Q4 1 4.5 1 L10.5 1 Q11 1 11 1.5 L11 7.5 Q11 8 10.5 8 L8 8"
                stroke="currentColor"
                strokeWidth="1.5"
                fill="none"
              />
              <rect x="1" y="4" width="7" height="7" rx="0.5" stroke="currentColor" strokeWidth="1.5" fill="none" />
            </svg>
          ) : (
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none" aria-hidden="true">
              <rect x="2" y="2" width="8" height="8" stroke="currentColor" strokeWidth="1.5" fill="none" />
            </svg>
          )}
        </button>
      </Tooltip>
      <Tooltip content={t("window.close")}>
        <button
          type="button"
          className="window-controls__btn window-controls__btn--close"
          aria-label={t("window.close")}
          onClick={onClose}
        >
          <svg width="10" height="10" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <line x1="3" y1="3" x2="11" y2="11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            <line x1="11" y1="3" x2="3" y2="11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </button>
      </Tooltip>
    </div>
  );
}
