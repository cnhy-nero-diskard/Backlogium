import { getFirestore, Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { Observation, SCHEMA_VERSION } from "./steam";

/**
 * Firestore layout:
 *
 *   players/{steamId}                    current state (this document's fields)
 *   players/{steamId}/presence/{ISO}     append-only transition log
 *
 * Current state lives on the player document itself rather than at
 * `players/{steamId}/current`, because that path names a *collection* —
 * Firestore alternates collection/document segments, so a document cannot
 * sit directly beneath another document.
 */
const PLAYERS = "players";
const PRESENCE = "presence";

export type WriteOutcome = "unchanged" | "written";

export interface StoredState {
  gameid?: unknown;
  lastObservedAt?: unknown;
  updatedAt?: unknown;
}

function asDate(value: unknown): Date | undefined {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? undefined : value;
  }

  if (!value || typeof value !== "object") return undefined;

  const timestamp = value as {
    toDate?: unknown;
    date?: unknown;
  };

  if (typeof timestamp.toDate === "function") {
    const date = timestamp.toDate();
    return date instanceof Date && !Number.isNaN(date.getTime())
      ? date
      : undefined;
  }

  return timestamp.date instanceof Date && !Number.isNaN(timestamp.date.getTime())
    ? timestamp.date
    : undefined;
}

function isStaleOrEqualObservation(
  previous: StoredState | undefined,
  observation: Observation,
): boolean {
  const lastObservedAt = asDate(previous?.lastObservedAt);
  return (
    lastObservedAt !== undefined &&
    observation.t.getTime() <= lastObservedAt.getTime()
  );
}

/**
 * Material change means the game changed. Nothing else counts.
 *
 * `personastate` is deliberately excluded. Steam cycles an idle account
 * between online (1), away (3), and snooze (4) by itself, which carries no
 * information about what is being played. Including it filled half the log
 * with idle churn and — worse — split a continuous session into fragments
 * when the user idled mid-game.
 *
 * Excluding it guarantees something a consumer can rely on: no two adjacent
 * entries share a game ID, so every entry is a genuine game change and no
 * merge-contiguous-runs logic is needed downstream.
 */
export function isMaterialChange(
  previous: StoredState | undefined,
  observation: Observation,
): boolean {
  if (!previous) return true;

  const previousGameId =
    typeof previous.gameid === "string" ? previous.gameid : null;

  return previousGameId !== observation.gameid;
}

/**
 * Record an observation, appending a transition only when something material
 * changed. Every successful observation also advances the ordering watermark
 * on the current-state document.
 *
 * Note what is absent: no session, duration, playtime, or experience value
 * is computed or stored. This records what Steam said and nothing more —
 * derivation stays on-device, where a single author owns it.
 */
export async function recordObservation(
  steamId: string,
  observation: Observation,
): Promise<WriteOutcome> {
  const db = getFirestore();
  const playerRef = db.collection(PLAYERS).doc(steamId);
  const observedAt = Timestamp.fromDate(observation.t);

  // The ISO timestamp remains the document key so transitions sort
  // chronologically. Uniqueness comes from the transaction and
  // `isMaterialChange`: a concurrent invocation re-reads committed state and
  // writes nothing. The key is not an idempotency key; weakening this
  // transaction would re-open duplicates regardless of the key.
  const presenceRef = playerRef
    .collection(PRESENCE)
    .doc(observation.t.toISOString());

  const result = await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(playerRef);
    const previous = snapshot.exists
      ? (snapshot.data() as StoredState)
      : undefined;

    if (isStaleOrEqualObservation(previous, observation)) {
      // Never let an older or equal observation overwrite the newest state.
      return { outcome: "unchanged" as const, first: false };
    }

    if (!isMaterialChange(previous, observation)) {
      // No transition write. Refresh the raw observed fields while `since` and
      // `updatedAt` keep their stored values. `lastObservedAt` records the
      // newest successful observation so a stalled older transaction cannot
      // roll state backward.
      transaction.set(playerRef, {
        ...(snapshot.data() ?? {}),
        personastate: observation.personastate,
        gameName: observation.gameName,
        lastObservedAt: observedAt,
      });
      return { outcome: "unchanged" as const, first: false };
    }

    transaction.set(playerRef, {
      v: SCHEMA_VERSION,
      personastate: observation.personastate,
      gameid: observation.gameid,
      gameName: observation.gameName,
      lastObservedAt: observedAt,
      // `since` marks when the present state began, and is reset only on a
      // transition. Without it a consumer cannot tell a three-hour session
      // from a one-minute one.
      since: observedAt,
      updatedAt: observedAt,
    });

    transaction.set(presenceRef, {
      v: SCHEMA_VERSION,
      t: observedAt,
      personastate: observation.personastate,
      gameid: observation.gameid,
      gameName: observation.gameName,
    });

    return { outcome: "written" as const, first: previous === undefined };
  });

  if (result.outcome === "written") {
    logger.info("Recorded presence transition", {
      steamId,
      personastate: observation.personastate,
      gameid: observation.gameid,
      gameName: observation.gameName,
      first: result.first,
    });
  }

  return result.outcome;
}
