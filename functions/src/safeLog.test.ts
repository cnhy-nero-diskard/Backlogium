import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}));

import * as logger from "firebase-functions/logger";
import * as safeLog from "./safeLog";
import type { SafeLogPayload } from "./safeLog";

describe("safeLog", () => {
  beforeEach(() => {
    safeLog.clearSensitiveValues();
  });

  afterEach(() => {
    vi.clearAllMocks();
    safeLog.clearSensitiveValues();
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

  it("scrubs a registered Steam ID smuggled in under another field name", () => {
    const steamId = "76561198000000099";
    safeLog.registerSensitive(steamId);
    safeLog.info("message", { reason: steamId });

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain(steamId);
    expect(payload).toEqual({ reason: "[redacted]" });
  });

  it("scrubs registered values nested inside objects and arrays", () => {
    const steamId = "76561198000000098";
    const gameName = "Nested Test Game";
    safeLog.registerSensitive(steamId, gameName);
    safeLog.info("message", {
      context: { steamId },
      tags: [steamId, "unrelated"],
      nested: { deep: { detail: `playing ${gameName}` } },
    });

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain(steamId);
    expect(JSON.stringify(payload)).not.toContain(gameName);
  });

  it("drops identity field names at any depth without registration", () => {
    safeLog.info("message", {
      context: { gameid: "440", outcome: "written" },
    } as unknown as SafeLogPayload);

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain("440");
    expect(payload).toEqual({ context: { outcome: "written" } });
  });

  it("scrubs registered values interpolated into the message text", () => {
    const steamId = "76561198000000097";
    const gameName = "Interpolated Test Game";
    safeLog.registerSensitive(steamId, gameName);
    safeLog.warn(`failed for ${steamId} while playing ${gameName}`);

    const [message] = vi.mocked(logger.warn).mock.calls[0];
    expect(message).not.toContain(steamId);
    expect(message).not.toContain(gameName);
    expect(message).toBe("failed for [redacted] while playing [redacted]");
  });

  it("scrubs non-plain class instances nested in the payload", () => {
    const steamId = "76561198000000095";
    safeLog.registerSensitive(steamId);
    class Context {
      constructor(readonly detail: string) {}
    }
    safeLog.info("message", {
      context: new Context(steamId),
    } as unknown as SafeLogPayload);

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain(steamId);
    expect(payload).toEqual({ context: { detail: "[redacted]" } });
  });

  it("scrubs custom toJSON results the underlying logger serializes", () => {
    const gameName = "Custom JSON Test Game";
    safeLog.registerSensitive(gameName);
    const holder = {
      toJSON: () => gameName,
    };
    safeLog.info("message", {
      context: holder,
    } as unknown as SafeLogPayload);

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain(gameName);
    expect(payload).toEqual({ context: "[redacted]" });
  });

  it("redacts a short numeric app ID standing on its own", () => {
    safeLog.registerSensitive("70");
    safeLog.info("message", { reason: "stopped playing 70 at the door" });

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(payload?.["reason"]).toBe("stopped playing [redacted] at the door");
  });

  it("leaves unrelated digits alone when a short app ID is registered", () => {
    // 10 is Counter-Strike's real app ID. Substituting it unbounded would
    // rewrite any later diagnostic that merely contains those two digits.
    safeLog.registerSensitive("10");
    safeLog.error("Steam request failed", {
      reason: "connect ETIMEDOUT 104.16.0.1:443 after 4100ms",
    });

    const [, payload] = vi.mocked(logger.error).mock.calls[0];
    expect(payload?.["reason"]).toBe(
      "connect ETIMEDOUT 104.16.0.1:443 after 4100ms",
    );
  });

  it("still redacts a full Steam ID, which is numeric but bounded", () => {
    const steamId = "76561198000000098";
    safeLog.registerSensitive(steamId);
    safeLog.warn(`no player for ${steamId}.`);

    const [message] = vi.mocked(logger.warn).mock.calls[0];
    expect(message).toBe("no player for [redacted].");
  });

  it("redacts a non-numeric title wherever it is embedded", () => {
    safeLog.registerSensitive("Portal");
    safeLog.info("message", { reason: "xxPortalyy" });

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(payload?.["reason"]).toBe("xx[redacted]yy");
  });

  it("leaves non-string payload values intact while scrubbing strings", () => {
    const steamId = "76561198000000096";
    safeLog.registerSensitive(steamId);
    const observedAt = new Date("2026-08-14T00:00:00.000Z");
    safeLog.info("message", {
      status: 503,
      retryable: true,
      observedAt,
      reason: `saw ${steamId}`,
    });

    const [, payload] = vi.mocked(logger.info).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain(steamId);
    expect(payload?.["status"]).toBe(503);
    expect(payload?.["retryable"]).toBe(true);
    expect(payload?.["observedAt"]).toBe(observedAt);
  });
});
