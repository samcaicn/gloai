import { Clock, FolderOpen, Globe, RotateCw, Settings, Square } from "lucide-react";
import { useI18n } from "@/infrastructure/i18n";
import { Button } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import "./NavPanel.scss";

export function NavPanel() {
  const { t } = useI18n();
  const workspacePath = useAppStore((s) => s.workspacePath);
  const recent = useAppStore((s) => s.recentWorkspaces);
  const harness = useAppStore((s) => s.harness);
  const setScene = useAppStore((s) => s.setScene);
  const openWorkspace = useAppStore((s) => s.openWorkspace);
  const restartHarness = useAppStore((s) => s.restartHarness);
  const stopRuntime = useAppStore((s) => s.stopRuntime);
  const host = useAppStore((s) => s.host);
  const name = workspacePath?.split(/[\\/]/).filter(Boolean).at(-1);
  const others = recent.filter((item) => item.path !== workspacePath).slice(0, 6);

  return (
    <aside className="dshg-nav-panel">
      <section>
        <div className="dshg-nav-panel__label">{t("panel.workspace")}</div>
        <div className="dshg-nav-panel__workspace" title={workspacePath ?? undefined}>
          {name ?? t("panel.noWorkspace")}
        </div>
        <Button onClick={() => void openWorkspace()}>
          <FolderOpen size={14} />
          {t("welcome.open")}
        </Button>
      </section>
      {others.length > 0 && (
        <section>
          <div className="dshg-nav-panel__label">
            <Clock size={11} />
            {t("welcome.recent")}
          </div>
          <div className="dshg-nav-panel__recents">
            {others.map((item) => (
              <button
                key={item.path}
                type="button"
                title={item.path}
                onClick={() => void openWorkspace(item.path)}
              >
                {item.name}
              </button>
            ))}
          </div>
        </section>
      )}
      <section>
        <div className="dshg-nav-panel__label">{t("panel.runtime")}</div>
        <div className={`dshg-nav-panel__status is-${harness.state}`}>
          {t(
            `panel.${harness.state === "starting" ? "starting" : harness.state === "ready" ? "ready" : harness.state === "error" ? "error" : "idle"}`,
          )}
        </div>
        <div className="dshg-nav-panel__actions">
          <Button disabled={!workspacePath} onClick={() => void restartHarness()}>
            <RotateCw size={14} />
            {t("panel.restart")}
          </Button>
          <Button disabled={harness.state === "idle"} onClick={() => void stopRuntime()}>
            <Square size={14} />
            {t("panel.stop")}
          </Button>
          <Button
            disabled={!harness.url}
            onClick={() => harness.url && void host.openExternal(harness.url)}
          >
            <Globe size={14} />
            {t("panel.openBrowser")}
          </Button>
        </div>
      </section>
      <section className="dshg-nav-panel__scenes">
        <button type="button" onClick={() => setScene("welcome")}>
          {t("nav.welcome")}
        </button>
        <button type="button" onClick={() => setScene("session")}>
          {t("nav.session")}
        </button>
        <button type="button" onClick={() => setScene("settings")}>
          <Settings size={14} />
          {t("nav.settings")}
        </button>
      </section>
    </aside>
  );
}
