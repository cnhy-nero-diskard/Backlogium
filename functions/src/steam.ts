import * as logger from "firebase-functions/logger";

/**
 * Schema version stamped onto every document this poller writes.
 * Bump only alongside a shape change, and teach readers the new branch.
 */
export const SCHEMA_VERSION = 1;

const ENDPOINT =
  "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";

const REQUEST_TIMEOUT_MS = 15_000;

/** Steam's `communityvisibilitystate` value meaning the profile is public. */
const VISIBILITY_PUBLIC = 3;

/**
 * A single observation of Steam presence at a point in time.
 *
 * This is raw recorded fact, never a derived value. `gameid` is kept as a
 * string because that is how Steam sends it, and because app IDs for
 * non-Steam shortcuts are synthetic 64-bit values that do not survive a
 * round trip through a JavaScript number.
 */
export interface Observation {
  readonly v: number;
  readonly t: Date;
  readonly personastate: number;
  readonly gameid: string | null;
  readonly gameName: string | null;
}

interface SteamPlayerSummary {
  steamid?: unknown;
  personastate?: unknown;
  communityvisibilitystate?: unknown;
  gameid?: unknown;
  gameextrainfo?: unknown;
}

const asString = (value: unknown): string | null =>
  typeof value === "string" && value.length > 0 ? value : null;

/**
 * Fetch current presence for one Steam ID.
 *
 * Returns `null` for "no information" — a transport failure, an error
 * status, or an unusable body. That is deliberately distinct from an
 * observation saying the user is offline: inferring offline from a failed
 * request would fabricate a session end that never happened.
 */
export async function fetchPresence(
  apiKey: string,
  steamId: string,
): Promise<Observation | null> {
  // Stamped before the request so the timestamp reflects when Steam was
  // asked, not when it happened to answer.
  const observedAt = new Date();

  const url =
    `${ENDPOINT}?key=${encodeURIComponent(apiKey)}` +
    `&steamids=${encodeURIComponent(steamId)}`;

  let response: Response;
  try {
    // NB: never log `url` — it carries the API key.
    response = await fetch(url, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    logger.error("Steam request failed; leaving stored state untouched", {
      reason: String(error),
    });
    return null;
  }

  if (!response.ok) {
    logger.error("Steam returned an error status; leaving stored state untouched", {
      status: response.status,
    });
    return null;
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    logger.error("Steam response was not JSON; leaving stored state untouched", {
      reason: String(error),
    });
    return null;
  }

  const players = (body as { response?: { players?: unknown } })?.response
    ?.players;

  if (!Array.isArray(players)) {
    logger.error("Steam response had no players array; leaving stored state untouched");
    return null;
  }

  const player = players.find(
    (candidate: SteamPlayerSummary) => asString(candidate?.steamid) === steamId,
  ) as SteamPlayerSummary | undefined;

  if (!player) {
    // An empty players array is what Steam returns for an unknown ID, and
    // also for a profile the API key cannot see at all.
    logger.error(
      "Steam returned no player for the configured Steam ID — verify the ID is correct and the profile is reachable",
      { steamId },
    );
    return null;
  }

  if (typeof player.personastate !== "number") {
    logger.error("Steam player summary had no persona state; leaving stored state untouched");
    return null;
  }

  const gameid = asString(player.gameid);
  const gameName = asString(player.gameextrainfo);

  // Task 4.5 — distinguish "not playing" from "cannot see what you are
  // playing". The endpoint gives no positive signal for the latter, but
  // `communityvisibilitystate` tells us attribution is structurally
  // unavailable, which is the actionable case: a private profile yields a
  // log full of unattributable presence.
  if (
    player.communityvisibilitystate !== VISIBILITY_PUBLIC &&
    gameid === null
  ) {
    logger.warn(
      "Profile is not public — game attribution is unavailable, so presence cannot be tied to a game. Set Steam profile and Game details to Public.",
      { communityvisibilitystate: player.communityvisibilitystate },
    );
  }

  return {
    v: SCHEMA_VERSION,
    t: observedAt,
    personastate: player.personastate,
    gameid,
    gameName,
  };
}
