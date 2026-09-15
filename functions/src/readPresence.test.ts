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
