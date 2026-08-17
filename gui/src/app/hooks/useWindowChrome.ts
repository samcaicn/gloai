import { useCallback, useEffect, useRef, useState, type MouseEvent } from "react";
import { useAppStore } from "@/app/stores/appStore";
import {
  isMacOSDesktopRuntime,
  supportsNativeWindowDragging,
} from "@/infrastructure/runtime";
import { createLogger } from "@/infrastructure/logger";

const log = createLogger("window-chrome");
const INTERACTIVE =
  "button, input, textarea, select, a, [role='button'], .window-controls";

export function useWindowChrome() {
  const host = useAppStore((state) => state.host);
  const canDrag = supportsNativeWindowDragging();
  const isMacOS = isMacOSDesktopRuntime();
  const [isMaximized, setMaximized] = useState(false);
  const lastMouseDownAt = useRef(0);

  useEffect(() => {
    void host.isMaximized().then(setMaximized);
    return host.subscribeMaximized(setMaximized);
  }, [host]);

  const onDrag = useCallback(
    (event: MouseEvent) => {
      if (!canDrag || event.button !== 0) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest(INTERACTIVE)) return;
      if (target?.closest("[data-tauri-drag-region]")) return;
      const now = Date.now();
      const sinceLast = now - lastMouseDownAt.current;
      lastMouseDownAt.current = now;
      if (sinceLast < 500 && sinceLast > 50) return;
      void host.startDragging().catch((error) => log.error("drag failed", error));
    },
    [canDrag, host],
  );

  const onDoubleClick = useCallback(
    (event: MouseEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest(INTERACTIVE)) return;
      if (isMacOS && target?.closest("[data-tauri-drag-region]")) return;
      void host.toggleMaximizeWindow();
    },
    [host, isMacOS],
  );

  return { host, isMaximized, onDrag, onDoubleClick };
}
