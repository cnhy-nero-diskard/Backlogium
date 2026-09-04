import { initializeApp } from "firebase-admin/app";
import { defineSecret, defineString } from "firebase-functions/params";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { fetchPresence } from "./steam";
import { recordObservation } from "./presence";
import * as safeLog from "./safeLog";

initializeApp();

/**
 * Read from Secret Manager at runtime. The value never appears in source,
 * in function environment config, or in git history.
 */
const STEAM_API_KEY = defineSecret("STEAM_API_KEY");

/**
 * Single user, one value, changes never — configuration, not data. Storing
 * it in Firestore would imply a registration model this project does not have.
 */
const STEAM_ID = defineString("STEAM_ID");

export const pollPresence = onSchedule(
  {
    schedule: "* * * * *",
    timeZone: "Etc/UTC",
    // Must match the Firestore database location, which is permanent.
    region: "asia-southeast1",
    secrets: [STEAM_API_KEY],
    memory: "256MiB",
    maxInstances: 1,
    // Bound both container count and per-instance request concurrency.
    concurrency: 1,
    // Steam requests are bounded at 15 seconds. A 45-second ceiling leaves
    // margin for the Firestore transaction and cold starts while still
    // ending before the next scheduled poll; a poll that cannot finish in
    // time has nothing useful to report.
    timeoutSeconds: 45,
    // No retries. The next poll is 60 seconds away, so a retry buys nothing
    // and would only record a near-duplicate observation timestamp.
    retryCount: 0,
  },
  () => poll(STEAM_API_KEY.value(), STEAM_ID.value()),
);

/**
 * The scheduled handler's body, factored out so it can be exercised directly
 * with fake credentials instead of through the Cloud Scheduler trigger.
 */
export async function poll(apiKey: string, steamId: string): Promise<void> {
  if (!steamId) {
    safeLog.error("STEAM_ID is not configured; nothing to poll");
    return;
  }
  // From here on every log line is scrubbed of this value, wherever a
  // later call site places it — field value, nested object, or message.
  safeLog.registerSensitive(steamId);

  const observation = await fetchPresence(apiKey, steamId);

  if (observation === null) {
    // No information. fetchPresence has already logged why. Stored state
    // is deliberately left untouched rather than recorded as offline.
    return;
  }

  const outcome = await recordObservation(steamId, observation);

  // Liveness heartbeat. Emitted only after a successful Steam fetch AND a
  // successful Firestore interaction, so its absence means the pipeline is
  // broken somewhere — not merely that the user has not played recently.
  //
  // This remains the preferred monitoring hook even though successful polls
  // now advance `lastObservedAt`: a log-based absence alert is cheaper and
  // more direct to monitor than polling and interpreting a Firestore document.
  // Invocation count stays at a perfect 1,440/day if the Steam key is
  // revoked, since the function still runs and still returns 200.
  //
  // A metric-absence alert on this line is the monitoring hook. It carries no
  // app ID: what was played is Firestore's to hold, not Cloud Logging's.
  safeLog.info("poll ok", { outcome });
}
