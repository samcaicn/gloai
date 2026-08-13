import { useEffect } from "react";
import { I18nProvider } from "@/infrastructure/i18n";
import { AppLayout } from "./layout/AppLayout";
import { useAppStore } from "./stores/appStore";

function applyTheme(theme: string) {
  document.documentElement.dataset.theme = theme;
}

export default function App() {
  const ready = useAppStore((s) => s.ready);
  const locale = useAppStore((s) => s.locale);
  const theme = useAppStore((s) => s.theme);
  const bootstrap = useAppStore((s) => s.bootstrap);

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  if (!ready) {
    return <div className="dshg-app-layout" />;
  }

  return (
    <I18nProvider locale={locale}>
      <AppLayout />
    </I18nProvider>
  );
}
