import { getFirestore, Timestamp } from "firebase-admin/firestore";
import * as safeLog from "./safeLog";

const PLAYERS = "players";
const PRESENCE = "presence";

/** The first read is recent by design; later reads resume from their watermark. */
export const FIRST_READ_WINDOW_MILLIS = 31 * 24 * 60 * 60 * 1_000;
export const MAX_RESPONSE_TRANSITIONS = 250;

interface RequestLike {
  readonly headers?: Record<string, string | string[] | undefined>;
  get?(name: string): string | undefined;
  readonly query?: Record<string, unknown>;
}

interface ResponseLike {
  status(code: number): ResponseLike;
  json(body: unknown): unknown;
}

interface DocumentSnapshotLike {
  readonly exists: boolean;
  data(): Record<string, unknown> | undefined;
}

interface QuerySnapshotLike {
  readonly docs: readonly DocumentSnapshotLike[];
}

interface QueryLike {
  where(field: string, operator: ">" | ">=" | "<" | "<=", value: unknown): QueryLike;
  orderBy(field: string, direction: "asc" | "desc"): QueryLike;
  startAfter(value: unknown): QueryLike;
  limit(value: number): QueryLike;
  get(): Promise<QuerySnapshotLike>;
}

interface CollectionLike {
  doc(id: string): {
    get(): Promise<DocumentSnapshotLike>;
    collection(name: string): CollectionLike;
  };
  where(field: string, operator: ">" | ">=" | "<" | "<=", value: unknown): QueryLike;
  orderBy(field: string, direction: "asc" | "desc"): QueryLike;
}

interface FirestoreLike {
  collection(name: string): CollectionLike;
}

interface StoredTransition {
  readonly t?: unknown;
  readonly v?: unknown;
  readonly prevLastObservedAt?: unknown;
  readonly prevCoverageLapseFrom?: unknown;
  readonly prevCoverageLapseRecoveredAt?: unknown;
  readonly personastate?: unknown;
  readonly gameid?: unknown;
  readonly gameName?: unknown;
}

interface StoredCurrentState {
  readonly v?: unknown;
  readonly lastObservedAt?: unknown;
  readonly coverageLapseFrom?: unknown;
  readonly coverageLapseRecoveredAt?: unknown;
  readonly since?: unknown;
  readonly updatedAt?: unknown;
  readonly personastate?: unknown;
  readonly gameid?: unknown;
  readonly gameName?: unknown;
}

export interface PresenceReadTransition {
  readonly v: number | null;
  readonly t: string;
  readonly prevLastObservedAt?: string;
  readonly prevCoverageLapseFrom?: string;
  readonly prevCoverageLapseRecoveredAt?: string;
  readonly personastate: number | null;
  readonly gameid: string | null;
  readonly gameName: string | null;
}

export interface PresenceReadCurrentState {
  readonly v: number | null;
  readonly lastObservedAt?: string;
  readonly coverageLapseFrom?: string;
  readonly coverageLapseRecoveredAt?: string;
  readonly since?: string;
  readonly updatedAt?: string;
  readonly personastate: number | null;
  readonly gameid: string | null;
  readonly gameName: string | null;
}

export interface PresenceReadResponse {
  readonly account: string;
  readonly transitions: readonly PresenceReadTransition[];
  readonly predecessor?: PresenceReadTransition | null;
  readonly current: PresenceReadCurrentState | null;
  readonly nextPosition: string | null;
  readonly hasMore: boolean;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly readAt: string;
}

export interface PresenceRangeMetadataResponse {
  readonly mode: "range";
  readonly account: string;
  readonly earliestObservedAt: string | null;
  readonly current: PresenceReadCurrentState | null;
  readonly readAt: string;
}

function asDate(value: unknown): Date | undefined {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? undefined : value;
  }
  if (!value || typeof value !== "object") return undefined;
  const candidate = value as { toDate?: unknown; date?: unknown };
  if (typeof candidate.toDate === "function") {
    const date = candidate.toDate();
    return date instanceof Date && !Number.isNaN(date.getTime()) ? date : undefined;
  }
  return candidate.date instanceof Date && !Number.isNaN(candidate.date.getTime())
    ? candidate.date
    : undefined;
}

function iso(value: unknown): string | undefined {
  return asDate(value)?.toISOString();
}

function optionalNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function bearer(request: RequestLike): string | undefined {
  const header = request.get?.("authorization") ?? request.headers?.authorization;
  const value = Array.isArray(header) ? header[0] : header;
  if (typeof value !== "string") return undefined;
  const match = /^Bearer\s+(\S+)$/i.exec(value.trim());
  return match?.[1];
}

function queryValue(request: RequestLike, name: string): string | undefined {
  const value = request.query?.[name];
  if (Array.isArray(value)) return typeof value[0] === "string" ? value[0] : undefined;
  return typeof value === "string" ? value : undefined;
}

interface StrictQueryParameter {
  readonly present: boolean;
  readonly value?: string | null;
}

function strictQueryParameter(request: RequestLike, name: string): StrictQueryParameter {
  const raw = request.query?.[name];
  if (raw === undefined) return { present: false };
  return {
    present: true,
    value: typeof raw === "string" ? raw : null,
  };
}

function strictUtcInstant(value: string | null | undefined): Date | undefined {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) {
    return undefined;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) || parsed.toISOString() !== value
    ? undefined
    : parsed;
}

function parsePosition(request: RequestLike): Date | undefined | null {
  const raw = queryValue(request, "position");
  if (raw === undefined || raw === "") return undefined;
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function transition(data: Record<string, unknown> | undefined): PresenceReadTransition {
  const stored = (data ?? {}) as StoredTransition;
  const timestamp = iso(stored.t);
  if (!timestamp) throw new Error("Presence transition has no usable timestamp");
  const previous = iso(stored.prevLastObservedAt);
  const lapseFrom = iso(stored.prevCoverageLapseFrom);
  const lapseRecovered = iso(stored.prevCoverageLapseRecoveredAt);
  return {
    v: optionalNumber(stored.v),
    t: timestamp,
    ...(previous === undefined ? {} : { prevLastObservedAt: previous }),
    ...(lapseFrom === undefined ? {} : { prevCoverageLapseFrom: lapseFrom }),
    ...(lapseRecovered === undefined
      ? {}
      : { prevCoverageLapseRecoveredAt: lapseRecovered }),
    personastate: optionalNumber(stored.personastate),
    gameid: optionalString(stored.gameid),
    gameName: optionalString(stored.gameName),
  };
}

function current(data: Record<string, unknown> | undefined): PresenceReadCurrentState | null {
  if (!data) return null;
  const stored = data as StoredCurrentState;
  const lastObservedAt = iso(stored.lastObservedAt);
  const lapseFrom = iso(stored.coverageLapseFrom);
  const lapseRecovered = iso(stored.coverageLapseRecoveredAt);
  const since = iso(stored.since);
  const updatedAt = iso(stored.updatedAt);
  return {
    v: optionalNumber(stored.v),
    ...(lastObservedAt === undefined ? {} : { lastObservedAt }),
    ...(lapseFrom === undefined ? {} : { coverageLapseFrom: lapseFrom }),
    ...(lapseRecovered === undefined
      ? {}
      : { coverageLapseRecoveredAt: lapseRecovered }),
    ...(since === undefined ? {} : { since }),
    ...(updatedAt === undefined ? {} : { updatedAt }),
    personastate: optionalNumber(stored.personastate),
    gameid: optionalString(stored.gameid),
    gameName: optionalString(stored.gameName),
  };
}

function unauthorized(response: ResponseLike): void {
  response.status(401).json({ error: "unauthorized" });
}

/**
 * HTTP handler body kept separate from the Firebase trigger so authentication and query bounds
 * can be tested without deploying or opening a real Firestore connection.
 */
export async function servePresenceRead(
  request: RequestLike,
  response: ResponseLike,
  configuredToken: string | undefined,
  steamId: string,
  firestore?: FirestoreLike,
): Promise<void> {
  const expectedToken = configuredToken?.trim();
  if (!expectedToken || bearer(request) !== expectedToken) {
    unauthorized(response);
    return;
  }
  if (!steamId.trim()) {
    safeLog.error("Presence read is not configured");
    response.status(503).json({ error: "unavailable" });
    return;
  }

  const modeParameter = strictQueryParameter(request, "mode");
  const fromParameter = strictQueryParameter(request, "from");
  const throughParameter = strictQueryParameter(request, "through");
  const positionParameter = strictQueryParameter(request, "position");
  if (
    (modeParameter.present && modeParameter.value === null)
    || (fromParameter.present && fromParameter.value === null)
    || (throughParameter.present && throughParameter.value === null)
    || (positionParameter.present && positionParameter.value === null)
  ) {
    response.status(400).json({ error: "invalid_range" });
    return;
  }

  const mode = modeParameter.value;
  if (mode !== undefined && mode !== "range") {
    response.status(400).json({ error: "invalid_mode" });
    return;
  }

  const hasHistoricalBounds = fromParameter.present || throughParameter.present;
  if (mode === "range" && (hasHistoricalBounds || positionParameter.present)) {
    response.status(400).json({ error: "conflicting_range_parameters" });
    return;
  }

  if (mode === "range") {
    safeLog.registerSensitive(expectedToken, steamId);
    try {
      // Capture the server-side end before starting either lookup. The returned current
      // snapshot is therefore frozen for this metadata response and callers can reject
      // it if its observation falls after readAt.
      const readAt = new Date();
      const db = firestore ?? (getFirestore() as unknown as FirestoreLike);
      const player = db.collection(PLAYERS).doc(steamId);
      const [currentSnapshot, oldestSnapshot] = await Promise.all([
        player.get(),
        player.collection(PRESENCE).orderBy("t", "asc").limit(1).get(),
      ]);
      const currentState = current(
        currentSnapshot.exists ? currentSnapshot.data() : undefined,
      );
      const oldestTransition = oldestSnapshot.docs[0]
        ? transition(oldestSnapshot.docs[0].data()).t
        : undefined;
      const currentObservedAt = currentState?.lastObservedAt
        ? new Date(currentState.lastObservedAt).getTime()
        : Number.NaN;
      const candidateCurrentStart = currentState?.since ?? currentState?.lastObservedAt;
      const candidateCurrentStartAt = candidateCurrentStart
        ? new Date(candidateCurrentStart).getTime()
        : Number.NaN;
      const currentEvidenceStart = !Number.isNaN(currentObservedAt)
        && currentObservedAt <= readAt.getTime()
        && !Number.isNaN(candidateCurrentStartAt)
        && candidateCurrentStartAt <= readAt.getTime()
        ? candidateCurrentStart
        : undefined;
      const result: PresenceRangeMetadataResponse = {
        mode: "range",
        account: steamId,
        earliestObservedAt: oldestTransition ?? currentEvidenceStart ?? null,
        current: currentState,
        readAt: readAt.toISOString(),
      };
      safeLog.info("presence range metadata ok", {
        hasOldest: result.earliestObservedAt !== null,
      });
      response.status(200).json(result);
    } catch (error) {
      safeLog.error("Presence range metadata failed", { reason: String(error) });
      response.status(500).json({ error: "unusable_response" });
    }
    return;
  }

  let historicalFrom: Date | undefined;
  let historicalThrough: Date | undefined;
  let historicalPosition: Date | undefined;
  if (hasHistoricalBounds) {
    if (!fromParameter.present || !throughParameter.present) {
      response.status(400).json({ error: "invalid_range" });
      return;
    }
    historicalFrom = strictUtcInstant(fromParameter.value);
    historicalThrough = strictUtcInstant(throughParameter.value);
    historicalPosition = positionParameter.present
      ? strictUtcInstant(positionParameter.value)
      : undefined;
    if (
      !historicalFrom
      || !historicalThrough
      || (positionParameter.present && !historicalPosition)
      || historicalFrom.getTime() > historicalThrough.getTime()
      || historicalThrough.getTime() > Date.now()
      || (historicalPosition !== undefined && (
        historicalPosition.getTime() < historicalFrom.getTime()
        || historicalPosition.getTime() > historicalThrough.getTime()
      ))
    ) {
      response.status(400).json({ error: "invalid_range" });
      return;
    }
  }

  const parsedPosition = hasHistoricalBounds ? historicalPosition : parsePosition(request);
  if (parsedPosition === null) {
    response.status(400).json({ error: "invalid_position" });
    return;
  }

  safeLog.registerSensitive(expectedToken, steamId);
  try {
    const db = firestore ?? (getFirestore() as unknown as FirestoreLike);
    const player = db.collection(PLAYERS).doc(steamId);
    const position = parsedPosition;
    // A single cutoff drives both the Firestore lower bound and the reported window
    // start: computing them from separate Date.now() calls would let the two drift,
    // and a stale current document must not reintroduce evidence from outside it.
    const windowStartDate = historicalFrom
      ?? position
      ?? new Date(Date.now() - FIRST_READ_WINDOW_MILLIS);
    const presence = player.collection(PRESENCE);
    const query = historicalFrom && historicalThrough
      ? presence
        .where(
          "t",
          historicalPosition ? ">" : ">=",
          Timestamp.fromDate(historicalPosition ?? historicalFrom),
        )
        .where("t", "<=", Timestamp.fromDate(historicalThrough))
        .orderBy("t", "asc")
      : presence
        .where("t", position ? ">" : ">=", Timestamp.fromDate(windowStartDate))
        .orderBy("t", "asc");
    const resumedQuery = !historicalFrom && position
      ? query.startAfter(Timestamp.fromDate(position))
      : query;
    const predecessorQuery = historicalFrom && !historicalPosition
      ? presence
        .where("t", "<", Timestamp.fromDate(historicalFrom))
        .orderBy("t", "desc")
        .limit(1)
        .get()
      : Promise.resolve({ docs: [] } as QuerySnapshotLike);
    const startedAt = historicalFrom ? new Date() : undefined;
    const [currentSnapshot, transitionsSnapshot, predecessorSnapshot] = await Promise.all([
      player.get(),
      resumedQuery.limit(MAX_RESPONSE_TRANSITIONS + 1).get(),
      predecessorQuery,
    ]);
    const all = transitionsSnapshot.docs.map((document) => transition(document.data()));
    const hasMore = all.length > MAX_RESPONSE_TRANSITIONS;
    const transitions = all.slice(0, MAX_RESPONSE_TRANSITIONS);
    const readAt = startedAt ?? new Date();
    const lastTransition = transitions.at(-1)?.t;
    const storedCurrent = current(
      currentSnapshot.exists ? currentSnapshot.data() : undefined,
    );
    // A current observation older than the window boundary carries no evidence inside
    // the bounded window: presenting it would leak a stale current-only interval and
    // could invert windowEnd < windowStart. Omit it so the response stays coherent.
    const currentObservedAt = storedCurrent?.lastObservedAt
      ? new Date(storedCurrent.lastObservedAt).getTime()
      : Number.NaN;
    const currentState = historicalFrom && historicalThrough
      ? (!Number.isNaN(currentObservedAt)
        && currentObservedAt >= historicalFrom.getTime()
        && currentObservedAt <= historicalThrough.getTime()
        ? storedCurrent
        : null)
      : (Number.isNaN(currentObservedAt) || currentObservedAt >= windowStartDate.getTime()
        ? storedCurrent
        : null);
    const predecessor = historicalFrom && !historicalPosition && predecessorSnapshot.docs[0]
      ? transition(predecessorSnapshot.docs[0].data())
      : null;
    // An incomplete page must not claim the full window: when more transitions remain,
    // the window ends at the last returned transition so the page cannot masquerade as
    // complete evidence. The client resumes from nextPosition for the remainder.
    const candidateWindowEnd = hasMore
      ? (lastTransition ?? readAt.toISOString())
      : (currentState?.lastObservedAt
        ?? lastTransition
        ?? (historicalFrom ? historicalFrom.toISOString() : readAt.toISOString()));
    const windowStart = windowStartDate.toISOString();
    // Belt and braces: the reported window never runs backward, even if the caller
    // resumes from a future position.
    const windowEnd = candidateWindowEnd < windowStart ? windowStart : candidateWindowEnd;
    const result: PresenceReadResponse = {
      account: steamId,
      transitions,
      ...(historicalFrom && !historicalPosition ? { predecessor } : {}),
      current: currentState,
      nextPosition: lastTransition ?? position?.toISOString() ?? null,
      hasMore,
      windowStart,
      windowEnd,
      readAt: readAt.toISOString(),
    };
    safeLog.info("presence read ok", {
      count: transitions.length,
      hasMore,
    });
    response.status(200).json(result);
  } catch (error) {
    safeLog.error("Presence read failed", { reason: String(error) });
    response.status(500).json({ error: "unusable_response" });
  }
}
