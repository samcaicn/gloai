import { useMemo, useState } from "react";
import { Clock, FolderOpen, Trash2 } from "lucide-react";
import { useI18n } from "@/infrastructure/i18n";
import { Button, FishLogo } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import "./WelcomeScene.scss";

const GREETINGS = [
  "welcome.greeting1",
  "welcome.greeting2",
  "welcome.greeting3",
  "welcome.greeting4",
] as const;

export function WelcomeScene() {
  const { t } = useI18n();
  const recent = useAppStore((s) => s.recentWorkspaces);
  const openWorkspace = useAppStore((s) => s.openWorkspace);
  const host = useAppStore((s) => s.host);
  const [busy, setBusy] = useState(false);
  const greeting = useMemo(
    () => GREETINGS[Math.floor(Math.random() * GREETINGS.length)],
    [],
  );

  async function open(path?: string) {
    setBusy(true);
    try {
      await openWorkspace(path);
    } finally {
      setBusy(false);
    }
  }

  async function remove(path: string) {
    const next = recent.filter((entry) => entry.path !== path);
    const settings = await host.saveSettings({ recentWorkspaces: next });
    useAppStore.setState({ recentWorkspaces: settings.recentWorkspaces });
  }

  return (
    <div className="dshg-welcome">
      <div className="dshg-welcome__content">
        <header className="dshg-welcome__greeting">
          <FishLogo size={34} />
          <h1>{t("app.title")}</h1>
          <p>{t(greeting)}</p>
        </header>
        <div className="dshg-welcome__divider" />
        <section className="dshg-welcome__switch">
          <div className="dshg-welcome__switch-header">
            <span className="dshg-welcome__section-label">
              <Clock size={12} />
              {t("welcome.recent")}
            </span>
            <Button variant="primary" disabled={busy} onClick={() => void open()}>
              <FolderOpen size={14} />
              {busy ? t("welcome.opening") : t("welcome.open")}
            </Button>
          </div>
          {recent.length === 0 ? (
            <p className="dshg-welcome__empty">{t("welcome.empty")}</p>
          ) : (
            <ul>
              {recent.map((item) => (
                <li key={item.path}>
                  <button type="button" disabled={busy} onClick={() => void open(item.path)}>
                    <strong>{item.name}</strong>
                    <span>{item.path}</span>
                  </button>
                  <button
                    type="button"
                    className="dshg-welcome__remove"
                    aria-label="remove"
                    onClick={() => void remove(item.path)}
                  >
                    <Trash2 size={13} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
        <p className="dshg-welcome__credit">
          <button
            type="button"
            onClick={() => void host.openExternal("https://github.com/GCWing/BitFun/")}
          >
            {t("welcome.madeBy")}
          </button>
        </p>
      </div>
    </div>
  );
}
