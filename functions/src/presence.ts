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

interface StoredState {
  personastate?: unknown;
  gameid?: unknown;
}

/**
 * Material change means the presence state or the game changed. Anything
 * else — a display name edit, an avatar change — is not a transition and
 * must not produce a log entry.
 */
function isMaterialChange(
  previous: StoredState | undefined,
  observation: Observation,
): boolean {
  if (!previous) return true;

  const previousGameId =
    typeof previous.gameid === "string" ? previous.gameid : null;

  return (
    previous.personastate !== observation.personastate ||
    previousGameId !== observation.gameid
  );
}

/**
 * Record an observation, writing only when something material changed.
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

  const snapshot = await playerRef.get();
  const previous = snapshot.exists
    ? (snapshot.data() as StoredState)
    : undefined;

  if (!isMaterialChange(previous, observation)) {
    // No write at all. `since` and `updatedAt` keep their stored values.
    return "unchanged";
  }

  const observedAt = Timestamp.fromDate(observation.t);

  // The presence document is keyed by observation time, which makes the
  // write idempotent: Cloud Scheduler delivers at least once, and a
  // redelivery for the same instant overwrites rather than appends.
  const presenceRef = playerRef
    .collection(PRESENCE)
    .doc(observation.t.toISOString());

  const batch = db.batch();

  batch.set(playerRef, {
    v: SCHEMA_VERSION,
    personastate: observation.personastate,
    gameid: observation.gameid,
    gameName: observation.gameName,
    // `since` marks when the present state began, and is reset only on a
    // transition. Without it a consumer cannot tell a three-hour session
    // from a one-minute one.
    since: observedAt,
    updatedAt: observedAt,
  });

  batch.set(presenceRef, {
    v: SCHEMA_VERSION,
    t: observedAt,
    personastate: observation.personastate,
    gameid: observation.gameid,
    gameName: observation.gameName,
  });

  await batch.commit();

  logger.info("Recorded presence transition", {
    steamId,
    personastate: observation.personastate,
    gameid: observation.gameid,
    gameName: observation.gameName,
    first: previous === undefined,
  });

  return "written";
}
