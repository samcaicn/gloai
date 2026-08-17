import { describe, expect, it } from "vitest";
import { resolveLauncher, smokeDshWeb } from "./dshWebSmoke";

const READY_MS = 90_000;
const runSmoke = process.env.DSH_GUI_SMOKE === "1";

describe.skipIf(!runSmoke)("dsh web loopback smoke", () => {
  it("prints a loopback URL and serves HTTP 200", async (ctx) => {
    if (!resolveLauncher()) {
      ctx.skip();
      return;
    }
    const result = await smokeDshWeb();
    expect(result.url).toMatch(/^http:\/\/(?:127\.0\.0\.1|localhost):\d+$/);
    expect(result.status).toBe(200);
  }, READY_MS);
});
