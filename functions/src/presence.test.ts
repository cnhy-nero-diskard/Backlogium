import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FakeFirestore } from "./testSupport/FakeFirestore";

const firestoreModule = vi.hoisted(() => ({
  getFirestore: vi.fn(),
  Timestamp: {
    fromDate: vi.fn((date: Date) => ({ date })),
  },
}));

vi.mock("firebase-admin/firestore", () => firestoreModule);
vi.mock("firebase-functions/logger", () => ({
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
}));

import { fetchPresence, type Observation } from "./steam";
import { isMaterialChange, recordObservation } from "./presence";

describe("presence poller", () => {
  let firestore: FakeFirestore;

  beforeEach(() => {
    firestore = new FakeFirestore();
    firestoreModule.getFirestore.mockReturnValue(firestore);
    firestoreModule.Timestamp.fromDate.mockImplementation((date: Date) => ({ date }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  describe("isMaterialChange", () => {
    it.each([
      ["no previous state", undefined, "440", true],
      ["same game", { gameid: "440" }, "440", false],
      ["different game", { gameid: "440" }, "570", true],
      ["same offline state", { gameid: null }, null, false],
      ["offline to a game", { gameid: null }, "570", true],
      ["malformed previous state remains offline", { gameid: 440 }, null, false],
      ["malformed previous state differs from a game", { gameid: 440 }, "570", true],
    ])("returns %s", (_label, previous, gameid, expected) => {
      expect(isMaterialChange(previous, observation(gameid))).toBe(expected);
    });
  });

  it("same-game poll records no transition and advances the watermark", async () => {
    const next = observation("440");
    firestore.seed("players/test-steam-id", {
      gameid: "440",
      personastate: 1,
      gameName: "Old Game 440",
      lastObservedAt: { date: new Date("2026-08-13T00:00:00.000Z") },
    });

    await expect(recordObservation("test-steam-id", next)).resolves.toBe("unchanged");
    expect(firestore.committedWrites).toHaveLength(1);
    expect(firestore.committedWrites[0].path).toBe("players/test-steam-id");
    expect(
      (firestore.committedWrites[0].data.lastObservedAt as { date: Date }).date,
    ).toEqual(next.t);
    expect(firestore.committedWrites[0].data.personastate).toBe(1);
    expect(firestore.committedWrites[0].data.gameName).toBe("Game 440");
  });

  it.each([
    ["stale", "2026-08-14T00:59:59.000Z"],
    ["equal", "2026-08-14T01:00:00.000Z"],
  ])("ignores %s observations before comparing the game", async (_label, timestamp) => {
    firestore.seed("players/test-steam-id", {
      gameid: "730",
      personastate: 1,
      lastObservedAt: { date: new Date("2026-08-14T01:00:00.000Z") },
      updatedAt: { date: new Date("2026-08-14T01:00:00.000Z") },
    });

    await expect(
      recordObservation("test-steam-id", observation("570", timestamp)),
    ).resolves.toBe("unchanged");
    expect(firestore.committedWrites).toHaveLength(0);
  });

  it("normalizes malformed current state on a non-transition poll", async () => {
    const next = observation(null, "2026-08-14T01:30:00.000Z");
    const since = { date: new Date("2026-08-13T01:00:00.000Z") };
    const updatedAt = { date: new Date("2026-08-14T01:00:00.000Z") };
    firestore.seed("players/test-steam-id", {
      v: 0,
      gameid: 440,
      personastate: 1,
      gameName: "Old Game",
      since,
      updatedAt,
      lastObservedAt: { date: new Date("2026-08-14T01:15:00.000Z") },
    });

    await expect(recordObservation("test-steam-id", next)).resolves.toBe("unchanged");

    const playerWrite = firestore.committedWrites.find(
      (write) => write.path === "players/test-steam-id",
    );
    expect(playerWrite?.data.v).toBe(1);
    expect(playerWrite?.data.gameid).toBeNull();
    expect(playerWrite?.data.personastate).toBe(1);
    expect(playerWrite?.data.gameName).toBeNull();
    expect(playerWrite?.data.since).toBe(since);
    expect(playerWrite?.data.updatedAt).toBe(updatedAt);
    expect(
      firestore.committedWrites.filter((write) =>
        write.path.startsWith("players/test-steam-id/presence/"),
      ),
    ).toHaveLength(0);
  });

  it("game-to-game transition writes both documents and resets since", async () => {
    const next = observation("570", "2026-08-14T01:02:03.000Z");
    firestore.seed("players/test-steam-id", {
      gameid: "440",
      personastate: 1,
      since: { date: new Date("2026-08-13T01:02:03.000Z") },
    });

    await expect(recordObservation("test-steam-id", next)).resolves.toBe("written");

    expect(firestore.committedWrites).toHaveLength(2);
    const playerWrite = firestore.committedWrites.find(
      (write) => write.path === "players/test-steam-id",
    );
    const presenceWrite = firestore.committedWrites.find((write) =>
      write.path.startsWith("players/test-steam-id/presence/"),
    );
    expect(playerWrite?.data.gameid).toBe("570");
    expect((playerWrite?.data.since as { date: Date }).date).toEqual(next.t);
    expect(presenceWrite?.data.gameid).toBe("570");
    expect((presenceWrite?.data.t as { date: Date }).date).toEqual(next.t);
  });

  it("game-to-offline records a transition", async () => {
    const next = observation(null, "2026-08-14T02:00:00.000Z");
    firestore.seed("players/test-steam-id", { gameid: "570", personastate: 1 });

    await expect(recordObservation("test-steam-id", next)).resolves.toBe("written");

    expect(firestore.committedWrites).toHaveLength(2);
    const playerWrite = firestore.committedWrites.find(
      (write) => write.path === "players/test-steam-id",
    );
    expect(playerWrite?.data.gameid).toBeNull();
    expect(playerWrite?.data.personastate).toBe(1);
  });

  it("persona-state-only change records no transition", async () => {
    firestore.seed("players/test-steam-id", {
      gameid: "440",
      personastate: 1,
      gameName: "Old Game 440",
      lastObservedAt: { date: new Date("2026-08-14T02:00:00.000Z") },
    });

    const next = observation("440", "2026-08-14T03:00:00.000Z", 3);
    await expect(
      recordObservation("test-steam-id", next),
    ).resolves.toBe("unchanged");
    expect(firestore.committedWrites).toHaveLength(1);
    expect(
      firestore.committedWrites[0].path,
    ).toBe("players/test-steam-id");
    expect(
      (firestore.committedWrites[0].data.lastObservedAt as { date: Date }).date,
    ).toEqual(next.t);
    expect(firestore.committedWrites[0].data.personastate).toBe(3);
    expect(firestore.committedWrites[0].data.gameName).toBe("Game 440");
  });

  it("Steam errors and timeouts return no observation and write nothing", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error("request timed out"));
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchPresence("secret", "test-steam-id")).resolves.toBeNull();
    expect(firestore.committedWrites).toHaveLength(0);
  });

  it("Steam error responses return no observation and write nothing", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 503 });
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchPresence("secret", "test-steam-id")).resolves.toBeNull();
    expect(firestore.committedWrites).toHaveLength(0);
  });

  it("overlapping invocations write one transition and advance the retry watermark", async () => {
    firestore.seed("players/test-steam-id", { gameid: "440", personastate: 1 });
    firestore.holdNextReads(2);

    const first = recordObservation(
      "test-steam-id",
      observation("570", "2026-08-14T04:00:00.000Z"),
    );
    const second = recordObservation(
      "test-steam-id",
      observation("570", "2026-08-14T04:00:01.000Z"),
    );

    await firestore.waitUntilReadsHeld();
    firestore.releaseHeldReads();
    const outcomes = await Promise.all([first, second]);
    expect([...outcomes].sort()).toEqual(["unchanged", "written"]);
    expect(firestore.transactionAttempts).toBeGreaterThan(2);
    expect(firestore.committedWrites).toHaveLength(3);
    expect(
      firestore.committedWrites.filter((write) =>
        write.path.startsWith("players/test-steam-id/presence/"),
      ),
    ).toHaveLength(1);
  });

  it("does not roll state backward when different observations commit out of order", async () => {
    firestore.seed("players/test-steam-id", {
      gameid: "440",
      personastate: 1,
      lastObservedAt: { date: new Date("2026-08-14T05:00:00.000Z") },
      updatedAt: { date: new Date("2026-08-14T05:00:00.000Z") },
    });
    firestore.holdNextReads(1);
    firestore.holdNextTransactionCommit();

    const olderPoll = recordObservation(
      "test-steam-id",
      observation("570", "2026-08-14T05:01:00.000Z"),
    );
    await firestore.waitUntilReadsHeld();
    firestore.releaseHeldReads();
    await firestore.waitUntilTransactionCommitHeld();

    const newerPoll = recordObservation(
      "test-steam-id",
      observation("730", "2026-08-14T05:02:00.000Z"),
    );
    await expect(newerPoll).resolves.toBe("written");
    firestore.releaseHeldTransactionCommit();
    await expect(olderPoll).resolves.toBe("unchanged");

    expect(firestore.transactionAttempts).toBe(3);
    expect(firestore.committedWrites).toHaveLength(2);
    const playerWrite = firestore.committedWrites.find(
      (write) => write.path === "players/test-steam-id",
    );
    expect(playerWrite?.data.gameid).toBe("730");
    expect(
      firestore.committedWrites.filter((write) =>
        write.path.startsWith("players/test-steam-id/presence/"),
      ),
    ).toHaveLength(1);
  });

  it("does not roll state backward when an older transition races a newer same-game poll", async () => {
    firestore.seed("players/test-steam-id", {
      gameid: "440",
      personastate: 1,
      lastObservedAt: { date: new Date("2026-08-14T06:00:00.000Z") },
    });
    firestore.holdNextReads(1);
    firestore.holdNextTransactionCommit();

    const olderPoll = recordObservation(
      "test-steam-id",
      observation("570", "2026-08-14T06:01:00.000Z"),
    );
    await firestore.waitUntilReadsHeld();
    firestore.releaseHeldReads();
    await firestore.waitUntilTransactionCommitHeld();

    const newerSameGamePoll = recordObservation(
      "test-steam-id",
      observation("440", "2026-08-14T06:02:00.000Z"),
    );
    await expect(newerSameGamePoll).resolves.toBe("unchanged");
    firestore.releaseHeldTransactionCommit();
    await expect(olderPoll).resolves.toBe("unchanged");

    expect(firestore.transactionAttempts).toBe(3);
    expect(firestore.committedWrites).toHaveLength(1);
    expect(firestore.committedWrites[0].path).toBe("players/test-steam-id");
    expect(
      (firestore.committedWrites[0].data.lastObservedAt as { date: Date }).date,
    ).toEqual(new Date("2026-08-14T06:02:00.000Z"));
  });
});

function observation(
  gameid: string | null,
  timestamp = "2026-08-14T00:00:00.000Z",
  personastate = 1,
): Observation {
  return {
    v: 1,
    t: new Date(timestamp),
    personastate,
    gameid,
    gameName: gameid === null ? null : `Game ${gameid}`,
  };
}
