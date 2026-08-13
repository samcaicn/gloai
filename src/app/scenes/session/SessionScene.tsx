import { useI18n } from "@/infrastructure/i18n";
import { Button } from "@/component-library";
import { useAppStore } from "@/app/stores/appStore";
import "./SessionScene.scss";

export function SessionScene() {
  const { t } = useI18n();
  const harness = useAppStore((s) => s.harness);
  const logs = useAppStore((s) => s.logs);
  const restartHarness = useAppStore((s) => s.restartHarness);
  const workspacePath = useAppStore((s) => s.workspacePath);

  if (!workspacePath && harness.state === "idle") {
    return (
      <div className="dshg-session dshg-session--empty">
        <p>{t("session.empty")}</p>
      </div>
    );
  }

  if (harness.state === "ready" && harness.url) {
    return (
      <div className="dshg-session">
        <iframe
          className="dshg-session__frame"
          title="dsh web"
          src={harness.url}
          allow="clipboard-read; clipboard-write"
        />
      </div>
    );
  }

  return (
    <div className="dshg-session dshg-session--status">
      <p>{harness.error ?? t("session.loading")}</p>
      {logs.length > 0 && <pre>{logs.slice(-12).join("\n")}</pre>}
      {(harness.state === "error" || workspacePath) && (
        <Button variant="primary" onClick={() => void restartHarness()}>
          {t("session.retry")}
        </Button>
      )}
    </div>
  );
}
