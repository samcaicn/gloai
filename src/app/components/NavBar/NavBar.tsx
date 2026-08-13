import { useCallback, useEffect, useState, type MouseEvent } from "react";
import { PanelLeft } from "lucide-react";
import { isMacOSDesktopRuntime, supportsNativeWindowDragging } from "@/infrastructure/runtime";
import { useI18n } from "@/infrastructure/i18n";
import { createLogger } from "@/infrastructure/logger";
import { WindowControls } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import "./NavBar.scss";

const log = createLogger("NavBar");
const INTERACTIVE =
  "button, input, textarea, select, a, [role='button'], .window-controls";

export function NavBar() {
  const { t } = useI18n();
  const host = useAppStore((s) => s.host);
  const navCollapsed = useAppStore((s) => s.navCollapsed);
  const toggleNav = useAppStore((s) => s.toggleNav);
  const isMacOS = isMacOSDesktopRuntime();
  const canDrag = supportsNativeWindowDragging();
  const [isMaximized, setMaximized] = useState(false);

  useEffect(() => {
    void host.isMaximized().then(setMaximized);
  }, [host]);

  const onDrag = useCallback(
      (event: MouseEvent) => {
      if (!canDrag || event.button !== 0) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest(INTERACTIVE)) return;
      void host.startDragging().catch((error) => log.error("drag failed", error));
    },
    [canDrag, host],
  );

  const root = [
    "dshg-nav-bar",
    navCollapsed ? "dshg-nav-bar--collapsed" : "",
    isMacOS ? "dshg-nav-bar--macos" : "",
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={root} role="toolbar" aria-label={t("nav.aria")} onMouseDown={onDrag}>
      <button
        type="button"
        className="dshg-nav-bar__btn"
        aria-label={navCollapsed ? t("nav.expand") : t("nav.collapse")}
        onClick={toggleNav}
      >
        <PanelLeft size={13} />
      </button>
      <div className="dshg-nav-bar__drag" />
      {!isMacOS && (
        <WindowControls
          isMaximized={isMaximized}
          onMinimize={() => void host.minimizeWindow()}
          onMaximize={() => {
            void host.toggleMaximizeWindow().then(async () => {
              setMaximized(await host.isMaximized());
            });
          }}
          onClose={() => void host.closeWindow()}
        />
      )}
    </div>
  );
}
