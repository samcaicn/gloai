import { FolderOpen, RotateCw, Settings, Square, Globe } from "lucide-react";
import { useI18n } from "@/infrastructure/i18n";
import { Button } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import "./NavPanel.scss";

export function NavPanel() {
  const { t } = useI18n();
  const workspacePath = useAppStore((s) => s.workspacePath);
  const harness = useAppStore((s) => s.harness);
  const setScene = useAppStore((s) => s.setScene);
  const openWorkspace = useAppStore((s) => s.openWorkspace);
  const restartHarness = useAppStore((s) => s.restartHarness);
  const stopRuntime = useAppStore((s) => s.stopRuntime);
  const host = useAppStore((s) => s.host);
  const name = workspacePath?.split(/[\\/]/).filter(Boolean).at(-1);

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
      <section>
        <div className="dshg-nav-panel__label">{t("panel.runtime")}</div>
        <div className={`dshg-nav-panel__status is-${harness.state}`}>
          {t(`panel.${harness.state === "starting" ? "starting" : harness.state === "ready" ? "ready" : harness.state === "error" ? "error" : "idle"}`)}
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
