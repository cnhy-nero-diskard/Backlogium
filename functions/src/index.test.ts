import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("firebase-admin/app", () => ({ initializeApp: vi.fn() }));
vi.mock("firebase-functions/params", () => ({
  defineSecret: vi.fn(() => ({ value: () => "fake-api-key" })),
  defineString: vi.fn(() => ({ value: () => "fake-steam-id" })),
}));
vi.mock("firebase-functions/v2/scheduler", () => ({
  onSchedule: vi.fn((_config: unknown, handler: () => unknown) => handler),
}));
vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}));
vi.mock("./steam", () => ({ fetchPresence: vi.fn() }));
vi.mock("./presence", () => ({ recordObservation: vi.fn() }));

import * as logger from "firebase-functions/logger";
import { fetchPresence } from "./steam";
import { recordObservation } from "./presence";
import { poll } from "./index";
import type { Observation } from "./steam";

const playing: Observation = {
  v: 1,
  t: new Date("2026-08-14T00:00:00.000Z"),
  personastate: 1,
  gameid: "440",
  gameName: "Team Fortress 2",
};

describe("poll heartbeat", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("emits the heartbeat on a successful poll and contains no app ID", async () => {
    vi.mocked(fetchPresence).mockResolvedValue(playing);
    vi.mocked(recordObservation).mockResolvedValue("written");

    await poll("fake-api-key", "fake-steam-id");

    expect(logger.info).toHaveBeenCalledWith("poll ok", { outcome: "written" });
  });

  it("still emits the heartbeat when the poll results in no write", async () => {
    vi.mocked(fetchPresence).mockResolvedValue(playing);
    vi.mocked(recordObservation).mockResolvedValue("unchanged");

    await poll("fake-api-key", "fake-steam-id");

    expect(logger.info).toHaveBeenCalledWith("poll ok", { outcome: "unchanged" });
  });

  it("suppresses the heartbeat when the Steam fetch fails", async () => {
    vi.mocked(fetchPresence).mockResolvedValue(null);

    await poll("fake-api-key", "fake-steam-id");

    expect(recordObservation).not.toHaveBeenCalled();
    expect(logger.info).not.toHaveBeenCalled();
  });

  it("logs a configuration error and does not fetch when the Steam ID is missing", async () => {
    await poll("fake-api-key", "");

    expect(fetchPresence).not.toHaveBeenCalled();
    expect(logger.error).toHaveBeenCalledWith(
      "STEAM_ID is not configured; nothing to poll",
    );
  });
});
