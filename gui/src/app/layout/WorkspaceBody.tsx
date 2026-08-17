import { NavBar } from "../components/NavBar/NavBar";
import { NavPanel } from "../components/NavPanel/NavPanel";
import { SceneBar } from "../components/SceneBar/SceneBar";
import { WelcomeScene } from "../scenes/welcome/WelcomeScene";
import { SessionScene } from "../scenes/session/SessionScene";
import { SettingsScene } from "../scenes/settings/SettingsScene";
import { useAppStore } from "../stores/appStore";
import "./WorkspaceBody.scss";

export function WorkspaceBody() {
  const collapsed = useAppStore((s) => s.navCollapsed);
  const scene = useAppStore((s) => s.activeScene);
  return (
    <div className={`dshg-workspace ${collapsed ? "is-collapsed" : ""}`}>
      <div className="dshg-workspace__nav">
        <NavBar />
        {!collapsed && <NavPanel />}
      </div>
      <div className="dshg-workspace__scene">
        <SceneBar />
        <div className="dshg-workspace__viewport">
          {scene === "welcome" && <WelcomeScene />}
          {scene === "session" && <SessionScene />}
          {scene === "settings" && <SettingsScene />}
        </div>
      </div>
    </div>
  );
}
