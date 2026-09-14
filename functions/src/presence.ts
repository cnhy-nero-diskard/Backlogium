import { getFirestore, Timestamp } from "firebase-admin/firestore";
import * as safeLog from "./safeLog";
import {
  CURRENT_STATE_SCHEMA_VERSION,
  PRESENCE_TRANSITION_SCHEMA_VERSION,
  type Observation,
} from "./steam";

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
  coverageLapseFrom?: unknown;
  coverageLapseRecoveredAt?: unknown;
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
  // Scrub the account and the observed title from every log line below,
  // wherever a later call site places them.
  safeLog.registerSensitive(steamId, observation.gameid, observation.gameName);

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
    const previousLastObservedAt = previous?.lastObservedAt;
    const previousLapseFrom = previous?.coverageLapseFrom;
    const previousLapseRecoveredAt = previous?.coverageLapseRecoveredAt;

    if (isStaleOrEqualObservation(previous, observation)) {
      // Never let an older or equal observation overwrite the newest state.
      return { outcome: "unchanged" as const, first: false };
    }

    if (!isMaterialChange(previous, observation)) {
      // No transition write. Refresh the raw observed fields while `since` and
      // `updatedAt` keep their stored values. `lastObservedAt` records the
      // newest successful observation so a stalled older transaction cannot
      // roll state backward.
      //
      // A same-game observation must not erase the step that led to it by
      // advancing the watermark alone: the stored watermark and this
      // observation's time stay on the document as `coverageLapseFrom` and
      // `coverageLapseRecoveredAt`, so the later transition that closes this
      // state can report the span instead of passing the state off as
      // continuously observed. Both are raw observation timestamps, never a
      // computed gap, and no tolerance is applied here — whether a span of a
      // minute or an hour counts as a lapse is the reader's decision. The
      // retained pair is the largest consecutive-observation step seen within
      // this state: a later step replaces it only when strictly longer, and
      // ties keep the earlier pair. Keeping the first step would hide a later
      // outage behind a harmless on-cadence pair; widening across steps would
      // synthesise a span no two consecutive observations bound, and always
      // keeping the latest would hide the earlier evidence. Comparing spans
      // selects which raw pair to keep but writes no duration, so a reader
      // applying any tolerance to the retained pair reaches the same verdict
      // as if it had seen every step — max(gaps) preserves the verdict, not
      // the locations. A consumer that must exclude unconfirmed time therefore
      // discards the whole interval once the retained span exceeds its
      // tolerance, rather than excluding only the retained span. The tail
      // watermark still bounds the change itself.
      const previousWatermarkDate = asDate(previousLastObservedAt);
      const retainedFromDate = asDate(previousLapseFrom);
      const retainedRecoveredDate = asDate(previousLapseRecoveredAt);
      let carriedLapseFrom: unknown = previousLapseFrom;
      let carriedLapseRecoveredAt: unknown = previousLapseRecoveredAt;
      if (previousWatermarkDate === undefined) {
        // No new step to bound: keep a complete retained pair, else record
        // nothing rather than synthesising endpoints from different steps.
        if (
          retainedFromDate === undefined ||
          retainedRecoveredDate === undefined
        ) {
          carriedLapseFrom = undefined;
          carriedLapseRecoveredAt = undefined;
        }
      } else if (
        retainedFromDate === undefined ||
        retainedRecoveredDate === undefined
      ) {
        carriedLapseFrom = previousLastObservedAt;
        carriedLapseRecoveredAt = observedAt;
      } else {
        const retainedSpan =
          retainedRecoveredDate.getTime() - retainedFromDate.getTime();
        const currentSpan =
          observation.t.getTime() - previousWatermarkDate.getTime();
        if (currentSpan > retainedSpan) {
          carriedLapseFrom = previousLastObservedAt;
          carriedLapseRecoveredAt = observedAt;
        }
      }
      transaction.set(playerRef, {
        ...(snapshot.data() ?? {}),
        v: CURRENT_STATE_SCHEMA_VERSION,
        personastate: observation.personastate,
        gameid: observation.gameid,
        gameName: observation.gameName,
        lastObservedAt: observedAt,
        ...(carriedLapseFrom === undefined
          ? {}
          : {
              coverageLapseFrom: carriedLapseFrom,
              coverageLapseRecoveredAt: carriedLapseRecoveredAt,
            }),
      });
      return { outcome: "unchanged" as const, first: false };
    }

    transaction.set(playerRef, {
      v: CURRENT_STATE_SCHEMA_VERSION,
      personastate: observation.personastate,
      gameid: observation.gameid,
      gameName: observation.gameName,
      lastObservedAt: observedAt,
      // `since` marks when the present state began, and is reset only on a
      // transition. Without it a consumer cannot tell a three-hour session
      // from a one-minute one.
      since: observedAt,
      updatedAt: observedAt,
      // No spread: the new state starts with no retained lapse. Whatever the
      // replaced state rode out was just copied onto the transition above.
    });

    transaction.set(presenceRef, {
      v: PRESENCE_TRANSITION_SCHEMA_VERSION,
      t: observedAt,
      // The first transition has no predecessor to provide coverage for.
      ...(previousLastObservedAt === undefined
        ? {}
        : { prevLastObservedAt: previousLastObservedAt }),
      // The largest step the replaced state retained stays with the transition
      // that closes it: the state was last confirmed at the first timestamp
      // and next observed at the second, so a reader can tell an interior
      // span from a merely stale tail by its own tolerance. Absent when no
      // same-game poll ever advanced the replaced state — a direct
      // transition — exactly like the watermark.
      ...(previousLapseFrom === undefined
        ? {}
        : { prevCoverageLapseFrom: previousLapseFrom }),
      ...(previousLapseRecoveredAt === undefined
        ? {}
        : { prevCoverageLapseRecoveredAt: previousLapseRecoveredAt }),
      personastate: observation.personastate,
      gameid: observation.gameid,
      gameName: observation.gameName,
    });

    return { outcome: "written" as const, first: previous === undefined };
  });

  if (result.outcome === "written") {
    // What was played is already in Firestore, the boundary that is actually
    // access-controlled. The log does not need a shadow copy of it.
    safeLog.info("Recorded presence transition", {
      outcome: result.outcome,
      first: result.first,
    });
  }

  return result.outcome;
}
