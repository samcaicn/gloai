import { describe, expect, it } from "vitest";
import { IDLE_HARNESS } from "@/shared/types";
import { useAppStore } from "./appStore";

describe("app store scene switching", () => {
  it("defaults to the welcome scene", () => {
    expect(useAppStore.getState().activeScene).toBe("welcome");
    expect(useAppStore.getState().harness).toEqual(IDLE_HARNESS);
  });

  it("toggles the nav rail and switches scenes locally", () => {
    const collapsed = useAppStore.getState().navCollapsed;
    useAppStore.getState().toggleNav();
    expect(useAppStore.getState().navCollapsed).toBe(!collapsed);
    useAppStore.getState().setScene("settings");
    expect(useAppStore.getState().activeScene).toBe("settings");
  });
});
