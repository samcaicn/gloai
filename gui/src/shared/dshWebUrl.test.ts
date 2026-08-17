import { describe, expect, it } from "vitest";
import { parseDshWebUrl } from "./dshWebUrl";

describe("parseDshWebUrl", () => {
  it("accepts a plain loopback URL", () => {
    expect(parseDshWebUrl("dsh web: http://127.0.0.1:4567")).toBe("http://127.0.0.1:4567");
  });

  it("keeps the loopback URL when a LAN suffix is present", () => {
    expect(
      parseDshWebUrl("dsh web: http://127.0.0.1:4567 (LAN: http://192.168.1.5:4567)"),
    ).toBe("http://127.0.0.1:4567");
  });

  it("accepts localhost", () => {
    expect(parseDshWebUrl("  dsh web: http://localhost:3080  ")).toBe("http://localhost:3080");
  });

  it("rejects non-loopback and non-matching lines", () => {
    expect(parseDshWebUrl("dsh web: http://192.168.1.5:4567")).toBeNull();
    expect(parseDshWebUrl("ready on http://127.0.0.1:4567")).toBeNull();
    expect(parseDshWebUrl("dsh web: https://127.0.0.1:4567")).toBeNull();
  });
});
