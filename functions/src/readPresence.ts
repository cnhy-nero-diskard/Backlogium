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
  where(field: string, operator: ">" | ">=", value: unknown): QueryLike;
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
  where(field: string, operator: ">" | ">=", value: unknown): QueryLike;
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
  readonly current: PresenceReadCurrentState | null;
  readonly nextPosition: string | null;
  readonly hasMore: boolean;
  readonly windowStart: string;
  readonly windowEnd: string;
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

  const parsedPosition = parsePosition(request);
  if (parsedPosition === null) {
    response.status(400).json({ error: "invalid_position" });
    return;
  }

  safeLog.registerSensitive(expectedToken);
  try {
    const db = firestore ?? (getFirestore() as unknown as FirestoreLike);
    const player = db.collection(PLAYERS).doc(steamId);
    const position = parsedPosition;
    const query = player.collection(PRESENCE)
      .where("t", position ? ">" : ">=", Timestamp.fromDate(
        position ?? new Date(Date.now() - FIRST_READ_WINDOW_MILLIS),
      ))
      .orderBy("t", "asc");
    const resumedQuery = position ? query.startAfter(Timestamp.fromDate(position)) : query;
    const [currentSnapshot, transitionsSnapshot] = await Promise.all([
      player.get(),
      resumedQuery.limit(MAX_RESPONSE_TRANSITIONS + 1).get(),
    ]);
    const all = transitionsSnapshot.docs.map((document) => transition(document.data()));
    const hasMore = all.length > MAX_RESPONSE_TRANSITIONS;
    const transitions = all.slice(0, MAX_RESPONSE_TRANSITIONS);
    const readAt = new Date();
    const firstWindow = position ?? new Date(Date.now() - FIRST_READ_WINDOW_MILLIS);
    const lastTransition = transitions.at(-1)?.t;
    const currentState = current(
      currentSnapshot.exists ? currentSnapshot.data() : undefined,
    );
    const windowEnd = currentState?.lastObservedAt ?? lastTransition ?? readAt.toISOString();
    const result: PresenceReadResponse = {
      account: steamId,
      transitions,
      current: currentState,
      nextPosition: lastTransition ?? position?.toISOString() ?? null,
      hasMore,
      windowStart: firstWindow.toISOString(),
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
