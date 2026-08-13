import { PanelLeft } from "lucide-react";
import { isMacOSDesktopRuntime } from "@/infrastructure/runtime";
import { useI18n } from "@/infrastructure/i18n";
import { WindowControls } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import { useWindowChrome } from "@/app/hooks/useWindowChrome";
import "./NavBar.scss";

export function NavBar() {
  const { t } = useI18n();
  const navCollapsed = useAppStore((s) => s.navCollapsed);
  const toggleNav = useAppStore((s) => s.toggleNav);
  const isMacOS = isMacOSDesktopRuntime();
  const { host, isMaximized, onDrag, onDoubleClick } = useWindowChrome();

  const root = [
    "dshg-nav-bar",
    navCollapsed ? "dshg-nav-bar--collapsed" : "",
    isMacOS ? "dshg-nav-bar--macos" : "",
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div
      className={root}
      role="toolbar"
      aria-label={t("nav.aria")}
      onMouseDown={onDrag}
      onDoubleClick={onDoubleClick}
    >
      <button
        type="button"
        className="dshg-nav-bar__btn"
        aria-label={navCollapsed ? t("nav.expand") : t("nav.collapse")}
        onClick={toggleNav}
      >
        <PanelLeft size={13} />
      </button>
      <div className="dshg-nav-bar__drag" data-tauri-drag-region />
      {!isMacOS && (
        <WindowControls
          isMaximized={isMaximized}
          onMinimize={() => void host.minimizeWindow()}
          onMaximize={() => void host.toggleMaximizeWindow()}
          onClose={() => void host.closeWindow()}
        />
      )}
    </div>
  );
}
