import { MessageSquare, Settings2, Sparkles } from "lucide-react";
import { useI18n } from "@/infrastructure/i18n";
import { useAppStore } from "@/app/stores/appStore";
import type { SceneId } from "@/shared/types";
import "./SceneBar.scss";

const TABS: { id: SceneId; icon: typeof Sparkles; label: "nav.welcome" | "nav.session" | "nav.settings" }[] = [
  { id: "welcome", icon: Sparkles, label: "nav.welcome" },
  { id: "session", icon: MessageSquare, label: "nav.session" },
  { id: "settings", icon: Settings2, label: "nav.settings" },
];

export function SceneBar() {
  const { t } = useI18n();
  const active = useAppStore((s) => s.activeScene);
  const setScene = useAppStore((s) => s.setScene);
  return (
    <div className="dshg-scene-bar" role="tablist">
      {TABS.map((tab) => {
        const Icon = tab.icon;
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={active === tab.id}
            className={active === tab.id ? "is-active" : ""}
            onClick={() => setScene(tab.id)}
          >
            <Icon size={13} />
            {t(tab.label)}
          </button>
        );
      })}
    </div>
  );
}
