import { useEffect, useRef, useState } from "react";
import { I18nProvider } from "@/infrastructure/i18n";
import { AppLayout } from "./layout/AppLayout";
import { SplashScreen } from "./components/SplashScreen/SplashScreen";
import { useAppStore } from "./stores/appStore";

const MIN_SPLASH_MS = 650;

function applyTheme(theme: string) {
  document.documentElement.dataset.theme = theme;
}

export default function App() {
  const ready = useAppStore((s) => s.ready);
  const locale = useAppStore((s) => s.locale);
  const theme = useAppStore((s) => s.theme);
  const bootstrap = useAppStore((s) => s.bootstrap);
  const startedAt = useRef(
    typeof performance === "undefined" ? 0 : performance.now(),
  );
  const [splash, setSplash] = useState<"idle" | "exiting" | "gone">("idle");

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    if (!ready || splash !== "idle") return;
    const remaining = Math.max(0, MIN_SPLASH_MS - (performance.now() - startedAt.current));
    const timer = window.setTimeout(() => setSplash("exiting"), remaining);
    return () => window.clearTimeout(timer);
  }, [ready, splash]);

  return (
    <I18nProvider locale={locale}>
      {splash !== "gone" && (
        <SplashScreen isExiting={splash === "exiting"} onExited={() => setSplash("gone")} />
      )}
      {ready ? <AppLayout /> : <div className="dshg-app-layout" />}
    </I18nProvider>
  );
}
