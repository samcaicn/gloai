/**
 * @vitest-environment jsdom
 */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/infrastructure/i18n";
import { SplashScreen } from "./SplashScreen";

describe("SplashScreen", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    vi.useFakeTimers();
  });

  afterEach(() => {
    act(() => {
      root.unmount();
    });
    container.remove();
    vi.useRealTimers();
  });

  it("shows the delayed loading message, then calls onExited after the exit animation", () => {
    const onExited = vi.fn();
    act(() => {
      root.render(
        <I18nProvider locale="zh">
          <SplashScreen isExiting={false} onExited={onExited} delayedMessageMs={50} />
        </I18nProvider>,
      );
    });
    expect(container.textContent).not.toContain("正在加载…");
    act(() => {
      vi.advanceTimersByTime(50);
    });
    expect(container.textContent).toContain("正在加载…");

    act(() => {
      root.render(
        <I18nProvider locale="zh">
          <SplashScreen isExiting onExited={onExited} delayedMessageMs={50} />
        </I18nProvider>,
      );
    });
    expect(onExited).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(650);
    });
    expect(onExited).toHaveBeenCalledTimes(1);
  });
});
