import { describe, expect, it } from "vitest";
import { createHostAdapter } from "./host";
import { webAdapter } from "./web";
import { isTauriRuntime } from "@/infrastructure/runtime";

describe("createHostAdapter", () => {
  it("uses the web adapter outside Tauri", () => {
    expect(isTauriRuntime()).toBe(false);
    expect(createHostAdapter()).toBe(webAdapter);
    expect(createHostAdapter().isNative).toBe(false);
  });
});
