import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}));

import * as logger from "firebase-functions/logger";
import * as safeLog from "./safeLog";
import type { SafeLogPayload } from "./safeLog";

describe("safeLog", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it.each([
    ["steamId", { steamId: "76561198000000000" }],
    ["gameid", { gameid: "440" }],
    ["gameName", { gameName: "Team Fortress 2" }],
  ])("strips %s from an info payload", (field, identityPayload) => {
    // Bypass the compile-time guard to prove the runtime backstop holds too.
    safeLog.info("message", identityPayload as unknown as SafeLogPayload);

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(payload).not.toHaveProperty(field);
  });

  it("keeps non-identity fields alongside a stripped identity field", () => {
    safeLog.info("poll ok", {
      outcome: "written",
      steamId: "76561198000000000",
    } as unknown as SafeLogPayload);

    expect(logger.info).toHaveBeenCalledWith("poll ok", { outcome: "written" });
  });

  it("emits without a payload argument when nothing is passed", () => {
    safeLog.error("STEAM_ID is not configured; nothing to poll");

    expect(logger.error).toHaveBeenCalledWith(
      "STEAM_ID is not configured; nothing to poll",
    );
  });

  it("passes clean payloads through unmodified", () => {
    safeLog.warn("Profile is not public", { communityvisibilitystate: 1 });

    expect(logger.warn).toHaveBeenCalledWith("Profile is not public", {
      communityvisibilitystate: 1,
    });
  });

  it("rejects an identity field at compile time without a cast", () => {
    // @ts-expect-error steamId is disallowed by SafeLogPayload — the type
    // system refuses this call before the redaction ever runs.
    safeLog.info("message", { steamId: "abc" });
  });

  it("strips identity fields regardless of log level", () => {
    safeLog.warn("message", { gameid: "440" } as unknown as SafeLogPayload);
    safeLog.error("message", { gameName: "Hades" } as unknown as SafeLogPayload);

    expect(vi.mocked(logger.warn).mock.calls[0][1]).not.toHaveProperty("gameid");
    expect(vi.mocked(logger.error).mock.calls[0][1]).not.toHaveProperty(
      "gameName",
    );
  });
});
