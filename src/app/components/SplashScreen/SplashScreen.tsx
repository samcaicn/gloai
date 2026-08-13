import { useCallback, useEffect, useState } from "react";
import { FishLogo } from "@/component-library";
import { useI18n } from "@/infrastructure/i18n";
import "./SplashScreen.scss";

const DEFAULT_LOADING_MESSAGE_DELAY_MS = 1800;
const EXIT_MS = 650;

export function SplashScreen({
  isExiting,
  onExited,
  delayedMessageMs = DEFAULT_LOADING_MESSAGE_DELAY_MS,
}: {
  isExiting: boolean;
  onExited: () => void;
  delayedMessageMs?: number;
}) {
  const { t } = useI18n();
  const [showDelayedMessage, setShowDelayedMessage] = useState(false);
  const handleExited = useCallback(() => {
    onExited();
  }, [onExited]);

  useEffect(() => {
    setShowDelayedMessage(false);
    if (isExiting) return;
    const timer = window.setTimeout(() => {
      setShowDelayedMessage(true);
    }, delayedMessageMs);
    return () => window.clearTimeout(timer);
  }, [delayedMessageMs, isExiting]);

  useEffect(() => {
    if (!isExiting) return;
    const timer = window.setTimeout(handleExited, EXIT_MS);
    return () => window.clearTimeout(timer);
  }, [isExiting, handleExited]);

  return (
    <div
      className={`dshg-splash${isExiting ? " dshg-splash--exiting" : ""}`}
      aria-hidden={!showDelayedMessage}
    >
      <div className="dshg-splash__center">
        <div className="dshg-splash__logo-wrap">
          <div className="dshg-splash__logo">
            <FishLogo size={64} />
          </div>
        </div>
        {showDelayedMessage && !isExiting && (
          <div className="dshg-splash__message dshg-splash__message--visible" role="status" aria-live="polite">
            {t("splash.loading")}
          </div>
        )}
      </div>
    </div>
  );
}
