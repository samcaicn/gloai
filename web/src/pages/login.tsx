import { LoginCard } from "../components/login-card";
import { HexagonBackground } from "../components/ui/hexagon-background";

export function LoginPage() {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-12">
      <HexagonBackground className="opacity-20" hexagonSize={60} hexagonMargin={4} />
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,transparent_0%,hsl(var(--background))_100%)]" />

      <div className="relative z-10 w-full max-w-[420px] animate-in fade-in zoom-in-95 duration-500">
        <LoginCard />
        <footer className="mt-8 text-center text-[11px] text-muted-foreground/50 font-medium">
          &copy; 2026 CEOadmin Hub 项目保留所有权利。
        </footer>
      </div>
    </div>
  );
}
