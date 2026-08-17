import { useEffect, useState } from "react";
import { useI18n } from "@/infrastructure/i18n";
import { Button } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import type { DoctorReport, Locale, Theme } from "@/shared/types";
import "./SettingsScene.scss";

export function SettingsScene() {
  const { t } = useI18n();
  const host = useAppStore((s) => s.host);
  const theme = useAppStore((s) => s.theme);
  const locale = useAppStore((s) => s.locale);
  const closeToTray = useAppStore((s) => s.closeToTray);
  const harnessCommand = useAppStore((s) => s.harnessCommand);
  const hasApiKey = useAppStore((s) => s.hasApiKey);
  const applySettings = useAppStore((s) => s.applySettings);
  const saveApiKey = useAppStore((s) => s.saveApiKey);
  const needKey = useAppStore((s) => s.errorBanner === "need-key");
  const [keyDraft, setKeyDraft] = useState("");
  const [commandDraft, setCommandDraft] = useState(harnessCommand);
  const [doctor, setDoctor] = useState<DoctorReport | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setCommandDraft(harnessCommand);
  }, [harnessCommand]);

  useEffect(() => {
    void host.doctor().then(setDoctor);
  }, [host]);

  return (
    <div className="dshg-settings">
      <h1>{t("settings.title")}</h1>

      {needKey && <p className="dshg-settings__warn">{t("welcome.needKey")}</p>}

      <section>
        <label>
          {t("settings.apiKey")}
          <span>
            {hasApiKey ? t("settings.apiKeyPresent") : t("settings.apiKeyMissing")}
          </span>
        </label>
        <p className="dshg-settings__hint">{t("settings.apiKeyHint")}</p>
        <input
          type="password"
          autoComplete="off"
          value={keyDraft}
          onChange={(event) => setKeyDraft(event.target.value)}
          placeholder="sk-..."
        />
        <div className="dshg-settings__row">
          <Button
            variant="primary"
            disabled={!keyDraft.trim() || busy}
            onClick={() => {
              setBusy(true);
              void saveApiKey(keyDraft.trim()).finally(() => {
                setKeyDraft("");
                setBusy(false);
                useAppStore.setState({ errorBanner: null });
              });
            }}
          >
            {t("settings.saveKey")}
          </Button>
          <Button disabled={!hasApiKey || busy} onClick={() => void saveApiKey(null)}>
            {t("settings.clearKey")}
          </Button>
        </div>
      </section>

      <section>
        <label>{t("settings.command")}</label>
        <input
          value={commandDraft}
          placeholder={t("settings.commandPlaceholder")}
          onChange={(event) => setCommandDraft(event.target.value)}
        />
        <Button
          onClick={() => void applySettings({ harnessCommand: commandDraft.trim() })}
        >
          {t("settings.save")}
        </Button>
      </section>

      <section>
        <label>{t("settings.theme")}</label>
        <select
          value={theme}
          onChange={(event) => void applySettings({ theme: event.target.value as Theme })}
        >
          <option value="dark">{t("settings.theme.dark")}</option>
          <option value="light">{t("settings.theme.light")}</option>
          <option value="system">{t("settings.theme.system")}</option>
        </select>
      </section>

      <section>
        <label>{t("settings.locale")}</label>
        <select
          value={locale}
          onChange={(event) => void applySettings({ locale: event.target.value as Locale })}
        >
          <option value="zh">{t("settings.locale.zh")}</option>
          <option value="en">{t("settings.locale.en")}</option>
        </select>
      </section>

      <section>
        <label className="dshg-settings__check">
          <input
            type="checkbox"
            checked={closeToTray}
            onChange={(event) => void applySettings({ closeToTray: event.target.checked })}
          />
          {t("settings.closeToTray")}
        </label>
      </section>

      <section>
        <div className="dshg-settings__row">
          <h2>{t("settings.doctor")}</h2>
          <Button onClick={() => void host.doctor().then(setDoctor)}>
            {t("settings.runDoctor")}
          </Button>
        </div>
        {doctor && (
          <dl>
            <dt>node</dt>
            <dd>{doctor.node.version ?? doctor.node.error ?? "—"}</dd>
            <dt>dsh</dt>
            <dd>{doctor.dsh.version ?? doctor.dsh.error ?? "—"}</dd>
            <dt>npx</dt>
            <dd>{doctor.npx.version ?? doctor.npx.error ?? "—"}</dd>
            <dt>{t("settings.keyring")}</dt>
            <dd>{doctor.keyring ? "ok" : "fallback"}</dd>
            <dt>{t("settings.launch")}</dt>
            <dd>
              {doctor.launchSource}: {doctor.launchProgram} {doctor.launchArgs.join(" ")}
            </dd>
          </dl>
        )}
      </section>
    </div>
  );
}
