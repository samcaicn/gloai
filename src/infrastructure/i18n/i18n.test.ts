import { describe, expect, it } from "vitest";
import { zh } from "./zh";
import { en } from "./en";

describe("i18n dictionaries", () => {
  it("keeps English keys aligned with Chinese", () => {
    const zhKeys = Object.keys(zh).sort();
    const enKeys = Object.keys(en).sort();
    expect(enKeys).toEqual(zhKeys);
  });

  it("has no empty product strings", () => {
    for (const [key, value] of Object.entries({ ...zh, ...en })) {
      expect(value.trim().length, key).toBeGreaterThan(0);
    }
  });
});
