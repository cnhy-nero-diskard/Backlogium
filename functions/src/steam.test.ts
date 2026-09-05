import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}));

import * as logger from "firebase-functions/logger";
import { clearSensitiveValues } from "./safeLog";
import { fetchPresence } from "./steam";

describe("fetchPresence logging", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    clearSensitiveValues();
  });

  it("logs the unknown-player condition without the configured Steam ID", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ response: { players: [] } }),
      }),
    );

    await fetchPresence("api-key", "76561198000000000");

    expect(logger.error).toHaveBeenCalledWith(
      "Steam returned no player for the configured Steam ID — verify the ID is correct and the profile is reachable",
    );
    const [, payload] = vi.mocked(logger.error).mock.calls[0];
    expect(payload).toBeUndefined();
  });

  it("keeps the API key out of a transport failure that echoes the URL", async () => {
    // The request URL carries the key. Node's fetch does not put the URL in
    // its error text today; a runtime or a refactor that did would otherwise
    // publish the secret to Cloud Logging on every failed poll.
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(
        new Error("request to ...?key=secret-key&steamids=1 failed"),
      ),
    );

    await fetchPresence("secret-key", "76561198000000000");

    const [, payload] = vi.mocked(logger.error).mock.calls[0];
    expect(JSON.stringify(payload)).not.toContain("secret-key");
    expect(payload?.["reason"]).toContain("[redacted]");
  });

  it("still identifies a transport failure with its reason", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("timed out")));

    await fetchPresence("api-key", "76561198000000000");

    expect(logger.error).toHaveBeenCalledWith(
      "Steam request failed; leaving stored state untouched",
      { reason: "Error: timed out" },
    );
  });

  it("still identifies an error status with its status code", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 503 }));

    await fetchPresence("api-key", "76561198000000000");

    expect(logger.error).toHaveBeenCalledWith(
      "Steam returned an error status; leaving stored state untouched",
      { status: 503 },
    );
  });

  it("still identifies a private profile with its visibility state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          response: {
            players: [
              {
                steamid: "76561198000000000",
                personastate: 1,
                communityvisibilitystate: 1,
              },
            ],
          },
        }),
      }),
    );

    await fetchPresence("api-key", "76561198000000000");

    expect(logger.warn).toHaveBeenCalledWith(
      "Profile is not public — game attribution is unavailable, so presence cannot be tied to a game. Set Steam profile and Game details to Public.",
      { communityvisibilitystate: 1 },
    );
  });
});
