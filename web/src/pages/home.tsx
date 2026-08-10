import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Dialog, DialogContent, DialogTitle } from "../components/ui/dialog";
import { LoginCard } from "../components/login-card";
import { useUser } from "@/hooks/use-auth";

export function HomePage() {
  const navigate = useNavigate();
  const { data: user, isLoading } = useUser();
  const loggedIn = isLoading ? null : !!user;

  const [loginOpen, setLoginOpen] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const pendingRef = useRef(false);

  const openLogin = () => {
    if (loggedIn) {
      navigate("/dashboard");
    } else if (loggedIn === null) {
      pendingRef.current = true;
    } else {
      setLoginOpen(true);
    }
  };

  useEffect(() => {
    if (loggedIn !== null && pendingRef.current) {
      pendingRef.current = false;
      if (loggedIn) navigate("/dashboard");
      else setLoginOpen(true);
    }
  }, [loggedIn]);

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (!e.data || e.data.type !== "tuptup-open-login") return;
      if (e.source && iframeRef.current && e.source !== iframeRef.current.contentWindow) return;
      openLogin();
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [loggedIn]);

  return (
    <div className="relative h-dvh w-full overflow-hidden bg-background">
      <iframe
        ref={iframeRef}
        src="/tuptup/index.html"
        title="拓谱人工智能"
        className="h-full w-full border-0"
      />

      {/* 登录浮窗 */}
      <Dialog open={loginOpen} onOpenChange={setLoginOpen}>
        <DialogContent
          className="max-w-[420px] gap-0 rounded-2xl border-border/50 bg-background p-6 shadow-2xl max-h-[92vh] overflow-y-auto"
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <DialogTitle className="sr-only">登录 CEOadmin Hub</DialogTitle>
          <LoginCard />
        </DialogContent>
      </Dialog>
    </div>
  );
}
