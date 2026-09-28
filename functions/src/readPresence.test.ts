import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FakeFirestore } from "./testSupport/FakeFirestore";
import {
  FIRST_READ_WINDOW_MILLIS,
  MAX_RESPONSE_TRANSITIONS,
  servePresenceRead,
} from "./readPresence";

vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}));

import * as logger from "firebase-functions/logger";
import * as safeLog from "./safeLog";

interface CapturedResponse {
  readonly statusCode: number | undefined;
  readonly body: unknown;
  status(code: number): CapturedResponse;
  json(body: unknown): CapturedResponse;
}

function response(): CapturedResponse {
  let statusCode: number | undefined;
  let body: unknown;
  const captured: CapturedResponse = {
    get statusCode() {
      return statusCode;
    },
    get body() {
      return body;
    },
    status(code) {
      statusCode = code;
      return captured;
    },
    json(value) {
      body = value;
      return captured;
    },
  };
  return captured;
}

function request(authorization: string | undefined, query: Record<string, unknown> = {}) {
  return {
    headers: authorization === undefined ? {} : { authorization },
    query,
  };
}

const steamId = "76561198000000001";
const firstAt = new Date("2026-09-10T00:00:00.000Z");
const secondAt = new Date("2026-09-10T00:01:00.000Z");

function seedPresence(firestore: FakeFirestore): void {
  firestore.seed("players/" + steamId, {
    v: 3,
    lastObservedAt: secondAt,
    coverageLapseFrom: new Date("2026-09-09T23:59:00.000Z"),
    coverageLapseRecoveredAt: firstAt,
    personastate: 1,
    gameid: "440",
    gameName: "Team Fortress 2",
  });
  firestore.seed("players/" + steamId + "/presence/old", {
    t: new Date("2025-01-01T00:00:00.000Z"),
    v: 2,
    personastate: 0,
    gameid: null,
    gameName: null,
  });
  firestore.seed("players/" + steamId + "/presence/first", {
    t: firstAt,
    v: 2,
    prevLastObservedAt: new Date("2026-09-09T23:58:00.000Z"),
    prevCoverageLapseFrom: new Date("2026-09-09T23:57:00.000Z"),
    prevCoverageLapseRecoveredAt: new Date("2026-09-09T23:59:00.000Z"),
    personastate: 1,
    gameid: "440",
    gameName: "Team Fortress 2",
  });
  firestore.seed("players/" + steamId + "/presence/second", {
    t: secondAt,
    v: 2,
    personastate: 0,
    gameid: null,
    gameName: null,
  });
}

describe("servePresenceRead", () => {
  beforeEach(() => {
    safeLog.clearSensitiveValues();
  });

  afterEach(() => {
    vi.clearAllMocks();
    safeLog.clearSensitiveValues();
  });

  it.each([
    ["missing", undefined],
    ["malformed", "Basic secret"],
    ["wrong", "Bearer wrong"],
  ])("rejects %s bearer credentials before reading Firestore", async (_label, authorization) => {
    const firestore = new FakeFirestore();

    await servePresenceRead(request(authorization), response(), "secret", steamId, firestore);

    expect(firestore.readCount).toBe(0);
  });

  it("rejects every request when the server secret is absent before reading Firestore", async () => {
    const firestore = new FakeFirestore();
    const captured = response();

    await servePresenceRead(request("Bearer secret"), captured, undefined, steamId, firestore);

    expect(captured.statusCode).toBe(401);
    expect(firestore.readCount).toBe(0);
  });

  it("returns the oldest retained transition and a frozen current snapshot for range metadata", async () => {
    const firestore = new FakeFirestore();
    seedPresence(firestore);
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { mode: "range" }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    expect(captured.body).toMatchObject({
      mode: "range",
      account: steamId,
      earliestObservedAt: "2025-01-01T00:00:00.000Z",
      current: { lastObservedAt: secondAt.toISOString() },
    });
    expect(new Date((captured.body as { readAt: string }).readAt).getTime()).toBeGreaterThan(0);
    expect(firestore.readCount).toBe(2);
  });

  it("uses the current-only evidence start when no transition is retained", async () => {
    const firestore = new FakeFirestore();
    firestore.seed("players/" + steamId, {
      v: 3,
      since: firstAt,
      lastObservedAt: secondAt,
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { mode: "range" }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    expect(captured.body).toMatchObject({
      earliestObservedAt: firstAt.toISOString(),
      current: { since: firstAt.toISOString(), lastObservedAt: secondAt.toISOString() },
    });
    expect(firestore.readCount).toBe(2);
  });

  it("reports an empty range when neither transitions nor usable current evidence exist", async () => {
    const firestore = new FakeFirestore();
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { mode: "range" }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    expect(captured.body).toMatchObject({
      earliestObservedAt: null,
      current: null,
    });
    expect(firestore.readCount).toBe(2);
  });

  it("rejects unauthenticated range metadata without reading Firestore", async () => {
    const firestore = new FakeFirestore();
    const captured = response();

    await servePresenceRead(
      request(undefined, { mode: "range" }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(401);
    expect(firestore.readCount).toBe(0);
  });

  it("rejects malformed resume positions before reading Firestore", async () => {
    const firestore = new FakeFirestore();
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { position: "not-a-timestamp" }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(400);
    expect(captured.body).toEqual({ error: "invalid_position" });
    expect(firestore.readCount).toBe(0);
  });

  it.each([
    ["a missing through bound", (from: string, through: string) => ({ from })],
    ["a missing from bound", (from: string, through: string) => ({ through })],
    ["a non-UTC instant", (from: string, through: string) => ({ from: from.replace("Z", "+00:00"), through })],
    ["a non-full position instant", (from: string, through: string) => ({ from, through, position: from.slice(0, -5) + "Z" })],
    ["an impossible calendar date", (_from: string, through: string) => ({ from: "2026-02-30T00:00:00.000Z", through })],
    ["a reversed range", (from: string, _through: string) => ({ from: new Date(Date.now() - 1_000).toISOString(), through: from })],
    ["a future end", (from: string) => ({ from, through: new Date(Date.now() + 60_000).toISOString() })],
    ["a position before the range", (from: string, through: string) => ({ from, through, position: new Date(new Date(from).getTime() - 1).toISOString() })],
    ["a position after the range", (from: string, through: string) => ({ from, through, position: new Date(new Date(through).getTime() + 1).toISOString() })],
    ["a range combined with metadata mode", (from: string, through: string) => ({ mode: "range", from, through })],
    ["an unsupported read mode", () => ({ mode: "history" })],
  ])("rejects %s before reading Firestore", async (_label, makeQuery) => {
    const firestore = new FakeFirestore();
    const captured = response();
    const through = new Date(Date.now() - 30_000).toISOString();
    const from = new Date(Date.now() - 90_000).toISOString();

    await servePresenceRead(
      request("Bearer secret", makeQuery(from, through)),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(400);
    expect(firestore.readCount).toBe(0);
  });

  it("reads an explicit historical page inclusively through its fixed end", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - 2 * 60_000);
    const outsideBefore = new Date(from.getTime() - 1);
    const outsideAfter = new Date(through.getTime() + 1);
    for (const [id, at] of [
      ["before", outsideBefore],
      ["from", from],
      ["through", through],
      ["after", outsideAfter],
    ] as const) {
      firestore.seed("players/" + steamId + "/presence/" + id, {
        t: at,
        v: 2,
        personastate: 1,
        gameid: "440",
        gameName: "Team Fortress 2",
      });
    }
    // A current document beyond the confirmed end must not enter this page.
    firestore.seed("players/" + steamId, {
      lastObservedAt: outsideAfter,
      since: from,
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { from: from.toISOString(), through: through.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    const body = captured.body as {
      transitions: Array<{ t: string }>;
      current: unknown;
      windowStart: string;
      windowEnd: string;
      hasMore: boolean;
    };
    expect(body.transitions.map((item) => item.t)).toEqual([
      from.toISOString(),
      through.toISOString(),
    ]);
    expect(body.current).toBeNull();
    expect(body.windowStart).toBe(from.toISOString());
    expect(body.windowEnd).toBe(through.toISOString());
    expect(body.hasMore).toBe(false);
  });

  it("caps explicit historical pages at 250 transitions and reports the last returned position", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - (MAX_RESPONSE_TRANSITIONS + 2) * 1_000);
    for (let index = 0; index < MAX_RESPONSE_TRANSITIONS + 1; index += 1) {
      const at = new Date(from.getTime() + index * 1_000);
      firestore.seed("players/" + steamId + "/presence/historical-" + index, {
        t: at,
        v: 2,
        personastate: 1,
        gameid: "440",
        gameName: "Team Fortress 2",
      });
    }
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { from: from.toISOString(), through: through.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    const body = captured.body as {
      transitions: Array<{ t: string }>;
      hasMore: boolean;
      nextPosition: string;
      windowEnd: string;
    };
    expect(body.transitions).toHaveLength(MAX_RESPONSE_TRANSITIONS);
    expect(body.hasMore).toBe(true);
    expect(body.nextPosition).toBe(body.transitions.at(-1)?.t);
    expect(body.windowEnd).toBe(body.transitions.at(-1)?.t);
  });

  it("resumes a historical page strictly after its position", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - 3 * 60_000);
    const first = from;
    const second = new Date(from.getTime() + 60_000);
    for (const [id, at] of [["first", first], ["second", second]] as const) {
      firestore.seed("players/" + steamId + "/presence/" + id, {
        t: at,
        v: 2,
        personastate: 1,
        gameid: "440",
        gameName: "Team Fortress 2",
      });
    }
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", {
        from: from.toISOString(),
        through: through.toISOString(),
        position: first.toISOString(),
      }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect((captured.body as { transitions: Array<{ t: string }> }).transitions.map((item) => item.t))
      .toEqual([second.toISOString()]);
  });

  it("returns only the nearest raw predecessor on a historical first page", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - 3 * 60_000);
    const older = new Date(from.getTime() - 2 * 60_000);
    const predecessorAt = new Date(from.getTime() - 30_000);
    firestore.seed("players/" + steamId + "/presence/older", {
      t: older,
      v: 2,
      personastate: 0,
      gameid: null,
      gameName: null,
    });
    firestore.seed("players/" + steamId + "/presence/predecessor", {
      t: predecessorAt,
      v: 2,
      prevLastObservedAt: new Date(predecessorAt.getTime() - 60_000),
      prevCoverageLapseFrom: new Date(predecessorAt.getTime() - 30_000),
      prevCoverageLapseRecoveredAt: new Date(predecessorAt.getTime() - 10_000),
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    firestore.seed("players/" + steamId + "/presence/at-start", {
      t: from,
      v: 2,
      personastate: 0,
      gameid: null,
      gameName: null,
    });
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { from: from.toISOString(), through: through.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    expect(captured.body).toMatchObject({
      predecessor: {
        t: predecessorAt.toISOString(),
        prevLastObservedAt: new Date(predecessorAt.getTime() - 60_000).toISOString(),
        prevCoverageLapseFrom: new Date(predecessorAt.getTime() - 30_000).toISOString(),
        prevCoverageLapseRecoveredAt: new Date(predecessorAt.getTime() - 10_000).toISOString(),
      },
      transitions: [{ t: from.toISOString() }],
    });
    expect(firestore.readCount).toBe(3);
  });

  it("does not repeat the predecessor lookup on a historical continuation page", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - 3 * 60_000);
    const position = new Date(from.getTime() + 60_000);
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", {
        from: from.toISOString(),
        through: through.toISOString(),
        position: position.toISOString(),
      }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.statusCode).toBe(200);
    expect(captured.body).not.toHaveProperty("predecessor");
    expect(firestore.readCount).toBe(2);
  });

  it("uses an in-range terminal current snapshot as the historical evidence end", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 60_000);
    const from = new Date(through.getTime() - 3 * 60_000);
    const observedAt = new Date(through.getTime() - 30_000);
    const since = new Date(from.getTime() - 30_000);
    firestore.seed("players/" + steamId, {
      since,
      lastObservedAt: observedAt,
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { from: from.toISOString(), through: through.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.body).toMatchObject({
      current: { since: since.toISOString(), lastObservedAt: observedAt.toISOString() },
      windowStart: from.toISOString(),
      windowEnd: observedAt.toISOString(),
      predecessor: null,
    });
  });

  it("does not let current-only evidence beyond the fixed end extend an empty page", async () => {
    const firestore = new FakeFirestore();
    const through = new Date(Date.now() - 120_000);
    const from = new Date(through.getTime() - 3 * 60_000);
    const afterThrough = new Date(through.getTime() + 1);
    firestore.seed("players/" + steamId, {
      since: from,
      lastObservedAt: afterThrough,
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { from: from.toISOString(), through: through.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    expect(captured.body).toMatchObject({
      current: null,
      windowStart: from.toISOString(),
      windowEnd: from.toISOString(),
      predecessor: null,
    });
  });

  it("returns a recent bounded window, current state, account, and raw coverage fields", async () => {
    const firestore = new FakeFirestore();
    seedPresence(firestore);
    const captured = response();

    await servePresenceRead(request("Bearer secret"), captured, "secret", steamId, firestore);

    expect(captured.statusCode).toBe(200);
    const body = captured.body as {
      account: string;
      transitions: Array<Record<string, unknown>>;
      current: Record<string, unknown>;
      nextPosition: string;
      hasMore: boolean;
      windowStart: string;
    };
    expect(body.account).toBe(steamId);
    expect(body.transitions).toHaveLength(2);
    expect(body.transitions[0]).toMatchObject({
      t: firstAt.toISOString(),
      prevLastObservedAt: "2026-09-09T23:58:00.000Z",
      prevCoverageLapseFrom: "2026-09-09T23:57:00.000Z",
      prevCoverageLapseRecoveredAt: "2026-09-09T23:59:00.000Z",
    });
    expect(body.current).toMatchObject({
      lastObservedAt: secondAt.toISOString(),
      coverageLapseFrom: "2026-09-09T23:59:00.000Z",
      coverageLapseRecoveredAt: firstAt.toISOString(),
    });
    expect(body.nextPosition).toBe(secondAt.toISOString());
    expect(body.hasMore).toBe(false);
    expect(new Date(body.windowStart).getTime()).toBeGreaterThan(
      new Date("2025-01-01T00:00:00.000Z").getTime(),
    );
  });

  it("omits a stale current state older than the first-read window", async () => {
    // Regression: when polling has stalled for longer than the bounded window, the
    // transition query correctly returns nothing recent, but the stale current document
    // must not drive the window backward or leak a current-only interval outside it.
    const firestore = new FakeFirestore();
    const staleObservedAt = new Date(Date.now() - FIRST_READ_WINDOW_MILLIS - 24 * 60 * 60 * 1_000);
    const staleSince = new Date(staleObservedAt.getTime() - 60 * 1_000);
    firestore.seed("players/" + steamId, {
      v: 3,
      lastObservedAt: staleObservedAt,
      since: staleSince,
      personastate: 0,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    firestore.seed("players/" + steamId + "/presence/ancient", {
      t: new Date(staleObservedAt.getTime() - 24 * 60 * 60 * 1_000),
      v: 2,
      personastate: 0,
      gameid: null,
      gameName: null,
    });
    const captured = response();

    await servePresenceRead(request("Bearer secret"), captured, "secret", steamId, firestore);

    expect(captured.statusCode).toBe(200);
    const body = captured.body as {
      transitions: unknown[];
      current: unknown;
      windowStart: string;
      windowEnd: string;
      hasMore: boolean;
      nextPosition: string | null;
    };
    expect(body.transitions).toHaveLength(0);
    expect(body.current).toBeNull();
    expect(new Date(body.windowEnd).getTime()).toBeGreaterThanOrEqual(
      new Date(body.windowStart).getTime(),
    );
    expect(body.hasMore).toBe(false);
    expect(body.nextPosition).toBeNull();
  });

  it("resumes strictly after the supplied position", async () => {
    const firestore = new FakeFirestore();
    seedPresence(firestore);
    const captured = response();

    await servePresenceRead(
      request("Bearer secret", { position: firstAt.toISOString() }),
      captured,
      "secret",
      steamId,
      firestore,
    );

    const body = captured.body as { transitions: Array<{ t: string }> };
    expect(body.transitions.map((item) => item.t)).toEqual([secondAt.toISOString()]);
  });

  it("caps a response and exposes a resumable position", async () => {
    const firestore = new FakeFirestore();
    const base = new Date("2026-09-11T00:00:00.000Z");
    for (let index = 0; index < MAX_RESPONSE_TRANSITIONS + 1; index += 1) {
      const at = new Date(base.getTime() + index * 1_000);
      firestore.seed("players/" + steamId + "/presence/" + index, {
        t: at,
        v: 2,
        personastate: 1,
        gameid: "440",
        gameName: "Team Fortress 2",
      });
    }
    firestore.seed("players/" + steamId, {
      v: 2,
      lastObservedAt: new Date(base.getTime() + (MAX_RESPONSE_TRANSITIONS + 60) * 1_000),
      personastate: 1,
      gameid: "440",
      gameName: "Team Fortress 2",
    });
    const captured = response();

    await servePresenceRead(request("Bearer secret"), captured, "secret", steamId, firestore);

    const body = captured.body as {
      transitions: Array<{ t: string }>;
      hasMore: boolean;
      nextPosition: string;
      windowEnd: string;
    };
    expect(body.transitions).toHaveLength(MAX_RESPONSE_TRANSITIONS);
    expect(body.hasMore).toBe(true);
    expect(body.nextPosition).toBe(body.transitions.at(-1)?.t);
    // An incomplete page ends at its last returned transition, not at the latest
    // observation, so it cannot masquerade as full-window evidence.
    expect(body.windowEnd).toBe(body.transitions.at(-1)?.t);
  });

  it("returns an unusable response error when stored data cannot be serialized", async () => {
    const firestore = new FakeFirestore();
    firestore.seed("players/" + steamId, {
      lastObservedAt: {
        toDate: () => {
          throw new Error("bad timestamp");
        },
      },
    });
    const captured = response();

    await servePresenceRead(request("Bearer secret"), captured, "secret", steamId, firestore);

    expect(captured.statusCode).toBe(500);
    expect(captured.body).toEqual({ error: "unusable_response" });
  });

  it("scrubs the Steam ID when Firestore fails", async () => {
    // Regression: the reader runs as a separate service instance from the
    // poller, so it cannot rely on the poller's process-wide registration.
    // The store is cleared above; this handler must register the Steam ID
    // itself before any datastore failure can be logged.
    safeLog.clearSensitiveValues();
    const failingFirestore = {
      collection: () => {
        throw new Error(`lookup failed for ${steamId}`);
      },
    };
    const captured = response();

    await servePresenceRead(
      request("Bearer secret"),
      captured,
      "secret",
      steamId,
      failingFirestore as unknown as FakeFirestore,
    );

    expect(captured.statusCode).toBe(500);
    expect(captured.body).toEqual({ error: "unusable_response" });
    expect(logger.error).toHaveBeenCalled();
    expect(JSON.stringify(vi.mocked(logger.error).mock.calls)).not.toContain(
      steamId,
    );
  });
});
